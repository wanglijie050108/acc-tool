package com.moneyflow.app.parse

import com.moneyflow.app.data.Direction
import com.moneyflow.app.data.SourceKind
import com.moneyflow.app.data.TransactionEntity
import java.security.MessageDigest

/** 解析结果 */
sealed interface ParseResult {
    /** 成功解析出一条流水 */
    data class Success(
        val amount: Double,
        val direction: Direction,
        val sourceKind: SourceKind,
        val sourceName: String,
        val merchant: String?,
        val ruleId: String,
        val fingerprint: String,
    ) : ParseResult

    /** 文本里没有金额，或不属于资金变动，正常跳过 */
    data object NoAmount : ParseResult

    /** 解析过程中出错 */
    data class Failure(val reason: String) : ParseResult
}

/**
 * 解析引擎：把一段原始文本（通知内容 / 短信全文）变成结构化流水。
 *
 * 使用方式：
 *   val result = ParseEngine.parse(text, packageName, occurredAt)
 */
object ParseEngine {

    /**
     * 当前生效的规则集合。
     *
     * 由内置规则 + 用户自定义规则组成，自定义规则**优先级更高** ——
     * 这样用户遇到内置规则识别错的格式时，可以自己加一条覆盖掉，
     * 不用等开发者发新版。
     *
     * 由 App 启动时或用户改规则后通过 [setCustomRules] 设置。
     */
    @Volatile
    private var customRules: List<ParseRule> = emptyList()

    /**
     * 设置自定义规则。传空列表表示只用内置规则。
     *
     * 自定义规则的优先级统一 +1000，确保它们先于内置规则尝试，
     * 但用户之间仍按各自填的 priority 排序。
     */
    fun setCustomRules(rules: List<ParseRule>) {
        customRules = rules.map { it.copy(priority = it.priority + 1000) }
    }

    fun currentCustomRuleCount(): Int = customRules.size

    /** 合并后的规则表：自定义优先，其余按 priority 降序 */
    private fun activeRules(): List<ParseRule> =
        (customRules + BuiltinRules.orderedRules).sortedByDescending { it.priority }

    /**
     * 解析一段文本。
     *
     * @param text        原始文本，通常是「通知标题 + 通知正文」拼接，或短信全文
     * @param packageName 来源 App 包名。短信传 null
     * @param occurredAt  发生时间戳。当前解析本身不再需要它
     *                    （去重的时间维度判断已移到 Repository 的范围查询），
     *                    但保留在签名里：调用方本来就要传时间入库，
     *                    且后续若要做「同一分钟内合并」之类的规则会用到。
     */
    @Suppress("UNUSED_PARAMETER")
    fun parse(
        text: String,
        packageName: String?,
        occurredAt: Long,
    ): ParseResult {
        if (text.isBlank()) return ParseResult.NoAmount

        val normalized = normalize(text)

        // 超长文本直接放弃：用户自定义的正则可能有灾难性回溯，
        // 而正常通知/短信不会这么长。
        if (normalized.length > CustomRuleStore.MAX_MATCH_LENGTH * 4) {
            return ParseResult.NoAmount
        }

        // 按优先级尝试每条规则
        for (rule in activeRules()) {

            // 通道过滤。
            //
            // packageName == null 表示「这段文本来自短信」（短信没有包名概念）。
            // 两条规则：
            //   1. 限定了包名的规则，只在对应 App 的通知里生效
            //   2. smsOnly 的规则只在短信里生效
            //
            // ⚠️ 第 2 条是必须的：银行短信规则写得很宽松（金额后可能没有「元」字、
            // 动词有十几种写法），如果让它去匹配微信/支付宝的通知，
            // 会因优先级更高而抢走匹配，导致商户名丢失、来源标错。
            val isSms = packageName == null

            if (rule.smsOnly && !isSms) continue

            if (rule.packageNames.isNotEmpty()) {
                if (packageName == null) continue
                if (packageName !in rule.packageNames) continue
            }

            val match = rule.pattern.find(normalized) ?: continue

            // 规则自检：组号超出本次匹配实际的捕获组数，说明规则写错了。
            // match.groups.size = 捕获组数 + 1（第 0 组是整个匹配）。
            // 直接跳过而不是静默取到错误内容，避免记错金额。
            if (rule.amountGroup >= match.groups.size) continue

            // 提取金额。
            // 注意：MatchGroupCollection 只有 get(index)，没有 getOrNull，
            // 组号越界时返回 null，所以这里必须判空。
            val amountRaw = match.groups[rule.amountGroup]?.value ?: continue
            val amount = parseAmount(amountRaw) ?: continue

            // 金额为 0 或负数，视为无效
            if (amount <= 0.0) continue

            // 提取商户（同样做组号边界检查）
            val merchant = rule.merchantGroup
                ?.takeIf { it < match.groups.size }
                ?.let { match.groups[it]?.value }
                ?.trim()
                ?.takeIf { it.isNotEmpty() }

            return ParseResult.Success(
                amount = amount,
                direction = rule.direction,
                sourceKind = rule.sourceKind,
                sourceName = rule.sourceName,
                merchant = merchant,
                ruleId = rule.id,
                fingerprint = fingerprint(
                    amount = amount,
                    direction = rule.direction,
                ),
            )
        }

        return ParseResult.NoAmount
    }

    /** 把解析结果转成可入库的实体 */
    fun toEntity(
        result: ParseResult.Success,
        rawText: String,
        occurredAt: Long,
    ): TransactionEntity = TransactionEntity(
        amount = result.amount,
        direction = result.direction,
        sourceKind = result.sourceKind,
        sourceName = result.sourceName,
        merchant = result.merchant,
        rawText = rawText,
        occurredAt = occurredAt,
        fingerprint = result.fingerprint,
    )

    /**
     * 文本归一化。
     * 通知里的全角字符、特殊空格、换行都会导致正则匹配失败，这里统一处理。
     */
    private fun normalize(text: String): String = text
        .replace('\u00A0', ' ')          // 不换行空格
        .replace('\u3000', ' ')          // 全角空格
        .replace('\uFF0D', '-')          // 全角减号
        .replace('\u2013', '-')          // en dash
        .replace('\u2014', '-')          // em dash
        .replace("\r\n", " ")
        .replace("\n", " ")
        .replace(Regex("\\s+"), " ")
        .trim()

    /**
     * 金额字符串转 Double。
     * 处理千分位逗号：「1,234.56」→ 1234.56
     * 处理无小数位：「58」→ 58.0
     */
    private fun parseAmount(raw: String): Double? {
        val cleaned = raw.replace(",", "").trim()
        return cleaned.toDoubleOrNull()
    }

    /**
     * 生成去重指纹。
     *
     * 实现已移到 [Fingerprint]，因为 CSV 账单导入也需要用**完全相同**的算法，
     * 否则「通知已记过、账单又出现」的同一笔钱无法跨来源去重。
     */
    private fun fingerprint(
        amount: Double,
        direction: Direction,
    ): String = Fingerprint.of(amount, direction)
}
