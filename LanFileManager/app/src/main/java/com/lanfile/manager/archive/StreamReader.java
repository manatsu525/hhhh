package com.lanfile.manager.archive;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import org.apache.commons.compress.compressors.lzma.LZMACompressorInputStream;
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream;
import org.apache.commons.compress.compressors.z.ZCompressorInputStream;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

/**
 * tar，以及 gzip / bzip2 / xz / lzma / Z 压缩的单个文件或 tar 包（.tar.gz、.tgz、.tar.xz …）。
 * 这些格式只能顺序读，列目录和解压各需要完整读一遍。
 */
final class StreamReader extends ArchiveReader {
    static final int NONE = 0, GZ = 1, BZ2 = 2, XZ = 3, LZMA = 4, Z = 5, ZSTD = 6;
    private static final String[] NAMES = {"", "GZ", "BZ2", "XZ", "LZMA", "Z", "ZST"};

    private final int comp;
    private boolean tar;

    StreamReader(File f, String displayName, int comp) {
        super(f, displayName);
        this.comp = comp;
    }

    static int detect(byte[] h, int n, String ext) {
        if (n >= 2 && (h[0] & 0xff) == 0x1f && (h[1] & 0xff) == 0x8b) return GZ;
        if (n >= 3 && h[0] == 'B' && h[1] == 'Z' && h[2] == 'h') return BZ2;
        if (n >= 6 && (h[0] & 0xff) == 0xfd && h[1] == '7' && h[2] == 'z' && h[3] == 'X' && h[4] == 'Z' && h[5] == 0) return XZ;
        if (n >= 2 && (h[0] & 0xff) == 0x1f && (h[1] & 0xff) == 0x9d) return Z;
        if (n >= 4 && (h[0] & 0xff) == 0x28 && (h[1] & 0xff) == 0xb5 && (h[2] & 0xff) == 0x2f && (h[3] & 0xff) == 0xfd) return ZSTD;
        // lzma（alone）没有魔数，只能看扩展名 + 头部特征
        if ((ext.equals("lzma") || ext.equals("tlz")) && n >= 13 && (h[0] & 0xff) < 225) return LZMA;
        return NONE;
    }

    static boolean isTar(byte[] h, int n) {
        return n >= 512 && TarArchiveInputStream.matches(h, n);
    }

    @Override
    public String format() {
        if (comp == NONE) return "TAR";
        return tar ? "TAR." + NAMES[comp] : NAMES[comp];
    }

    private InputStream openRaw() throws IOException {
        InputStream in = new BufferedInputStream(new FileInputStream(file), 256 * 1024);
        try {
            switch (comp) {
                case GZ: return new GzipCompressorInputStream(in, true);
                case BZ2: return new BZip2CompressorInputStream(in, true);
                case XZ: return new XZCompressorInputStream(in, true);
                case LZMA: return new LZMACompressorInputStream(in);
                case Z: return new ZCompressorInputStream(in);
                default: return in;
            }
        } catch (IOException | RuntimeException e) {
            in.close();
            throw new IOException("压缩数据损坏或格式不正确：" + e.getMessage(), e);
        }
    }

    private TarArchiveInputStream openTar() throws IOException {
        return new TarArchiveInputStream(new BufferedInputStream(openRaw(), 64 * 1024), "UTF-8");
    }

    void load() throws IOException {
        if (comp == NONE) {
            tar = true;
        } else {
            byte[] h = new byte[512];
            int n = 0;
            try (InputStream in = openRaw()) {
                while (n < h.length) {
                    int k = in.read(h, n, h.length - n);
                    if (k < 0) break;
                    n += k;
                }
            }
            tar = isTar(h, n);
        }
        if (!tar) {
            ArcEntry e = new ArcEntry();
            e.path = Formats.cleanPath(Formats.singleName(displayName), false);
            if (e.path.isEmpty()) e.path = "data";
            e.mtime = file.lastModified();
            e.packed = file.length();
            if (comp == GZ) e.size = gzipSize();
            entries.add(e);
            return;
        }
        try (TarArchiveInputStream t = openTar()) {
            TarArchiveEntry te;
            int i = 0;
            while ((te = t.getNextEntry()) != null) {
                if (!te.isDirectory() && !te.isFile()) continue;   // 链接、设备文件等不处理
                ArcEntry e = new ArcEntry();
                e.path = Formats.cleanPath(te.getName(), false);
                if (e.path.isEmpty()) continue;
                e.index = i++;   // 与 extract 中的计数方式保持一致
                e.dir = te.isDirectory();
                e.size = e.dir ? 0 : te.getSize();
                e.mtime = te.getModTime() == null ? 0 : te.getModTime().getTime();
                entries.add(e);
            }
        }
    }

    /** gzip 末尾 4 字节是原始大小（模 4GB），只在不超过 4GB 时可信 */
    private long gzipSize() {
        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            long len = raf.length();
            if (len < 18) return -1;
            raf.seek(len - 4);
            long v = (raf.read() & 0xff) | (raf.read() & 0xff) << 8 | (raf.read() & 0xff) << 16 | ((long) (raf.read() & 0xff)) << 24;
            return v >= len / 2 || len < 1024 ? v : -1;
        } catch (IOException e) {
            return -1;
        }
    }

    @Override
    public void extract(Collection<ArcEntry> files, Sink sink, Progress p) throws IOException {
        if (!tar) {
            ArcEntry e = entries.get(0);
            if (!files.contains(e)) return;
            p.current = e.path;
            OutputStream out = sink.open(e);
            if (out == null) return;
            boolean ok = false;
            try (InputStream in = openRaw()) {
                copy(in, out, p);
                ok = true;
            } finally {
                sink.close(e, out, ok);
            }
            p.count++;
            return;
        }
        Map<Integer, ArcEntry> want = new HashMap<>();
        for (ArcEntry e : files) if (!e.dir && e.index >= 0) want.put(e.index, e);
        if (want.isEmpty()) return;
        try (TarArchiveInputStream t = openTar()) {
            TarArchiveEntry te;
            int i = 0;
            while ((te = t.getNextEntry()) != null) {
                p.check();
                if (!te.isDirectory() && !te.isFile()) continue;
                if (Formats.cleanPath(te.getName(), false).isEmpty()) continue;
                ArcEntry e = want.remove(i++);
                if (e == null) continue;
                p.current = e.path;
                OutputStream out = sink.open(e);
                if (out != null) {
                    boolean ok = false;
                    try {
                        copy(t, out, p);
                        ok = true;
                    } finally {
                        sink.close(e, out, ok);
                    }
                    p.count++;
                }
                if (want.isEmpty()) break;
            }
        }
    }

    @Override
    public void close() {
    }
}
