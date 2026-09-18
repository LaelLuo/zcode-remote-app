# 远程控制页面中继帧协议笔记

来源：2026-09-07 真机 CDP 帧记录（CDP「文档创建时注入」覆写 WebSocket 旁听；采集时页面
reload 重新握手 + turn 运行中）。样本为本地采集的分析产物，未随仓库分发；可用
`scripts/frame-recorder.ts` 自行采集（产物默认写 `artifacts/`，已被 gitignore）。仅旁听未发送任何帧。

## 外层：与中继的 WebSocket

- 页面建连：`wss://zcode.z.ai/ws?mid=<设备mid>`（一条）；握手帧序列（已实证）：
  1. 收 `{"type":"auth_challenge","server_ts":…,"nonce":…}`
  2. 收 `{"type":"auth_ack","server_ts":…,"device_sid":"d_…","terminal_sid":"t_…",…}`
  3. 之后全部为 `{"type":"data","server_ts":…,"payload":{…}}` 帧
- `data` 帧的 payload 常见形态（已实证）：
  - `{bridgeGeneration, bridgeSessionId, checksum:{algorithm:"crc32",value}, dataBase64}`——批量数据，**base64 解码后是「二进制小端头 + JSON 正文」**，从头第一个 `{` 起是合法 JSON
  - `{bridgeGeneration, bridgeSessionId, ackMessageSeq}`——纯确认帧
  - `{requestId:"bootstrap-…", result:{…}}`——初始快照（见下）
- 二进制头内还有 wire 封装形态：`{"wireVersion":3,"kind":"complete","deliveryKind":"online","logicalFrameId":…,"logicalFrameOrdinal":…,"topic":…,"frame":{真正数据}}`（kind 疑似还有分片形态，未观测到）

## 事件层：topic 订阅 + delta 流（已实证）

- `controller/tasks-index`：`payload.kind="deltas"`，`deltas[].op="task.upserted"`，
  `deltas[].task` 字段（本会话 running 时的完整样本）：
  - `meta.status:"running"`、`liveStatus:"running"`、`activity.phase:"running"`
  - `meta.title`（会话标题）、`meta.workspacePath`、`meta.model`、`meta.provider`
  - `sourceAvailability:"online"`、`membership:{pinned,archived,active}`
- `sessions-index/<workspacePath>`：`deltas[].op="session.upserted"`，`session` 字段：
  - `phase:"running"`、`sessionEnded:false`、`lastAssistantPreview`（实时回复预览）、
    `title`、`lastTerminalQuery`
- `controller/workspaces`：snapshot 含全部工作区 `connectionState`
- bootstrap 帧（`requestId:"bootstrap-…"`）的 `result.initialViewState.activeTaskId` +
  `result.tasks[]`（每项含 `displayStatus:"running"`、taskId、title、workspacePath）

## 待实证（第二轮采集补）

- ~~turn 异常中断时的取值与是否有独立错误事件~~——值空间已从 bundle 的校验 schema 实证：
  `liveStatus: ["idle","running","waiting","completed","error"]`（zod 枚举，error 值存在）；
  **真实 error 场景已在真机验证**（provider 认证失败）：task.upserted 增量 liveStatus="error" 翻转正常驱动 SEND_FAILED。**错误详情不在帧数据**（指会话内 provider 错误——2026-09-15 桌面源码级补充：桥/请求层错误在 app-error/workspace-bridge-error 帧的 reason+error 字段，壳 app 已接入感知）——session/task 完整 schema 均无 error 字段，错误对象只存在于会话视图的渲染层（DOM 锚 `[data-error-code]`，React 属性 `error.message`），结构见下文「完整 schema」的错误对象条目
- ~~waiting 的语义（疑似等用户输入/确认）与通知态映射~~——waiting 单列为「等输入」态（上岛+横幅提醒），列表聚合 waitingCount
- 会话列表页 vs 会话详情页的订阅差异（当前只旁听不订阅，不影响）
- `kind` 的分片形态（chunked）与 `payload.kind` 的其他取值（snapshot 已见于 workspaces）。**kind=hello 已实证**（2026-09-16 壳 app 分片防御首次捕获）：RPC 握手信封 `{"kind":"hello","protocolVersion":3,"connectionId":"host-rpc-…","clientMode":"web-remote-replayable","deliveryProfile":"replayable",…}`，无 topic，属正常协议帧非分片（frame_hook 白名单跳过不计数）；真分片形态（kind 值待定）仍待实证——出现时 frame_hook 计数+样本头自动落 FRAGMENT 日志行
- 已实证转换样本（第二轮采集）：running→completed 转换走同一 task.upserted 增量，
  字段 status/liveStatus 同步翻转为 "completed"（276 帧）

## 完整 schema（2026-09-07 晚从前端 bundle zod 定义提取，混淆名还原）

