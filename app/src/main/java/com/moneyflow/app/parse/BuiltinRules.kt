package com.moneyflow.app.parse

import com.moneyflow.app.data.Direction
import com.moneyflow.app.data.SourceKind

/**
 * 内置解析规则表。
 *
 * ⚠️ 重要：这里的正则基于「常见格式」编写，属于初版。
 * 真实的微信/支付宝/银行通知格式会随版本变化，必须用真实样本校准。
 * 校准方法：把真实通知文本贴到「解析测试」页面，看命中结果，然后回来改这里的规则。
 *
 * 规则编写约定：
 * - 用 .*? 而不是 .* 做非贪婪匹配，避免跨字段吃掉内容
 * - 金额部分统一匹配 ([\d,]+\.?\d*) ，由引擎负责去掉千分位逗号
 * - 只在必要时限制 packageNames
 */
object BuiltinRules {

    // 常见包名
    private const val PKG_WECHAT = "com.tencent.mm"
    private const val PKG_ALIPAY = "com.eg.android.AlipayGphone"
    private const val PKG_UNIONPAY = "com.unionpay"

    val rules: List<ParseRule> = listOf(

        // ─────────────── 微信支付 ───────────────

        // 「微信支付」向你转账 / 收款到账
        ParseRule(
            id = "wechat_receive",
            sourceKind = SourceKind.WECHAT_NOTIFICATION,
            sourceName = "微信支付",
            packageNames = setOf(PKG_WECHAT),
            pattern = Regex("""(?:收款|到账|收到).*?[¥￥]?\s*([\d,]+\.?\d*)"""),
            direction = Direction.INCOME,
            priority = 20,
        ),

        // 微信支付：向商户付款
        ParseRule(
            id = "wechat_pay_merchant",
            sourceKind = SourceKind.WECHAT_NOTIFICATION,
            sourceName = "微信支付",
            packageNames = setOf(PKG_WECHAT),
            pattern = Regex("""向\s*(.{1,20}?)\s*(?:付款|支付)\s*[¥￥]?\s*([\d,]+\.?\d*)"""),
            amountGroup = 2,
            direction = Direction.EXPENSE,
            merchantGroup = 1,
            priority = 15,
        ),

        // 微信支付通用支出：微信支付-¥58.00 之类
        ParseRule(
            id = "wechat_pay_generic",
            sourceKind = SourceKind.WECHAT_NOTIFICATION,
            sourceName = "微信支付",
            packageNames = setOf(PKG_WECHAT),
            pattern = Regex("""微信支付.*?[-－]\s*[¥￥]?\s*([\d,]+\.?\d*)"""),
            direction = Direction.EXPENSE,
            priority = 10,
        ),

        // ─────────────── 支付宝 ───────────────

        // 支付宝：你已成功付款 / 支付成功
        ParseRule(
            id = "alipay_pay",
            sourceKind = SourceKind.ALIPAY_NOTIFICATION,
            sourceName = "支付宝",
            packageNames = setOf(PKG_ALIPAY),
            pattern = Regex("""(?:成功付款|支付成功|付款成功)\s*[¥￥]?\s*([\d,]+\.?\d*)"""),
            direction = Direction.EXPENSE,
            priority = 15,
        ),

        // 支付宝：向商户付款
        ParseRule(
            id = "alipay_pay_merchant",
            sourceKind = SourceKind.ALIPAY_NOTIFICATION,
            sourceName = "支付宝",
            packageNames = setOf(PKG_ALIPAY),
            pattern = Regex("""(?:向|在)\s*(.{1,20}?)\s*(?:付款|支付|消费)\s*[¥￥]?\s*([\d,]+\.?\d*)"""),
            amountGroup = 2,
            direction = Direction.EXPENSE,
            merchantGroup = 1,
            priority = 14,
        ),

        // 支付宝：收款到账
        ParseRule(
            id = "alipay_receive",
            sourceKind = SourceKind.ALIPAY_NOTIFICATION,
            sourceName = "支付宝",
            packageNames = setOf(PKG_ALIPAY),
            pattern = Regex("""(?:收款|到账|收到).*?[¥￥]?\s*([\d,]+\.?\d*)"""),
            direction = Direction.INCOME,
            priority = 20,
        ),

        // 支付宝：余额宝/花呗等资金变动
        ParseRule(
            id = "alipay_generic_expense",
            sourceKind = SourceKind.ALIPAY_NOTIFICATION,
            sourceName = "支付宝",
            packageNames = setOf(PKG_ALIPAY),
            pattern = Regex("""支付宝.*?[-－]\s*[¥￥]?\s*([\d,]+\.?\d*)"""),
            direction = Direction.EXPENSE,
            priority = 5,
        ),

        // ─────────────── 云闪付 ───────────────

        ParseRule(
            id = "unionpay_pay",
            sourceKind = SourceKind.OTHER_NOTIFICATION,
            sourceName = "云闪付",
            packageNames = setOf(PKG_UNIONPAY),
            pattern = Regex("""(?:消费|支出|付款|交易).*?[¥￥]?\s*([\d,]+\.?\d*)"""),
            direction = Direction.EXPENSE,
            priority = 10,
        ),

        // ─────────────── 银行短信（不限制包名，短信走独立通道） ───────────────
        //
        // 这些规则是用「公开的 10 家银行真实短信模板」校准过的，覆盖：
        //   招商 / 光大 / 工商 / 中国 / 建设 / 平安 / 成都 / 德阳 / 民生 / 农业
        //
        // 真实格式里的坑（都是实测踩出来的）：
        //   1. 招行金额后**没有「元」字**：「扣款人民币99.50，余额1123.59」
        //   2. 平安用 **RMB** 而不是「人民币」：「存RMB485,000.00元」
        //   3. 动词五花八门：存款/取款/支取/现支/取现/入账/存/取
        //   4. 农行用**负号**表示支出：「现支(分行营业部)-1400.00元」
        //   5. 工行时间是**全角冒号**：「12日19：30」
        //   6. 民生用 **￥** 符号：「支出￥100.00元」
        //
        // 所以金额后半部分写成「元?」（可选的元），
        // 货币前缀写成 (?:人民币|RMB|¥|￥)? 的通用形式。

        // ── 支出 ──
        // 覆盖：支出/消费/付款/转出/扣款/取现/支取/现支/取款/取出/取
        ParseRule(
            id = "sms_expense",
            sourceKind = SourceKind.BANK_SMS,
            sourceName = "银行短信",
            // 短信专属：这条规则宽松，绝不能让它在 App 通知上生效，
            // 否则会抢走微信/支付宝规则的匹配（真实发生过的 bug）
            smsOnly = true,
            packageNames = emptySet(),
            pattern = Regex(
                """(?:支出|消费|付款|转出|扣款|取现|支取|现支|取款|取出|取)\s*""" +
                    """(?:金额)?\s*(?:人民币|RMB|¥|￥)?\s*([\d,]+\.?\d*)\s*元?"""
            ),
            direction = Direction.EXPENSE,
            priority = 30,
        ),

        // ── 收入 ──
        // 覆盖：收入/存入/存款/转入/到账/退款/入账/存/收
        // 注意「存款」要在「存」之前，否则「存」会先匹配但也没问题（同一组）
        ParseRule(
            id = "sms_income",
            sourceKind = SourceKind.BANK_SMS,
            sourceName = "银行短信",
            // 短信专属：这条规则宽松，绝不能让它在 App 通知上生效，
            // 否则会抢走微信/支付宝规则的匹配（真实发生过的 bug）
            smsOnly = true,
            packageNames = emptySet(),
            pattern = Regex(
                """(?:收入|存款|存入|转入|到账|退款|入账|存|收)\s*""" +
                    """(?:金额)?\s*(?:人民币|RMB|¥|￥)?\s*([\d,]+\.?\d*)\s*元?"""
            ),
            direction = Direction.INCOME,
            priority = 30,
        ),

        // ── 负号表示支出（农业银行等）──
        // 格式：「现支(分行营业部)-1400.00元」
        // 必须优先于收入规则，否则「存」之类的字眼会先命中。
        ParseRule(
            id = "sms_negative_amount_expense",
            sourceKind = SourceKind.BANK_SMS,
            sourceName = "银行短信",
            // 短信专属：这条规则宽松，绝不能让它在 App 通知上生效，
            // 否则会抢走微信/支付宝规则的匹配（真实发生过的 bug）
            smsOnly = true,
            packageNames = emptySet(),
            pattern = Regex("""-\s*([\d,]+\.?\d*)\s*元"""),
            direction = Direction.EXPENSE,
            priority = 35,
        ),

        // 不带「人民币」二字的短信变体。
        // 保留独立的低优先级规则，作为上面通用规则之外的兜底，
        // 防止某些格式因通用规则过宽而误判方向。
        ParseRule(
            id = "sms_expense_plain",
            sourceKind = SourceKind.BANK_SMS,
            sourceName = "银行短信",
            // 短信专属：这条规则宽松，绝不能让它在 App 通知上生效，
            // 否则会抢走微信/支付宝规则的匹配（真实发生过的 bug）
            smsOnly = true,
            packageNames = emptySet(),
            pattern = Regex("""(?:支出|消费|扣款|转出)\s*(?:金额)?\s*[¥￥]?\s*([\d,]+\.?\d*)\s*元"""),
            direction = Direction.EXPENSE,
            priority = 20,
        ),

        ParseRule(
            id = "sms_income_plain",
            sourceKind = SourceKind.BANK_SMS,
            sourceName = "银行短信",
            // 短信专属：这条规则宽松，绝不能让它在 App 通知上生效，
            // 否则会抢走微信/支付宝规则的匹配（真实发生过的 bug）
            smsOnly = true,
            packageNames = emptySet(),
            pattern = Regex("""(?:收入|转入|存入|到账|退款)\s*(?:金额)?\s*[¥￥]?\s*([\d,]+\.?\d*)\s*元"""),
            direction = Direction.INCOME,
            priority = 20,
        ),

        // ─────────────── 资金流动类（需求：只要有金钱变动都记） ───────────────
        // 这些不是「消费」，但属于资金变动，用户明确要求一并记录。
        // 方向标记为 NEUTRAL：因为还款/提现/充值往往是账户间转移，
        // 当成支出或收入都会让统计失真。

        // 信用卡还款
        ParseRule(
            id = "sms_repayment",
            sourceKind = SourceKind.BANK_SMS,
            sourceName = "银行短信",
            // 短信专属：这条规则宽松，绝不能让它在 App 通知上生效，
            // 否则会抢走微信/支付宝规则的匹配（真实发生过的 bug）
            smsOnly = true,
            packageNames = emptySet(),
            pattern = Regex("""(?:还款|已还|偿还)\s*(?:金额)?\s*(?:人民币)?\s*[¥￥]?\s*([\d,]+\.?\d*)\s*元"""),
            direction = Direction.NEUTRAL,
            priority = 25,
        ),

        // 提现 / 充值 / 转存
        ParseRule(
            id = "sms_transfer_neutral",
            sourceKind = SourceKind.BANK_SMS,
            sourceName = "银行短信",
            // 短信专属：这条规则宽松，绝不能让它在 App 通知上生效，
            // 否则会抢走微信/支付宝规则的匹配（真实发生过的 bug）
            smsOnly = true,
            packageNames = emptySet(),
            pattern = Regex("""(?:提现|充值|转存|转账)\s*(?:金额)?\s*(?:人民币)?\s*[¥￥]?\s*([\d,]+\.?\d*)\s*元"""),
            direction = Direction.NEUTRAL,
            priority = 22,
        ),

        // 微信零钱提现（含「零钱」字样，避免被通用支出规则吃掉而标成 EXPENSE）
        ParseRule(
            id = "wechat_withdraw",
            sourceKind = SourceKind.WECHAT_NOTIFICATION,
            sourceName = "微信支付",
            packageNames = setOf(PKG_WECHAT),
            pattern = Regex("""(?:零钱提现|提现).*?[¥￥]?\s*([\d,]+\.?\d*)"""),
            direction = Direction.NEUTRAL,
            priority = 22,
        ),

        // 信用卡还款通知
        ParseRule(
            id = "generic_repayment_notification",
            sourceKind = SourceKind.OTHER_NOTIFICATION,
            sourceName = "其他",
            packageNames = emptySet(),
            pattern = Regex("""(?:信用卡还款|自动还款|还款成功).*?[¥￥]?\s*([\d,]+\.?\d*)"""),
            direction = Direction.NEUTRAL,
            priority = 22,
        ),

        // ─────────────── 兜底规则（优先级最低） ───────────────
        // 只在明确出现支付类关键词时才兜底，避免把无关通知记成流水

        ParseRule(
            id = "fallback_expense",
            sourceKind = SourceKind.OTHER_NOTIFICATION,
            sourceName = "其他",
            packageNames = emptySet(),
            pattern = Regex("""(?:支付|付款|消费|扣款|支出).*?[¥￥]\s*([\d,]+\.?\d*)"""),
            direction = Direction.EXPENSE,
            priority = -10,
        ),
    )

    /** 按优先级从高到低排序后的规则。引擎按这个顺序尝试匹配 */
    val orderedRules: List<ParseRule> = rules.sortedByDescending { it.priority }
}
