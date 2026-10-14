package com.moneyflow.app.parse

import com.moneyflow.app.data.Direction
import java.security.MessageDigest

/**
 * 去重指纹 —— 全项目唯一的实现。
 *
 * ⚠️ 为什么必须只有一份实现：
 * 「通知抓取」和「CSV 账单导入」是两条独立的数据入口，
 * 但它们记录的很可能是**同一笔钱**（例如微信买东西，通知记了一次，
 * 后来导入账单又出现一次）。只有两条路径生成**完全相同**的指纹，
 * 跨来源去重才能生效，否则同一笔消费会被记两次。
 *
 * 组成：金额 + 方向。
 * **故意不含时间，也故意不含来源。**
 *
 * ⚠️ 为什么不含时间：早期版本把「分钟」编进指纹，结果两笔相隔 19 秒的通知
 * （10:00:50 和 10:01:09）落在不同分钟桶里，指纹不同 → 去重失效
 * → 同一笔钱被记两次。这个 bug 是在真实 Android 设备上测出来的。
 *
 * 时间维度的判断交给 Repository 的范围查询：
 *   「指纹相同」AND「occurredAt 在 ±90 秒内」两个条件同时满足才算重复。
 * 这样既不会漏判（不受分桶边界影响），也不会误判
 * （同一家店两次相同金额的消费，间隔超过 90 秒就都保留）。
 */
object Fingerprint {

    fun of(amount: Double, direction: Direction): String {
        val raw = "%.2f|%s".format(amount, direction.name)
        return MessageDigest.getInstance("SHA-256")
            .digest(raw.toByteArray())
            .joinToString("") { "%02x".format(it) }
            .take(32)
    }
}
