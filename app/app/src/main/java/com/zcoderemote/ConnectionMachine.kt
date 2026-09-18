package com.zcoderemote

import android.os.SystemClock

/**
 * 连接层状态机（ 架构收敛）：唯一有权决定整页重载、清凭据回配置、连接层通知。
 * 五个信号源（注入脚本上报 / 降级 DOM 探测 / 网络回调 / WebView 加载回调 / 慢重试）
 * 只调 onXxx 报事实，决策与状态转移集中在此。会话层（任务状态→通知文案）不归此管，
 * 见 MainActivity.applyFrameState（ 已验收资产）。
 *
 * 状态：
 * OK 连接健康
 * RECONNECTING 瞬态故障自动重载中（退避 0/3/10s，一轮最多 3 次）
 * EXHAUSTED 密集重载耗尽：错误覆盖层 + 60s 低频自愈
 * CONFIG 无凭据/已回配置。真死终态（配对失效/被踢/鉴权失败）瞬时迁移到
 * CONFIG（清凭据停服务）；可恢复终态（桌面端离线/中继故障——凭据仍
 * 有效）也回 CONFIG 但保留链接（rescanKeepLink），无驻留「终态」。
 */
/** terminalHint：延迟求值的终态提示文案（Activity 完成装配前构造 machine，不能立即取资源）。 */
class ConnectionMachine(private val actor: Actor, private val terminalHint: ) -> String) {

 enum class State { OK, RECONNECTING, EXHAUSTED, CONFIG }

 interface Actor {
 /** 延迟 delayMs 毫秒重载 WebView（0=立即）。 */
 fun reloadAfter(delayMs: Long)

 /** 清凭据回配置界面；hint=null=无提示（用户主动重扫），非空=红字原因。 */
 fun rescan(hint: String?)

 /** 可恢复终态回配置界面但保留链接：凭据仍有效（桌面端断电重启/中继临时故障），
 * 不清不预空——用户手动点「使用粘贴的链接」即重连（2026-09-09 设计决策：
 * 自动重试方案被否，要求保留链接走手动重连）。 */
 fun rescanKeepLink(hint: String)

 /** 重载耗尽：显示错误覆盖层（文案由实现侧取资源）。 */
 fun showExhausted)

