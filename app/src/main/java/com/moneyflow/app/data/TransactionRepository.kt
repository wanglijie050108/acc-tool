package com.moneyflow.app.data

import android.content.Context
import com.moneyflow.app.parse.Fingerprint
import kotlinx.coroutines.flow.Flow

/**
 * 数据仓库。所有写入都经过这里，是「去重」和「入库」的唯一入口。
 * UI 层和抓取层都只跟它打交道，不直接碰 DAO。
 */
class TransactionRepository(private val dao: TransactionDao) {

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

    companion object {
        /** 去重时间窗：90 秒。通知和短信到达时间通常相差几秒到几十秒 */
        const val DEDUP_WINDOW_MS = 90_000L

        @Volatile
        private var instance: TransactionRepository? = null

        fun get(context: Context): TransactionRepository =
            instance ?: synchronized(this) {
                instance ?: TransactionRepository(
                    AppDatabase.get(context).transactionDao()
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
