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

 // —— 连接层状态机（）：重载/回配置/连接层通知的唯一决策处，信号源只报事实 ——
 private val machine = ConnectionMachine(object : ConnectionMachine.Actor {
 override fun reloadAfter(delayMs: Long) {
 handler.postDelayed({ webView?.reload) }, delayMs)
 }

 override fun rescan(hint: String?) {
 performRescan(hint)
 }

 override fun showExhausted) {
 showErrorOverlay(getString(R.string.error_exhausted), retryEnabled = true)
 }

 override fun hideExhausted) {
 hideErrorOverlay)
 }

 override fun notifyReconnecting) {
 if (panel == Panel.WEB || panel == Panel.ERROR) {
 StatusNotifier.update(this@MainActivity, SessionState.RECONNECTING)
 }
 }

 override fun hasNetwork): Boolean = isNetworkAvailable)
 }, terminalHint = { getString(R.string.config_stale_hint) })

 // 会话状态基准（瞬态覆盖不污染）——会话层，不属于连接状态机
 private var lastNotifiedRunState: SessionState = SessionState.IDLE

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

 override fun onLost(network: Network) {
 // 断网瞬间岛切「重连中」（2026-09-08 改为上岛）：此前无处理，岛停在断网前
 // 的旧会话态上，断了也看不出来。只做通知展示，重载决策仍由恢复侧驱动
 handler.post {
 if (panel == Panel.WEB) {
 StatusNotifier.update(this@MainActivity, SessionState.RECONNECTING)
 }
 }
 }
 }

 // —— 帧拦截：注入脚本旁听中继帧，提炼任务状态经桥上报（只听不发） ——
 /** 帧桥最近一次上报时刻由 ConnectionMachine 记账（onFrameSignal）。 */
 private var frameStatus = "" // 会话视图：当前会话 liveStatus（running/completed/error/…）
 private var frameTitle = "" // 会话视图：当前会话标题
 private var framePreview = "" // 会话视图：最新一条消息（lastAssistantPreview 实时滚动）
 private var frameRunningCount = 0 // 列表视图：运行中任务数
 private var frameWaitingCount = 0 // 列表视图：等输入任务数（liveStatus=waiting）
 private var frameErrorCount = 0 // 列表视图：失败任务数（liveStatus=error）
 private var frameFramesSince = -1L // JS 侧距最近真实中继帧的秒数（假死判据）
 // 事件锚（JS 侧任务从 running 翻出瞬间记名）：列表聚合事件横幅的实体标题，
 // 防「用户看 A、后台 B 转态、横幅却写 A」的串台（全局 sessionTitle 只反映最近打开的会话）
 private var frameWaitingTitle = ""
 private var frameDoneTitle = ""
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
 if (sig.has("life")) {
 // 只旁听不驱动；close 的 code/pairState 是「close 层为何未判终态」的取证面
 Log.d("FrameSignal", json)
 return
 }
 // 传输层终态（帧桥第一手信号，React 渲染错误组件的同一毫秒上报，早于 DOM 文本探测
 // 一个量级）→ 状态机瞬时迁移回配置（CONFIG 守卫挡 WebView 销毁竞态期的重复信号）
 val terminalCode = sig.optString("terminal", "")
 if (terminalCode.isNotEmpty)) {
 if (machine.state != ConnectionMachine.State.CONFIG) {
 Log.i("FrameSignal", "terminal='$terminalCode' via='${sig.optString("via", sig.optString("relayCode", ""))}' -> rescan")
 machine.onTerminal(getString(R.string.config_stale_hint))
 }
 return
 }
 machine.onFrameSignal)
 frameStatus = sig.optString("status", "")
 frameTitle = sig.optString("title", "")
 framePreview = sig.optString("preview", "")
 frameRunningCount = sig.optInt("runningCount", 0)
 frameWaitingCount = sig.optInt("waitingCount", 0)
 frameErrorCount = sig.optInt("errorCount", 0)
 frameFramesSince = sig.optLong("framesSince", -1L)
 frameWaitingTitle = sig.optString("waitingTitle", "")
 frameDoneTitle = sig.optString("doneTitle", "")
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
 // 已弹过的事件实体（防重复）：同任务持续 waiting 期间每帧都带事件锚标题，只在首次弹
 private var lastAlertedWaitingTitle = ""
 private var lastAlertedDoneTitle = ""

 /** 弹等输入横幅（每实体一次）：title=转态任务名。 */
 private fun alertWaitingOnce(title: String) {
 if (title.isBlank) || title == lastAlertedWaitingTitle) return
 lastAlertedWaitingTitle = title
 StatusNotifier.fireWaitingAlert(this, title)
 }

 /** 弹完成横幅（每实体一次）：title=转态任务名。 */
 private fun alertDoneOnce(title: String) {
 if (title.isBlank) || title == lastAlertedDoneTitle) return
 lastAlertedDoneTitle = title
 StatusNotifier.fireDoneAlert(this, title)
 }

 /** 帧信号 → 通知状态（语义：列表视图=聚合、会话视图=单会话）+ 完成/等输入事件提醒。 */
 private fun applyFrameState) {
 if (panel != Panel.WEB) return
 val view = if (frameStatus.isNotEmpty)) "session" else "list"
 // 切换判定在基准滚动前：本帧与上帧比（视图切换 或 会话间切换）
 val contextSwitch = view != lastView || (view == "session" && frameTitle != lastSessionTitle)
 // 后台任务事件（会话视图盲区修复，2026-09-08）：用户开着会话 B、后台任务 A 转态时，
 // 视图语境的事件判定（当前会话翻转/列表聚合翻转）都够不着 A——用帧层事件锚
 // （任务从 running 翻出瞬间记名）直接弹。当前会话自己的翻转走下面的视图事件路径，
 // 两者经 alertXxxOnce 同基准防重
 if (frameWaitingTitle.isNotBlank) && frameWaitingTitle != frameTitle) {
 alertWaitingOnce(frameWaitingTitle)
 }
 if (frameDoneTitle.isNotBlank) && frameDoneTitle != frameTitle) {
 alertDoneOnce(frameDoneTitle)
 }
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
 if (doneEvent) alertDoneOnce(frameDoneTitle.ifBlank { frameTitle })
 if (waitingEvent) alertWaitingOnce(frameWaitingTitle.ifBlank { frameTitle })
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
 if (doneEvent) alertDoneOnce(frameDoneTitle.ifBlank { frameTitle })
 if (waitingEvent && state == SessionState.WAITING) alertWaitingOnce(frameWaitingTitle.ifBlank { frameTitle })
 }
 }
 }

 /** 帧桥活着（新鲜度分档见 ConnectionMachine）→ 状态由帧驱动，轮询让位。 */
 private fun frameSignalFresh): Boolean = machine.frameSignalFresh)

 // —— 探测循环：唯一职责是降级模式下的 DOM 兜底（：注入脚本报平安新鲜时
 // 不执行任何 evaluateJavascript），节拍 5s 只是轮询降级开关本身 ——
 private val probeRunnable = object : Runnable {
 override fun run) {
 if (machine.shouldProbeDom)) probeOnce)
 val delay = if (panel == Panel.WEB) 5_000L else 10_000L
 handler.postDelayed(this, delay)
 }
 }

 // 密集重载耗尽后的低频自愈 + 帧流假死检测（）：每 60s
 private val slowRetryRunnable = object : Runnable {
 override fun run) {
 if (!isFinishing && !machine.onSlowTick)) {
 // 假死：JS 活着（心跳在）+ 当前显示运行中 + JS 侧长时间无真实中继帧
 // —— 页面自认连接活着但数据已冻结，任何看页面的检测都测不出，靠帧流空闲判定
 val stalled = frameFramesSince > 180
 if (machine.jsAlive) && stalled &&
 StatusNotifier.current == SessionState.RUNNING && machine.pageLoadedOnce
 ) {
 frameFramesSince = 0 // 触发重载期间不再重复判假死
 machine.onStallDetected)
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
 findViewById<Button>(R.id.btnRetry).setOnClickListener { machine.onUserRetry) }
 findViewById<Button>(R.id.btnRescan).setOnClickListener { machine.onUserRescan) }

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

 val stored = UrlStore.load(this)
 if (stored != null) enterWeb(stored) else showPanel(Panel.CONFIG)
 handleNavIntent(intent) // 冷启动路径的通知直达（onNewIntent 只覆盖活动态）
 }

 override fun onDestroy) {
 super.onDestroy)
 stopProbeLoops)
 getSystemService(ConnectivityManager::class.java)
 .unregisterNetworkCallback(networkCallback)
 destroyWebView)
 }

 // 前后台变化喂状态机：新鲜度阈值分档（前台 15s 严格、后台 90s 容忍 WebView 节流压稀心跳）
 override fun onResume) {
 super.onResume)
 machine.onForegroundChanged(true)
 }

 override fun onPause) {
 super.onPause)
 machine.onForegroundChanged(false)
 }

 // —— 通知直达会话（2026-09-09 需求）：通知 extra 带目标任务标题，点击后页面导航过去 ——

 private var pendingNavTitle: String? = null

 override fun onNewIntent(intent: Intent) {
 super.onNewIntent(intent)
 handleNavIntent(intent)
 }

 private fun handleNavIntent(intent: Intent?) {
 val t = intent?.getStringExtra("openTaskTitle")?.takeIf { it.isNotBlank) } ?: return
 pendingNavTitle = t
 tryNavigate)
 }

 /** 导航到目标任务会话页；页面未就绪时挂起（onPageFinished 会再调）。 */
 private fun tryNavigate) {
 val title = pendingNavTitle ?: return
 val wv = webView ?: return
 if (panel != Panel.WEB) return
 // 当前视图判定：会话视图且目标≠当前会话 → 先回列表（SPA 路由在 WebView history 里），
 // 600ms 后点目标任务；列表视图直接点
 wv.evaluateJavascript(NavScript.CHECK_VIEW) { res ->
 when (res?.trim('"')) {
 "session" -> wv.evaluateJavascript(NavScript.h1)) { h1 ->
 if (h1?.trim('"') == title) {
 pendingNavTitle = null // 已在目标会话
 } else if (wv.canGoBack)) {
 pendingNavTitle = null
 wv.goBack)
 handler.postDelayed({ clickTaskItem(title) }, 600)
 }
 }
 "list" -> {
 pendingNavTitle = null
 clickTaskItem(title)
 }
 }
 }
 }

 /** 在任务列表里点目标任务项（cdp-click-text 同法：最小匹配元素→滚到可见→合成 click，React 委托响应）。 */
 private fun clickTaskItem(title: String) {
 val wv = webView ?: return
 if (panel != Panel.WEB) return
 wv.evaluateJavascript(NavScript.clickTask(title)) { r ->
 android.util.Log.d("ZCodeRemote", "nav click('$title') -> ${r?.trim('"')}")
 }
 }

 // 不调用 webView.onPause)：后台保持 JS 心跳运行是本 app 的核心（前台服务保活配合）。

 /** 通知导航的页面脚本（CHECK_VIEW 视图判定与 frame_hook 的 isSessionView 同锚）。 */
 private object NavScript {
 const val CHECK_VIEW =
 """(function){try{var it=document.evaluate("//*[normalize-space(text))='任务会话']",document,null,9,null);return it.singleNodeValue?'session':'list'}catch(e){return 'list'}}))"""

 fun h1) =
 """(function){try{var h=document.querySelector('h1');return h?(h.textContent||'').trim):''}catch(e){return ''}}))"""

 /** 点目标任务列表项：精确标题优先，前缀+40 字符容差兼容「标题+计时/状态文字」，
 * 取最小匹配元素滚到可见后派发完整指针事件序列（pointer/mouse/down/up/click——
 * 只发 click 对监听 pointerdown 的 SPA 无效，实测 CLICKED 但路由不动）。 */
 fun clickTask(title: String): String {
 val jsTitle = title.replace("\\", "\\\\").replace("'", "\\'")
 return """(function){
var title='$jsTitle';
var els=document.querySelectorAll('div,li,a,button');
var best=null;
for(var i=0;i<els.length;i++){
 var e=els[i];var t=(e.innerText||'').trim);
 if(!t)continue;
 var ok=t===title||(t.indexOf(title)===0&&t.length<title.length+40);
 if(!ok)continue;
 if(!best||t.length<best.innerText.trim).length)best=e;
}
if(!best)return 'NOT_FOUND';
best.scrollIntoView({block:'center'});
setTimeout(function){
 var go=best;
 function pe(t){try{go.dispatchEvent(new PointerEvent(t,{bubbles:true,cancelable:true,pointerId:1,isPrimary:true,button:0}))}catch(x){}}
 function me(t){go.dispatchEvent(new MouseEvent(t,{bubbles:true,cancelable:true,button:0}))}
 pe('pointerdown');me('mousedown');pe('pointerup');me('mouseup');go.click);
},200);
return 'CLICKED';
}))"""
 }
 }

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
 // 探测/慢重试循环在此（重）启动：performRescan 的 stopProbeLoops 清掉过它们，
 // 第二次扫码起若不重启，降级探测/EXHAUSTED 自愈/假死检测全部失效（review P2-1）
 stopProbeLoops)
 handler.post(probeRunnable)
 handler.post(slowRetryRunnable)
 machine.onEnterWeb)
 resetFrameState)
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
 machine.onPageFinished)
 // 页面就绪后兑现挂起的通知导航（点击通知时 WebView 还在加载的场景）
 tryNavigate)
 // API<33 无 addDocumentStartJavaScript：加载后补注入（晚于本次建连，
 // 下次 reload 起全量生效——弱兜底，主路径是 33+ 文档创建时注入）
 if (Build.VERSION.SDK_INT < 33) {
 view.evaluateJavascript(frameHookSource), null)
 }
 }

 override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
 android.util.Log.d("ZCodeRemote", "onReceivedError main=${request.isForMainFrame} url=${request.url} err=${error.description}")
 if (request.isForMainFrame) {
 machine.onPageMainError)
 }
 }

 override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
 // 渲染进程崩溃：销毁重建，不清配对
 destroyWebView)
 val stored = UrlStore.load(this@MainActivity)
 if (stored != null && panel == Panel.WEB) {
 webContainer.addView(createWebView))
 machine.onEnterWeb)
 resetFrameState)
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
 if (panel != Panel.WEB) return
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
 android.util.Log.d("ZCodeRemote", "probe fail=$hit run=$running sf=$sendFailed title=$title state=${machine.state}")
 // 终态由 machine 决策（去抖+回配置）；会话状态兜底只在降级模式有意义
 machine.onProbeHit(hit)
 if (machine.state != ConnectionMachine.State.CONFIG) {
 updateSessionState(running, sendFailed, title)
 }
 }
 }

 /** 会话状态兜底（降级模式）：优先级 重连中 > 发送失败 > 运行中 > 已完成/空闲。 */
 private fun updateSessionState(running: Boolean, sendFailed: Boolean, title: String) {
 // 配置界面没有会话在跑，通知已随保活服务停止，不该再动
 if (panel == Panel.CONFIG) return
 // ：帧桥活着时状态由协议帧驱动（帧拿不准的 idle/unknown 会留空不走帧路径），轮询让位只做兜底
 if (frameSignalFresh) && machine.lastProbeHit == null) return
 val newState = when {
 machine.lastProbeHit != null || machine.pageErrorVisible || machine.recentlyReloaded) ->
 SessionState.RECONNECTING
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

 private fun onNetworkRecovered) {
 if (panel == Panel.CONFIG) return
 // 回调注册时系统会立刻回调一次当前网络：首次跳过，不算网络变化
 if (!seenFirstNetwork) {
 seenFirstNetwork = true
 return
 }
 // 语义（重载/耗尽决策在 ConnectionMachine）：网络切换必然弄死页面的中继连接
 // （源地址变了页面却收不到断开信号），已加载过页面就无条件重载
 machine.onNetworkRecovered)
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

 /** 清凭据回到配置界面（ConnectionMachine.Actor.rescan 的实现；hint 非空=红字原因）。 */
 private fun performRescan(hint: String?) {
 stopProbeLoops)
 UrlStore.clear(this)
 destroyWebView)
 KeepAliveService.stop(this)
 etUrl.setText("") // 残留的旧链接是死链，误点「使用粘贴的链接」白等一轮 30s 超时
 if (hint != null) {
 tvConfigError.text = hint
 tvConfigError.visibility = View.VISIBLE
 }
 showPanel(Panel.CONFIG)
 }

 /** 帧信号清零（连接层状态由 ConnectionMachine.onEnterWeb 负责）：reload 后 JS 重新注入，旧信号不作数。 */
 private fun resetFrameState) {
 lastNotifiedRunState = SessionState.IDLE
 frameStatus = ""
 frameTitle = ""
 framePreview = ""
 frameRunningCount = 0
 frameWaitingCount = 0
 frameErrorCount = 0
 frameFramesSince = -1L
 frameWaitingTitle = ""
 frameDoneTitle = ""
 lastAlertedWaitingTitle = ""
 lastAlertedDoneTitle = ""
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
