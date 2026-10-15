package com.moneyflow.app.capture

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
// ⚠️ 必须用 **androidx** 的 RemoteInput，不能用 android.app.RemoteInput。
// NotificationCompat.Action.Builder.addRemoteInput() 要的就是前者，
// 传框架那个会直接编译不过（Type mismatch）—— 这很可能就是当初
// 「建了 RemoteInput 却挂不上去、最后退回普通按钮」的原因。
// 接收侧（PendingTransferActionReceiver）继续用框架版读取是可以的：
// NotificationCompat 发出去时本来就会转成框架 action，结果按同一套 key 回填。
import androidx.core.app.RemoteInput
import com.moneyflow.app.MainActivity
import com.moneyflow.app.data.Direction
import com.moneyflow.app.data.PendingTransferEntity
import com.moneyflow.app.ui.Fmt

/**
 * 待确认转账的提醒通知。
 *
 * ── 为什么用通知而不是悬浮窗 ──
 * 悬浮窗（SYSTEM_ALERT_WINDOW）在国产 ROM 上会被「支付保护 / 支付保险箱」
 * 拦截，需要用户额外关掉系统开关才行（这是实测结论，多个记账 App 的
 * 帮助文档里都提到了）。而通知只需要 [POST_NOTIFICATIONS]，
 * 对「转账后补个金额」这个动作来说，通知足够，且干扰更小。
 *
 * ── 快捷填写（用户 2026-10-15 明确要求）────────────────────────
 * 「检测到转账但是不知道金额，就推送个小弹窗让用户快捷填写，
 *   而不是让用户手动打开软件补充金额。」
 *
 * 落地为通知上的 **RemoteInput 行内输入**：
 * 用户在通知栏直接点「填入金额」→ 金额框在通知里展开 → 输入数字、
 * （可选）点一下「支出/收入」→ 点发送，直接入库。
 * 全程不打开 App —— 老的「点通知进 App 再补」路径保留为兜底
 * （通知正文点击仍进完整补录弹窗）。
 *
 * ── ⚠️ 曾经踩过的坑：提醒「弹不出来」（2026-10-15 用户报障）──────
 * 用户反馈：「转账后能被检测到，但**必须还是要回到 App 内部去填写**」。
 * 查下来不是 RemoteInput 没做，而是**横幅气泡根本没弹**，用户压根没注意到
 * 通知栏里那条静默通知，于是点了通知正文 → 进了 App → 在 App 里填。
 *
 * 根因是**通道重要性给了 DEFAULT**：
 *   Android 只有 `IMPORTANCE_HIGH` 才会把通知渲染成「横幅气泡」（heads-up）。
 *   本项目当初为了「克制」特意选了 DEFAULT + 无声音 + 无震动，
 *   顺手把用户真正要的那个「弹一下」也一起去掉了。
 *
 * 实机佐证（REDMI K80 / Android 15），同一个手机上对比：
 *   - 本应用 `moneyflow_transfer_prompt` → `mImportance=3`（DEFAULT）→ 不弹
 *   - 微信「新消息通知」           → `mImportance=4`（HIGH）  → 正常弹
 *
 * ⚠️ 还有个 Android 的硬规矩：**通道重要性一经创建就改不了**
 * （用同一个 id 再 `createNotificationChannel` 传 HIGH 是无效的，
 * 只有名字和描述能改）。所以修这个必须**换新 id** 并把老通道删掉 ——
 * 见 [CHANNEL_ID] / [LEGACY_CHANNEL_ID]。
 *
 * ── 语气仍然要克制 ──
 * 弹是必须弹的（否则用户注意不到，功能等于没做），但除此之外不额外加戏：
 *   - 文案说清楚「检测到一笔转账」而不是「你有一笔未记账」，不制造焦虑
 *   - 提供「不是转账」的忽略入口，让用户能一键打发
 *   - 同一个 id 复用，避免同一笔转账堆出多条通知
 *   - 回执（[showResult]）**静默**：那只是告知，不该再响一次
 */
object TransferPrompt {

    private const val TAG = "TransferPrompt"

    /**
     * 提醒通知的通道 id。
     *
     * ⚠️ 带 `_v2` 不是随手起的：**老通道是用 IMPORTANCE_DEFAULT 建的，
     * 而 Android 规定通道重要性创建后不可修改**（同 id 再 create 传 HIGH 无效）。
     * 要让它开始弹横幅，只能换一个新 id —— 这是官方推荐的唯一做法。
     * 改回旧的 `moneyflow_transfer_prompt` 会让「不弹横幅」的 bug 原样复活。
     */
    private const val CHANNEL_ID = "moneyflow_transfer_prompt_v2"

