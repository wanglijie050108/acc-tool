package com.moneyflow.app

import com.moneyflow.app.data.Direction
import com.moneyflow.app.parse.ParseEngine
import com.moneyflow.app.parse.ParseResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 真实银行短信格式测试。
 *
 * ── 这些样本从哪来 ──
 * 来自公开的银行短信模板汇总（覆盖 10 家银行）。
 * 是外部资料，仅作为解析引擎的测试输入。
 *
 * ── 为什么必须有这个测试类 ──
 * 之前只用手写的「标准格式」测过，结果真实覆盖率只有 9/19。
 * 真实格式的差异非常大：
 *   - 招行金额后没有「元」字
 *   - 平安用 RMB 而不是「人民币」
 *   - 农行用负号表示支出
 *   - 工行用全角冒号
 *   - 动词有 存款/取款/支取/现支/入账 等十几种
 *
 * 补齐规则后覆盖率达到 19/19。
 */
class RealBankSmsTest {

    private fun parse(text: String) =
        ParseEngine.parse(text, null, 1_700_000_000_000L)

    private fun assertParsed(
        text: String,
        expectedAmount: Double,
        expectedDirection: Direction,
        bank: String,
    ) {
        val r = parse(text)
        assertTrue("[$bank] 应识别成功，实际=$r\n原文：$text", r is ParseResult.Success)
        r as ParseResult.Success
        assertEquals("[$bank] 金额不符", expectedAmount, r.amount, 0.001)
        assertEquals("[$bank] 方向不符", expectedDirection, r.direction)
    }

    // ── 招商银行：金额后没有「元」字 ──

    @Test
    fun cmbIncomeWithoutYuanSuffix() = assertParsed(
        "您账户1523于11月01日21:23ATM存款人民币8000.00，余额8402.39[招商银行]",
        8000.0, Direction.INCOME, "招商银行",
    )

    @Test
    fun cmbQuickPayDeduction() = assertParsed(
        "您账户1523于10月03日21:05直付通/快捷支付扣款人民币99.50，余额1123.59[招商银行]",
        99.50, Direction.EXPENSE, "招商银行",
    )

    // ── 光大银行 ──

    @Test
    fun cebTransferIn() = assertParsed(
        "xx向您尾号6469账户15:24转入8000元，余额为8654.78元，摘要:网银跨行汇款。[光大银行]",
        8000.0, Direction.INCOME, "光大银行",
    )

    @Test
    fun cebExpense() = assertParsed(
        "尾号6469账户11:56支出32.34元，余额88.74元，摘要:网上支付 支付宝 支付宝[光大银行]",
        32.34, Direction.EXPENSE, "光大银行",
    )

    // ── 工商银行：全角冒号 ──

    @Test
    fun icbcIncome() = assertParsed(
        "你尾号8888卡12日19：30工商银行收入10元，余额10.00元。【工商银行】",
        10.0, Direction.INCOME, "工商银行",
    )

    @Test
    fun icbcPosExpense() = assertParsed(
        "你尾号8888卡12日19：30POS支出10元，余额10.00元。【工商银行】",
        10.0, Direction.EXPENSE, "工商银行",
    )

    @Test
    fun icbcPosConsumption() = assertParsed(
        "你尾号8888卡12日19：30POS消费10元，余额10.00元。【工商银行】",
        10.0, Direction.EXPENSE, "工商银行",
    )

    // ── 中国银行：动词是「支取」「存入」 ──

    @Test
    fun bocDeposit() = assertParsed(
        "您的个人账户8888于2014-11-03 13:48:43 存入人民币1000.00元，交易后余额5000.00元【中国银行】",
        1000.0, Direction.INCOME, "中国银行",
    )

    @Test
    fun bocWithdrawByVerbZhiQu() = assertParsed(
        "您的个人账户8888于2014-07-31 10:09:50 网银支取人民币8888.00元，交易后余额123.00元【中国银行】",
        8888.0, Direction.EXPENSE, "中国银行",
    )

    // ── 建设银行 ──

    @Test
    fun ccbAtmDeposit() = assertParsed(
        "您尾号8199的储蓄卡账户10月26日14时3分ATM存款收入人民币2786.07元,活期余额3443.16元。[建设银行]",
        2786.07, Direction.INCOME, "建设银行",
    )

