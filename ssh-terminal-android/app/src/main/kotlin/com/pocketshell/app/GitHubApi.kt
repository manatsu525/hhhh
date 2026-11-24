package com.pocketshell.app

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class Repo(val fullName: String, val defaultBranch: String, val private: Boolean, val description: String)
class Entry(val name: String, val path: String, val isDir: Boolean, val size: Long)

/** 用个人访问令牌（PAT）调用 GitHub REST API。只发 GET 请求，只读。 */
object GitHubApi {
    private fun get(token: String, pathAndQuery: String, raw: Boolean = false): String {
        val c = URL("https://api.github.com$pathAndQuery").openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 15000
            c.readTimeout = 30000
            c.setRequestProperty("Authorization", "Bearer $token")
            c.setRequestProperty("Accept", if (raw) "application/vnd.github.raw+json" else "application/vnd.github+json")
            c.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            c.setRequestProperty("User-Agent", "PocketShell")
            val code = c.responseCode
            if (code !in 200..299) {
                val body = try { c.errorStream?.bufferedReader()?.readText() } catch (e: Exception) { null }
                val msg = try { JSONObject(body ?: "").optString("message") } catch (e: Exception) { "" }
                throw IOException(
                    when (code) {
                        401 -> "令牌无效或已过期"
                        403 -> "没有权限或请求过于频繁（$msg）"
                        404 -> "找不到，或令牌没有访问该仓库的权限"
                        else -> "GitHub 返回 $code $msg"
                    }
                )
            }
            return c.inputStream.bufferedReader().readText()
        } finally {
            c.disconnect()
        }
    }

    private fun encPath(p: String) =
        p.split('/').filter { it.isNotEmpty() }.joinToString("/") { URLEncoder.encode(it, "UTF-8").replace("+", "%20") }

    /** 校验令牌，返回登录名 */
    fun login(token: String): String = JSONObject(get(token, "/user")).getString("login")

    /** 令牌能访问的仓库，按最近推送排序 */
    fun repos(token: String): List<Repo> {
        val out = ArrayList<Repo>()
        for (page in 1..5) {
            val a = JSONArray(get(token, "/user/repos?per_page=100&sort=pushed&page=$page"))
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                out.add(
                    Repo(
                        o.getString("full_name"), o.optString("default_branch", "main"),
                        o.optBoolean("private"), if (o.isNull("description")) "" else o.optString("description")
                    )
                )
            }
            if (a.length() < 100) break
        }
        return out
    }

    fun list(token: String, repo: String, path: String, ref: String): List<Entry> {
        val a = JSONArray(get(token, "/repos/$repo/contents/${encPath(path)}?ref=${URLEncoder.encode(ref, "UTF-8")}"))
        val out = ArrayList<Entry>()
        for (i in 0 until a.length()) {
            val o = a.getJSONObject(i)
            out.add(Entry(o.getString("name"), o.getString("path"), o.optString("type") == "dir", o.optLong("size")))
        }
        return out.sortedWith(compareBy({ !it.isDir }, { it.name.lowercase() }))
    }

    fun content(token: String, repo: String, path: String, ref: String): String {
        val q = if (ref.isEmpty()) "" else "?ref=${URLEncoder.encode(ref, "UTF-8")}"
        return get(token, "/repos/$repo/contents/${encPath(path)}$q", raw = true)
    }
}
