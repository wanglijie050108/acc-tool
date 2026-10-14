package com.moneyflow.app

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.moneyflow.app.csv.CsvParseResult
import com.moneyflow.app.csv.CsvParser
import com.moneyflow.app.data.AppDatabase
import com.moneyflow.app.data.Direction
import com.moneyflow.app.data.SourceKind
import com.moneyflow.app.data.TransactionEntity
import com.moneyflow.app.data.TransactionRepository
import com.moneyflow.app.parse.ParseEngine
import com.moneyflow.app.parse.ParseResult
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 数据层仪器测试 —— 跑在真实 Android 环境上，用真实的 Room/SQLite。
 *
 * 为什么必须有这类测试：项目历史上最严重的 bug（去重失效）就出在这一层，
 * 而且它只在真实设备上、跨分钟边界时才暴露。纯 JVM 单元测试测不到 SQLite 的真实行为。
 */
@RunWith(AndroidJUnit4::class)
class TransactionRepositoryTest {

    private lateinit var db: AppDatabase
    private lateinit var repository: TransactionRepository

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()
        repository = TransactionRepository(db.transactionDao())
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun entity(
        amount: Double,
        direction: Direction = Direction.EXPENSE,
        occurredAt: Long,
        fingerprint: String? = null,
        sourceName: String = "测试",
    ) = TransactionEntity(
        amount = amount,
        direction = direction,
        sourceKind = SourceKind.MANUAL,
        sourceName = sourceName,
        occurredAt = occurredAt,
        fingerprint = fingerprint,
    )

    // ─────────── 基础读写 ───────────

    @Test
    fun insertAndReadBack() = runTest {
        repository.save(entity(58.0, occurredAt = 1_000_000L))
        val all = repository.observeAll().first()
        assertEquals(1, all.size)
        assertEquals(58.0, all.first().amount, 0.001)
    }

    @Test
    fun updateExisting() = runTest {
        repository.save(entity(58.0, occurredAt = 1_000_000L))
        val saved = repository.observeAll().first().first()
        repository.save(saved.copy(amount = 99.0))
        val all = repository.observeAll().first()
        assertEquals(1, all.size)
        assertEquals(99.0, all.first().amount, 0.001)
    }

    @Test
    fun deleteByIdWorks() = runTest {
        repository.save(entity(58.0, occurredAt = 1_000_000L))
        val saved = repository.observeAll().first().first()
        repository.deleteById(saved.id)
        assertTrue(repository.observeAll().first().isEmpty())
    }

    @Test
    fun orderingIsNewestFirst() = runTest {
        repository.save(entity(1.0, occurredAt = 1_000L))
        repository.save(entity(2.0, occurredAt = 3_000L))
        repository.save(entity(3.0, occurredAt = 2_000L))
        val all = repository.observeAll().first()
        assertEquals(listOf(3_000L, 2_000L, 1_000L), all.map { it.occurredAt })
    }

    // ─────────── 去重（核心） ───────────

    @Test
    fun sameFingerprintWithinWindowIsDeduplicated() = runTest {
        val fp = "same-fingerprint"
        val t = 1_700_000_000_000L
        val first = repository.insertDeduplicated(entity(58.0, occurredAt = t, fingerprint = fp))
        val second = repository.insertDeduplicated(entity(58.0, occurredAt = t + 5_000L, fingerprint = fp))

        assertTrue("第一条应入库", first)
        assertTrue("第二条应被判为重复", !second)
        assertEquals(1, repository.observeAll().first().size)
    }

