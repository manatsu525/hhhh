package com.lanfile.manager.editor;

import java.nio.CharBuffer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * 在连续字符数组上查找。普通文本区分大小写时走手写的快速扫描，其余情况走正则。
 * 只读访问数组，可以在后台线程运行。
 */
public final class Searcher {

    public final String query;
    public final boolean matchCase, wholeWord, regex;
    private final Pattern pattern;
    private final char[] q;

    public Searcher(String query, boolean matchCase, boolean wholeWord, boolean regex) throws PatternSyntaxException {
        this.query = query;
        this.matchCase = matchCase;
        this.wholeWord = wholeWord;
        this.regex = regex;
        q = query.toCharArray();
        if (regex || !matchCase || wholeWord) {
            String src = regex ? query : Pattern.quote(query);
            if (wholeWord) src = "(?<![\\p{L}\\p{N}_])(?:" + src + ")(?![\\p{L}\\p{N}_])";
            int f = Pattern.MULTILINE;
            if (!matchCase) f |= Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
            pattern = Pattern.compile(src, f);
        } else pattern = null;
    }

    public boolean usable() {
        return q.length > 0;
    }

    /** 结果：{start, end}，找不到返回 null */
    public int[] next(char[] a, int len, int from) {
        if (from > len) return null;
        if (pattern == null) {
            int i = indexOf(a, len, from);
            return i < 0 ? null : new int[]{i, i + q.length};
        }
        Matcher m = pattern.matcher(CharBuffer.wrap(a, 0, len));
        int f = from;
        while (f <= len && m.find(f)) {
            if (m.end() > m.start()) return new int[]{m.start(), m.end()};
            f = m.end() + 1; // 跳过空匹配
        }
        return null;
    }

    /** 查找结束位置不超过 before 的最后一个匹配 */
    public int[] prev(char[] a, int len, int before) {
        if (pattern == null) {
            int i = lastIndexOf(a, before - q.length);
            return i < 0 ? null : new int[]{i, i + q.length};
        }
        int win = 64 * 1024;
        int hi = before;
        while (true) {
            int lo = Math.max(0, hi - win);
            Matcher m = pattern.matcher(CharBuffer.wrap(a, 0, len));
            m.region(lo, before);
            m.useTransparentBounds(true);
            m.useAnchoringBounds(false);
            int[] best = null;
            while (m.find()) {
                if (m.end() > m.start()) best = new int[]{m.start(), m.end()};
            }
            if (best != null) return best;
            if (lo == 0) return null;
            hi = lo;
            win = Math.min(win * 4, 1 << 26);
        }
    }

    /** 计数（cancel[0] 置 true 时提前结束，返回 -1） */
    public int count(char[] a, int len, boolean[] cancel) {
        int n = 0, from = 0;
        int[] r;
        while ((r = next(a, len, from)) != null) {
            n++;
            from = r[1];
            if ((n & 1023) == 0 && cancel[0]) return -1;
        }
        return n;
    }

    /** [start,end) 是否恰好是一个匹配 */
    public boolean matchesAt(char[] a, int len, int start, int end) {
        if (pattern == null) {
            if (end - start != q.length) return false;
            for (int i = 0; i < q.length; i++) if (a[start + i] != q[i]) return false;
            return true;
        }
        Matcher m = pattern.matcher(CharBuffer.wrap(a, 0, len));
        m.region(start, len);
        m.useTransparentBounds(true);
        m.useAnchoringBounds(false);
        return m.lookingAt() && m.end() == end;
    }

    /** 当前匹配 [start,end) 对应的替换文本 */
    public String replacement(char[] a, int len, int start, int end, String rep) {
        if (!regex) return rep;
        Matcher m = pattern.matcher(CharBuffer.wrap(a, 0, len));
        m.region(start, len);
        m.useTransparentBounds(true);
        m.useAnchoringBounds(false);
        if (!m.lookingAt()) return rep;
        return expand(m, rep);
    }

