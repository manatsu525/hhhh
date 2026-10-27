package com.lanfile.manager;

import android.app.Application;

import java.io.File;

public class App extends Application {
    public static App ctx;
    public static AndroidVfs vfs;
    private final long started = System.currentTimeMillis() - 2000;

    @Override
    public void onCreate() {
        super.onCreate();
        ctx = this;
        Prefs.init(this);
        vfs = new AndroidVfs(this);
        ShizukuHelper.init(this);
        cleanup();
    }

    /** 清理上次异常退出时遗留的半截文件，以及分享过来暂存的文件 */
    private void cleanup() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                for (String p : Prefs.partials()) {
                    try {
                        if (vfs.stat(p) != null) vfs.delete(p);
                    } catch (Exception ignored) {
                    }
                    Prefs.trackPartial(p, false);
                }
                if (!TransferService.isRunning()) clearOutbox(App.this);
                // 压缩包查看器临时解压的文件（正常关闭时已删除，这里处理异常退出的情况）。
                // 只删本次启动前留下的，避免误删刚打开的压缩包
                File[] arc = new File(getCacheDir(), "arc").listFiles();
                if (arc != null) for (File f : arc) if (f.lastModified() < started) deleteTree(f);
            }
        }).start();
    }

    public static File outbox(android.content.Context c) {
        return new File(c.getCacheDir(), "outbox");
    }

    public static void clearOutbox(android.content.Context c) {
        deleteTree(outbox(c));
    }

    public static void deleteTree(File f) {
        File[] k = f.listFiles();
        if (k != null) for (File x : k) deleteTree(x);
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }
}
