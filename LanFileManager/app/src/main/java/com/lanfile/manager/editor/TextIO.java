package com.lanfile.manager.editor;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CoderResult;
import java.nio.charset.CodingErrorAction;
import java.util.concurrent.atomic.AtomicBoolean;

/** 流式读写文本：编码识别、换行符识别与转换。全程不产生任何临时文件。 */
public final class TextIO {

    public static final int EOL_LF = 0, EOL_CRLF = 1, EOL_CR = 2;
    public static final String[] EOL_NAMES = {"LF (Unix)", "CRLF (Windows)", "CR (旧 Mac)"};
    public static final String[] EOL_SHORT = {"LF", "CRLF", "CR"};

    /** 编辑器里可选的编码 */
    public static final String[] CHARSETS = {"UTF-8", "GB18030", "GBK", "Big5", "UTF-16LE", "UTF-16BE", "Shift_JIS", "EUC-KR", "ISO-8859-1", "windows-1252"};

    public interface Progress {
        /** @return false 表示取消 */
        boolean onProgress(long done, long total);
    }

    public static final class Loaded {
        public char[] chars;
        public int len;
        public int[] lineStarts;
        public int lineCount;
        public String charset;
        public boolean bom;
        public int eol = EOL_LF;
        /** 读取时遇到了无法解码的字节（已替换为 �） */
        public boolean malformed;
        public boolean binaryLike;
    }

    public static final class TooLargeException extends IOException {
        public TooLargeException(String m) {
            super(m);
        }
    }

    public static final class CancelledException extends IOException {
        public CancelledException() {
            super("已取消");
        }
    }

    private static final int CHUNK = 256 * 1024;

    /** 当前进程还能安全地给文本用多少个 char */
    public static long charBudget() {
        Runtime rt = Runtime.getRuntime();
        long free = rt.maxMemory() - (rt.totalMemory() - rt.freeMemory());
        // 留出余量给界面、撤销记录、查找替换时的临时数组
        return Math.max(0, (free - 24L * 1024 * 1024) / 2 / 2);
    }