    /** 支持 $0-$9、${name}、\n、\t、\\、\$ */
    static String expand(Matcher m, String rep) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < rep.length(); i++) {
            char c = rep.charAt(i);
            if (c == '\\' && i + 1 < rep.length()) {
                char d = rep.charAt(++i);
                if (d == 'n') sb.append('\n');
                else if (d == 't') sb.append('\t');
                else sb.append(d);
            } else if (c == '$' && i + 1 < rep.length()) {
                char d = rep.charAt(i + 1);
                if (d >= '0' && d <= '9') {
                    int g = d - '0';
                    i++;
                    if (g <= m.groupCount() && m.group(g) != null) sb.append(m.group(g));
                } else if (d == '{') {
                    int e = rep.indexOf('}', i + 2);
                    if (e > 0) {
                        String name = rep.substring(i + 2, e);
                        try {
                            String v = android.os.Build.VERSION.SDK_INT >= 26 ? m.group(name) : null;
                            if (android.os.Build.VERSION.SDK_INT < 26) sb.append(rep, i, e + 1);
                            else if (v != null) sb.append(v);
                        } catch (IllegalArgumentException ex) {
                            sb.append(rep, i, e + 1);
                        }
                        i = e;
                    } else sb.append(c);
                } else sb.append(c);
            } else sb.append(c);
        }
        return sb.toString();
    }

    public static final class ReplaceResult {
        public char[] chars;
        public int len;
        public int count;
    }

    /** 全部替换，生成新数组（原数组不动，方便撤销） */
    public ReplaceResult replaceAll(char[] a, int len, String rep, boolean[] cancel) {
        ReplaceResult r = new ReplaceResult();
        char[] out = new char[len + 1024];
        int o = 0, last = 0;
        Matcher m = pattern == null ? null : pattern.matcher(CharBuffer.wrap(a, 0, len));
        char[] repLit = rep.toCharArray();
        int from = 0;
        while (from <= len) {
            int s, e;
            char[] rc;
            if (m == null) {
                s = indexOf(a, len, from);
                if (s < 0) break;
                e = s + q.length;
                rc = repLit;
            } else {
                if (!m.find(from)) break;
                s = m.start();
                e = m.end();
                if (e == s) {
                    from = e + 1;
                    continue;
                }
                rc = regex ? expand(m, rep).toCharArray() : repLit;
            }
            int need = o + (s - last) + rc.length;
            if (need > out.length) out = grow(out, need + (len - s));
            System.arraycopy(a, last, out, o, s - last);
            o += s - last;
            System.arraycopy(rc, 0, out, o, rc.length);
            o += rc.length;
            last = e;
            from = e;
            r.count++;
            if ((r.count & 1023) == 0 && cancel[0]) return null;
        }
        int need = o + (len - last);
        if (need + 16 > out.length) out = grow(out, need + 1024);
        System.arraycopy(a, last, out, o, len - last);
        o += len - last;
        r.chars = out;
        r.len = o;
        return r;
    }

    private static char[] grow(char[] a, int min) {
        long n = Math.max(min, (long) a.length + (a.length >> 2));
        char[] b = new char[(int) Math.min(Integer.MAX_VALUE - 64, n)];
        System.arraycopy(a, 0, b, 0, a.length);
        return b;
    }

    private int indexOf(char[] a, int len, int from) {
        int n = q.length;
        if (n == 0) return -1;
        char f = q[0];
        int max = len - n;
        for (int i = Math.max(0, from); i <= max; i++) {
            if (a[i] != f) {
                while (++i <= max && a[i] != f) ;
            }
            if (i <= max) {
                int j = i + 1, k = 1;
                while (k < n && a[j] == q[k]) {
                    j++;
                    k++;
                }
                if (k == n) return i;
            }
        }
        return -1;
    }

    private int lastIndexOf(char[] a, int from) {
        int n = q.length;
        if (n == 0) return -1;
        for (int i = from; i >= 0; i--) {
            if (a[i] != q[0]) continue;
            int k = 1;
            while (k < n && a[i + k] == q[k]) k++;
            if (k == n) return i;
        }
        return -1;
    }
}
