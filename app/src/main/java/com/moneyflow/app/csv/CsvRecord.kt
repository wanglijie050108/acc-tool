package com.moneyflow.app.csv

import com.moneyflow.app.data.Direction

/**
 * 一条从 CSV 解析出来的待入库记录。
 *
 * 与 TransactionEntity 的区别：这里是「原始解析结果」，
 * 还没有生成指纹、还没落库，方便先预览给用户确认。
 */
data class CsvRecord(
    /** 交易时间，毫秒时间戳 */
    val occurredAt: Long,

    /** 金额，始终为正 */
    val amount: Double,

    /** 资金方向 */
    val direction: Direction,

    /** 交易对方 / 商户 */
    val merchant: String?,

    /** 商品说明，作为备注 */
    val description: String?,

    /** 交易分类（支付宝账单里有，微信没有） */
    val category: String?,

    /** 来源展示名，例如「支付宝账单」 */
    val sourceName: String,

    /** 该行在原始文件里的行号，用于报错定位 */
    val lineNumber: Int,

    /** 原始行内容，保留用于排查 */
    val rawLine: String,
)

/** CSV 解析结果 */
sealed interface CsvParseResult {
    /** 解析成功。records 可能为空（文件里没有有效流水） */
    data class Success(
        val records: List<CsvRecord>,
        /** 被跳过的行数（表头、空行、不计收支的记录等） */
        val skippedLines: Int,
        /** 跳过的原因摘要，用于提示用户 */
        val skippedReasons: List<String>,
    ) : CsvParseResult

    /** 解析失败 */
    data class Failure(val reason: String) : CsvParseResult
}
