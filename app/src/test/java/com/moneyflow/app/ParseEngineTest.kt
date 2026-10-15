package com.moneyflow.app

import com.moneyflow.app.data.Direction
import com.moneyflow.app.parse.ParseEngine
import com.moneyflow.app.parse.ParseResult

/**
 * 解析引擎的 JVM 单元测试。
 *
 * 不依赖 Android 运行时，可以直接用 `gradlew test` 跑。
 * 这些用例与 tools/rule_check.py 保持一致，但这里测的是真正的 Kotlin 实现。
 */
class ParseEngineTest {

    private fun parse(text: String, pkg: String? = null) =
        ParseEngine.parse(text, pkg, System.currentTimeMillis())

    private fun assertParsed(
        text: String,
        pkg: String?,
        expectedAmount: Double,
        expectedDirection: Direction,
        label: String,
    ) {
        val result = parse(text, pkg)
        check(result is ParseResult.Success) {
            "[$label] 期望识别成功，实际：$result\n文本：$text"
        }
        check(kotlin.math.abs(result.amount - expectedAmount) < 0.001) {
            "[$label] 金额错误：期望 $expectedAmount，实际 ${result.amount}"
        }
        check(result.direction == expectedDirection) {
            "[$label] 方向错误：期望 $expectedDirection，实际 ${result.direction}"
        }
    }

    private fun assertIgnored(text: String, pkg: String?, label: String) {
        val result = parse(text, pkg)
        check(result is ParseResult.NoAmount) {
            "[$label] 期望忽略，实际识别为：$result\n文本：$text"
        }
    }

    // ── 微信 ──

    fun testWechatMerchantPayment() = assertParsed(
        "微信支付 向 星巴克(国贸店) 付款 ¥38.00", "com.tencent.mm", 38.0, Direction.EXPENSE, "微信-商户付款"
    )

    fun testWechatGenericPayment() = assertParsed(
        "微信支付-¥200.00", "com.tencent.mm", 200.0, Direction.EXPENSE, "微信-转账支出"
    )

    fun testWechatReceive() = assertParsed(
        "微信支付 收款到账 ¥88.88", "com.tencent.mm", 88.88, Direction.INCOME, "微信-收款"
    )

    fun testWechatThousandsSeparator() = assertParsed(
        "微信支付-¥1,234.56", "com.tencent.mm", 1234.56, Direction.EXPENSE, "微信-千分位"
    )

    fun testWechatFullWidthMinus() = assertParsed(
        "微信支付－¥66.00", "com.tencent.mm", 66.0, Direction.EXPENSE, "微信-全角减号"
    )

    fun testWechatNewlines() = assertParsed(
        "微信支付\n向 肯德基 付款\n¥29.90", "com.tencent.mm", 29.9, Direction.EXPENSE, "微信-换行"
    )

    fun testWechatWithdrawIsExpense() = assertParsed(
        "微信支付 零钱提现 ¥500.00", "com.tencent.mm", 500.0, Direction.EXPENSE, "微信-提现"
    )

    // ── 支付宝 ──

    fun testAlipayPay() = assertParsed(
        "支付宝 你已成功付款 ¥25.50", "com.eg.android.AlipayGphone", 25.5, Direction.EXPENSE, "支付宝-付款"
    )

    fun testAlipayMerchant() = assertParsed(
        "支付宝 向 美团外卖 付款 ¥45.00", "com.eg.android.AlipayGphone", 45.0, Direction.EXPENSE, "支付宝-商户"
    )

    fun testAlipayReceive() = assertParsed(
        "支付宝 收款到账 ¥300.00", "com.eg.android.AlipayGphone", 300.0, Direction.INCOME, "支付宝-收款"
    )

    fun testAlipayIntegerAmount() = assertParsed(
        "支付宝 付款成功 ¥100", "com.eg.android.AlipayGphone", 100.0, Direction.EXPENSE, "支付宝-整数"
    )

