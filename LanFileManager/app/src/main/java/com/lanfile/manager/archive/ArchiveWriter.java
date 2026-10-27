package com.lanfile.manager.archive;

import com.lanfile.manager.core.Vfs;

import net.lingala.zip4j.io.outputstream.ZipOutputStream;
import net.lingala.zip4j.model.ZipParameters;
import net.lingala.zip4j.model.enums.AesKeyStrength;
import net.lingala.zip4j.model.enums.CompressionLevel;
import net.lingala.zip4j.model.enums.CompressionMethod;
import net.lingala.zip4j.model.enums.EncryptionMethod;
import net.sf.sevenzipjbinding.ICryptoGetTextPassword;
import net.sf.sevenzipjbinding.IOutCreateArchive7z;
import net.sf.sevenzipjbinding.IOutCreateCallback;
import net.sf.sevenzipjbinding.IOutItem7z;
import net.sf.sevenzipjbinding.ISequentialInStream;
import net.sf.sevenzipjbinding.SevenZip;
import net.sf.sevenzipjbinding.SevenZipException;
import net.sf.sevenzipjbinding.impl.OutItemFactory;
import net.sf.sevenzipjbinding.impl.RandomAccessFileOutStream;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;
import org.apache.commons.compress.compressors.gzip.GzipParameters;
import org.apache.commons.compress.compressors.xz.XZCompressorOutputStream;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/** 创建压缩包：zip（可 AES-256 加密）、7z（可加密，可加密文件名）、tar、tar.gz、tar.bz2、tar.xz */
public final class ArchiveWriter {
    private ArchiveWriter() {}

    public enum Format {
        ZIP("zip", "ZIP（通用，兼容性最好）", true),
        SEVEN_Z("7z", "7Z（压缩率高）", true),
        TAR_GZ("tar.gz", "TAR.GZ（Linux 常用）", false),
        TAR_XZ("tar.xz", "TAR.XZ（压缩率高）", false),
        TAR_BZ2("tar.bz2", "TAR.BZ2", false),
        TAR("tar", "TAR（仅打包，不压缩）", false);

        public final String ext;
        public final String label;
        public final boolean password;

        Format(String ext, String label, boolean password) {
            this.ext = ext;
            this.label = label;
            this.password = password;
        }
    }

    /** 压缩级别 */
    public static final int STORE = 0, FAST = 1, NORMAL = 2, MAX = 3;

    public static final class Options {
        public Format format = Format.ZIP;
        public int level = NORMAL;
        public String password;
        /** 7z：同时加密文件名（不输入密码就看不到压缩包里有什么） */
        public boolean encryptNames;
    }

    /** 要压缩的一项。name 为在压缩包里的路径 */
    public static final class Item {
        public String src;
        public String name;
        public boolean dir;
        public long size;
        public long mtime;
    }

    /** 递归收集要压缩的文件，每个选中项以自己的名字作为压缩包里的顶层 */
    public static List<Item> collect(Vfs vfs, List<Vfs.Entry> roots, Progress p) throws IOException {
        List<Item> out = new ArrayList<>();
        for (Vfs.Entry e : roots) add(vfs, e, e.name, out, p, 0);
        return out;
    }

    private static void add(Vfs vfs, Vfs.Entry e, String name, List<Item> out, Progress p, int depth) throws IOException {
        p.check();
        if (depth > 128) throw new IOException("目录层级太深：" + name);
        Item it = new Item();
        it.src = e.path;
        it.name = name;
        it.dir = e.dir;
        it.size = e.dir ? 0 : e.size;
        it.mtime = e.mtime;
        out.add(it);
        p.current = name;
        if (e.dir) {
            for (Vfs.Entry k : vfs.list(e.path)) add(vfs, k, name + "/" + k.name, out, p, depth + 1);
        }
    }

    public static long totalSize(List<Item> items) {
        long t = 0;
        for (Item it : items) t += it.size;
        return t;
    }

    /** zip、tar 系列：直接写入输出流（写完会关闭 out） */
    public static void write(Vfs vfs, List<Item> items, Options o, OutputStream out, Progress p) throws IOException {
        OutputStream bout = new BufferedOutputStream(out, 256 * 1024);
        switch (o.format) {
            case ZIP:
                writeZip(vfs, items, o, bout, p);
                break;
            case TAR:
            case TAR_GZ:
            case TAR_BZ2:
            case TAR_XZ:
                writeTar(vfs, items, o, bout, p);
                break;
            default:
                bout.close();
                throw new IllegalArgumentException("7z 需要可随机写入的文件");
        }
    }

