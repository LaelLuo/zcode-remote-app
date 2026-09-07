#!/usr/bin/env bash
# 真机验证一键链：CDP 打开会话视图 → 等探测周期 → dumpsys 机制面 → 亮屏截图。
# 前提：adb 设备在线（mdns 直连形态）。
# 用法：bash scripts/v7-verify.sh
set -u
D="adb-DEVICE-REDACTED (2)._adb-tls-connect._tcp"

PID=$(adb -s "$D" shell pidof com.zcoderemote | tr -d '\r\n ')
if [ -z "$PID" ]; then echo "!! app 未在运行"; exit 1; fi
echo "== app pid=$PID"

adb -s "$D" forward --remove-all >/dev/null 2>&1
adb -s "$D" forward tcp:9222 localabstract:webview_devtools_remote_$PID

cd "$(dirname "$0")/.."
echo "== 当前通知文本："
adb -s "$D" shell "dumpsys notification --noredact 2>/dev/null | grep -A45 'pkg=com.zcoderemote' | grep -E 'android.text=|flags=ONGO'"

echo "== 页面内点开活跃会话（找含「工作中」或最近的会话条目）："
bun scripts/cdp.ts "() => {
 const els = [...document.querySelectorAll('a,button,[role=button],li,div')].filter(e => e.innerText && e.innerText.length < 200);
 const cand = els.filter(e => /工作中|playground|Playground|当前会话/.test(e.innerText));
 const target = cand.sort((a,b) => (a.innerText.length - b.innerText.length))[0] || els.find(e => /最近|会话/.test(e.innerText));
 if (!target) return 'no-target: ' + document.body.innerText.slice(0,300);
 target.click);
 return 'clicked: ' + target.innerText.slice(0,80);
}))"

echo "== 等 12s（探测周期 5s×2 + 稳态）："
sleep 12

echo "== 通知机制面（ 主锚点）："
adb -s "$D" shell "dumpsys notification --noredact 2>/dev/null | grep -A60 'pkg=com.zcoderemote' | grep -iE 'android.text=|android.requestPromoted|android.shortCriticalText=String|android.progressIndet|flags=ONGO|channel='"

echo "== 亮屏截图（锁屏上岛可见）："
adb -s "$D" shell "input keyevent KEYCODE_WAKEUP; sleep 1; screencap -p /sdcard/z7v7.png"
adb -s "$D" pull "//sdcard/z7v7.png" "$PWD/artifacts/v7-1-running-lockscreen.png" >/dev/null 2>&1
ls -la "$PWD/artifacts/" | grep v7- || echo "!! 截图拉取失败"
