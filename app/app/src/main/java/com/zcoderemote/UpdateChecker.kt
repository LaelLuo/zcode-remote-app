package com.zcoderemote

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * 启动更新检查：查询 GitHub 最新 Release，比当前版本新则提示。
 * 数据源=公开 API（releases/latest），只读；失败静默（网络问题不打扰启动流程）。
 */
object UpdateChecker {

    private const val LATEST_API = "https://api.github.com/repos/LaelLuo/zcode-remote-app/releases/latest"
    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 10_000

    data class Update(val version: String, val url: String, val notes: String?)

    /** 查询最新版本；无更新、网络失败或解析失败均返回 null。阻塞调用，需在后台线程跑。 */
    fun check(currentVersion: String): Update? {
        val conn = try {
            (URL(LATEST_API).openConnection() as HttpURLConnection).apply {
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                setRequestProperty("Accept", "application/vnd.github+json")
            }
        } catch (e: Exception) {
            android.util.Log.d("UpdateCheck", "open failed: ${e.message}")
            return null
        }
        return try {
            val code = conn.responseCode
            android.util.Log.d("UpdateCheck", "http $code")
            if (code != 200) return null
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(body)
            val tag = json.optString("tag_name", "")
            val url = json.optString("html_url", "")
            if (tag.isEmpty() || url.isEmpty()) return null
            val remote = tag.removePrefix("v")
            android.util.Log.d("UpdateCheck", "remote=$remote current=$currentVersion newer=${isNewer(remote, currentVersion)}")
            if (!isNewer(remote, currentVersion)) return null
            Update(remote, url, json.optString("body", "").takeIf { it.isNotBlank() })
        } catch (e: Exception) {
            android.util.Log.d("UpdateCheck", "check failed: ${e.message}")
            null
        } finally {
            conn.disconnect()
        }
    }

    /** 语义化版本比较：按点分段数字逐段比，缺段补零（1.6 与 1.6.0 等价）。 */
    fun isNewer(remote: String, current: String): Boolean {
        val r = remote.split('.').map { it.toIntOrNull() ?: 0 }
        val c = current.split('.').map { it.toIntOrNull() ?: 0 }
        val n = maxOf(r.size, c.size)
        for (i in 0 until n) {
            val rv = r.getOrElse(i) { 0 }
            val cv = c.getOrElse(i) { 0 }
            if (rv != cv) return rv > cv
        }
        return false
    }
}