    /**
     * @param size    文件字节数，未知时 -1
     * @param forced  指定编码，null 表示自动识别
     */
    public static Loaded load(InputStream in, long size, String forced, Progress p) throws IOException {
        byte[] head = new byte[64 * 1024];
        int hn = readFully(in, head);
        Loaded r = new Loaded();
        int skip = 0;
        String cs = forced;
        if (hn >= 3 && (head[0] & 0xFF) == 0xEF && (head[1] & 0xFF) == 0xBB && (head[2] & 0xFF) == 0xBF) {
            if (cs == null || cs.equals("UTF-8")) {
                cs = "UTF-8";
                r.bom = true;
                skip = 3;
            }
        } else if (hn >= 2 && (head[0] & 0xFF) == 0xFF && (head[1] & 0xFF) == 0xFE) {
            if (cs == null || cs.equals("UTF-16LE")) {
                cs = "UTF-16LE";
                r.bom = true;
                skip = 2;
            }
        } else if (hn >= 2 && (head[0] & 0xFF) == 0xFE && (head[1] & 0xFF) == 0xFF) {
            if (cs == null || cs.equals("UTF-16BE")) {
                cs = "UTF-16BE";
                r.bom = true;
                skip = 2;
            }
        }
        if (cs == null) cs = detect(head, hn, hn < head.length);
        r.charset = cs;
        r.binaryLike = looksBinary(head, hn, cs);

        boolean wide = cs.startsWith("UTF-16");
        long est;
        if (size < 0) est = 1 << 16;
        else if (wide) est = size / 2 + 16;
        else if (hn - skip > 0 && size > hn) {
            // 用开头的样本估算"字符/字节"比例（中文 UTF-8 约 1/3），避免按字节数分配浪费内存
            int sc = new String(head, skip, hn - skip, Charset.forName(cs)).length();
            double ratio = Math.min(1.0, (double) sc / (hn - skip));
            est = (long) (size * Math.min(1.0, ratio * 1.15 + 0.02)) + 4096;
        } else est = size + 16;
        long budget = charBudget();
        if (est > budget) {
            throw new TooLargeException("文件太大，超出可用内存（约可打开 " + (budget / (1024 * 1024)) + " M 字符）");
        }
        char[] out = new char[(int) Math.min(Integer.MAX_VALUE - 64, est + Math.min(est / 16, 8L << 20) + 1024)];
        int len = 0;
        int[] lsArr = new int[(int) Math.max(64, Math.min(est / 40, 1 << 22))];
        int lc = 0;
        lsArr[lc++] = 0;
        int crlf = 0, lf = 0, cr = 0;
        boolean lastCR = false;

        CharsetDecoder dec = Charset.forName(cs).newDecoder()
                .onMalformedInput(CodingErrorAction.REPLACE).onUnmappableCharacter(CodingErrorAction.REPLACE);
        ByteBuffer bb = ByteBuffer.allocate(CHUNK + 16);
        CharBuffer cb = CharBuffer.allocate(CHUNK + 16);
        bb.put(head, skip, hn - skip);
        long done = hn;
        boolean eof = hn < head.length;
        byte[] rd = new byte[CHUNK];
        while (true) {
            if (!eof && bb.remaining() > 0) {
                int want = Math.min(bb.remaining(), rd.length);
                int n = in.read(rd, 0, want);
                if (n < 0) eof = true;
                else {
                    bb.put(rd, 0, n);
                    done += n;
                }
            }
            bb.flip();
            CoderResult res = dec.decode(bb, cb, eof);
            bb.compact();
            if (eof && bb.position() == 0) {
                dec.flush(cb);
            }
            // cb -> out
            cb.flip();
            int n = cb.remaining();
            if (len + n + 2 > out.length) {
                long nl = (long) out.length + Math.max(n + 1024, out.length / 2);
                if (nl - out.length > charBudget()) throw new TooLargeException("文件太大，超出可用内存");
                char[] o2 = new char[(int) Math.min(Integer.MAX_VALUE - 64, nl)];
                System.arraycopy(out, 0, o2, 0, len);
                out = o2;
            }
            char[] ca = cb.array();
            int off = cb.position();
            for (int i = 0; i < n; i++) {
                char c = ca[off + i];
                if (c == '\r') {
                    cr++;
                    out[len++] = '\n';
                    if (lc == lsArr.length) lsArr = growInts(lsArr);
                    lsArr[lc++] = len;
                    lastCR = true;
                    continue;
                }
                if (c == '\n') {
                    if (lastCR) {
                        crlf++;
                        cr--;
                        lastCR = false;
                        continue;
                    }
                    lf++;
                    out[len++] = '\n';
                    if (lc == lsArr.length) lsArr = growInts(lsArr);
                    lsArr[lc++] = len;
                    continue;
                }
                lastCR = false;
                if (c == '\uFFFD') r.malformed = true;
                out[len++] = c;
            }
            cb.clear();
            if (p != null && !p.onProgress(done, size)) throw new CancelledException();
            if (eof && bb.position() == 0 && res.isUnderflow()) break;
            if (eof && bb.position() > 0 && res.isUnderflow()) {
                // 末尾残缺的字节
                r.malformed = true;
                break;
            }
        }
        if (crlf >= lf && crlf >= cr && crlf > 0) r.eol = EOL_CRLF;
        else if (cr > lf && cr > crlf) r.eol = EOL_CR;
        else r.eol = EOL_LF;
        r.chars = out;
        r.len = len;
        r.lineStarts = lsArr;
        r.lineCount = lc;
        return r;
    }

    private static int[] growInts(int[] a) {
        int[] n = new int[a.length + (a.length >> 1) + 16];
        System.arraycopy(a, 0, n, 0, a.length);
        return n;
    }

    private static int readFully(InputStream in, byte[] b) throws IOException {
        int n = 0;
        while (n < b.length) {
            int k = in.read(b, n, b.length - n);
            if (k < 0) break;
            n += k;
        }
        return n;
    }