    /**
     * 回归测试：对应真实设备上发现的去重失效 bug。
     *
     * 旧实现的指纹含「分钟」，两条记录若跨分钟边界（相差 19 秒但分属不同分钟），
     * 指纹不同 → 去重失效 → 同一笔钱记两次。
     *
     * 这里用「真实解析产物 + 真实数据库」端到端验证修复。
     */
    @Test
    fun crossMinuteBoundaryIsStillDeduplicated() = runTest {
        // 构造跨分钟边界的两个时刻：相差 19 秒，但落在不同分钟
        val base = 1_700_000_000_000L
        val t1 = base - (base % 60_000L) + 50_000L
        val t2 = t1 + 19_000L
        assertTrue("测试前提：两个时刻应在不同分钟", t1 / 60_000L != t2 / 60_000L)

        // 用真实解析引擎产生记录（这才是 App 里实际走的路）
        val a = ParseEngine.parse("微信支付 向 星巴克 付款 ¥58.00", "com.tencent.mm", t1)
        val b = ParseEngine.parse("您尾号1234账户支出人民币58.00元", null, t2)
        assertTrue(a is ParseResult.Success)
        assertTrue(b is ParseResult.Success)

        val inserted1 = repository.insertDeduplicated(
            ParseEngine.toEntity(a as ParseResult.Success, "raw-a", t1)
        )
        val inserted2 = repository.insertDeduplicated(
            ParseEngine.toEntity(b as ParseResult.Success, "raw-b", t2)
        )

        assertTrue("第一条应入库", inserted1)
        assertTrue(
            "跨分钟边界的同金额记录必须被去重（回归 bug：旧实现会记两条）",
            !inserted2,
        )
        assertEquals(1, repository.observeAll().first().size)
    }

    @Test
    fun sameAmountOutsideWindowIsKept() = runTest {
        val fp = "same-fingerprint"
        val t = 1_700_000_000_000L
        repository.insertDeduplicated(entity(58.0, occurredAt = t, fingerprint = fp))
        // 超过 90 秒窗口 → 应视为两笔独立消费（例如同一家店分两次买同样的东西）
        val second = repository.insertDeduplicated(
            entity(58.0, occurredAt = t + 10 * 60_000L, fingerprint = fp)
        )
        assertTrue("超出时间窗的相同金额应保留", second)
        assertEquals(2, repository.observeAll().first().size)
    }

    @Test
    fun nullFingerprintNeverDeduplicated() = runTest {
        val t = 1_700_000_000_000L
        // 手动补录的记录 fingerprint 为 null，不参与去重
        repository.insertDeduplicated(entity(58.0, occurredAt = t, fingerprint = null))
        repository.insertDeduplicated(entity(58.0, occurredAt = t, fingerprint = null))
        assertEquals("无指纹的记录不应被去重", 2, repository.observeAll().first().size)
    }

    @Test
    fun differentAmountsNotDeduplicated() = runTest {
        val t = 1_700_000_000_000L
        val a = ParseEngine.parse("微信支付-¥58.00", "com.tencent.mm", t) as ParseResult.Success
        val b = ParseEngine.parse("微信支付-¥59.00", "com.tencent.mm", t) as ParseResult.Success
        repository.insertDeduplicated(ParseEngine.toEntity(a, "a", t))
        val inserted = repository.insertDeduplicated(ParseEngine.toEntity(b, "b", t))
        assertTrue("不同金额应各自入库", inserted)
        assertEquals(2, repository.observeAll().first().size)
    }

    // ─────────── CSV 账单导入 ───────────

    private val csvSample = """
        交易号,商家订单号,交易创建时间,付款时间,最近修改时间,交易来源地,类型,交易对方,商品名称,金额（元）,收/支,交易状态,服务费（元）,成功退款（元）,备注,资金状态
        001,,2026-10-14 16:42:03,,,网站,消费,星巴克,咖啡,38.00,支出,交易成功,0.00,0.00,,已支出
        002,,2026-10-14 12:10:00,,,网站,消费,美团外卖,午餐,45.50,支出,交易成功,0.00,0.00,,已支出
        003,,2026-10-14 09:00:00,,,网站,转账,张三,转账,200.00,收入,交易成功,0.00,0.00,,已收入
    """.trimIndent()

