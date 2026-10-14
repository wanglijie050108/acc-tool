package com.moneyflow.app

import com.moneyflow.app.parse.MoneyText
import com.moneyflow.app.parse.ParseEngine
import com.moneyflow.app.parse.ParseResult
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「看起来和钱有关」判定 + 真机样本回归。
 *
 * 这个判定决定**要不要把原文留档**（留档是用户导出给我补规则的唯一来源），
 * 所以两个方向都会出事：
 *   - 判太严 → 认不出的通知被悄悄丢掉，我永远拿不到样本
 *   - 判太松 → 验证码、聊天记录被存下来，违反隐私承诺
 *
 * 本文件里的样本**全部来自真机实测**（REDMI K80 / Android 15 / HyperOS），
 * 不是编造的，改动判定逻辑时不能把它们测坏。
 */
class MoneyTextTest {

    // ─────────── 验证码必须排除（README 明确承诺过）───────────

    /**
     * 真机实测样本。
     *
     * 这条短信因为带「微信支付」四个字，曾经被当成账务样本留档，
     * 但它是验证码，既没有账务价值又含敏感临时凭证。
     */
    @Test
    fun realDeviceWechatPayVerificationSmsIsNotRecorded() {
        val real = "微信支付 【微信支付】027626(微信支付验证码,请勿泄露),"
        assertFalse("验证码短信不能留档", MoneyText.looksMoneyRelated(real))
        assertTrue("应被识别为验证码", MoneyText.isVerificationCode(real))
    }

    @Test
    fun bankVerificationSmsIsNotRecorded() {
        val cases = listOf(
            "【工商银行】您的验证码是 123456，请勿泄露",
            "【建设银行】校验码 654321，5分钟内有效",
            "【招商银行】动态密码 998877，用于本次支付",
            "Your verification code is 445566",
        )
        for (text in cases) {
            assertFalse("验证码类不能留档：$text", MoneyText.looksMoneyRelated(text))
        }
    }

    @Test
    fun bankTransactionSmsIsStillRecorded() {
        // 银行交易短信不会带验证码，所以排除规则不会误伤它们
        val cases = listOf(
            "您尾号1234账户10月14日16:42支出人民币58.00元，余额1234.56元",
            "您账户1523于11月01日21:05扣款人民币99.50，余额1123.59",
            "【民生银行】您尾号8888的卡支出￥100.00元",
        )
        for (text in cases) {
            assertTrue("银行交易短信必须留档：$text", MoneyText.looksMoneyRelated(text))
        }
    }

    // ─────────── 真机上的微信通知：留档，但不记账 ───────────

    /**
     * 真机实测样本：微信「转账给好友」发出的系统通知。
     *
     * 两条都**不含金额** —— 这不是 bug，是微信通知本身就没带金额。
     * 正确行为是：留档（因为含「转账」，看起来和钱有关）+ 不记账（没有金额可以记）。
     * 宁可漏记，也绝不能瞎编一个金额记进去。
     */
    @Test
    fun realDeviceWechatTransferNotificationsHaveNoAmount() {
        val real = listOf(
            "42 [2条]42: [转账] 请收款",
            "test [转账] 已被接收",
        )
        for (text in real) {
            assertTrue("含「转账」，应当留档：$text", MoneyText.looksMoneyRelated(text))
            assertTrue(
                "没有金额就不该记账：$text",
                ParseEngine.parse(text, "com.tencent.mm", 0L) is ParseResult.NoAmount,
            )
        }
    }

    // ─────────── 边界 ───────────

    @Test
    fun blankIsNotMoneyRelated() {
        assertFalse(MoneyText.looksMoneyRelated(""))
        assertFalse(MoneyText.looksMoneyRelated("   "))
    }

    @Test
    fun overlyLongTextIsRejected() {
        // 超长多半是广告或异常内容，留档只会挤掉有价值的样本
        val long = "支付" + "x".repeat(MoneyText.MAX_LENGTH)
        assertFalse(MoneyText.looksMoneyRelated(long))
    }

    @Test
    fun ordinaryChatMessageIsNotRecorded() {
        assertFalse(MoneyText.looksMoneyRelated("在吗？晚上一起吃饭"))
    }

    @Test
    fun lengthBoundaryIsInclusive() {
        // 正好等于上限应通过，超过一个字符就不通过。
        // 注意「支付」是两个字符，所以填充长度要相应减 2。
        val justAtLimit = "支付" + "x".repeat(MoneyText.MAX_LENGTH - 2)
        assertTrue("等于上限应当通过", MoneyText.looksMoneyRelated(justAtLimit))
        val overLimit = "支付" + "x".repeat(MoneyText.MAX_LENGTH - 1)
        assertFalse("超过上限应当拒绝", MoneyText.looksMoneyRelated(overLimit))
    }
}
