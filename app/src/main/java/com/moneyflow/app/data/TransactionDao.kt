package com.moneyflow.app.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface TransactionDao {

    /** 主列表：按发生时间倒序。这是 App 唯一的核心视图 */
    @Query("SELECT * FROM transactions ORDER BY occurredAt DESC, id DESC")
    fun observeAll(): Flow<List<TransactionEntity>>

    @Query("SELECT * FROM transactions WHERE id = :id")
    suspend fun findById(id: Long): TransactionEntity?

    @Insert
    suspend fun insert(entity: TransactionEntity): Long

    @Update
    suspend fun update(entity: TransactionEntity)

    @Delete
    suspend fun delete(entity: TransactionEntity)

    /**
     * 找到一个时间窗内指纹相同的记录，用于跨来源去重。
     * 返回 null 表示这是一笔新流水。
     */
    @Query(
        """
        SELECT * FROM transactions
        WHERE fingerprint = :fingerprint
          AND occurredAt BETWEEN :from AND :to
        LIMIT 1
        """
    )
    suspend fun findDuplicate(fingerprint: String, from: Long, to: Long): TransactionEntity?

    /** 手动补录的流水，occurredAt 与指纹无关，这里用于清理历史重复 */
    @Query("DELETE FROM transactions WHERE id = :id")
    suspend fun deleteById(id: Long)

    /** 统计当前记录总数，用于首页展示 */
    @Query("SELECT COUNT(*) FROM transactions")
    fun observeCount(): Flow<Int>
}

/**
 * 待确认转账的读写。
 *
 * 与流水表分开，因为它们的**生命周期完全不同**：
 * 流水是最终数据，待确认是「用户还没处理的事件」，确认或忽略后就该消失。
 */
@Dao
interface PendingTransferDao {

    /** 首页顶部展示用：按检测时间倒序，最新的在最上面 */
    @Query("SELECT * FROM pending_transfers ORDER BY detectedAt DESC, id DESC")
    fun observeAll(): Flow<List<PendingTransferEntity>>

    @Query("SELECT * FROM pending_transfers WHERE id = :id")
    suspend fun findById(id: Long): PendingTransferEntity?

    @Insert
    suspend fun insert(entity: PendingTransferEntity): Long

    @Query("DELETE FROM pending_transfers WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM pending_transfers")
    suspend fun clear()

    /**
     * 去重查询：时间窗内是否已有相同去重键的待确认记录。
     *
     * 与 [TransactionDao.findDuplicate] 同样的思路 ——
     * 键本身不含时间，时间维度靠范围查询，避免分桶边界导致去重失效。
     */
    @Query(
        """
        SELECT * FROM pending_transfers
        WHERE dedupKey = :dedupKey
          AND detectedAt BETWEEN :from AND :to
        LIMIT 1
        """
    )
    suspend fun findDuplicate(dedupKey: String, from: Long, to: Long): PendingTransferEntity?

    /** 只保留最近 N 条，防止用户长期不处理导致无限堆积 */
    @Query(
        """
        DELETE FROM pending_transfers
        WHERE id NOT IN (
            SELECT id FROM pending_transfers ORDER BY detectedAt DESC LIMIT :keep
        )
        """
    )
    suspend fun trimTo(keep: Int)
}

@Dao
interface RawSampleDao {

    @Insert
    suspend fun insert(entity: RawSampleEntity): Long

    /** 按时间倒序取最近若干条，用于导出 */
    @Query("SELECT * FROM raw_samples ORDER BY receivedAt DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<RawSampleEntity>

    /** 只取「含金额特征」的样本，隐私更少，默认导出用这个 */
    @Query(
        """
        SELECT * FROM raw_samples
        WHERE text LIKE '%元%' OR text LIKE '%¥%' OR text LIKE '%￥%'
           OR text LIKE '%支出%' OR text LIKE '%收入%' OR text LIKE '%消费%'
           OR text LIKE '%付款%' OR text LIKE '%扣款%' OR text LIKE '%支付%'
        ORDER BY receivedAt DESC LIMIT :limit
        """
    )
    suspend fun recentMoneyLike(limit: Int): List<RawSampleEntity>

    @Query("SELECT COUNT(*) FROM raw_samples")
    suspend fun count(): Int

    @Query("DELETE FROM raw_samples")
    suspend fun clear()

    /** 只保留最近 N 条，防止无限增长 */
    @Query(
        """
        DELETE FROM raw_samples
        WHERE id NOT IN (
            SELECT id FROM raw_samples ORDER BY receivedAt DESC LIMIT :keep
        )
        """
    )
    suspend fun trimTo(keep: Int)
}
