// 一次性取证：任务列表项 DOM 特征 + 会话路由格式（CDP，端口自适应）
const base = process.argv[2] ? `http://127.0.0.1:${process.argv[2]}` : "http://127.0.0.1:9223";
const list = await (await fetch(`${base}/json/list`)).json();
const page = list.find((t: { type: string }) => t.type === "page");
const ws = new WebSocket(page.webSocketDebuggerUrl);
let seq = 0;
const pending = new Map<number, (v: unknown) => void>();
ws.onmessage = (ev: MessageEvent) => {
  const m = JSON.parse(String(ev.data)) as { id?: number; result?: unknown };
  if (m.id != null && pending.has(m.id)) { pending.get(m.id)!(m.result); pending.delete(m.id); }
};
function call(method: string, params: Record<string, unknown> = {}): Promise<{ result?: { value?: unknown } }> {
  return new Promise((resolve) => { const id = ++seq; pending.set(id, resolve as (v: unknown) => void); ws.send(JSON.stringify({ id, method, params })); });
}
await new Promise<void>((resolve) => { ws.onopen = () => resolve(); });

const probe = [
  "(() => {",
  " var out = { url: location.href,",
  "  h1: (document.querySelector('h1')?document.querySelector('h1').textContent:'').trim().slice(0,40) };",
  // 会话视图时 h1=会话名；列表视图找任务行（含状态词/计时文本）
  " var items = [];",
  " var all = document.querySelectorAll('div,li,a,button');",
  " for (var i=0;i<all.length;i++){ var e=all[i]; var t=(e.innerText||'').trim();",
  "  if(t.length>5&&t.length<150&&(t.indexOf('工作中')>=0||t.indexOf('已工作')>=0||t.indexOf('等输入')>=0||t.indexOf('已完成')>=0||/\\d+\\s*(秒|分|小时)/.test(t))){items.push(e);} }",
  " out.matchCount = items.length;",
  " if (items.length) {",
  "  var el = items[0];",
  "  out.firstText = el.innerText.slice(0,80);",
  "  var n = el, attrs = [];",
  "  for (var j=0;j<6&&n&&n!==document.body;j++){",
  "   var as=[]; var na=n.attributes;",
  "   for(var k=0;k<na.length;k++){as.push(na[k].name+'='+String(na[k].value).slice(0,36));}",
  "   attrs.push('<'+n.tagName.toLowerCase()+' '+as.join(' ')+'>');",
  "   n=n.parentElement;",
  "  }",
  "  out.attrs = attrs;",
  " }",
  " return JSON.stringify(out,null,1);",
  "})()",
].join("\n");

const r = await call("Runtime.evaluate", { expression: probe, returnByValue: true });
console.log(r.result?.value);
ws.close();
process.exit(0);
