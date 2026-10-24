package com.lanfile.manager;

import android.os.ParcelFileDescriptor;

import java.io.File;

/**
 * 运行在 Shizuku 启动的 shell（或 root）进程里的文件服务。
 * shell 身份可以读写 Android/data、Android/obb，Android 14+ 也一样。
 * 它随本应用进程结束而退出（非 daemon），不在手机上留下任何东西。
 */
public class ShizukuFileService extends IFileService.Stub {

    public ShizukuFileService() {}

    @Override
    public void destroy() {
        System.exit(0);
    }

    private static String line(File f) {
        boolean d = f.isDirectory();
        return (d ? "1" : "0") + "|" + (d ? 0 : f.length()) + "|" + f.lastModified() + "|" + f.getName();
    }

    @Override
    public String list(String path) {
        File[] kids = new File(path).listFiles();
        if (kids == null) return null;
        StringBuilder b = new StringBuilder();
        for (File k : kids) b.append(line(k)).append('\n');
        return b.toString();
    }

    @Override
    public String stat(String path) {
        File f = new File(path);
        return f.exists() ? line(f) : null;
    }

    @Override
    public ParcelFileDescriptor open(String path, int mode) {
        try {
            int m = mode == 1
                    ? ParcelFileDescriptor.MODE_WRITE_ONLY | ParcelFileDescriptor.MODE_CREATE | ParcelFileDescriptor.MODE_TRUNCATE
                    : ParcelFileDescriptor.MODE_READ_ONLY;
            return ParcelFileDescriptor.open(new File(path), m);
        } catch (Exception e) {
            throw new IllegalStateException(e.getMessage());
        }
    }

    @Override
    public boolean mkdir(String path) {
        File f = new File(path);
        return f.isDirectory() || f.mkdirs();
    }

    @Override
    public boolean delete(String path) {
        File f = new File(path);
        del(f);
        return !f.exists();
    }

    private static void del(File f) {
        if (f.isDirectory()) {
            File[] k = f.listFiles();
            if (k != null) for (File x : k) del(x);
        }
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }

    @Override
    public boolean rename(String from, String to) {
        File t = new File(to);
        if (t.exists()) return false;
        return new File(from).renameTo(t);
    }
}
