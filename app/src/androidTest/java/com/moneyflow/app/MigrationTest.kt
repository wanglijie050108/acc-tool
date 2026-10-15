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

                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
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

    /**
     * 用真实 Room 打开旧库，触发迁移一路升到最新版。
     *
     * ⚠️ **每加一个迁移都必须登记到这里**，否则测试跑的是一条
     * 与线上不同的升级路径 —— 迁移本身没人验证，用户升级时才炸。
     */
    private fun openWithMigration(): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .addMigrations(
                AppDatabase.MIGRATION_1_2,
                AppDatabase.MIGRATION_2_3,
                AppDatabase.MIGRATION_3_4,
            )
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

    // ─────────────── v2 → v3：新增待确认转账表 ───────────────

    /**
     * v2 时代的建表语句：有 transactions 和 raw_samples，没有 pending_transfers。
     * 这是「用户已经装了带样本功能的版本」的状态。
     */
    private fun createV2Database() {
        context.deleteDatabase(dbName)
        val config = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(dbName)
            .callback(object : SupportSQLiteOpenHelper.Callback(2) {
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
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS room_master_table " +
                            "(id INTEGER PRIMARY KEY, identity_hash TEXT)"
                    )
                    db.execSQL(
                        "INSERT OR REPLACE INTO room_master_table (id, identity_hash) " +
                            "VALUES(42, '')"
                    )
                }

                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            })
            .build()

        val helper = FrameworkSQLiteOpenHelperFactory().create(config)
        helper.writableDatabase.use { db ->
            db.execSQL(
                "INSERT INTO transactions " +
                    "(amount, direction, sourceKind, sourceName, occurredAt, createdAt, confirmed) " +
                    "VALUES (58.0, 'EXPENSE', 'WECHAT_NOTIFICATION', '微信支付', " +
                    "1700000000000, 1700000000000, 0)"
            )
            db.execSQL(
                "INSERT INTO raw_samples " +
                    "(packageName, text, receivedAt, matched) " +
                    "VALUES ('com.tencent.mm', '微信支付-¥58.00', 1700000000000, 1)"
            )
        }
        helper.close()
    }

    @Test
    fun migrationV2ToV3PreservesExistingData() = runTest {
        createV2Database()

        val db = openWithMigration()
        try {
            // 已有的流水和样本都必须完好
            assertEquals("流水不应丢失", 1, db.transactionDao().observeAll().first().size)
            assertEquals("样本不应丢失", 1, db.rawSampleDao().count())
            // 新表可用
            assertEquals(0, db.pendingTransferDao().observeAll().first().size)
        } finally {
            db.close()
        }
    }

    @Test
    fun migrationV2ToV3CreatesUsablePendingTransfersTable() = runTest {
        createV2Database()

        val db = openWithMigration()
        try {
            val dao = db.pendingTransferDao()
            dao.insert(
                com.moneyflow.app.data.PendingTransferEntity(
                    packageName = "com.tencent.mm",
                    sourceName = "微信转账",
                    rawText = "[转账]已被接收",
                    detectedAt = 1_700_000_000_000L,
                    direction = Direction.EXPENSE,
                    dedupKey = "com.tencent.mm|[转账]已被接收",
                )
            )
            val all = dao.observeAll().first()
            assertEquals(1, all.size)
            assertEquals("[转账]已被接收", all.first().rawText)
            assertEquals(Direction.EXPENSE, all.first().direction)
        } finally {
            db.close()
        }
    }

    /** 去重键查询：这是防止同一笔转账反复提醒的关键 */
    @Test
    fun pendingTransferDedupQueryWorks() = runTest {
        createV2Database()

        val db = openWithMigration()
        try {
            val dao = db.pendingTransferDao()
            val key = "com.tencent.mm|[转账]已被接收"
            dao.insert(
                com.moneyflow.app.data.PendingTransferEntity(
                    packageName = "com.tencent.mm",
                    sourceName = "微信转账",
                    rawText = "[转账]已被接收",
                    detectedAt = 1_700_000_000_000L,
                    direction = Direction.EXPENSE,
                    dedupKey = key,
                )
            )

            // 窗口内 → 命中
            assertTrue(
                dao.findDuplicate(key, 1_700_000_000_000L - 1000, 1_700_000_000_000L + 1000) != null
            )
            // 窗口外 → 不命中（用户后来真的又转了一笔相同的）
            assertTrue(
                dao.findDuplicate(key, 1_700_000_000_000L + 900_000, 1_700_000_000_000L + 901_000) == null
            )
        } finally {
            db.close()
        }
    }

    // ─────────────── v3 → v4：删掉「中性」方向 ───────────────

    /**
     * v3 时代的库：transactions 表里存着老版本写入的 `'NEUTRAL'` 行。
     *
     * ⚠️ 这是**真实会发生在用户手机上**的状态 —— 老版本把提现、还款、
     * 信用卡还款都记成 NEUTRAL。新版本枚举里已经没有这个值了，
     * 如果不迁移，这些行会被 [Direction.fromName] 兜底成 EXPENSE，
     * 「信用卡还款 3000」就静默变成一笔支出，用户根本不会发现。
     *
     * @param rows (金额, 方向字符串, 原文)，原文可为 null（手动补录没有原文）
     */
    private fun createV3DatabaseWithDirections(rows: List<Triple<Double, String, String?>>) {
        context.deleteDatabase(dbName)
        val config = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(dbName)
            .callback(object : SupportSQLiteOpenHelper.Callback(3) {
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
                    // v3 已有的另外两张表（迁移不碰它们，但 Room 校验 schema 需要存在）
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
                        "CREATE INDEX IF NOT EXISTS index_pending_transfers_detectedAt " +
                            "ON pending_transfers (detectedAt)"
                    )
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS index_pending_transfers_dedupKey " +
                            "ON pending_transfers (dedupKey)"
                    )
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS room_master_table " +
                            "(id INTEGER PRIMARY KEY, identity_hash TEXT)"
                    )
                    db.execSQL(
                        "INSERT OR REPLACE INTO room_master_table (id, identity_hash) " +
                            "VALUES(42, '')"
                    )
                }

                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            })
            .build()

        val helper = FrameworkSQLiteOpenHelperFactory().create(config)
        helper.writableDatabase.use { db ->
            rows.forEachIndexed { i, (amount, direction, raw) ->
                db.execSQL(
                    "INSERT INTO transactions " +
                        "(amount, direction, sourceKind, sourceName, rawText, " +
                        "occurredAt, createdAt, confirmed) " +
                        "VALUES (?, ?, 'BANK_SMS', '银行短信', ?, ?, ?, 0)",
                    arrayOf(amount, direction, raw, 1_700_000_000_000L + i, 1_700_000_000_000L + i)
                )
            }
        }
        helper.close()
    }

    /**
     * ⚠️ 本项目最重要的一条迁移测试。
     *
     * 逐条断言「方向按业务语义落位」，而不是笼统地「没崩就行」：
     * 映射错了不会报错，只会静静改掉用户的历史账 —— 比崩溃更难发现。
     */
    @Test
    fun migrationV3ToV4MapsNeutralByBusinessMeaning() = runTest {
        createV3DatabaseWithDirections(
            listOf(
                Triple(500.0, "NEUTRAL", "您尾号1234账户提现人民币500.00元"),
                Triple(3000.0, "NEUTRAL", "您尾号1234账户还款人民币3000.00元"),
                Triple(200.0, "NEUTRAL", "信用卡还款成功 ¥200.00"),
                Triple(88.0, "NEUTRAL", "微信支付 到账 ¥88.00"),
                Triple(66.0, "NEUTRAL", null),
                Triple(58.0, "EXPENSE", "您尾号1234账户支出人民币58.00元"),
                Triple(900.0, "INCOME", "您尾号1234账户收入人民币900.00元"),
            )
        )

        val db = openWithMigration()
        try {
            val all = db.transactionDao().observeAll().first()
            assertEquals("一条都不能丢", 7, all.size)

            val byRaw = all.associateBy { it.rawText }

            fun directionOf(raw: String) = byRaw.getValue(raw).direction

            // 提现：钱离开账户 → 支出
            assertEquals(
                "提现应落成支出",
                Direction.EXPENSE,
                directionOf("您尾号1234账户提现人民币500.00元"),
            )
            // 还款：钱离开储蓄卡 → 支出
            assertEquals(
                "还款应落成支出",
                Direction.EXPENSE,
                directionOf("您尾号1234账户还款人民币3000.00元"),
            )
            assertEquals(
                "信用卡还款应落成支出",
                Direction.EXPENSE,
                directionOf("信用卡还款成功 ¥200.00"),
            )
            // 到账：钱进入账户 → 收入
            assertEquals(
                "到账应落成收入",
                Direction.INCOME,
                directionOf("微信支付 到账 ¥88.00"),
            )
            // rawText 为 null 也不能让 CASE 结果为 NULL
            assertEquals(
                "无原文的记录应兜底为收入，不能变成 NULL",
                Direction.INCOME,
                byRaw.getValue(null).direction,
            )
            // 原本就明确的方向不能被改动
            assertEquals(
                "原有支出不能被改动",
                Direction.EXPENSE,
                directionOf("您尾号1234账户支出人民币58.00元"),
            )
            assertEquals(
                "原有收入不能被改动",
                Direction.INCOME,
                directionOf("您尾号1234账户收入人民币900.00元"),
            )

            // 最关键的断言：库里不能残留任何 NEUTRAL
            val stillNeutral = all.count { it.direction.name == "NEUTRAL" }
            assertEquals("不应残留 NEUTRAL 方向", 0, stillNeutral)
        } finally {
            db.close()
        }
    }

    /**
     * 兜底分支：库里若有 `'NEUTRAL'` 以外的手改脏值（例如用户拿工具直接改过库），
     * 也必须收敛成 EXPENSE/INCOME，否则读出来会被 fromName 静默吞掉。
     */
    @Test
    fun migrationV3ToV4AlsoCleansOtherUnknownDirections() = runTest {
        createV3DatabaseWithDirections(
            listOf(
                Triple(77.0, "GARBAGE", "手改过的脏数据"),
                Triple(12.0, "NEUTRAL", "无关键词的旧记录"),
            )
        )

        val db = openWithMigration()
        try {
            val all = db.transactionDao().observeAll().first()
            assertEquals(2, all.size)
            all.forEach {
                assertTrue(
                    "方向必须是 EXPENSE 或 INCOME，实际=${it.direction}",
                    it.direction == Direction.EXPENSE || it.direction == Direction.INCOME,
                )
            }
        } finally {
            db.close()
        }
    }

    /** 迁移后数据库必须仍然可写，否则用户升级后记不了账 */
    @Test
    fun databaseIsWritableAfterV3ToV4Migration() = runTest {
        createV3DatabaseWithDirections(
            listOf(Triple(500.0, "NEUTRAL", "您尾号1234账户提现人民币500.00元"))
        )

        val db = openWithMigration()
        try {
            db.transactionDao().insert(
                com.moneyflow.app.data.TransactionEntity(
                    amount = 38.0,
                    direction = Direction.EXPENSE,
                    sourceKind = com.moneyflow.app.data.SourceKind.MANUAL,
                    sourceName = "手动记录",
                    occurredAt = 1_700_000_001_000L,
                )
            )
            assertEquals(2, db.transactionDao().observeAll().first().size)
        } finally {
            db.close()
        }
    }
}
