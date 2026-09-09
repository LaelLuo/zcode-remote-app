// 手机全端口 TCP connect 扫描：adb 无线调试端口随机漂移且 mdns 广告失效时定位 adbd 端口。
// 用法：bun scripts/portscan.ts <ip> [起始端口] [结束端口]
// 锚点自检：Termux sshd 的 8022 必须出现在结果里，否则扫描器本身有问题（假阴性警戒）。
const host = process.argv[2];
if (!host) {
  console.error("用法: bun scripts/portscan.ts <ip> [startPort] [endPort]");
  process.exit(1);
}
const start = Number(process.argv[3] ?? 1);
const end = Number(process.argv[4] ?? 65535);
const CONCURRENCY = 800;
const TIMEOUT_MS = 400;

function tryPort(port: number): Promise<number | null> {
  return new Promise((resolve) => {
    const net = require("node:net") as typeof import("node:net");
    const sock = net.connect({ host, port });
    const done = (v: number | null) => {
      sock.removeAllListeners();
      sock.destroy();
      resolve(v);
    };
    sock.setTimeout(TIMEOUT_MS, () => done(null));
    sock.on("connect", () => done(port));
    sock.on("error", () => done(null));
  });
}

const open: number[] = [];
let cursor = start;
const workers = Array.from({ length: CONCURRENCY }, async () => {
  while (cursor <= end) {
    const port = cursor++;
    if (await tryPort(port)) open.push(port);
  }
});
await Promise.all(workers);
open.sort((a, b) => a - b);
if (!open.includes(8022)) {
  console.error("警戒：8022 (Termux sshd) 未扫到——扫描器假阴性，结果不可信");
}
console.log(`开放端口 ${open.length} 个：`);
for (const p of open) console.log(`  ${p}`);
