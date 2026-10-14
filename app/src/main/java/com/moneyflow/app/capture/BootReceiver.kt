package com.moneyflow.app.capture

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * 开机自启。手机重启后自动恢复监听，否则用户每次重启都要手动打开一次 App。
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) return

        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != "android.intent.action.QUICKBOOT_POWERON"
        ) {
            return
        }

        Log.i(TAG, "开机自启，恢复后台服务")
        KeepAliveService.start(context.applicationContext)
    }

    companion object {
        private const val TAG = "BootReceiver"
    }
}
