package com.moneyflow.app.parse

/**
 * 极简 JSON 解析器 —— 只支持本项目的规则文件格式。
 *
 * ── 为什么不用 org.json ──
 * `org.json` 是 Android 框架类，在 JVM 单元测试里是**空壳**：
 * 调用任何方法都抛 `RuntimeException: Method ... not mocked`。
 * 这导致规则解析这段关键逻辑完全没法在电脑上跑测试。
 *
 * 自己实现一个小解析器后：
 *   - 核心逻辑可以在 JVM 上完整测试（秒级，不需要模拟器）
 *   - 真机与测试环境行为完全一致
 *
 * ── 支持范围 ──
 * 对象、数组、字符串（含 \u 转义）、数字、true/false/null。
 * 不支持注释和尾逗号 —— 与标准 JSON 保持一致，避免解析出用户没写的东西。
 */
internal object MiniJson {

    /** 解析顶层数组，返回每个元素的键值对 */
    fun parseArray(text: String): List<Map<String, Any?>> {
        val p = Parser(text)
        p.skipWhitespace()
        val result = p.parseArrayValue()
        p.skipWhitespace()
        // 允许末尾有空白，但不应有剩余内容
        if (!p.atEnd()) throw IllegalArgumentException("JSON 结尾有多余内容")
        return result.map { value ->
            (value as? Map<*, *>)?.entries
                ?.associate { (k, v) -> k.toString() to v }
                ?: emptyMap()
        }
    }

    private class Parser(private val s: String) {
        private var i = 0

        fun atEnd() = i >= s.length

        fun skipWhitespace() {
            while (i < s.length && s[i].isWhitespace()) i++
        }

        fun parseArrayValue(): List<Any?> {
            expect('[')
            val list = mutableListOf<Any?>()
            skipWhitespace()
            if (peek() == ']') {
                i++
                return list
            }
            while (true) {
                skipWhitespace()
                list += parseValue()
                skipWhitespace()
                when (peek()) {
                    ',' -> { i++ }
                    ']' -> { i++; return list }
                    else -> throw IllegalArgumentException("数组里期待 , 或 ]，位置 $i")
                }
            }
        }

        private fun parseValue(): Any? {
            skipWhitespace()
            if (atEnd()) throw IllegalArgumentException("JSON 意外结束")
            return when (val c = s[i]) {
                '{' -> parseObject()
                '[' -> parseArrayValue()
                '"' -> parseString()
                't' -> { expectWord("true"); true }
                'f' -> { expectWord("false"); false }
                'n' -> { expectWord("null"); null }
                else -> {
                    if (c == '-' || c.isDigit()) parseNumber()
                    else throw IllegalArgumentException("无法解析的字符 '$c'，位置 $i")
                }
            }
        }

        private fun parseObject(): Map<String, Any?> {
            expect('{')
            val map = LinkedHashMap<String, Any?>()
            skipWhitespace()
            if (peek() == '}') {
                i++
                return map
            }
            while (true) {
                skipWhitespace()
                val key = parseString()
                skipWhitespace()
                expect(':')
                skipWhitespace()
                map[key] = parseValue()
                skipWhitespace()
                when (peek()) {
                    ',' -> { i++ }
                    '}' -> { i++; return map }
                    else -> throw IllegalArgumentException("对象里期待 , 或 }，位置 $i")
                }
            }
        }

        private fun parseString(): String {
            expect('"')
            val sb = StringBuilder()
            while (true) {
                if (atEnd()) throw IllegalArgumentException("字符串未闭合")
                val c = s[i++]
                when {
                    c == '"' -> return sb.toString()
                    c == '\\' -> {
                        if (atEnd()) throw IllegalArgumentException("转义符后意外结束")
                        when (val e = s[i++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (i + 4 > s.length) {
                                    throw IllegalArgumentException("\\u 转义不完整")
                                }
                                val hex = s.substring(i, i + 4)
                                i += 4
                                sb.append(hex.toInt(16).toChar())
                            }
                            else -> throw IllegalArgumentException(
                                "非法转义 \\$e —— JSON 字符串里反斜杠必须写成 \\\\"
                            )
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }

        private fun parseNumber(): Number {
            val start = i
            if (peek() == '-') i++
            while (!atEnd() && (s[i].isDigit() || s[i] == '.' ||
                        s[i] == 'e' || s[i] == 'E' || s[i] == '+' || s[i] == '-')
            ) {
                i++
            }
            val raw = s.substring(start, i)
            return raw.toLongOrNull() ?: raw.toDoubleOrNull()
                ?: throw IllegalArgumentException("非法数字：$raw")
        }

        private fun expect(c: Char) {
            skipWhitespace()
            if (atEnd() || s[i] != c) {
                throw IllegalArgumentException("期待 '$c'，位置 $i")
            }
            i++
        }

        private fun expectWord(word: String) {
            if (i + word.length > s.length || s.substring(i, i + word.length) != word) {
                throw IllegalArgumentException("期待 $word，位置 $i")
            }
            i += word.length
        }

        private fun peek(): Char = if (atEnd()) '\u0000' else s[i]
    }
}
