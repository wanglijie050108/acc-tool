package com.moneyflow.app.parse

import com.moneyflow.app.data.Direction

/**
 * 转账信号识别 —— 判断一条「没有金额」的通知是不是**朋友间的转账**。
 *
 * ── 为什么需要这个 ──
 * 微信/支付宝给好友转账后，推送到通知栏的那条消息**不含金额**，
 * 只有「[转账] 已被接收」这类文案（真机已确认）。
 * 金额只存在于 App 内部的账单页里，通知拿不到。
 *
 * 所以这类通知走 [ParseEngine.parse] 会得到 [ParseResult.NoAmount]，
 * 在旧版本里被直接丢弃 —— 用户永远不知道漏了一笔。
 * 本类的作用是把它挑出来，交给上层变成「待确认」事件，让用户补个金额即可。
 *
 * ── ⚠️ 误报的代价极高 ──
 * 用户每天会收到几十条**没有金额**的通知（聊天、推送、验证码、广告……）。
 * 如果判定太宽松，App 会不停弹「是不是有一笔转账？」，用户会立刻卸载。
 *
 * 所以这里的判定必须**远严于** [MoneyText.looksMoneyRelated]：
 *   - 后者是「留档」判定，宁可多存（只存本地，最多 300 条，可清空）
 *   - 本类是「打扰用户」判定，宁可漏，不可误报
 *
 * ⚠️ 特别地，**绝不能复用 [MoneyText.MONEY_MARKERS]**：
 * 那里面有一个裸的「转账」，而本类的 [STRONG_MARKERS] 要求的是
 * 「[转账]」这种带方括号的**微信专用标记**或完整短语。
 * 裸关键词误判的教训在 [BuiltinRules] 里已经踩过一次
 * （「收款方」被当成收入），不能重蹈覆辙。
 */
object TransferSignal {

    /**
     * 强标记：出现任意一个就基本可以确定是转账通知。
     *
     * 这些都是微信/支付宝**转账通知专用**的固定文案，不会出现在
     * 广告、聊天、验证码里。
     *
     * 微信的转账通知形如：
     *   「[转账]已被接收」「[转账]待朋友确认收款」「[转账]已退还」
     * 支付宝的转账通知形如：
     *   「你已成功转账给×××」「×××向你转账」
     */
    private val STRONG_MARKERS = listOf(
        // 微信：方括号是微信系统消息的固定格式，非常好认
        "[转账]",
        "【转账】",
        // 转账的几种终态/中间态（都是微信固定文案）
        "已被接收",
        "待朋友确认收款",
        "待确认收款",
        "已退还",
        "已收款",
        // 支付宝
        "成功转账给",
        "向你转账",
    )

    /**
     * 弱标记：单独出现不足以判定，必须**同时命中两个不同类别**才算。
     *
     * ⚠️ 这里踩过一个真实的坑（单元测试抓出来的）：
     * 最初写成「转账」和「转账给」并列，以为能靠组合提高置信度，
     * 结果「转账给朋友的时候记得备注」这句普通聊天同时"命中"了两个 ——
     * 因为**「转账给」本身就包含「转账」**，同一个词被数了两次。
     *
     * 所以现在**不再用重叠的字符串**，而是显式分组：
     * 每个元素是「一个语义类别」，类别之间保证互不包含。
     * 「转账」和「转给」虽然同义，但它们不是彼此的子串，
     * 各自代表一类证据，组合起来才说明这确实像在描述一次转账动作。
     *
     * 回归用例：weakMarkerInNormalSentenceIsNotEnough / chatIsNotTransfer
     */
    private val WEAK_MARKER_GROUPS = listOf(
        // 类别 1：出现「转账」这个词本身
        listOf("转账"),
        // 类别 2：出现「转给 / 转至」这类明确的付出动作
        listOf("转给", "转至"),
        // 类别 3：出现到账/收讫的口吻
        listOf("已到账", "收到转账"),
    )

    /**
     * 排除词：出现任意一个就**绝对不是**转账通知。
     *
     * 这些是实测会误伤的场景：
     *   - 「转账」出现在验证码短信里（「您正在转账，验证码 123456」）
     *   - 广告推送（「转账免手续费」「转账有优惠」）
     *   - 银行营销（「转账抽奖」）
     */
    private val EXCLUDE_MARKERS = listOf(
        // 验证码类：绝不能打扰用户，也绝不能留档
        "验证码", "校验码", "动态码", "动态密码", "短信密码",
        "verification code",
        // 营销/活动类：这些不是一笔真实的转账
        "免手续费", "手续费优惠", "抽奖", "领取", "点击", "活动",
        "立减", "红包封面", "推荐", "邀请", "下载",
    )

    /**
     * 判定结果。
     *
     * 用密封接口而不是 Boolean：上层要区分「确定是转账」和
     * 「可能是转账」，两者的处理策略不同（前者直接提示，后者只留档）。
     */
    sealed interface Verdict {
        /** 确定是转账通知，应提示用户补金额 */
        data class Transfer(val direction: Direction) : Verdict

        /** 不是转账通知，正常丢弃 */
        data object NotTransfer : Verdict
    }

