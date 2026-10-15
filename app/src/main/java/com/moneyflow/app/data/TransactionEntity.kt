package com.moneyflow.app.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 资金流动方向。
 *
 * ⚠️ **只有支出和收入两个方向，没有「中性」。**
 *
 * 曾经有过第三个值 `NEUTRAL`，用来表示「转账/还款/提现这类账户间转移，
 * 方向不明确」。它被删掉了，原因是：
 *
 *   1. **统计上等于没记。** 首页的月度汇总只按 EXPENSE/INCOME 求和，
 *      NEUTRAL 的记录金额是黑色无符号数字，既不进支出也不进收入 ——
 *      用户提现 500 元，界面上看不出任何变化，只会以为「App 没记上」。
 *   2. **它把「不知道」当成了答案。** 提现、还款这些场景其实方向是确定的
 *      （零钱提现 = 零钱少了一笔 = 支出），标成 NEUTRAL 只是逃避判断。
 *      方向说不清的真正解法是**去问用户**，而不是替他记一个模糊值
 *      （见 [com.moneyflow.app.capture.TransferSignal] 的「待确认」流程）。
 *
 * 约定：**解析规则必须给出确定方向**。确实判断不了的场景，
 * 负责任的做法是不记账并留档/待确认，而不是记一条方向不明的流水。
 *
 * ⚠️ 数据库里 `direction` 存的是枚举**名字字符串**（见 `Converters`）。
 * 老版本写入的 `"NEUTRAL"` 行由 `AppDatabase.MIGRATION_3_4` 映射成
 * EXPENSE / INCOME；若绕过迁移直接读到，[fromName] 会兜底成 EXPENSE。
 */
enum class Direction {
    /** 支出：钱从你手里出去 */
    EXPENSE,

    /** 收入：钱进到你手里 */
    INCOME;

    companion object {
        /**
         * 把库里存的字符串转回枚举。
         *
         * 兜底成 [EXPENSE] 而不是抛异常：数据读不出来时，
         * **让 App 能启动**比严格校验重要（转换器在 Room 初始化时就跑，
         * 抛异常会让整个 App 起不来）。历史遗留的 `"NEUTRAL"` 也走这里。
         */
        fun fromName(name: String): Direction =
            entries.firstOrNull { it.name == name } ?: EXPENSE
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

    /**
     * 由「待确认转账」补录而来。
     *
     * 与 [MANUAL] 的区别值得单独记：这条记录**源于一条自动捕获的通知**，
     * 只是金额由用户补上。将来若要统计「转账漏记有多少」，
     * 靠这个值就能区分出来，而 MANUAL 会把手动和半自动混在一起。
     */
    PENDING_TRANSFER_CONFIRMED,

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