    @Test
    fun csvImportInsertsRecords() = runTest {
        val parsed = CsvParser.parse(csvSample) as CsvParseResult.Success
        val inserted = repository.importCsvRecords(parsed.records)

        assertEquals("应导入 3 条", 3, inserted)
        val all = repository.observeAll().first()
        assertEquals(3, all.size)
        assertTrue("来源应标记为账单导入", all.all { it.sourceKind == SourceKind.CSV_IMPORT })
        assertTrue("导入的记录应标记为已核对", all.all { it.confirmed })
    }

    @Test
    fun csvImportPreservesCategoryFromBill() = runTest {
        val parsed = CsvParser.parse(csvSample) as CsvParseResult.Success
        repository.importCsvRecords(parsed.records)
        val all = repository.observeAll().first()
        // 支付宝账单的「类型」列（消费/转账）会存进 category 字段
        assertTrue("应保留账单里的分类信息", all.any { it.category != null })
    }

    /**
     * CSV 导入的核心价值验证：
     * 通知已经抓过的流水，导入账单时**不能重复入库**；
     * 通知漏掉的，导入时要**补上**。
     */
    @Test
    fun csvImportDeduplicatesAgainstCapturedNotifications() = runTest {
        // 时间：构造与 CSV 里「星巴克 38.00」接近的时刻（同一分钟内）
        val csvTime = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.CHINA)
            .parse("2026-10-14 16:42:03")!!.time

        // 1. 先模拟「通知已经抓到了星巴克这笔」
        val notif = ParseEngine.parse("微信支付 向 星巴克 付款 ¥38.00", "com.tencent.mm", csvTime + 5_000L)
        assertTrue(notif is ParseResult.Success)
        repository.insertDeduplicated(
            ParseEngine.toEntity(notif as ParseResult.Success, "notif", csvTime + 5_000L)
        )
        assertEquals(1, repository.observeAll().first().size)

        // 2. 再导入账单（里面也含这笔 38.00）
        val parsed = CsvParser.parse(csvSample) as CsvParseResult.Success
        val inserted = repository.importCsvRecords(parsed.records)

