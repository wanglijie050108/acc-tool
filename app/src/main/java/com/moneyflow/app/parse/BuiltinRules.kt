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
        //
        // ⚠️ 这里**绝不能**用裸的「收款」二字（曾经就是「收款|到账|收到」）：
        // 微信的**付款**通知里写着「收款方：星巴克 ¥38.50」——
        // 那是「收款方」这个名词，指商家，语义是**支出**。
        // 裸「收款」会命中它，且本规则优先级(20)高于 wechat_pay_merchant(15)，
        // 于是把一笔支出记成了**收入**（真机复现，见回归测试
        // wechatPayeeLabelIsNotMistakenForIncome）。
        // 同理「收到」也太泛（「收到货」「收到消息」都可能带数字）。
        //
        // 所以只保留**能确定是资金入账**的写法，并要求金额紧跟在动词之后，
        // 避免用 .*? 跨字段去抓一个不相干的数字。
        ParseRule(
            id = "wechat_receive",
            sourceKind = SourceKind.WECHAT_NOTIFICATION,
            sourceName = "微信支付",
            packageNames = setOf(PKG_WECHAT),
            pattern = Regex(
                """(?:收款到账|收款成功|已收款|收款通知|收到转账|收到一笔|收到红包|到账)\s*""" +
                    """(?:通知|提醒)?\s*[:：]?\s*[¥￥]?\s*([\d,]+\.?\d*)"""
            ),
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
        //
        // 与 wechat_receive 同样的坑：不能用裸的「收款」——
        // 支付宝的付款通知里也有「收款方」字样，会把支出记成收入。
        ParseRule(
            id = "alipay_receive",
            sourceKind = SourceKind.ALIPAY_NOTIFICATION,
            sourceName = "支付宝",
            packageNames = setOf(PKG_ALIPAY),
            pattern = Regex(
                """(?:收款到账|收款成功|已收款|收款通知|收到转账|收到一笔|收到红包|到账)\s*""" +
                    """(?:通知|提醒)?\s*[:：]?\s*[¥￥]?\s*([\d,]+\.?\d*)"""
            ),
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
        //
        // ⚠️ **没有「中性」方向了**（曾经有，已按用户要求删除）。
        // 还款/提现/充值这些账户间转移**必须判断出确定方向**：
        //   - 钱离开当前账户（提现、取现、还款、充值）→ EXPENSE
        //   - 钱进入当前账户（到账、入账、转入、收款） → INCOME
        //
        // 为什么不再用「中性」：月度汇总只按 EXPENSE/INCOME 求和，
        // 中性记录在界面上是黑色无符号数字，既不进支出也不进收入 ——
        // 用户提现 500 元，统计上完全看不出变化，只会以为「App 没记上」。
        // 把「不知道」当成答案是错的；方向确实判断不了的场景应该走
        // 「待确认」流程去问用户，而不是记一条方向不明的流水。

        // 信用卡还款 → 支出
        // 钱从你的储蓄卡离开去还信用卡，对「你的钱包」而言是流出。
        ParseRule(
            id = "sms_repayment",
            sourceKind = SourceKind.BANK_SMS,
            sourceName = "银行短信",
            // 短信专属：这条规则宽松，绝不能让它在 App 通知上生效，
            // 否则会抢走微信/支付宝规则的匹配（真实发生过的 bug）
            smsOnly = true,
            packageNames = emptySet(),
            pattern = Regex("""(?:还款|已还|偿还)\s*(?:金额)?\s*(?:人民币)?\s*[¥￥]?\s*([\d,]+\.?\d*)\s*元"""),
            direction = Direction.EXPENSE,
            priority = 25,
        ),

        // 提现 / 取现 / 充值 / 转存 → 支出
        // 这些都是钱**离开当前账户**的动作：零钱提现到银行卡、
        // 从银行卡充值到零钱、转存到别的账户 —— 对当前账户都是流出。
        ParseRule(
            id = "sms_transfer_expense",
            sourceKind = SourceKind.BANK_SMS,
            sourceName = "银行短信",
            // 短信专属：这条规则宽松，绝不能让它在 App 通知上生效，
            // 否则会抢走微信/支付宝规则的匹配（真实发生过的 bug）
            smsOnly = true,
            packageNames = emptySet(),
            pattern = Regex("""(?:提现|取现|充值|转存)\s*(?:金额)?\s*(?:人民币)?\s*[¥￥]?\s*(\d[\d,]*(?:\.\d{1,2})?)\s*元"""),
            direction = Direction.EXPENSE,
            priority = 22,
        ),

        // 银行侧的「提现到账」→ 收入。
        //
        // ⚠️ 必须**排在** sms_transfer_expense(22) 前面，否则含「提现」二字的
        // 到账短信会被上面那条按支出吃掉。
        //
        // 语义差别只有「到账」两个字，方向却相反：
        //   「您尾号1234账户提现人民币500.00元」      → 你在别处发起的提现，支出
        //   「您的零钱提现到账 500.00元」             → 钱进了这张卡，收入
        // 这正是 BuiltinRules 里踩过的「收款 vs 收款方」同类坑。
        ParseRule(
            id = "sms_withdraw_arrival_income",
            sourceKind = SourceKind.BANK_SMS,
            sourceName = "银行短信",
            smsOnly = true,
            packageNames = emptySet(),
            pattern = Regex("""(?:提现|转账|转存|汇款).{0,10}?(?:到账|入账|已到账)\s*(?:金额)?\s*(?:人民币)?\s*[¥￥]?\s*(\d[\d,]*(?:\.\d{1,2})?)\s*元?"""),
            direction = Direction.INCOME,
            priority = 26,
        ),

        // 微信零钱提现 → 支出（零钱少了一笔）
        //
        // 提现通知的两种形态：
        //   「微信支付 零钱提现 ¥500.00」                      → 金额紧跟「提现」
        //   「微信支付 零钱提现 手续费 0.10 元 到账 500.00 元」 → 金额在「到账」后
        //
        // ⚠️ 不能用 .*? 一把抓，否则会把「手续费 0.10」当成提现金额（实测过）。
        // 做法：先用一条「到账 N」的规则吃掉带手续费的形态，
        // 再用原来的「提现 N」形态兜底。两条规则共享同一 id 语义，
        // 用优先级区分先后，金额始终在第 1 组 —— 不动引擎逻辑。
        //
        // ⚠️ 金额组写成 (\d[\d,]*(?:\.\d{1,2})?) 而不是 ([\d,]+\.?\d*)：
        // 后者两段都可空，**能匹配空字符串**。一旦「到账」后面不是它预期的
        // 数字形态（例如「已到账,请查收」），它会匹配到空串，
        // parseAmount("") 返回 null → 引擎 continue 换下一条规则 →
        // **整条通知被静默丢弃，用户完全无从察觉**。这个 bug 实测复现过。
        ParseRule(
            id = "wechat_withdraw_settled",
            sourceKind = SourceKind.WECHAT_NOTIFICATION,
            sourceName = "微信支付",
            packageNames = setOf(PKG_WECHAT),
            pattern = Regex(
                """(?:零钱提现|提现).{0,30}?(?:到账|成功|已完成)\s*[¥￥]?\s*(\d[\d,]*(?:\.\d{1,2})?)\s*元?"""
            ),
            direction = Direction.EXPENSE,
            priority = 23,
        ),

        ParseRule(
            id = "wechat_withdraw",
            sourceKind = SourceKind.WECHAT_NOTIFICATION,
            sourceName = "微信支付",
            packageNames = setOf(PKG_WECHAT),
            pattern = Regex(
                """(?:零钱提现|提现)\s*(?:金额)?\s*[:：]?\s*(?:人民币)?\s*""" +
                    """(?:(?:[-－]|[¥￥])\s*)?(\d[\d,]*(?:\.\d{1,2})?)\s*元?"""
            ),
            direction = Direction.EXPENSE,
            priority = 22,
        ),

        // 支付宝余额提现 → 支出。
        // 与微信零钱提现是同一个场景，方向同样是「余额少了一笔」。
        ParseRule(
            id = "alipay_withdraw",
            sourceKind = SourceKind.ALIPAY_NOTIFICATION,
            sourceName = "支付宝",
            packageNames = setOf(PKG_ALIPAY),
            pattern = Regex(
                """(?:余额提现|提现).{0,20}?(?:到账|成功|已完成)\s*[¥￥]?\s*(\d[\d,]*(?:\.\d{1,2})?)\s*元?"""
            ),
            direction = Direction.EXPENSE,
            priority = 23,
        ),

        // 信用卡还款通知 → 支出（同上：钱离开你的账户）
        ParseRule(
            id = "generic_repayment_notification",
            sourceKind = SourceKind.OTHER_NOTIFICATION,
            sourceName = "其他",
            packageNames = emptySet(),
            pattern = Regex("""(?:信用卡还款|自动还款|还款成功).*?(\d[\d,]*(?:\.\d{1,2})?)\s*元?"""),
            direction = Direction.EXPENSE,
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
