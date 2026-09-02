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
import android.view.View
import android.webkit.RenderProcessGoneDetail
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions

class MainActivity : AppCompatActivity() {

    private enum class Panel { CONFIG, WEB, ERROR }

    private lateinit var configView: View
    private lateinit var webContainer: FrameLayout
    private lateinit var errorOverlay: View
    private lateinit var tvErrorMsg: TextView
    private lateinit var etUrl: EditText
    private lateinit var tvConfigError: TextView

    private var webView: WebView? = null
    private var panel = Panel.CONFIG

    private val handler = Handler(Looper.getMainLooper())

    // —— 断线探测与重载调度状态 ——
    private var lastProbeHit: String? = null     // 最近一次探测命中类别（A/B），null=健康
    private var confirmCategory: String? = null  // 去抖：当前连续命中的类别
    private var confirmStreak = 0                // 去抖：连续命中次数
    private var lastNotifiedRunState: SessionState = SessionState.IDLE // 会话状态基准（瞬态覆盖不污染）
    private var reloadCount = 0                  // 本轮连续自动重载次数
    private var lastReloadAt = 0L                // 上次重载时刻（恢复健康清零用）
    private var pageLoadedOnce = false           // 页面至少完整加载过一次
    private var pageErrorVisible = false         // 主框架加载失败（Chromium 错误页在显示，探测文本不可信）
    private var terminalStop = false             // B 类终态：完全静止，不再自动重载

    private val reloadDelays = longArrayOf(0L, 3_000L, 10_000L)
    private val maxReloads = reloadDelays.size

    // 文件上传回调（给 agent 发附件）
    private var filePathCallback: ValueCallback<Array<Uri>>? = null

