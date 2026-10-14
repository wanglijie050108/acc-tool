package com.moneyflow.app

import com.moneyflow.app.csv.CsvParseResult
import com.moneyflow.app.csv.CsvParser
import com.moneyflow.app.data.Direction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CSV 账单解析器测试。
 *
 * 重点覆盖真实文件里的「坑」：
 * - 表头不在第一行（支付宝有 20+ 行说明文字）
 * - 商品名称含逗号（必须正确处理引号）
 * - 微信金额带 ¥ 符号
 * - 「不计收支」的记录要跳过
 * - 千分位、Excel 另存导致的格式差异
 */
class CsvParserTest {

    /** 一份贴近真实的支付宝账单（表头前有说明文字） */
    private val alipaySample = """
        支付宝交易记录明细查询
        账号:[test@example.com]
        起始日期:[2026-09-01 00:00:00]    终止日期:[2026-10-01 00:00:00]
        ---------------------------------交易记录明细列表------------------------------------
        交易号,商家订单号,交易创建时间,付款时间,最近修改时间,交易来源地,类型,交易对方,商品名称,金额（元）,收/支,交易状态,服务费（元）,成功退款（元）,备注,资金状态
        2026101400001,SHOP001,2026-10-14 16:42:03,2026-10-14 16:42:05,2026-10-14 16:42:05,支付宝网站,即时到账交易,星巴克,咖啡,38.00,支出,交易成功,0.00,0.00,,已支出
        2026101400002,SHOP002,2026-10-14 12:10:00,2026-10-14 12:10:02,2026-10-14 12:10:02,支付宝网站,即时到账交易,美团外卖,午餐,45.50,支出,交易成功,0.00,0.00,,已支出
        2026101400003,,2026-10-14 09:00:00,,2026-10-14 09:00:00,支付宝网站,转账,张三,转账,200.00,收入,交易成功,0.00,0.00,,已收入
        2026101400004,,2026-10-14 08:00:00,,2026-10-14 08:00:00,,,余额宝收益,余额宝收益,1.23,不计收支,交易成功,0.00,0.00,,已收入
        -------------------------------------------------------------------------------------
    """.trimIndent()

    /** 一份贴近真实的微信账单 */
    private val wechatSample = """
        微信支付账单明细
        微信昵称:[test]
        起始时间:[2026-09-01 00:00:00] 终止时间:[2026-10-01 00:00:00]
        ----------------------微信支付账单明细列表--------------------
        交易时间,交易类型,交易对方,商品,收/支,金额(元),支付方式,当前状态,交易单号,商户单号,备注
        2026-10-14 18:30:00,商户消费,肯德基,晚餐,支出,¥29.90,零钱,支付成功,42000...,,,
        2026-10-14 10:00:00,转账,李四,转账备注,支出,¥100.00,零钱,已转账,42000...,,,
        2026-10-13 20:00:00,微信红包,王五,/,收入,¥66.60,零钱,已存入零钱,42000...,,,
        2026-10-13 09:00:00,零钱提现,/,提现,/,¥500.00,工商银行,提现已到账,42000...,,,
    """.trimIndent()

    private fun parseOk(text: String): CsvParseResult.Success {
        val r = CsvParser.parse(text)
        assertTrue("应解析成功，实际=$r", r is CsvParseResult.Success)
        return r as CsvParseResult.Success
    }

    // ─────────── 支付宝 ───────────

    @Test
    fun alipayFindsHeaderNotOnFirstLine() {
        val r = parseOk(alipaySample)
        assertTrue("表头前的说明文字不应被当成数据", r.records.isNotEmpty())
        assertEquals("应解析出 3 条有效流水（不含「不计收支」那条）", 3, r.records.size)
    }

    @Test
    fun alipayParsesExpense() {
        val r = parseOk(alipaySample)
        val starbucks = r.records.first { it.merchant == "星巴克" }
        assertEquals(38.0, starbucks.amount, 0.001)
        assertEquals(Direction.EXPENSE, starbucks.direction)
        assertEquals("咖啡", starbucks.description)
    }

    @Test
    fun alipayParsesIncome() {
        val r = parseOk(alipaySample)
        val income = r.records.first { it.direction == Direction.INCOME }
        assertEquals(200.0, income.amount, 0.001)
        assertEquals("张三", income.merchant)
    }

    @Test
    fun alipaySkipsNotCountedRecords() {
        val r = parseOk(alipaySample)
        // 「不计收支」的余额宝收益不应入库
        assertTrue(
            "「不计收支」的记录不应成为流水",
            r.records.none { it.merchant == "余额宝收益" },
        )
    }

