package com.moneyflow.app

import com.moneyflow.app.data.Direction
import com.moneyflow.app.parse.CustomRuleStore
import com.moneyflow.app.parse.ParseEngine
import com.moneyflow.app.parse.ParseResult
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 自定义规则系统测试。
 *
 * 用户会手写正则，出错是常态。这个系统必须：
 * - 正则非法 → 跳过该条，不影响其他规则和 App 启动
 * - 括号编号越界 → 跳过，不能崩
 * - 自定义规则优先于内置规则（这样用户能覆盖内置的错误识别）
 * - 导入的 JSON 损坏 → 不崩，返回空
 */
class CustomRuleTest {

    @After
    fun tearDown() {
        // 每个用例后清空，避免互相影响
        ParseEngine.setCustomRules(emptyList())
    }

    /**
     * 走「不依赖 Android」的解析路径。
     *
     * org.json 在 JVM 单元测试里是空壳（调用会抛 "not mocked"），
     * 所以测试直接用 jsonToMaps + parseMaps 这条纯 Kotlin 路径 ——
     * 它和真机上走的是同一套规则解析逻辑。
     */
    private fun parse(json: String) =
        CustomRuleStore.parseMaps(CustomRuleStore.jsonToMaps(json))

    private fun ruleJson(
        id: String = "r1",
        pattern: String,
        amountGroup: Int = 1,
        direction: String = "EXPENSE",
        sourceName: String = "自定义",
        priority: Int = 12,
        smsOnly: Boolean = false,
    ): String {
        // ⚠️ 正则里的反斜杠在 JSON 字符串里必须转义成 \\，
        // 否则 \d 这种序列不是合法 JSON 转义，文件根本解析不了。
        // 这里的转义是为了让测试用的 JSON 合法 —— 真机上用户手写规则时同样要注意。
        val escaped = pattern.replace("\\", "\\\\").replace("\"", "\\\"")
        return """
            [{
              "id": "$id",
              "pattern": "$escaped",
              "amountGroup": $amountGroup,
              "direction": "$direction",
              "sourceName": "$sourceName",
              "priority": $priority,
              "smsOnly": $smsOnly
            }]
        """.trimIndent()
    }

    // ─────────── 基本功能 ───────────

    @Test
    fun customRuleMatchesTextBuiltinCannotHandle() {
        // 一个内置规则不认识的格式
        val weird = "【新新银行】您尾号1111的卡发生了一笔 66.00 的交易"
        assertTrue("前提：内置规则应认不出", ParseEngine.parse(weird, null, 0L) is ParseResult.NoAmount)

        ParseEngine.setCustomRules(parse(ruleJson(pattern = """发生了一笔\s*([\d.]+)\s*的交易""")))
        val r = ParseEngine.parse(weird, null, 0L)
        assertTrue("加了自定义规则后应能识别", r is ParseResult.Success)
        assertEquals(66.0, (r as ParseResult.Success).amount, 0.001)
    }

    @Test
    fun customRuleTakesPriorityOverBuiltin() {
        // 内置规则会把这条识别成支出 58.00（通过 sms_expense）
        val text = "您尾号1234账户支出人民币58.00元"
        val before = ParseEngine.parse(text, null, 0L) as ParseResult.Success
        assertEquals(Direction.EXPENSE, before.direction)

        // 用户认为这条规则判断错了，加一条覆盖它
        ParseEngine.setCustomRules(
            parse(
                ruleJson(pattern = """支出人民币([\d,]+\.?\d*)元""", direction = "NEUTRAL", priority = 99)
            )
        )
        val after = ParseEngine.parse(text, null, 0L) as ParseResult.Success
        assertEquals("自定义规则应覆盖内置规则的方向判断", Direction.NEUTRAL, after.direction)
    }

    @Test
    fun customRuleCanSpecifyAmountGroup() {
        // 金额在第 2 个括号里
        ParseEngine.setCustomRules(
            parse(
                ruleJson(pattern = """(?:消费于)(.{1,10}?)\s*金额([\d.]+)""", amountGroup = 2)
            )
        )
        val r = ParseEngine.parse("消费于星巴克 金额38.50", null, 0L)
        assertTrue(r is ParseResult.Success)
        assertEquals(38.50, (r as ParseResult.Success).amount, 0.001)
    }

    @Test
    fun customRuleRespectsDirectionSetting() {
        ParseEngine.setCustomRules(
            parse(ruleJson(pattern = """到账([\d.]+)""", direction = "INCOME"))
        )
        val r = ParseEngine.parse("到账500.00", null, 0L) as ParseResult.Success
        assertEquals(Direction.INCOME, r.direction)
    }

    // ─────────── 健壮性：用户会写错 ───────────

    @Test
    fun invalidRegexIsSkippedNotCrashed() {
        // 未闭合的括号是非法正则
        val rules = parse(ruleJson(pattern = """支出([\d.]+"""))
        assertTrue("非法正则应被跳过", rules.isEmpty())

        // 且不能影响引擎工作
        ParseEngine.setCustomRules(rules)
        val r = ParseEngine.parse("您尾号1234账户支出人民币58.00元", null, 0L)
        assertTrue("内置规则应照常工作", r is ParseResult.Success)
    }

