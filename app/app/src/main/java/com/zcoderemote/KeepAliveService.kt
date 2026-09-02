package com.zcoderemote

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

/**
 * 前台保活服务：让 app 进程不进后台冻结态，WebView 里的官方页面心跳得以持续。
 * 通知本体（含会话状态）由 StatusNotifier 统一构建，状态变化走 notify 同 id 更新。
 */
class KeepAliveService : Service() {

    companion object {
        private const val ACTION_STOP = "com.zcoderemote.STOP_KEEPALIVE"

        fun start(ctx: Context) {
            ctx.startForegroundService(Intent(ctx, KeepAliveService::class.java))
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, KeepAliveService::class.java))
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        startAsForeground()
        return START_STICKY
    }

    private fun startAsForeground() {
        // startForeground 的通知自带渠道 ID，渠道不存在会被判 Bad notification 崩溃；
        // 服务可能被系统独立拉起，必须自己保证渠道存在，不依赖 Activity 侧先发过通知
        StatusNotifier.ensureChannel(this)
        val notification = StatusNotifier.buildNotification(this, StatusNotifier.current)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                StatusNotifier.NOTIF_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            )
        } else {
            startForeground(StatusNotifier.NOTIF_ID, notification)
        }
    }
}