    // ── 银行短信 ──

    fun testSmsExpense() = assertParsed(
        "您尾号1234的储蓄卡10月14日16:42支出人民币58.00元，余额1234.56元",
        null, 58.0, Direction.EXPENSE, "短信-支出"
    )

    fun testSmsIncome() = assertParsed(
        "您尾号5678账户10月14日收入人民币5000.00元，余额8000.00元",
        null, 5000.0, Direction.INCOME, "短信-收入"
    )

    fun testSmsRepaymentIsExpense() = assertParsed(
        "您尾号1234账户还款人民币3000.00元", null, 3000.0, Direction.EXPENSE, "短信-还款"
    )

    fun testSmsWithdrawIsExpense() = assertParsed(
        "您尾号1234账户提现人民币500.00元", null, 500.0, Direction.EXPENSE, "短信-提现"
    )

    fun testSmsLargeAmount() = assertParsed(
        "您尾号1234账户支出人民币100,000.00元", null, 100000.0, Direction.EXPENSE, "短信-十万级"
    )

    fun testSmsTransferIn() = assertParsed(
        "您尾号5678账户转入人民币250.00元", null, 250.0, Direction.INCOME, "短信-转入"
    )

    // ── 必须忽略的（防误记）──

    fun testIgnoreBalanceOnly() = assertIgnored(
        "您尾号1234账户余额1234.56元", null, "短信-仅余额"
    )

    fun testIgnoreVerificationCode() = assertIgnored(
        "【工商银行】您的验证码是123456，请勿泄露", null, "验证码短信"
    )

    fun testIgnoreMarketing() = assertIgnored(
        "【招商银行】您的信用卡账单已生成，请及时查看", null, "营销短信"
    )

    fun testIgnoreChatMessage() = assertIgnored(
        "张三 发来一条消息", "com.tencent.mm", "聊天通知"
    )

    fun testIgnoreGroupMessage() = assertIgnored(
        "王五: 晚上一起吃饭吗", "com.tencent.mm", "群消息"
    )

    fun testIgnoreDeliveryNotice() = assertIgnored(
        "您的快递已到达菜鸟驿站，取件码 8-1-2345", "com.tencent.mm", "快递通知"
    )

    // ── 跨来源去重（指纹）──

    fun testFingerprintMatchesAcrossSources() {
        val at = 1_700_000_000_000L
        val fromNotification = ParseEngine.parse("微信支付-¥58.00", "com.tencent.mm", at)
        val fromSms = ParseEngine.parse("您尾号1234账户支出人民币58.00元", null, at + 30_000L)

        check(fromNotification is ParseResult.Success && fromSms is ParseResult.Success)
        // 同一笔钱的微信通知 + 银行短信，指纹应当相同（时间维度由仓储层的 ±90 秒范围负责）
        check(fromNotification.fingerprint == fromSms.fingerprint) {
            "同金额同方向的跨来源记录应产生相同指纹，用于去重"
        }
    }

    /**
     * 回归测试：这个用例对应真实设备上发现的去重失效 bug。
     *
     * 旧实现把「分钟」编进指纹，当两条记录跨分钟边界时（10:00:50 与 10:01:09），
     * 指纹不同导致去重失效，同一笔钱被记两次。
     *
     * 现在指纹不含时间，跨分钟边界的相同记录必须产生相同指纹。
     */
    fun testFingerprintStableAcrossMinuteBoundary() {
        // 构造一对跨分钟边界的时刻：相差 19 秒，但分属不同分钟
        val base = 1_700_000_000_000L
        val t1 = base - (base % 60_000L) + 50_000L   // 该分钟的 50 秒
        val t2 = t1 + 19_000L                        // 19 秒后，已进入下一分钟
        check(t1 / 60_000L != t2 / 60_000L) { "测试前提错误：两个时刻应在不同分钟桶" }

        val a = ParseEngine.parse("微信支付-¥58.00", "com.tencent.mm", t1)
        val b = ParseEngine.parse("微信支付-¥58.00", "com.tencent.mm", t2)
        check(a is ParseResult.Success && b is ParseResult.Success)
        check(a.fingerprint == b.fingerprint) {
            "跨分钟边界的相同记录必须产生相同指纹，否则去重失效（回归 bug）"
        }
    }

