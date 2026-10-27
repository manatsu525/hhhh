package com.lanfile.manager.archive;

import net.sf.sevenzipjbinding.ArchiveFormat;
import net.sf.sevenzipjbinding.ExtractAskMode;
import net.sf.sevenzipjbinding.ExtractOperationResult;
import net.sf.sevenzipjbinding.IArchiveExtractCallback;
import net.sf.sevenzipjbinding.IArchiveOpenCallback;
import net.sf.sevenzipjbinding.IArchiveOpenVolumeCallback;
import net.sf.sevenzipjbinding.ICryptoGetTextPassword;
import net.sf.sevenzipjbinding.IInArchive;
import net.sf.sevenzipjbinding.IInStream;
import net.sf.sevenzipjbinding.ISequentialOutStream;
import net.sf.sevenzipjbinding.PropID;
import net.sf.sevenzipjbinding.SevenZip;
import net.sf.sevenzipjbinding.SevenZipException;
import net.sf.sevenzipjbinding.impl.RandomAccessFileInStream;
import net.sf.sevenzipjbinding.impl.VolumedArchiveInStream;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** 7-Zip 引擎：7z、rar/rar5、iso、cab、arj、lzh、cpio、rpm、deb、wim、dmg、xar、分卷 .001 等 */
final class NativeReader extends ArchiveReader {
    private IInArchive arc;
    private OpenCallback openCb;

    NativeReader(File f, String displayName) {
        super(f, displayName);
    }

    @Override
    public String format() {
        ArchiveFormat af = arc.getArchiveFormat();
        String m = af == null ? "?" : af.getMethodName();
        return m.toUpperCase(Locale.ROOT);
    }

    void load() throws IOException {
        openCb = new OpenCallback(file);
        IInStream in;
        try {
            if (file.getName().matches("(?i).*\\.0*1$") && file.getName().matches("(?i).*\\.\\d{3}$")) {
                // 7z/zip 等按字节切分的分卷：a.7z.001、a.7z.002 …
                // 回调不能再实现 IArchiveOpenVolumeCallback，否则 7-Zip 会按 Split 格式把它当成单个文件
                in = new VolumedArchiveInStream(file.getAbsolutePath(), openCb);
                arc = SevenZip.openInArchive(null, in, openCb.passwordOnly());
            } else {
                in = openCb.getStream(file.getAbsolutePath());
                if (in == null) throw new IOException("无法打开文件");
                arc = SevenZip.openInArchive(null, in, openCb);
            }
        } catch (SevenZipException e) {
            openCb.closeAll();
            if (openCb.askedPassword) throw new PasswordException(password != null);
            throw new IOException("无法识别的压缩格式，或文件已损坏");
        }
        try {
            int n = arc.getNumberOfItems();
            String single = Formats.singleName(displayName);
            for (int i = 0; i < n; i++) {
                ArcEntry e = new ArcEntry();
                e.index = i;
                String p = arc.getStringProperty(i, PropID.PATH);
                if (p == null || p.isEmpty()) p = n == 1 ? single : single + "_" + i;
                e.path = Formats.cleanPath(p, true);
                if (e.path.isEmpty()) e.path = "item_" + i;
                e.dir = Boolean.TRUE.equals(arc.getProperty(i, PropID.IS_FOLDER));
                Object sz = arc.getProperty(i, PropID.SIZE);
                e.size = e.dir ? 0 : sz instanceof Long ? (Long) sz : -1;
                Object pk = arc.getProperty(i, PropID.PACKED_SIZE);
                e.packed = pk instanceof Long ? (Long) pk : -1;
                Object t = arc.getProperty(i, PropID.LAST_MODIFICATION_TIME);
                e.mtime = t instanceof Date ? ((Date) t).getTime() : 0;
                e.encrypted = Boolean.TRUE.equals(arc.getProperty(i, PropID.ENCRYPTED));
                entries.add(e);
            }
        } catch (SevenZipException e) {
            close();
            throw new IOException("读取压缩包目录失败：" + e.getMessage());
        }
    }

