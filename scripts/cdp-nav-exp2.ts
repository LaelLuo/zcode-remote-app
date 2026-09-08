// 实验2：fiber 直调业务名 handler（onSelectTask 等）能否打开会话
// 用法: bun scripts/cdp-nav-exp2.ts <端口> <任务标题>
const port = process.argv[2] ?? "9223";
const title = process.argv[3];
if (!title) { console.error("用法: bun cdp-nav-exp2.ts <端口> <任务标题>"); process.exit(1); }
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

console.log("h1 起点 =", await ev(`document.querySelector('h1')?document.querySelector('h1').textContent.trim():'(无)'`));

// 会话视图先回列表（history.back，SPA pushState 路由）
const back1 = await ev(`(async()=>{ var h=document.querySelector('h1'); if(h&&h.textContent.trim()!=='' && !document.evaluate("//*[normalize-space(text())='当前设备上的工作区和任务']",document,null,9,null).singleNodeValue){ history.back(); await new Promise(r=>setTimeout(r,1200)); } return document.querySelector('h1')?document.querySelector('h1').textContent.trim().slice(0,30):'(无)'; })()`);
console.log("回列表后 h1 =", back1);

const result = await ev(`(async () => {
  var title = ${J(title)};
  // 列表视图找到目标行；会话视图先看当前 h1 是否已是目标
  var h1 = document.querySelector('h1');
  if (h1 && h1.textContent.trim() === title) return 'ALREADY';
  var all = document.querySelectorAll('div,li,a,button');
  var best = null;
  for (var i=0;i<all.length;i++){ var e=all[i]; var t=(e.innerText||'').trim();
    if(t && (t===title||(t.indexOf(title)===0&&t.length<title.length+50))&&t.length<200){ if(!best||t.length<best.innerText.trim().length) best=e; } }
  if(!best) return 'NO_TARGET';
  // 该行及其祖先的 fiber props 里找业务 handler（onSelectTask 优先，其次 onSelect*/onOpen*/onClick）
  var node = best, found = [];
  outer:
  while(node && node !== document.body){
    var keys = Object.keys(node);
    for(var i2=0;i2<keys.length;i2++){
      if(keys[i2].indexOf('__reactFiber$')===0){
        var f = node[keys[i2]];
        for(var d=0; d<15 && f; d++){
          var p = f.memoizedProps;
          if(p){
            var pri = ['onSelectTask','onSelect','onOpenTask','onNavigateToChat','onClick','onPointerDown'];
            for(var pi=0;pi<pri.length;pi++){
              if(typeof p[pri[pi]]==='function'){
                found.push({el:node.tagName, name:pri[pi], depth:d});
                if(found.length>=1) break outer;
              }
            }
          }
          f = f.return;
        }
        break;
      }
    }
    node = node.parentElement;
  }
  if(!found.length) return 'NO_HANDLER';
  var h = found[0];
  // 直调：无参优先（闭包捕获 task 场景），异常再试 mock 事件
  var el = node; // 外层循环结束后 node 指向找到 handler 的元素——用闭包内变量重查
  // 重新精确定位（外层 node 变量已变）——简化：重新跑一遍爬取并直接调用
  var node2 = best, called = null;
  outer2:
  while(node2 && node2 !== document.body){
    var keys2 = Object.keys(node2);
    for(var i3=0;i3<keys2.length;i3++){
      if(keys2[i3].indexOf('__reactFiber$')===0){
        var f2 = node2[keys2[i3]];
        for(var d2=0; d2<15 && f2; d2++){
          var p2 = f2.memoizedProps;
          if(p2 && typeof p2[h.name]==='function'){
            var fn = p2[h.name], ctx = node2;
            // 参数优先级：props 里的任务对象（React 列表把 task 与回调同层传入）→ 无参 → mock 事件
            var arg = p2.task || p2.item || p2.taskData || p2.data || p2.model;
            try { fn(arg); called = h.name+':'+(arg?'arg':'noarg'); }
            catch(x){ try { fn.call(ctx,{type:'click',currentTarget:ctx,target:ctx,bubbles:true,preventDefault:function(){},stopPropagation:function(){}}); called = h.name+':mock'; } catch(x2){ called = h.name+':FAIL'; } }
            break outer2;
          }
          f2 = f2.return;
        }
        break;
      }
    }
    node2 = node2.parentElement;
  }
  await new Promise(r=>setTimeout(r,1000));
  var h1b = document.querySelector('h1');
  return JSON.stringify({called:called, h1:(h1b?h1b.textContent.trim():'').slice(0,40)});
})()`);
console.log("直调结果:", result);
ws.close();
process.exit(0);
