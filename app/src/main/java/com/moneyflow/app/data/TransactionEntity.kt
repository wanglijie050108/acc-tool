package com.moneyflow.app.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** 资金流动方向 */
enum class Direction {
    /** 支出：钱从你手里出去 */
    EXPENSE,

    /** 收入：钱进到你手里 */
    INCOME,

    /** 中性：转账、还款等，方向不明确或双向对冲 */
    NEUTRAL;

    companion object {
        fun fromName(name: String): Direction =
            entries.firstOrNull { it.name == name } ?: NEUTRAL
    }
}

/** 记录来源。用于区分自动抓取和手动补录，也用于统计各渠道识别质量 */
enum class SourceKind {
    /** 微信支付通知 */
    WECHAT_NOTIFICATION,

    /** 支付宝通知 */
    ALIPAY_NOTIFICATION,

    /** 银行 App 通知 */
    BANK_NOTIFICATION,

    /** 银行短信 */
    BANK_SMS,

    /** 其他 App 通知 */
    OTHER_NOTIFICATION,

    /** 手动补录 */
    MANUAL,

    /** CSV 账单导入（预留，P4 阶段启用） */
    CSV_IMPORT;

    companion object {
        fun fromName(name: String): SourceKind =
            entries.firstOrNull { it.name == name } ?: OTHER_NOTIFICATION
    }
}

/**
 * 一条流水记录。
 *
 * 设计原则（来自需求确认）：
 * - 不分类：category 字段保留但不使用，为后续功能预留
 * - 不追踪余额：只记录金额和流动方向
 * - 只要有金钱变动都记：支出、收入、转账、退款、还款全部入库
 */
@Entity(
    tableName = "transactions",
    indices = [
        Index(value = ["occurredAt"]),
        Index(value = ["fingerprint"]),
    ]
)
data class TransactionEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,

    /** 金额，单位「元」，始终为正数。方向由 direction 字段决定 */
    @ColumnInfo(name = "amount")
    val amount: Double,

    /** 资金流动方向 */
    @ColumnInfo(name = "direction")
    val direction: Direction,

    /** 记录来源 */
    @ColumnInfo(name = "sourceKind")
    val sourceKind: SourceKind,

    /** 来源展示名，例如「微信支付」「招商银行」 */
    @ColumnInfo(name = "sourceName")
    val sourceName: String,

    /** 商户/对手方，可能为空 */
    @ColumnInfo(name = "merchant")
    val merchant: String? = null,

    /** 原始文本（通知内容或短信全文）。保留用于排查识别错误 */
    @ColumnInfo(name = "rawText")
    val rawText: String? = null,

    /** 发生时间，毫秒时间戳 */
    @ColumnInfo(name = "occurredAt")
    val occurredAt: Long,

    /** 记录创建时间，毫秒时间戳 */
    @ColumnInfo(name = "createdAt")
    val createdAt: Long = System.currentTimeMillis(),

    /** 用户备注 */
    @ColumnInfo(name = "note")
    val note: String? = null,

    /** 用户手动标记：已核对无误 */
    @ColumnInfo(name = "confirmed")
    val confirmed: Boolean = false,

    /**
     * 去重指纹。同一笔支付可能同时产生「微信通知」和「银行短信」，
     * 指纹相同则只保留一条。手动补录的记录 fingerprint 为 null，不参与去重。
     */
    @ColumnInfo(name = "fingerprint")
    val fingerprint: String? = null,

    /** 分类。当前版本不使用，为后续功能预留 */
    @ColumnInfo(name = "category")
    val category: String? = null,
)
