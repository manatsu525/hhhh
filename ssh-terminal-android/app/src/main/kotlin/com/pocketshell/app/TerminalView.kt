package com.pocketshell.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Typeface
import android.text.InputType
import android.util.TypedValue
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.widget.Scroller
import com.termux.terminal.KeyHandler
import com.termux.terminal.TerminalEmulator
import com.termux.view.TerminalRenderer

/** 屏幕上的 CTRL / SHIFT / META 粘滞修饰键状态：0=关，1=只对下一个键生效，2=锁定 */
class Mods {
    var ctrl = 0
    var shift = 0
    var alt = 0
    var onChange: (() -> Unit)? = null

    /** 用掉一次性修饰键 */
    fun consume() {
        if (ctrl == 1 || shift == 1 || alt == 1) {
            if (ctrl == 1) ctrl = 0
            if (shift == 1) shift = 0
            if (alt == 1) alt = 0
            onChange?.invoke()
        }
    }
}

/**
 * 终端显示与输入。VT 解析和绘制用 Termux 的 TerminalEmulator / TerminalRenderer，
 * 这里负责尺寸计算、滚动、缩放、软键盘和实体键盘输入。
 */
class TerminalView(context: Context) : View(context) {

    var session: TermSession? = null
        set(value) {
            field = value
            topRow = 0
            updateSize()
            invalidate()
        }
    val mods = Mods()
    var onLongPress: (() -> Unit)? = null