    /** 自动识别：合法 UTF-8 → UTF-8；看起来像无 BOM 的 UTF-16 → UTF-16；否则按中文环境用 GB18030 */
    static String detect(byte[] b, int n, boolean complete) {
        if (n == 0) return "UTF-8";
        // 无 BOM 的 UTF-16：大量 0 字节集中在奇数位或偶数位
        if (n >= 64) {
            int ze = 0, zo = 0;
            int m = Math.min(n, 8192) & ~1;
            for (int i = 0; i < m; i += 2) {
                if (b[i] == 0) ze++;
                if (b[i + 1] == 0) zo++;
            }
            int half = m / 2;
            if (zo > half * 0.3 && ze < half * 0.02) return "UTF-16LE";
            if (ze > half * 0.3 && zo < half * 0.02) return "UTF-16BE";
        }
        boolean nonAscii = false;
        int i = 0;
        while (i < n) {
            int c = b[i] & 0xFF;
            if (c < 0x80) {
                i++;
                continue;
            }
            nonAscii = true;
            int k;
            if (c >= 0xC2 && c <= 0xDF) k = 1;
            else if (c >= 0xE0 && c <= 0xEF) k = 2;
            else if (c >= 0xF0 && c <= 0xF4) k = 3;
            else return "GB18030";
            if (i + k >= n) {
                // 采样末尾被截断的多字节字符，不算错误
                if (!complete) break;
                return "GB18030";
            }
            for (int j = 1; j <= k; j++) {
                if ((b[i + j] & 0xC0) != 0x80) return "GB18030";
            }
            i += k + 1;
        }
        return "UTF-8";
    }

    private static boolean looksBinary(byte[] b, int n, String cs) {
        if (cs.startsWith("UTF-16")) return false;
        int z = 0;
        int m = Math.min(n, 8192);
        for (int i = 0; i < m; i++) if (b[i] == 0) z++;
        return z > 0 && z * 100 / Math.max(1, m) >= 1;
    }

    // ============================ 保存 ============================

    /**
     * 把 chars[0, len) 按指定编码和换行符写出。遇到当前编码无法表示的字符会报错（不会悄悄变成问号）。
     */
    public static void save(char[] chars, int len, OutputStream os, String cs, boolean bom, int eol, Progress p) throws IOException {
        Charset charset = Charset.forName(cs);
        if (bom) {
            if (cs.equals("UTF-8")) os.write(new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF});
            else if (cs.equals("UTF-16LE")) os.write(new byte[]{(byte) 0xFF, (byte) 0xFE});
            else if (cs.equals("UTF-16BE")) os.write(new byte[]{(byte) 0xFE, (byte) 0xFF});
        }
        CharsetEncoder enc = charset.newEncoder()
                .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT);
        char[] stage = new char[CHUNK * 2 + 4];
        CharBuffer in = CharBuffer.wrap(stage);
        ByteBuffer out = ByteBuffer.allocate(CHUNK * 4 + 64);
        int pos = 0;
        int carry = 0; // 上一块末尾遗留在 stage 开头的字符数（被拆开的代理对）
        while (pos < len || carry > 0) {
            int sn = carry;
            int end = Math.min(len, pos + CHUNK);
            for (int i = pos; i < end; i++) {
                char c = chars[i];
                if (c == '\n') {
                    if (eol == EOL_CRLF) {
                        stage[sn++] = '\r';
                        stage[sn++] = '\n';
                    } else if (eol == EOL_CR) {
                        stage[sn++] = '\r';
                    } else stage[sn++] = '\n';
                } else stage[sn++] = c;
            }
            boolean last = end >= len;
            in.limit(sn);
            in.position(0);
            while (true) {
                CoderResult res = enc.encode(in, out, last);
                if (res.isOverflow()) {
                    os.write(out.array(), 0, out.position());
                    out.clear();
                    continue;
                }
                if (res.isError()) {
                    int bad = pos + Math.max(0, in.position() - carry);
                    throw new UnmappableException(bad, cs);
                }
                break;
            }
            if (last) {
                while (enc.flush(out).isOverflow()) {
                    os.write(out.array(), 0, out.position());
                    out.clear();
                }
            }
            os.write(out.array(), 0, out.position());
            out.clear();
            carry = in.remaining();
            if (carry > 0) {
                System.arraycopy(stage, in.position(), stage, 0, carry);
                if (last) throw new UnmappableException(len - 1, cs);
            }
            pos = end;
            if (p != null && !p.onProgress(pos, len)) throw new CancelledException();
        }
        os.flush();
    }

    public static final class UnmappableException extends CharacterCodingException {
        public final int offset;
        public final String charset;

        UnmappableException(int offset, String charset) {
            this.offset = offset;
            this.charset = charset;
        }

        @Override
        public String getMessage() {
            return "文本中有 " + charset + " 编码无法表示的字符，请改用 UTF-8 保存";
        }
    }

    public static final AtomicBoolean NONE = new AtomicBoolean();
}
