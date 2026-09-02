package com.zcoderemote

import android.content.Context

/**
 * 远程控制链接的存取与校验。
 * 链接形态：https://zcode.chatglm.site/remote/<版本>?sid=...&hash=...&t=...（t 无校验，链接长期可复用）。
 */
object UrlStore {
    private const val PREFS = "zcode_remote"
    private const val KEY_URL = "remote_url"

    fun load(ctx: Context): String? {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getString(KEY_URL, null)?.takeIf { it.isNotBlank() }
    }

    fun save(ctx: Context, url: String) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_URL, url).apply()
    }

    fun clear(ctx: Context) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().remove(KEY_URL).apply()
    }

    /** 校验远程控制链接；合法返回 null，否则返回给用户看的原因。 */
    fun validate(url: String): String? {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return "链接为空"
        val u = try { java.net.URI(trimmed) } catch (e: Exception) { return "这不是一个有效的链接" }
        if (u.scheme != "https") return "链接必须是 https 开头"
        val host = u.host ?: return "链接缺少域名"
        if (host != "zcode.chatglm.site" && host != "zcode.z.ai") {
            return "链接域名（$host）不是 ZCode 远程控制"
        }
        val path = u.path ?: ""
        if (!path.startsWith("/remote/")) return "链接路径不是 ZCode 远程控制页面"
        val query = u.rawQuery ?: return "链接缺少配对参数（sid/hash）"
        val params = query.split('&').mapNotNull {
            val i = it.indexOf('='); if (i <= 0) null else it.substring(0, i) to it.substring(i + 1)
        }.toMap()
        if (params["sid"].isNullOrBlank()) return "链接缺少 sid 配对参数"
        if (params["hash"].isNullOrBlank()) return "链接缺少 hash 配对参数"
        return null
    }
}
