package com.zcoderemote

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat

/**
 * 会话状态（常驻通知显示的内容）。
 * 优先级：RECONNECTING > SEND_FAILED > RUNNING > DONE > IDLE。
 * 无 TERMINAL 态：链接判死=瞬时回配置界面（清凭据停服务），不驻留在通知上（ 死代码清理）。
 */
enum class SessionState(val ticker: String) {
 IDLE("远程控制"),
 RUNNING("会话工作中"),
 WAITING("等输入"),
 DONE("会话已完成"),
 SEND_FAILED("发送失败"),
 RECONNECTING("连接恢复中");
}

/**
 * 前台服务的常驻通知：持续显示会话运行状态，点击回到 app。
 *
 * 2026-09-07 ：会话状态经 Android 16 Live Updates 标准通道上岛（超级岛/状态栏胶囊）——
 * 裸提升请求 + 胶囊短文本，HyperOS 3.0.300+ 系统级适配，无需小米平台流程。
 * 上岛集合=会话状态 {RUNNING, DONE, SEND_FAILED}；连接层状态（IDLE/RECONNECTING）
 * 不上岛（用户 2026-09-02 原话排除「重连中」）。ProgressStyle 经单变量实验证明非必需后，
 * 设计决策去掉（会话无进度数值，进度形态是装饰）。小米私有 miui.focus
 * 通道的旧结论与存档见 git 9c2eea2。
 */
object StatusNotifier {

 const val NOTIF_ID = 1
 // LOW：无声、不弹横幅，适合常驻状态通知（渠道重要性创建后不可改，换 ID 即换渠道）；
 // 标准通道只禁 IMPORTANCE_MIN，LOW 合法
 private const val CHANNEL_ID = "keepalive3"

 // 完成提醒（2026-09-07 反馈「会话完成直接收岛、没有任何提示」）：常驻渠道是 LOW 无声，
 // 完成这件事实体必须可感知——进入 DONE 时经独立 HIGH 渠道弹横幅+响声提醒，离开 DONE 撤掉。
 // 渠道重要性创建后不可改：首版建成 DEFAULT(3) 实测只进状态栏不弹横幅无声（真机 2026-09-07），
 // 升 HIGH 必须换 ID——done_alerts2；旧 done_alerts 渠道废弃不再使用，留在系统里无害。
 // 同日扩为双提醒：等输入也弹（用户「等待输入最好和完成一样有通知」）——同一 HIGH 渠道，
 // 渠道名随之改「会话提醒」（渠道名可更新，重要性不可变），两类提醒各自通知 id 并存不覆盖
 private const val ALERT_CHANNEL_ID = "done_alerts2"
 private const val ALERT_DONE_ID = 2
 private const val ALERT_WAITING_ID = 3

 /** 请求系统提升（上岛）的状态集合：会话状态上岛，连接层状态不上岛。
 * WAITING 上岛——等输入恰是需要用户来看的状态，提示价值最高。 */
 private val promotedStates = setOf(
 SessionState.RUNNING, SessionState.WAITING, SessionState.DONE, SessionState.SEND_FAILED,
 )

 /** 状态栏胶囊短文本（显示空间 96dp 内，超过 6 个字符可能被截断为仅图标）。
 * 「工作中」跟 zcode web 端计时按钮同文案（2026-09-07 设计决策统一）；
 * 「等输入」为 waiting 单列展示（2026-09-07 设计决策「要」）。 */
 private val chipText = mapOf(
 SessionState.RUNNING to "工作中",
 SessionState.WAITING to "等输入",
 SessionState.DONE to "已完成",
 SessionState.SEND_FAILED to "发送失败",
 )

 @Volatile
 var current: SessionState = SessionState.IDLE
 private set

 @Volatile
 var sessionTitle: String = ""
 private set

 /** 状态变化则更新通知；标题独立更新。返回是否真的发生了变化（状态与标题都没变则短路，防探测循环高频重建通知）。
 * title：会话标题（存 sessionTitle 供完成提醒文案用，不影响通知外观）；
 * titleOverride：通知标题（2026-09-07 定义通知形态：会话视图=会话名、列表视图=「任务列表」，
 * 应用名系统自带显示不重复写；null=回退应用名）；
 * textOverride：通知正文（会话视图=最新一条消息 lastAssistantPreview；null=状态文案）。 */
 @Volatile
 private var currentTextOverride: String? = null

 @Volatile
 private var currentTitleOverride: String? = null

 fun update(
 ctx: Context, state: SessionState,
 title: String? = null,
 titleOverride: String? = null,
 textOverride: String? = null,
 ): Boolean {
 val wasDone = current == SessionState.DONE
 val wasWaiting = current == SessionState.WAITING
 val titleChanged = title != null && title != sessionTitle
 if (title != null) sessionTitle = title
 val overrideChanged = textOverride != currentTextOverride || titleOverride != currentTitleOverride
 if (state == current && !titleChanged && !overrideChanged) return false
 current = state
 currentTextOverride = textOverride
 currentTitleOverride = titleOverride
 notify(ctx)
 // 离开 DONE/WAITING 撤对应提醒（状态已翻页，旧提醒不再挂着）。弹提醒不在这里判：
 // 「进入 DONE」≠「完成事件」——浏览一个早已完成的会话也会进入 DONE（2026-09-07 真机
 // 反馈「一进已完成的会话就弹」），真事件=同一实体从跑变停/从跑变等，由 MainActivity 的
 // 帧层判定后调 fireDoneAlert/fireWaitingAlert
 if (current != SessionState.DONE && wasDone) ctx.getSystemService(NotificationManager::class.java).cancel(ALERT_DONE_ID)
 if (current != SessionState.WAITING && wasWaiting) ctx.getSystemService(NotificationManager::class.java).cancel(ALERT_WAITING_ID)
 return true
 }

