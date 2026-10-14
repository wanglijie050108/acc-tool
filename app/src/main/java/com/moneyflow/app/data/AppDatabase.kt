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
    entities = [TransactionEntity::class, RawSampleEntity::class],
    version = 2,
    // 导出 schema：MigrationTestHelper 依赖它验证迁移正确性。
    // 迁移写错会让用户升级后丢数据，所以必须可测。
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun transactionDao(): TransactionDao

    abstract fun rawSampleDao(): RawSampleDao

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
                    .addMigrations(MIGRATION_1_2)
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
    }
}
