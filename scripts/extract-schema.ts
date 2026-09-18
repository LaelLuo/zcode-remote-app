// 错误原因字段定位：从前端 bundle 里按括号匹配提取 zod schema 定义段。
// 用法：bun scripts/extract-schema.ts <锚点子串> [锚点子串2 ...]
// 原理：找到锚点位置，向前找最近的 "={" 或 "=Ka({" 起点，向后做括号配平到段尾，打印整段。
// 输入文件需自行准备：remote-bundle.js 从 ZCode 安装目录 app.asar 解包获取；帧样本用 frame-recorder.ts 采集（写 artifacts/，已被 gitignore）
import { readFileSync } from "node:fs";

const bundle = readFileSync("artifacts/remote-bundle.js", "utf8");

for (const anchor of process.argv.slice(2)) {
  let idx = 0;
  let found = 0;
  while (found < 3) {
    idx = bundle.indexOf(anchor, idx);
    if (idx < 0) break;
    // 向前找 schema 赋值起点：最近出现的 "=Ka({" 或 "={"（限 4000 字符内）
    let start = -1;
    for (let back = idx; back > Math.max(0, idx - 4000); back--) {
      if (bundle.startsWith("=Ka({", back)) { start = back + 1; break; }
      if (bundle.startsWith("={", back) && bundle[back + 2] !== "{") { start = back + 1; break; }
    }
    if (start < 0) { idx += anchor.length; continue; }
    // 向后括号配平（从 Ka({ / { 的 { 开始）
    let depth = 0;
    let end = -1;
    for (let i = start; i < bundle.length && i < start + 20000; i++) {
      const c = bundle[i];
      if (c === "{") depth++;
      else if (c === "}") {
        depth--;
        if (depth === 0) { end = i + 1; break; }
      }
    }
    if (end < 0) { idx += anchor.length; continue; }
    const seg = bundle.slice(start, end);
    // 只要像 schema 的段（含冒号字段定义且含 optional/shape 字样）
    if (/optional\(\)|\.shape/.test(seg) && /:/.test(seg)) {
      console.log(`\n===== 锚点「${anchor}」命中@${idx} 段长${seg.length} =====`);
      console.log(seg.replace(/,/g, ",\n"));
      found++;
    }
    idx = end;
  }
  if (found === 0) console.log(`锚点「${anchor}」无 schema 命中`);
}
