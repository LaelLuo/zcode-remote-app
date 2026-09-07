// 帧格式调研：CDP 在文档创建时注入 WebSocket 包装，把页面与中继的全部帧流
// 打到 console（[frame] 前缀），本脚本收集 console 输出落盘。
// 用法：bun scripts/frame-recorder.ts <采集秒数> [输出文件]
// 前提：adb forward tcp:9222 localabstract:webview_devtools_remote_<pid> 已建。
const seconds = Number(process.argv[2] || 90);
const outFile = process.argv[3] || "artifacts/v8-frame-samples.txt";

const list = await (await fetch("http://127.0.0.1:9222/json/list")).json);
const page = list.find((t: { type: string }) => t.type === "page");
if (!page) {
 console.error("无 page 目标");
 process.exit(1);
}

// 注入脚本：包实例不换类型（页面 instanceof 检查不受影响）；onmessage 赋值与
// addEventListener('message') 双路径都过记录器；open/close 生命周期一并记录。
const hook = `(function){
 const O = window.WebSocket;
 function wrap(ws, url){
 const raw = ws.addEventListener.bind(ws);
 const log = (s) => { try { console.log('[frame]'+s); } catch(e){} };
 raw('open', ) => log('LIFE open '+url));
 raw('close', (ev) => log('LIFE close code='+ev.code+' reason='+ev.reason));
 raw('error', ) => log('LIFE error'));
 ws.addEventListener = function(type, listener, opts){
 if (type === 'message' && typeof listener === 'function') {
 const wrapped = function(ev){ log('MSG '+String(ev.data).slice(0,3000)); listener(ev); };
 return raw(type, wrapped, opts);
 }
 return raw(type, listener, opts);
 };
 let userFn = null;
 const protoDesc = Object.getOwnPropertyDescriptor(O.prototype, 'onmessage');
 Object.defineProperty(ws, 'onmessage', {
 get){ return userFn; },
 set(fn){ userFn = fn; protoDesc.set.call(ws, function(ev){ log('MSG '+String(ev.data).slice(0,3000)); fn && fn(ev); }); }
 });
 return ws;
 }
 const Hooked = function(url, protocols){
 const ws = protocols !== undefined ? new O(url, protocols) : new O(url);
 return wrap(ws, url);
 };
 Hooked.prototype = O.prototype;
 Hooked.CONNECTING = O.CONNECTING; Hooked.OPEN = O.OPEN; Hooked.CLOSING = O.CLOSING; Hooked.CLOSED = O.CLOSED;
 window.WebSocket = Hooked;
 console.log('[frame]HOOK installed');
}));`;

const ws = new WebSocket(page.webSocketDebuggerUrl);
let seq = 0;
const pending = new Map<number, (v: unknown) => void>);
function call(method: string, params: Record<string, unknown> = {}): Promise<{ result?: { identifier?: string } }> {
 return new Promise((resolve) => {
 const id = ++seq;
 pending.set(id, resolve as (v: unknown) => void);
 ws.send(JSON.stringify({ id, method, params }));
 });
}

const lines: string[] = [];
ws.onmessage = (ev: MessageEvent) => {
 const m = JSON.parse(String(ev.data)) as {
 id?: number; method?: string;
 result?: unknown;
 params?: { timestamp?: number; args?: { value?: string }[] };
 };
 if (m.id != null && pending.has(m.id)) {
 pending.get(m.id)!(m.result);
 pending.delete(m.id);
 return;
 }
 if (m.method === "Runtime.consoleAPICalled") {
 const text = (m.params?.args || []).map((a) => String(a.value ?? "")).join(" ");
 if (text.includes("[frame]")) {
 lines.push(`[${new Date).toISOString).slice(11, 23)}] ${text}`);
 process.stderr.write("."); // 进度点
 }
 }
};

await new Promise<void>((resolve, reject) => {
 ws.onopen = ) => resolve);
 ws.onerror = ) => reject(new Error("ws 连接失败"));
 setTimeout() => reject(new Error("ws 连接超时")), 5000);
});

await call("Runtime.enable");
await call("Page.enable");
await call("Page.addScriptToEvaluateOnNewDocument", { source: hook });
console.error("hook 已挂，reload 页面开始采集…");
await call("Page.reload");
await new Promise((r) => setTimeout(r, seconds * 1000));

import { appendFileSync, mkdirSync, writeFileSync } from "node:fs";
mkdirSync("artifacts", { recursive: true });
writeFileSync(outFile, lines.join("\n") + "\n", "utf8");
console.error(`\n采集完成：${lines.length} 帧行 → ${outFile}`);
ws.close);
process.exit(0);