    /**
     * 判定一条文本是否是「无金额的转账通知」。
     *
     * ⚠️ 调用方必须先确认这条通知**没有金额**（即 [ParseEngine.parse]
     * 返回了 [ParseResult.NoAmount]）。本函数不负责判断有无金额；
     * 但如果文本里明显有金额，这里会保守地返回 [Verdict.NotTransfer]，
     * 因为那种情况属于「规则没覆盖到」，应该走样本留档而不是打扰用户。
     *
     * @param text        通知的合并文本（标题 + 正文 + bigText）
     * @param packageName 来源包名。null（短信）或非微信/支付宝一律不判定
     */
    fun detect(text: String, packageName: String?): Verdict {
        if (text.isBlank()) return Verdict.NotTransfer

        // 只处理微信/支付宝。
        // 其他 App 的通知（含短信）不走这条路 —— 银行短信有金额，
        // 而且银行的「转账」是自有格式，由 BuiltinRules 的短信规则负责。
        if (packageName == null) return Verdict.NotTransfer
        if (packageName !in SUPPORTED_PACKAGES) return Verdict.NotTransfer

        // 太长的多半是广告或异常内容
        if (text.length > MoneyText.MAX_LENGTH) return Verdict.NotTransfer

        // 排除词优先：宁可漏报，不可误报
        if (EXCLUDE_MARKERS.any { text.contains(it, ignoreCase = true) }) {
            return Verdict.NotTransfer
        }

        // ⚠️ 如果文本里已经有金额，说明是「规则没覆盖到」而不是「通知里没有金额」。
        // 那种情况应该由用户手动加规则解决，不该弹转账提示（金额会冲突）。
        if (containsAmount(text)) return Verdict.NotTransfer

        val strongHit = STRONG_MARKERS.any { text.contains(it) }
        if (!strongHit) {
            // 没有强标记时，要求同时命中**两个不同类别**的弱标记。
            //
            // 按类别计数（而不是按词计数）是关键：见 [WEAK_MARKER_GROUPS]。
            // 同一类别里命中多个词只算一次，避免子串重叠凑数。
            val hitGroups = WEAK_MARKER_GROUPS.count { group ->
                group.any { text.contains(it) }
            }
            if (hitGroups < 2) return Verdict.NotTransfer
        }

        return Verdict.Transfer(direction = guessDirection(text))
    }

    /**
     * 猜方向。
     *
     * ⚠️ 这只是**默认值**，必须允许用户改。
     * 「[转账]已被接收」这句话本身无法区分是「我转出被对方收了」
     * 还是「对方转我我收了」—— 微信对两边推的是同一句话。
     *
     * ⚠️ **没有「中性」兜底了。** 曾经这句的 `else` 分支返回
     * `Direction.NEUTRAL`（「方向不明确」），但中性记录在统计上等于没记 ——
     * 用户补完金额，月度收支两个数字都不动，只会以为 App 又出问题了。
     *
     * 所以现在给一个**明确的默认方向**（支出），理由：
     *   - 微信/支付宝的转账通知里，「我主动转账给别人」远多于「别人转我」
     *   - 补录弹窗里方向是显式可选项，用户看到默认值不对可以直接改
     *   - 就算用户没改，一笔有方向的记录也**可以被统计、可以被发现**，
     *     而中性记录只会静默地消失在统计之外
     *
     * 回归用例见 TransferSignalTest：方向必须非空、必须是支出或收入。
     */
    private fun guessDirection(text: String): Direction = when {
        // 「已退还」：钱退回来了，是收入
        text.contains("已退还") -> Direction.INCOME
        // 「待朋友确认收款」：是我转出、对方还没收 → 支出
        text.contains("待朋友确认收款") -> Direction.EXPENSE
        // 到账/收讫口吻 → 收入
        text.contains("已到账") || text.contains("收到转账") -> Direction.INCOME
        // 其余默认按支出：主动转出比被动收款更常见，且用户可改
        else -> Direction.EXPENSE
    }

    /**
     * 文本里是否已经含金额。
     *
     * 用比 BuiltinRules 更宽松的判定（只要能看出「像钱」就算），
     * 因为这里的目标是**避免**误报，只要疑似有金额就退回不打扰。
     */
    private fun containsAmount(text: String): Boolean {
        // ¥12.34 / ￥12 / 12.34元 / 12元
        if (AMOUNT_PATTERN.containsMatchIn(text)) return true
        // 裸数字 + 元（「转账 50 元」）
        return text.contains("元") && Regex("""\d""").containsMatchIn(text)
    }

    /**
     * 金额特征。要求：带货币符号的数字，或带小数点的数字，或带「元」的数字。
     *
     * 不能简单用 \d+ —— 「待朋友确认收款」里没有数字，但
     * 「[转账]已验证 2026-10-14」这种会误判，所以要求数字紧邻货币特征。
     */
    private val AMOUNT_PATTERN = Regex(
        """(?:[¥￥]\s*[\d,]+\.?\d*)|(?:[\d,]+\.\d{1,2})|(?:[\d,]+\s*元)"""
    )

    /** 支持的包名。与 BuiltinRules 保持一致 */
    private val SUPPORTED_PACKAGES = setOf(
        "com.tencent.mm",                    // 微信
        "com.eg.android.AlipayGphone",       // 支付宝
    )
}
