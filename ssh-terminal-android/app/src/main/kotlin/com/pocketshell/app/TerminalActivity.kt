package com.pocketshell.app

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.TextView

class TerminalActivity : Activity(), TermSession.Listener {

    private lateinit var session: TermSession
    private lateinit var term: TerminalView
    private var ctrlBtn: TextView? = null
    private var shiftBtn: TextView? = null
    private var metaBtn: TextView? = null
    private var promptDialog: AlertDialog? = null
    private var shownPrompt: Prompt? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.getStringExtra("server")
        val s = SessionManager.get(id) ?: Store.servers.find { it.id == id }?.let { SessionManager.open(it) }
        if (s == null) {
            finish()
            return
        }
        session = s
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        term = TerminalView(this)
        term.onLongPress = { showMenu(term) }
        term.mods.onChange = { refreshMods() }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            fitsSystemWindows = true
        }
        root.addView(term, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(buildToolRow(), LinearLayout.LayoutParams(-1, dp(38)))
        root.addView(buildKeyRow(1), LinearLayout.LayoutParams(-1, dp(40)))
        root.addView(buildKeyRow(2), LinearLayout.LayoutParams(-1, dp(40)))
        setContentView(root)
        term.session = session
        term.requestFocus()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // singleTop：已在前台时换到另一台服务器的会话
        val id = intent.getStringExtra("server")
        val s = SessionManager.get(id) ?: Store.servers.find { it.id == id }?.let { SessionManager.open(it) } ?: return
        if (s !== session) {
            session.listener = null
            session = s
            term.session = s
            s.listener = this
        }
    }

    override fun onStart() {
        super.onStart()
        if (!::session.isInitialized) return
        session.listener = this
        term.onScreenUpdated()
        session.pendingPrompt?.let { onPrompt(it) }
    }

    override fun onStop() {
        super.onStop()
        if (::session.isInitialized && session.listener === this) session.listener = null
    }

    override fun onDestroy() {
        super.onDestroy()
        shownPrompt = null
        promptDialog?.dismiss()
        // 会话已经断开就顺手从列表里清掉；还连着的保留在后台
        if (::session.isInitialized && isFinishing && session.state == TermSession.State.CLOSED &&
            SessionManager.get(session.server.id) === session
        ) {
            SessionManager.close(session.server.id)
        }
    }

    // ---------- 会话回调 ----------

    override fun onScreenUpdated() = term.onScreenUpdated()

    override fun onStateChanged() {}

    override fun onPrompt(p: Prompt) {
        if (p.result.isDone || shownPrompt === p || isFinishing) return
        shownPrompt = p
        promptDialog?.dismiss()
        if (p.kind == Prompt.YES_NO) {
            val d = AlertDialog.Builder(this)
                .setTitle("主机密钥确认")
                .setMessage(p.message)
                .setPositiveButton("是") { _, _ -> p.result.complete("yes") }
                .setNegativeButton("否") { _, _ -> p.result.complete("no") }
                .setCancelable(false)
                .create()
            d.setOnDismissListener { if (shownPrompt === p) p.result.complete("no") }
            d.show()
            promptDialog = d
        } else {
            promptDialog = inputDialog("需要输入", p.message, p.kind == Prompt.SECRET, cancelable = false) { v ->
                if (v != null) p.result.complete(v)
                else if (shownPrompt === p) p.result.complete(null)
            }
        }
    }

    // ---------- 特殊按键 ----------

    private fun keyBackground(active: Int = 0) = GradientDrawable().apply {
        cornerRadius = dp(5).toFloat()
        setColor(
            when (active) {
                1 -> 0xFF1B6E78.toInt()
                2 -> ACCENT
                else -> 0xFF22272E.toInt()
            }
        )
    }

    private fun makeKey(label: String): TextView = TextView(this).apply {
        text = label
        gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
        textSize = 13f
        typeface = Typeface.DEFAULT_BOLD
        background = keyBackground()
        isClickable = true
    }

    /** 按住自动连发的按键 */
    private fun repeating(v: View, action: () -> Unit) {
        var down = false
        val rep = object : Runnable {
            override fun run() {
                if (!down) return
                action()
                v.postDelayed(this, 60)
            }
        }
        v.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    down = true
                    v.alpha = 0.6f
                    action()
                    v.postDelayed(rep, 400)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    down = false
                    v.alpha = 1f
                    v.removeCallbacks(rep)
                }
            }
            true
        }
    }

    private fun buildKeyRow(n: Int): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(2), dp(2), dp(2), dp(2))
        }
        fun add(v: View) {
            row.addView(v, LinearLayout.LayoutParams(0, -1, 1f).apply {
                leftMargin = dp(2); rightMargin = dp(2)
            })
        }
        fun key(label: String, code: Int) = add(makeKey(label).also { k -> repeating(k) { term.sendKeyCode(code) } })
        fun text(label: String) = add(makeKey(label).also { k -> repeating(k) { term.sendText(label) } })
        fun mod(label: String, get: () -> Int, set: (Int) -> Unit): TextView {
            val k = makeKey(label)
            // 点一下：只对下一个键生效；再点：锁定；再点：关闭
            k.setOnClickListener {
                set((get() + 1) % 3)
                refreshMods()
            }
            add(k)
            return k
        }
        if (n == 1) {
            key("ESC", KeyEvent.KEYCODE_ESCAPE)
            text("/")
            text("-")
            text("|")
            key("HOME", KeyEvent.KEYCODE_MOVE_HOME)
            key("↑", KeyEvent.KEYCODE_DPAD_UP)
            key("END", KeyEvent.KEYCODE_MOVE_END)
            key("PGUP", KeyEvent.KEYCODE_PAGE_UP)
        } else {
            key("TAB", KeyEvent.KEYCODE_TAB)
            ctrlBtn = mod("CTRL", { term.mods.ctrl }, { term.mods.ctrl = it })
            shiftBtn = mod("SHIFT", { term.mods.shift }, { term.mods.shift = it })
            metaBtn = mod("META", { term.mods.alt }, { term.mods.alt = it })
            key("←", KeyEvent.KEYCODE_DPAD_LEFT)
            key("↓", KeyEvent.KEYCODE_DPAD_DOWN)
            key("→", KeyEvent.KEYCODE_DPAD_RIGHT)
            key("PGDN", KeyEvent.KEYCODE_PAGE_DOWN)
        }
        return row
    }

    private fun refreshMods() {
        ctrlBtn?.background = keyBackground(term.mods.ctrl)
        shiftBtn?.background = keyBackground(term.mods.shift)
        metaBtn?.background = keyBackground(term.mods.alt)
    }

    /** 最上面一排可左右滑动：功能入口 + F1~F12 等 */
    private fun buildToolRow(): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(2), dp(2), dp(2), dp(2))
        }
        fun add(label: String, accent: Boolean = false): TextView {
            val k = makeKey(label)
            k.setPadding(dp(12), 0, dp(12), 0)
            k.minWidth = dp(44)
            if (accent) k.setTextColor(ACCENT)
            row.addView(k, LinearLayout.LayoutParams(-2, -1).apply { leftMargin = dp(2); rightMargin = dp(2) })
            return k
        }
        add("⌨", true).setOnClickListener { term.toggleKeyboard() }
        add("指令", true).setOnClickListener { showSnippets() }
        add("GitHub", true).setOnClickListener {
            startActivity(Intent(this, GitHubActivity::class.java).putExtra("server", session.server.id))
        }
        add("粘贴", true).setOnClickListener { paste() }
        add("复制", true).setOnClickListener { showCopy() }
        for (i in 1..12) {
            val k = add("F$i")
            repeating(k) { term.sendKeyCode(KeyEvent.KEYCODE_F1 + i - 1) }
        }
        repeating(add("INS")) { term.sendKeyCode(KeyEvent.KEYCODE_INSERT) }
        repeating(add("DEL")) { term.sendKeyCode(KeyEvent.KEYCODE_FORWARD_DEL) }
        for (sym in listOf("~", "_", "\\", "&", ";", "*", "$", "\"", "'", "<", ">", "{", "}", "[", "]")) {
            val k = add(sym)
            repeating(k) { term.sendText(sym) }
        }
        val more = add("⋮", true)
        more.setOnClickListener { showMenu(more) }
        return HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(row, FrameLayout.LayoutParams(-2, -1))
        }
    }

    // ---------- 菜单 / 粘贴 / 复制 / 快捷指令 ----------

    private fun showMenu(anchor: View) {
        val m = PopupMenu(this, anchor)
        m.menu.add(0, 1, 0, "粘贴")
        m.menu.add(0, 2, 0, "复制终端文本…")
        m.menu.add(0, 3, 0, "放大字号")
        m.menu.add(0, 4, 0, "缩小字号")
        m.menu.add(0, 5, 0, if (Store.cjkInput) "中文输入模式：开" else "中文输入模式：关")
        if (session.state == TermSession.State.CLOSED) m.menu.add(0, 6, 0, "重新连接")
        m.menu.add(0, 7, 0, "断开并关闭")
        m.setOnMenuItemClickListener {
            when (it.itemId) {
                1 -> paste()
                2 -> showCopy()
                3 -> term.setFontSize(Store.fontSize + 1)
                4 -> term.setFontSize(Store.fontSize - 1)
                5 -> {
                    Store.cjkInput = !Store.cjkInput
                    Store.save()
                    term.restartInput()
                    toast(if (Store.cjkInput) "已开启：可以用拼音等输入法输入中文" else "已关闭：按键直接发送，适合敲命令")
                }
                6 -> session.connect()
                7 -> {
                    SessionManager.close(session.server.id)
                    finish()
                }
            }
            true
        }
        m.show()
    }

    private fun paste() {
        val cm = getSystemService(ClipboardManager::class.java)
        val text = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString()
        if (text.isNullOrEmpty()) {
            toast("剪贴板是空的")
            return
        }
        if (session.state == TermSession.State.CONNECTED) session.emulator.paste(text)
    }

    /** 把终端内容（含回滚历史）放进可选择的文本框，用系统自带的选择/复制 */
    private fun showCopy() {
        val text = session.emulator.screen.transcriptText
        val tv = TextView(this).apply {
            this.text = text
            typeface = Typeface.MONOSPACE
            textSize = 11f
            setTextColor(Color.WHITE)
            setTextIsSelectable(true)
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        val sv = ScrollView(this).apply { addView(tv) }
        AlertDialog.Builder(this)
            .setTitle("长按选择要复制的文本")
            .setView(sv)
            .setPositiveButton("复制全部") { _, _ ->
                getSystemService(ClipboardManager::class.java)
                    .setPrimaryClip(ClipData.newPlainText("terminal", text))
                toast("已复制")
            }
            .setNegativeButton("关闭", null)
            .show()
        sv.post { sv.fullScroll(View.FOCUS_DOWN) }
    }

    private fun showSnippets() {
        val list = Store.snippets
        val b = AlertDialog.Builder(this).setTitle("快捷指令")
        if (list.isEmpty()) {
            b.setMessage("还没有快捷指令。点\"管理\"添加常用命令，之后在这里一点就发送。")
        } else {
            b.setItems(list.map { it.name.ifEmpty { it.command } }.toTypedArray()) { _, i ->
                val sn = list[i]
                if (session.state != TermSession.State.CONNECTED) {
                    toast("当前未连接")
                } else {
                    // 多行指令逐行执行
                    session.write(sn.command.replace("\r\n", "\n").replace('\n', '\r') + if (sn.autoEnter) "\r" else "")
                }
            }
        }
        b.setNeutralButton("管理") { _, _ -> startActivity(Intent(this, SnippetsActivity::class.java)) }
        b.setNegativeButton("关闭", null)
        b.show()
    }
}
