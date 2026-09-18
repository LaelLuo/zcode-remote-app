// 辅助脚本：把 frame-recorder 采集样本里的全部 dataBase64 解码，统计 topic/op/displayStatus。
// 用法：bun scripts/decode-frames.ts artifacts/v8-frame-samples.txt > artifacts/v8-frame-decoded.txt
// 输入文件需自行准备：remote-bundle.js 从 ZCode 安装目录 app.asar 解包获取；帧样本用 frame-recorder.ts 采集（写 artifacts/，已被 gitignore）
import { readFileSync } from "node:fs";

const src = readFileSync(process.argv[2] || "artifacts/v8-frame-samples.txt", "utf8");
const topics = new Map<string, number>();
const ops = new Map<string, number>();
const statuses = new Map<string, number>();
const out: string[] = [];

for (const line of src.split("\n")) {
  const m = line.match(/"dataBase64":"([^"]+)"/);
  if (!m) continue;
  let decoded: string;
  try {
    decoded = Buffer.from(m[1], "base64").toString("utf8");
  } catch {
    continue;
  }
  // dataBase64 = 二进制小端头 + JSON 正文：跳到第一个 { 再解析
  const brace = decoded.indexOf("{");
  if (brace < 0) continue;
  decoded = decoded.slice(brace);
  let j: {
    topic?: string; op?: string;
    payload?: { kind?: string; deltas?: { op?: string; task?: { displayStatus?: string } }[] };
  };
  try {
    j = JSON.parse(decoded);
  } catch {
    out.push(`[解析失败] ${decoded.slice(0, 120)}`);
    continue;
  }
  topics.set(j.topic ?? "?", (topics.get(j.topic ?? "?") ?? 0) + 1);
  for (const d of j.payload?.deltas ?? []) {
    if (d.op) ops.set(d.op, (ops.get(d.op) ?? 0) + 1);
    if (d.task?.displayStatus) {
      statuses.set(d.task.displayStatus, (statuses.get(d.task.displayStatus) ?? 0) + 1);
    }
  }
  out.push(line.slice(0, 20) + " DECODED " + decoded.slice(0, 1500));
}

console.log("==== topic 分布 ====");
for (const [k, v] of [...topics].sort((a, b) => b[1] - a[1])) console.log(`${v}\t${k}`);
console.log("==== delta op 分布 ====");
for (const [k, v] of [...ops].sort((a, b) => b[1] - a[1])) console.log(`${v}\t${k}`);
console.log("==== task.displayStatus 分布 ====");
for (const [k, v] of [...statuses].sort((a, b) => b[1] - a[1])) console.log(`${v}\t${k}`);
console.log("==== 解码明细（每帧前1500字） ====");
console.log(out.join("\n"));