    fun testFingerprintDiffersByAmount() {
        val at = 1_700_000_000_000L
        val a = ParseEngine.parse("微信支付-¥58.00", "com.tencent.mm", at)
        val b = ParseEngine.parse("微信支付-¥59.00", "com.tencent.mm", at)
        check(a is ParseResult.Success && b is ParseResult.Success)
        check(a.fingerprint != b.fingerprint) { "不同金额不应被去重" }
    }

    fun testFingerprintDiffersByDirection() {
        val at = 1_700_000_000_000L
        val expense = ParseEngine.parse("微信支付-¥58.00", "com.tencent.mm", at)
        val income = ParseEngine.parse("微信支付 收款到账 ¥58.00", "com.tencent.mm", at)
        check(expense is ParseResult.Success && income is ParseResult.Success)
        check(expense.fingerprint != income.fingerprint) {
            "同金额但方向不同（一收一支）不应被去重"
        }
    }

    /** 全部用例入口，供手写 runner 调用 */
    fun runAll(): List<String> {
        val failures = mutableListOf<String>()
        val tests: List<Pair<String, () -> Unit>> = listOf(
            "微信-商户付款" to ::testWechatMerchantPayment,
            "微信-转账支出" to ::testWechatGenericPayment,
            "微信-收款" to ::testWechatReceive,
            "微信-千分位" to ::testWechatThousandsSeparator,
            "微信-全角减号" to ::testWechatFullWidthMinus,
            "微信-换行" to ::testWechatNewlines,
            "微信-提现为支出" to ::testWechatWithdrawIsExpense,
            "支付宝-付款" to ::testAlipayPay,
            "支付宝-商户" to ::testAlipayMerchant,
            "支付宝-收款" to ::testAlipayReceive,
            "支付宝-整数" to ::testAlipayIntegerAmount,
            "短信-支出" to ::testSmsExpense,
            "短信-收入" to ::testSmsIncome,
            "短信-还款为支出" to ::testSmsRepaymentIsExpense,
            "短信-提现为支出" to ::testSmsWithdrawIsExpense,
            "短信-十万级" to ::testSmsLargeAmount,
            "短信-转入" to ::testSmsTransferIn,
            "忽略-仅余额" to ::testIgnoreBalanceOnly,
            "忽略-验证码" to ::testIgnoreVerificationCode,
            "忽略-营销短信" to ::testIgnoreMarketing,
            "忽略-聊天通知" to ::testIgnoreChatMessage,
            "忽略-群消息" to ::testIgnoreGroupMessage,
            "忽略-快递通知" to ::testIgnoreDeliveryNotice,
            "指纹-跨来源一致" to ::testFingerprintMatchesAcrossSources,
            "指纹-跨分钟边界稳定" to ::testFingerprintStableAcrossMinuteBoundary,
            "指纹-金额不同则不同" to ::testFingerprintDiffersByAmount,
            "指纹-方向不同则不同" to ::testFingerprintDiffersByDirection,
        )
        for ((name, fn) in tests) {
            try {
                fn()
            } catch (t: Throwable) {
                failures += "$name: ${t.message}"
            }
        }
        return failures
    }
}

/** 独立入口：java -cp ... com.moneyflow.app.ParseEngineTestKt */
fun main() {
    val failures = ParseEngineTest().runAll()
    val total = 27
    if (failures.isEmpty()) {
        println("[OK] 全部 $total 个用例通过")
    } else {
        println("[FAIL] ${failures.size}/$total 个用例失败：")
        failures.forEach { println("  - $it") }
    }
}