        // 星巴克那笔应被去重，只补进美团外卖和张三转账
        assertEquals("只应补进通知漏掉的 2 条", 2, inserted)
        val all = repository.observeAll().first()
        assertEquals(3, all.size)
        assertEquals(
            "同一笔消费不应出现两次",
            1,
            all.count { kotlin.math.abs(it.amount - 38.0) < 0.001 },
        )
    }

    @Test
    fun csvImportTwiceDoesNotDuplicate() = runTest {
        val parsed = CsvParser.parse(csvSample) as CsvParseResult.Success
        val first = repository.importCsvRecords(parsed.records)
        val second = repository.importCsvRecords(parsed.records)

        assertEquals("首次导入应全部入库", 3, first)
        assertEquals("重复导入同一份账单不应新增", 0, second)
        assertEquals(3, repository.observeAll().first().size)
    }

    @Test
    fun csvImportKeepsDistinctSameAmountDifferentTimes() = runTest {
        // 同一金额、相隔很久 → 是两笔不同的消费，都应保留
        val csv = """
            交易号,商家订单号,交易创建时间,付款时间,最近修改时间,交易来源地,类型,交易对方,商品名称,金额（元）,收/支,交易状态,服务费（元）,成功退款（元）,备注,资金状态
            001,,2026-10-14 08:00:00,,,网站,消费,早餐店,包子,10.00,支出,交易成功,0.00,0.00,,已支出
            002,,2026-10-14 20:00:00,,,网站,消费,夜宵摊,烧烤,10.00,支出,交易成功,0.00,0.00,,已支出
        """.trimIndent()
        val parsed = CsvParser.parse(csv) as CsvParseResult.Success
        val inserted = repository.importCsvRecords(parsed.records)
        assertEquals("相隔 12 小时的同金额消费应都保留", 2, inserted)
    }

    @Test
    fun csvParsedTimeMatchesStoredTime() = runTest {
        val parsed = CsvParser.parse(csvSample) as CsvParseResult.Success
        repository.importCsvRecords(parsed.records)
        val all = repository.observeAll().first()
        val expected = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.CHINA)
            .parse("2026-10-14 16:42:03")!!.time
        assertTrue(
            "账单里的交易时间应原样入库",
            all.any { it.occurredAt == expected },
        )
    }

    @Test
    fun incomeAndExpenseNotDeduplicated() = runTest {
        val t = 1_700_000_000_000L
        val expense = ParseEngine.parse("微信支付-¥58.00", "com.tencent.mm", t) as ParseResult.Success
        val income = ParseEngine.parse("微信支付 收款到账 ¥58.00", "com.tencent.mm", t) as ParseResult.Success
        repository.insertDeduplicated(ParseEngine.toEntity(expense, "e", t))
        val inserted = repository.insertDeduplicated(ParseEngine.toEntity(income, "i", t))
        assertTrue("同金额但方向不同，不应去重", inserted)
        assertEquals(2, repository.observeAll().first().size)
    }

    // ─────────── 边界：90 秒窗口的两侧 ───────────

    @Test
    fun justInsideWindowIsDeduplicated() = runTest {
        val fp = "fp"
        val t = 1_700_000_000_000L
        repository.insertDeduplicated(entity(58.0, occurredAt = t, fingerprint = fp))
        val second = repository.insertDeduplicated(entity(58.0, occurredAt = t + 89_000L, fingerprint = fp))
        assertTrue("窗口内（89 秒）应去重", !second)
    }

    @Test
    fun justOutsideWindowIsKept() = runTest {
        val fp = "fp"
        val t = 1_700_000_000_000L
        repository.insertDeduplicated(entity(58.0, occurredAt = t, fingerprint = fp))
        val second = repository.insertDeduplicated(entity(58.0, occurredAt = t + 91_000L, fingerprint = fp))
        assertTrue("窗口外（91 秒）应保留", second)
    }

    // ─────────── 手动补录 ───────────

    @Test
    fun manualEntryHasNoFingerprintAndIsConfirmed() = runTest {
        val manual = TransactionEntity(
            amount = 20.0,
            direction = Direction.EXPENSE,
            sourceKind = SourceKind.MANUAL,
            sourceName = "手动记录",
            occurredAt = 1_700_000_000_000L,
            confirmed = true,
        )
        repository.save(manual)
        val saved = repository.observeAll().first().first()
        assertNull("手动记录不应有指纹", saved.fingerprint)
        assertTrue("手动记录应标记为已核对", saved.confirmed)
        assertEquals(SourceKind.MANUAL, saved.sourceKind)
    }

    @Test
    fun findByIdReturnsEntity() = runTest {
        repository.save(entity(58.0, occurredAt = 1_000L))
        val saved = repository.observeAll().first().first()
        val found = repository.findById(saved.id)
        assertNotNull(found)
        assertEquals(58.0, found!!.amount, 0.001)
    }

    // ─────────── 枚举持久化 ───────────

    @Test
    fun enumsRoundTripCorrectly() = runTest {
        repository.save(entity(1.0, Direction.INCOME, 1_000L, sourceName = "A"))
        repository.save(entity(2.0, Direction.NEUTRAL, 2_000L, sourceName = "B"))
        val all = repository.observeAll().first()
        assertEquals(Direction.NEUTRAL, all[0].direction)
        assertEquals(Direction.INCOME, all[1].direction)
    }

    @Test
    fun thousandsSeparatorAmountSurvivesRoundTrip() = runTest {
        val r = ParseEngine.parse("微信支付-¥1,234.56", "com.tencent.mm", 1_000L)
        assertTrue(r is ParseResult.Success)
        repository.insertDeduplicated(
            ParseEngine.toEntity(r as ParseResult.Success, "raw", 1_000L)
        )
        val saved = repository.observeAll().first().first()
        assertEquals(1234.56, saved.amount, 0.001)
    }
}