 fun notify(ctx: Context) {
 ensureChannel(ctx)
 val nm = ctx.getSystemService(NotificationManager::class.java)
 nm.notify(NOTIF_ID, buildNotification(ctx, current))
 }

 /** 幂等创建通知渠道。前台服务 startForeground 前必须调用：
 * 带渠道 ID 的通知在渠道不存在时会被系统判为 Bad notification 直接崩溃，
 * 且服务可能被系统独立重启（START_STICKY），不能依赖 MainActivity 先发过通知。 */
 fun ensureChannel(ctx: Context) {
 val nm = ctx.getSystemService(NotificationManager::class.java)
 nm.createNotificationChannel(
 NotificationChannel(
 CHANNEL_ID,
 ctx.getString(R.string.notif_channel_name),
 NotificationManager.IMPORTANCE_LOW,
 ).apply { setShowBadge(false) }
 )
 // 清历史废弃渠道（换 ID 升档的副作用：旧渠道永远挂在系统设置里）——重复删除是 no-op
 for (old in listOf("keepalive", "keepalive2", "done_alerts")) {
 nm.deleteNotificationChannel(old)
 }
 }

 private fun pendingOpenApp(ctx: Context): PendingIntent = PendingIntent.getActivity(
 ctx, 0,
 Intent(ctx, MainActivity::class.java).apply {
 flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
 },
 PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
 )

 /** 共用提醒渠道（HIGH：弹横幅+响声）。渠道名可更新，重要性创建后不可变。 */
 private fun ensureAlertChannel(ctx: Context) {
 ctx.getSystemService(NotificationManager::class.java).createNotificationChannel(
 NotificationChannel(
 ALERT_CHANNEL_ID,
 ctx.getString(R.string.done_alert_channel_name),
 NotificationManager.IMPORTANCE_HIGH,
 ).apply { enableVibration(true) } // 震动显式开：真机实测渠道默认震动关，HIGH 档横幅要靠它兜感知
 )
 }

 fun fireDoneAlert(ctx: Context) {
 ensureAlertChannel(ctx)
 ctx.getSystemService(NotificationManager::class.java).notify(
 ALERT_DONE_ID,
 buildAlert(ctx, ctx.getString(R.string.done_alert_title), sessionTitle.ifBlank { ctx.getString(R.string.done_alert_text) }),
 )
 }

 /** 等输入提醒（2026-09-07 用户「等待输入最好和完成一样有通知」）：与完成同渠道同形态。 */
 fun fireWaitingAlert(ctx: Context) {
 ensureAlertChannel(ctx)
 ctx.getSystemService(NotificationManager::class.java).notify(
 ALERT_WAITING_ID,
 buildAlert(ctx, ctx.getString(R.string.waiting_alert_title), sessionTitle.ifBlank { ctx.getString(R.string.waiting_alert_text) }),
 )
 }

 private fun buildAlert(ctx: Context, title: String, text: String) =
 NotificationCompat.Builder(ctx, ALERT_CHANNEL_ID)
 .setSmallIcon(R.drawable.ic_stat_keepalive)
 .setContentTitle(title)
 .setContentText(text)
 .setContentIntent(pendingOpenApp(ctx))
 .setAutoCancel(true)
 .build)

 fun buildNotification(ctx: Context, state: SessionState): Notification {
 // 标题：覆盖值（会话名/任务列表）优先，回退应用名；正文：覆盖值（最新消息/聚合文案）优先，
 // 回退状态文案——老的「会话名 · 状态」拼接形态随标题独立化退役（2026-09-07 定义）
 val title = currentTitleOverride ?: ctx.getString(R.string.notif_title)
 val shown = currentTextOverride ?: state.ticker
 val contentIntent = pendingOpenApp(ctx)
 val stopServiceIntent = PendingIntent.getService(
 ctx, 1,
 Intent(ctx, KeepAliveService::class.java).apply {
 action = "com.zcoderemote.STOP_KEEPALIVE"
 },
 PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
 )

 val builder = NotificationCompat.Builder(ctx, CHANNEL_ID)
 .setSmallIcon(R.drawable.ic_stat_keepalive)
 .setContentTitle(title)
 .setContentText(shown)
 .setOngoing(true)
 .setOnlyAlertOnce(true)
 .setContentIntent(contentIntent)
 .setCategory(Notification.CATEGORY_SERVICE)
 .addAction(
 NotificationCompat.Action.Builder(
 null, ctx.getString(R.string.notif_stop), stopServiceIntent
 ).build)
 )
 // 版本门：core 1.17 的 Compat 在 API<36 会把 ProgressStyle 降级为传统进度条（review 字节码
 // 实证），不是忽略——为保持旧设备通知形态与旧版完全一致，仅 API 36+ 设置上岛三件套
 if (state in promotedStates) {
 // 上岛=裸提升请求 + 胶囊短文本（2026-09-07 实证裸请求系统同样批准上岛后，设计决策去掉
 // ProgressStyle——进度样式对无进度数值的会话是装饰；提升请求与短文本在旧系统仅为
 // extras 写键被忽略的无害操作，原先为防 Compat 降级传统进度条的版本门随之失去存在理由）
 builder.setRequestPromotedOngoing(true)
 chipText[state]?.let { builder.setShortCriticalText(it) }
 }
 return builder.build)
 }
}