    @Test
    fun ccbAtmWithdrawExpense() = assertParsed(
        "您尾号8199的储蓄卡账户11月5日16时1分ATM取款支出人民币300.00元,活期余额71.16元。[建设银行]",
        300.0, Direction.EXPENSE, "建设银行",
    )

    // ── 平安银行：用 RMB ──

    @Test
    fun pabDepositWithRmb() = assertParsed(
        "10月30日11时03分,账户*[9002]存RMB485,000.00元成功，现余额561,752.68元【平安银行】",
        485000.0, Direction.INCOME, "平安银行",
    )

    @Test
    fun pabWithdrawWithRmb() = assertParsed(
        "11月03日09时51分,账户*[9002]取RMB3,700.00元成功，现余额6,749.68元【平安银行】",
        3700.0, Direction.EXPENSE, "平安银行",
    )

    // ── 成都银行 ──

    @Test
    fun cdbAtmDeposit() = assertParsed(
        "【成都银行】您尾号为2352的卡于10月7日16时57分行内ATM存款人民币1,500元。公交次卡、电卡充值，我行营业点一站搞定",
        1500.0, Direction.INCOME, "成都银行",
    )

    @Test
    fun cdbCurrentWithdraw() = assertParsed(
        "【成都银行】您尾号为2352的卡于10月17日19时13分活期取款人民币700元。49天理财在售预期年化收益5-5.1%",
        700.0, Direction.EXPENSE, "成都银行",
    )

    // ── 德阳银行 ──

    @Test
    fun dybSmallCredit() = assertParsed(
        "【德阳银行】您尾号4625账户于10月15日 17:04小额入账人民币2092.82元。余额2094.98元。随心贷助您圆梦之贷",
        2092.82, Direction.INCOME, "德阳银行",
    )

    @Test
    fun dybAtmWithdraw() = assertParsed(
        "【德阳银行】您尾号4625账户于10月26日 10:38ATM取款人民币500.00元。余额1157.30元。开个人网银 移动银行UK免费送",
        500.0, Direction.EXPENSE, "德阳银行",
    )

    // ── 民生银行：￥ 符号 ──

    @Test
    fun cmbcExpenseWithYenSign() = assertParsed(
        "账户*8844于11月04日08:13支出￥100.00元，可用余额9800.00元。取款:86310138成都市红星中路四段199号蜀源大厦。【民生银行】",
        100.0, Direction.EXPENSE, "民生银行",
    )

    // ── 农业银行：负号表示支出 ──

    @Test
    fun abcNegativeAmountIsExpense() = assertParsed(
        "【四川农行】您好:您的账户6228***1813于20141012现支(分行营业部)-1400.00元,余额498.58元",
        1400.0, Direction.EXPENSE, "农业银行",
    )

    // ── 回归：宽松的短信规则不能抢走微信/支付宝的匹配 ──

    /**
     * 回归测试：smsOnly 标志的作用。
     *
     * 银行短信规则写得很宽松（金额后「元」可选、动词十几种），
     * 一旦让它们匹配 App 通知，就会因优先级更高而抢走匹配，
     * 导致商户名丢失、来源标错。这个 bug 真实发生过。
     */
    @Test
    fun wechatMerchantRuleNotStolenByBroadSmsRule() {
        val r = ParseEngine.parse(
            "微信支付 向 星巴克(国贸店) 付款 ¥38.00",
            "com.tencent.mm",
            1_700_000_000_000L,
        )
        assertTrue(r is ParseResult.Success)
        r as ParseResult.Success
        assertEquals("商户名必须能提取到", "星巴克(国贸店)", r.merchant)
    }

    @Test
    fun alipayMerchantRuleNotStolenByBroadSmsRule() {
        val r = ParseEngine.parse(
            "支付宝 向 美团外卖 付款 ¥45.00",
            "com.eg.android.AlipayGphone",
            1_700_000_000_000L,
        )
        assertTrue(r is ParseResult.Success)
        r as ParseResult.Success
        assertEquals("商户名必须能提取到", "美团外卖", r.merchant)
    }

    @Test
    fun smsRuleStillWorksWhenPackageIsNull() {
        // 短信通道下，smsOnly 规则必须正常工作
        val r = parse("您尾号1234账户支出人民币58.00元")
        assertTrue(r is ParseResult.Success)
    }
}
