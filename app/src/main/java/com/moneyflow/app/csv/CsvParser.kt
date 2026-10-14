package com.moneyflow.app.csv

import com.moneyflow.app.data.Direction
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * 支付宝 / 微信账单 CSV 解析器。
 *
 * ── 为什么需要这个（来自需求确认）──
 * 通知抓取是「实时的但可能漏」，账单导入是「全的但要手动」。
 * 用户明确要求把它做成可选的高级功能：开启就用，不开启就只走通知。
 *
 * ── 支付宝账单 CSV 的真实结构（很容易踩坑）──
 * 导出的 CSV 前面有若干行说明文字，真正的表头不在第一行，例如：
 *
 *   支付宝交易记录明细查询
 *   账号:[xxx@example.com]
 *   起始日期:[2026-09-01 00:00:00]    终止日期:[2026-10-01 00:00:00]
 *   ---------------------------------
 *   交易号,商家订单号,交易创建时间,付款时间,最近修改时间,交易来源地,类型,
 *   交易对方,商品名称,金额（元）,收/支,交易状态,服务费（元）,成功退款（元）,
 *   备注,资金状态
 *   2026101412345...,...,2026-10-14 16:42:03,...,即时到账交易,星巴克,咖啡,
 *   38.00,支出,交易成功,0.00,0.00,,已支出
 *
 * 所以不能假设「第一行是表头」，必须扫描找到真正的表头行。
 *
 * ── 微信账单 CSV ──
 * 微信的是「------微信支付账单明细列表------」之后跟表头：
 *   交易时间,交易类型,交易对方,商品,收/支,金额(元),支付方式,当前状态,...
 * 微信的金额字段带 ¥ 符号，且「收/支」列写作「支出」「收入」「/」。
 */
object CsvParser {

    /** 支付宝表头里必然出现的列名 */
    private val ALIPAY_HEADER_MARKERS = listOf("交易号", "交易创建时间", "收/支")

    /** 微信表头里必然出现的列名 */
    private val WECHAT_HEADER_MARKERS = listOf("交易时间", "交易类型", "收/支")

    private val dateFormats = listOf(
        "yyyy-MM-dd HH:mm:ss",
        "yyyy/MM/dd HH:mm:ss",
        "yyyy-MM-dd HH:mm",
        "yyyy/MM/dd HH:mm",
        "yyyyMMddHHmmss",
    )

