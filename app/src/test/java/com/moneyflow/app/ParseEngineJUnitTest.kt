package com.moneyflow.app

import com.moneyflow.app.data.Direction
import com.moneyflow.app.parse.ParseEngine
import com.moneyflow.app.parse.ParseResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 解析引擎的 JVM 单元测试（标准 JUnit，`gradlew test` 会自动执行）。
 *
 * 与手写 runner 的 ParseEngineTest 是两套：
 * - 这个用标准 JUnit，能被 Gradle 和 CI 自动发现
 * - ParseEngineTest 是手写 runner，方便在没有测试框架时直接跑
 * 两者用例互补，都保留。
 */
class ParseEngineJUnitTest {

    private fun parse(text: String, pkg: String? = null) =
        ParseEngine.parse(text, pkg, 1_700_000_000_000L)

    private fun assertParsed(
        text: String,
        pkg: String?,
        expectedAmount: Double,
        expectedDirection: Direction,
    ) {
        val r = parse(text, pkg)
        assertTrue("应识别成功，实际=$r，文本=$text", r is ParseResult.Success)
        r as ParseResult.Success
        assertEquals("金额不符（$text）", expectedAmount, r.amount, 0.001)
        assertEquals("方向不符（$text）", expectedDirection, r.direction)
    }

    private fun assertIgnored(text: String, pkg: String? = null) {
        val r = parse(text, pkg)
        assertTrue("应被忽略，实际=$r，文本=$text", r is ParseResult.NoAmount)
    }

    // ── 微信 ──

    @Test
    fun wechatMerchantPayment() =
        assertParsed("微信支付 向 星巴克(国贸店) 付款 ¥38.00", "com.tencent.mm", 38.0, Direction.EXPENSE)

    @Test
    fun wechatGenericPayment() =
        assertParsed("微信支付-¥200.00", "com.tencent.mm", 200.0, Direction.EXPENSE)

    @Test
    fun wechatReceive() =
        assertParsed("微信支付 收款到账 ¥88.88", "com.tencent.mm", 88.88, Direction.INCOME)

    @Test
    fun wechatThousandsSeparator() =
        assertParsed("微信支付-¥1,234.56", "com.tencent.mm", 1234.56, Direction.EXPENSE)

    @Test
    fun wechatFullWidthMinus() =
        assertParsed("微信支付－¥66.00", "com.tencent.mm", 66.0, Direction.EXPENSE)

    @Test
    fun wechatNewlinesNormalized() =
        assertParsed("微信支付\n向 肯德基 付款\n¥29.90", "com.tencent.mm", 29.9, Direction.EXPENSE)

    @Test
    fun wechatFullWidthSpaces() =
        assertParsed("微信支付\u3000向\u3000滴滴出行\u3000付款\u3000¥18.50", "com.tencent.mm", 18.5, Direction.EXPENSE)

    @Test
    fun wechatWithdrawIsExpense() =
        // 零钱提现 = 零钱账户少了一笔 = 支出（曾经记成「中性」，统计上等于没记）
        assertParsed("微信支付 零钱提现 ¥500.00", "com.tencent.mm", 500.0, Direction.EXPENSE)

    // ── 回归：不要把「收款方」当成收入（真机复现过的方向记反 bug）──
    //
    // 旧的 wechat_receive 规则是 `(?:收款|到账|收到).*?([\d,]+\.?\d*)`。
    // 微信的**付款**通知里会写「收款方：星巴克 ¥38.50」——
    // 「收款方」是名词（指商家），整句语义是**支出**。
    // 但裸「收款」命中它，且该规则优先级(20)高于 wechat_pay_merchant(15)，
    // 于是这笔支出被记成了**收入**，统计彻底反向。
    //
    // 这条测试守住修复：付款语义的通知绝不能被判成收入。

    @Test
    fun wechatPayeeLabelIsNotMistakenForIncome() {
        val r = parse("微信支付 收款方：星巴克 ¥38.50", "com.tencent.mm")
        assertTrue("「收款方」是支出语义，不能记成收入，实际=$r", r is ParseResult.Success)
        r as ParseResult.Success
        assertEquals("金额应为 38.50", 38.5, r.amount, 0.001)
        assertEquals("「收款方」必须判为支出", Direction.EXPENSE, r.direction)
    }