    @Override
    public void extract(Collection<ArcEntry> files, final Sink sink, final Progress p) throws IOException {
        final Map<Integer, ArcEntry> byIndex = new HashMap<>();
        List<Integer> idx = new ArrayList<>();
        for (ArcEntry e : files) {
            if (e.dir || e.index < 0 || byIndex.containsKey(e.index)) continue;
            byIndex.put(e.index, e);
            idx.add(e.index);
        }
        if (idx.isEmpty()) return;
        int[] ids = new int[idx.size()];
        for (int i = 0; i < ids.length; i++) ids[i] = idx.get(i);
        Arrays.sort(ids);

        ExtractCallback cb = new ExtractCallback(byIndex, sink, p);
        SevenZipException err = null;
        try {
            arc.extract(ids, false, cb);
        } catch (SevenZipException e) {
            err = e;
        }
        cb.abortCurrent();
        if (p.cancelled) throw new CancelledException();
        if (cb.ioError != null) throw cb.ioError;
        if (cb.askedPassword && password == null) throw new PasswordException(false);
        if (cb.failed != null) {
            ExtractOperationResult r = cb.failed;
            if (r == ExtractOperationResult.WRONG_PASSWORD
                    || (cb.failedEntry.encrypted && (r == ExtractOperationResult.CRCERROR || r == ExtractOperationResult.DATAERROR))) {
                throw new PasswordException(true);
            }
            throw new IOException("「" + cb.failedEntry.name() + "」" + describe(r));
        }
        if (err != null) throw new IOException("解压失败：" + rootMessage(err));
    }

    private static String rootMessage(Throwable t) {
        while (t.getCause() != null && t.getCause() != t) t = t.getCause();
        return t.getMessage();
    }

    private static String describe(ExtractOperationResult r) {
        switch (r) {
            case UNSUPPORTEDMETHOD: return "使用了不支持的压缩方式";
            case CRCERROR: return "校验失败，文件已损坏";
            case DATAERROR: return "数据错误，文件已损坏";
            case UNEXPECTED_END: return "数据不完整（压缩包被截断或缺少分卷）";
            case UNAVAILABLE: return "数据不可用（可能缺少分卷）";
            case HEADERS_ERROR: return "压缩包头部损坏";
            default: return "解压失败（" + r + "）";
        }
    }

    private final class ExtractCallback implements IArchiveExtractCallback, ICryptoGetTextPassword {
        final Map<Integer, ArcEntry> byIndex;
        final Sink sink;
        final Progress p;
        ArcEntry cur, pending;
        OutputStream out;
        boolean askedPassword;
        IOException ioError;
        ExtractOperationResult failed;
        ArcEntry failedEntry;

        ExtractCallback(Map<Integer, ArcEntry> byIndex, Sink sink, Progress p) {
            this.byIndex = byIndex;
            this.sink = sink;
            this.p = p;
        }

        @Override
        public ISequentialOutStream getStream(int index, ExtractAskMode mode) throws SevenZipException {
            ArcEntry e = byIndex.get(index);
            pending = e;
            // 密码错误等导致整块数据无法解码时，7-Zip 会以 TEST 模式逐个报告失败，这里也要记下来
            if (mode != ExtractAskMode.EXTRACT || e == null) return null;
            if (p.cancelled) throw new SevenZipException("cancelled");
            cur = e;
            p.current = e.path;
            try {
                out = sink.open(e);
            } catch (IOException ex) {
                ioError = ex;
                throw new SevenZipException(ex.getMessage());
            }
            if (out == null) {
                cur = null;
                return null;
            }
            return new ISequentialOutStream() {
                @Override
                public int write(byte[] data) throws SevenZipException {
                    if (p.cancelled) throw new SevenZipException("cancelled");
                    try {
                        out.write(data);
                    } catch (IOException ex) {
                        ioError = ex;
                        throw new SevenZipException(ex.getMessage());
                    }
                    p.done += data.length;
                    return data.length;
                }
            };
        }

