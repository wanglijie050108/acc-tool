package com.moneyflow.app

import com.moneyflow.app.data.RawSampleEntity
import com.moneyflow.app.ui.formatSamples
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 样本导出格式化测试。
 *
 * 导出文本是用户发给我调规则的载体，必须：
 * - 未识别的样本**明显标记**出来（我要重点看这些）
 * - 包含包名和时间（判断是哪个 App、哪个版本的行为）
 * - 原文完整（否则正则没法写）
 */
class SampleFormatTest {

    private fun sample(
        text: String,
        matched: Boolean,
        ruleId: String? = null,
        pkg: String? = "com.tencent.mm",
        title: String? = null,
        at: Long = 1_700_000_000_000L,
    ) = RawSampleEntity(
        packageName = pkg,
        title = title,
        text = text,
        receivedAt = at,
        matched = matched,
        ruleId = ruleId,
    )

    @Test
    fun unmatchedSamplesAreClearlyMarked() {
        val out = formatSamples(listOf(sample("某种没见过的扣款通知 ¥88.00", matched = false)))
        assertTrue("未识别样本必须标记为 UNMATCHED", out.contains("UNMATCHED"))
    }

    @Test
    fun matchedSamplesShowRuleId() {
        val out = formatSamples(
            listOf(sample("微信支付-¥58.00", matched = true, ruleId = "wechat_pay_generic")),
        )
        assertTrue("已识别样本应显示命中的规则", out.contains("wechat_pay_generic"))
    }

    @Test
    fun packageNameIsIncluded() {
        val out = formatSamples(listOf(sample("测试", matched = true, pkg = "com.eg.android.AlipayGphone")))
        assertTrue("应包含包名，便于判断来源", out.contains("com.eg.android.AlipayGphone"))
    }

    @Test
    fun smsHasNoPackageAndIsMarked() {
        val out = formatSamples(listOf(sample("您尾号1234账户支出人民币58.00元", matched = true, pkg = null, title = "95588")))
        assertTrue("短信应标记为无包名", out.contains("(短信)"))
        assertTrue("短信应显示发件人", out.contains("95588"))
    }

    @Test
    fun originalTextIsPreservedVerbatim() {
        val original = "您尾号1234的储蓄卡10月14日16:42支出人民币58.00元，余额1234.56元"
        val out = formatSamples(listOf(sample(original, matched = true)))
        assertTrue("原文必须完整保留，否则无法写正则", out.contains(original))
    }

    @Test
    fun samplesAreSortedByTime() {
        val older = sample("早的", matched = true, at = 1_000L)
        val newer = sample("晚的", matched = true, at = 2_000L)
        val out = formatSamples(listOf(newer, older))
        assertTrue(
            "应按时间升序排列，便于按顺序阅读",
            out.indexOf("早的") < out.indexOf("晚的"),
        )
    }

    @Test
    fun emptyListProducesValidHeader() {
        val out = formatSamples(emptyList())
        assertTrue(out.contains("共 0 条"))
    }

    @Test
    fun countIsReported() {
        val out = formatSamples(
            listOf(
                sample("a", matched = true, at = 1L),
                sample("b", matched = false, at = 2L),
            ),
        )
        assertTrue("应报告总数", out.contains("共 2 条"))
    }
}
