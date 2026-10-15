package com.moneyflow.app

import android.app.Application
import com.moneyflow.app.capture.KeepAliveService
import com.moneyflow.app.capture.TransferPrompt
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

        // 建立（并修正）转账提醒的通知通道。
        //
        // ⚠️ 必须在启动时做，不能等第一笔转账才懒建：
        // 老版本的通道是用 IMPORTANCE_DEFAULT 建的，而**通道重要性创建后改不了**，
        // 只能换新 id（v2）并删掉老的。只在 [TransferPrompt.show] 里做的话，
        // 用户装完新版本、还没转账之前，系统设置里那条「永不弹横幅」的老通道
        // 会一直挂着，用户看到的就是错的。
        try {
            TransferPrompt.ensureChannel(this)
        } catch (_: Throwable) {
            // 建通道失败（极罕见）不该让 App 起不来；转账时 show() 还会再试一次
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