        @Override
        public void prepareOperation(ExtractAskMode mode) {
        }

        @Override
        public void setOperationResult(ExtractOperationResult r) throws SevenZipException {
            ArcEntry pe = pending;
            pending = null;
            if (cur == null) {
                if (pe != null && r != ExtractOperationResult.OK && failed == null) {
                    failed = r;
                    failedEntry = pe;
                    throw new SevenZipException("extract failed");
                }
                return;
            }
            ArcEntry e = cur;
            OutputStream o = out;
            cur = null;
            out = null;
            boolean ok = r == ExtractOperationResult.OK;
            try {
                sink.close(e, o, ok);
            } catch (IOException ex) {
                ioError = ex;
                throw new SevenZipException(ex.getMessage());
            }
            if (ok) {
                p.count++;
            } else if (failed == null) {
                failed = r;
                failedEntry = e;
                throw new SevenZipException("extract failed");
            }
        }

        /** 中途出错时关闭并删除正在写的文件 */
        void abortCurrent() {
            if (cur == null) return;
            try {
                sink.close(cur, out, false);
            } catch (IOException ignored) {
            }
            cur = null;
            out = null;
        }

        @Override
        public void setTotal(long total) {
        }

        @Override
        public void setCompleted(long complete) throws SevenZipException {
            if (p.cancelled) throw new SevenZipException("cancelled");
        }

        @Override
        public String cryptoGetTextPassword() throws SevenZipException {
            askedPassword = true;
            if (password == null) throw new SevenZipException("password required");
            return password;
        }
    }

    /** 打开回调：提供密码、提供 rar 等格式的其他分卷 */
    private final class OpenCallback implements IArchiveOpenCallback, IArchiveOpenVolumeCallback, ICryptoGetTextPassword {
        private final File dir;
        private final Map<String, RandomAccessFile> opened = new HashMap<>();
        private String name;
        boolean askedPassword;

        OpenCallback(File first) {
            dir = first.getAbsoluteFile().getParentFile();
            name = first.getAbsolutePath();
        }

        @Override
        public Object getProperty(PropID propID) {
            return propID == PropID.NAME ? name : null;
        }

        @Override
        public IInStream getStream(String filename) throws SevenZipException {
            File f = new File(filename);
            if (!f.isAbsolute()) f = new File(dir, filename);
            String key = f.getAbsolutePath();
            try {
                RandomAccessFile raf = opened.get(key);
                if (raf == null) {
                    if (!f.isFile()) return null;
                    raf = new RandomAccessFile(f, "r");
                    opened.put(key, raf);
                }
                raf.seek(0);
                name = key;
                return new RandomAccessFileInStream(raf);
            } catch (IOException e) {
                return null;
            }
        }

        @Override
        public void setTotal(Long files, Long bytes) {
        }

        @Override
        public void setCompleted(Long files, Long bytes) {
        }

        @Override
        public String cryptoGetTextPassword() throws SevenZipException {
            askedPassword = true;
            if (password == null) throw new SevenZipException("password required");
            return password;
        }

        IArchiveOpenCallback passwordOnly() {
            return new PasswordOnly();
        }

        private final class PasswordOnly implements IArchiveOpenCallback, ICryptoGetTextPassword {
            @Override
            public void setTotal(Long files, Long bytes) {
            }

            @Override
            public void setCompleted(Long files, Long bytes) {
            }

            @Override
            public String cryptoGetTextPassword() throws SevenZipException {
                return OpenCallback.this.cryptoGetTextPassword();
            }
        }

        void closeAll() {
            for (RandomAccessFile r : opened.values()) {
                try {
                    r.close();
                } catch (IOException ignored) {
                }
            }
            opened.clear();
        }
    }

    @Override
    public void close() {
        if (arc != null) {
            try {
                arc.close();
            } catch (SevenZipException ignored) {
            }
            arc = null;
        }
        if (openCb != null) openCb.closeAll();
    }
}