    private static void writeZip(Vfs vfs, List<Item> items, Options o, OutputStream out, Progress p) throws IOException {
        char[] pw = o.password == null || o.password.isEmpty() ? null : o.password.toCharArray();
        try (ZipOutputStream zos = pw == null ? new ZipOutputStream(out) : new ZipOutputStream(out, pw)) {
            for (Item it : items) {
                p.check();
                p.current = it.name;
                ZipParameters zp = new ZipParameters();
                zp.setFileNameInZip(it.dir ? it.name + "/" : it.name);
                zp.setLastModifiedFileTime(it.mtime > 0 ? it.mtime : System.currentTimeMillis());
                if (it.dir) {
                    zp.setCompressionMethod(CompressionMethod.STORE);
                    zos.putNextEntry(zp);
                    zos.closeEntry();
                    continue;
                }
                if (o.level == STORE) {
                    zp.setCompressionMethod(CompressionMethod.STORE);
                } else {
                    zp.setCompressionMethod(CompressionMethod.DEFLATE);
                    zp.setCompressionLevel(o.level == FAST ? CompressionLevel.FASTEST
                            : o.level == MAX ? CompressionLevel.ULTRA : CompressionLevel.NORMAL);
                }
                zp.setEntrySize(it.size);
                if (pw != null) {
                    zp.setEncryptFiles(true);
                    zp.setEncryptionMethod(EncryptionMethod.AES);
                    zp.setAesKeyStrength(AesKeyStrength.KEY_STRENGTH_256);
                }
                zos.putNextEntry(zp);
                try (InputStream in = vfs.openRead(it.src, 0)) {
                    ArchiveReader.copy(in, zos, p);
                }
                zos.closeEntry();
                p.count++;
            }
        }
    }