    @Test
    fun wechatPayeeLabelVariantsStayExpense() {
        // 各种带「收款方/收款账户」字样的付款通知，都必须是支出
        val cases = listOf(
            "微信支付 收款方：星巴克 ¥38.50",
            "微信支付 收款方 美团外卖 ¥25.00",
            "微信支付 收款账户：某某商户 ¥12.00",
        )
        for (text in cases) {
            val r = parse(text, "com.tencent.mm")
            assertTrue("应识别成功，文本=$text 实际=$r", r is ParseResult.Success)
            r as ParseResult.Success
            assertEquals("「收款方」类文本必须判为支出，文本=$text", Direction.EXPENSE, r.direction)
        }
    }

    @Test
    fun alipayPayeeLabelIsNotMistakenForIncome() {
        // 支付宝同样有「收款方」字样，同样的坑
        val r = parse("支付宝 收款方：某某商家 ¥30.00", "com.eg.android.AlipayGphone")
        if (r is ParseResult.Success) {
            assertEquals("「收款方」必须判为支出", Direction.EXPENSE, r.direction)
        }
    }

    @Test
    fun realIncomePhrasingsStillWork() {
        // 修复不能把真正的收入也堵死 —— 这些是真实的入账写法
        val cases = listOf(
            "微信支付 收款到账 ¥88.88" to 88.88,
            "微信支付 已收款 ¥50.00" to 50.0,
            "微信支付 收到转账 ¥20.00" to 20.0,
            "微信支付 到账 ¥100.00" to 100.0,
        )
        for ((text, amount) in cases) {
            val r = parse(text, "com.tencent.mm")
            assertTrue("真实入账应识别成功，文本=$text 实际=$r", r is ParseResult.Success)
            r as ParseResult.Success
            assertEquals("金额不符，文本=$text", amount, r.amount, 0.001)
            assertEquals("应判为收入，文本=$text", Direction.INCOME, r.direction)
        }
    }

    // ── 回归：金额必须紧跟动词，不能被手续费/订单号骗走 ──

    @Test
    fun withdrawDoesNotCaptureFeeOrOrderNumber() {
        // 提现通知里常先出现「手续费 0.10 元」，再是「到账 500.00 元」。
        // 旧规则用 .*? 会把手续费当成提现金额（金额错成 0.10）。
        val r = parse("微信支付 零钱提现 手续费 0.10 元 到账 500.00 元", "com.tencent.mm")
        assertTrue("应识别成功，实际=$r", r is ParseResult.Success)
        r as ParseResult.Success
        assertEquals("必须取到账金额 500.00，而不是手续费 0.10", 500.0, r.amount, 0.001)
        assertEquals("提现是钱离开本账户，方向应为支出", Direction.EXPENSE, r.direction)
    }

    // ── 支付宝 ──

    @Test
    fun alipayPay() =
        assertParsed("支付宝 你已成功付款 ¥25.50", "com.eg.android.AlipayGphone", 25.5, Direction.EXPENSE)

    @Test
    fun alipayMerchant() =
        assertParsed("支付宝 向 美团外卖 付款 ¥45.00", "com.eg.android.AlipayGphone", 45.0, Direction.EXPENSE)

    @Test
    fun alipayReceive() =
        assertParsed("支付宝 收款到账 ¥300.00", "com.eg.android.AlipayGphone", 300.0, Direction.INCOME)

    @Test
    fun alipayIntegerAmount() =
        assertParsed("支付宝 付款成功 ¥100", "com.eg.android.AlipayGphone", 100.0, Direction.EXPENSE)

    // ── 银行短信 ──

    @Test
    fun smsExpenseIgnoresBalance() =
        assertParsed(
            "您尾号1234的储蓄卡10月14日16:42支出人民币58.00元，余额1234.56元",
            null, 58.0, Direction.EXPENSE,
        )

    @Test
    fun smsIncome() =
        assertParsed("您尾号5678账户10月14日收入人民币5000.00元，余额8000.00元", null, 5000.0, Direction.INCOME)

    @Test
    fun smsRepaymentIsExpense() =
        // 还款 = 钱离开本账户去还信用卡 = 支出
        assertParsed("您尾号1234账户还款人民币3000.00元", null, 3000.0, Direction.EXPENSE)

    @Test
    fun smsWithdrawIsExpense() =
        assertParsed("您尾号1234账户提现人民币500.00元", null, 500.0, Direction.EXPENSE)

    @Test
    fun smsTransferIn() =
        assertParsed("您尾号5678账户转入人民币250.00元", null, 250.0, Direction.INCOME)

    @Test
    fun smsLargeAmount() =
        assertParsed("您尾号1234账户支出人民币100,000.00元", null, 100000.0, Direction.EXPENSE)