    /**
     * 老通道 id（IMPORTANCE_DEFAULT 那一版）。
     *
     * 保留这个常量是为了**主动删掉**它：否则用户的设置页里会同时出现
     * 两条同名的「转账待确认提醒」，用户根本分不清该改哪个。
     * 确认所有用户都升到 v2 之后（比如下个大版本）可以删掉这个常量。
     */
    private const val LEGACY_CHANNEL_ID = "moneyflow_transfer_prompt"

    /**
     * 通知 id 的基数。
     *
     * ⚠️ 用「基数 + 待确认记录 id」而不是固定 id：
     *   - 固定 id 会让新提醒覆盖旧的，用户可能漏看前一笔
     *   - 用记录 id 直接做 id 又可能和保活通知（1001）撞号
     * 取模到 1000 以内，保证不越界也不会和 KeepAliveService 冲突。
     */
    private const val NOTIFICATION_ID_BASE = 2000

    /**
     * 快捷填写里金额输入框的 key。
     *
     * [PendingTransferActionReceiver] 用同一个 key 从
     * `RemoteInput.getResultsFromIntent` 里取值 —— 构造和读取必须一致，
     * 所以只在这里定义一份。
     */
    const val KEY_QUICK_AMOUNT = "quick_amount"

    /** 快捷填写里方向选择（支出/收入）的 key。没选时回退到记录的猜测方向 */
    const val KEY_QUICK_DIRECTION = "quick_direction"

