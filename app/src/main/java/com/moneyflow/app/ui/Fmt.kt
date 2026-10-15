package com.moneyflow.app.ui

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** 时间与金额的格式化工具。集中放这里，保证全 App 显示一致 */
object Fmt {

    private val dateTimeFmt = SimpleDateFormat("MM-dd HH:mm", Locale.CHINA)
    private val fullDateFmt = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA)

    fun dateTime(millis: Long): String = dateTimeFmt.format(Date(millis))

    fun fullDate(millis: Long): String = fullDateFmt.format(Date(millis))

    /** 金额显示：保留两位小数，带千分位 */
    fun amount(value: Double): String = "%,.2f".format(value)

    /**
     * 带符号的金额，用于流水列表。
     * 支出前面加「-」，收入加「+」。
     */
    fun signedAmount(value: Double, direction: com.moneyflow.app.data.Direction): String =
        when (direction) {
            com.moneyflow.app.data.Direction.EXPENSE -> "-${amount(value)}"
            com.moneyflow.app.data.Direction.INCOME -> "+${amount(value)}"
        }

    /** 判断两个时间戳是否是同一天，用于列表分组 */
    fun isSameDay(a: Long, b: Long): Boolean {
        val calA = Calendar.getInstance().apply { timeInMillis = a }
        val calB = Calendar.getInstance().apply { timeInMillis = b }
        return calA.get(Calendar.YEAR) == calB.get(Calendar.YEAR) &&
            calA.get(Calendar.DAY_OF_YEAR) == calB.get(Calendar.DAY_OF_YEAR)
    }

    /** 日期分组标题：今天 / 昨天 / 10-14 星期三 */
    fun dayHeader(millis: Long, now: Long = System.currentTimeMillis()): String {
        if (isSameDay(millis, now)) return "今天"
        val yesterday = now - 24 * 60 * 60 * 1000L
        if (isSameDay(millis, yesterday)) return "昨天"
        return SimpleDateFormat("MM-dd EEEE", Locale.CHINA).format(Date(millis))
    }
}