    @Test
    fun smsRefundIsIncome() =
        assertParsed("您尾号1234账户退款人民币200.00元", null, 200.0, Direction.INCOME)

    // ── 必须忽略（防误记）──

    @Test
    fun ignoreBalanceOnly() = assertIgnored("您尾号1234账户余额1234.56元")

    @Test
    fun ignoreVerificationCode() = assertIgnored("【工商银行】您的验证码是123456，请勿泄露")

    @Test
    fun ignoreMarketing() = assertIgnored("【招商银行】您的信用卡账单已生成，请及时查看")

    @Test
    fun ignoreChatMessage() = assertIgnored("张三 发来一条消息", "com.tencent.mm")

    @Test
    fun ignoreGroupMessage() = assertIgnored("王五: 晚上一起吃饭吗", "com.tencent.mm")

    @Test
    fun ignoreDeliveryNotice() = assertIgnored("您的快递已到达菜鸟驿站，取件码 8-1-2345", "com.tencent.mm")

    @Test
    fun ignoreBlank() = assertIgnored("")

    @Test
    fun ignorePlainNumberWithoutContext() = assertIgnored("12345")

    // ── 包名隔离：别的 App 的支付字样不该被当成微信 ──

    @Test
    fun wechatRuleDoesNotFireForOtherPackage() {
        // 「微信支付」字样出现在非微信包名里时，不应命中微信专属规则。
        // 这里验证的是包名过滤确实生效：结果即便被兜底规则接住，
        // 来源也不会是「微信支付」。
        val r = parse("微信支付-¥58.00", "com.example.someother.app")
        if (r is ParseResult.Success) {
            assertTrue(
                "非微信包名不应命中微信专属规则，实际来源=${r.sourceName}",
                r.sourceName != "微信支付",
            )
        }
    }

    @Test
    fun smsRulesFireRegardlessOfPackage() {
        // 短信没有包名，不限制包名的规则必须能命中
        assertParsed("您尾号1234账户支出人民币58.00元", null, 58.0, Direction.EXPENSE)
    }

    // ── 指纹 ──

    @Test
    fun fingerprintStableAcrossMinuteBoundary() {
        val base = 1_700_000_000_000L
        val t1 = base - (base % 60_000L) + 50_000L
        val t2 = t1 + 19_000L
        assertTrue("测试前提：应跨分钟", t1 / 60_000L != t2 / 60_000L)

        val a = parse("微信支付-¥58.00", "com.tencent.mm") as ParseResult.Success
        val b = ParseEngine.parse("微信支付-¥58.00", "com.tencent.mm", t2) as ParseResult.Success
        assertEquals("跨分钟边界指纹必须一致（回归 bug）", a.fingerprint, b.fingerprint)
    }

    @Test
    fun fingerprintSameAcrossSources() {
        val t = 1_700_000_000_000L
        val notif = ParseEngine.parse("微信支付-¥58.00", "com.tencent.mm", t) as ParseResult.Success
        val sms = ParseEngine.parse("您尾号1234账户支出人民币58.00元", null, t) as ParseResult.Success
        assertEquals("跨来源同金额应指纹一致，才能去重", notif.fingerprint, sms.fingerprint)
    }

    @Test
    fun fingerprintDiffersByAmount() {
        val t = 1_700_000_000_000L
        val a = ParseEngine.parse("微信支付-¥58.00", "com.tencent.mm", t) as ParseResult.Success
        val b = ParseEngine.parse("微信支付-¥59.00", "com.tencent.mm", t) as ParseResult.Success
        assertTrue(a.fingerprint != b.fingerprint)
    }

    @Test
    fun fingerprintDiffersByDirection() {
        val t = 1_700_000_000_000L
        val e = ParseEngine.parse("微信支付-¥58.00", "com.tencent.mm", t) as ParseResult.Success
        val i = ParseEngine.parse("微信支付 收款到账 ¥58.00", "com.tencent.mm", t) as ParseResult.Success
        assertTrue(e.fingerprint != i.fingerprint)
    }

    // ── 商户提取 ──

    @Test
    fun merchantExtractedFromPayment() {
        val r = parse("微信支付 向 星巴克(国贸店) 付款 ¥38.00", "com.tencent.mm") as ParseResult.Success
        assertEquals("星巴克(国贸店)", r.merchant)
    }

    @Test
    fun merchantExtractedFromAlipay() {
        val r = parse("支付宝 向 美团外卖 付款 ¥45.00", "com.eg.android.AlipayGphone") as ParseResult.Success
        assertEquals("美团外卖", r.merchant)
    }
}