    /**
     * 解析 CSV 文本。
     *
     * @param text 文件内容（需已按正确编码解码）
     */
    fun parse(text: String): CsvParseResult {
        if (text.isBlank()) return CsvParseResult.Failure("文件是空的")

        val lines = text.lines()
        // 注意：不能在这里要求「至少 2 行」。
        // 只有表头、没有数据行的文件是合法的（用户导出了但当期无交易），
        // 应该返回「解析成功但 0 条记录」，而不是报错。
        // 真正的有效性由下面「能否找到表头」来判断。
        if (lines.isEmpty()) return CsvParseResult.Failure("文件没有内容")

        // 1. 找到真正的表头行
        val headerIndex = findHeaderIndex(lines)
            ?: return CsvParseResult.Failure(
                "找不到账单表头。请确认这是支付宝或微信导出的原始 CSV 文件，" +
                    "而不是 Excel 另存过的版本。"
            )

        val header = splitCsvLine(lines[headerIndex])
        val columnMap = mapColumns(header)

        // 判断是哪个平台的账单，用于来源展示名
        val sourceName = detectSourceName(lines[headerIndex])

        // 必须能定位到「时间」「金额」「收/支」三列，否则没法用
        val timeCol = columnMap["time"]
        val amountCol = columnMap["amount"]
        val directionCol = columnMap["direction"]

        if (amountCol == null || directionCol == null) {
            return CsvParseResult.Failure(
                "表头里找不到「金额」或「收/支」列。实际表头：${header.joinToString(",")}"
            )
        }

        val records = mutableListOf<CsvRecord>()
        val skippedReasons = mutableListOf<String>()
        var skipped = 0

        // 2. 逐行解析数据
        for (i in (headerIndex + 1) until lines.size) {
            val raw = lines[i]
            if (raw.isBlank()) {
                skipped++
                continue
            }

            val cells = splitCsvLine(raw)

            // 行内字段数明显不对，跳过（支付宝文件末尾常有统计行）
            if (cells.size < header.size / 2) {
                skipped++
                continue
            }

            val directionRaw = cells.getOrNull(directionCol)?.trim().orEmpty()
            val direction = parseDirection(directionRaw)
            if (direction == null) {
                // 「不计收支」「/」这类记录不算流水，正常跳过
                skipped++
                if (directionRaw.isNotBlank() && skippedReasons.size < 5) {
                    skippedReasons += "第 ${i + 1} 行「收/支」为「$directionRaw」，不计入流水"
                }
                continue
            }

            val amountRaw = cells.getOrNull(amountCol)?.trim().orEmpty()
            val amount = parseAmount(amountRaw)
            if (amount == null || amount <= 0.0) {
                skipped++
                if (amountRaw.isNotBlank() && skippedReasons.size < 5) {
                    skippedReasons += "第 ${i + 1} 行金额无法识别：「$amountRaw」"
                }
                continue
            }

            val timeRaw = timeCol?.let { cells.getOrNull(it)?.trim() }.orEmpty()
            val occurredAt = parseTime(timeRaw)
            if (occurredAt == null) {
                skipped++
                if (timeRaw.isNotBlank() && skippedReasons.size < 5) {
                    skippedReasons += "第 ${i + 1} 行时间无法识别：「$timeRaw」"
                }
                continue
            }

            val merchant = columnMap["merchant"]?.let { cells.getOrNull(it)?.trim() }
                ?.takeIf { it.isNotBlank() }
            val description = columnMap["description"]?.let { cells.getOrNull(it)?.trim() }
                ?.takeIf { it.isNotBlank() }
            val category = columnMap["category"]?.let { cells.getOrNull(it)?.trim() }
                ?.takeIf { it.isNotBlank() }

            records += CsvRecord(
                occurredAt = occurredAt,
                amount = amount,
                direction = direction,
                merchant = merchant,
                description = description,
                category = category,
                sourceName = sourceName,
                lineNumber = i + 1,
                rawLine = raw,
            )
        }

        return CsvParseResult.Success(
            records = records,
            skippedLines = skipped,
            skippedReasons = skippedReasons,
        )
    }

    /**
     * 找到真正的表头行。
     *
     * 支付宝/微信的导出文件前面都有若干行说明，不能假设第一行就是表头。
     * 逐行检查是否包含标志性列名。
     */
    private fun findHeaderIndex(lines: List<String>): Int? {
        lines.forEachIndexed { index, line ->
            // 只看前 60 行，避免在超长文件里误判数据行
            if (index > 60) return null
            val hasAlipay = ALIPAY_HEADER_MARKERS.count { line.contains(it) } >= 2
            val hasWechat = WECHAT_HEADER_MARKERS.count { line.contains(it) } >= 2
            if (hasAlipay || hasWechat) return index
        }
        return null
    }

    /**
     * 根据表头判断账单来源，用于来源展示名。
     *
     * 支付宝和微信的列名有区别：
     *   支付宝有「交易号」「交易创建时间」
     *   微信有「交易单号」「交易时间」
     */
    private fun detectSourceName(headerLine: String): String = when {
        headerLine.contains("交易号") || headerLine.contains("交易创建时间") -> "支付宝账单"
        headerLine.contains("交易单号") || headerLine.contains("微信") -> "微信账单"
        else -> "账单导入"
    }

