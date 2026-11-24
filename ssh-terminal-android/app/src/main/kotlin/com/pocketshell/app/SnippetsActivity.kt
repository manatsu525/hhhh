package com.pocketshell.app

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView

/** 快捷指令管理：点击编辑，长按删除 */
class SnippetsActivity : Activity() {
    private lateinit var adapter: RowAdapter
    private lateinit var empty: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "快捷指令"
        adapter = RowAdapter(this)
        val list = ListView(this)
        list.adapter = adapter
        empty = TextView(this).apply {
            text = "还没有快捷指令\n点右上角 ＋ 添加常用命令"
            gravity = Gravity.CENTER
            setTextColor(DIM)
            textSize = 16f
        }
        setContentView(FrameLayout(this).apply {
            addView(list)
            addView(empty)
        })
        list.setOnItemClickListener { _, _, pos, _ -> edit(adapter.rows[pos].tag as Snippet) }
        list.setOnItemLongClickListener { _, _, pos, _ ->
            val s = adapter.rows[pos].tag as Snippet
            AlertDialog.Builder(this).setMessage("删除快捷指令\"${s.name}\"？")
                .setPositiveButton("删除") { _, _ ->
                    Store.snippets.remove(s)
                    Store.save()
                    refresh()
                }
                .setNegativeButton("取消", null).show()
            true
        }
        refresh()
    }

    private fun refresh() {
        adapter.set(Store.snippets.map {
            Row(it.name, it.command.replace('\n', ' ') + if (it.autoEnter) "  ⏎" else "", it)
        })
        empty.visibility = if (Store.snippets.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun edit(existing: Snippet?) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }
        val name = EditText(this).apply {
            hint = "名称，例如：查看磁盘"
            inputType = InputType.TYPE_CLASS_TEXT
            setText(existing?.name ?: "")
        }
        val cmd = EditText(this).apply {
            hint = "命令，例如：df -h"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            typeface = Typeface.MONOSPACE
            minLines = 2
            maxLines = 8
            setText(existing?.command ?: "")
        }
        val enter = CheckBox(this).apply {
            text = "发送后自动回车执行"
            isChecked = existing?.autoEnter ?: true
        }
        box.addView(name)
        box.addView(cmd)
        box.addView(enter)
        AlertDialog.Builder(this)
            .setTitle(if (existing == null) "添加快捷指令" else "编辑快捷指令")
            .setView(box)
            .setPositiveButton("保存") { _, _ ->
                val c = cmd.text.toString()
                if (c.isBlank()) {
                    toast("命令不能为空")
                    return@setPositiveButton
                }
                val s = existing ?: Snippet().also { Store.snippets.add(it) }
                s.name = name.text.toString().trim().ifEmpty { c.lineSequence().first().take(30) }
                s.command = c
                s.autoEnter = enter.isChecked
                Store.save()
                refresh()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, 1, 0, "＋ 添加").setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == 1) edit(null)
        return true
    }
}