    private static void writeTar(Vfs vfs, List<Item> items, Options o, OutputStream out, Progress p) throws IOException {
        OutputStream comp;
        switch (o.format) {
            case TAR_GZ: {
                GzipParameters gp = new GzipParameters();
                gp.setCompressionLevel(o.level == STORE ? 0 : o.level == FAST ? 1 : o.level == MAX ? 9 : 6);
                comp = new GzipCompressorOutputStream(out, gp);
                break;
            }
            case TAR_BZ2:
                comp = new BZip2CompressorOutputStream(out, o.level == FAST || o.level == STORE ? 1 : 9);
                break;
            case TAR_XZ:
                // xz 预设越高越占内存（-6 约 94MB，-9 约 674MB），手机上最高用到 6
                comp = new XZCompressorOutputStream(out, o.level == STORE ? 0 : o.level == FAST ? 1 : o.level == MAX ? 6 : 3);
                break;
            default:
                comp = out;
        }
        try (TarArchiveOutputStream tos = new TarArchiveOutputStream(comp, "UTF-8")) {
            tos.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);
            tos.setBigNumberMode(TarArchiveOutputStream.BIGNUMBER_POSIX);
            tos.setAddPaxHeadersForNonAsciiNames(true);
            for (Item it : items) {
                p.check();
                p.current = it.name;
                TarArchiveEntry te = new TarArchiveEntry(it.dir ? it.name + "/" : it.name);
                if (it.mtime > 0) te.setModTime(new Date(it.mtime));
                if (!it.dir) te.setSize(it.size);
                tos.putArchiveEntry(te);
                if (!it.dir) {
                    long n;
                    try (InputStream in = vfs.openRead(it.src, 0)) {
                        n = copyExact(in, tos, it.size, p);
                    }
                    if (n != it.size) throw new IOException("「" + it.name + "」在压缩过程中被修改了");
                    p.count++;
                }
                tos.closeArchiveEntry();
            }
            tos.finish();
        }
    }

    /** tar 头里已经写了文件大小，这里最多只写这么多 */
    private static long copyExact(InputStream in, OutputStream out, long size, Progress p) throws IOException {
        byte[] buf = new byte[128 * 1024];
        long left = size;
        while (left > 0) {
            p.check();
            int k = in.read(buf, 0, (int) Math.min(buf.length, left));
            if (k < 0) break;
            out.write(buf, 0, k);
            left -= k;
            p.done += k;
        }
        return size - left;
    }

    /** 7z：用 7-Zip 引擎写入（需要能随机写的文件） */
    public static void write7z(final Vfs vfs, final List<Item> items, Options o, RandomAccessFile raf, final Progress p) throws IOException {
        if (!SevenZipLib.available()) throw new IOException("7-Zip 引擎加载失败：" + SevenZipLib.error());
        final String pw = o.password == null || o.password.isEmpty() ? null : o.password;
        IOutCreateArchive7z arc = null;
        // 只有加密时回调才实现 ICryptoGetTextPassword，7-Zip 据此决定是否加密
        Callback7z cb = pw == null ? new Callback7z(vfs, items, p) : new EncryptedCallback7z(vfs, items, p, pw);
        try {
            arc = SevenZip.openOutArchive7z();
            // 注意：7-Zip-JBinding 每设置一项都会单独调用一次 SetProperties，而 7-Zip 每次都会先重置全部参数，
            // 所以 setThreadCount / setSolid(false) 会把前面设的级别和文件名加密冲掉，这里只设这两项
            arc.setLevel(o.level == STORE ? 0 : o.level == FAST ? 1 : o.level == MAX ? 7 : 5);
            if (pw != null && o.encryptNames) arc.setHeaderEncryption(true);
            arc.createArchive(new RandomAccessFileOutStream(raf), items.size(), cb);
        } catch (SevenZipException e) {
            if (p.cancelled) throw new CancelledException();
            if (cb.error != null) throw cb.error;
            throw new IOException("创建 7z 失败：" + e.getMessage());
        } finally {
            cb.closeCurrent();
            if (arc != null) {
                try {
                    arc.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    private static class Callback7z implements IOutCreateCallback<IOutItem7z> {
        final Vfs vfs;
        final List<Item> items;
        final Progress p;
        InputStream cur;
        IOException error;

        Callback7z(Vfs vfs, List<Item> items, Progress p) {
            this.vfs = vfs;
            this.items = items;
            this.p = p;
        }

        @Override
        public void setOperationResult(boolean ok) {
            closeCurrent();
            if (ok) p.count++;
        }

        @Override
        public IOutItem7z getItemInformation(int index, OutItemFactory<IOutItem7z> f) {
            Item it = items.get(index);
            IOutItem7z o = f.createOutItem();
            o.setPropertyPath(it.name);
            o.setPropertyIsDir(it.dir);
            o.setPropertyLastModificationTime(new Date(it.mtime > 0 ? it.mtime : System.currentTimeMillis()));
            if (!it.dir) o.setDataSize(it.size);
            return o;
        }

        @Override
        public ISequentialInStream getStream(int index) throws SevenZipException {
            closeCurrent();
            final Item it = items.get(index);
            if (it.dir) return null;
            p.current = it.name;
            try {
                cur = new BufferedInputStream(vfs.openRead(it.src, 0), 256 * 1024);
            } catch (IOException e) {
                error = e;
                throw new SevenZipException(e.getMessage());
            }
            final InputStream in = cur;
            return new ISequentialInStream() {
                @Override
                public int read(byte[] data) throws SevenZipException {
                    if (p.cancelled) throw new SevenZipException("cancelled");
                    try {
                        int n = in.read(data);
                        if (n < 0) return 0;
                        p.done += n;
                        return n;
                    } catch (IOException e) {
                        error = e;
                        throw new SevenZipException(e.getMessage());
                    }
                }

                @Override
                public void close() throws IOException {
                    in.close();
                }
            };
        }

        void closeCurrent() {
            if (cur != null) {
                try {
                    cur.close();
                } catch (IOException ignored) {
                }
                cur = null;
            }
        }

        @Override
        public void setTotal(long total) {
        }

        @Override
        public void setCompleted(long complete) throws SevenZipException {
            if (p.cancelled) throw new SevenZipException("cancelled");
        }
    }

    private static final class EncryptedCallback7z extends Callback7z implements ICryptoGetTextPassword {
        final String pw;

        EncryptedCallback7z(Vfs vfs, List<Item> items, Progress p, String pw) {
            super(vfs, items, p);
            this.pw = pw;
        }

        @Override
        public String cryptoGetTextPassword() {
            return pw;
        }
    }
}