    /**
     * 把表头映射成「用途 → 列号」。
     *
     * 支付宝和微信的列名不同（如「商品名称」vs「商品」），这里做兼容。
     */
    private fun mapColumns(header: List<String>): Map<String, Int> {
        val map = mutableMapOf<String, Int>()

        header.forEachIndexed { i, raw ->
            val name = raw.trim().trim('"', '\uFEFF')
            when {
                // 时间：支付宝是「交易创建时间」，微信是「交易时间」
                name.contains("交易创建时间") -> map["time"] = i
                name == "交易时间" -> map["time"] = i
                // 金额：支付宝是「金额（元）」，微信是「金额(元)」
                name.startsWith("金额") -> map["amount"] = i
                // 方向
                name == "收/支" || name == "收支" -> map["direction"] = i
                // 交易对方
                name == "交易对方" -> map["merchant"] = i
                // 商品说明
                name.contains("商品名称") || name == "商品" -> map["description"] = i
                // 类型（支付宝的「类型」列可用作分类）
                name == "类型" || name == "交易类型" -> {
                    if (!map.containsKey("category")) map["category"] = i
                }
                // 备注
                name == "备注" -> map["remark"] = i
            }
        }

        // 时间列兜底：如果没识别到，用「付款时间」或「最近修改时间」
        if (!map.containsKey("time")) {
            header.forEachIndexed { i, raw ->
                val name = raw.trim().trim('"', '\uFEFF')
                if (name.contains("付款时间") || name.contains("创建时间")) {
                    map["time"] = i
                }
            }
        }

        return map
    }

    /**
     * 解析「收/支」列。
     *
     * @return 支出/收入；「不计收支」「/」等返回 null 表示跳过该行
     */
    private fun parseDirection(raw: String): Direction? = when {
        raw.contains("支出") -> Direction.EXPENSE
        raw.contains("收入") -> Direction.INCOME
        // 支付宝有「不计收支」，微信有「/」，这些是转账/充值之类，不算流水
        raw.contains("不计") -> null
        raw == "/" || raw.isBlank() -> null
        else -> null
    }

    /**
     * 金额解析。需要处理多种写法：
     *   "38.00"      普通
     *   "¥38.00"     微信带货币符号
     *   "1,234.56"   千分位
     *   "\"38.00\""  CSV 引号包裹（若未被正确剥离）
     */
    private fun parseAmount(raw: String): Double? {
        val cleaned = raw
            .replace("\"", "")
            .replace("¥", "")
            .replace("￥", "")
            .replace(",", "")
            .replace("元", "")
            .trim()
        if (cleaned.isEmpty()) return null
        return cleaned.toDoubleOrNull()
    }

    /** 时间解析。支付宝格式是 "2026-10-14 16:42:03" */
    private fun parseTime(raw: String): Long? {
        val cleaned = raw.replace("\"", "").trim()
        if (cleaned.isEmpty()) return null
        for (pattern in dateFormats) {
            try {
                val fmt = SimpleDateFormat(pattern, Locale.CHINA)
                fmt.isLenient = false
                val parsed = fmt.parse(cleaned) ?: continue
                return parsed.time
            } catch (_: Exception) {
                // 换下一个格式
            }
        }
        return null
    }

    /**
     * 按 CSV 规则切分一行。
     *
     * 必须处理引号，因为支付宝账单里的「商品名称」常含逗号，例如：
     *   星巴克,咖啡,拿铁     ← 三列
     *   "咖啡,拿铁"          ← 一列，含逗号
     * 简单用 split(",") 会把后者切错。
     */
    internal fun splitCsvLine(line: String): List<String> {
        val result = mutableListOf<String>()
        val current = StringBuilder()
        var inQuotes = false
        var i = 0

        while (i < line.length) {
            val c = line[i]
            when {
                c == '"' -> {
                    // 连续两个引号表示一个字面引号
                    if (inQuotes && i + 1 < line.length && line[i + 1] == '"') {
                        current.append('"')
                        i++
                    } else {
                        inQuotes = !inQuotes
                    }
                }
                c == ',' && !inQuotes -> {
                    result += current.toString()
                    current.clear()
                }
                else -> current.append(c)
            }
            i++
        }
        result += current.toString()
        return result
    }
}