 fun hideExhausted)

 /** 连接层通知（「连接恢复中」文案；会话态文案由会话层负责）。 */
 fun notifyReconnecting)

 /** 当前是否有网络（断网期间不烧重载次数）。 */
 fun hasNetwork): Boolean
 }

 // 重载退避档位。参数核差结论（对照桌面侧全套实测值——桌面 WS 退避 1s 起步、看门狗 30s、
 // 重放宽限 45s/8MB；完整对照结论见 docs/desktop-remote-architecture.md §1.3/§2）：
 // 全部维持现状不照搬——页面自己按桌面同款退避（1s+≤2s 抖动）做 WS 层自愈，壳的整页重载
 // 是页面自救失败后的兜底（重载本身 2-5s，1s 档会被吃掉）；重放 45s 窗口已被现有逻辑
 // 正确处理（网络切换才整页重载，纯 WS 断由页面自愈补帧）；前台 15s 比桌面看门狗 30s 严
 // （降级探测开关，无害），后台 90s 松于看门狗是 WebView 节流所迫（真机实证）。
 private val reloadDelays = longArrayOf(0L, 3_000L, 10_000L)

 var state: State = State.CONFIG
 private set

 /** 供会话层兜底判定读取的页面事实。 */
 var pageLoadedOnce = false
 private set
 var pageErrorVisible = false
 private set
 var lastProbeHit: String? = null
 private set

 private var reloadCount = 0
 private var lastReloadAt = 0L
 private var lastFrameSignalAt = 0L
 private var foreground = true

 // 降级探测的去抖：连续 2 次命中同一类别才动作，过滤加载瞬间的闪现文本
 private var confirmCategory: String? = null
 private var confirmStreak = 0

 // —— 新鲜度（单点定义，两个判定各自管辖）——

 /** 注入脚本报平安是否新鲜——降级开关用。前台要求高（15s），后台/锁屏放宽
 * （90s：WebView 后台节流会把 JS 心跳压稀，15s 会常态误判失联——真机实证）。 */
 fun frameSignalFresh): Boolean = signalAge) < if (foreground) 15_000L else 90_000L

 /** 注入脚本 JS 本身是否活着——假死检测用（与前台无关：它区分「JS 死了」与
 * 「JS 活着但服务器帧停了」，前者归降级模式，后者才是假死重载的对象）。 */
 fun jsAlive): Boolean = signalAge) < 90_000L

 /** DOM 文本探测只在降级模式跑：注入脚本失联时由它接管（）。 */
 fun shouldProbeDom): Boolean = state != State.CONFIG && !frameSignalFresh)

 fun onForegroundChanged(fg: Boolean) {
 foreground = fg
 }

 fun onFrameSignal) {
 lastFrameSignalAt = SystemClock.elapsedRealtime)
 // 帧桥持续活着=页面真恢复。RECONNECTING 的回收不能只靠降级探测的健康路径
 // （ 后正常态 DOM 探测不跑，没人喂 onProbeHit(null)）
 maybeRecover)
 }

 private fun signalAge) = SystemClock.elapsedRealtime) - lastFrameSignalAt

 /** 重载后保持健康 60s 视为真恢复：计数清零、回 OK。 */
 private fun maybeRecover) {
 if (reloadCount > 0 && SystemClock.elapsedRealtime) - lastReloadAt > 60_000L) {
 reloadCount = 0
 if (state == State.RECONNECTING) state = State.OK
 }
 }

 /** 供会话层兜底：最近 30s 内是否发生过自动重载。 */
 fun recentlyReloaded): Boolean =
 reloadCount > 0 && SystemClock.elapsedRealtime) - lastReloadAt < 30_000L

 // —— 输入：信号源报事实 ——

 /** 进入 WebView（扫码/输链接成功、启动直连、渲染进程重建）。 */
 fun onEnterWeb) {
 state = State.OK
 reloadCount = 0
 lastFrameSignalAt = 0L
 confirmCategory = null
 confirmStreak = 0
 pageLoadedOnce = false
 pageErrorVisible = false
 lastProbeHit = null
 }

 /** 注入脚本报链接已死（WS error 帧 / close 判死 / 终态标题锚）。hint=完整提示文案。 */
 fun onTerminal(hint: String) {
 if (state == State.CONFIG) return
 state = State.CONFIG
 actor.rescan(hint)
 }

 /** 可恢复终态（desktop-disconnected/relay-unavailable）：凭据仍有效——桌面端断电重启/
 * 中继临时故障都属此类，清凭据会强迫用户重新扫码（2026-09-09 用户断电实测踩坑）。
 * 回配置但保留链接等用户手动重连（自动重试方案同日被弃用，见 Actor.rescanKeepLink）。 */
 fun onRecoverableTerminal(hint: String) {
 if (state == State.CONFIG) return
 state = State.CONFIG
 actor.rescanKeepLink(hint)
 }

 /** 用户手点「重新扫码配对」。 */
 fun onUserRescan) {
 state = State.CONFIG
 actor.rescan(null)
 }

 /** 用户手点错误覆盖层「重试」。 */
 fun onUserRetry) {
 reloadCount = 0
 state = State.RECONNECTING // 不残留 EXHAUSTED：否则 60s 慢重试会误触发
 actor.hideExhausted)
 actor.reloadAfter(0)
 }

 /** 页面主框架加载完成。 */
 fun onPageFinished) {
 pageLoadedOnce = true
 pageErrorVisible = false
 }

 /** 页面主框架加载失败。 */
 fun onPageMainError) {
 pageErrorVisible = true
 scheduleReload)
 }

 /** 降级 DOM 探测结果：hit=null 健康；"A"=瞬态；"B"=终态。内部去抖与决策。 */
 fun onProbeHit(hit: String?) {
 lastProbeHit = hit
 if (hit == null) {
 confirmCategory = null
 confirmStreak = 0
 maybeRecover)
 return
 }
 if (hit == confirmCategory) confirmStreak++ else {
 confirmCategory = hit
 confirmStreak = 1
 }
 if (confirmStreak >= 2) {
 confirmStreak = 0
 confirmCategory = null
 if (hit == "B") {
 state = State.CONFIG
 actor.rescan(terminalHint))
 } else {
 scheduleReload)
 }
 }
 }

 /** 网络恢复（含网络切换）。ERROR 态立即重试；已加载过页面的无条件重载
 * （同段说明）。 */
 fun onNetworkRecovered) {
 if (state == State.CONFIG) return
 if (state == State.EXHAUSTED) {
 reloadCount = 0
 state = State.RECONNECTING
 actor.hideExhausted)
 actor.reloadAfter(0)
 return
 }
 if (pageLoadedOnce || lastProbeHit != null || pageErrorVisible) {
 scheduleReload)
 }
 }

 /** 60s 慢重试 tick。返回 true=本次 tick 消费在 EXHAUSTED 自愈重试上。
 * 假死检测不在 machine：它需要「通知当前显示 RUNNING」这个会话层事实，由调用方判定后
 * 经 scheduleReload 路径回来。 */
 fun onSlowTick): Boolean {
 if (state != State.EXHAUSTED) return false
 reloadCount = 0
 state = State.RECONNECTING
 actor.hideExhausted)
 actor.reloadAfter(0)
 return true
 }

 /** 假死判定成立（调用方综合会话层事实「通知显示运行中+帧流空闲」），走统一重载出口。 */
 fun onStallDetected) {
 scheduleReload)
 }

 /** 回前台重建判定成立（）：后台时长超重放宽限启发式由调用方判定，此处守卫+统一出口。
 * 与 onStallDetected 分开命名——触发前提不同（假死=运行中帧流停 vs 本条=后台超时），
 * 防后来者按注释误读。守卫：CONFIG 无页面、RECONNECTING/EXHAUSTED 已在恢复路上让位
 * （网络回调可能与 resume 前后脚到达，重复触发连烧计数+连刷页）；OK 态进入=新周期先清
 * 计数（45-60s 快速前后台切换不该攒满 EXHAUSTED 误显错误覆盖层，对账修正）。 */
 fun onForegroundStale) {
 if (state != State.OK) return
 reloadCount = 0
 scheduleReload)
 }

 // —— 决策（唯一重载出口）——

 private fun scheduleReload) {
 if (state == State.CONFIG) return
 if (!actor.hasNetwork)) return // 断网期间不烧重载次数，等网络恢复回调来触发
 if (reloadCount >= reloadDelays.size) {
 state = State.EXHAUSTED
 actor.notifyReconnecting)
 actor.showExhausted)
 return
 }
 state = State.RECONNECTING
 actor.notifyReconnecting)
 val delay = reloadDelays[reloadCount]
 reloadCount++
 lastReloadAt = SystemClock.elapsedRealtime)
 actor.reloadAfter(delay)
 }
}
