package com.moneyflow.app.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 待确认的转账 —— 「检测到一笔转账，金额是多少？」
 *
 * ── 为什么需要这张表 ──
 * 微信/支付宝给好友转账的通知里**没有金额**（真机确认），
 * 解析引擎拿不到金额，会返回 [com.moneyflow.app.parse.ParseResult.NoAmount]。
 * 旧版本直接丢弃，用户永远不知道漏了一笔。
 *
 * 现在把这类通知挂起成「待确认」，用户点一下、补个金额就入库。
 *
 * ── ⚠️ 为什么必须持久化，不能用内存队列 ──
 * 转账后用户很可能立刻切走，App 被国产 ROM 冻结甚至杀掉是常态
 * （[com.moneyflow.app.capture.KeepAliveService] 的注释里记录了
 * REDMI K80 上「已授权但监听根本没连上」的实测）。
 * 如果用内存队列，进程一死待确认就没了 —— 用户既没记上账，
 * 也不知道自己漏了，这正是本功能要解决的原始问题。
 *
 * 存表里则最坏情况也只是「晚点再提醒」，不会丢。
 */
@Entity(
    tableName = "pending_transfers",
    indices = [
        Index(value = ["detectedAt"]),
        // 去重用：同一条通知重复推送时不重复挂起
        Index(value = ["dedupKey"]),
    ],
)
data class PendingTransferEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,

    /** 来源包名，例如 com.tencent.mm */
    @ColumnInfo(name = "packageName")
    val packageName: String?,

    /** 来源展示名，例如「微信转账」 */
    @ColumnInfo(name = "sourceName")
    val sourceName: String,

    /** 通知原文（「[转账]已被接收」之类）。保留用于排查和展示 */
    @ColumnInfo(name = "rawText")
    val rawText: String,

    /** 通知到达时间。这就是这笔转账的发生时间 */
    @ColumnInfo(name = "detectedAt")
    val detectedAt: Long,

    /**
     * 猜测的资金方向。**只是默认值，允许用户改**。
     * 见 TransferSignal.guessDirection 的说明：
     * 微信对转账双方推的是同一句话，无法从文案可靠区分收/付。
     */
    @ColumnInfo(name = "direction")
    val direction: Direction,

    /**
     * 去重键。
     *
     * 转账通知可能重复推送（微信有时会推多条），
     * 且用户可能反复进出账单页。用「包名 + 原文」做键，
     * 配合时间窗口判断，避免同一笔转账挂起多条。
     */
    @ColumnInfo(name = "dedupKey")
    val dedupKey: String,

    /** 记录创建时间 */
    @ColumnInfo(name = "createdAt")
    val createdAt: Long = System.currentTimeMillis(),
) {
    companion object {
        /**
         * 生成去重键。
         *
         * ⚠️ 有意**不含时间**：微信的重复推送可能间隔十几秒，
         * 若把分钟编进键里会像 Fingerprint 踩过的坑一样跨桶失效。
         * 时间维度的判断交给 DAO 的范围查询。
         */
        fun dedupKeyOf(packageName: String?, rawText: String): String =
            "${packageName.orEmpty()}|${rawText.trim()}"
    }
}