    private var renderer = makeRenderer()
    private var topRow = 0
    private var scrollPx = 0f
    private val scroller = Scroller(context)
    private var lastFlingY = 0
    private var scaleAcc = 1f

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        setBackgroundColor(Color.BLACK)
    }

    private fun makeRenderer(): TerminalRenderer {
        val px = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP, Store.fontSize.toFloat(), resources.displayMetrics
        ).toInt()
        return TerminalRenderer(px, Typeface.MONOSPACE)
    }

    fun setFontSize(sp: Int) {
        val v = sp.coerceIn(6, 36)
        if (v == Store.fontSize) return
        Store.fontSize = v
        Store.save()
        renderer = makeRenderer()
        updateSize()
        invalidate()
    }

    private fun updateSize() {
        val s = session ?: return
        if (width == 0 || height == 0) return
        val cols = maxOf(4, (width / renderer.fontWidth).toInt())
        val rows = maxOf(4, (height - renderer.fontLineSpacing / 4) / renderer.fontLineSpacing)
        s.resize(cols, rows)
        topRow = 0
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        updateSize()
    }

    override fun onDraw(canvas: Canvas) {
        val emu = session?.emulator ?: return
        renderer.render(emu, canvas, topRow, -1, -1, -1, -1)
    }

    /** 会话有新输出时调用 */
    fun onScreenUpdated() {
        val emu = session?.emulator ?: return
        val scrolled = emu.scrollCounter
        emu.clearScrollCounter()
        // 用户正在往回翻看历史时，保持看到的内容不动
        if (topRow != 0) {
            topRow = maxOf(-emu.screen.activeTranscriptRows, topRow - scrolled)
        }
        invalidate()
    }

    // ---------- 触摸：滚动 / 惯性 / 缩放 / 长按 ----------

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean {
            scroller.forceFinished(true)
            scrollPx = 0f
            return true
        }

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            showKeyboard()
            return true
        }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            scrollByPixels(dy)
            return true
        }

        override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
            val emu = session?.emulator ?: return true
            if (emu.isMouseTrackingActive || emu.isAlternateBufferActive) return true
            lastFlingY = 0
            scroller.fling(0, 0, 0, -vy.toInt(), 0, 0, -1000000, 1000000)
            post(flinger)
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            onLongPress?.invoke()
        }
    })

    private val flinger = object : Runnable {
        override fun run() {
            if (scroller.isFinished || !scroller.computeScrollOffset()) return
            val y = scroller.currY
            scrollByPixels((y - lastFlingY).toFloat())
            lastFlingY = y
            post(this)
        }
    }

    private val scaler = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean {
            scaleAcc *= d.scaleFactor
            if (scaleAcc > 1.12f) {
                setFontSize(Store.fontSize + 1)
                scaleAcc = 1f
            } else if (scaleAcc < 0.89f) {
                setFontSize(Store.fontSize - 1)
                scaleAcc = 1f
            }
            return true
        }
    })

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaler.onTouchEvent(event)
        if (!scaler.isInProgress) gestures.onTouchEvent(event)
        return true
    }

    /** dy>0 表示手指上滑（看更新的内容） */
    private fun scrollByPixels(dy: Float) {
        val s = session ?: return
        val emu = s.emulator
        scrollPx += dy
        val rows = (scrollPx / renderer.fontLineSpacing).toInt()
        if (rows == 0) return
        scrollPx -= rows * renderer.fontLineSpacing
        val up = rows < 0
        val n = minOf(kotlin.math.abs(rows), 50)
        when {
            // 开了鼠标上报的程序（如 htop、带鼠标的 vim/tmux）：发滚轮事件
            emu.isMouseTrackingActive -> repeat(n) {
                emu.sendMouseEvent(
                    if (up) TerminalEmulator.MOUSE_WHEELUP_BUTTON else TerminalEmulator.MOUSE_WHEELDOWN_BUTTON,
                    emu.mColumns / 2, emu.mRows / 2, true
                )
            }
            // 全屏程序（less、vim 等）没有本地历史可翻：发上下方向键
            emu.isAlternateBufferActive -> {
                val code = KeyHandler.getCode(
                    if (up) KeyEvent.KEYCODE_DPAD_UP else KeyEvent.KEYCODE_DPAD_DOWN,
                    0, emu.isCursorKeysApplicationMode, emu.isKeypadApplicationMode
                )
                if (code != null) repeat(n) { s.write(code) }
            }
            else -> {
                val t = (topRow + rows).coerceIn(-emu.screen.activeTranscriptRows, 0)
                if (t != topRow) {
                    topRow = t
                    invalidate()
                } else {
                    scroller.forceFinished(true)
                }
            }
        }
    }

    // ---------- 键盘输入 ----------

    fun showKeyboard() {
        requestFocus()
        context.getSystemService(InputMethodManager::class.java).showSoftInput(this, 0)
    }

    fun toggleKeyboard() {
        val imm = context.getSystemService(InputMethodManager::class.java)
        val shown = rootWindowInsets?.let {
            if (android.os.Build.VERSION.SDK_INT >= 30) it.isVisible(android.view.WindowInsets.Type.ime())
            else it.systemWindowInsetBottom > height / 4
        } ?: false
        if (shown) imm.hideSoftInputFromWindow(windowToken, 0) else showKeyboard()
    }

    /** 切换中文输入模式后让输入法重新读取输入类型 */
    fun restartInput() {
        context.getSystemService(InputMethodManager::class.java).restartInput(this)
    }

    override fun onCheckIsTextEditor() = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        // 默认用 TYPE_NULL（和 Termux 默认一致）：输入法不做联想/组词，按一个键就立刻发一个字符。
        // 不能用"可见密码"类型——小米等系统会因此弹出安全键盘并禁止截图。
        // 中文输入模式下用普通文本类型，这样拼音等输入法可以正常组词。
        outAttrs.inputType = if (Store.cjkInput) {
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        } else {
            InputType.TYPE_NULL
        }
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN or EditorInfo.IME_FLAG_NO_EXTRACT_UI or
            EditorInfo.IME_ACTION_NONE

        return object : BaseInputConnection(this, true) {
            private fun flush() {
                val e = editable ?: return
                val t = e.toString()
                e.clear()
                if (t.isNotEmpty()) sendText(t)
            }

            override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
                super.commitText(text, newCursorPosition)
                flush()
                return true
            }

            override fun finishComposingText(): Boolean {
                super.finishComposingText()
                flush()
                return true
            }

            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                // 组词缓冲里没有内容时，退格要发给服务器
                val composing = editable?.length ?: 0
                if (composing == 0) {
                    repeat(maxOf(1, beforeLength)) { sendKeyCode(KeyEvent.KEYCODE_DEL) }
                    return true
                }
                return super.deleteSurroundingText(beforeLength, afterLength)
            }
        }
    }

    /** 发送一段文本（来自输入法、屏幕上的符号键），套用当前的粘滞修饰键 */
    fun sendText(text: String) {
        val s = session ?: return
        val sb = StringBuilder()
        var i = 0
        while (i < text.length) {
            var cp = text.codePointAt(i)
            i += Character.charCount(cp)
            if (cp == '\n'.code) cp = '\r'.code
            val ctrl = mods.ctrl != 0
            val alt = mods.alt != 0
            if (mods.shift != 0 && cp in 'a'.code..'z'.code) cp -= 32
            if (ctrl) cp = ctrlCode(cp)
            if (alt) sb.append('\u001b')
            sb.appendCodePoint(cp)
            mods.consume()
        }
        topRow = 0
        s.write(sb.toString())
    }

    private fun ctrlCode(cp: Int): Int = when (cp) {
        in 'a'.code..'z'.code -> cp - 'a'.code + 1
        in 'A'.code..'Z'.code -> cp - 'A'.code + 1
        ' '.code, '2'.code, '@'.code -> 0
        '['.code, '3'.code -> 27
        '\\'.code, '4'.code -> 28
        ']'.code, '5'.code -> 29
        '^'.code, '6'.code -> 30
        '_'.code, '7'.code, '/'.code -> 31
        '8'.code, '?'.code -> 127
        else -> cp
    }

    /** 发送一个功能键（方向键、F1、PgUp…），套用屏幕修饰键和额外的修饰位 */
    fun sendKeyCode(keyCode: Int, extraMod: Int = 0): Boolean {
        val s = session ?: return false
        val emu = s.emulator
        var mod = extraMod
        if (mods.ctrl != 0) mod = mod or KeyHandler.KEYMOD_CTRL
        if (mods.alt != 0) mod = mod or KeyHandler.KEYMOD_ALT
        if (mods.shift != 0) mod = mod or KeyHandler.KEYMOD_SHIFT
        val code = KeyHandler.getCode(keyCode, mod, emu.isCursorKeysApplicationMode, emu.isKeypadApplicationMode)
            ?: return false
        topRow = 0
        s.write(code)
        mods.consume()
        invalidate()
        return true
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (session == null || event.isSystem || keyCode == KeyEvent.KEYCODE_BACK) {
            return super.onKeyDown(keyCode, event)
        }
        // 实体键盘上的修饰键
        var mod = 0
        if (event.isCtrlPressed) mod = mod or KeyHandler.KEYMOD_CTRL
        if (event.isAltPressed) mod = mod or KeyHandler.KEYMOD_ALT
        if (event.isShiftPressed) mod = mod or KeyHandler.KEYMOD_SHIFT
        if (sendKeyCode(keyCode, mod)) return true

        // 普通字符：先去掉 Ctrl/Alt 取出字符，再自己套修饰
        val meta = event.metaState and (KeyEvent.META_CTRL_MASK or KeyEvent.META_ALT_MASK).inv()
        var cp = event.getUnicodeChar(meta)
        if (cp == 0 || (cp and android.view.KeyCharacterMap.COMBINING_ACCENT) != 0) {
            return super.onKeyDown(keyCode, event)
        }
        val sb = StringBuilder()
        if (mods.shift != 0 && cp in 'a'.code..'z'.code) cp -= 32
        if (event.isCtrlPressed || mods.ctrl != 0) cp = ctrlCode(cp)
        if (event.isAltPressed || mods.alt != 0) sb.append('\u001b')
        sb.appendCodePoint(cp)
        topRow = 0
        session?.write(sb.toString())
        mods.consume()
        return true
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (session == null || event.isSystem || keyCode == KeyEvent.KEYCODE_BACK) {
            return super.onKeyUp(keyCode, event)
        }
        return true
    }
}
