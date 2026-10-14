package com.moneyflow.app.parse

/**
 * 判断一段文本「看起来和钱有关」—— 决定要不要把它留档成待补规则的样本。
 *
 * ⚠️ 这个判定必须**只有一份实现**。
 * 原先通知通道（NotificationCaptureService）和短信通道（SmsReceiver）
 * 各写了一份一模一样的 markers 列表，改一处漏一处，
 * 结果就是「通知不记验证码、短信却记」这种不一致 —— 真机上已经踩到过：
 * 一条「【微信支付】027626(微信支付验证码,请勿泄露)」因为含「微信支付」四个字
 * 被当成了账务样本留档，而 README 明确承诺验证码不留档。
 *
 * 判定宽松一点没关系（宁可多存），因为：
 *   - 只存本地，最多 300 条，自动淘汰
 *   - 用户导出前能看到内容，也能一键清空
 * 但**验证码必须排除**：它既没有账务价值，又包含敏感的临时凭证。
 */
object MoneyText {

    /** 超过这个长度直接放弃。正常通知/短信不会这么长，超长多半是广告或异常内容 */
    const val MAX_LENGTH = 500

    /**
     * 出现这些词，说明这是一条验证码/动态口令短信，**不是**账务信息。
     *
     * 银行交易短信不会带验证码，所以命中即排除是安全的。
     */
    private val VERIFICATION_MARKERS = listOf(
        "验证码",
        "校验码",
        "动态码",
        "动态密码",
        "短信密码",
        "verification code",
    )

    /** 出现这些词，说明可能和金额有关，值得留档 */
    private val MONEY_MARKERS = listOf(
        "元", "¥", "￥", "支出", "收入", "消费", "付款", "支付",
        "扣款", "转账", "收款", "到账", "退款", "还款", "提现",
        "余额", "账单", "交易",
    )

    /**
     * 是否值得留档为「疑似账务」样本。
     *
     * 注意这是**留档**判定，不是**记账**判定 ——
     * 能不能记账由 [ParseEngine.parse] 决定，这里只负责别把有用的原文丢掉。
     */
    fun looksMoneyRelated(text: String): Boolean {
        if (text.isBlank()) return false
        if (text.length > MAX_LENGTH) return false
        if (isVerificationCode(text)) return false
        return MONEY_MARKERS.any { text.contains(it) }
    }

    /** 是否是验证码/动态口令类内容 */
    fun isVerificationCode(text: String): Boolean {
        if (text.isBlank()) return false
        val lower = text.lowercase()
        return VERIFICATION_MARKERS.any { lower.contains(it) }
    }
}
