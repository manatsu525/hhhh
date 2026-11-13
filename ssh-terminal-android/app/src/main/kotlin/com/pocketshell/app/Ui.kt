package com.pocketshell.app

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.Executors

val mainHandler = Handler(Looper.getMainLooper())
private val pool = Executors.newCachedThreadPool()

/** 后台线程执行（网络请求等） */
fun bg(block: () -> Unit) = pool.execute(block)

/** 回到主线程 */
fun ui(block: () -> Unit) {
    mainHandler.post(block)
}

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

fun Context.toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

const val ACCENT = 0xFF26C6DA.toInt()
const val DIM = 0xFF8B949E.toInt()

/** 列表的一行。header=true 时是不可点击的分组标题。dot!=0 时在左侧画一个状态圆点。 */
class Row(
    val title: String,
    val subtitle: String = "",
    val tag: Any? = null,
    val dot: Int = 0,
    val header: Boolean = false
)

class RowAdapter(private val ctx: Context, var rows: List<Row> = emptyList()) : BaseAdapter() {
    fun set(r: List<Row>) {
        rows = r
        notifyDataSetChanged()
    }

    override fun getCount() = rows.size
    override fun getItem(position: Int) = rows[position]
    override fun getItemId(position: Int) = position.toLong()
    override fun areAllItemsEnabled() = false
    override fun isEnabled(position: Int) = !rows[position].header

    override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
        val r = rows[position]
        if (r.header) {
            return TextView(ctx).apply {
                text = r.title
                setTextColor(ACCENT)
                textSize = 13f
                setPadding(ctx.dp(16), ctx.dp(14), ctx.dp(16), ctx.dp(4))
            }
        }
        val line = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(ctx.dp(16), ctx.dp(10), ctx.dp(16), ctx.dp(10))
            minimumHeight = ctx.dp(56)
        }
        if (r.dot != 0) {
            line.addView(View(ctx).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(r.dot)
                }
            }, LinearLayout.LayoutParams(ctx.dp(10), ctx.dp(10)).apply { rightMargin = ctx.dp(12) })
        }
        val texts = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(TextView(ctx).apply {
            text = r.title
            textSize = 16f
            setTextColor(Color.WHITE)
        })
        if (r.subtitle.isNotEmpty()) {
            texts.addView(TextView(ctx).apply {
                text = r.subtitle
                textSize = 13f
                setTextColor(DIM)
                maxLines = 2
            })
        }
        line.addView(texts)
        return line
    }
}

/** 单行输入对话框；取消时回调 null */
fun Context.inputDialog(
    title: String,
    message: String?,
    secret: Boolean,
    cancelable: Boolean = true,
    cb: (String?) -> Unit
): AlertDialog {
    val edit = EditText(this).apply {
        inputType = if (secret) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        isSingleLine = true
    }
    val box = FrameLayout(this).apply {
        setPadding(dp(20), dp(8), dp(20), 0)
        addView(edit)
    }
    var answered = false
    val d = AlertDialog.Builder(this)
        .setTitle(title)
        .setMessage(message)
        .setView(box)
        .setPositiveButton("确定") { _, _ ->
            answered = true
            cb(edit.text.toString())
        }
        .setNegativeButton("取消", null)
        .setCancelable(cancelable)
        .create()
    d.setOnDismissListener { if (!answered) cb(null) }
    d.show()
    edit.requestFocus()
    return d
}
