package com.zcoderemote

import android.annotation.SuppressLint
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONObject
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions

class MainActivity : AppCompatActivity) {

 private enum class Panel { CONFIG, WEB, ERROR }

 private lateinit var configView: View
 private lateinit var webContainer: FrameLayout
 private lateinit var errorOverlay: View
 private lateinit var tvErrorMsg: TextView
 private lateinit var etUrl: EditText
 private lateinit var tvConfigError: TextView

 private var webView: WebView? = null
 private var panel = Panel.CONFIG

 private val handler = Handler(Looper.getMainLooper))

 // —— 断线探测与重载调度状态 ——
 private var lastProbeHit: String? = null // 最近一次探测命中类别（A/B），null=健康
 private var confirmCategory: String? = null // 去抖：当前连续命中的类别
 private var confirmStreak = 0 // 去抖：连续命中次数
 private var lastNotifiedRunState: SessionState = SessionState.IDLE // 会话状态基准（瞬态覆盖不污染）
 private var reloadCount = 0 // 本轮连续自动重载次数
 private var lastReloadAt = 0L // 上次重载时刻（恢复健康清零用）
 private var pageLoadedOnce = false // 页面至少完整加载过一次
 private var pageErrorVisible = false // 主框架加载失败（Chromium 错误页在显示，探测文本不可信）
 private var terminalStop = false // B 类终态：完全静止，不再自动重载

 private val reloadDelays = longArrayOf(0L, 3_000L, 10_000L)
 private val maxReloads = reloadDelays.size

 // 文件上传回调（给 agent 发附件）
 private var filePathCallback: ValueCallback<Array<Uri>>? = null

 private val fileChooserLauncher =
 registerForActivityResult(ActivityResultContracts.StartActivityForResult)) { result ->
 val cb = filePathCallback
 filePathCallback = null
 cb?.onReceiveValue(
 WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data)
 )
 }

 private val scanLauncher = registerForActivityResult(ScanContract)) { result ->
 result.contents?.let { acceptLink(it) }
 }

 private val notifPermissionLauncher =
 registerForActivityResult(ActivityResultContracts.RequestPermission)) { /* 拒绝不阻塞：保活照常 */ }

 private val networkCallback = object : ConnectivityManager.NetworkCallback) {
 override fun onAvailable(network: Network) {
 handler.post { onNetworkRecovered) }
 }
 }

 // —— 帧拦截：注入脚本旁听中继帧，提炼任务状态经桥上报（只听不发） ——
 /** 帧桥最近一次上报时刻（elapsedRealtime）——新鲜=帧桥活着，轮询让位。 */
 private var frameSignalAt = 0L
 private var frameStatus = "" // 会话视图：当前会话 liveStatus（running/completed/error/…）
 private var frameTitle = "" // 会话视图：当前会话标题
 private var framePreview = "" // 会话视图：最新一条消息（lastAssistantPreview 实时滚动）
 private var frameRunningCount = 0 // 列表视图：运行中任务数
 private var frameWaitingCount = 0 // 列表视图：等输入任务数（liveStatus=waiting）
 private var frameErrorCount = 0 // 列表视图：失败任务数（liveStatus=error）
 private var frameFramesSince = -1L // JS 侧距最近真实中继帧的秒数（假死判据）
 private var lastLoggedFrameStatus: String? = null // 取证日志去重：status 翻转才打
 private var seenFirstNetwork = false // 回调注册时会立刻回调当前网络一次，跳过

 /** JS 桥入口（JS 线程回调，转发主线程）。帧信号格式见 assets/frame_hook.js。 */
 private val frameBridge = object {
 @JavascriptInterface
 fun onSignal(json: String) {
 handler.post { onFrameSignal(json) }
 }
 }

 private fun onFrameSignal(json: String) {
 val sig = try { JSONObject(json) } catch (_: Exception) { return }
 if (sig.has("life")) return // WS open/close 生命周期：只旁听记录，不驱动（假死检测兜底）
 // 传输层终态（帧桥第一手信号，React 渲染错误组件的同一毫秒上报，早于 DOM 文本探测
 // 一个量级）：直接清凭据回配置界面。panel 守卫挡 WebView 销毁竞态期的重复信号
 val terminalCode = sig.optString("terminal", "")
 if (terminalCode.isNotEmpty)) {
 if (panel == Panel.WEB) {
 Log.i("FrameSignal", "terminal='$terminalCode' -> rescan")
 performRescan(getString(R.string.config_stale_hint))
 }
 return
 }
 frameSignalAt = SystemClock.elapsedRealtime)
 frameStatus = sig.optString("status", "")
 frameTitle = sig.optString("title", "")
 framePreview = sig.optString("preview", "")
 frameRunningCount = sig.optInt("runningCount", 0)
 frameWaitingCount = sig.optInt("waitingCount", 0)
 frameErrorCount = sig.optInt("errorCount", 0)
 frameFramesSince = sig.optLong("framesSince", -1L)
 // 取证日志：status 翻转才打（5s 心跳不刷屏）——完成/回落的真实帧序列靠它对账
 if (frameStatus != lastLoggedFrameStatus) {
 Log.i("FrameSignal", "status='$frameStatus' running=$frameRunningCount title='${frameTitle}' framesSince=$frameFramesSince")
 lastLoggedFrameStatus = frameStatus
 }
 applyFrameState)
 }

 // —— 完成事件判定基准（ 修「一进已完成会话就弹」）：完成事件=同一实体「从跑变停」，
 // 「进入已完成状态」（浏览老会话/切视图）不是事件，不弹提醒，只更新通知状态 ——
 /** 上次信号的视图（"list"/"session"）——视图切换帧不判事件（跨语境无事件语义）。 */
 private var lastView = ""
 /** 会话视图：上次该会话的 liveStatus 原值（跑/等/停的转换判定要看原值，布尔区分不了等待帧重复）。 */
 private var lastSessionLive = ""
 /** 列表视图：上次是否有任务在跑。 */
 private var lastListRunning = false
 /** 会话视图：上次会话标题——会话间切换=换实体，重置基准不产生事件。 */
 private var lastSessionTitle = ""

 /** 帧信号 → 通知状态（语义：列表视图=聚合、会话视图=单会话）+ 完成/等输入事件提醒。 */
 private fun applyFrameState) {
 if (terminalStop || panel != Panel.WEB) return
 val view = if (frameStatus.isNotEmpty)) "session" else "list"
 // 切换判定在基准滚动前：本帧与上帧比（视图切换 或 会话间切换）
 val contextSwitch = view != lastView || (view == "session" && frameTitle != lastSessionTitle)
 // 完成事件=同一实体从跑/等变停；等输入事件=同一实体从跑变等（任务需要用户输入了）。
 // 浏览老会话/切视图不产生任何事件（contextSwitch 挡）
 val doneEvent = !contextSwitch && when (view) {
 "session" -> (lastSessionLive == "running" || lastSessionLive == "waiting") &&
 frameStatus == "completed"
 // 等输入不算「全部停」；任务失败也不算完成（running 转 error 是失败不是完成）
 else -> lastListRunning && frameRunningCount == 0 && frameWaitingCount == 0 &&
 frameErrorCount == 0
 }
 val waitingEvent = !contextSwitch && when (view) {
 "session" -> lastSessionLive == "running" && frameStatus == "waiting"
 else -> lastListRunning && frameRunningCount == 0 && frameWaitingCount > 0
 }
 // 基准滚动到本帧，供下一帧判定
 lastView = view
 if (view == "session") {
 lastSessionTitle = frameTitle
 lastSessionLive = frameStatus
 }
 lastListRunning = frameRunningCount > 0

 if (view == "list") {
 if (frameRunningCount > 0) {
 StatusNotifier.update(
 this, SessionState.RUNNING,
 titleOverride = getString(R.string.list_title),
 textOverride = "$frameRunningCount 个任务工作中",
 )
 } else if (frameWaitingCount > 0) {
 StatusNotifier.update(
 this, SessionState.WAITING,
 titleOverride = getString(R.string.list_title),
 textOverride = "$frameWaitingCount 个任务等输入",
 )
 } else if (frameErrorCount > 0) {
 // 任务失败（不再误显「全部完成」）：失败原因只在会话视图能给（横幅数据源），
 // 列表态给失败计数
 StatusNotifier.update(
 this, SessionState.SEND_FAILED,
 titleOverride = getString(R.string.list_title),
 textOverride = "$frameErrorCount 个任务失败",
 )
 } else if (StatusNotifier.current == SessionState.RUNNING ||
 StatusNotifier.current == SessionState.DONE
 ) {
 // 聚合完成：列表语境没有单会话的 completed 帧，「从有任务在跑到全部停」即完成。
 // 保持 DONE 而非回落 IDLE——完成态上岛（2026-09-07 反馈收岛无提示），
 // 直到新一轮任务开始才回工作中
 StatusNotifier.update(
 this, SessionState.DONE,
 titleOverride = getString(R.string.list_title),
 textOverride = "任务全部完成",
 )
 } else {
 StatusNotifier.update(this, SessionState.IDLE, titleOverride = getString(R.string.list_title))
 }
 if (doneEvent) StatusNotifier.fireDoneAlert(this)
 if (waitingEvent) StatusNotifier.fireWaitingAlert(this)
 } else {
 val state = when (frameStatus) {
 "running" -> SessionState.RUNNING
 "waiting" -> SessionState.WAITING // 等输入单列展示（2026-09-07 设计决策「要」）
 "completed" -> SessionState.DONE
 "error" -> SessionState.SEND_FAILED
 else -> null // idle/unknown：帧拿不准，交给轮询兜底
 }
 if (state != null) {
 // 通知形态（2026-09-07 定义）：标题=会话名（应用名系统自带显示，不重复写），
 // 正文=最新一条消息（lastAssistantPreview 实时滚动）；preview 空时正文退状态文案
 StatusNotifier.update(
 this, state,
 title = frameTitle.ifBlank { null },
 titleOverride = frameTitle.ifBlank { null },
 textOverride = framePreview.ifBlank { null },
 )
 if (doneEvent) StatusNotifier.fireDoneAlert(this)
 if (waitingEvent && state == SessionState.WAITING) StatusNotifier.fireWaitingAlert(this)
 }
 }
 }

 /** 帧桥活着（含页面后台节流的心跳稀疏）→ 状态由帧驱动，轮询让位。 */
 private fun frameSignalFresh): Boolean =
 !terminalStop && SystemClock.elapsedRealtime) - frameSignalAt < 90_000L

 // —— 探测循环：前台 5s、后台 10s（后台也要较快发现会话完成，驱动灵动岛状态） ——
 private val probeRunnable = object : Runnable {
 override fun run) {
 probeOnce)
 val delay = if (panel == Panel.WEB) 5_000L else 10_000L
 handler.postDelayed(this, delay)
 }
 }

 // 密集重载耗尽后的低频自愈 + 帧流假死检测（）：每 60s
 private val slowRetryRunnable = object : Runnable {
 override fun run) {
 if (!terminalStop && !isFinishing) {
 if (panel == Panel.ERROR) {
 reloadCount = 0
 hideErrorOverlay)
 webView?.reload)
 } else {
 // 假死：帧桥活着（心跳在）+ 当前显示运行中 + JS 侧长时间无真实中继帧
 // —— 页面自认连接活着但数据已冻结，任何看页面的检测都测不出，靠帧流空闲判定
 val frameAlive = SystemClock.elapsedRealtime) - frameSignalAt < 90_000L
 val stalled = frameFramesSince > 180
 if (frameAlive && stalled &&
 StatusNotifier.current == SessionState.RUNNING && pageLoadedOnce
 ) {
 frameFramesSince = 0 // 触发重载期间不再重复判假死
 scheduleReload)
 }
 }
 }
 handler.postDelayed(this, 60_000L)
 }
 }

 override fun onCreate(savedInstanceState: Bundle?) {
 super.onCreate(savedInstanceState)
 setContentView(R.layout.activity_main)

 configView = findViewById(R.id.configView)
 webContainer = findViewById(R.id.webContainer)
 errorOverlay = findViewById(R.id.errorOverlay)
 tvErrorMsg = findViewById(R.id.tvErrorMsg)
 etUrl = findViewById(R.id.etUrl)
 tvConfigError = findViewById(R.id.tvConfigError)

 findViewById<Button>(R.id.btnScan).setOnClickListener { launchScanner) }
 findViewById<Button>(R.id.btnUsePasted).setOnClickListener {
 acceptLink(etUrl.text.toString))
 }
 findViewById<Button>(R.id.btnRetry).setOnClickListener {
 reloadCount = 0
 terminalStop = false
 hideErrorOverlay)
 webView?.reload)
 }
 findViewById<Button>(R.id.btnRescan).setOnClickListener { performRescan(null) }

 // 返回键：会话/列表页优先页面内后退（SPA 路由进 WebView history），退无可退
 // 回桌面但 app 不死（保活+WebView 继续跑，监控常驻语义）；配置页正常退出
 onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
 override fun handleOnBackPressed) {
 when (panel) {
 Panel.WEB, Panel.ERROR ->
 if (webView?.canGoBack) == true) webView?.goBack) else moveTaskToBack(true)
 Panel.CONFIG -> finish)
 }
 }
 })

 registerNetworkCallback)
 handler.post(probeRunnable)
 handler.post(slowRetryRunnable)

 val stored = UrlStore.load(this)
 if (stored != null) enterWeb(stored) else showPanel(Panel.CONFIG)
 }

 override fun onDestroy) {
 super.onDestroy)
 stopProbeLoops)
 getSystemService(ConnectivityManager::class.java)
 .unregisterNetworkCallback(networkCallback)
 destroyWebView)
 }

 // 不调用 webView.onPause)：后台保持 JS 心跳运行是本 app 的核心（前台服务保活配合）。

 // —— 配置 ——
 private fun launchScanner) {
 val options = ScanOptions).apply {
 setDesiredBarcodeFormats(ScanOptions.QR_CODE)
 setPrompt(getString(R.string.scan_prompt))
 setBeepEnabled(false)
 setOrientationLocked(true)
 }
 scanLauncher.launch(options)
 }

 private fun acceptLink(url: String) {
 val reason = UrlStore.validate(url)
 if (reason != null) {
 tvConfigError.text = reason
 tvConfigError.visibility = View.VISIBLE
 return
 }
 tvConfigError.visibility = View.GONE
 val normalized = url.trim)
 UrlStore.save(this, normalized)
 enterWeb(normalized)
 }

 private fun enterWeb(url: String) {
 if (webView == null) {
 webContainer.addView(createWebView))
 }
 resetConnectionState)
 showPanel(Panel.WEB)
 StatusNotifier.update(this, SessionState.IDLE)
 webView?.loadUrl(url)
 KeepAliveService.start(this)
 if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
 ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS)
 != android.content.pm.PackageManager.PERMISSION_GRANTED
 ) {
 notifPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
 }
 }

 // —— WebView ——
 @SuppressLint("SetJavaScriptEnabled")
 private fun createWebView): WebView {
 WebView.setWebContentsDebuggingEnabled(true)
 val wv = WebView(this)
 wv.settings.javaScriptEnabled = true
 wv.settings.domStorageEnabled = true
 wv.settings.setSupportMultipleWindows(false)
 wv.addJavascriptInterface(frameBridge, "ZcodeFrameBridge")
 installFrameHook(wv)
 wv.webViewClient = object : WebViewClient) {
 override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
 val uri = request.url
 // 官方域内导航放行；其余（外部链接）交给系统浏览器
 val host = uri.host ?: return false
 return if (host == "zcode.chatglm.site" || host == "zcode.z.ai") {
 false
 } else {
 try {
 startActivity(Intent(Intent.ACTION_VIEW, uri))
 } catch (_: Exception) {
 }
 true
 }
 }

 override fun onPageFinished(view: WebView, url: String?) {
 pageLoadedOnce = true
 pageErrorVisible = false
 // API<33 无 addDocumentStartJavaScript：加载后补注入（晚于本次建连，
 // 下次 reload 起全量生效——弱兜底，主路径是 33+ 文档创建时注入）
 if (Build.VERSION.SDK_INT < 33) {
 view.evaluateJavascript(frameHookSource), null)
 }
 }

 override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
 android.util.Log.d("ZCodeRemote", "onReceivedError main=${request.isForMainFrame} url=${request.url} err=${error.description}")
 if (request.isForMainFrame && panel != Panel.CONFIG && !terminalStop) {
 pageErrorVisible = true
 scheduleReload)
 }
 }

 override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
 // 渲染进程崩溃：销毁重建，不清配对
 destroyWebView)
 val stored = UrlStore.load(this@MainActivity)
 if (stored != null && panel == Panel.WEB) {
 webContainer.addView(createWebView))
 resetConnectionState)
 webView?.loadUrl(stored)
 }
 return true
 }
 }
 wv.webChromeClient = object : WebChromeClient) {
 override fun onShowFileChooser(
 view: WebView, callback: ValueCallback<Array<Uri>>,
 params: FileChooserParams,
 ): Boolean {
 filePathCallback?.onReceiveValue(null)
 filePathCallback = callback
 fileChooserLauncher.launch(params.createIntent))
 return true
 }
 }
 wv.setDownloadListener { url, _, _, _, _ ->
 try {
 startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
 } catch (_: Exception) {
 Toast.makeText(this, R.string.download_failed, Toast.LENGTH_SHORT).show)
 }
 }
 webView = wv
 return wv
 }

 private fun destroyWebView) {
 webView?.let {
 (it.parent as? FrameLayout)?.removeView(it)
 it.destroy)
 }
 webView = null
 }

 // —— 帧拦截注入 ——

 private var cachedHookSource: String? = null
 private fun frameHookSource): String =
 cachedHookSource ?: assets.open("frame_hook.js").bufferedReader).use { it.readText) }
 .also { cachedHookSource = it }

 /** 文档创建时注入（API 33+ 设备生效）：赶在页面任何 new WebSocket 之前完成覆写。 */
 private fun installFrameHook(wv: WebView) {
 if (Build.VERSION.SDK_INT < 33) return // onPageFinished 兜底注入
 if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) return
 val origins = setOf(
 "https://zcode.z.ai",
 "https://zcode.chatglm.site",
 )
 WebViewCompat.addDocumentStartJavaScript(wv, frameHookSource), origins)
 }

 // —— 断线探测与会话状态 ——
 private fun probeOnce) {
 val wv = webView ?: return
 if (panel != Panel.WEB || terminalStop) return
 wv.evaluateJavascript(FailureFeatures.probeScript)) { result ->
 // result 形如 "\"A|1|0|%E4%BC%9A...\""（evaluateJavascript 会做一层字符串编码）
 val raw = result?.trim('"') ?: return@evaluateJavascript
 val parts = raw.split('|')
 if (parts.size != 4) return@evaluateJavascript
 val hit = parts[0].takeIf { it == "A" || it == "B" }
 val running = parts[1] == "1"
 // 发送失败与 turn 运行中互斥：run 是更可靠的信号，矛盾时忽略 sf
 val sendFailed = parts[2] == "1" && !running
 val title = try { java.net.URLDecoder.decode(parts[3], "UTF-8") } catch (_: Exception) { "" }
 android.util.Log.d("ZCodeRemote", "probe fail=$hit run=$running sf=$sendFailed title=$title loaded=$pageLoadedOnce err=$pageErrorVisible reloads=$reloadCount")
 onProbeResult(hit, running, sendFailed, title)
 }
 }

 private fun onProbeResult(hit: String?, running: Boolean, sendFailed: Boolean, title: String) {
 lastProbeHit = hit
 if (hit == null) {
 confirmStreak = 0
 confirmCategory = null
 // 重载后保持健康 60s，视为真恢复，计数清零
 if (reloadCount > 0 && SystemClock.elapsedRealtime) - lastReloadAt > 60_000L) {
 reloadCount = 0
 }
 } else {
 if (hit == confirmCategory) confirmStreak++ else {
 confirmCategory = hit
 confirmStreak = 1
 }
 // 连续 2 次命中才动作，过滤加载瞬间的闪现文本
 if (confirmStreak >= 2) {
 confirmStreak = 0
 confirmCategory = null
 if (hit == "B") {
 // ：终态=凭据已死，重载无意义，直接清凭据回配置界面（带原因提示）。
 // return 跳过末尾的 updateSessionState——保活服务已停，别再动通知
 performRescan(getString(R.string.config_stale_hint))
 return
 } else {
 scheduleReload)
 }
 }
 }
 updateSessionState(running, sendFailed, title)
 }

 /** 会话状态优先级：终态 > 重连中 > 发送失败 > 运行中 > 已完成/空闲。 */
 private fun updateSessionState(running: Boolean, sendFailed: Boolean, title: String) {
 // 配置界面没有会话在跑，通知已随保活服务停止，不该再动
 if (panel == Panel.CONFIG) return
 // ：帧桥活着时状态由协议帧驱动（帧拿不准的 idle/unknown 会留空不走帧路径），
 // 轮询让位只做兜底；terminalStop 的终态探测不受让位影响
 if (frameSignalFresh) && !terminalStop && lastProbeHit == null) return
 val newState = when {
 terminalStop -> SessionState.TERMINAL
 lastProbeHit != null || pageErrorVisible || recentlyReloaded) -> SessionState.RECONNECTING
 sendFailed -> SessionState.SEND_FAILED
 running -> SessionState.RUNNING
 else -> when (lastNotifiedRunState) {
 SessionState.RUNNING, SessionState.DONE -> SessionState.DONE
 else -> SessionState.IDLE
 }
 }
 // RUNNING/DONE/IDLE 记入基准（SEND_FAILED 等瞬态覆盖不污染基准）
 if (newState in setOf(SessionState.RUNNING, SessionState.DONE, SessionState.IDLE)) {
 lastNotifiedRunState = newState
 }
 StatusNotifier.update(this, newState, title.ifBlank { null })
 }

 private fun recentlyReloaded): Boolean =
 reloadCount > 0 && SystemClock.elapsedRealtime) - lastReloadAt < 30_000L

 private fun scheduleReload) {
 if (terminalStop) return
 // 断网期间不烧重载次数：等网络恢复回调来触发
 if (!isNetworkAvailable)) return
 if (reloadCount >= maxReloads) {
 StatusNotifier.update(this, SessionState.RECONNECTING)
 showErrorOverlay(getString(R.string.error_exhausted), retryEnabled = true)
 return
 }
 StatusNotifier.update(this, SessionState.RECONNECTING)
 val delay = reloadDelays[reloadCount]
 reloadCount++
 lastReloadAt = SystemClock.elapsedRealtime)
 handler.postDelayed({ webView?.reload) }, delay)
 }

 private fun onNetworkRecovered) {
 if (terminalStop || panel == Panel.CONFIG) return
 // 回调注册时系统会立刻回调一次当前网络：首次跳过，不算网络变化
 if (!seenFirstNetwork) {
 seenFirstNetwork = true
 return
 }
 // 错误页（密集重载已耗尽）：网络回来了立即重试
 if (panel == Panel.ERROR) {
 reloadCount = 0
 hideErrorOverlay)
 scheduleReload)
 return
 }
 // ：网络切换（Wi-Fi↔流量/换 Wi-Fi）必然弄死页面的中继连接（源地址变了，
 // 页面却收不到断开信号——实测的假死场景）。已加载过页面就无条件重载，
 // 不再等页面表现出不健康（假死恰恰是「没有任何表现」）
 if (pageLoadedOnce) {
 scheduleReload)
 } else if (lastProbeHit != null || pageErrorVisible) {
 scheduleReload)
 }
 }

 private fun isNetworkAvailable): Boolean {
 val cm = getSystemService(ConnectivityManager::class.java)
 val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
 return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
 }

 // —— 视图切换 ——
 private fun showPanel(target: Panel) {
 panel = target
 configView.visibility = if (target == Panel.CONFIG) View.VISIBLE else View.GONE
 webContainer.visibility = if (target == Panel.WEB || target == Panel.ERROR) View.VISIBLE else View.GONE
 errorOverlay.visibility = if (target == Panel.ERROR) View.VISIBLE else View.GONE
 }

 private fun showErrorOverlay(message: String, retryEnabled: Boolean) {
 tvErrorMsg.text = message
 findViewById<Button>(R.id.btnRetry).visibility =
 if (retryEnabled) View.VISIBLE else View.GONE
 showPanel(Panel.ERROR)
 }

 private fun hideErrorOverlay) {
 if (panel == Panel.ERROR) showPanel(Panel.WEB)
 }

 /** 清凭据回到配置界面；hint 非空时在配置页红字说明回退原因（重新配对成功后自动清掉）。 */
 private fun performRescan(hint: String?) {
 terminalStop = false
 reloadCount = 0
 stopProbeLoops)
 UrlStore.clear(this)
 destroyWebView)
 KeepAliveService.stop(this)
 if (hint != null) {
 tvConfigError.text = hint
 tvConfigError.visibility = View.VISIBLE
 }
 showPanel(Panel.CONFIG)
 }

 private fun resetConnectionState) {
 lastProbeHit = null
 confirmCategory = null
 confirmStreak = 0
 reloadCount = 0
 terminalStop = false
 pageLoadedOnce = false
 pageErrorVisible = false
 lastNotifiedRunState = SessionState.IDLE
 // ：帧信号一并清零（reload 后 JS 重新注入，旧信号不作数）
 frameSignalAt = 0L
 frameStatus = ""
 frameTitle = ""
 framePreview = ""
 frameRunningCount = 0
 frameWaitingCount = 0
 frameErrorCount = 0
 frameFramesSince = -1L
 }

 private fun stopProbeLoops) {
 handler.removeCallbacks(probeRunnable)
 handler.removeCallbacks(slowRetryRunnable)
 handler.removeCallbacksAndMessages(null)
 }

 private fun registerNetworkCallback) {
 val request = NetworkRequest.Builder)
 .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
 .build)
 getSystemService(ConnectivityManager::class.java)
 .registerNetworkCallback(request, networkCallback)
 }
}
