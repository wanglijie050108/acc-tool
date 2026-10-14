package com.moneyflow.app.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 原始通知/短信留档。
 *
 * ── 为什么需要这张表 ──
 * 解析规则准不准，取决于真实的通知长什么样。但用户很难主动把原文交出来：
 * 「支付后下拉通知栏、长按、复制」这个操作很多人不会做，也容易忘记。
 *
 * 所以 App 自己在后台把**每一条收到的通知**原文记下来（含未识别的），
 * 用户只要点一下「导出样本」，就能把真实数据发给我调规则。
 *
 * 注意隐私：这里存的是通知全文，可能含验证码、聊天内容等敏感信息。
 * 导出时默认只包含**含金额特征**的记录，且用户可自行删除整张表。
 */
@Entity(tableName = "raw_samples")
data class RawSampleEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,

    /** 来源包名，null 表示短信 */
    @ColumnInfo(name = "packageName")
    val packageName: String?,

    /** 通知标题 / 短信发件人 */
    @ColumnInfo(name = "title")
    val title: String?,

    /** 合并后的完整文本（解析引擎实际拿到的内容） */
    @ColumnInfo(name = "text")
    val text: String,

    /** 收到时间 */
    @ColumnInfo(name = "receivedAt")
    val receivedAt: Long,

    /** 是否被解析引擎识别出金额 */
    @ColumnInfo(name = "matched")
    val matched: Boolean,

    /** 命中的规则 id，未识别为 null */
    @ColumnInfo(name = "ruleId")
    val ruleId: String?,
)
