package com.zcoderemote

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent

/**
 * 会话状态（常驻通知显示的内容）。
 * 优先级：TERMINAL > RECONNECTING > SEND_FAILED > RUNNING > DONE > IDLE。
 */
enum class SessionState(val ticker: String) {
    IDLE("远程控制"),
    RUNNING("会话运行中"),
    DONE("会话已完成"),
    SEND_FAILED("发送失败"),
    RECONNECTING("连接恢复中"),
    TERMINAL("需重新扫码");
}

/**
 * 前台服务的常驻通知：持续显示会话运行状态，点击回到 app。
 *
 * 2026-09-02 曾实现 HyperOS 超级岛（灵动岛）焦点通知，真机结论：参数解析正确
 * （focusType=PARAMS）但被平台授权拦截（系统日志 authResult=false），第三方应用
 * 需小米平台审核+设备白名单流程，设计决策放弃。参数实现存档在 git 历史
 * （commit 9c2eea2），若将来政策放开可取回。
 */
object StatusNotifier {

    const val NOTIF_ID = 1
    // LOW：无声、不弹横幅，适合常驻状态通知（渠道重要性创建后不可改，换 ID 即换渠道）
    private const val CHANNEL_ID = "keepalive3"

    @Volatile
    var current: SessionState = SessionState.IDLE
        private set

    @Volatile
    var sessionTitle: String = ""
        private set

    /** 状态变化则更新通知；标题独立更新。返回是否真的发生了变化。 */
    fun update(ctx: Context, state: SessionState, title: String? = null): Boolean {
        if (title != null) sessionTitle = title
        if (state == current && title == null) return false
        current = state
        notify(ctx)
        return true
    }

    fun notify(ctx: Context) {
        ensureChannel(ctx)
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.notify(NOTIF_ID, buildNotification(ctx, current))
    }

    /** 幂等创建通知渠道。前台服务 startForeground 前必须调用：
     *  带渠道 ID 的通知在渠道不存在时会被系统判为 Bad notification 直接崩溃，
     *  且服务可能被系统独立重启（START_STICKY），不能依赖 MainActivity 先发过通知。 */
    fun ensureChannel(ctx: Context) {
        ctx.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                ctx.getString(R.string.notif_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply { setShowBadge(false) }
        )
    }

    fun buildNotification(ctx: Context, state: SessionState): Notification {
        val shown = if (sessionTitle.isNotBlank() &&
            state in setOf(SessionState.RUNNING, SessionState.DONE, SessionState.SEND_FAILED)
        ) "$sessionTitle · ${state.ticker}" else state.ticker
        val contentIntent = PendingIntent.getActivity(
            ctx, 0,
            Intent(ctx, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopServiceIntent = PendingIntent.getService(
            ctx, 1,
            Intent(ctx, KeepAliveService::class.java).apply {
                action = "com.zcoderemote.STOP_KEEPALIVE"
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return Notification.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_keepalive)
            .setContentTitle(ctx.getString(R.string.notif_title))
            .setContentText(shown)
            .setOngoing(true)
            .setContentIntent(contentIntent)
            .setCategory(Notification.CATEGORY_SERVICE)
            .addAction(
                Notification.Action.Builder(
                    null, ctx.getString(R.string.notif_stop), stopServiceIntent
                ).build()
            )
            .build()
    }
}
