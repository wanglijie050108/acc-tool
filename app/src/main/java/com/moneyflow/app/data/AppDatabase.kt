package com.moneyflow.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** 枚举与数据库的转换。存成字符串而不是序号，方便以后加枚举值不打乱已有数据 */
class Converters {
    @TypeConverter
    fun directionToString(value: Direction): String = value.name

    @TypeConverter
    fun stringToDirection(value: String): Direction = Direction.fromName(value)

    @TypeConverter
    fun sourceKindToString(value: SourceKind): String = value.name

    @TypeConverter
    fun stringToSourceKind(value: String): SourceKind = SourceKind.fromName(value)
}

@Database(
    entities = [
        TransactionEntity::class,
        RawSampleEntity::class,
        PendingTransferEntity::class,
    ],
    version = 4,
    // 导出 schema：MigrationTestHelper 依赖它验证迁移正确性。
    // 迁移写错会让用户升级后丢数据，所以必须可测。
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun transactionDao(): TransactionDao

    abstract fun rawSampleDao(): RawSampleDao

    abstract fun pendingTransferDao(): PendingTransferDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "moneyflow.db",
                )
                    // 版本 1 → 2：新增 raw_samples 表。
                    // 用迁移而不是 fallbackToDestructiveMigration，
                    // 否则用户升级 App 会丢掉全部流水记录。
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                    .build()
                    .also { instance = it }
            }

        /**
         * v1 → v2：新增原始样本表。
         * 只加表不改动已有表，所以用户的流水数据完全不受影响。
         *
         * 可见性为 internal 是为了让迁移测试能引用它 ——
         * 迁移写错的代价是用户升级后丢数据，必须有测试覆盖。
         */
        internal val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `raw_samples` (
                        `id` INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
                        `packageName` TEXT,
                        `title` TEXT,
                        `text` TEXT NOT NULL,
                        `receivedAt` INTEGER NOT NULL,
                        `matched` INTEGER NOT NULL,
                        `ruleId` TEXT
                    )
                    """.trimIndent()
                )
            }
        }

        /**
         * v2 → v3：新增待确认转账表。
         *
         * 同样只加表、不动已有表，用户的流水和样本数据完全不受影响。
         *
         * ⚠️ 列定义必须与 [PendingTransferEntity] 完全一致（名称、类型、非空），
         * 否则 Room 运行时会抛 IllegalStateException 导致 App 起不来。
         * 这也是为什么 schema JSON 必须导出、迁移必须测试。
         */
        internal val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `pending_transfers` (
                        `id` INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
                        `packageName` TEXT,
                        `sourceName` TEXT NOT NULL,
                        `rawText` TEXT NOT NULL,
                        `detectedAt` INTEGER NOT NULL,
                        `direction` TEXT NOT NULL,
                        `dedupKey` TEXT NOT NULL,
                        `createdAt` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_pending_transfers_detectedAt` " +
                        "ON `pending_transfers` (`detectedAt`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_pending_transfers_dedupKey` " +
                        "ON `pending_transfers` (`dedupKey`)"
                )
            }
        }

        /**
         * v3 → v4：删掉「中性」方向，把历史 `NEUTRAL` 记录按业务语义落成支出或收入。
         *
         * ── 为什么必须写这个迁移 ──
         * `direction` 列存的是枚举**名字字符串**（见 [Converters]），
         * 老版本往库里写过 `"NEUTRAL"`。新版本 [Direction] 已经没有这个值了，
         * 不做处理的话这些行会被 [Direction.fromName] 兜底成 EXPENSE ——
         * 「信用卡还款 3000」会变成一笔支出，静默改掉用户的历史账，
         * 比迁移失败更糟（用户根本不会发现）。
         *
         * ── 映射规则（已与用户确认）──
         * 逐条按记录自身的业务语义判断，不搞一刀切：
         *
         * | 场景 | 判据 | 落到 |
         * |---|---|---|
         * | 提现 / 取现 / 充值 / 转存 | 钱离开本账户 | **支出** |
         * | 还款 / 已还 / 偿还 | 钱离开本账户去还信用卡 | **支出** |
         * | 到账 / 收款 / 收到 | 钱进入本账户 | **收入** |
         * | 其他（无法判断） | 兜底 | **收入** |
         *
         * 为什么「其他」兜底成收入：能进到 `NEUTRAL` 却没有上述关键词的记录，
         * 是极早期版本留下的，数量极少；兜底方向只影响统计展示，
         * 用户可以在界面上点开任意一条手动改（编辑弹窗支持改方向）。
         *
         * ⚠️ 只允许在这里出现字面量 `'NEUTRAL'` —— 它是历史数据，
         * 不是当前枚举的成员，代码里任何地方都不该再引用 `Direction.NEUTRAL`。
         */
        internal val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 顺序有讲究：先用「还款」判定支出，
                // 否则「信用卡还款到账」这种混合文案会被收入分支抢先命中。
                //
                // rawText 可能为 null（手动补录的记录没有原始文本），
                // 所以每个 LIKE 都要用 IFNULL 兜住，否则整条 CASE 结果为 NULL。
                db.execSQL(
                    """
                    UPDATE `transactions`
                    SET `direction` = CASE
                        WHEN IFNULL(`rawText`, '') LIKE '%还款%'
                          OR IFNULL(`rawText`, '') LIKE '%已还%'
                          OR IFNULL(`rawText`, '') LIKE '%偿还%'
                          OR IFNULL(`rawText`, '') LIKE '%提现%'
                          OR IFNULL(`rawText`, '') LIKE '%取现%'
                          OR IFNULL(`rawText`, '') LIKE '%充值%'
                          OR IFNULL(`rawText`, '') LIKE '%转存%'
                            THEN 'EXPENSE'
                        WHEN IFNULL(`rawText`, '') LIKE '%到账%'
                          OR IFNULL(`rawText`, '') LIKE '%收款%'
                          OR IFNULL(`rawText`, '') LIKE '%收到%'
                          OR IFNULL(`rawText`, '') LIKE '%入账%'
                            THEN 'INCOME'
                        ELSE 'INCOME'
                    END
                    WHERE `direction` = 'NEUTRAL'
                    """.trimIndent()
                )

                // 兜底：万一还有别的非 EXPENSE/INCOME 的历史脏值（例如手改过的库），
                // 一并收敛，避免读出来时被 fromName 静默吞掉。
                db.execSQL(
                    """
                    UPDATE `transactions`
                    SET `direction` = 'INCOME'
                    WHERE `direction` NOT IN ('EXPENSE', 'INCOME')
                    """.trimIndent()
                )
            }
        }
    }
}