    @Test
    fun alipayTimeParsedCorrectly() {
        val r = parseOk(alipaySample)
        val starbucks = r.records.first { it.merchant == "星巴克" }
        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.CHINA)
        assertEquals("2026-10-14 16:42:03", fmt.format(java.util.Date(starbucks.occurredAt)))
    }

    // ─────────── 微信 ───────────

    @Test
    fun wechatParsesWithCurrencySymbol() {
        val r = parseOk(wechatSample)
        val kfc = r.records.first { it.merchant == "肯德基" }
        assertEquals("金额带 ¥ 符号也要能解析", 29.9, kfc.amount, 0.001)
        assertEquals(Direction.EXPENSE, kfc.direction)
    }

    @Test
    fun wechatParsesIncome() {
        val r = parseOk(wechatSample)
        val redPacket = r.records.first { it.direction == Direction.INCOME }
        assertEquals(66.6, redPacket.amount, 0.001)
    }

    @Test
    fun wechatSkipsSlashDirection() {
        val r = parseOk(wechatSample)
        // 零钱提现的「收/支」是「/」，跳过
        assertTrue(
            "「/」方向的记录应跳过",
            r.records.none { it.description == "提现" },
        )
        assertEquals("应解析出 3 条", 3, r.records.size)
    }

    // ─────────── CSV 引号处理（最容易写错的地方） ───────────

    @Test
    fun quotedFieldWithCommaIsOneCell() {
        val cells = CsvParser.splitCsvLine("a,\"b,c\",d")
        assertEquals(listOf("a", "b,c", "d"), cells)
    }

    @Test
    fun escapedQuotesAreHandled() {
        // CSV 里用两个连续引号表示一个字面引号字符
        val line = "a,\"say \"\"hi\"\"\",c"
        val cells = CsvParser.splitCsvLine(line)
        assertEquals(listOf("a", "say \"hi\"", "c"), cells)
    }

    @Test
    fun merchantWithCommaParsedAsSingleField() {
        val csv = """
            交易号,商家订单号,交易创建时间,付款时间,最近修改时间,交易来源地,类型,交易对方,商品名称,金额（元）,收/支,交易状态,服务费（元）,成功退款（元）,备注,资金状态
            001,,2026-10-14 10:00:00,,,网站,消费,"星巴克,国贸店",咖啡,38.00,支出,交易成功,0.00,0.00,,已支出
        """.trimIndent()
        val r = parseOk(csv)
        assertEquals(1, r.records.size)
        assertEquals("星巴克,国贸店", r.records.first().merchant)
    }

    @Test
    fun emptyFieldsDoNotBreakParsing() {
        val csv = """
            交易号,商家订单号,交易创建时间,付款时间,最近修改时间,交易来源地,类型,交易对方,商品名称,金额（元）,收/支,交易状态,服务费（元）,成功退款（元）,备注,资金状态
            001,,2026-10-14 10:00:00,,,网站,消费,,,38.00,支出,交易成功,0.00,0.00,,已支出
        """.trimIndent()
        val r = parseOk(csv)
        assertEquals(1, r.records.size)
        assertEquals(38.0, r.records.first().amount, 0.001)
    }

    // ─────────── 金额格式 ───────────

    @Test
    fun thousandsSeparatorAmount() {
        val csv = """
            交易号,商家订单号,交易创建时间,付款时间,最近修改时间,交易来源地,类型,交易对方,商品名称,金额（元）,收/支,交易状态,服务费（元）,成功退款（元）,备注,资金状态
            001,,2026-10-14 10:00:00,,,网站,消费,某商家,大额,"1,234.56",支出,交易成功,0.00,0.00,,已支出
        """.trimIndent()
        val r = parseOk(csv)
        assertEquals(1234.56, r.records.first().amount, 0.001)
    }

    // ─────────── 异常输入 ───────────

    @Test
    fun emptyFileFails() {
        val r = CsvParser.parse("")
        assertTrue(r is CsvParseResult.Failure)
    }

    @Test
    fun noHeaderFails() {
        val r = CsvParser.parse("aaa,bbb,ccc\n1,2,3")
        assertTrue("没有账单表头应报错", r is CsvParseResult.Failure)
    }

    @Test
    fun headerButNoDataRowsReturnsEmpty() {
        val csv = """
            交易号,商家订单号,交易创建时间,付款时间,最近修改时间,交易来源地,类型,交易对方,商品名称,金额（元）,收/支,交易状态,服务费（元）,成功退款（元）,备注,资金状态
        """.trimIndent()
        val r = parseOk(csv)
        assertEquals(0, r.records.size)
    }

    @Test
    fun malformedRowsAreSkippedNotCrashed() {
        val csv = """
            交易号,商家订单号,交易创建时间,付款时间,最近修改时间,交易来源地,类型,交易对方,商品名称,金额（元）,收/支,交易状态,服务费（元）,成功退款（元）,备注,资金状态
            001,,2026-10-14 10:00:00,,,网站,消费,星巴克,咖啡,38.00,支出,交易成功,0.00,0.00,,已支出
            坏行
            002,,这不是时间,,,网站,消费,肯德基,晚餐,29.90,支出,交易成功,0.00,0.00,,已支出
            003,,2026-10-14 11:00:00,,,网站,消费,麦当劳,午餐,abc,支出,交易成功,0.00,0.00,,已支出
            004,,2026-10-14 12:00:00,,,网站,消费,汉堡王,午餐,45.00,支出,交易成功,0.00,0.00,,已支出
        """.trimIndent()
        val r = parseOk(csv)
        assertEquals("只应保留 2 条正常记录", 2, r.records.size)
        assertTrue("应记录跳过原因", r.skippedReasons.isNotEmpty())
    }

    @Test
    fun utf8BomInHeaderIsStripped() {
        // Excel 另存 CSV 常会加 BOM，导致第一列列名变成 "\uFEFF交易号"
        val csv = "\uFEFF交易号,商家订单号,交易创建时间,付款时间,最近修改时间," +
            "交易来源地,类型,交易对方,商品名称,金额（元）,收/支,交易状态," +
            "服务费（元）,成功退款（元）,备注,资金状态\n" +
            "001,,2026-10-14 10:00:00,,,网站,消费,星巴克,咖啡,38.00,支出," +
            "交易成功,0.00,0.00,,已支出"
        val r = parseOk(csv)
        assertEquals("BOM 不应影响解析", 1, r.records.size)
    }
}
