# zcode-remote-app

Android 壳 app：把 ZCode 桌面端的「Web 远程控制」页面装进一个全屏 WebView，解决手机浏览器访问的两大痛点——后台断连要手动刷新、浏览器自身 UI 占空间。

> 非官方项目，与 ZCode 及其开发商无关；个人学习与自用目的，请自行遵守当地法律法规与官方服务条款。License: [MIT](LICENSE)。

## 功能

- **免扫码直连**：配对一次，链接持久保存，冷启动零操作自动连接（链接可长期复用，原理见下文）。
- **保活抗后台冻结**：前台常驻服务 + 后台不挂起 WebView，页面心跳持续——浏览器形态「切后台就断」的根因被绕开。
- **断线自动恢复**：页面重载退避（0/3/10s，耗尽降 60s 低频自愈）+ 网络切换检测整页重载 + 帧流假死检测 + 后台超时回前台主动重建。
- **会话状态常驻通知**：任务列表聚合（「N 个任务工作中/等输入/失败」）或当前会话状态（标题=会话名，正文=最新回复实时滚动）。
- **超级岛（灵动岛）显示**：Android 16 Live Updates 标准通道，HyperOS 3.0.300+ 渲染为超级岛/状态栏胶囊（运行中/等输入/已完成/发送失败/重连中）；其他设备回退普通常驻通知。
- **完成/等输入横幅提醒**：任务从运行中转出时弹 HIGH 渠道横幅（可关）。
- **通知直达会话**：点通知直接导航进对应任务会话页。
- **终态感知与保链**：配对失效/被踢/桌面离线等终止场景按码分流——凭据仍有效的（桌面端关机/断网）保留链接回配置页一键重连，不强迫重新扫码。
- **关键事件文件日志**：`files/logs/frame-events.log`（512KB 轮转），连接生命周期/终态码/错误帧/重载动作全落盘，排障有据可查。

## 系统要求

- Android 8.0+（minSdk 26）；超级岛需 Android 16 + HyperOS 3.0.300+，其余机型自动回退普通通知。
- 依赖 ZCode 桌面端开启「Web 远程控制」生成二维码；协议观察对象为 ZCode 桌面 3.12.1，官方更新可能改变页面/帧结构导致探测失效（本项目只能被动适配）。
- 适配验证机型：小米 15 / HyperOS 3。

## 构建

需 JDK 17+ 与 Android SDK（compileSdk 36，AGP 较新，建议直接用最新 Android Studio）：

```sh
cd app && ./gradlew assembleDebug
# 产物：app/app/build/outputs/apk/debug/app-debug.apk
```

仓库不发布 APK，请自行构建与签名。

## 使用

1. 桌面端 ZCode 开启「Web 远程控制」，生成配对二维码。
2. app 首次启动进入配置页，点「扫码配对」扫码（或粘贴链接），自动保存并连接。
3. 之后冷启动零操作直连；断线自动恢复；杀进程重开也直接恢复。

小米/HyperOS 侧建议两步（其他机型未测试）：允许通知权限；最近任务里锁定 app（避免清后台连服务一起杀）。不建议额外改电池策略——实测默认策略下前台服务整夜存活。

## 远程控制链路如何工作（互操作性观察笔记）

来源：对已安装 ZCode 桌面端 `resources/app.asar` 的静态分析与运行日志观察，2026-09-02 起。仅旁听分析，未绕过任何授权。

```
桌面 ZCode ←WebSocket→ 官方中继（wss://zcode.chatglm.site/ws，备用 zcode.z.ai/ws）←WebSocket→ 手机 Web UI
```

- **配对凭据**：桌面端首次开启时生成随机密码，`passHash`=**sha256(密码) 的 base64**（详见 docs/desktop-remote-architecture.md §1.5），向中继注册换取 `deviceSid`（setting.json 的 `webRemoteControlExternalRelayDevice` 存 deviceSid；passHash 存凭据服务键 `web-remote-control:external-relay:pass_hash`）。
- **链接形态**：`<baseUrl>?sid=<deviceSid>&hash=<passHash>&t=<时间戳>&deviceMid=…&deviceName=…&theme=…`。二维码内容即此 URL。
- **链接不过期**：`t` 参数在 Web 端无任何校验逻辑（全 asar 无 `searchParams.get("t")`），`hash` 就是 passHash 本身，不绑时间戳。**存住 URL 即可长期复用。**
- **失效场景**：持久凭据 AUTH_FAILED 时桌面端自动降级重新注册（仅试一次），拿到新 deviceSid，旧 URL 作废 → 需重新扫码。低频，壳 app 提供重新扫码入口兜底。
- **会话互斥**：同一链接被第二个页面打开会互踢（`session-conflict` / `external-relay-kicked`）。
- **断连根因**（日志实证）：手机浏览器后台冻结心跳断；中继自身 INTERNAL 错误（当日日志 15 次）；断线后 RPC 帧重放超时降级（`remote.rpcFrame.replayGraceExceeded`）。

> 安全提示：远程控制链接即凭据（含 hash），泄露等于交出工作区控制权。本 app 只在应用私有存储保存链接、文件日志只记 sid 不记 hash。

## 目录结构

- `app/`：Android 工程（Kotlin + WebView + 前台服务）。
- `docs/`：互操作性观察笔记——[frame-protocol.md](docs/frame-protocol.md)（网页↔中继帧协议，真机实证）、[desktop-remote-architecture.md](docs/desktop-remote-architecture.md)（中继↔桌面↔引擎侧，源码级分析）。
- `scripts/`：开发调试与协议取证辅助脚本（需 bun 与 adb，不参与 app 构建）。`frame-recorder.ts` 可采集帧样本、`cdp.ts` 系列经 CDP 直驱 WebView 调试；部分一次性实验脚本仅作方法留档。

## 设计边界

- 不逆向中继协议自写客户端。
- 不改 ZCode 桌面端本体，只消费它生成的链接。
- 注入脚本只旁听页面 WebSocket 帧，不发送任何协议帧。
