// 真机验证辅助：CDP 协议级真实点击（Input.dispatchMouseEvent，isTrusted=true）。
// 用法：bun scripts/cdp-click-text.ts <元素文本开头> —— 找 innerText 以该文本开头的最小元素，真实点击其中心
const needle = process.argv[2];
if (!needle) {
 console.error("用法: bun scripts/cdp-click-text.ts <元素文本开头>");
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
 ws.onerror = ) => reject(new Error("ws 连接失败"));
 setTimeout() => reject(new Error("ws 连接超时")), 5000);
});

// 1) 找目标元素中心坐标（视口内可见者优先，否则滚到它）
const loc = await call("Runtime.evaluate", {
 expression: `() => {
 const els = [...document.querySelectorAll('div,li,a,button')].filter(e => e.innerText && e.innerText.startsWith(${JSON.stringify(needle)}) && e.innerText.length < 150);
 if (!els.length) return null;
 els.sort((a,b)=>a.innerText.length-b.innerText.length);
 const el = els[0];
 el.scrollIntoView({block:'center'});
 return new Promise(res => setTimeout() => {
 const r = el.getBoundingClientRect);
 res({x: r.x + r.width/2, y: r.y + r.height/2, text: el.innerText.slice(0,60)});
 }, 300));
 }))`,
 returnByValue: true,
 awaitPromise: true,
});
const pos = loc.result?.value as { x: number; y: number; text: string } | null;
if (!pos) {
 console.error("未找到目标元素");
 process.exit(1);
}
console.log(`目标「${pos.text}」中心=(${Math.round(pos.x)}, ${Math.round(pos.y)})`);

// 2) 真实鼠标按下+抬起
await call("Input.dispatchMouseEvent", { type: "mousePressed", x: pos.x, y: pos.y, button: "left", clickCount: 1 });
await call("Input.dispatchMouseEvent", { type: "mouseReleased", x: pos.x, y: pos.y, button: "left", clickCount: 1 });
console.log("已点击");

// 3) 800ms 后回报页面开头文本确认视图变化
await new Promise((r) => setTimeout(r, 800));
const after = await call("Runtime.evaluate", {
 expression: "document.body.innerText.slice(0, 200)",
 returnByValue: true,
});
console.log("点击后页面开头：", after.result?.value);
ws.close);
process.exit(0);
