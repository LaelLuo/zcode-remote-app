// 真机验证辅助：经 CDP 直驱手机 WebView（锁屏下唯一可用通道）。
// 用法：bun scripts/cdp.ts <表达式> —— 在远程控制页面主世界执行 JS 并打印结果
// 依赖 adb forward tcp:9222 localabstract:webview_devtools_remote_<pid> 已建好。
const expr = process.argv[2];
if (!expr) {
 console.error("用法: bun scripts/cdp.ts <表达式>");
 process.exit(1);
}

const list = await (await fetch("http://127.0.0.1:9222/json/list")).json);
const page = list.find((t: { type: string }) => t.type === "page");
if (!page) {
 console.error("无 page 目标");
 process.exit(1);
}

const ws = new WebSocket(page.webSocketDebuggerUrl);
let seq = 0;
const pending = new Map<number, (v: unknown) => void>);

ws.onmessage = (ev: MessageEvent) => {
 const m = JSON.parse(String(ev.data)) as { id?: number; result?: unknown };
 if (m.id != null && pending.has(m.id)) {
 pending.get(m.id)!(m.result);
 pending.delete(m.id);
 }
};

function call(method: string, params: Record<string, unknown> = {}): Promise<{ result?: { value?: unknown; exceptionDetails?: unknown } }> {
 return new Promise((resolve) => {
 const id = ++seq;
 pending.set(id, resolve as (v: unknown) => void);
 ws.send(JSON.stringify({ id, method, params }));
 });
}

await new Promise<void>((resolve, reject) => {
 ws.onopen = ) => resolve);
 ws.onerror = ) => reject(new Error("ws 连接失败（adb forward 还在吗）"));
 setTimeout() => reject(new Error("ws 连接超时")), 5000);
});

const r = await call("Runtime.evaluate", {
 expression: expr,
 returnByValue: true,
 awaitPromise: true,
});
if (r.result?.exceptionDetails) {
 console.error("页面内异常:", JSON.stringify(r.result.exceptionDetails, null, 2));
} else {
 console.log(JSON.stringify(r.result?.value, null, 2));
}
ws.close);
process.exit(0);
