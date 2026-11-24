package com.pocketshell.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.widget.FrameLayout
import android.widget.ListView
import android.widget.TextView
import java.util.UUID

/** 首页：保存的服务器列表，点一下直接登录 */
class MainActivity : Activity() {
    private lateinit var adapter: RowAdapter
    private lateinit var empty: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        adapter = RowAdapter(this)
        val list = ListView(this)
        list.adapter = adapter
        empty = TextView(this).apply {
            text = "还没有服务器\n点右上角 ＋ 添加一台"
            gravity = Gravity.CENTER
            setTextColor(DIM)
            textSize = 16f
        }
        setContentView(FrameLayout(this).apply {
            addView(list)
            addView(empty)
        })

        list.setOnItemClickListener { _, _, pos, _ ->
            val s = adapter.rows[pos].tag as Server
            startActivity(Intent(this, TerminalActivity::class.java).putExtra("server", s.id))
        }
        list.setOnItemLongClickListener { _, _, pos, _ ->
            val s = adapter.rows[pos].tag as Server
            val items = arrayListOf("编辑", "复制一份", "删除")
            if (SessionManager.isActive(s.id)) items.add(0, "断开会话")
            AlertDialog.Builder(this).setTitle(s.name)
                .setItems(items.toTypedArray()) { _, i ->
                    when (items[i]) {
                        "断开会话" -> {
                            SessionManager.close(s.id)
                            refresh()
                        }
                        "编辑" -> startActivity(Intent(this, ServerEditActivity::class.java).putExtra("id", s.id))
                        "复制一份" -> {
                            Store.servers.add(s.copy(id = UUID.randomUUID().toString(), name = s.name + " 副本"))
                            Store.save()
                            refresh()
                        }
                        "删除" -> AlertDialog.Builder(this)
                            .setMessage("删除服务器\"${s.name}\"？保存的密码/密钥会一并删除。")
                            .setPositiveButton("删除") { _, _ ->
                                SessionManager.close(s.id)
                                Store.servers.remove(s)
                                Store.save()
                                refresh()
                            }
                            .setNegativeButton("取消", null).show()
                    }
                }.show()
            true
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        adapter.set(Store.servers.map {
            Row(
                it.name, "${it.user}@${it.host}:${it.port} · ${if (it.useKey) "密钥" else "密码"}", it,
                if (SessionManager.isActive(it.id)) 0xFF3FB950.toInt() else 0xFF484F58.toInt()
            )
        })
        empty.visibility = if (Store.servers.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, 1, 0, "＋ 添加").setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        menu.add(0, 2, 0, "快捷指令")
        menu.add(0, 3, 0, "GitHub 脚本")
        menu.add(0, 4, 0, "关于")
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            1 -> startActivity(Intent(this, ServerEditActivity::class.java))
            2 -> startActivity(Intent(this, SnippetsActivity::class.java))
            3 -> startActivity(Intent(this, GitHubActivity::class.java))
            4 -> AlertDialog.Builder(this).setTitle("PocketShell")
                .setMessage(
                    "所有数据（服务器配置、密码、私钥、快捷指令、GitHub 令牌）都加密保存在应用私有目录里，" +
                        "不申请存储权限，不写入任何公共目录，也不参与云备份。卸载应用后手机上不会留下任何文件。"
                )
                .setPositiveButton("好", null).show()
        }
        return true
    }
}
