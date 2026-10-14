package com.moneyflow.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.moneyflow.app.data.AppDatabase
import com.moneyflow.app.data.Direction
import com.moneyflow.app.data.RawSampleEntity
import com.moneyflow.app.data.SourceKind
import com.moneyflow.app.data.TransactionEntity
import com.moneyflow.app.data.TransactionRepository
import com.moneyflow.app.csv.CsvParseResult
import com.moneyflow.app.csv.CsvParser
import com.moneyflow.app.parse.CustomRuleStore
import com.moneyflow.app.parse.ParseEngine
import com.moneyflow.app.parse.ParseRule
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class TransactionViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = TransactionRepository.get(application)

    val transactions: StateFlow<List<TransactionEntity>> = repository.observeAll()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList(),
        )

    /** 正在编辑的记录。null 表示当前没有打开编辑弹窗 */
    private val _editing = MutableStateFlow<TransactionEntity?>(null)
    val editing: StateFlow<TransactionEntity?> = _editing.asStateFlow()

    /** 用户点击「补录」时为 true，此时弹窗是新建模式 */
    private val _creating = MutableStateFlow(false)
    val creating: StateFlow<Boolean> = _creating.asStateFlow()

    fun openCreate() {
        _editing.value = null
        _creating.value = true
    }

    fun openEdit(entity: TransactionEntity) {
        _editing.value = entity
        _creating.value = false
    }

    fun closeEditor() {
        _editing.value = null
        _creating.value = false
    }

    /**
     * 保存一条手动补录/编辑的记录。
     * 手动记录不带指纹，不参与自动去重。
     */
    fun save(
        id: Long,
        amountText: String,
        direction: Direction,
        sourceName: String,
        merchant: String,
        note: String,
        occurredAt: Long,
    ) {
        val amount = amountText.replace(",", "").trim().toDoubleOrNull() ?: return
        if (amount <= 0.0) return

        viewModelScope.launch {
            val existing = if (id != 0L) repository.findById(id) else null
            val entity = (existing ?: TransactionEntity(
                amount = amount,
                direction = direction,
                sourceKind = SourceKind.MANUAL,
                sourceName = sourceName.ifBlank { "手动记录" },
                occurredAt = occurredAt,
            )).copy(
                amount = amount,
                direction = direction,
                sourceName = sourceName.ifBlank { "手动记录" },
                merchant = merchant.ifBlank { null },
                note = note.ifBlank { null },
                occurredAt = occurredAt,
                // 手动改过的记录标记为已核对
                confirmed = existing?.confirmed ?: true,
            )
            repository.save(entity)
            closeEditor()
        }
    }

    fun delete(entity: TransactionEntity) {
        viewModelScope.launch {
            repository.delete(entity)
        }
    }

    // ─────────────── CSV 账单导入 ───────────────

    /** 导入过程的提示文字，显示在导入页上 */
    private val _importStatus = MutableStateFlow<String?>(null)
    val importStatus: StateFlow<String?> = _importStatus.asStateFlow()

    /** 导入完成后的结果说明 */
    private val _importResult = MutableStateFlow<String?>(null)
    val importResult: StateFlow<String?> = _importResult.asStateFlow()

    fun clearImportMessages() {
        _importStatus.value = null
        _importResult.value = null
    }

    /** 读取文件阶段的错误（权限、文件损坏等），与解析错误分开汇报 */
    fun reportImportError(message: String) {
        _importStatus.value = null
        _importResult.value = "❌ 读取文件失败：$message"
    }

    /**
     * 解析并导入一份账单 CSV。
     *
     * 整个流程：读取文本 → 解析 → 去重入库 → 汇报结果。
     * 去重让「通知已记过的」不会被重复导入，只补上漏掉的。
     */
    fun importCsv(text: String) {
        viewModelScope.launch {
            _importStatus.value = "正在解析账单…"
            _importResult.value = null

            when (val parsed = CsvParser.parse(text)) {
                is CsvParseResult.Failure -> {
                    _importStatus.value = null
                    _importResult.value = "❌ 解析失败：${parsed.reason}"
                }

                is CsvParseResult.Success -> {
                    if (parsed.records.isEmpty()) {
                        _importStatus.value = null
                        _importResult.value = buildString {
                            append("⚠️ 没有解析出任何流水。\n")
                            append("跳过了 ${parsed.skippedLines} 行。\n")
                            parsed.skippedReasons.take(3).forEach { append("· $it\n") }
                        }
                        return@launch
                    }

                    _importStatus.value = "正在导入 ${parsed.records.size} 条…"
                    val inserted = repository.importCsvRecords(parsed.records)
                    val duplicated = parsed.records.size - inserted

                    _importStatus.value = null
                    _importResult.value = buildString {
                        append("✅ 解析出 ${parsed.records.size} 条流水\n")
                        append("新增 $inserted 条")
                        if (duplicated > 0) {
                            append("，$duplicated 条与已有记录重复已跳过")
                        }
                        append("\n")
                        if (parsed.skippedLines > 0) {
                            append("跳过 ${parsed.skippedLines} 行（表头/空行/不计收支等）\n")
                        }
                        parsed.skippedReasons.take(3).forEach { append("· $it\n") }
                    }
                }
            }
        }
    }

    // ─────────────── 样本导出 ───────────────

    /** 供导出用的样本列表（默认只含「和钱有关」的，隐私更少） */
    private val _samples = MutableStateFlow<List<RawSampleEntity>>(emptyList())
    val samples: StateFlow<List<RawSampleEntity>> = _samples.asStateFlow()

    private val _sampleTotal = MutableStateFlow(0)
    val sampleTotal: StateFlow<Int> = _sampleTotal.asStateFlow()

    /** 刷新样本列表。进入导出页时调用 */
    fun refreshSamples() {
        viewModelScope.launch {
            try {
                val dao = AppDatabase.get(getApplication()).rawSampleDao()
                _samples.value = dao.recentMoneyLike(200)
                _sampleTotal.value = dao.count()
            } catch (t: Throwable) {
                _samples.value = emptyList()
                _sampleTotal.value = 0
            }
        }
    }

    fun clearSamples() {
        viewModelScope.launch {
            try {
                AppDatabase.get(getApplication()).rawSampleDao().clear()
                _samples.value = emptyList()
                _sampleTotal.value = 0
            } catch (_: Throwable) {
                // 清理失败不影响使用
            }
        }
    }

    // ─────────────── 自定义规则 ───────────────

    private val _customRuleCount = MutableStateFlow(0)
    val customRuleCount: StateFlow<Int> = _customRuleCount.asStateFlow()

    fun refreshCustomRuleCount() {
        _customRuleCount.value = ParseEngine.currentCustomRuleCount()
    }

    /**
     * 保存一条用户自定义规则。
     *
     * 规则存成 JSON 文件，保存后立即重新加载到解析引擎，
     * 用户不用重启 App 就能看到效果。
     */
    fun saveCustomRule(
        name: String,
        pattern: String,
        amountGroup: Int,
        direction: Direction,
        smsOnly: Boolean,
    ) {
        viewModelScope.launch {
            val context = getApplication<Application>()
            val existing = CustomRuleStore.load(context).toMutableList()

            val newRule = ParseRule(
                id = "custom_${System.currentTimeMillis()}",
                sourceKind = SourceKind.OTHER_NOTIFICATION,
                sourceName = name.ifBlank { "自定义规则" },
                packageNames = emptySet(),
                pattern = runCatching { Regex(pattern) }.getOrNull() ?: return@launch,
                amountGroup = amountGroup,
                direction = direction,
                priority = 12,
                smsOnly = smsOnly,
            )

            // 同名的旧规则替换掉，避免重复堆积
            existing.removeAll { it.sourceName == newRule.sourceName }
            existing.add(newRule)

            CustomRuleStore.save(context, existing)
            ParseEngine.setCustomRules(CustomRuleStore.load(context))
            _customRuleCount.value = ParseEngine.currentCustomRuleCount()
        }
    }

    fun exportCustomRules(onReady: (String) -> Unit) {
        val context = getApplication<Application>()
        onReady(CustomRuleStore.exportJson(context))
    }

    /**
     * 导入规则 JSON。
     *
     * 解析失败的条目会被跳过（CustomRuleStore 内部处理），
     * 返回成功导入的条数供界面提示。
     */
    fun importCustomRules(json: String) {
        viewModelScope.launch {
            val context = getApplication<Application>()
            val imported = runCatching { CustomRuleStore.parseJson(json) }.getOrDefault(emptyList())
            CustomRuleStore.save(context, imported)
            ParseEngine.setCustomRules(CustomRuleStore.load(context))
            _customRuleCount.value = ParseEngine.currentCustomRuleCount()
        }
    }
}
