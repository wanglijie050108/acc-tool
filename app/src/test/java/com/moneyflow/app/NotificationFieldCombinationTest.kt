package com.moneyflow.app

import com.moneyflow.app.data.Direction
import com.moneyflow.app.parse.ParseEngine
import com.moneyflow.app.parse.ParseResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 真实通知字段组合的测试。
 *
 * 真机上，微信/支付宝的通知文本不一定都在 EXTRA_TEXT 里：
 * 有时标题是「微信支付」，正文是金额；有时金额藏在 EXTRA_BIG_TEXT。
 * NotificationCaptureService 会把 title/text/bigText/subText 用空格拼起来再解析。
 *
 * 这个类专门验证「拼接后的文本」能被正确解析 —— 模拟真机上的字段组合。
 */
class NotificationFieldCombinationTest {

    /** 复刻 NotificationCaptureService 的字段合并逻辑 */
    private fun combine(vararg fields: String): String =
        fields.filter { it.isNotBlank() }.distinct().joinToString(" ")

    private fun parseCombined(vararg fields: String, pkg: String? = "com.tencent.mm"): ParseResult =
        ParseEngine.parse(combine(*fields), pkg, 1_700_000_000_000L)

    private fun assertParsed(
        expectedAmount: Double,
        expectedDirection: Direction,
        pkg: String?,
        vararg fields: String,
    ) {
        val r = ParseEngine.parse(combine(*fields), pkg, 1_700_000_000_000L)
        assertTrue("应识别成功，实际=$r，字段=${fields.toList()}", r is ParseResult.Success)
        r as ParseResult.Success
        assertEquals("金额不符", expectedAmount, r.amount, 0.001)
        assertEquals("方向不符", expectedDirection, r.direction)
    }

    @Test
    fun titleAndTextSplit() =
        assertParsed(38.0, Direction.EXPENSE, "com.tencent.mm", "微信支付", "向 星巴克 付款 ¥38.00")

    @Test
    fun amountOnlyInBigText() =
        assertParsed(58.0, Direction.EXPENSE, "com.tencent.mm", "微信支付", "查看详情", "微信支付-¥58.00")

    @Test
    fun amountInSubText() =
        assertParsed(25.5, Direction.EXPENSE, "com.eg.android.AlipayGphone", "支付宝", "付款成功", "¥25.50")

    @Test
    fun titleOnly() =
        assertParsed(58.0, Direction.EXPENSE, "com.tencent.mm", "微信支付-¥58.00")

    @Test
    fun duplicateFieldsAreDeduplicated() =
        assertParsed(58.0, Direction.EXPENSE, "com.tencent.mm", "微信支付-¥58.00", "微信支付-¥58.00")

    @Test
    fun blankFieldsIgnored() =
        assertParsed(58.0, Direction.EXPENSE, "com.tencent.mm", "", "微信支付-¥58.00", "")

    @Test
    fun smsHasNoPackageAndNoTitle() =
        assertParsed(128.0, Direction.EXPENSE, null, "您尾号1234账户支出人民币128.00元")

    @Test
    fun balanceInSameNotificationDoesNotWin() =
        assertParsed(
            58.0, Direction.EXPENSE, null,
            "您尾号1234的储蓄卡支出人民币58.00元，余额1234.56元",
        )

    @Test
    fun incomeNotificationWithTitle() =
        assertParsed(88.88, Direction.INCOME, "com.tencent.mm", "微信支付", "收款到账 ¥88.88")

    // ── 容易误记的组合 ──

    @Test
    fun chatMessageWithNumbersIsIgnored() {
        val r = ParseEngine.parse(
            combine("张三", "明天还你 100 块钱"),
            "com.tencent.mm",
            1_700_000_000_000L,
        )
        assertTrue("聊天消息含数字但不含支付符号，不应记账，实际=$r", r is ParseResult.NoAmount)
    }

    @Test
    fun balanceOnlyNotificationIgnored() {
        val r = ParseEngine.parse(
            combine("招商银行", "您尾号1234账户余额1234.56元"),
            null,
            1_700_000_000_000L,
        )
        assertTrue("只有余额变动不算流水，实际=$r", r is ParseResult.NoAmount)
    }

    @Test
    fun verificationCodeWithAmountLikeDigitsIgnored() {
        val r = ParseEngine.parse(
            combine("工商银行", "验证码 123456，请勿泄露"),
            null,
            1_700_000_000_000L,
        )
        assertTrue("验证码不应记账，实际=$r", r is ParseResult.NoAmount)
    }
}
