package com.lanfile.manager.core;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/** 基于 java.io.File 的实现 */
public class LocalVfs implements Vfs {

    protected final List<File> rootDirs = new ArrayList<>();

    public LocalVfs(List<File> roots) {
        if (roots != null) rootDirs.addAll(roots);
    }

    public void setRoots(List<File> roots) {
        synchronized (rootDirs) {
            rootDirs.clear();
            rootDirs.addAll(roots);
        }
    }

    @Override
    public List<Entry> roots() {
        List<Entry> out = new ArrayList<>();
        synchronized (rootDirs) {
            for (File f : rootDirs) {
                Entry e = toEntry(f);
                out.add(e);
            }
        }
        return out;
    }

    public static Entry toEntry(File f) {
        Entry e = new Entry();
        e.path = f.getAbsolutePath();
        e.name = f.getName();
        e.dir = f.isDirectory();
        e.size = e.dir ? 0 : f.length();
        e.mtime = f.lastModified();
        return e;
    }

    @Override
    public List<Entry> list(String dirPath) throws IOException {
        File dir = new File(dirPath);
        File[] files = dir.listFiles();
        if (files == null) {
            if (!dir.exists()) throw new FileNotFoundException(dirPath);
            throw new IOException("无法读取目录（可能没有权限）: " + dirPath);
        }
        List<Entry> out = new ArrayList<>(files.length);
        for (File f : files) out.add(toEntry(f));
        return out;
    }

    @Override
    public Entry stat(String path) throws IOException {
        File f = new File(path);
        if (!f.exists()) return null;
        return toEntry(f);
    }

    @Override
    public InputStream openRead(String path, long offset) throws IOException {
        FileInputStream in = new FileInputStream(path);
        if (offset > 0) in.getChannel().position(offset);
        return in;
    }

    @Override
    public OutputStream create(String parentPath, String name) throws IOException {
        File f = new File(parentPath, name);
        return new FileOutputStream(f);
    }

    @Override
    public String mkdir(String parentPath, String name) throws IOException {
        File f = new File(parentPath, name);
        if (!f.isDirectory() && !f.mkdirs()) throw new IOException("无法创建文件夹: " + name);
        return f.getAbsolutePath();
    }

    @Override
    public void delete(String path) throws IOException {
        File f = new File(path);
        deleteRecursive(f);
        if (f.exists()) throw new IOException("删除失败: " + f.getName());
    }

    private static void deleteRecursive(File f) {
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) for (File k : kids) deleteRecursive(k);
        }
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }

    @Override
    public String rename(String path, String newName) throws IOException {
        File f = new File(path);
        File t = new File(f.getParentFile(), newName);
        if (t.exists()) throw new IOException("已存在同名项目: " + newName);
        if (!f.renameTo(t)) throw new IOException("重命名失败");
        return t.getAbsolutePath();
    }
}
