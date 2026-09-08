// 交互实验：对目标任务行逐一试触发方式，找出能让路由进会话页的手法
// 用法: bun scripts/cdp-nav-exp.ts <端口> <任务标题>
const port = process.argv[2] ?? "9223";
const title = process.argv[3];
if (!title) { console.error("用法: bun cdp-nav-exp.ts <端口> <任务标题>"); process.exit(1); }
const list = await (await fetch(`http://127.0.0.1:${port}/json/list`)).json();
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
const ev = async (expression: string) =>
  (await call("Runtime.evaluate", { expression, returnByValue: true, awaitPromise: true })).result?.value as unknown;

await new Promise<void>((r) => { ws.onopen = () => r(); });
const J = JSON.stringify;

// 目标行定位 + 祖先链结构
const info = await ev(`(async () => {
  var title = ${J(title)};
  var all = document.querySelectorAll('div,li,a,button,section');
  var cands = [];
  for (var i=0;i<all.length;i++){ var e=all[i]; var t=(e.innerText||'').trim();
    if(t && (t===title||(t.indexOf(title)===0&&t.length<title.length+50))&&t.length<200){cands.push(e);} }
  cands.sort(function(a,b){return a.innerText.trim().length-b.innerText.trim().length;});
  if(!cands.length) return 'NO_TARGET';
  var el=cands[0];
  var chain=[]; var n=el;
  for(var j=0;j<8&&n&&n!==document.body;j++){
    var h=null;
    var keys=Object.keys(n);
    for(var k=0;k<keys.length;k++){if(keys[k].indexOf('__reactFiber$')===0){
      var f=n[keys[k]];
      for(var d=0;d<8&&f;d++){var p=f.memoizedProps;
        if(p){var names=[];for(var pk in p){if(pk.indexOf('on')===0&&typeof p[pk]==='function')names.push(pk);}
        if(names.length){h=names.join(',');break;}}
        f=f.return;}
      break;}}
    chain.push(n.tagName.toLowerCase()+'['+(n.className||'').toString().slice(0,50)+'] handlers='+(h||'-'));
    n=n.parentElement;
  }
  return JSON.stringify({text:el.innerText.slice(0,60), chain:chain},null,1);
})()`);
console.log("目标链:\n", info);

// 在列表视图确认起点
console.log("h1 起点 =", await ev(`document.querySelector('h1').textContent.trim()`));

// 实验：真实坐标 tap（CDP Input.dispatchMouseEvent，isTrusted 真事件——cdp-click-text 同法）
const pos = await ev(`(async () => {
  var title = ${J(title)};
  var all = document.querySelectorAll('div,li,a,button');
  var cands = [];
  for (var i=0;i<all.length;i++){ var e=all[i]; var t=(e.innerText||'').trim();
    if(t && (t===title||(t.indexOf(title)===0&&t.length<title.length+50))&&t.length<200){cands.push(e);} }
  if(!cands.length) return null;
  cands.sort(function(a,b){return a.innerText.trim().length-b.innerText.trim().length;});
  cands[0].scrollIntoView({block:'center'});
  await new Promise(r=>setTimeout(r,400));
  var r=cands[0].getBoundingClientRect();
  return JSON.stringify({x:r.x+r.width/2,y:r.y+r.height/2,text:cands[0].innerText.slice(0,40)});
})()`);
console.log("目标坐标:", pos);
if (pos) {
  const p = JSON.parse(pos as string);
  await call("Input.dispatchMouseEvent", { type: "mousePressed", x: p.x, y: p.y, button: "left", clickCount: 1 });
  await call("Input.dispatchMouseEvent", { type: "mouseReleased", x: p.x, y: p.y, button: "left", clickCount: 1 });
  await new Promise(r => setTimeout(r, 1200));
  console.log("点击后 h1 =", await ev(`document.querySelector('h1')?document.querySelector('h1').textContent.trim():'(无h1)'`));
  console.log("点击后 url =", await ev(`location.href.slice(-60)`));
}
ws.close();
process.exit(0);
