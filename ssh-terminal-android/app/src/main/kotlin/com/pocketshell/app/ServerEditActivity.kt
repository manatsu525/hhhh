package com.pocketshell.app

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView

/** 新增 / 编辑一台服务器 */
class ServerEditActivity : Activity() {
    private var existing: Server? = null
    private lateinit var name: EditText
    private lateinit var host: EditText
    private lateinit var port: EditText
    private lateinit var user: EditText
    private lateinit var password: EditText
    private lateinit var key: EditText
    private lateinit var passphrase: EditText
    private lateinit var rbKey: RadioButton
    private lateinit var pwBox: LinearLayout
    private lateinit var keyBox: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        existing = Store.servers.find { it.id == intent.getStringExtra("id") }
        title = if (existing == null) "添加服务器" else "编辑服务器"

        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(24))
        }
        fun label(parent: LinearLayout, t: String) = parent.addView(TextView(this).apply {
            text = t
            setTextColor(DIM)
            textSize = 13f
            setPadding(0, dp(12), 0, 0)
        })
        fun field(parent: LinearLayout, t: String, hint: String, type: Int): EditText {
            label(parent, t)
            val e = EditText(this).apply {
                this.hint = hint
                inputType = type
            }
            parent.addView(e)
            return e
        }
        val plain = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        val secret = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD

        name = field(form, "名称", "例如：东京 VPS（不填则用主机地址）", plain)
        host = field(form, "主机", "IP 或域名", plain or InputType.TYPE_TEXT_VARIATION_URI)
        port = field(form, "端口", "22", InputType.TYPE_CLASS_NUMBER)
        user = field(form, "用户名", "root", plain)

        label(form, "登录方式")
        val rg = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        val rbPw = RadioButton(this).apply { text = "密码"; id = View.generateViewId() }
        rbKey = RadioButton(this).apply { text = "密钥"; id = View.generateViewId() }
        rg.addView(rbPw)
        rg.addView(rbKey)
        form.addView(rg)

        pwBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        password = field(pwBox, "密码", "留空则每次连接时询问", secret)
        form.addView(pwBox)

        keyBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        label(keyBox, "私钥（OpenSSH / PEM 格式，支持 RSA、ECDSA、Ed25519）")
        key = EditText(this).apply {
            hint = "-----BEGIN OPENSSH PRIVATE KEY-----\n…粘贴私钥内容，或用下面的按钮导入"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            typeface = Typeface.MONOSPACE
            textSize = 11f
            minLines = 3
            maxLines = 6
        }
        keyBox.addView(key)
        keyBox.addView(Button(this).apply {
            text = "从文件导入私钥"
            setOnClickListener {
                // 系统文件选择器：只读取你选中的那一个文件，不需要任何存储权限
                startActivityForResult(
                    Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), 1
                )
            }
        })
        passphrase = field(keyBox, "私钥口令", "私钥没有加密就留空", secret)
        form.addView(keyBox)

        form.addView(Button(this).apply {
            text = "保存"
            setOnClickListener { save() }
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(20) })

        rg.setOnCheckedChangeListener { _, _ -> toggle() }
        existing?.let {
            name.setText(it.name); host.setText(it.host); port.setText(it.port.toString())
            user.setText(it.user); password.setText(it.password); key.setText(it.privateKey)
            passphrase.setText(it.passphrase)
        }
        rg.check(if (existing?.useKey == true) rbKey.id else rbPw.id)
        toggle()
        setContentView(ScrollView(this).apply { addView(form) })
    }

    private fun toggle() {
        pwBox.visibility = if (rbKey.isChecked) View.GONE else View.VISIBLE
        keyBox.visibility = if (rbKey.isChecked) View.VISIBLE else View.GONE
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        val uri = data?.data
        if (requestCode != 1 || resultCode != RESULT_OK || uri == null) return
        try {
            val bytes = contentResolver.openInputStream(uri)!!.use { it.readBytes() }
            if (bytes.size > 65536) throw Exception("文件太大，不像是私钥")
            val text = String(bytes, Charsets.UTF_8)
            if (!text.contains("PRIVATE KEY")) throw Exception("不是 OpenSSH/PEM 私钥文件（PuTTY 的 .ppk 请先转成 OpenSSH 格式）")
            key.setText(text.trim())
        } catch (e: Exception) {
            toast("导入失败：${e.message}")
        }
    }

    private fun save() {
        val h = host.text.toString().trim()
        val u = user.text.toString().trim().ifEmpty { "root" }
        val p = port.text.toString().toIntOrNull() ?: 22
        if (h.isEmpty()) {
            host.error = "请填写主机"
            return
        }
        if (p !in 1..65535) {
            port.error = "端口无效"
            return
        }
        if (rbKey.isChecked && key.text.isBlank()) {
            key.error = "请粘贴或导入私钥"
            return
        }
        val s = existing ?: Server().also { Store.servers.add(it) }
        s.name = name.text.toString().trim().ifEmpty { h }
        s.host = h
        s.port = p
        s.user = u
        s.useKey = rbKey.isChecked
        s.password = password.text.toString()
        s.privateKey = key.text.toString().trim()
        s.passphrase = passphrase.text.toString()
        Store.save()
        finish()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, 1, 0, "保存").setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == 1) save()
        return true
    }
}