    /**
     * 弹出一条「检测到转账，请补金额」的通知。
     *
     * @param hint 追加在文案里的提示。金额没读懂、要用户重填时传
     * @return true 表示通知已发出（用户授权了通知权限），false 表示被系统拦下
     */
    fun show(context: Context, pending: PendingTransferEntity, hint: String? = null): Boolean {
        val appContext = context.applicationContext
        ensureChannel(appContext)

        // Android 13+ 需要 POST_NOTIFICATIONS 运行时权限。
        // 没授权时不能抛异常（会让通知监听回调崩掉），静默返回 false，
        // 待确认记录仍然留在库里，用户打开 App 能看到。
        if (!NotificationManagerCompat.from(appContext).areNotificationsEnabled()) {
            Log.w(TAG, "通知权限未开启，待确认记录仅留在应用内")
            return false
        }

        val tapIntent = Intent(appContext, MainActivity::class.java).apply {
            // 让 MainActivity 知道要点开哪条待确认
            action = MainActivity.ACTION_CONFIRM_PENDING
            putExtra(MainActivity.EXTRA_PENDING_ID, pending.id)
            // ⚠️ 必须带 NEW_TASK：从 Application context 启动 Activity 需要它，
            // 否则部分 ROM 上点击通知无反应。
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }

        // requestCode 用记录 id，保证每笔转账的 PendingIntent 互不覆盖
        val requestCode = (pending.id % 100000).toInt()
        val contentIntent = PendingIntent.getActivity(
            appContext,
            requestCode,
            tapIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        // 「不是转账」：直接消掉这条待确认，不进 App
        val dismissIntent = Intent(appContext, PendingTransferActionReceiver::class.java).apply {
            action = PendingTransferActionReceiver.ACTION_DISMISS
            putExtra(PendingTransferActionReceiver.EXTRA_PENDING_ID, pending.id)
        }
        val dismissPendingIntent = PendingIntent.getBroadcast(
            appContext,
            requestCode,
            dismissIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        // 「填入金额」：行内输入，不打开 App。
        // 两个 RemoteInput —— 金额（文本）+ 方向（支出/收入 二选一，可不选）。
        // 用户不选方向时接收器回退到记录的猜测方向（见 PendingTransferActionReceiver）。
        val amountInput = RemoteInput.Builder(KEY_QUICK_AMOUNT)
            .setLabel("金额（元）")
            .build()
        val directionInput = RemoteInput.Builder(KEY_QUICK_DIRECTION)
            .setLabel("方向（不选就按默认）")
            .setChoices(arrayOf("支出", "收入"))
            .build()
        val quickFillIntent = Intent(appContext, PendingTransferActionReceiver::class.java).apply {
            action = PendingTransferActionReceiver.ACTION_QUICK_FILL
            putExtra(PendingTransferActionReceiver.EXTRA_PENDING_ID, pending.id)
        }
        // ⚠️ 这个 PendingIntent 必须是 **MUTABLE**，不能像另外两个那样用 IMMUTABLE。
        // 原因：RemoteInput 里用户敲的内容是**由系统（SystemUI）回填进 intent 的**，
        // 不可变的 PendingIntent 不允许别人往里写 extras，用了 IMMUTABLE 就会变成
        // 「输入框能打字、一点发送接收器读到的却是空」这种半死状态。
        // 官方「直接回复」示例用的也是 FLAG_MUTABLE。
        // 安全上不必担心：目标只有本项目自己的 receiver（未 exported），且不带敏感数据。
        val quickFillPendingIntent = PendingIntent.getBroadcast(
            appContext,
            requestCode,
            quickFillIntent,
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        // ⚠️ 必须用 Action.Builder + addRemoteInput 把输入框**挂到动作上**。
        //
        // 这里踩过一个真 bug（2026-10-15 用户报障「还是得回到 App 里填」）：
        // 原来的写法是 `addAction(0, "填入金额", quickFillPendingIntent)` ——
        // 那只是加了个**普通按钮，没有输入框**。用户点下去不会出现金额框，
        // 接收器读到的金额是 null，于是回一条「没读懂「」」。
        // 上面两个 RemoteInput 建出来却**从来没被挂上去**，成了死代码；
        // 编译器只报了一句 "Variable 'directionInput' is never used" 就放过去了。
        //
        // 所以：**别改回 addAction(icon, title, intent) 的简写形式。**
        val quickFillAction = NotificationCompat.Action.Builder(
            0,
            "填入金额",
            quickFillPendingIntent,
        )
            .addRemoteInput(amountInput)
            .addRemoteInput(directionInput)
            .build()

        val body = buildString {
            if (hint != null) {
                append("⚠️ $hint\n\n")
            }
            append("原文：${pending.rawText}\n\n")
            append(
                "微信/支付宝的转账通知里不含金额，所以需要你补一下。\n" +
                    "点下面的「填入金额」，直接在通知里输入即可，不用打开 App。"
            )
        }

        val notification = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setContentTitle(
                if (hint != null) "补一下这笔转账的金额" else "检测到一笔${shortSource(pending.sourceName)}"
            )
            .setContentText(
                hint ?: "点「填入金额」直接补，不用打开 App"
            )
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setSmallIcon(android.R.drawable.ic_menu_edit)
            .setContentIntent(contentIntent)
            // ⚠️ 动作顺序即显示顺序：最常用的「填入金额」放最前。
            // 注意这里是**预先建好的带 RemoteInput 的 Action**（见上），
            // 不是 addAction(icon, title, intent) 那个简写形式 —— 简写没有输入框。
            .addAction(quickFillAction)
            .addAction(0, "不是转账", dismissPendingIntent)
            .setAutoCancel(true)
            // ⚠️ 必须是 PRIORITY_HIGH（配合通道 IMPORTANCE_HIGH）。
            // 用户的诉求就是「像微信来消息那样弹一下提醒我记账」。
            // 这里曾经写的是 PRIORITY_DEFAULT，还注释「不是紧急事件，别打扰用户」——
            // 结果横幅不弹，用户注意不到提醒，最后仍然是「自己打开 App 手动填」，
            // 也就是这个功能本来要解决的问题。**别改回 DEFAULT。**
            // （minSdk=26，实际由通道重要性决定；这行是低版本兜底 + 表明意图）
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            // 声音/震动交给通道决定，这里**不要**覆盖。
            // 在 Android 8+ 上，通知级的 setSound(null)/setVibrate(null)
            // 会带上「静默」语义，把横幅气泡也一起弄没。
            .build()

        return try {
            NotificationManagerCompat.from(appContext)
                .notify(notificationId(pending.id), notification)
            true
        } catch (t: Throwable) {
            // Android 13+ 未授权时 notify 会抛 SecurityException
            Log.w(TAG, "发送转账提醒失败", t)
            false
        }
    }

    /**
     * 快捷填写完成后的**回执通知**，复用同一条通知的 id（原地替换提醒）。
     *
     * 为什么需要：整个填写过程发生在通知栏里，用户从未打开 App。
     * 不留一句「记上了」的反馈，用户会怀疑到底存没存 ——
     * 记账类 App 的信任就死在这种小事上。
     *
     * @param deduplicated true = 与已有流水重复未入库（银行短信可能已记过）
     */
    fun showResult(
        context: Context,
        pending: PendingTransferEntity,
        amount: Double,
        direction: Direction,
        deduplicated: Boolean,
    ) {
        val appContext = context.applicationContext
        if (!NotificationManagerCompat.from(appContext).areNotificationsEnabled()) return
        ensureChannel(appContext)

        val directionText = when (direction) {
            Direction.EXPENSE -> "支出"
            Direction.INCOME -> "收入"
        }
        val money = Fmt.amount(amount)

        val notification = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setContentTitle(
                if (deduplicated) "这笔已经记过了，没有重复记"
                else "已记账：${pending.sourceName}"
            )
            .setContentText(
                if (deduplicated) {
                    "同金额同方向的流水已存在（可能银行扣款短信先到了），已跳过"
                } else {
                    "$directionText ¥$money · 方向不对可在流水列表里改"
                }
            )
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    if (deduplicated) {
                        "同金额同方向的流水已存在（可能银行扣款短信先到了），未重复记。"
                    } else {
                        "$directionText ¥$money\n原文：${pending.rawText}\n\n" +
                            "方向不对、想改对方/备注，去流水列表里点这条记录即可。"
                    }
                )
            )
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            // 回执不是要用户动手的事，几秒后自动消失，不赖在通知栏
            .setTimeoutAfter(10_000L)
            .setAutoCancel(true)
            // ⚠️ 静默：通道是 IMPORTANCE_HIGH（为了提醒能弹），
            // 但**回执**只是告知「记上了」，不该再响一次、也不该再弹一次。
            // setSilent 是 Android 8+ 上「单独让这一条不出声」的正规做法。
            .setSilent(true)
            .build()

        try {
            NotificationManagerCompat.from(appContext)
                .notify(notificationId(pending.id), notification)
        } catch (t: Throwable) {
            Log.w(TAG, "发送记账回执失败", t)
        }
    }

    /** 用户处理完（确认或忽略）后，撤掉对应的通知 */
    fun cancel(context: Context, pendingId: Long) {
        try {
            NotificationManagerCompat.from(context.applicationContext)
                .cancel(notificationId(pendingId))
        } catch (_: Throwable) {
            // 取消失败无所谓，通知会自动消失或过期
        }
    }

    fun notificationId(pendingId: Long): Int =
        NOTIFICATION_ID_BASE + (pendingId % 1000).toInt()

    /** 「微信转账」→「微信转账」，「支付宝转账」→「支付宝转账」。保持不变，便于文案拼接 */
    private fun shortSource(sourceName: String): String = sourceName.ifBlank { "转账" }

    /**
     * 建立/修正提醒通道。**App 启动时就要调一次**（见 [com.moneyflow.app.MoneyFlowApp.onCreate]）。
     *
     * 为什么不在 [show] 里懒建就够了：
     *   - 通道设置会出现在系统「设置 → 通知」里，用户会去看、去改。
     *     等第一笔转账才建，用户在那之前看不到这条通道，也就会去乱翻；
     *   - 更重要的是**老通道的清理**：老通道（DEFAULT）在新版本装上后依然存在，
     *     只有跑到这里才会被删掉。启动时就跑来一遍，用户装完即生效，
     *     不用等「下一笔转账」才把旧通道换掉。
     * 幂等：通道已存在且是老 id 已清时，几乎无开销。
     */
    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return

        // ⚠️ 先删掉老通道。
        // 它的重要性是 DEFAULT，且 **Android 规定通道重要性创建后不可改**，
        // 不删掉的话用户设置页里会留下一条永不弹横幅的同名通道，
        // 用户还可能去改那一条（改了也没用），越修越乱。
        // deleteNotificationChannel 是幂等的，通道不存在时无副作用。
        if (manager.getNotificationChannel(LEGACY_CHANNEL_ID) != null) {
            manager.deleteNotificationChannel(LEGACY_CHANNEL_ID)
        }

        if (manager.getNotificationChannel(CHANNEL_ID) != null) return

        // ⚠️ 单独建通道，不要复用保活通道（那是 IMPORTANCE_MIN 静默的）。
        // 通道的重要性创建后**用户才能改、代码改不动**，所以一开始就必须给对。
        //
        // ⚠️ 必须是 IMPORTANCE_HIGH —— 这是「提醒能弹成横幅气泡」的**唯一前提**。
        // 实机对照（REDMI K80 / Android 15）：
        //   本应用原通道 importance=3(DEFAULT) → 只在通知栏，用户看不到
        //   微信「新消息通知」importance=4(HIGH) → 正常弹横幅
        // 别为了「不打扰」把它降回 DEFAULT：用户注意不到提醒，
        // 就会退回「自己打开 App 填」，正是本功能要消灭的路径。
        val channel = NotificationChannel(
            CHANNEL_ID,
            "转账待确认提醒",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "检测到微信/支付宝转账但通知里没有金额时，弹出来提醒你补上金额"
            setShowBadge(true)
            // 和微信/QQ 来消息一样：有提示音、有震动，否则「弹」了也容易被忽略
            enableVibration(true)
            setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION), null)
        }
        manager.createNotificationChannel(channel)
    }
}
