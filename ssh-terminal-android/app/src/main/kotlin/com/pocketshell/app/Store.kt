package com.pocketshell.app

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class Server(
    var id: String = UUID.randomUUID().toString(),
    var name: String = "",
    var host: String = "",
    var port: Int = 22,
    var user: String = "root",
    var useKey: Boolean = false,
    var password: String = "",
    var privateKey: String = "",
    var passphrase: String = ""
) {
    fun toJson() = JSONObject().apply {
        put("id", id); put("name", name); put("host", host); put("port", port); put("user", user)
        put("useKey", useKey); put("password", password); put("privateKey", privateKey)
        put("passphrase", passphrase)
    }

    companion object {
        fun from(o: JSONObject) = Server(
            o.getString("id"), o.optString("name"), o.optString("host"), o.optInt("port", 22),
            o.optString("user"), o.optBoolean("useKey"), o.optString("password"),
            o.optString("privateKey"), o.optString("passphrase")
        )
    }
}

data class Snippet(
    var id: String = UUID.randomUUID().toString(),
    var name: String = "",
    var command: String = "",
    var autoEnter: Boolean = true
) {
    fun toJson() = JSONObject().apply {
        put("id", id); put("name", name); put("command", command); put("autoEnter", autoEnter)
    }

    companion object {
        fun from(o: JSONObject) = Snippet(
            o.getString("id"), o.optString("name"), o.optString("command"), o.optBoolean("autoEnter", true)
        )
    }
}

/** 收藏的 GitHub 脚本：仓库全名 + 路径 + 分支 */
data class FavScript(val repo: String, val path: String, val ref: String) {
    val name: String get() = path.substringAfterLast('/')
    fun toJson() = JSONObject().apply { put("repo", repo); put("path", path); put("ref", ref) }

    companion object {
        fun from(o: JSONObject) = FavScript(o.getString("repo"), o.getString("path"), o.optString("ref"))
    }
}

/**
 * 全部持久化数据（服务器配置、密码/私钥、快捷指令、GitHub PAT、收藏）都放在
 * 应用私有目录下的一个文件里，并用 Android Keystore 里的 AES-GCM 密钥整体加密。
 * 私有目录和 Keystore 密钥都会在卸载时被系统删除，所以卸载后不留任何东西。
 */
object Store {
    private const val ALIAS = "pocketshell_store_key"

    lateinit var appContext: Context
        private set
    private lateinit var file: File
    private var loaded = false

    val servers = ArrayList<Server>()
    val snippets = ArrayList<Snippet>()
    val favorites = ArrayList<FavScript>()
    var githubToken = ""
    var githubLogin = ""
    var fontSize = 13
    var cjkInput = false

    @Synchronized
    fun init(ctx: Context) {
        if (loaded) return
        appContext = ctx.applicationContext
        file = File(appContext.filesDir, "store.bin")
        loaded = true
        try {
            if (file.exists()) parse(JSONObject(String(decrypt(file.readBytes()), Charsets.UTF_8)))
        } catch (e: Exception) {
            // 文件损坏或密钥丢失：从空配置开始
        }
    }

    private fun parse(o: JSONObject) {
        o.optJSONArray("servers")?.let { a -> for (i in 0 until a.length()) servers.add(Server.from(a.getJSONObject(i))) }
        o.optJSONArray("snippets")?.let { a -> for (i in 0 until a.length()) snippets.add(Snippet.from(a.getJSONObject(i))) }
        o.optJSONArray("favorites")?.let { a -> for (i in 0 until a.length()) favorites.add(FavScript.from(a.getJSONObject(i))) }
        githubToken = o.optString("githubToken")
        githubLogin = o.optString("githubLogin")
        fontSize = o.optInt("fontSize", 13)
        cjkInput = o.optBoolean("cjkInput")
    }

    @Synchronized
    fun save() {
        val o = JSONObject()
        o.put("servers", JSONArray().also { a -> servers.forEach { a.put(it.toJson()) } })
        o.put("snippets", JSONArray().also { a -> snippets.forEach { a.put(it.toJson()) } })
        o.put("favorites", JSONArray().also { a -> favorites.forEach { a.put(it.toJson()) } })
        o.put("githubToken", githubToken)
        o.put("githubLogin", githubLogin)
        o.put("fontSize", fontSize)
        o.put("cjkInput", cjkInput)
        try {
            val tmp = File(file.parentFile, "store.bin.tmp")
            tmp.writeBytes(encrypt(o.toString().toByteArray(Charsets.UTF_8)))
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            }
        } catch (e: Exception) {
            appContext.let { c -> ui { c.toast("保存失败: ${e.message}") } }
        }
    }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        kg.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return kg.generateKey()
    }

    private fun encrypt(plain: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        val iv = c.iv
        return byteArrayOf(iv.size.toByte()) + iv + c.doFinal(plain)
    }

    private fun decrypt(data: ByteArray): ByteArray {
        val n = data[0].toInt()
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, data, 1, n))
        return c.doFinal(data, 1 + n, data.size - 1 - n)
    }
}
