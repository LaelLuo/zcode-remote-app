package com.zcoderemote

/**
 * 官方 Web 远程控制页面的失败文案特征（来源：桌面端 app.asar 的 i18n 文案，2026-09-02 逆向）。
 *
 * B 类 = 终态：重载无意义（配对被踢/会话结束/链接失效/窗口关闭），直接停在原生错误页等用户重新扫码。
 * A 类 = 瞬态：重载可能恢复（连接超时/失效/中继不可用/会话冲突/启动失败），自动整页重载。
 *
 * 注意两条 "no longer valid" 特征必须整串匹配：B 类 "...Start it again" 先于 A 类 "...Reload" 判定。
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

    /** 注入页面执行的探测脚本：读 body 可见文本，先判 B 再判 A，返回 "A"/"B"/""。 */
    fun probeScript(): String {
        val b = CATEGORY_B.joinToString(",") { "\"$it\"" }
        val a = CATEGORY_A.joinToString(",") { "\"$it\"" }
        return """(function(){try{var t=(document.body&&document.body.innerText)||'';
var B=[$b];
var A=[$a];
for(var i=0;i<B.length;i++){if(t.indexOf(B[i])>=0)return 'B'}
for(var j=0;j<A.length;j++){if(t.indexOf(A[j])>=0)return 'A'}
return ''}catch(e){return ''}})()"""
    }
}
