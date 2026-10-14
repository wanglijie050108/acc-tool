package com.moneyflow.app

import com.moneyflow.app.parse.CustomRuleStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 手写规则文件的容错测试。
 *
 * ── 为什么需要 ──
 * 用户（以及我发给用户的示例）很容易在 pattern 里写 `\d`，
 * 但 JSON 字符串里反斜杠必须写成 `\\`。写错的话整个文件解析失败、
 * 规则静默消失，用户完全不知道为什么没生效。
 *
 * 这个类验证两种写法都能正常工作。
 */
class RuleJsonToleranceTest {

    @Test
    fun properlyEscapedJsonWorks() {
        // 标准写法：正则里的反斜杠已经转义成 \\
        val json = """[{"id":"a","pattern":"支出([\\d.]+)元","amountGroup":1,"direction":"EXPENSE"}]"""
        val rules = CustomRuleStore.parseMaps(CustomRuleStore.jsonToMaps(json))
        assertEquals(1, rules.size)
        assertEquals("""支出([\d.]+)元""", rules[0].pattern.pattern)
    }

    @Test
    fun unescapedBackslashIsRepaired() {
        // 用户实际会写的样子：反斜杠没转义
        val json = """[{"id":"a","pattern":"支出([\d.]+)元","amountGroup":1,"direction":"EXPENSE"}]"""
        val rules = CustomRuleStore.parseMaps(CustomRuleStore.jsonToMaps(json))
        assertEquals("未转义的反斜杠应被自动修复", 1, rules.size)
        assertEquals("""支出([\d.]+)元""", rules[0].pattern.pattern)
    }

    @Test
    fun escapeHelperLeavesLegalEscapesAlone() {
        // \\ 和 \" 是合法转义，不应被改动
        val input = """{"a":"say \"hi\"","b":"back\\slash"}"""
        val output = CustomRuleStore.escapeLoneBackslashes(input)
        assertEquals(input, output)
    }

    @Test
    fun escapeHelperFixesRegexSequences() {
        // \d \s \w 都该被补成双反斜杠
        val input = """{"p":"\d+\s\w"}"""
        val output = CustomRuleStore.escapeLoneBackslashes(input)
        assertTrue("结果应含双反斜杠", output.contains("\\\\d"))
    }

    @Test
    fun multipleRulesAllParse() {
        val json = """
            [
              {"id":"a","pattern":"支出([\\d.]+)","amountGroup":1,"direction":"EXPENSE"},
              {"id":"b","pattern":"收入([\\d.]+)","amountGroup":1,"direction":"INCOME"},
              {"id":"c","pattern":"到账([\\d.]+)","amountGroup":1,"direction":"INCOME","smsOnly":true}
            ]
        """.trimIndent()
        val rules = CustomRuleStore.parseMaps(CustomRuleStore.jsonToMaps(json))
        assertEquals(3, rules.size)
        assertTrue("smsOnly 标志应被正确读取", rules.any { it.smsOnly })
    }

    @Test
    fun emptyArrayProducesNoRules() {
        assertTrue(CustomRuleStore.parseMaps(CustomRuleStore.jsonToMaps("[]")).isEmpty())
    }

    @Test
    fun nestedArrayIsNotTreatedAsRule() {
        // 数组里混入非法元素应跳过而不是崩
        val json = """[[1,2,3],{"id":"ok","pattern":"支出([\\d.]+)","amountGroup":1,"direction":"EXPENSE"}]"""
        val maps = CustomRuleStore.jsonToMaps(json)
        val rules = CustomRuleStore.parseMaps(maps)
        assertEquals("只应解析出合法的那个", 1, rules.size)
    }

    @Test
    fun numericAndBooleanFieldsAreCoerced() {
        val json = """[{"id":"a","pattern":"x([\\d.]+)","amountGroup":"2","priority":"50","direction":"EXPENSE"}]"""
        // amountGroup 写成字符串是常见笔误，应被容错处理（使用默认值而非崩溃）
        val rules = CustomRuleStore.parseMaps(CustomRuleStore.jsonToMaps(json))
        assertEquals(1, rules.size)
    }

    @Test
    fun unicodeEscapeInPatternIsHandled() {
        val json = """[{"id":"a","pattern":"\u652f\u51fa([\\d.]+)","amountGroup":1,"direction":"EXPENSE"}]"""
        val rules = CustomRuleStore.parseMaps(CustomRuleStore.jsonToMaps(json))
        assertEquals(1, rules.size)
        assertEquals("支出([\\d.]+)", rules[0].pattern.pattern)
    }
}
