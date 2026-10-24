package com.lanfile.manager.editor;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.SystemClock;
import android.text.InputType;
import android.util.AttributeSet;
import android.view.ActionMode;
import android.view.GestureDetector;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.ExtractedText;
import android.view.inputmethod.ExtractedTextRequest;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;
import android.widget.OverScroller;

import com.lanfile.manager.R;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 高性能文本编辑视图：只排版、只绘制屏幕上可见的那几十行，和文件总大小无关。
 * 自动换行按行懒计算并缓存；超长行在不换行模式下按 4096 字符分段，避免一行几十 MB 时卡死。
 */
public class EditorView extends View {

    public interface Listener {
        void onEditorChanged();
    }

    private static final int SEG = 4096;

    private TextBuffer buf = new TextBuffer();
    private final UndoManager undo = new UndoManager();
    private Listener listener;

    private final Paint tp = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint gp = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint();
    private float density, textSizePx, lineH, ascent, spaceW, tabW, digitW;
    private int tabSize = 4;
    private boolean wrap = true, lineNumbers = true, readOnly;

    private final int cBg, cText, cGutterBg, cGutterText, cSel, cLine, cMatch, cCursor, cHandle;

    // 滚动位置：顶部是第 topLine 行的第 topRow 个显示行，向上偏移 topPx 像素
    private int topLine, topRow;
    private float topPx, scrollX, maxRowW;
    private final OverScroller scroller;
    private int flingLastX, flingLastY;

    // 选区（anchor 固定端，cursor 活动端）
    private int anchor, cursor;
    private int compStart = -1, compEnd = -1;
    private float wantX = -1;

    private boolean cursorOn = true;
    private long lastBlink;

    // 查找高亮
    private Searcher searcher;

    // 行内分段缓存
    private static final class Rows {
        int[] st = new int[4];
        int n = 1;
        boolean done;
    }

