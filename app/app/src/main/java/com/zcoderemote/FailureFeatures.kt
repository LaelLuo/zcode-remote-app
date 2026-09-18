package com.zcoderemote

/**
 * 官方 Web 远程控制页面的 DOM 探测特征（来源：桌面端 app.asar 的 i18n 文案与前端代码，2026-09-02 逆向）。
 *
 * 输出组合串：`<fail>|<run>|<sf>|<title>`
 * - fail：B=终态（重载无意义，需重新扫码）；A=瞬态（自动重载）；空=连接健康。
 *   两条 "no longer valid" 特征必须整串匹配：B 类 "...Start it again" 先于 A 类 "...Reload" 判定。
 * - run：turn 是否运行中。信号（2026-09-02 浏览器实测）：当前 turn 的计时按钮文本以「工作中」开头
 *   （历史消息恒为「已工作」，不可用作完成判定）；或存在文本恰为「停止生成」/"Stop" 的按钮
 *   （textContent 精确匹配，天然排除仅 title 挂载的图标按钮）。
 * - sf：页面出现「发送失败，请稍后重试」完整句（短串会被会话消息内容污染）。
 * - title：h1 会话名（encodeURIComponent，灵动岛显示用）。
 */
object FailureFeatures {

    private val CATEGORY_B = listOf(
        "relay 已踢出这次配对", "kicked this pairing",
        "这次 Web 远程控制会话已经结束", "session has already ended",
        "当前 Web 远程控制链接已经失效", "link is no longer valid. Start it again",
        "桌面端共享的窗口已经关闭", "shared desktop window has been closed",
        // 桌面端重置链接后的校验失败页（真机截图实证）。用长句而非
        // 短标题「手机连接已失效」：页面正文含整个聊天记录，短串会被会话消息引用误报
        "did not match this mobile connection",
        "二维码参数或鉴权信息已经失效",
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

    // 完整句匹配：短串「发送失败」会被会话消息内容污染（消息历史含特征字符串）
    private val SEND_FAIL = listOf("发送失败，请稍后重试", "Failed to send. Try again later.")

    /** 注入页面执行的探测脚本：返回 "A|1|0|%E4%BC%9A%E8%AF%9D" 形式的组合串。 */
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
var bs=document.querySelectorAll('button');
var run=false;
for(var m=0;m<bs.length;m++){
var x=(bs[m].textContent||'').trim();
if(x.indexOf('工作中')===0||x==='停止生成'||x==='Stop'){run=true;break}
}
var sf=0;
for(var k=0;k<S.length;k++){if(t.indexOf(S[k])>=0){sf=1;break}}
var h1=document.querySelector('h1');
var ti=h1?encodeURIComponent(h1.textContent.trim().slice(0,24)):'';
return f+'|'+(run?1:0)+'|'+sf+'|'+ti;
}catch(e){return '||0|'}})()"""
    }

    private fun jsArray(list: List<String>): String =
        list.joinToString(",", "[", "]") { "\"$it\"" }
}
