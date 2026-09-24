package com.zcoderemote

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 终态取证文件日志）：关键事件落盘 filesDir/logs/frame-events.log——logcat 环形缓冲
 * 会被日常使用冲掉（2026-09-13 跳电取证缺 terminal 码的教训），文件不受影响。
 * 时间格式与 logcat 对齐（MM-dd HH:mm:ss.SSS）便于核对；512KB 轮转保上一份；
 * 全程静默失败——日志系统绝不影响主流程。
 */
object FileLog {

    private const val MAX_BYTES = 512L * 1024
    private val lock = Any()
    private var dir: File? = null
    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    fun init(context: Context) {
        dir = File(context.filesDir, "logs").apply { mkdirs() }
    }

    fun log(tag: String, msg: String) {
        val d = dir ?: return
        try {
            synchronized(lock) {
                val f = File(d, "frame-events.log")
                if (f.exists() && f.length() > MAX_BYTES) {
                    val prev = File(d, "frame-events.log.1")
                    prev.delete()
                    f.renameTo(prev)
                }
                f.appendText("${fmt.format(Date())} $tag $msg\n")
            }
        } catch (_: Exception) {
        }
    }
}
