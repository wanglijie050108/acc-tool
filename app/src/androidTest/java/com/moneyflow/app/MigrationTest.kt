package com.moneyflow.app

import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.moneyflow.app.data.AppDatabase
import com.moneyflow.app.data.Direction
import com.moneyflow.app.data.SourceKind
import com.moneyflow.app.data.TransactionEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 数据库迁移测试：v1 → v2（新增 raw_samples 表）。
 *
 * ⚠️ 为什么这个测试很重要：
 * 迁移写错的代价是**用户升级 App 后丢掉全部流水**，不可挽回。
 * 而且这类错误在开发期完全看不出来 —— 只有「装了旧版 → 升到新版」才会触发。
 *
 * ── 为什么不用 MigrationTestHelper ──
 * 它要求提供 v1 的 schema JSON 做比对。但 v1 是上线前的内部版本，
 * 从未导出过 schema；手工伪造一份 JSON 只是在验证一份虚构的契约，
 * 反而会给出虚假的安全感。
 *
 * 这里用更直接的方式：**真的建一个 v1 库 → 真的跑迁移 → 真的断言数据还在**，
 * 验证的是实际行为而不是 schema 声明。
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private var dbName = "migration-test.db"

    /** v1 时代的建表语句（从当时的 entity 定义抄下来） */
    private val v1TransactionTable = """
        CREATE TABLE IF NOT EXISTS `transactions` (
            `id` INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
            `amount` REAL NOT NULL,
            `direction` TEXT NOT NULL,
            `sourceKind` TEXT NOT NULL,
            `sourceName` TEXT NOT NULL,
            `merchant` TEXT,
            `rawText` TEXT,
            `occurredAt` INTEGER NOT NULL,
            `createdAt` INTEGER NOT NULL,
            `note` TEXT,
            `confirmed` INTEGER NOT NULL,
            `fingerprint` TEXT,
            `category` TEXT
        )
    """.trimIndent()

    /** 建一个真实的 v1 数据库文件，写入若干流水 */
    private fun createV1Database(rows: List<Triple<Double, String, String>>) {
        context.deleteDatabase(dbName)
        val config = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(dbName)
            .callback(object : SupportSQLiteOpenHelper.Callback(1) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    db.execSQL(v1TransactionTable)
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS index_transactions_occurredAt " +
                            "ON transactions (occurredAt)"
                    )
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS index_transactions_fingerprint " +
                            "ON transactions (fingerprint)"
                    )
                    // Room 用它校验身份，缺少会导致后续打开失败
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS room_master_table " +
                            "(id INTEGER PRIMARY KEY, identity_hash TEXT)"
                    )
                    db.execSQL(
                        "INSERT OR REPLACE INTO room_master_table (id, identity_hash) " +
                            "VALUES(42, '')"
                    )
                }

                override fun onUpgrade(db: SupportSQLiteDatabase, old: Int, new: Int) = Unit
            })
            .build()

        val helper = FrameworkSQLiteOpenHelperFactory().create(config)
        helper.writableDatabase.use { db ->
            rows.forEach { (amount, direction, sourceName) ->
                db.execSQL(
                    "INSERT INTO transactions " +
                        "(amount, direction, sourceKind, sourceName, occurredAt, createdAt, confirmed) " +
                        "VALUES (?, ?, 'WECHAT_NOTIFICATION', ?, 1700000000000, 1700000000000, 0)",
                    arrayOf(amount, direction, sourceName),
                )
            }
        }
        helper.close()
    }

    /** 用真实 Room 打开 v1 库，触发迁移到 v2 */
    private fun openWithMigration(): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .addMigrations(AppDatabase.MIGRATION_1_2)
            .allowMainThreadQueries()
            .build()

    @Test
    fun migrationPreservesExistingTransactions() = runTest {
        createV1Database(
            listOf(
                Triple(58.0, "EXPENSE", "微信支付"),
                Triple(25.5, "EXPENSE", "支付宝"),
                Triple(5000.0, "INCOME", "银行短信"),
            )
        )

        val db = openWithMigration()
        try {
            val all = db.transactionDao().observeAll().first()
            assertEquals("迁移后流水条数不应变化", 3, all.size)
            // 数据内容也要完好
            assertTrue(all.any { it.amount == 58.0 && it.sourceName == "微信支付" })
            assertTrue(all.any { it.amount == 5000.0 && it.direction == Direction.INCOME })
        } finally {
            db.close()
        }
    }

    @Test
    fun migrationCreatesUsableRawSamplesTable() = runTest {
        createV1Database(listOf(Triple(58.0, "EXPENSE", "微信支付")))

        val db = openWithMigration()
        try {
            val dao = db.rawSampleDao()
            // 新表必须能写能读
            dao.insert(
                com.moneyflow.app.data.RawSampleEntity(
                    packageName = "com.tencent.mm",
                    title = null,
                    text = "微信支付-¥58.00",
                    receivedAt = 1_700_000_000_000L,
                    matched = true,
                    ruleId = "wechat_pay_generic",
                )
            )
            assertEquals(1, dao.count())
            val recent = dao.recent(10)
            assertEquals("微信支付-¥58.00", recent.first().text)
        } finally {
            db.close()
        }
    }

    @Test
    fun migrationStillWorksOnEmptyV1Database() = runTest {
        createV1Database(emptyList())
        val db = openWithMigration()
        try {
            assertEquals(0, db.transactionDao().observeAll().first().size)
            assertEquals(0, db.rawSampleDao().count())
        } finally {
            db.close()
        }
    }

    @Test
    fun dataIsWritableAfterMigration() = runTest {
        createV1Database(listOf(Triple(58.0, "EXPENSE", "微信支付")))
        val db = openWithMigration()
        try {
            // 迁移后继续记账必须正常
            db.transactionDao().insert(
                TransactionEntity(
                    amount = 99.0,
                    direction = Direction.EXPENSE,
                    sourceKind = SourceKind.MANUAL,
                    sourceName = "手动记录",
                    occurredAt = 1_800_000_000_000L,
                )
            )
            val all = db.transactionDao().observeAll().first()
            assertEquals(2, all.size)
            assertTrue(all.any { it.amount == 99.0 })
        } finally {
            db.close()
        }
    }
}
