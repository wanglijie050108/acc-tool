package com.moneyflow.app.parse

import com.moneyflow.app.data.Direction
import com.moneyflow.app.data.SourceKind

/**
 * 一条解析规则。
 *
 * 设计要点：规则是「数据」不是「代码」。识别错了只改这张表，不用碰引擎逻辑。
 * 每条规则的 pattern 必须至少包含一个捕获组用于取金额。
 *
 * @param id            规则唯一标识，出问题时方便定位
 * @param sourceKind    命中后归到哪个来源
 * @param sourceName    来源展示名
 * @param packageNames  只在哪些 App 的通知里生效。见下方 [smsOnly] 的说明
 * @param pattern       正则。金额必须落在 [amountGroup] 指定的捕获组
 * @param amountGroup   金额所在的捕获组序号（从 1 开始）
 * @param direction     资金方向
 * @param merchantGroup 商户名所在的捕获组序号，null 表示不提取
 * @param priority      数字越大越先匹配。默认 0
 * @param smsOnly       true 表示这条规则**只用于短信**，不参与 App 通知的匹配
 */
data class ParseRule(
    val id: String,
    val sourceKind: SourceKind,
    val sourceName: String,
    val packageNames: Set<String>,
    val pattern: Regex,
    val amountGroup: Int = 1,
    val direction: Direction,
    val merchantGroup: Int? = null,
    val priority: Int = 0,
    /**
     * 是否只用于短信。
     *
     * ⚠️ 为什么需要这个标志：
     * 银行短信的规则必须写得**很宽松**（金额后可能没有「元」字、
     * 动词有 存款/取款/支取/现支 十几种、货币前缀可能是 RMB/人民币/￥），
     * 否则覆盖不了各家银行的格式。
     *
     * 但这么宽松的规则如果也去匹配微信/支付宝的通知，
     * 就会因为优先级更高而**抢走本该由微信规则处理的匹配**，
     * 导致商户名提取不到、来源标错。这个 bug 真实发生过。
     *
     * 所以：宽松的短信规则只在「短信」这个通道里生效。
     */
    val smsOnly: Boolean = false,
)
