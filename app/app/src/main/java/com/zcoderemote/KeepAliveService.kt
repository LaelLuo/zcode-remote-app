package com.zcoderemote

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

/**
 * 前台保活服务：让 app 进程不进后台冻结态，WebView 里的官方页面心跳得以持续。
 * 通知本体（含灵动岛状态）由 IslandNotifier 统一构建，状态变化走 notify 同 id 更新。
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
        val notification = IslandNotifier.buildNotification(this, IslandNotifier.current)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                IslandNotifier.NOTIF_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            )
        } else {
            startForeground(IslandNotifier.NOTIF_ID, notification)
        }
    }
}
