package com.moneyflow.app.data

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 原始样本记录器。
 *
 * 抓取层每收到一条通知/短信就调一次 [record]，把原文留档。
 * 目的是让用户能一键导出真实样本给开发者调规则 ——
 * 靠用户手动「下拉通知栏、长按、复制」是不现实的，很多人根本不会做。
 *
 * ⚠️ 隐私：这里会存通知全文。所以：
 *   - 只保留最近 [MAX_SAMPLES] 条，自动淘汰旧的
 *   - 设置页有「清空样本」按钮
 *   - 导出时默认只含「含金额特征」的条目
 */
object SampleRecorder {

    /** 最多保留多少条原始样本 */
    const val MAX_SAMPLES = 300

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * 异步记录一条样本，不阻塞通知回调。
     *
     * @param matched 是否被解析引擎识别出金额
     * @param ruleId  命中的规则，未识别为 null
     */
    fun record(
        context: Context,
        packageName: String?,
        title: String?,
        text: String,
        receivedAt: Long,
        matched: Boolean,
        ruleId: String?,
    ) {
        if (text.isBlank()) return
        val appContext = context.applicationContext
        scope.launch {
            try {
                val dao = AppDatabase.get(appContext).rawSampleDao()
                dao.insert(
                    RawSampleEntity(
                        packageName = packageName,
                        title = title,
                        text = text,
                        receivedAt = receivedAt,
                        matched = matched,
                        ruleId = ruleId,
                    )
                )
                // 防止无限增长
                dao.trimTo(MAX_SAMPLES)
            } catch (_: Throwable) {
                // 留档失败不能影响正常记账，静默处理
            }
        }
    }
}