    @Test
    fun outOfRangeAmountGroupIsSkipped() {
        // 正则里只有 1 个括号，却要求取第 5 个
        ParseEngine.setCustomRules(
            parse(ruleJson(pattern = """支出([\d.]+)""", amountGroup = 5))
        )
        // 不应崩溃；该规则匹配时会因组号越界被跳过
        val r = ParseEngine.parse("支出999.00", null, 0L)
        assertTrue("组号越界应被安全跳过，实际=$r", r is ParseResult.Success || r is ParseResult.NoAmount)
    }

    @Test
    fun zeroOrNegativeAmountGroupRejected() {
        val rules = parse(ruleJson(pattern = """支出([\d.]+)""", amountGroup = 0))
        assertTrue("组号必须 ≥ 1，非法值应被拒绝", rules.isEmpty())
    }

    @Test
    fun malformedJsonReturnsEmptyNotCrash() {
        val rules = parse("{ this is not valid json }")
        assertTrue("损坏的 JSON 应返回空列表", rules.isEmpty())
    }

    @Test
    fun oneBadRuleDoesNotKillOthers() {
        // 数组里第一条非法、第二条正常
        val json = """
            [
              {"id":"bad","pattern":"([unclosed","amountGroup":1,"direction":"EXPENSE"},
              {"id":"good","pattern":"到账([\\d.]+)","amountGroup":1,"direction":"INCOME","sourceName":"好的"}
            ]
        """.trimIndent()
        val rules = parse(json)
        assertEquals("应只保留合法的那条", 1, rules.size)

        ParseEngine.setCustomRules(rules)
        val r = ParseEngine.parse("到账123.45", null, 0L)
        assertTrue("合法规则应生效", r is ParseResult.Success)
    }

    @Test
    fun missingRequiredFieldsAreSkipped() {
        // 缺 id
        assertTrue(parse("""[{"pattern":"支出([\\d.]+)"}]""").isEmpty())
        // 缺 pattern
        assertTrue(parse("""[{"id":"x"}]""").isEmpty())
        // id 为空
        assertTrue(parse("""[{"id":"","pattern":"支出([\\d.]+)"}]""").isEmpty())
    }

    @Test
    fun unknownDirectionFallsBackToNeutral() {
        val rules = parse(
            ruleJson(pattern = """支出([\d.]+)""", direction = "NOT_A_REAL_DIRECTION")
        )
        assertEquals(1, rules.size)
        assertEquals(Direction.NEUTRAL, rules[0].direction)
    }

    // ─────────── smsOnly 通道隔离 ───────────

    @Test
    fun smsOnlyCustomRuleDoesNotFireOnAppNotifications() {
        ParseEngine.setCustomRules(
            parse(
                ruleJson(pattern = """付款\s*([\d.]+)""", smsOnly = true, priority = 99)
            )
        )
        // 这是一条 App 通知（有包名），smsOnly 规则不该生效
        val r = ParseEngine.parse("微信支付 向 星巴克 付款 ¥38.00", "com.tencent.mm", 0L)
        assertTrue(r is ParseResult.Success)
        r as ParseResult.Success
        assertEquals("smsOnly 规则不应抢走微信规则的匹配", "星巴克", r.merchant)
    }

    @Test
    fun smsOnlyCustomRuleFiresOnSms() {
        ParseEngine.setCustomRules(
            parse(
                ruleJson(pattern = """到账金额([\d.]+)""", smsOnly = true, direction = "INCOME")
            )
        )
        val r = ParseEngine.parse("您有一笔到账金额888.00", null, 0L)
        assertTrue("短信通道下 smsOnly 规则应生效", r is ParseResult.Success)
        assertEquals(888.0, (r as ParseResult.Success).amount, 0.001)
    }

    // ─────────── 数量与长度限制 ───────────

    @Test
    fun ruleCountIsCapped() {
        val many = (1..(CustomRuleStore.MAX_RULES + 50)).joinToString(",") { i ->
            """{"id":"r$i","pattern":"测试$i([\\d.]+)","amountGroup":1,"direction":"EXPENSE"}"""
        }
        val rules = parse("[$many]")
        assertTrue(
            "规则数应被限制在 ${CustomRuleStore.MAX_RULES} 以内，实际=${rules.size}",
            rules.size <= CustomRuleStore.MAX_RULES,
        )
    }

    @Test
    fun veryLongTextDoesNotHang() {
        // 超长文本应被快速拒绝，而不是让正则跑很久
        val huge = "支".repeat(100_000) + "支出123.45"
        val start = System.currentTimeMillis()
        ParseEngine.parse(huge, null, 0L)
        val elapsed = System.currentTimeMillis() - start
        assertTrue("超长文本应被快速处理，实际耗时 ${elapsed}ms", elapsed < 2_000)
    }

    @Test
    fun clearingRulesRestoresBuiltinBehavior() {
        // 故意把这条识别成「收入」，验证自定义规则能覆盖内置判断
        ParseEngine.setCustomRules(
            parse(ruleJson(pattern = """支出人民币([\d,]+\.?\d*)元""", direction = "INCOME"))
        )
        val text = "您尾号1234账户支出人民币58.00元"
        assertEquals(Direction.INCOME, (ParseEngine.parse(text, null, 0L) as ParseResult.Success).direction)

        ParseEngine.setCustomRules(emptyList())
        assertEquals(
            "清空自定义规则后应恢复内置判断",
            Direction.EXPENSE,
            (ParseEngine.parse(text, null, 0L) as ParseResult.Success).direction,
        )
    }
}
