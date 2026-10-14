package com.moneyflow.app.parse

import android.content.Context
import com.moneyflow.app.data.Direction
import com.moneyflow.app.data.SourceKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 自定义规则存储。
 *
 * ── 为什么需要这个 ──
 * 内置规则写死在代码里，用户遇到不认识的格式时只能等开发者改代码 + 重新安装。
 * 但真实世界里的通知格式千变万化（各家银行、各版本微信支付宝），
 * 靠发版追是追不完的。
 *
 * 参考了成熟项目 AutoAccounting 的做法：把规则做成**用户可编辑的数据**，
 * 遇到新格式时用户自己加一条即可，不用等发版。
 *
 * ── 规则文件位置 ──
 * 存在 App 私有目录 `files/custom_rules.json`，纯本地。
 * 用户可以在「规则管理」页里导入/导出，便于分享和备份。
 *
 * ── 安全 ──
 * 用户写的正则可能很糟（灾难性回溯），所以：
 *   - 限制单条文本的解析长度（见 MAX_MATCH_LENGTH）
 *   - 加载时校验正则合法性，非法的跳过并提示
 *   - 限制规则总数
 */
object CustomRuleStore {

    private const val FILE_NAME = "custom_rules.json"

    /** 最多允许多少条自定义规则，防止用户误导入超大文件 */
    const val MAX_RULES = 200

    /** 规则匹配的文本长度上限，避免正则灾难性回溯卡死 */
    const val MAX_MATCH_LENGTH = 1000

    @Volatile
    private var cached: List<ParseRule>? = null

    private fun file(context: Context) = File(context.filesDir, FILE_NAME)

    /** 读取自定义规则。解析失败的单条会被跳过，不影响其他规则 */
    fun load(context: Context): List<ParseRule> {
        cached?.let { return it }

        val f = file(context)
        if (!f.exists()) {
            cached = emptyList()
            return emptyList()
        }

        val loaded = try {
            parseJson(f.readText(Charsets.UTF_8))
        } catch (t: Throwable) {
            emptyList()
        }
        cached = loaded
        return loaded
    }

    /** 强制重新读取（导入新规则后调用） */
    fun invalidate() {
        cached = null
    }

    /** 保存规则列表 */
    suspend fun save(context: Context, rules: List<ParseRule>) = withContext(Dispatchers.IO) {
        val json = JSONArray()
        rules.take(MAX_RULES).forEach { r ->
            json.put(
                JSONObject().apply {
                    put("id", r.id)
                    put("pattern", r.pattern.pattern)
                    put("amountGroup", r.amountGroup)
                    put("direction", r.direction.name)
                    put("sourceName", r.sourceName)
                    put("sourceKind", r.sourceKind.name)
                    put("priority", r.priority)
                    r.merchantGroup?.let { put("merchantGroup", it) }
                    put("smsOnly", r.smsOnly)
                }
            )
        }
        file(context).writeText(json.toString(2), Charsets.UTF_8)
        invalidate()
    }

    /**
     * 解析 JSON 文本。
     *
     * 单条规则出错时跳过并继续，而不是整个文件失败 ——
     * 用户手写规则难免出错，不该因为一条写错就全部失效。
     *
     * 注意：这里先把 JSON 转成纯 Kotlin 结构再处理，
     * 而不是直接用 org.json —— org.json 是 Android 框架类，
     * 在 JVM 单元测试里是空壳（方法会抛 "not mocked"），
     * 导致这段逻辑没法测。拆开后核心逻辑可以纯 JVM 测试。
     */
    fun parseJson(text: String): List<ParseRule> =
        parseMaps(jsonToMaps(text))

    /** JSON 数组 → 若干 Map。失败返回空列表而不是抛异常 */
    internal fun jsonToMaps(text: String): List<Map<String, Any?>> {
        val trimmed = text.trim()

        // 优先用自带的极简解析器：它是纯 Kotlin，不依赖 Android，
        // 所以在 JVM 单元测试里也能跑（org.json 在单测里是空壳）。
        // 真机上两种实现的结果一致。
        try {
            val parsed = MiniJson.parseArray(trimmed)
            if (parsed.isNotEmpty() || trimmed == "[]") return parsed
        } catch (_: Throwable) {
            // 落到下面的宽松/标准解析
        }

        // 手写正则忘记转义反斜杠（`\d` 而非 `\\d`）是常见错误，
        // 会导致整个文件解析失败、规则静默消失。这里兜底修复一次。
        try {
            val repaired = escapeLoneBackslashes(trimmed)
            val parsed = MiniJson.parseArray(repaired)
            if (parsed.isNotEmpty()) return parsed
        } catch (_: Throwable) {
            // 继续尝试标准解析
        }

        // 最后再用平台 JSON 试一次（兼容 MiniJson 未覆盖的写法）
        return try {
            standardJsonToMaps(trimmed)
        } catch (_: Throwable) {
            emptyList()
        }
    }

