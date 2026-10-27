package com.lanfile.manager.archive;

import com.lanfile.manager.core.Util;
import com.lanfile.manager.core.Vfs;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 把压缩包里的条目解压到 Vfs 中的某个目录（普通目录、Android/data 等都可以） */
public final class Extractor {
    private Extractor() {}

    /** 目标位置已有同名文件时：保留两者（自动改名）、覆盖、跳过 */
    public enum Conflict { RENAME, OVERWRITE, SKIP }

    public interface Listener {
        /** 一个文件写完 */
        void written(String path, long mtime);
    }

    /**
     * @param sel   要解压的条目；选中目录时应传入目录下的全部条目（见 {@link ArchiveReader#under}）
     * @param base  解压时去掉的路径前缀（在压缩包的子目录里解压时，不重建上层目录），根目录为 ""
     */
    public static void extract(ArchiveReader r, Collection<ArcEntry> sel, String base, final Vfs vfs, final String destDir,
                               final Conflict conflict, final Progress p, final Listener l) throws IOException {
        final String pre = base == null || base.isEmpty() ? "" : base + "/";
        final Set<String> madeDirs = new HashSet<>();
        madeDirs.add(destDir);
        List<ArcEntry> files = new ArrayList<>();
        long total = 0;
        boolean known = true;
        for (ArcEntry e : sel) {
            if (e.dir) {
                String rel = rel(e.path, pre);
                if (!rel.isEmpty()) mkdirs(vfs, Util.join(destDir, rel), madeDirs);
                continue;
            }
            files.add(e);
            if (e.size >= 0) total += e.size;
            else known = false;
        }
        p.total = known ? total : -1;
        p.done = 0;

        r.extract(files, new ArchiveReader.Sink() {
            String curPath;

            @Override
            public OutputStream open(ArcEntry e) throws IOException {
                String rel = rel(e.path, pre);
                if (rel.isEmpty()) rel = e.name();
                String parent = Util.join(destDir, rel);
                String name = Util.nameOf(parent);
                parent = Util.parent(parent);
                if (!Util.isUnder(parent, destDir)) throw new IOException("非法路径：" + e.path);
                mkdirs(vfs, parent, madeDirs);
                Vfs.Entry old = vfs.stat(Util.join(parent, name));
                if (old != null) {
                    if (conflict == Conflict.SKIP) {
                        p.done += Math.max(0, e.size);
                        return null;
                    }
                    if (conflict == Conflict.OVERWRITE && !old.dir) vfs.delete(old.path);
                    else name = Util.uniqueName(vfs, parent, name);
                }
                curPath = Util.join(parent, name);
                return new BufferedOutputStream(vfs.create(parent, name), 128 * 1024);
            }

            @Override
            public void close(ArcEntry e, OutputStream out, boolean ok) throws IOException {
                String path = curPath;
                curPath = null;
                try {
                    out.close();
                } catch (IOException ex) {
                    if (ok) {
                        ok = false;
                        throw ex;
                    }
                } finally {
                    if (!ok && path != null) {
                        try {
                            vfs.delete(path);
                        } catch (IOException ignored) {
                        }
                    }
                }
                if (ok && l != null) l.written(path, e.mtime);
            }
        }, p);
    }

    private static String rel(String path, String pre) {
        if (pre.isEmpty()) return path;
        return path.startsWith(pre) ? path.substring(pre.length()) : path.equals(pre.substring(0, pre.length() - 1)) ? "" : path;
    }

    private static void mkdirs(Vfs vfs, String dir, Set<String> made) throws IOException {
        if (made.contains(dir)) return;
        Vfs.Entry st = vfs.stat(dir);
        if (st == null) {
            String parent = Util.parent(dir);
            mkdirs(vfs, parent, made);
            vfs.mkdir(parent, Util.nameOf(dir));
        } else if (!st.dir) {
            throw new IOException("无法创建文件夹「" + Util.nameOf(dir) + "」：已存在同名文件");
        }
        made.add(dir);
    }
}