    private final LinkedHashMap<Integer, Rows> rowCache = new LinkedHashMap<Integer, Rows>(256, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Integer, Rows> e) {
            return size() > 2048;
        }
    };

    // 可见行
    private int visN;
    private int[] visLine = new int[64], visS = new int[64], visE = new int[64];
    private boolean[] visLast = new boolean[64];
    private boolean[] visFirst = new boolean[64];
    private float[] visY = new float[64];

    private char[] tmp = new char[SEG + 16];
    private float[] wid = new float[SEG + 16];
    private float[] xs = new float[SEG + 17];

    private final GestureDetector gd;
    private final ScaleGestureDetector sgd;
    private ActionMode actionMode;
    private int dragHandle; // 0 无 1 起点 2 终点 3 滚动条
    private boolean showHandles;
    private float handleR;
    private long lastScrollTime;

    private int batch;
    private boolean selDirty;
    private InputMethodManager imm;

    public EditorView(Context c) {
        this(c, null);
    }

    public EditorView(Context c, AttributeSet a) {
        super(c, a);
        setFocusable(true);
        setFocusableInTouchMode(true);
        density = getResources().getDisplayMetrics().density;
        handleR = 11 * density;
        cBg = c.getColor(R.color.ed_bg);
        cText = c.getColor(R.color.ed_text);
        cGutterBg = c.getColor(R.color.ed_gutter_bg);
        cGutterText = c.getColor(R.color.ed_gutter_text);
        cSel = c.getColor(R.color.ed_sel);
        cLine = c.getColor(R.color.ed_line);
        cMatch = c.getColor(R.color.ed_match);
        cCursor = c.getColor(R.color.accent);
        cHandle = c.getColor(R.color.accent);
        tp.setTypeface(Typeface.MONOSPACE);
        gp.setTypeface(Typeface.MONOSPACE);
        setTextSizeSp(15);
        scroller = new OverScroller(c);
        imm = (InputMethodManager) c.getSystemService(Context.INPUT_METHOD_SERVICE);
        gd = new GestureDetector(c, new Gestures());
        gd.setIsLongpressEnabled(true);
        sgd = new ScaleGestureDetector(c, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(ScaleGestureDetector d) {
                float sp = textSizePx / getResources().getDisplayMetrics().scaledDensity * d.getScaleFactor();
                setTextSizeSp(sp);
                return true;
            }

            @Override
            public void onScaleEnd(ScaleGestureDetector d) {
                if (listener != null) listener.onEditorChanged();
            }
        });
    }

    // ============================ 公共接口 ============================

    public void setListener(Listener l) {
        listener = l;
    }

    public TextBuffer buffer() {
        return buf;
    }

    public void setBuffer(TextBuffer b, boolean resetUndo) {
        buf = b;
        if (resetUndo) undo.clear();
        rowCache.clear();
        anchor = cursor = Math.min(cursor, b.length());
        compStart = compEnd = -1;
        if (resetUndo) {
            anchor = cursor = 0;
            topLine = topRow = 0;
            topPx = 0;
            scrollX = 0;
        }
        topLine = Math.min(topLine, b.lineCount() - 1);
        topRow = 0;
        maxRowW = 0;
        restartIme();
        changed();
    }

    public void setTextSizeSp(float sp) {
        sp = Math.max(8, Math.min(40, sp));
        float px = sp * getResources().getDisplayMetrics().scaledDensity;
        if (Math.abs(px - textSizePx) < 0.01f) return;
        textSizePx = px;
        tp.setTextSize(px);
        gp.setTextSize(px * 0.85f);
        Paint.FontMetrics fm = tp.getFontMetrics();
        ascent = -fm.ascent;
        lineH = (float) Math.ceil(fm.descent - fm.ascent + fm.leading + 2 * density);
        spaceW = tp.measureText(" ");
        tabW = spaceW * tabSize;
        digitW = gp.measureText("0");
        rowCache.clear();
        maxRowW = 0;
        topPx = Math.min(topPx, lineH - 1);
        invalidate();
    }

    public float textSizeSp() {
        return textSizePx / getResources().getDisplayMetrics().scaledDensity;
    }

    public void setWrap(boolean w) {
        if (wrap == w) return;
        wrap = w;
        scrollX = 0;
        maxRowW = 0;
        rowCache.clear();
        topRow = 0;
        invalidate();
    }

    public boolean wrap() {
        return wrap;
    }

    public void setLineNumbers(boolean b) {
        lineNumbers = b;
        rowCache.clear();
        invalidate();
    }

    public boolean lineNumbers() {
        return lineNumbers;
    }

    public void setReadOnly(boolean r) {
        readOnly = r;
        if (r && imm != null) imm.hideSoftInputFromWindow(getWindowToken(), 0);
        restartIme();
        invalidate();
    }

    public boolean readOnly() {
        return readOnly;
    }

    public void setSearcher(Searcher s) {
        searcher = s != null && s.usable() ? s : null;
        invalidate();
    }

    public int selStart() {
        return Math.min(anchor, cursor);
    }

    public int selEnd() {
        return Math.max(anchor, cursor);
    }

    public int cursor() {
        return cursor;
    }

    public boolean canUndo() {
        return undo.canUndo();
    }

    public boolean canRedo() {
        return undo.canRedo();
    }

    public void select(int a, int c, boolean scroll) {
        int len = buf.length();
        a = clamp(a, 0, len);
        c = clamp(c, 0, len);
        anchor = a;
        cursor = c;
        wantX = -1;
        showHandles = a != c;
        if (scroll) ensureVisible(c, true);
        selChanged();
    }

    public void selectAll() {
        anchor = 0;
        cursor = buf.length();
        showHandles = true;
        selChanged();
    }

    public void gotoLine(int line) {
        line = clamp(line, 0, buf.lineCount() - 1);
        int p = buf.lineStart(line);
        anchor = cursor = p;
        showHandles = false;
        topLine = line;
        topRow = 0;
        topPx = 0;
        scrollBack(Math.max(0, visibleRows() / 3));
        clampBottom();
        scrollX = 0;
        selChanged();
    }

    /** 当前光标所在行号、列号（从 1 开始） */
    public int[] lineCol() {
        int l = buf.lineOf(cursor);
        return new int[]{l + 1, cursor - buf.lineStart(l) + 1};
    }

    /** 用 text 替换当前选区（记入撤销） */
    public void replaceSelection(String text) {
        if (readOnly) return;
        int s = selStart(), e = selEnd();
        edit(s, e, text, true);
        anchor = cursor = s + text.length();
        ensureVisible(cursor, false);
        selChanged();
    }

    /** 全部替换后整体换掉缓冲区，可以撤销 */
    public void replaceWhole(TextBuffer nb) {
        TextBuffer old = buf;
        int c = cursor;
        undo.recordWhole(old, nb, c);
        buf = nb;
        rowCache.clear();
        anchor = cursor = Math.min(c, nb.length());
        compStart = compEnd = -1;
        topLine = Math.min(topLine, nb.lineCount() - 1);
        topRow = 0;
        clampBottom();
        restartIme();
        changed();
    }

    public void undo() {
        if (readOnly) return;
        UndoManager.Edit e = undo.popUndo();
        if (e == null) return;
        finishComposing();
        if (e.before != null) {
            swapBuffer(e.before, e.cursorBefore);
            return;
        }
        edit(e.pos, e.pos + e.inserted.length(), e.removed, false);
        anchor = e.pos;
        cursor = e.pos + e.removed.length();
        if (anchor == cursor) showHandles = false;
        ensureVisible(cursor, false);
        restartIme();
        selChanged();
    }

    public void redo() {
        if (readOnly) return;
        UndoManager.Edit e = undo.popRedo();
        if (e == null) return;
        finishComposing();
        if (e.after != null) {
            swapBuffer(e.after, e.cursorBefore);
            return;
        }
        edit(e.pos, e.pos + e.removed.length(), e.inserted, false);
        anchor = cursor = e.pos + e.inserted.length();
        showHandles = false;
        ensureVisible(cursor, false);
        restartIme();
        selChanged();
    }

    private void swapBuffer(TextBuffer b, int c) {
        buf = b;
        rowCache.clear();
        anchor = cursor = Math.min(c, b.length());
        topLine = Math.min(topLine, b.lineCount() - 1);
        topRow = 0;
        clampBottom();
        ensureVisible(cursor, false);
        restartIme();
        changed();
    }

    public void copy() {
        int s = selStart(), e = selEnd();
        if (s == e) return;
        if (e - s > 8 * 1024 * 1024) {
            toast("选中内容太大（超过 800 万字符），系统剪贴板无法容纳");
            return;
        }
        ClipboardManager cm = (ClipboardManager) getContext().getSystemService(Context.CLIPBOARD_SERVICE);
        try {
            cm.setPrimaryClip(ClipData.newPlainText("text", buf.substring(s, e)));
        } catch (RuntimeException ex) {
            toast("复制失败：内容太大");
        }
    }

    public void cut() {
        if (readOnly || selStart() == selEnd()) return;
        if (selEnd() - selStart() > 8 * 1024 * 1024) {
            toast("选中内容太大（超过 800 万字符），系统剪贴板无法容纳");
            return;
        }
        copy();
        undo.breakMerge();
        replaceSelection("");
    }

    public void paste() {
        if (readOnly) return;
        ClipboardManager cm = (ClipboardManager) getContext().getSystemService(Context.CLIPBOARD_SERVICE);
        ClipData cd = cm.getPrimaryClip();
        if (cd == null || cd.getItemCount() == 0) return;
        CharSequence t = cd.getItemAt(0).coerceToText(getContext());
        if (t == null || t.length() == 0) return;
        undo.breakMerge();
        finishComposing();
        replaceSelection(normalize(t.toString()));
        undo.breakMerge();
        restartIme();
    }

    static String normalize(String s) {
        if (s.indexOf('\r') < 0) return s;
        return s.replace("\r\n", "\n").replace('\r', '\n');
    }

    private void toast(String s) {
        android.widget.Toast.makeText(getContext(), s, android.widget.Toast.LENGTH_SHORT).show();
    }

    // ============================ 编辑核心 ============================

    private void edit(int s, int e, String text, boolean record) {
        int len = buf.length();
        s = clamp(s, 0, len);
        e = clamp(e, s, len);
        if (s == e && text.isEmpty()) return;
        int line = buf.lineOf(s);
        int oldLines = buf.lineCount();
        String removed = record ? buf.substring(s, e) : null;
        boolean nl = text.indexOf('\n') >= 0;
        if (!nl) {
            for (int i = s; i < e; i++) {
                if (buf.charAt(i) == '\n') {
                    nl = true;
                    break;
                }
            }
        }
        // 仅影响一行时保留该行排版的前缀
        Rows r = nl ? null : rowCache.get(line);
        int rel = s - buf.lineStart(line);
        buf.replace(s, e, text);
        if (record) undo.record(s, removed, text, cursor);
        int delta = buf.lineCount() - oldLines;
        if (nl) {
            Iterator<Map.Entry<Integer, Rows>> it = rowCache.entrySet().iterator();
            while (it.hasNext()) if (it.next().getKey() >= line) it.remove();
            if (line < topLine) topLine = Math.max(line, topLine + delta);
            if (topLine >= buf.lineCount()) topLine = buf.lineCount() - 1;
        } else if (r != null) {
            int k = 0;
            for (int i = 1; i < r.n; i++) if (r.st[i] <= rel) k = i;
            r.n = Math.max(1, k); // 保留到上一显示行为止
            r.done = false;
        }
        // 输入法组字区跟着移动
        if (compStart >= 0) {
            int d = text.length() - (e - s);
            if (compStart >= e) {
                compStart += d;
                compEnd += d;
            } else if (compEnd <= s) {
                // 不变
            } else {
                compStart = compEnd = -1;
            }
        }
        changed();
    }

    private void changed() {
        invalidate();
        if (listener != null) listener.onEditorChanged();
    }

    private void selChanged() {
        cursorOn = true;
        lastBlink = SystemClock.uptimeMillis();
        if (actionMode != null && selStart() == selEnd() && dragHandle == 0) {
            // 保留粘贴菜单
        }
        if (actionMode != null) actionMode.invalidateContentRect();
        notifyImeSelection();
        invalidate();
        if (listener != null) listener.onEditorChanged();
    }

    private void notifyImeSelection() {
        if (batch > 0) {
            selDirty = true;
            return;
        }
        if (imm != null) imm.updateSelection(this, selStart(), selEnd(), compStart, compEnd);
    }

    private void restartIme() {
        if (imm != null) imm.restartInput(this);
    }

    private void finishComposing() {
        compStart = compEnd = -1;
    }

    private void typeText(String t) {
        if (readOnly) return;
        int s, e;
        if (compStart >= 0) {
            s = compStart;
            e = compEnd;
            compStart = compEnd = -1;
        } else {
            s = selStart();
            e = selEnd();
        }
        edit(s, e, t, true);
        anchor = cursor = s + t.length();
        showHandles = false;
        wantX = -1;
        ensureVisible(cursor, false);
        selChanged();
    }

    private void backspace() {
        if (readOnly) return;
        int s = selStart(), e = selEnd();
        if (s == e) {
            if (s == 0) return;
            s = s - 1;
            if (s > 0 && Character.isLowSurrogate(buf.charAt(s)) && Character.isHighSurrogate(buf.charAt(s - 1))) s--;
        } else undo.breakMerge();
        finishComposing();
        edit(s, e, "", true);
        anchor = cursor = s;
        showHandles = false;
        ensureVisible(cursor, false);
        selChanged();
    }

    private void forwardDelete() {
        if (readOnly) return;
        int s = selStart(), e = selEnd();
        if (s == e) {
            if (e >= buf.length()) return;
            e++;
            if (e < buf.length() && Character.isLowSurrogate(buf.charAt(e)) && Character.isHighSurrogate(buf.charAt(e - 1))) e++;
        }
        undo.breakMerge();
        finishComposing();
        edit(s, e, "", true);
        anchor = cursor = s;
        showHandles = false;
        ensureVisible(cursor, false);
        selChanged();
    }

    private void newline() {
        // 自动缩进：沿用当前行开头的空白
        int ls = buf.lineStart(buf.lineOf(selStart()));
        int i = ls, end = selStart();
        while (i < end && (buf.charAt(i) == ' ' || buf.charAt(i) == '\t')) i++;
        undo.breakMerge();
        typeText("\n" + buf.substring(ls, i));
        undo.breakMerge();
    }

    // ============================ 排版 ============================

    private float gutterW() {
        if (!lineNumbers) return 4 * density;
        int d = Math.max(3, String.valueOf(buf.lineCount()).length());
        return digitW * d + 14 * density;
    }

    private float textLeft() {
        return gutterW() + 6 * density;
    }

    private float wrapWidth() {
        return getWidth() - textLeft() - 10 * density;
    }

    private int visibleRows() {
        return Math.max(1, (int) (getHeight() / lineH));
    }

    private Rows rows(int line, int needRowIdx) {
        Rows r = rowCache.get(line);
        if (r == null) {
            r = new Rows();
            rowCache.put(line, r);
        }
        if (r.done || r.n > needRowIdx + 1) return r;
        int ls = buf.lineStart(line);
        int len = buf.lineEnd(line) - ls;
        boolean doWrap = wrap && wrapWidth() > lineH;
        if (!doWrap) {
            while (!r.done && r.n <= needRowIdx + 1) {
                int s = r.st[r.n - 1] + SEG;
                if (s >= len) {
                    r.done = true;
                    break;
                }
                // 不拆代理对
                if (Character.isLowSurrogate(buf.charAt(ls + s))) s--;
                addRow(r, s);
            }
            return r;
        }
        float W = wrapWidth();
        while (!r.done && r.n <= needRowIdx + 1) {
            int rs = r.st[r.n - 1];
            if (len - rs <= 0) {
                r.done = true;
                break;
            }
            // 从 rs 开始找这一显示行的结尾
            float x = 0;
            int pos = rs;
            int lastBreak = -1;
            int brk = -1;
            outer:
            while (pos < len) {
                int n = Math.min(SEG, len - pos);
                buf.getChars(ls + pos, ls + pos + n, tmp, 0);
                tp.getTextWidths(tmp, 0, n, wid);
                for (int i = 0; i < n; i++) {
                    char c = tmp[i];
                    float w = c == '\t' ? tabW - (x % tabW) : wid[i];
                    if ((x + w > W || pos + i - rs >= SEG) && pos + i > rs) {
                        int b = pos + i;
                        if (Character.isLowSurrogate(c)) b--;
                        if (lastBreak > rs && lastBreak > b - 40 && lastBreak <= b && !Character.isWhitespace(c)) b = lastBreak;
                        brk = Math.max(b, rs + 1);
                        break outer;
                    }
                    x += w;
                    if (c == ' ' || c == '\t' || c == '-' || c == ',' || c == '.' || c == ';' || c == '/' || c > 0x2E80) {
                        lastBreak = pos + i + 1;
                    }
                }
                pos += n;
            }
            if (brk < 0) {
                r.done = true;
            } else addRow(r, brk);
        }
        return r;
    }

    private static void addRow(Rows r, int s) {
        if (r.n == r.st.length) {
            int[] n = new int[r.st.length * 2];
            System.arraycopy(r.st, 0, n, 0, r.n);
            r.st = n;
        }
        r.st[r.n++] = s;
    }

    private int rowCount(int line) {
        Rows r = rows(line, Integer.MAX_VALUE - 2);
        return r.n;
    }

    /** 绝对位置 → 所在显示行序号 */
    private int rowOf(int line, int pos) {
        int rel = pos - buf.lineStart(line);
        Rows r = rowCache.get(line);
        int need = 0;
        while (true) {
            r = rows(line, need);
            if (r.done || r.st[r.n - 1] > rel) break;
            need = r.n + 16;
        }
        int lo = 0, hi = r.n - 1;
        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;
            if (r.st[mid] <= rel) lo = mid;
            else hi = mid - 1;
        }
        return lo;
    }

    private int rowStartAbs(int line, int row) {
        return buf.lineStart(line) + rows(line, row).st[row];
    }

    private int rowEndAbs(int line, int row) {
        Rows r = rows(line, row + 1);
        if (row + 1 < r.n) return buf.lineStart(line) + r.st[row + 1];
        return buf.lineEnd(line);
    }

    private boolean nextRow() {
        Rows r = rows(topLine, topRow + 1);
        if (topRow + 1 < r.n) {
            topRow++;
            return true;
        }
        if (topLine + 1 < buf.lineCount()) {
            topLine++;
            topRow = 0;
            return true;
        }
        return false;
    }

    private boolean prevRow() {
        if (topRow > 0) {
            topRow--;
            return true;
        }
        if (topLine > 0) {
            topLine--;
            topRow = rowCount(topLine) - 1;
            return true;
        }
        return false;
    }

    private void scrollBack(int n) {
        for (int i = 0; i < n; i++) if (!prevRow()) break;
    }

    /** 能否让顶部再向下一行（保证最后一行不会滚出屏幕底部太多） */
    private boolean canAdvance() {
        int vr = visibleRows();
        int lc = buf.lineCount();
        if (topLine < lc - vr - 1) return true;
        int l = topLine, row = topRow, cnt = 0;
        while (cnt < vr) {
            Rows r = rows(l, row + 1);
            if (row + 1 < r.n) row++;
            else if (l + 1 < lc) {
                l++;
                row = 0;
            } else return false;
            cnt++;
        }
        return true;
    }

    private void clampBottom() {
        int guard = 0;
        while (!canAdvanceOrEqual() && guard++ < 10000) {
            if (!prevRow()) break;
        }
        if (topLine == 0 && topRow == 0 && topPx < 0) topPx = 0;
    }

    private boolean canAdvanceOrEqual() {
        // 底部还有内容填满屏幕（允许最后留 1/3 屏空白）
        int vr = Math.max(1, visibleRows() * 2 / 3);
        int lc = buf.lineCount();
        if (topLine < lc - vr - 1) return true;
        int l = topLine, row = topRow, cnt = 0;
        while (cnt < vr) {
            Rows r = rows(l, row + 1);
            if (row + 1 < r.n) row++;
            else if (l + 1 < lc) {
                l++;
                row = 0;
            } else return false;
            cnt++;
        }
        return true;
    }

    private void scrollByPx(float dx, float dy) {
        if (dy != 0) {
            topPx += dy;
            while (topPx >= lineH) {
                if (!canAdvance() || !nextRow()) {
                    topPx = Math.min(topPx, lineH - 1);
                    if (!canAdvance()) topPx = 0;
                    scroller.forceFinished(true);
                    break;
                }
                topPx -= lineH;
            }
            while (topPx < 0) {
                if (!prevRow()) {
                    topPx = 0;
                    scroller.forceFinished(true);
                    break;
                }
                topPx += lineH;
            }
        }
        if (dx != 0 && !wrap) {
            float max = Math.max(0, maxRowW - (getWidth() - textLeft()) + 40 * density);
            scrollX = clampF(scrollX + dx, 0, max);
        }
        lastScrollTime = SystemClock.uptimeMillis();
        invalidate();
        if (actionMode != null) actionMode.invalidateContentRect();
    }

    private void layoutVisible() {
        int vr = (int) (getHeight() / lineH) + 2;
        if (visLine.length < vr) {
            visLine = new int[vr];
            visS = new int[vr];
            visE = new int[vr];
            visY = new float[vr];
            visLast = new boolean[vr];
            visFirst = new boolean[vr];
        }
        int lc = buf.lineCount();
        if (topLine >= lc) {
            topLine = lc - 1;
            topRow = 0;
        }
        Rows tr = rows(topLine, topRow);
        if (topRow >= tr.n) topRow = tr.n - 1;
        int l = topLine, row = topRow;
        float y = -topPx;
        visN = 0;
        while (visN < vr && l < lc && y < getHeight()) {
            Rows r = rows(l, row + 1);
            int ls = buf.lineStart(l);
            visLine[visN] = l;
            visS[visN] = ls + r.st[row];
            boolean last = row + 1 >= r.n;
            visE[visN] = last ? buf.lineEnd(l) : ls + r.st[row + 1];
            visLast[visN] = last;
            visFirst[visN] = row == 0;
            visY[visN] = y;
            visN++;
            y += lineH;
            if (!last) row++;
            else {
                l++;
                row = 0;
            }
        }
    }

    /** 把一行显示文本读入 tmp，计算 xs（每个字符左边的 x） */
    private int loadRow(int s, int e) {
        if (e - s > SEG) e = s + SEG; // 防御：缓存过期时也不会越界
        if (e < s) e = s;
        int n = e - s;
        buf.getChars(s, e, tmp, 0);
        tp.getTextWidths(tmp, 0, n, wid);
        float x = 0;
        for (int i = 0; i < n; i++) {
            xs[i] = x;
            x += tmp[i] == '\t' ? tabW - (x % tabW) : wid[i];
        }
        xs[n] = x;
        return n;
    }

    // ============================ 绘制 ============================

    @Override
    protected void onDraw(Canvas c) {
        c.drawColor(cBg);
        if (lineH <= 0) return;
        layoutVisible();
        float gw = gutterW();
        float left = textLeft() - scrollX;
        int ss = selStart(), se = selEnd();
        int curLine = buf.lineOf(cursor);
        boolean focused = hasFocus();

        // 查找高亮：只在可见范围内搜索
        int[] marks = null;
        int markN = 0;
        if (searcher != null && visN > 0) {
            int a = visS[0], b = visE[visN - 1];
            if (b - a < 400_000) {
                int qa = Math.max(0, a - 256), qb = Math.min(buf.length(), b + 256);
                char[] arr = new char[qb - qa];
                buf.getChars(qa, qb, arr, 0);
                ArrayList<int[]> found = new ArrayList<>();
                int from = 0;
                int[] m;
                try {
                    while (found.size() < 2000 && (m = searcher.next(arr, arr.length, from)) != null) {
                        found.add(m);
                        from = m[1];
                    }
                } catch (RuntimeException | StackOverflowError ignored) {
                }
                marks = new int[found.size() * 2];
                for (int[] f : found) {
                    marks[markN++] = f[0] + qa;
                    marks[markN++] = f[1] + qa;
                }
            }
        }

        for (int i = 0; i < visN; i++) {
            float y = visY[i];
            int s = visS[i], e = visE[i];
            int n = loadRow(s, e);
            if (xs[n] > maxRowW) maxRowW = xs[n];
            if (visLine[i] == curLine && ss == se && focused) {
                fill.setColor(cLine);
                c.drawRect(gw, y, getWidth(), y + lineH, fill);
            }
            // 查找匹配
            if (marks != null) {
                fill.setColor(cMatch);
                for (int k = 0; k < markN; k += 2) {
                    int a = Math.max(marks[k], s), b = Math.min(marks[k + 1], e);
                    if (a < b) {
                        float x2 = left + xs[b - s];
                        if (marks[k + 1] > e && visLast[i]) x2 += spaceW / 2;
                        c.drawRect(left + xs[a - s], y, Math.max(x2, left + xs[a - s] + 2), y + lineH, fill);
                    }
                }
            }
            // 选区
            if (ss < se) {
                int a = Math.max(ss, s), b = Math.min(se, e);
                boolean eol = visLast[i] && se > e && ss <= e;
                if (a < b || eol) {
                    fill.setColor(cSel);
                    float x1 = left + xs[a - s];
                    float x2 = left + xs[b - s] + (eol ? spaceW * 0.6f : 0);
                    c.drawRect(x1, y, x2, y + lineH, fill);
                }
            }
            // 文字（遇到 tab 分段绘制）
            tp.setColor(cText);
            float base = y + (lineH - (tp.descent() - tp.ascent())) / 2 + ascent;
            int runStart = 0;
            for (int k = 0; k <= n; k++) {
                if (k == n || tmp[k] == '\t') {
                    if (k > runStart) {
                        // 跳过左侧不可见部分，减少绘制量
                        c.drawText(tmp, runStart, k - runStart, left + xs[runStart], base, tp);
                    }
                    runStart = k + 1;
                }
            }
            // 组字下划线
            if (compStart >= 0 && compEnd > compStart) {
                int a = Math.max(compStart, s), b = Math.min(compEnd, e);
                if (a < b) {
                    fill.setColor(cText);
                    c.drawRect(left + xs[a - s], y + lineH - 2 * density, left + xs[b - s], y + lineH - density, fill);
                }
            }
            // 光标
            if (focused && cursorOn && !readOnly && cursor >= s && (cursor < e || (cursor == e && visLast[i]))) {
                fill.setColor(cCursor);
                float cx = left + xs[cursor - s];
                c.drawRect(cx - density, y + 2 * density, cx + density, y + lineH - 2 * density, fill);
            }
        }

        // 行号栏
        if (lineNumbers) {
            fill.setColor(cGutterBg);
            c.drawRect(0, 0, gw, getHeight(), fill);
            gp.setColor(cGutterText);
            gp.setTextAlign(Paint.Align.RIGHT);
            for (int i = 0; i < visN; i++) {
                if (!visFirst[i]) continue;
                float base = visY[i] + (lineH - (tp.descent() - tp.ascent())) / 2 + ascent;
                String num = String.valueOf(visLine[i] + 1);
                c.drawText(num, gw - 7 * density, base, gp);
            }
        }

        // 选区手柄
        if (showHandles && ss < se) {
            drawHandle(c, ss, true);
            drawHandle(c, se, false);
        }

        // 快速滚动条
        int lc = buf.lineCount();
        if (lc > visibleRows() * 3) {
            boolean active = dragHandle == 3 || SystemClock.uptimeMillis() - lastScrollTime < 1500;
            RectF tr = thumbRect();
            fill.setColor(cHandle);
            fill.setAlpha(active ? 200 : 70);
            c.drawRoundRect(tr, 3 * density, 3 * density, fill);
            fill.setAlpha(255);
            if (active) postInvalidateDelayed(1600);
        }

        // 光标闪烁
        if (focused && !readOnly) {
            long now = SystemClock.uptimeMillis();
            if (now - lastBlink >= 500) {
                cursorOn = !cursorOn;
                lastBlink = now;
            }
            postInvalidateDelayed(500 - (now - lastBlink));
        }
    }

    private RectF thumbRect() {
        int lc = buf.lineCount();
        float h = 44 * density;
        float frac = lc <= 1 ? 0 : (float) topLine / (lc - 1);
        float top = frac * (getHeight() - h);
        float w = dragHandle == 3 ? 8 * density : 5 * density;
        return new RectF(getWidth() - w - 2 * density, top, getWidth() - 2 * density, top + h);
    }

    private void drawHandle(Canvas c, int pos, boolean start) {
        float[] p = posToXY(pos);
        if (p == null) return;
        fill.setColor(cHandle);
        float x = p[0], y = p[1] + lineH;
        c.drawRect(x - density, p[1], x + density, y, fill);
        c.drawCircle(x, y + handleR * 0.8f, handleR, fill);
    }

    /** 位置 → 屏幕坐标（左上角），不可见返回 null */
    private float[] posToXY(int pos) {
        float left = textLeft() - scrollX;
        for (int i = 0; i < visN; i++) {
            if (pos >= visS[i] && (pos < visE[i] || (pos == visE[i] && visLast[i]))) {
                int n = loadRow(visS[i], visE[i]);
                return new float[]{left + xs[Math.min(pos - visS[i], n)], visY[i]};
            }
        }
        return null;
    }

    /** 屏幕坐标 → 文本位置 */
    private int hitTest(float x, float y) {
        layoutVisible();
        if (visN == 0) return 0;
        int i = (int) Math.floor((y + topPx) / lineH);
        if (i < 0) i = 0;
        if (i >= visN) i = visN - 1;
        return hitRow(i, x);
    }

    private int hitRow(int i, float x) {
        float left = textLeft() - scrollX;
        int s = visS[i];
        int n = loadRow(s, visE[i]);
        float rx = x - left;
        int k = 0;
        while (k < n && xs[k] + (xs[k + 1] - xs[k]) / 2 < rx) k++;
        if (k < n && Character.isLowSurrogate(tmp[k]) && k > 0) k--;
        if (k == n && !visLast[i] && n > 0) k = n - 1; // 软换行处落在本行末尾字符前
        return s + k;
    }

    // ============================ 滚动定位 ============================

    /** 保证 pos 可见 */
    public void ensureVisible(int pos, boolean center) {
        if (getHeight() == 0) {
            post(() -> ensureVisible(pos, center));
            return;
        }
        int line = buf.lineOf(pos);
        int row = rowOf(line, pos);
        int vr = visibleRows();
        // 与当前顶部比较
        int cmp = line != topLine ? Integer.compare(line, topLine) : Integer.compare(row, topRow);
        if (cmp < 0 || (cmp == 0 && topPx > 0)) {
            topLine = line;
            topRow = row;
            topPx = 0;
            if (center) scrollBack(vr / 3);
        } else {
            // 从顶部往下数 vr-1 行，看是否在屏幕内
            int l = topLine, r = topRow, cnt = 0;
            boolean inside = false;
            int lim = Math.max(1, vr - 1);
            while (cnt < lim) {
                if (l == line && r == row) {
                    inside = true;
                    break;
                }
                Rows rr = rows(l, r + 1);
                if (r + 1 < rr.n) r++;
                else if (l + 1 < buf.lineCount()) {
                    l++;
                    r = 0;
                } else break;
                cnt++;
                if (l > line) break;
            }
            if (!inside) {
                topLine = line;
                topRow = row;
                topPx = 0;
                scrollBack(center ? vr / 3 : Math.max(0, vr - 2));
            }
        }
        // 水平
        if (!wrap) {
            int rs = rowStartAbs(line, row);
            int re = rowEndAbs(line, row);
            int n = loadRow(rs, re);
            float x = xs[Math.min(pos - rs, n)];
            float vw = getWidth() - textLeft() - 16 * density;
            if (x < scrollX) scrollX = Math.max(0, x - vw / 3);
            else if (x > scrollX + vw) scrollX = x - vw * 2 / 3;
            if (xs[n] > maxRowW) maxRowW = xs[n];
        }
        invalidate();
    }

    // ============================ 光标移动 ============================

    private void moveCursor(int pos, boolean extend) {
        pos = clamp(pos, 0, buf.length());
        cursor = pos;
        if (!extend) anchor = pos;
        showHandles = extend && anchor != cursor;
        finishComposing();
        ensureVisible(cursor, false);
        selChanged();
    }

    private void moveVertical(int dir, boolean extend) {
        int line = buf.lineOf(cursor);
        int row = rowOf(line, cursor);
        int rs = rowStartAbs(line, row);
        int n = loadRow(rs, rowEndAbs(line, row));
        float x = wantX >= 0 ? wantX : xs[Math.min(cursor - rs, n)];
        int tl = line, tr = row;
        if (dir < 0) {
            if (tr > 0) tr--;
            else if (tl > 0) {
                tl--;
                tr = rowCount(tl) - 1;
            } else {
                moveCursor(0, extend);
                return;
            }
        } else {
            Rows r = rows(tl, tr + 1);
            if (tr + 1 < r.n) tr++;
            else if (tl + 1 < buf.lineCount()) {
                tl++;
                tr = 0;
            } else {
                moveCursor(buf.length(), extend);
                return;
            }
        }
        int s = rowStartAbs(tl, tr), e = rowEndAbs(tl, tr);
        int m = loadRow(s, e);
        int k = 0;
        while (k < m && xs[k] + (xs[k + 1] - xs[k]) / 2 < x) k++;
        boolean last = rows(tl, tr + 1).n <= tr + 1;
        if (k == m && !last && m > 0) k = m - 1;
        float keep = x;
        moveCursor(s + k, extend);
        wantX = keep;
    }

    private void page(int dir, boolean extend) {
        int vr = Math.max(1, visibleRows() - 1);
        float keep = wantX;
        for (int i = 0; i < vr; i++) {
            if (dir < 0) {
                if (!prevRow()) break;
            } else if (!canAdvance() || !nextRow()) break;
        }
        wantX = keep;
        for (int i = 0; i < vr; i++) {
            int before = cursor;
            moveVertical(dir, extend);
            if (cursor == before) break;
        }
    }

    // ============================ 输入 ============================

    @Override
    public boolean onCheckIsTextEditor() {
        return !readOnly;
    }

    @Override
    public InputConnection onCreateInputConnection(EditorInfo out) {
        if (readOnly) return null;
        out.inputType = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS;
        out.imeOptions = EditorInfo.IME_ACTION_NONE | EditorInfo.IME_FLAG_NO_EXTRACT_UI
                | EditorInfo.IME_FLAG_NO_FULLSCREEN | EditorInfo.IME_FLAG_NO_ENTER_ACTION;
        out.initialSelStart = selStart();
        out.initialSelEnd = selEnd();
        return new Conn();
    }

    private final class Conn extends BaseInputConnection {
        Conn() {
            super(EditorView.this, false);
        }

        @Override
        public boolean beginBatchEdit() {
            batch++;
            return true;
        }

        @Override
        public boolean endBatchEdit() {
            if (batch > 0) batch--;
            if (batch == 0 && selDirty) {
                selDirty = false;
                notifyImeSelection();
            }
            return batch > 0;
        }

        @Override
        public boolean commitText(CharSequence text, int newCursorPosition) {
            if (readOnly) return false;
            String t = normalize(text.toString());
            if (t.equals("\n")) {
                compStart = compEnd = -1;
                newline();
                return true;
            }
            typeText(t);
            return true;
        }

        @Override
        public boolean setComposingText(CharSequence text, int newCursorPosition) {
            if (readOnly) return false;
            String t = normalize(text.toString());
            int s, e;
            if (compStart >= 0) {
                s = compStart;
                e = compEnd;
            } else {
                s = selStart();
                e = selEnd();
            }
            edit(s, e, t, true);
            if (t.isEmpty()) compStart = compEnd = -1;
            else {
                compStart = s;
                compEnd = s + t.length();
            }
            anchor = cursor = s + t.length();
            showHandles = false;
            ensureVisible(cursor, false);
            selChanged();
            return true;
        }

        @Override
        public boolean setComposingRegion(int start, int end) {
            int a = clamp(Math.min(start, end), 0, buf.length()), b = clamp(Math.max(start, end), 0, buf.length());
            if (a == b) compStart = compEnd = -1;
            else {
                compStart = a;
                compEnd = b;
            }
            invalidate();
            return true;
        }

        @Override
        public boolean finishComposingText() {
            compStart = compEnd = -1;
            undo.breakMerge();
            invalidate();
            notifyImeSelection();
            return true;
        }

        @Override
        public boolean deleteSurroundingText(int before, int after) {
            if (readOnly) return false;
            int s = selStart(), e = selEnd();
            int len = buf.length();
            int ae = Math.min(len, e + Math.max(0, after));
            if (ae > e) edit(e, ae, "", true);
            int bs = Math.max(0, s - Math.max(0, before));
            if (bs < s) edit(bs, s, "", true);
            int d = s - bs;
            anchor = clamp(anchor - d, 0, buf.length());
            cursor = clamp(cursor - d, 0, buf.length());
            ensureVisible(cursor, false);
            selChanged();
            return true;
        }

        @Override
        public boolean deleteSurroundingTextInCodePoints(int before, int after) {
            int s = selStart(), e = selEnd();
            int b = 0, p = s;
            for (int i = 0; i < before && p > 0; i++) {
                p--;
                if (p > 0 && Character.isLowSurrogate(buf.charAt(p)) && Character.isHighSurrogate(buf.charAt(p - 1))) p--;
            }
            b = s - p;
            int a = 0, q = e;
            for (int i = 0; i < after && q < buf.length(); i++) {
                q++;
                if (q < buf.length() && Character.isLowSurrogate(buf.charAt(q))) q++;
            }
            a = q - e;
            return deleteSurroundingText(b, a);
        }

        @Override
        public CharSequence getTextBeforeCursor(int n, int flags) {
            int s = selStart();
            n = Math.min(n, 4096);
            return buf.substring(Math.max(0, s - n), s);
        }

        @Override
        public CharSequence getTextAfterCursor(int n, int flags) {
            int e = selEnd();
            n = Math.min(n, 4096);
            return buf.substring(e, Math.min(buf.length(), e + n));
        }

        @Override
        public CharSequence getSelectedText(int flags) {
            int s = selStart(), e = selEnd();
            if (s == e) return null;
            return buf.substring(s, Math.min(e, s + 4096));
        }

        @Override
        public boolean setSelection(int start, int end) {
            select(start, end, true);
            return true;
        }

        @Override
        public ExtractedText getExtractedText(ExtractedTextRequest request, int flags) {
            return null;
        }

        @Override
        public int getCursorCapsMode(int reqModes) {
            return 0;
        }

        @Override
        public boolean performContextMenuAction(int id) {
            if (id == android.R.id.selectAll) selectAll();
            else if (id == android.R.id.copy) copy();
            else if (id == android.R.id.cut) cut();
            else if (id == android.R.id.paste) paste();
            else return false;
            return true;
        }

        @Override
        public boolean performEditorAction(int action) {
            newline();
            return true;
        }

        @Override
        public boolean requestCursorUpdates(int cursorUpdateMode) {
            return false;
        }

        @Override
        public void closeConnection() {
            compStart = compEnd = -1;
            batch = 0;
            super.closeConnection();
        }

        @Override
        public boolean performPrivateCommand(String action, Bundle data) {
            return false;
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent ev) {
        boolean shift = ev.isShiftPressed();
        boolean ctrl = ev.isCtrlPressed();
        if (ctrl) {
            switch (keyCode) {
                case KeyEvent.KEYCODE_A:
                    selectAll();
                    return true;
                case KeyEvent.KEYCODE_C:
                    copy();
                    return true;
                case KeyEvent.KEYCODE_X:
                    cut();
                    return true;
                case KeyEvent.KEYCODE_V:
                    paste();
                    return true;
                case KeyEvent.KEYCODE_Z:
                    if (shift) redo();
                    else undo();
                    return true;
                case KeyEvent.KEYCODE_Y:
                    redo();
                    return true;
                case KeyEvent.KEYCODE_MOVE_HOME:
                    moveCursor(0, shift);
                    return true;
                case KeyEvent.KEYCODE_MOVE_END:
                    moveCursor(buf.length(), shift);
                    return true;
            }
            return super.onKeyDown(keyCode, ev); // 交给 Activity 处理 Ctrl+F / Ctrl+S 等
        }
        switch (keyCode) {
            case KeyEvent.KEYCODE_DEL:
                backspace();
                return true;
            case KeyEvent.KEYCODE_FORWARD_DEL:
                forwardDelete();
                return true;
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                if (!readOnly) newline();
                return true;
            case KeyEvent.KEYCODE_TAB:
                if (!readOnly) typeText("\t");
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (!shift && selStart() != selEnd()) moveCursor(selStart(), false);
                else moveCursor(cursor - (cursor > 1 && Character.isLowSurrogate(buf.charAt(cursor - 1)) ? 2 : 1), shift);
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (!shift && selStart() != selEnd()) moveCursor(selEnd(), false);
                else moveCursor(cursor + (cursor + 1 < buf.length() && Character.isHighSurrogate(buf.charAt(cursor)) ? 2 : 1), shift);
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
                moveVertical(-1, shift);
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                moveVertical(1, shift);
                return true;
            case KeyEvent.KEYCODE_MOVE_HOME: {
                int l = buf.lineOf(cursor);
                moveCursor(rowStartAbs(l, rowOf(l, cursor)), shift);
                return true;
            }
            case KeyEvent.KEYCODE_MOVE_END: {
                int l = buf.lineOf(cursor);
                int r = rowOf(l, cursor);
                int e = rowEndAbs(l, r);
                boolean last = rows(l, r + 1).n <= r + 1;
                moveCursor(last ? e : Math.max(rowStartAbs(l, r), e - 1), shift);
                return true;
            }
            case KeyEvent.KEYCODE_PAGE_UP:
                page(-1, shift);
                return true;
            case KeyEvent.KEYCODE_PAGE_DOWN:
                page(1, shift);
                return true;
        }
        int uc = ev.getUnicodeChar(ev.getMetaState());
        if (uc != 0 && !readOnly && (ev.getMetaState() & (KeyEvent.META_ALT_ON | KeyEvent.META_META_ON)) == 0) {
            typeText(new String(Character.toChars(uc)));
            return true;
        }
        return super.onKeyDown(keyCode, ev);
    }

    // ============================ 触摸 ============================

    public void showKeyboard() {
        if (readOnly) return;
        requestFocus();
        if (imm != null) imm.showSoftInput(this, 0);
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        int act = ev.getActionMasked();
        if (act == MotionEvent.ACTION_DOWN) {
            dragHandle = 0;
            if (!scroller.isFinished()) scroller.forceFinished(true);
            float x = ev.getX(), y = ev.getY();
            if (buf.lineCount() > visibleRows() * 3) {
                RectF tr = thumbRect();
                if (x > getWidth() - 36 * density && y > tr.top - 20 * density && y < tr.bottom + 20 * density) {
                    dragHandle = 3;
                    getParent().requestDisallowInterceptTouchEvent(true);
                    invalidate();
                    return true;
                }
            }
            if (showHandles && selStart() < selEnd()) {
                layoutVisible();
                if (nearHandle(selStart(), x, y)) dragHandle = 1;
                else if (nearHandle(selEnd(), x, y)) dragHandle = 2;
                if (dragHandle != 0) {
                    // 统一让 cursor 成为被拖动的一端
                    int s = selStart(), e = selEnd();
                    if (dragHandle == 1) {
                        anchor = e;
                        cursor = s;
                    } else {
                        anchor = s;
                        cursor = e;
                    }
                    getParent().requestDisallowInterceptTouchEvent(true);
                    return true;
                }
            }
        }
        if (dragHandle == 3) {
            if (act == MotionEvent.ACTION_MOVE) {
                float h = 44 * density;
                float frac = clampF((ev.getY() - h / 2) / Math.max(1, getHeight() - h), 0, 1);
                topLine = Math.round(frac * (buf.lineCount() - 1));
                topRow = 0;
                topPx = 0;
                clampBottom();
                lastScrollTime = SystemClock.uptimeMillis();
                invalidate();
                if (listener != null) listener.onEditorChanged();
            } else if (act == MotionEvent.ACTION_UP || act == MotionEvent.ACTION_CANCEL) {
                dragHandle = 0;
                lastScrollTime = SystemClock.uptimeMillis();
                invalidate();
            }
            return true;
        }
        if (dragHandle == 1 || dragHandle == 2) {
            if (act == MotionEvent.ACTION_MOVE) {
                float y = ev.getY() - lineH - handleR;
                if (ev.getY() > getHeight() - lineH) scrollByPx(0, lineH / 2);
                else if (ev.getY() < lineH) scrollByPx(0, -lineH / 2);
                int p = hitTest(ev.getX(), y);
                if (p != anchor) {
                    cursor = p;
                    selChanged();
                }
            } else if (act == MotionEvent.ACTION_UP || act == MotionEvent.ACTION_CANCEL) {
                dragHandle = 0;
                startSelectionMode();
            }
            return true;
        }
        sgd.onTouchEvent(ev);
        if (sgd.isInProgress()) return true;
        gd.onTouchEvent(ev);
        return true;
    }

    private boolean nearHandle(int pos, float x, float y) {
        float[] p = posToXY(pos);
        if (p == null) return false;
        float hx = p[0], hy = p[1] + lineH + handleR * 0.8f;
        float dx = x - hx, dy = y - hy;
        float r = handleR * 2.2f;
        return dx * dx + dy * dy < r * r;
    }

    private final class Gestures extends GestureDetector.SimpleOnGestureListener {
        @Override
        public boolean onDown(MotionEvent e) {
            return true;
        }

        @Override
        public boolean onSingleTapUp(MotionEvent e) {
            int p = hitTest(e.getX(), e.getY());
            if (actionMode != null) actionMode.finish();
            anchor = cursor = p;
            wantX = -1;
            showHandles = false;
            finishComposing();
            restartIme();
            selChanged();
            showKeyboard();
            return true;
        }

        @Override
        public boolean onDoubleTap(MotionEvent e) {
            selectWordAt(hitTest(e.getX(), e.getY()));
            return true;
        }

        @Override
        public void onLongPress(MotionEvent e) {
            if (sgd.isInProgress()) return;
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            int p = hitTest(e.getX(), e.getY());
            int s = selStart(), en = selEnd();
            if (s < en && p >= s && p <= en) {
                startSelectionMode();
                return;
            }
            selectWordAt(p);
        }

        @Override
        public boolean onScroll(MotionEvent e1, MotionEvent e2, float dx, float dy) {
            getParent().requestDisallowInterceptTouchEvent(true);
            scrollByPx(Math.abs(dx) > Math.abs(dy) * 0.5f ? dx : 0, dy);
            return true;
        }

        @Override
        public boolean onFling(MotionEvent e1, MotionEvent e2, float vx, float vy) {
            flingLastX = 0;
            flingLastY = 0;
            if (wrap || Math.abs(vx) < Math.abs(vy)) vx = 0;
            scroller.fling(0, 0, (int) -vx, (int) -vy, Integer.MIN_VALUE / 2, Integer.MAX_VALUE / 2,
                    Integer.MIN_VALUE / 2, Integer.MAX_VALUE / 2);
            postInvalidateOnAnimation();
            return true;
        }
    }

    @Override
    public void computeScroll() {
        if (scroller.computeScrollOffset()) {
            int x = scroller.getCurrX(), y = scroller.getCurrY();
            scrollByPx(x - flingLastX, y - flingLastY);
            flingLastX = x;
            flingLastY = y;
            postInvalidateOnAnimation();
            if (listener != null && scroller.isFinished()) listener.onEditorChanged();
        }
    }

    private void selectWordAt(int p) {
        int len = buf.length();
        int s = p, e = p;
        if (p < len && isWord(buf.charAt(p)) || p > 0 && isWord(buf.charAt(p - 1))) {
            while (s > 0 && isWord(buf.charAt(s - 1)) && p - s < 512) s--;
            while (e < len && isWord(buf.charAt(e)) && e - p < 512) e++;
        } else if (p < len && buf.charAt(p) != '\n') {
            e = p + 1;
        }
        anchor = s;
        cursor = e;
        showHandles = s < e;
        finishComposing();
        restartIme();
        selChanged();
        startSelectionMode();
    }

    private static boolean isWord(char c) {
        if (Character.isLetterOrDigit(c) || c == '_') return true;
        return Character.isSurrogate(c);
    }

    // ============================ 选择菜单 ============================

    private void startSelectionMode() {
        if (actionMode != null) {
            actionMode.invalidate();
            actionMode.invalidateContentRect();
            return;
        }
        actionMode = startActionMode(new ActionMode.Callback2() {
            @Override
            public boolean onCreateActionMode(ActionMode mode, Menu menu) {
                return true;
            }

            @Override
            public boolean onPrepareActionMode(ActionMode mode, Menu menu) {
                menu.clear();
                boolean sel = selStart() < selEnd();
                if (sel && !readOnly) menu.add(0, 1, 0, "剪切");
                if (sel) menu.add(0, 2, 1, "复制");
                if (!readOnly) menu.add(0, 3, 2, "粘贴");
                menu.add(0, 4, 3, "全选");
                return true;
            }

            @Override
            public boolean onActionItemClicked(ActionMode mode, MenuItem item) {
                switch (item.getItemId()) {
                    case 1:
                        cut();
                        mode.finish();
                        break;
                    case 2:
                        copy();
                        showHandles = false;
                        anchor = cursor;
                        selChanged();
                        mode.finish();
                        break;
                    case 3:
                        paste();
                        mode.finish();
                        break;
                    case 4:
                        selectAll();
                        mode.invalidate();
                        mode.invalidateContentRect();
                        break;
                }
                return true;
            }

            @Override
            public void onDestroyActionMode(ActionMode mode) {
                actionMode = null;
            }

            @Override
            public void onGetContentRect(ActionMode mode, View view, Rect outRect) {
                layoutVisible();
                float[] a = posToXY(selStart());
                float[] b = posToXY(selEnd());
                float[] p = a != null ? a : b;
                if (p == null) {
                    outRect.set(0, 0, getWidth(), (int) lineH);
                    return;
                }
                float right = b != null && a != null && b[1] == a[1] ? b[0] : p[0] + 1;
                outRect.set((int) p[0], (int) p[1], (int) Math.max(right, p[0] + 1), (int) (p[1] + lineH));
            }
        }, ActionMode.TYPE_FLOATING);
    }

    public void finishActionMode() {
        if (actionMode != null) actionMode.finish();
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        if (w != ow) {
            rowCache.clear();
            topRow = 0;
        }
        if (h < oh && h > 0) post(() -> ensureVisible(cursor, false));
    }

    @Override
    protected void onFocusChanged(boolean focused, int direction, Rect prev) {
        super.onFocusChanged(focused, direction, prev);
        invalidate();
    }

    private static int clamp(int v, int a, int b) {
        return v < a ? a : (v > b ? b : v);
    }

    private static float clampF(float v, float a, float b) {
        return v < a ? a : (v > b ? b : v);
    }
}
