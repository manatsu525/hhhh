package com.pocketshell.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.TextView

/**
 * GitHub 脚本：用保存的 PAT 浏览自己的仓库，点脚本文件即可在服务器上运行。
 * 从终端里打开时直接在当前会话运行；从首页打开时先选一台服务器。
 */
class GitHubActivity : Activity() {
    private lateinit var setup: LinearLayout
    private lateinit var browser: LinearLayout
    private lateinit var crumb: TextView
    private lateinit var status: TextView
    private lateinit var adapter: RowAdapter
    private lateinit var tokenEdit: EditText

    private var repo: Repo? = null
    private var path = ""
    private var loadSeq = 0

    companion object {
        /** 仓库列表缓存在内存里，进程结束就没了 */
        private var repoCache: List<Repo>? = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "GitHub 脚本"

        // --- 未连接：填 PAT ---
        setup = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(16))
        }
        setup.addView(TextView(this).apply {
            text = "填入 GitHub 个人访问令牌（PAT）即可连接。令牌只加密保存在本机，之后打开直接可用。\n\n" +
                "只需要读取权限：\n· Fine-grained 令牌：Repository permissions → Contents: Read-only\n· Classic 令牌：勾选 repo"
            setTextColor(DIM)
            textSize = 14f
        })
        tokenEdit = EditText(this).apply {
            hint = "github_pat_… 或 ghp_…"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        setup.addView(tokenEdit, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        setup.addView(Button(this).apply {
            text = "保存并连接"
            setOnClickListener { connect() }
        })
        setup.addView(Button(this).apply {
            text = "去 GitHub 创建令牌"
            setOnClickListener {
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/settings/personal-access-tokens/new")))
                } catch (e: Exception) {
                    toast("没有可用的浏览器")
                }
            }
        })

        // --- 已连接：浏览 ---
        browser = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        crumb = TextView(this).apply {
            setTextColor(ACCENT)
            textSize = 13f
            typeface = Typeface.MONOSPACE
            setPadding(dp(16), dp(10), dp(16), dp(6))
        }
        status = TextView(this).apply {
            setTextColor(DIM)
            textSize = 14f
            setPadding(dp(16), dp(8), dp(16), dp(8))
            visibility = View.GONE
        }
        adapter = RowAdapter(this)
        val list = ListView(this)
        list.adapter = adapter
        browser.addView(crumb)
        browser.addView(status)
        browser.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))

        list.setOnItemClickListener { _, _, pos, _ ->
            when (val t = adapter.rows[pos].tag) {
                is Repo -> showDir(t, "")
                is Entry -> if (t.isDir) showDir(repo!!, t.path) else openFile(repo!!.fullName, t.path, repo!!.defaultBranch, t.size)
                is FavScript -> openFile(t.repo, t.path, t.ref, 0)
            }
        }
        list.setOnItemLongClickListener { _, _, pos, _ ->
            val t = adapter.rows[pos].tag
            if (t is FavScript) {
                AlertDialog.Builder(this).setMessage("取消收藏 ${t.name}？")
                    .setPositiveButton("取消收藏") { _, _ ->
                        Store.favorites.remove(t)
                        Store.save()
                        showRoot(false)
                    }
                    .setNegativeButton("保留", null).show()
            }
            true
        }

        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(ScrollView(this@GitHubActivity).apply { addView(setup) }.also { setupScroll = it })
            addView(browser, LinearLayout.LayoutParams(-1, -1))
        })
        if (Store.githubToken.isEmpty()) showSetup() else showRoot(false)
    }

    private lateinit var setupScroll: ScrollView

    private fun showSetup() {
        setupScroll.visibility = View.VISIBLE
        browser.visibility = View.GONE
        invalidateOptionsMenu()
    }

    private fun connect() {
        val t = tokenEdit.text.toString().trim()
        if (t.isEmpty()) {
            toast("请先粘贴令牌")
            return
        }
        toast("正在验证…")
        bg {
            try {
                val login = GitHubApi.login(t)
                ui {
                    Store.githubToken = t
                    Store.githubLogin = login
                    Store.save()
                    tokenEdit.setText("")
                    repoCache = null
                    showRoot(false)
                }
            } catch (e: Exception) {
                ui { toast("连接失败：${e.message}") }
            }
        }
    }

    private fun busy(msg: String?) {
        status.text = msg ?: ""
        status.visibility = if (msg == null) View.GONE else View.VISIBLE
    }

    /** 第一层：收藏的脚本 + 仓库列表 */
    private fun showRoot(reload: Boolean) {
        setupScroll.visibility = View.GONE
        browser.visibility = View.VISIBLE
        invalidateOptionsMenu()
        repo = null
        path = ""
        crumb.text = "@${Store.githubLogin}"
        val seq = ++loadSeq
        fun render(repos: List<Repo>) {
            val rows = ArrayList<Row>()
            if (Store.favorites.isNotEmpty()) {
                rows.add(Row("收藏的脚本（点击运行，长按取消收藏）", header = true))
                Store.favorites.forEach { rows.add(Row("★ ${it.name}", "${it.repo}/${it.path}", it)) }
            }
            rows.add(Row("我的仓库", header = true))
            repos.forEach { rows.add(Row((if (it.private) "🔒 " else "") + it.fullName, it.description, it)) }
            adapter.set(rows)
        }
        val cached = repoCache
        if (cached != null && !reload) {
            busy(null)
            render(cached)
            return
        }
        render(emptyList())
        busy("正在加载仓库列表…")
        val token = Store.githubToken
        bg {
            try {
                val r = GitHubApi.repos(token)
                ui {
                    repoCache = r
                    if (seq == loadSeq) {
                        busy(if (r.isEmpty()) "这个令牌看不到任何仓库，请检查令牌的仓库权限" else null)
                        render(r)
                    }
                }
            } catch (e: Exception) {
                ui { if (seq == loadSeq) busy("加载失败：${e.message}") }
            }
        }
    }

    private fun showDir(r: Repo, p: String) {
        repo = r
        path = p
        crumb.text = r.fullName + (if (p.isEmpty()) "" else "/$p") + "  @${r.defaultBranch}"
        adapter.set(emptyList())
        busy("正在加载…")
        val seq = ++loadSeq
        val token = Store.githubToken
        bg {
            try {
                val entries = GitHubApi.list(token, r.fullName, p, r.defaultBranch)
                ui {
                    if (seq == loadSeq) {
                        busy(if (entries.isEmpty()) "空目录" else null)
                        adapter.set(entries.map {
                            if (it.isDir) Row("📁 ${it.name}", "", it)
                            else Row(it.name, "${it.size} 字节", it)
                        })
                    }
                }
            } catch (e: Exception) {
                ui { if (seq == loadSeq) busy("加载失败：${e.message}") }
            }
        }
    }

    private fun openFile(repoName: String, filePath: String, ref: String, size: Long) {
        if (size > 1024 * 1024) {
            toast("文件超过 1MB，不像是脚本")
            return
        }
        toast("正在获取 ${filePath.substringAfterLast('/')} …")
        val token = Store.githubToken
        bg {
            try {
                val content = GitHubApi.content(token, repoName, filePath, ref)
                ui { if (!isFinishing && !isDestroyed) showRun(FavScript(repoName, filePath, ref), content) }
            } catch (e: Exception) {
                ui { toast("获取失败：${e.message}") }
            }
        }
    }

    /** 运行前确认：预览内容、填参数 */
    private fun showRun(script: FavScript, content: String) {
        if (content.indexOf('\u0000') >= 0) {
            toast("这是二进制文件，不能当脚本运行")
            return
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }
        val preview = TextView(this).apply {
            text = if (content.length > 6000) content.take(6000) + "\n…（后面省略）" else content
            typeface = Typeface.MONOSPACE
            textSize = 10f
            setTextColor(Color.LTGRAY)
            setTextIsSelectable(true)
        }
        box.addView(ScrollView(this).apply {
            addView(preview)
            setBackgroundColor(0xFF0D1117.toInt())
            setPadding(dp(8), dp(6), dp(8), dp(6))
        }, LinearLayout.LayoutParams(-1, dp(200)))
        val args = EditText(this).apply {
            hint = "脚本参数（可选）"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            isSingleLine = true
        }
        box.addView(args)
        val del = CheckBox(this).apply {
            text = "运行结束后删除服务器上的临时文件"
            isChecked = true
        }
        box.addView(del)
        val isFav = Store.favorites.contains(script)
        AlertDialog.Builder(this)
            .setTitle(script.name)
            .setView(box)
            .setPositiveButton("运行") { _, _ -> run(script.name, content, args.text.toString(), del.isChecked) }
            .setNegativeButton("取消", null)
            .setNeutralButton(if (isFav) "取消收藏" else "收藏") { _, _ ->
                if (isFav) Store.favorites.remove(script) else Store.favorites.add(script)
                Store.save()
                toast(if (isFav) "已取消收藏" else "已收藏，下次在首层直接点它运行")
                if (repo == null) showRoot(false)
            }
            .show()
    }

    private fun run(name: String, content: String, args: String, del: Boolean) {
        val current = SessionManager.get(intent.getStringExtra("server"))
        if (current != null && current.state == TermSession.State.CONNECTED) {
            current.runScript(name, content, args, del)
            finish()
            return
        }
        // 没有当前会话：选一台服务器，连上后自动运行
        val servers = Store.servers
        if (servers.isEmpty()) {
            toast("请先在首页添加一台服务器")
            return
        }
        val labels = servers.map { (if (SessionManager.isActive(it.id)) "● " else "") + "${it.name}  (${it.user}@${it.host})" }
        AlertDialog.Builder(this).setTitle("在哪台服务器上运行？")
            .setItems(labels.toTypedArray()) { _, i ->
                val s = SessionManager.open(servers[i])
                if (s.state == TermSession.State.CONNECTED) s.runScript(name, content, args, del)
                else s.onConnected = { s.runScript(name, content, args, del) }
                startActivity(Intent(this, TerminalActivity::class.java).putExtra("server", servers[i].id))
                finish()
            }
            .setNegativeButton("取消", null).show()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        val r = repo
        when {
            browser.visibility != View.VISIBLE || r == null -> super.onBackPressed()
            path.isEmpty() -> showRoot(false)
            else -> showDir(r, path.substringBeforeLast('/', ""))
        }
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        menu.clear()
        if (Store.githubToken.isNotEmpty()) {
            menu.add(0, 1, 0, "刷新").setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
            menu.add(0, 2, 0, "断开 GitHub（删除令牌）")
        }
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            1 -> repo?.let { showDir(it, path) } ?: showRoot(true)
            2 -> AlertDialog.Builder(this).setMessage("删除保存的 GitHub 令牌？收藏的脚本会保留。")
                .setPositiveButton("删除") { _, _ ->
                    Store.githubToken = ""
                    Store.githubLogin = ""
                    Store.save()
                    repoCache = null
                    showSetup()
                }
                .setNegativeButton("取消", null).show()
        }
        return true
    }
}
