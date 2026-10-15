package com.moneyflow.app

import android.app.Application
import com.moneyflow.app.capture.KeepAliveService
import com.moneyflow.app.parse.CustomRuleStore
import com.moneyflow.app.parse.ParseEngine

class MoneyFlowApp : Application() {

    override fun onCreate() {
        super.onCreate()
        // 启动时就把保活服务拉起来。前台服务的通知权限在 MainActivity 里申请。
        KeepAliveService.start(this)

        // 载入用户自定义的解析规则。
        // 必须在任何通知到达之前完成，否则首批通知会用不上自定义规则。
        try {
            ParseEngine.setCustomRules(CustomRuleStore.load(this))
        } catch (_: Throwable) {
            // 规则文件损坏不应导致 App 起不来，退回只用内置规则
        }
    }

    /**
     * 用户改完规则后调用，让新规则立即生效
     */
    fun reloadCustomRules() {
        CustomRuleStore.invalidate()
        ParseEngine.setCustomRules(CustomRuleStore.load(this))
    }
}

