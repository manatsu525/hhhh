package com.lanfile.manager.archive;

import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 读取压缩包：列出内容、解压部分或全部条目。
 * <ul>
 * <li>zip（含 jar/apk 等）：zip4j，支持 AES / ZipCrypto 密码、自动识别 GBK 文件名</li>
 * <li>tar 以及 gz / bz2 / xz / lzma / Z 压缩（含 .tar.gz 等组合）：commons-compress 流式读取</li>
 * <li>其他（7z、rar/rar5、iso、cab、arj、lzh、cpio、rpm、deb、wim、dmg、xar、分卷 .001 …）：7-Zip 引擎</li>
 * </ul>
 */
public abstract class ArchiveReader implements Closeable {

    /** 解压目标。open 返回 null 表示跳过该条目 */
    public interface Sink {
        OutputStream open(ArcEntry e) throws IOException;

        /** 条目写完（ok）或失败后调用，负责关闭 out，失败时删除半截文件 */
        void close(ArcEntry e, OutputStream out, boolean ok) throws IOException;
    }

    protected final File file;
    protected final String displayName;
    protected String password;
    protected final List<ArcEntry> entries = new ArrayList<>();
    private Map<String, List<ArcEntry>> tree;

    protected ArchiveReader(File file, String displayName) {
        this.file = file;
        this.displayName = displayName;
    }

    /** 格式名称，例如 ZIP、7Z、RAR5、TAR.GZ */
    public abstract String format();

    /** 是否支持在解压前修改文件名编码（只有 zip 需要） */
    public boolean supportsCharset() {
        return false;
    }

    public void setCharset(String charsetOrNullForAuto) {
    }

    public String charset() {
        return null;
    }

    /** 解压 files 中的文件条目（目录条目会被忽略）。按压缩包内的顺序调用 sink */
    public abstract void extract(Collection<ArcEntry> files, Sink sink, Progress p) throws IOException;

    public void setPassword(String pw) {
        this.password = pw == null || pw.isEmpty() ? null : pw;
    }

    public String password() {
        return password;
    }

    public boolean hasEncrypted() {
        for (ArcEntry e : entries) if (e.encrypted) return true;
        return false;
    }

    public List<ArcEntry> entries() {
        return entries;
    }

    public File file() {
        return file;
    }

    // ======================= 目录树 =======================

    /** 列出压缩包内某个目录（"" 为根目录）的直接子项，自动补全压缩包里没单独记录的中间目录 */
    public synchronized List<ArcEntry> list(String dir) {
        if (tree == null) buildTree();
        List<ArcEntry> l = tree.get(dir);
        return l == null ? new ArrayList<ArcEntry>() : l;
    }

    protected synchronized void invalidateTree() {
        tree = null;
    }

    private void buildTree() {
        tree = new HashMap<>();
        Map<String, ArcEntry> dirs = new LinkedHashMap<>();
        Map<String, ArcEntry> files = new LinkedHashMap<>();
        for (ArcEntry e : entries) {
            if (e.path.isEmpty()) continue;
            if (e.dir) {
                ArcEntry old = dirs.get(e.path);
                if (old == null || old.implicit) dirs.put(e.path, e);
            } else {
                files.put(e.path, e);   // 同名条目以后出现的为准（与解压结果一致）
            }
            String p = e.parent();
            while (!p.isEmpty() && !dirs.containsKey(p)) {
                ArcEntry d = new ArcEntry();
                d.path = p;
                d.dir = true;
                d.implicit = true;
                dirs.put(p, d);
                p = d.parent();
            }
        }
        for (ArcEntry d : dirs.values()) child(d);
        for (ArcEntry f : files.values()) {
            if (dirs.containsKey(f.path)) continue;   // 同名的文件和目录，只保留目录
            child(f);
        }
    }

    private void child(ArcEntry e) {
        String p = e.parent();
        List<ArcEntry> l = tree.get(p);
        if (l == null) tree.put(p, l = new ArrayList<>());
        l.add(e);
    }

    /** 某目录下（递归）的所有条目 */
    public List<ArcEntry> under(String dir) {
        List<ArcEntry> out = new ArrayList<>();
        String pre = dir.isEmpty() ? "" : dir + "/";
        for (ArcEntry e : entries) {
            if (e.path.isEmpty()) continue;
            if (pre.isEmpty() || e.path.startsWith(pre) || e.path.equals(dir)) out.add(e);
        }
        return out;
    }

    // ======================= 打开 =======================

    public static ArchiveReader open(File f, String displayName) throws IOException {
        return open(f, displayName, null);
    }

    public static ArchiveReader open(File f, String displayName, String password) throws IOException {
        if (displayName == null) displayName = f.getName();
        byte[] h = new byte[1024];
        int n = readHead(f, h);
        String ext = Formats.ext(displayName);

        if (n >= 4 && h[0] == 'P' && h[1] == 'K' && (h[2] == 3 || h[2] == 5 || h[2] == 7) && !ext.equals("zipx")) {
            try {
                ZipReader z = new ZipReader(f, displayName);
                z.setPassword(password);
                return z;
            } catch (PasswordException e) {
                throw e;
            } catch (IOException | RuntimeException e) {
                // zip4j 不支持的压缩方式（如 deflate64、lzma、ppmd），交给 7-Zip
            }
        }
        int comp = StreamReader.detect(h, n, ext);
        if (comp == StreamReader.ZSTD) {
            throw new IOException("暂不支持 Zstandard（.zst）格式");
        }
        if (comp != StreamReader.NONE || StreamReader.isTar(h, n)) {
            StreamReader s = new StreamReader(f, displayName, comp);
            s.load();
            return s;
        }
        if (!SevenZipLib.available()) {
            throw new IOException("无法识别的压缩格式（7-Zip 引擎加载失败：" + SevenZipLib.error() + "）");
        }
        NativeReader r = new NativeReader(f, displayName);
        r.setPassword(password);
        r.load();
        return r;
    }

    private static int readHead(File f, byte[] h) throws IOException {
        try (InputStream in = new FileInputStream(f)) {
            int n = 0;
            while (n < h.length) {
                int k = in.read(h, n, h.length - n);
                if (k < 0) break;
                n += k;
            }
            return n;
        }
    }

    protected static void copy(InputStream in, OutputStream out, Progress p) throws IOException {
        byte[] buf = new byte[128 * 1024];
        int k;
        while ((k = in.read(buf)) > 0) {
            p.check();
            out.write(buf, 0, k);
            p.done += k;
        }
    }
}
