package com.zcoderemote

/**
 * 官方 Web 远程控制页面的 DOM 探测特征（来源：桌面端 app.asar 的 i18n 文案与前端代码，2026-09-02 逆向）。
 *
 * 输出组合串：`<fail>|<run>|<sf>`
 * - fail：B=终态（重载无意义，需重新扫码）；A=瞬态（自动重载）；空=连接健康。
 *   两条 "no longer valid" 特征必须整串匹配：B 类 "...Start it again" 先于 A 类 "...Reload" 判定。
 * - run：turn 是否运行中。发送按钮在 turn 进行中会替换为「停止生成」按钮（title 挂载，
 *   中英文 i18n 均为精确整串）。querySelector 不依赖渲染管线，后台探测同样可靠。
 * - sf：页面出现「发送失败」类文案（chat.error.sendFailed / sendMessage.failed 的可见文本）。
 */
object FailureFeatures {

    private val CATEGORY_B = listOf(
        "relay 已踢出这次配对", "kicked this pairing",
        "这次 Web 远程控制会话已经结束", "session has already ended",
        "当前 Web 远程控制链接已经失效", "link is no longer valid. Start it again",
        "桌面端共享的窗口已经关闭", "shared desktop window has been closed",
    )

    private val CATEGORY_A = listOf(
        "手机端连接恢复超时", "did not recover in time",
        "桌面端响应超时", "did not respond in time",
        "桌面端已经断开连接", "desktop side disconnected",
        "当前手机连接已失效", "no longer valid. Reload",
        "外部 relay 连接不可用", "relay connection is unavailable",
        "这个链接已经被其他页面占用", "already being used by another page",
        "无法启动移动端远程控制", "Could not start mobile remote control",
    )

    private val SEND_FAIL = listOf("发送失败", "Failed to send")

    private const val RUNNING_SELECTOR =
        "button[title=\"停止生成\"],button[title=\"Stop\"],button[aria-label=\"停止生成\"],button[aria-label=\"Stop\"]"

    /** 注入页面执行的探测脚本：返回 "A|1|0" 形式的组合串。 */
    fun probeScript(): String {
        val b = jsArray(CATEGORY_B)
        val a = jsArray(CATEGORY_A)
        val sf = jsArray(SEND_FAIL)
        return """(function(){try{
var t=(document.body&&document.body.innerText)||'';
var B=$b;
var A=$a;
var S=$sf;
var f='';
for(var i=0;i<B.length;i++){if(t.indexOf(B[i])>=0){f='B';break}}
if(!f){for(var j=0;j<A.length;j++){if(t.indexOf(A[j])>=0){f='A';break}}}
var run=!!document.querySelector("$RUNNING_SELECTOR");
var sf=0;
for(var k=0;k<S.length;k++){if(t.indexOf(S[k])>=0){sf=1;break}}
return f+'|'+(run?1:0)+'|'+sf;
}catch(e){return '||0'}})()"""
    }

    private fun jsArray(list: List<String>): String =
        list.joinToString(",", "[", "]") { "\"$it\"" }
}
