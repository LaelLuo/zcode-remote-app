# 远程控制页面中继帧协议笔记（ 调研）

来源：2026-09-07 真机 CDP 帧记录（CDP「文档创建时注入」覆写 WebSocket 旁听，样本
artifacts/v8-frame-samples.txt + v8-frame-deoded.txt，采集时页面 reload 重新握手 +
turn 运行中）。仅旁听未发送任何帧。

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

- turn 异常中断时的取值与是否有独立错误事件——值空间已从 bundle 的校验 schema 实证：
 `liveStatus: ["idle","running","waiting","completed","error"]`（zod 枚举，error 值存在）；
 真实 error 转换样本仍待自然发生抓取
- waiting 的语义（疑似等用户输入/确认）与通知态映射——待定收口点名
- 会话列表页 vs 会话详情页的订阅差异（当前只旁听不订阅，不影响）
- `kind` 的分片形态（chunked）与 `payload.kind` 的其他取值（snapshot 已见于 workspaces）
- 已实证转换样本（第二轮采集）：running→completed 转换走同一 task.upserted 增量，
 字段 status/liveStatus 同步翻转为 "completed"（276 帧）

## 对 app 的映射价值（ 设计输入，待定稿）

- RUNNING 判定=bootstrap.tasks[].displayStatus=="running" 或 task.upserted.liveStatus=="running"
 （任一订阅到即真，与当前打开哪个会话视图无关——比 DOM 探测强的点）
- DONE/SEND_FAILED 判定=待第二轮样本
- 假死检测=帧流空闲 + 声称 running（）
