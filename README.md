# zcode-remote-app

Android 壳 app：把 ZCode 桌面端的「Web 远程控制」页面装进一个全屏 WebView，解决手机浏览器访问的两大痛点——后台断连要手动刷新、浏览器自身 UI 占空间。

> 非官方项目，与 ZCode 及其开发商无关；个人学习与自用目的，请自行遵守当地法律法规与官方服务条款。

## 背景与动机

ZCode 桌面端自带 Web 远程控制（生成二维码/链接，手机浏览器打开即可控制工作区）。实际使用中：

- 手机浏览器切后台/锁屏后被系统冻结，WebSocket 心跳中断，切回来要重新连接甚至手动刷新页面。
- 浏览器地址栏/工具栏占用屏幕空间。
- 官方对断线的处理文案就是「刷新页面后重新连接」。

2026-09-02 设计决策做 Android 壳 app（路线 A），不做协议逆向原生客户端（路线 B，已否决）。

## 远程控制机制（逆向结论）

来源：ZCode 桌面端安装目录 `resources/app.asar` 逆向与运行日志，2026-09-02。

```
桌面 ZCode ←WebSocket→ 官方中继（wss://zcode.chatglm.site/ws，备用 zcode.z.ai/ws）←WebSocket→ 手机 Web UI
```

- **配对凭据**：桌面端首次开启时生成随机密码，`passHash`=**sha256(密码) 的 base64**（勘误 2026-09-15：本行原写「sha1 前 16 位 hex」，实为日志内容指纹的误认，桌面侧源码级定位见 docs/desktop-remote-architecture.md），向中继注册换取 `deviceSid`（setting.json 的 `webRemoteControlExternalRelayDevice` 存 deviceSid；passHash 存凭据服务键 `web-remote-control:external-relay:pass_hash`）。
- **链接形态**：`<baseUrl>?sid=<deviceSid>&hash=<passHash>&t=<时间戳>&deviceMid=…&deviceName=…&theme=…`。二维码内容即此 URL。
- **链接不过期**：`t` 参数在 Web 端无任何校验逻辑（全 asar 无 `searchParams.get("t")`），`hash` 就是 passHash 本身，不绑时间戳。**存住 URL 即可长期复用。**
- **失效场景**：持久凭据 AUTH_FAILED 时桌面端自动降级重新注册（仅试一次），拿到新 deviceSid，旧 URL 作废 → 需重新扫码。低频，壳 app 提供重新扫码入口兜底。
- **会话互斥**：同一链接被第二个页面打开会互踢（`session-conflict` / `external-relay-kicked`）。
- **断连根因**（日志实证）：手机浏览器后台冻结心跳断；中继自身 INTERNAL 错误（当日日志 15 次）；断线后 RPC 帧重放超时降级（`remote.rpcFrame.replayGraceExceeded`）。

## 目录结构

- `app/`：Android 工程（Kotlin + WebView + 前台服务）。
- `docs/`：中继帧协议与桌面侧架构的逆向笔记。

## 红线

- 不逆向中继协议自写客户端（路线 B 已否决，除非路线 A 验证失效）。
- 不改 ZCode 桌面端本体，只消费它生成的链接。

## 手机保活设置（小米 15 / HyperOS 3.0，实测机型）

app 自身机制：前台常驻服务（connectedDevice 类型）+ 不挂起 WebView（后台保持页面心跳），断线自动重载（退避 0/3/10s，耗尽降 60s 自愈）。系统侧只需两步：

1. 安装后首次启动，允许通知权限（拒绝也不阻塞，但看不到状态通知与岛）。
2. 最近任务里把 app 锁住（下拉加锁），避免手动清后台时连服务一起杀。

不建议额外改电池策略——夜测（2026-09-07 全天 46 轮探测）显示默认策略下前台服务整夜存活，锁屏深省电不影响。