    private val fileChooserLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val cb = filePathCallback
            filePathCallback = null
            cb?.onReceiveValue(
                WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data)
            )
        }

    private val scanLauncher = registerForActivityResult(ScanContract()) { result ->
        result.contents?.let { acceptLink(it) }
    }

    private val notifPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* 拒绝不阻塞：保活照常 */ }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            handler.post { onNetworkRecovered() }
        }
    }

    // —— 探测循环：前台 5s、后台 10s（后台也要较快发现会话完成，驱动灵动岛状态） ——
    private val probeRunnable = object : Runnable {
        override fun run() {
            probeOnce()
            val delay = if (panel == Panel.WEB) 5_000L else 10_000L
            handler.postDelayed(this, delay)
        }
    }

    // 密集重载耗尽后的低频自愈：每 60s 静默试一次，直到恢复（B 类终态不参与）
    private val slowRetryRunnable = object : Runnable {
        override fun run() {
            if (!terminalStop && panel == Panel.ERROR && !isFinishing) {
                reloadCount = 0
                hideErrorOverlay()
                webView?.reload()
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

        findViewById<Button>(R.id.btnScan).setOnClickListener { launchScanner() }
        findViewById<Button>(R.id.btnUsePasted).setOnClickListener {
            acceptLink(etUrl.text.toString())
        }
        findViewById<Button>(R.id.btnRetry).setOnClickListener {
            reloadCount = 0
            terminalStop = false
            hideErrorOverlay()
            webView?.reload()
        }
        findViewById<Button>(R.id.btnRescan).setOnClickListener {
            terminalStop = false
            reloadCount = 0
            stopProbeLoops()
            UrlStore.clear(this)
            destroyWebView()
            KeepAliveService.stop(this)
            showPanel(Panel.CONFIG)
        }

        registerNetworkCallback()
        handler.post(probeRunnable)
        handler.post(slowRetryRunnable)

        val stored = UrlStore.load(this)
        if (stored != null) enterWeb(stored) else showPanel(Panel.CONFIG)
    }

    override fun onDestroy() {
        super.onDestroy()
        stopProbeLoops()
        getSystemService(ConnectivityManager::class.java)
            .unregisterNetworkCallback(networkCallback)
        destroyWebView()
    }

    // 不调用 webView.onPause()：后台保持 JS 心跳运行是本 app 的核心（前台服务保活配合）。

    // —— 配置 ——
    private fun launchScanner() {
        val options = ScanOptions().apply {
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
        val normalized = url.trim()
        UrlStore.save(this, normalized)
        enterWeb(normalized)
    }

    private fun enterWeb(url: String) {
        if (webView == null) {
            webContainer.addView(createWebView())
        }
        resetConnectionState()
        showPanel(Panel.WEB)
        IslandNotifier.update(this, SessionState.IDLE)
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
    private fun createWebView(): WebView {
        WebView.setWebContentsDebuggingEnabled(true)
        val wv = WebView(this)
        wv.settings.javaScriptEnabled = true
        wv.settings.domStorageEnabled = true
        wv.settings.setSupportMultipleWindows(false)
        wv.webViewClient = object : WebViewClient() {
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
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                android.util.Log.d("ZCodeRemote", "onReceivedError main=${request.isForMainFrame} url=${request.url} err=${error.description}")
                if (request.isForMainFrame && panel != Panel.CONFIG && !terminalStop) {
                    pageErrorVisible = true
                    scheduleReload()
                }
            }

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                // 渲染进程崩溃：销毁重建，不清配对
                destroyWebView()
                val stored = UrlStore.load(this@MainActivity)
                if (stored != null && panel == Panel.WEB) {
                    webContainer.addView(createWebView())
                    resetConnectionState()
                    webView?.loadUrl(stored)
                }
                return true
            }
        }
        wv.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                view: WebView, callback: ValueCallback<Array<Uri>>,
                params: FileChooserParams,
            ): Boolean {
                filePathCallback?.onReceiveValue(null)
                filePathCallback = callback
                fileChooserLauncher.launch(params.createIntent())
                return true
            }
        }
        wv.setDownloadListener { url, _, _, _, _ ->
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            } catch (_: Exception) {
                Toast.makeText(this, R.string.download_failed, Toast.LENGTH_SHORT).show()
            }
        }
        webView = wv
        return wv
    }

    private fun destroyWebView() {
        webView?.let {
            (it.parent as? FrameLayout)?.removeView(it)
            it.destroy()
        }
        webView = null
    }

    // —— 断线探测与会话状态 ——
    private fun probeOnce() {
        val wv = webView ?: return
        if (panel != Panel.WEB || terminalStop) return
        wv.evaluateJavascript(FailureFeatures.probeScript()) { result ->
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
            if (reloadCount > 0 && SystemClock.elapsedRealtime() - lastReloadAt > 60_000L) {
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
                    terminalStop = true
                    IslandNotifier.update(this, SessionState.TERMINAL)
                    showErrorOverlay(getString(R.string.error_terminal), retryEnabled = true)
                } else {
                    scheduleReload()
                }
            }
        }
        updateSessionState(running, sendFailed, title)
    }

    /** 会话状态优先级：终态 > 重连中 > 发送失败 > 运行中 > 已完成/空闲。 */
    private fun updateSessionState(running: Boolean, sendFailed: Boolean, title: String) {
        val newState = when {
            terminalStop -> SessionState.TERMINAL
            lastProbeHit != null || pageErrorVisible || recentlyReloaded() -> SessionState.RECONNECTING
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
        IslandNotifier.update(this, newState, title.ifBlank { null })
    }

    private fun recentlyReloaded(): Boolean =
        reloadCount > 0 && SystemClock.elapsedRealtime() - lastReloadAt < 30_000L

    private fun scheduleReload() {
        if (terminalStop) return
        // 断网期间不烧重载次数：等网络恢复回调来触发
        if (!isNetworkAvailable()) return
        if (reloadCount >= maxReloads) {
            IslandNotifier.update(this, SessionState.RECONNECTING)
            showErrorOverlay(getString(R.string.error_exhausted), retryEnabled = true)
            return
        }
        IslandNotifier.update(this, SessionState.RECONNECTING)
        val delay = reloadDelays[reloadCount]
        reloadCount++
        lastReloadAt = SystemClock.elapsedRealtime()
        handler.postDelayed({ webView?.reload() }, delay)
    }

    private fun onNetworkRecovered() {
        if (terminalStop || panel == Panel.CONFIG) return
        // 错误页（密集重载已耗尽）：网络回来了立即重试
        if (panel == Panel.ERROR) {
            reloadCount = 0
            hideErrorOverlay()
            scheduleReload()
            return
        }
        // 页面不在健康态（探测有命中 / 主框架错误页 / 从未加载成功）：立即触发重载
        if (lastProbeHit != null || pageErrorVisible || !pageLoadedOnce) {
            scheduleReload()
        }
    }

    private fun isNetworkAvailable(): Boolean {
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

    private fun hideErrorOverlay() {
        if (panel == Panel.ERROR) showPanel(Panel.WEB)
    }

    private fun resetConnectionState() {
        lastProbeHit = null
        confirmCategory = null
        confirmStreak = 0
        reloadCount = 0
        terminalStop = false
        pageLoadedOnce = false
        pageErrorVisible = false
        lastNotifiedRunState = SessionState.IDLE
    }

    private fun stopProbeLoops() {
        handler.removeCallbacks(probeRunnable)
        handler.removeCallbacks(slowRetryRunnable)
        handler.removeCallbacksAndMessages(null)
    }

    private fun registerNetworkCallback() {
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        getSystemService(ConnectivityManager::class.java)
            .registerNetworkCallback(request, networkCallback)
    }
}
