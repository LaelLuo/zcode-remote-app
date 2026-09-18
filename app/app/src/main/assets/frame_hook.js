// 帧拦截注入脚本：覆写页面 WebSocket，旁听与中继的帧流（只听不发），
// 提炼任务/会话状态 + 视图语境，经 ZcodeFrameBridge 桥周期上报给 Kotlin。
// 协议结构依据 docs/frame-protocol.md（真机样本实证）。
(function () {
  if (window.__zcodeFrameHook) return;
  window.__zcodeFrameHook = true;

  var bridge = window.ZcodeFrameBridge;
  if (!bridge) return; // 桥缺失时不做任何事（页面功能不受影响）

  var tasks = {};          // taskId -> { live, title }
  var previews = {};       // 会话标题 -> lastAssistantPreview（session.upserted 的实时回复预览）
  var lastFrameAt = 0;     // 最近一次真实中继帧的 epoch ms
  var pendingReport = 0;   // 即时上报的合并定时器（帧风暴时 300ms 合并一次）
  var pairState = '';      // 最近一次 pair_status_ack 的值（waiting|matched）——诊断观察
  var everData = false;    // 收到过 data 帧=配对成功过（close 层终态判定：waiting 期无任何 data 帧）
  var wsOffline = false;   // 任一工作区 connectionState ∈ {disconnected, reconnecting}
  var fragSeen = 0;        // wire kind≠complete 的分片/未知信封计数（随信号上报）
  var fragParseFails = 0;  // dataBase64 解析失败计数（半截分片死在 JSON.parse，不经 handleInner）
  var fragSample = '';     // 首个分片样本头（kind/topic/前缀）——只记首个，反哺协议待实证节
  // 事件锚：任务从 running 翻出的瞬间记名（列表聚合事件的横幅实体来源——计数帧没有"是谁"，
  // 横幅若用"最近打开的会话"会串台：用户看 A、后台 B 转态，横幅却写 A）
  var lastWaitingTitle = '';
  var lastDoneTitle = '';

  // 状态变化即时上报：300ms 合并窗口（bootstrap 后初始帧风暴不去逐帧触发 DOM 读取）
  function scheduleReport() {
    if (pendingReport) return;
    pendingReport = setTimeout(function () { pendingReport = 0; report(); }, 300);
  }

  // —— 帧解析：外层 {type:"data"} → payload.dataBase64 → 字节级跳二进制头 → UTF-8 JSON ——
  function extractDataEvents(outer) {
    everData = true; // data 帧=工作区数据，只有配对成功后中继才推
    var payload = outer.payload || {};
    // 错误帧感知（zcode_type 判定，与 bootstrap 快照同层；bundle 实证页面侧同入口）。
    // 只上报不驱动终态：app-error 是请求失败回执（reason 与 terminal 码同名不同义），
    // bridge-degraded 页面自己 markDegraded 自愈——三类全部仅作感知取证（APPERR 日志）
    var zt = payload.zcode_type;
    if (zt === 'app-error' || zt === 'workspace-bridge-error' || zt === 'bridge-degraded') {
      reportAppError(zt, payload);
      return;
    }
    // 工作区掉线直读（源①：zcode_type 推送，签名变更即推；源②在 handleInner 的
    // controller/workspaces 快照——重载后状态恢复兜底）
    if (zt === 'workspace-list-updated') {
      applyWorkspaceStates(payload.result && payload.result.workspaces);
      return;
    }
    if (payload.dataBase64) {
      try {
        var bin = atob(payload.dataBase64);
        var bytes = new Uint8Array(bin.length);
        for (var i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i);
        var brace = bytes.indexOf(0x7b); // '{' 的字节值；二进制小端头在前
        if (brace < 0) return;
        var text = new TextDecoder('utf-8').decode(bytes.subarray(brace));
        handleInner(JSON.parse(text));
      } catch (e) { fragParseFails++; if (!fragSample) fragSample = 'parseFail'; /* 半截分片/未识别帧：计数不静默丢 */ }
    } else if (payload.requestId && payload.result && payload.result.tasks) {
      // bootstrap 快照：tasks[].displayStatus
      var list = payload.result.tasks;
      for (var k = 0; k < list.length; k++) {
        tasks[list[k].taskId] = {
          live: list[k].displayStatus || 'unknown',
          title: list[k].title || ''
        };
      }
      scheduleReport();
    }
  }

  // 内层 wire 帧：{wireVersion, kind, topic, frame:{topic, payload:{kind:"deltas", deltas:[…]}}}
  // ——真实数据在 frame.payload（排查结论：此前误读 j.payload，增量解析从未成功过，
  // 状态全靠 WS 重连时的 bootstrap 快照刷新兜着，分钟级延迟；兼容直挂 payload 的形态防御结构变化）
  function handleInner(j) {
    // 分片防御：kind 检查必须先于 topic 检查——分片信封可能缺 topic，首行 topic
    // 检查会把它挡在 kind 识别之前（对账修正）。完整重组待捕捉到真实分片样本后再实现。
    // 已知合法 kind 白名单：complete=普通逻辑帧；hello=RPC 握手信封（已在真实流量中观测到：
    // kind=hello/无 topic/clientMode=web-remote-replayable，握手帧无任务数据，跳过不计数）
    if (j && j.kind && j.kind !== 'complete') {
      if (j.kind === 'hello') return;
      if (!fragSample) {
        try { fragSample = 'kind=' + j.kind + ' topic=' + (j.topic || '') + ' head=' + JSON.stringify(j).slice(0, 160); } catch (e) {}
      }
      fragSeen++;
      return;
    }
    if (!j || !j.topic) return;
    var p = (j.frame && j.frame.payload) || j.payload;
    if (!p) return;
      if (j.topic === 'controller/workspaces') {
      // 工作区状态源②：workspaces 快照（bootstrap 同构，重载后状态恢复兜底）
      applyWorkspaceStates(p.workspaces || (p.snapshot && p.snapshot.workspaces));
    } else if (j.topic === 'controller/tasks-index') {
      var deltas = p.deltas;
      if (!deltas) return;
      var changed = false;
      for (var i = 0; i < deltas.length; i++) {
        var d = deltas[i];
        if (d.op === 'task.upserted' && d.task) {
          var t = d.task;
          var id = (t.address && t.address.taskId) || (t.meta && t.meta.taskId);
          if (id) {
            var live = t.liveStatus || (t.meta && t.meta.status) || 'unknown';
            var prev = tasks[id];
            // 事件锚：running 翻出的瞬间记名（见 lastWaitingTitle 声明处注释）
            if (prev && prev.live === 'running' && live !== 'running') {
              var evTitle = (t.meta && t.meta.title) || prev.title || '';
              if (live === 'waiting') lastWaitingTitle = evTitle;
              else if (live === 'completed' || live === 'error') lastDoneTitle = evTitle;
            }
            if (!prev || prev.live !== live) changed = true;
            tasks[id] = {
              live: live,
              title: (t.meta && t.meta.title) || (prev && prev.title) || ''
            };
          }
        } else if (d.op === 'task.removed' && d.address && d.address.taskId) {
          // 删除 delta（协议实证：tasks-index 只有 upserted/removed 两个 op）——
          // 不处理则已删任务成幽灵，永远参与标题配对与计数（已删会话的残留项会顶替通知标题）
          if (tasks[d.address.taskId]) {
            delete tasks[d.address.taskId];
            changed = true;
          }
        }
      }
      // 任务状态变化即时上报（300ms 合并）；纯心跳式 upsert（状态没变）不触发，
      // 交给 5s 定时器兜底——帧里高频的 updatedAt 抖动不该打搅 DOM 读取
      if (changed) scheduleReport();
    } else if (j.topic && j.topic.indexOf('sessions-index/') === 0) {
      // 会话实时预览：lastAssistantPreview=流式回复的滚动预览（通知正文「最新一条消息」的数据源）。
      // preview 变化即时上报，同样吃 300ms 合并——流式高频不至于刷爆桥
      var sDeltas = p.deltas;
      if (!sDeltas) return;
      for (var si = 0; si < sDeltas.length; si++) {
        var sd = sDeltas[si];
        if (sd.op === 'session.upserted' && sd.session) {
          var st = sd.session.title || '';
          var pv = sd.session.lastAssistantPreview;
          if (st && typeof pv === 'string' && pv !== previews[st]) {
            previews[st] = pv;
            scheduleReport();
          }
        }
      }
    }
  }

  // —— 错误帧上报：JSON.stringify 构造（帧内 reason/error 可能含引号，手拼会产生
  //    非法 JSON 被 Kotlin 静默丢——对账修正的构造约束；app-error/workspace-bridge-error 带
  //    requestId/reason/error，bridge-degraded 只带 bridgeSessionId/reason=rpc-transport-fault）——
  function reportAppError(zt, payload) {
    try {
      bridge.onSignal(JSON.stringify({
        appError: String(payload.reason || ''),
        detail: String(payload.error || '').slice(0, 200),
        zt: zt,
        requestId: String(payload.requestId || '')
      }));
    } catch (e) { /* 桥异常静默 */ }
  }

  // —— 工作区状态聚合：任一 connectionState ∈ {disconnected, reconnecting} 即离线 ——
  function applyWorkspaceStates(workspaces) {
    if (!workspaces || !workspaces.length) return;
    var off = false;
    for (var i = 0; i < workspaces.length; i++) {
      var cs = workspaces[i] && workspaces[i].connectionState;
      if (cs === 'disconnected' || cs === 'reconnecting') { off = true; break; }
    }
    if (off !== wsOffline) {
      wsOffline = off;
      scheduleReport();
    }
  }

  function onWireMessage(text) {
    lastFrameAt = Date.now();
    try {
      var outer = JSON.parse(text);
      if (outer.type === 'data') extractDataEvents(outer);
      else if (outer.type === 'error') reportRelayError(outer);
      else if (outer.type === 'pair_status_ack') {
        var ps = String(outer.pair_status || '');
        if (ps !== pairState) {
          pairState = ps;
          // 诊断：pair_status_ack 到达与取值可见（close 层判定的输入）
          try { bridge.onSignal('{"life":"pair","pairState":"' + ps + '"}'); } catch (e) {}
        }
      }
    } catch (e) { /* 非 JSON 帧忽略 */ }
  }

  // —— WS 层终态直报（第一手，早于 React 渲染）：中继 error 帧的分发语义照抄官方
  // handleRelayError（官方 web 包的 remote-bundle.js，本地逆向副本）：KICKED/AUTH_FAILED/WRONG_PARAM 终态，
  // DEVICE_OFFLINE/INTERNAL 可恢复不报，未知码=官方兜底 relay-unavailable 也终态。
  // 即时单帧上报不进 300ms 合并——量极小，且这一毫秒就是它存在的意义
  var RELAY_FATAL = { KICKED: 'session-conflict', AUTH_FAILED: 'invalid-mobile-connection', WRONG_PARAM: 'invalid-mobile-connection' };
  var RELAY_RECOVERABLE = { DEVICE_OFFLINE: 1, INTERNAL: 1 };
  function reportRelayError(outer) {
    var code = String(outer.code || '');
    if (!code || RELAY_RECOVERABLE[code]) return;
    var reason = RELAY_FATAL[code] || 'relay-unavailable';
    try {
      bridge.onSignal('{"terminal":"' + reason + '","relayCode":"' + code + '"}');
    } catch (e) { /* 桥异常静默，DOM 探测兜底 */ }
  }

  // close(1005) 语义不唯一，不做终态判定（网络抖动场景曾误判，该判定已退役）：
  // 页面重连前同样主动关旧连接（1005），与等待超时自杀无法区分——曾以「零 data 帧
  // +1005」判 waiting 终局，网络抖动撞上刚配对/重载后的无数据窗口即误清凭据。
  // 等待超时终态由 h1 标题锚层兜住（渲染后 ~0.5s，零误判）。close 只留诊断
  function reportCloseSignal(code) {
    try {
      bridge.onSignal('{"life":"close","code":' + code + ',"everData":' + everData + '}');
    } catch (e) { /* 桥异常静默 */ }
  }

  // —— 视图语境 + 状态决策（语义约定：列表视图=聚合、会话视图=单会话） ——
  // 视图判定用 XPath 定点查「任务会话」标题（文本节点查询不触发样式重排，比读全文便宜）；
  // 标题配对只在视图翻转时做一次——视图内当前会话不变，其状态由帧驱动即时刷新
  var lastView = '';
  var curTitle = '';

  // 终态页锚=渲染标题（_4t 组件 h1=r.title，不挂任何 data 属性——data-error-code 是
  // 会话消息错误组件的锚，曾挂错对象真机实测落空）。四码双语对照官方 web 包
  // remote-bundle.js 映射表；会话页 h1=会话名，撞上这八个标题的概率≈0
  var TERMINAL_TITLES = {
    '手机连接已失效': 'invalid-mobile-connection',
    'Mobile Connection Invalid': 'invalid-mobile-connection',
    '已被其他设备接管': 'session-conflict',
    'Taken Over By Another Device': 'session-conflict',
    '无法连接中转服务': 'relay-unavailable',
    'Relay Unavailable': 'relay-unavailable',
    '桌面端已离线': 'desktop-disconnected',
    'Desktop Offline': 'desktop-disconnected'
  };
  function readTerminalCode() {
    try {
      var h1 = document.querySelector('h1');
      if (!h1) return '';
      return TERMINAL_TITLES[(h1.textContent || '').trim()] || '';
    } catch (e) { return ''; }
  }

  function isSessionView() {
    try {
      var it = document.evaluate(
        "//*[normalize-space(text())='任务会话']", document, null,
        window.XPathResult.FIRST_ORDERED_NODE_TYPE, null
      );
      return !!it.singleNodeValue;
    } catch (e) { return false; }
  }

  function matchSessionTitle() {
    // 会话视图的 h1=页面自己渲染的当前会话名——在 tasks 里精确找同名。
    // 旧法「页面全文 indexOf 任务标题」对超短标题是灾难（已删的「hi」会话匹配
    // 任何含 hi 子串的英文页面，顶替通知标题）；h1 锚+整串相等双重收紧
    try {
      var h1 = document.querySelector('h1');
      var name = h1 ? (h1.textContent || '').trim() : '';
      if (!name) return '';
      for (var id in tasks) {
        if (tasks[id] && tasks[id].title === name) return name;
      }
    } catch (e) { /* fallthrough */ }
    return '';
  }

  // 错误横幅读取：错误详情不在帧数据里
  // （session/task 的帧 schema 均无 error 字段，lastAssistantPreview 对错误会话为空），真实源=
  // 会话视图渲染的错误横幅组件——带 data-error-code 的 DOM 元素，React 属性里挂完整错误对象。
  // 沿 fiber 向上爬找 error.message（干净文本）；React 结构变了退 textContent 截断
  function readErrorBanner() {
    try {
      var el = document.querySelector('[data-error-code]');
      if (!el) return '';
      var fk = null;
      var keys = Object.keys(el);
      for (var i = 0; i < keys.length; i++) {
        if (keys[i].indexOf('__reactFiber$') === 0) { fk = keys[i]; break; }
      }
      if (fk) {
        var f = el[fk];
        for (var d = 0; d < 6 && f; d++) {
          var p = f.memoizedProps;
          if (p && p.error && typeof p.error === 'object' && p.error.message) {
            return String(p.error.message);
          }
          f = f.return;
        }
      }
      return (el.textContent || '').trim().slice(0, 120);
    } catch (e) { return ''; }
  }

  function decide() {
    var view = isSessionView() ? 'session' : 'list';
    if (view !== lastView) {
      lastView = view;
      curTitle = '';
    }
    // 翻转瞬间页面可能还在渲染（innerText 里还没有会话标题），配对会落空——
    // 只试一次的话空标题会让信号 status 为空、Kotlin 侧判回列表视图。session 视图下空标题持续重试直到配上
    if (view === 'session' && !curTitle) curTitle = matchSessionTitle();

    var runningCount = 0;
    var waitingCount = 0;
    var errorCount = 0;
    for (var id0 in tasks) {
      if (!tasks[id0]) continue;
      if (tasks[id0].live === 'running') runningCount++;
      else if (tasks[id0].live === 'waiting') waitingCount++;
      else if (tasks[id0].live === 'error') errorCount++;
    }

    var signal = { view: view, status: '', title: '', preview: '', runningCount: runningCount, waitingCount: waitingCount, errorCount: errorCount, wsOffline: wsOffline ? 1 : 0, frag: fragSeen + fragParseFails, fragSample: fragSample };
    if (view === 'session') {
      var live = '';
      for (var id1 in tasks) {
        if (tasks[id1] && tasks[id1].title === curTitle && curTitle) live = tasks[id1].live;
      }
      signal.status = live || 'unknown';
      signal.title = curTitle;
      signal.preview = previews[curTitle] || '';
      // 错误态正文优先用失败原因（横幅 message）；读到后写缓存，列表视图/后续信号也能用
      if (signal.status === 'error' && !signal.preview) {
        var reason = readErrorBanner();
        if (reason) {
          signal.preview = reason;
          previews[curTitle] = reason;
        }
      }
    }
    signal.terminal = readTerminalCode();
    signal.waitingTitle = lastWaitingTitle;
    signal.doneTitle = lastDoneTitle;
    signal.framesSince = lastFrameAt ? Math.round((Date.now() - lastFrameAt) / 1000) : -1;
    signal.ts = Date.now();
    return signal;
  }

  function report() {
    // 每次都发（framesSince/ts 字段随时间变，去重无意义）：调用方已按触发源各自合并
    try {
      bridge.onSignal(JSON.stringify(decide()));
    } catch (e) { /* 桥异常静默 */ }
  }

  // —— WebSocket 覆写：包实例不换类型 ——
  var O = window.WebSocket;
  function wrap(ws, url) {
    var raw = ws.addEventListener.bind(ws);
    raw('open', function () { try { bridge.onSignal('{"life":"open","url":"' + url + '"}'); } catch (e) {} });
    raw('close', function (ev) { reportCloseSignal(ev.code); });
    ws.addEventListener = function (type, listener, opts) {
      if (type === 'message' && typeof listener === 'function') {
        var wrapped = function (ev) { onWireMessage(String(ev.data)); listener(ev); };
        return raw(type, wrapped, opts);
      }
      return raw(type, listener, opts);
    };
    var userFn = null;
    var protoDesc = Object.getOwnPropertyDescriptor(O.prototype, 'onmessage');
    if (protoDesc && protoDesc.set) {
      Object.defineProperty(ws, 'onmessage', {
        get: function () { return userFn; },
        set: function (fn) {
          userFn = fn;
          protoDesc.set.call(ws, function (ev) { onWireMessage(String(ev.data)); fn && fn(ev); });
        }
      });
    }
    return ws;
  }
  var Hooked = function (url, protocols) {
    var ws = protocols !== undefined ? new O(url, protocols) : new O(url);
    return wrap(ws, String(url));
  };
  Hooked.prototype = O.prototype;
  Hooked.CONNECTING = O.CONNECTING; Hooked.OPEN = O.OPEN;
  Hooked.CLOSING = O.CLOSING; Hooked.CLOSED = O.CLOSED;
  window.WebSocket = Hooked;

  // 视图切换 hook：MutationObserver 监听 DOM 变化（视图切换必然伴随 DOM 大改），
  // 500ms 合并后重判视图——任务流式更新也会触发，但判定走 XPath 定点查，开销可控
  var viewCheckTimer = 0;
  function scheduleViewCheck() {
    if (viewCheckTimer) return;
    viewCheckTimer = setTimeout(function () { viewCheckTimer = 0; report(); }, 500);
  }
  try {
    new MutationObserver(function () { scheduleViewCheck(); })
      .observe(document, { childList: true, subtree: true });
  } catch (e) { /* 观察失败退化为心跳轮询 */ }

  // 定时器=心跳（假死检测依赖 framesSince 字段）；状态与视图变化均已事件驱动
  setInterval(report, 5000);
  report();
})();
