// 排查：CDP evaluate 包装 ZcodeFrameBridge.onSignal，旁路观察 JS 信号原文（不动页面）。
// 前提：tcp:9222 已 forward 到 WebView。
const seconds = Number(process.argv[2] || 20);
const list = await (await fetch("http://127.0.0.1:9222/json/list")).json);
const page = list.find((t: { type: string }) => t.type === "page");
if (!page) { console.error("无 page 目标"); process.exit(1); }
const ws = new WebSocket(page.webSocketDebuggerUrl);
let id = 0;
const send = (method: string, params: unknown = {}) => {
 const i = ++id;
 ws.send(JSON.stringify({ id: i, method, params }));
 return i;
};
ws.onopen = ) => {
 send("Runtime.enable");
 send("Runtime.evaluate", {
 expression: `(function){
 if (!window.ZcodeFrameBridge) return "no-bridge";
 if (window.__peekInstalled) return "already";
 window.__peekInstalled = true;
 const raw = window.ZcodeFrameBridge.onSignal.bind(window.ZcodeFrameBridge);
 window.ZcodeFrameBridge.onSignal = function(s){ console.log('[peek]'+s); raw(s); };
 return "wrapped";
 }))`,
 returnByValue: true,
 });
};
ws.onmessage = (ev) => {
 const m = JSON.parse(String(ev.data));
 if (m.method === "Runtime.consoleAPICalled") {
 for (const a of m.params.args) {
 if (a.value && String(a.value).startsWith("[peek]")) console.log(String(a.value).slice(0, 400));
 }
 } else if (m.id && m.result?.result?.value) {
 console.log("eval:", m.result.result.value);
 }
};
setTimeout() => { console.log("--- done"); process.exit(0); }, seconds * 1000);
