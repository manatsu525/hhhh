package com.lanfile.manager.editor;

/**
 * 大文本缓冲区：字符用 gap buffer 存储，行首位置用带"相对末尾"编码的 gap 数组存储。
 * 插入/删除只移动编辑点附近的数据，和文件大小无关，几百 MB 的文本也能流畅输入。
 * 内部统一用 '\n' 作为换行符，保存时再转换成原文件的换行格式。
 * 非线程安全：只在 UI 线程修改；后台线程只在持有 snapshot 时读取。
 */
public final class TextBuffer implements CharSequence {

    private char[] buf;
    private int gapStart, gapEnd; // 字符 gap：[gapStart, gapEnd)

    // 行首索引。第 0 行永远从 0 开始。
    // gap 之前的条目是绝对位置；gap 之后的条目存 (位置 - length)，因此前面插入/删除时无需逐个修改。
    private int[] ls;
    private int lgs, lge; // 行 gap：[lgs, lge)

    private int modCount;

    public TextBuffer() {
        this(new char[64], 0, null, 0);
    }

    /**
     * @param chars     文本，前 len 个字符有效（数组会被接管）
     * @param lineStarts 行首数组（可为 null，会自动计算），前 lineCount 个有效（数组会被接管）
     */
    public TextBuffer(char[] chars, int len, int[] lineStarts, int lineCount) {
        if (chars.length < len + 16) {
            char[] n = new char[len + Math.max(16, len / 8)];
            System.arraycopy(chars, 0, n, 0, len);
            chars = n;
        }
        buf = chars;
        gapStart = len;
        gapEnd = chars.length;
        if (lineStarts == null) {
            lineStarts = new int[Math.max(16, len / 32)];
            lineCount = 0;
            lineStarts[lineCount++] = 0;
            for (int i = 0; i < len; i++) {
                if (chars[i] == '\n') {
                    if (lineCount == lineStarts.length) lineStarts = grow(lineStarts, lineCount);
                    lineStarts[lineCount++] = i + 1;
                }
            }
        }
        if (lineStarts.length < lineCount + 8) lineStarts = grow(lineStarts, lineCount);
        ls = lineStarts;
        lgs = lineCount;
        lge = ls.length;
    }

    private static int[] grow(int[] a, int used) {
        int[] n = new int[Math.max(16, used + (used >> 1) + 8)];
        System.arraycopy(a, 0, n, 0, used);
        return n;
    }

    public int modCount() {
        return modCount;
    }

    // ========================== 字符 ==========================

    @Override
    public int length() {
        return buf.length - (gapEnd - gapStart);
    }

    @Override
    public char charAt(int i) {
        return i < gapStart ? buf[i] : buf[i + gapEnd - gapStart];
    }

    /** 把 [start, end) 复制到 dst[dstOff..] */
    public void getChars(int start, int end, char[] dst, int dstOff) {
        if (end <= gapStart) {
            System.arraycopy(buf, start, dst, dstOff, end - start);
        } else if (start >= gapStart) {
            System.arraycopy(buf, start + gapEnd - gapStart, dst, dstOff, end - start);
        } else {
            int a = gapStart - start;
            System.arraycopy(buf, start, dst, dstOff, a);
            System.arraycopy(buf, gapEnd, dst, dstOff + a, end - gapStart);
        }
    }

    @Override
    public CharSequence subSequence(int start, int end) {
        return substring(start, end);
    }

    public String substring(int start, int end) {
        char[] c = new char[end - start];
        getChars(start, end, c, 0);
        return new String(c);
    }

    @Override
    public String toString() {
        return substring(0, length());
    }

    /**
     * 把 gap 移到末尾，返回底层数组：[0, length()) 连续有效。
     * 用于查找、保存这类需要整体扫描的操作。返回的数组在下次修改前有效。
     */
    public char[] compact() {
        moveGap(length());
        return buf;
    }

    private void moveGap(int pos) {
        if (pos == gapStart) return;
        int gl = gapEnd - gapStart;
        if (pos < gapStart) {
            System.arraycopy(buf, pos, buf, pos + gl, gapStart - pos);
        } else {
            System.arraycopy(buf, gapEnd, buf, gapStart, pos - gapStart);
        }
        gapStart = pos;
        gapEnd = pos + gl;
    }

    private void ensureGap(int need) {
        if (gapEnd - gapStart >= need) return;
        int len = length();
        long want = (long) len + need + Math.max(1024, len / 16);
        if (want > Integer.MAX_VALUE - 16) throw new OutOfMemoryError("文本太大");
        char[] n = new char[(int) want];
        System.arraycopy(buf, 0, n, 0, gapStart);
        int tail = buf.length - gapEnd;
        System.arraycopy(buf, gapEnd, n, n.length - tail, tail);
        gapEnd = n.length - tail;
        buf = n;
    }

    // ========================== 行 ==========================

    public int lineCount() {
        return ls.length - (lge - lgs);
    }

    public int lineStart(int line) {
        return line < lgs ? ls[line] : ls[line + lge - lgs] + length();
    }

    /** 不含 '\n' 的行尾 */
    public int lineEnd(int line) {
        if (line + 1 < lineCount()) return lineStart(line + 1) - 1;
        return length();
    }

    public int lineOf(int pos) {
        int lo = 0, hi = lineCount() - 1;
        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;
            if (lineStart(mid) <= pos) lo = mid;
            else hi = mid - 1;
        }
        return lo;
    }

    /** 行 gap 移到 index 处（gap 之前有 index 个条目） */
    private void moveLineGap(int index) {
        if (index == lgs) return;
        int len = length();
        int gl = lge - lgs;
        if (index < lgs) {
            for (int i = lgs - 1; i >= index; i--) ls[i + gl] = ls[i] - len;
        } else {
            for (int i = lgs; i < index; i++) ls[i] = ls[i + gl] + len;
        }
        lgs = index;
        lge = index + gl;
    }

    private void ensureLineGap(int need) {
        if (lge - lgs >= need) return;
        int used = lineCount();
        int[] n = new int[Math.max(16, used + need + (used >> 2) + 8)];
        System.arraycopy(ls, 0, n, 0, lgs);
        int tail = ls.length - lge;
        System.arraycopy(ls, lge, n, n.length - tail, tail);
        lge = n.length - tail;
        ls = n;
    }

    // ========================== 修改 ==========================

    public void insert(int pos, CharSequence s) {
        int n = s.length();
        if (n == 0) return;
        int line = lineOf(pos);
        moveLineGap(line + 1);
        int nl = 0;
        for (int i = 0; i < n; i++) if (s.charAt(i) == '\n') nl++;
        if (nl > 0) ensureLineGap(nl);
        ensureGap(n);
        moveGap(pos);
        for (int i = 0; i < n; i++) {
            char c = s.charAt(i);
            buf[gapStart++] = c;
            if (c == '\n') ls[lgs++] = pos + i + 1;
        }
        modCount++;
    }

    public void delete(int start, int end) {
        if (end <= start) return;
        int line = lineOf(start);
        moveLineGap(line + 1);
        int len = length();
        // 删除行首位于 (start, end] 的行
        while (lge < ls.length && ls[lge] + len <= end) lge++;
        moveGap(start);
        gapEnd += end - start;
        modCount++;
    }

    public void replace(int start, int end, CharSequence s) {
        delete(start, end);
        insert(start, s);
    }
}
