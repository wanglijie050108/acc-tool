package com.moneyflow.app.data

import android.content.Context
import com.moneyflow.app.parse.Fingerprint
import kotlinx.coroutines.flow.Flow

/**
 * 数据仓库。所有写入都经过这里，是「去重」和「入库」的唯一入口。
 * UI 层和抓取层都只跟它打交道，不直接碰 DAO。
 */
class TransactionRepository(
    private val dao: TransactionDao,
    private val pendingDao: PendingTransferDao,
) {

    fun observeAll(): Flow<List<TransactionEntity>> = dao.observeAll()

    fun observeCount(): Flow<Int> = dao.observeCount()

    suspend fun findById(id: Long): TransactionEntity? = dao.findById(id)

    /** 手动补录 / 编辑保存 */
    suspend fun save(entity: TransactionEntity) {
        if (entity.id == 0L) {
            dao.insert(entity)
        } else {
            dao.update(entity)
        }
    }

    suspend fun delete(entity: TransactionEntity) = dao.delete(entity)

    suspend fun deleteById(id: Long) = dao.deleteById(id)

    /**
     * 自动抓取的入库入口，带去重。
     *
     * 去重逻辑：同一时间窗（±[windowMs]）内，指纹相同的记录视为重复。
     * 场景：微信买东西，既来了微信支付通知，又来了银行扣款短信 —— 只记一笔。
     *
     * @return true 表示新入库，false 表示判定为重复被丢弃
     */
    suspend fun insertDeduplicated(
        entity: TransactionEntity,
        windowMs: Long = DEDUP_WINDOW_MS,
    ): Boolean {
        val fingerprint = entity.fingerprint
        if (fingerprint != null) {
            val from = entity.occurredAt - windowMs
            val to = entity.occurredAt + windowMs
            if (dao.findDuplicate(fingerprint, from, to) != null) {
                return false
            }
        }
        dao.insert(entity)
        return true
    }

    // ─────────────── 待确认转账 ───────────────

    /**
     * 挂起一条待确认的转账。
     *
     * 微信的转账通知可能重复推送，所以这里做去重：
     * 同一去重键、在 [PENDING_DEDUP_WINDOW_MS] 内已存在则不再挂起。
     *
     * ⚠️ 时间窗比流水的 90 秒长得多：转账通知的重复推送可能间隔几分钟，
     * 而且用户可能在通知栏反复看到。宁可窗口开大一点 ——
     * 代价只是「同一笔转账少提醒一次」，而重复提醒会直接让用户烦躁。
     *
     * @return true 表示新挂起，false 表示判定为重复
     */
    suspend fun addPendingTransfer(
        entity: PendingTransferEntity,
        windowMs: Long = PENDING_DEDUP_WINDOW_MS,
    ): Boolean {
        val from = entity.detectedAt - windowMs
        val to = entity.detectedAt + windowMs
        if (pendingDao.findDuplicate(entity.dedupKey, from, to) != null) return false

        pendingDao.insert(entity)
        pendingDao.trimTo(MAX_PENDING_TRANSFERS)
        return true
    }

    fun observePendingTransfers(): Flow<List<PendingTransferEntity>> =
        pendingDao.observeAll()

    suspend fun findPendingTransfer(id: Long): PendingTransferEntity? =
        pendingDao.findById(id)

    /**
     * 按去重键找刚插入的那条待确认记录。
     *
     * 用途：插入后需要拿到自增 id 去构造通知跳转，
     * 而 Room 的 @Insert 虽然能返回 id，但 [addPendingTransfer] 返回的是
     * Boolean（是否新增）。这里补一个查询，语义更清楚。
     */
    suspend fun findPendingTransferByDedupKey(
        dedupKey: String,
        aroundAt: Long,
        windowMs: Long = PENDING_DEDUP_WINDOW_MS,
    ): PendingTransferEntity? =
        pendingDao.findDuplicate(dedupKey, aroundAt - windowMs, aroundAt + windowMs)

    suspend fun deletePendingTransfer(id: Long) = pendingDao.deleteById(id)

    /**
     * 把一条待确认转账变成正式流水。
     *
     * ⚠️ 用 [insertDeduplicated] 而不是直接 insert：
     * 用户补录的时间点，那笔转账的**银行短信**可能已经到了并被记过
     * （如果钱是从银行卡扣的）。直接 insert 会产生两条记录。
     *
     * @return true 表示新入库，false 表示与已有流水重复（此时也删除待确认项）
     */
    suspend fun confirmPendingTransfer(
        pendingId: Long,
        amount: Double,
        direction: Direction,
        merchant: String?,
        note: String?,
    ): Boolean {
        val pending = pendingDao.findById(pendingId) ?: return false

        val entity = TransactionEntity(
            amount = amount,
            direction = direction,
            sourceKind = SourceKind.PENDING_TRANSFER_CONFIRMED,
            sourceName = pending.sourceName,
            merchant = merchant,
            rawText = pending.rawText,
            occurredAt = pending.detectedAt,
            note = note,
            // 用户亲手确认过的，标记为已核对
            confirmed = true,
            fingerprint = Fingerprint.of(amount, direction),
        )

        val inserted = insertDeduplicated(entity)
        // 无论是否重复，待确认项都该消失 —— 它已经被处理过了。
        // 若保留，用户会以为还没处理，反复点同一个提醒。
        pendingDao.deleteById(pendingId)
        return inserted
    }

    /** 用户点「忽略」：这条不是转账，或不想记 */
    suspend fun dismissPendingTransfer(id: Long) = pendingDao.deleteById(id)

    companion object {
        /** 去重时间窗：90 秒。通知和短信到达时间通常相差几秒到几十秒 */
        const val DEDUP_WINDOW_MS = 90_000L

        /** 待确认转账的去重窗口：10 分钟（比流水宽，见 addPendingTransfer 说明） */
        const val PENDING_DEDUP_WINDOW_MS = 10 * 60_000L

        /** 待确认记录最多保留多少条，防止用户长期不处理导致堆积 */
        const val MAX_PENDING_TRANSFERS = 50

        @Volatile
        private var instance: TransactionRepository? = null

        fun get(context: Context): TransactionRepository =
            instance ?: synchronized(this) {
                instance ?: TransactionRepository(
                    AppDatabase.get(context).transactionDao(),
                    AppDatabase.get(context).pendingTransferDao(),
                ).also { instance = it }
            }
    }

    // ─────────────── CSV 账单导入 ───────────────

    /**
     * 批量导入 CSV 解析出的记录。
     *
     * 去重策略与实时抓取一致：用「金额 + 方向」指纹 + ±90 秒窗口。
     * 这样「通知已经记过、账单里又出现一次」的流水不会重复入库，
     * 而账单里通知没抓到的那些会被补上 —— 这正是导入功能的价值。
     *
     * @return 实际新增的条数（去重后）
     */
    suspend fun importCsvRecords(records: List<com.moneyflow.app.csv.CsvRecord>): Int {
        var inserted = 0
        for (rec in records) {
            val entity = TransactionEntity(
                amount = rec.amount,
                direction = rec.direction,
                sourceKind = SourceKind.CSV_IMPORT,
                sourceName = rec.sourceName,
                merchant = rec.merchant,
                rawText = rec.rawLine,
                occurredAt = rec.occurredAt,
                note = rec.description,
                category = rec.category,
                // 账单导入的数据是「权威来源」，直接标记为已核对。
                // 指纹与实时抓取共用同一套算法，才能跨来源去重。
                confirmed = true,
                fingerprint = Fingerprint.of(rec.amount, rec.direction),
            )
            if (insertDeduplicated(entity)) inserted++
        }
        return inserted
    }
}