    private fun standardJsonToMaps(text: String): List<Map<String, Any?>> {
        val array = JSONArray(text)
        return (0 until array.length()).mapNotNull { i ->
            val obj = array.optJSONObject(i) ?: return@mapNotNull null
            val map = mutableMapOf<String, Any?>()
            obj.keys().forEach { key -> map[key] = obj.opt(key) }
            map
        }
    }

    /**
     * 把 JSON 字符串里非法/未转义的反斜杠补成合法的双反斜杠。
     *
     * 只处理「反斜杠后面跟的不是合法 JSON 转义字符」的情况，
     * 已经写对的 \\、\"、\n 等保持不动。
     */
    internal fun escapeLoneBackslashes(json: String): String {
        val sb = StringBuilder(json.length + 16)
        var i = 0
        while (i < json.length) {
            val c = json[i]
            if (c == '\\' && i + 1 < json.length) {
                val next = json[i + 1]
                val isLegalEscape = next == '"' || next == '\\' || next == '/' ||
                    next == 'b' || next == 'f' || next == 'n' || next == 'r' || next == 't' ||
                    next == 'u'
                if (isLegalEscape) {
                    sb.append(c).append(next)
                } else {
                    // \d \s \w 这类正则转义 → 补成 \\d
                    sb.append("\\\\").append(next)
                }
                i += 2
            } else {
                sb.append(c)
                i++
            }
        }
        return sb.toString()
    }

    /**
     * 纯 Kotlin 的规则解析逻辑，不依赖 Android。
     * 这样可以在 JVM 单元测试里完整覆盖。
     */
    internal fun parseMaps(maps: List<Map<String, Any?>>): List<ParseRule> {
        val result = mutableListOf<ParseRule>()
        for (map in maps.take(MAX_RULES)) {
            val rule = try {
                toRuleFromMap(map)
            } catch (_: Throwable) {
                null
            }
            if (rule != null) result += rule
        }
        return result
    }

    private fun toRuleFromMap(map: Map<String, Any?>): ParseRule? {
        val id = (map["id"] as? String)?.takeIf { it.isNotBlank() } ?: return null
        val patternText = (map["pattern"] as? String)?.takeIf { it.isNotBlank() } ?: return null

        // 正则本身非法 → 跳过
        val regex = try {
            Regex(patternText)
        } catch (_: Throwable) {
            return null
        }

        val direction = Direction.fromName((map["direction"] as? String) ?: "EXPENSE")
        val sourceName = (map["sourceName"] as? String)?.takeIf { it.isNotBlank() } ?: "自定义规则"
        val sourceKind = SourceKind.fromName((map["sourceKind"] as? String) ?: "OTHER_NOTIFICATION")
        val amountGroup = (map["amountGroup"] as? Number)?.toInt() ?: 1
        val priority = (map["priority"] as? Number)?.toInt() ?: 12
        val merchantGroup = (map["merchantGroup"] as? Number)?.toInt()
        val smsOnly = (map["smsOnly"] as? Boolean) ?: false

        // 组号必须为正；越界的组在匹配时会被引擎跳过
        if (amountGroup < 1) return null

        return ParseRule(
            id = "custom_$id",
            sourceKind = sourceKind,
            sourceName = sourceName,
            // 自定义规则不限定包名，让用户在任意来源上使用
            packageNames = emptySet(),
            pattern = regex,
            amountGroup = amountGroup,
            direction = direction,
            merchantGroup = merchantGroup,
            priority = priority,
            smsOnly = smsOnly,
        )
    }

    private fun toRule(obj: JSONObject): ParseRule? {
        val map = mutableMapOf<String, Any?>()
        obj.keys().forEach { key -> map[key] = obj.opt(key) }
        return toRuleFromMap(map)
    }

    /** 导出为可读 JSON，便于用户备份或分享给别人 */
    fun exportJson(context: Context): String {
        val f = file(context)
        return if (f.exists()) f.readText(Charsets.UTF_8) else "[]"
    }
}