- **session**（sessions-index 的 session.upserted.session）：sessionId, workspaceId, parentSessionId?, title, titleSource?, phase, sessionEnded, hasBackgroundWork, pendingInteraction?, pendingInteractionSummary?, goalStatus?, lastActivityAt, lastAssistantPreview?, lastTerminalQuery?, createdAt
- **task**（tasks-index 的 task.upserted.task）：address{remoteSessionId?, workspacePath, workspaceIdentity?, taskId}, meta{taskId, title, status, workspacePath, workspaceIdentity?, model, provider…}, membership{pinned, archived, active}, sourceAvailability["online","offline"], liveStatus["idle","running","waiting","completed","error"], activity{phase, lastActivityAt, hasBackgroundWork, pendingInteractions?}?, searchSnippets?[]
- **错误对象**（不在帧内，仅渲染层）：{code, message, traceId, attribution{source, reason, errorPhase, exceptionKind, providerId, modelId, providerKind, transport, statusCode, retryable}, taskId}

## 对 app 的映射价值

- RUNNING 判定=bootstrap.tasks[].displayStatus=="running" 或 task.upserted.liveStatus=="running"
  （任一订阅到即真，与当前打开哪个会话视图无关——比 DOM 探测强的点）
- DONE/SEND_FAILED 判定=待第二轮样本
- 假死检测=帧流空闲 + 声称 running

## 传输层控制帧与终态分发（2026-09-08 从官方前端打包产物逆向；壳 app 已落地三层终态感知）

WS 入站消息（onmessage 顶层 JSON）分发器（伪代码还原）：

```
switch (msg.type) {
  case "pair_status_ack": applyPairStatus(msg.pair_status); break  // waiting|matched
  case "data":            handleDataPayload(msg.payload);  break  // 上述任务/会话帧
  case "error":           handleRelayError(msg.code, msg.message); break
}
```

**relay error 帧的码表**（handleRelayError 语义，app 的 reportRelayError 照抄）：

- `KICKED` → 终态 session-conflict（被桌面端重置/踢出）
- `AUTH_FAILED` / `WRONG_PARAM` → 终态 invalid-mobile-connection
- `DEVICE_OFFLINE` → 可恢复（recoverFromDeviceOffline）
- `INTERNAL` → 可恢复（已配过对则转等待桌面端）
- 未知码 → 终态 relay-unavailable（官方兜底分支）

**enterTerminalFailure 五条路径**：上述 error 帧 + 「等待配对超时」（纯客户端 30s 定时器
`waitingTimeoutMs??3e4`，state 仍 waiting 即判死，无 WS 信号）+「桌面离线宽限」
（desktopOfflineGraceMs??15e3）。落地动作：setState(`kicked`|`error`) + options.onFailure +
socket.close()；React 渲染挂 `data-error-code="<reason>"` 的错误组件（任务错误横幅同款组件，
code 值域不同）。

**app 终态感知**（09-08 定稿）：死亡通知只认两个明确来源——① WS error 帧直报
（毫秒级，码表照抄官方 handleRelayError）② 终态页 h1 标题双语锚（渲染后 ~0.5s，覆盖 30s
等待超时这类无错误帧场景）③ probe DOM 文本轮询（降级模式兜底：注入脚本失联才跑）。全通向
performRescan（清凭据回配置界面+红字原因）。

## 终态感知实测修正（09-08 真机迭代定稿）

- 传输终态页 `_4t({failure,locale})` 组件不挂任何 data 属性（h1=r.title）——`data-error-code`
  是会话消息错误组件的锚，不可用于传输终态检测（曾挂错真机落空一轮）
- ~~WS close 层判 waiting 超时终局~~（**已退役**）：曾以「零 data 帧+close(1005)」判死并真机
  命中 108ms，实测中网络抖动会误清凭据——**页面重连前也主动关旧连接（1005），与超时
  自杀无法区分**，撞上刚配对/app 重载后的无数据窗口即误杀。close(1005) 多产生路径不可单独
  作判据；等待超时终态由 h1 标题锚兜住（慢半秒、零误杀）。close 事件保留为诊断日志
- 终态文案映射表（key→双语 badge/title）：invalid-mobile-connection=校验失败/手机连接已失效、
  session-conflict=设备接管/已被其他设备接管、relay-unavailable=中转异常/无法连接中转服务、
  desktop-disconnected=电脑端离线/桌面端已离线（表内混有非终态键如 desktop-bootstrap-timeout，
  按 badge/title 匹配时须限定四码）

## tasks-index 增量 op 全集（2026-09-09 bundle 实证，zod schema 直读）

`task.upserted`（task 全量）与 `task.removed`（仅 address.taskId）——**只有这两个**。
曾只处理 upserted：已删任务永留本地表（幽灵），叠加「全文 indexOf 配对当前会话」
对超短标题（hi 等）恒命中，导致已删会话顶替通知标题。删除 delta 的
正确处理=从本地表 delete 并触发上报。
