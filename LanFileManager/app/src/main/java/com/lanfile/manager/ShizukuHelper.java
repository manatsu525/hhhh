package com.lanfile.manager;

import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.IBinder;
import android.os.Looper;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import rikka.shizuku.Shizuku;

/** Shizuku 连接管理：检测状态、申请权限、绑定 shell 端文件服务 */
public final class ShizukuHelper {

    public static final String SHIZUKU_PKG = "moe.shizuku.privileged.api";
    public static final int REQ_CODE = 7701;

    public enum State { NOT_INSTALLED, NOT_RUNNING, NO_PERMISSION, READY }

    public interface Listener {
        void onShizukuChanged();
    }

    private static volatile IFileService service;
    private static volatile CountDownLatch bindLatch;
    private static volatile boolean binding;
    private static final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    private static Context app;

    private static final Shizuku.UserServiceArgs ARGS = new Shizuku.UserServiceArgs(
            new ComponentName(BuildConfig.APPLICATION_ID, ShizukuFileService.class.getName()))
            .daemon(false)
            .processNameSuffix("fs")
            .debuggable(false)
            .version(BuildConfig.VERSION_CODE);

    private static final ServiceConnection CONN = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            if (binder != null && binder.pingBinder()) service = IFileService.Stub.asInterface(binder);
            binding = false;
            CountDownLatch l = bindLatch;
            if (l != null) l.countDown();
            fire();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            service = null;
            binding = false;
            fire();
        }
    };

    private ShizukuHelper() {}

    static void init(Context c) {
        app = c.getApplicationContext();
        Shizuku.addBinderReceivedListenerSticky(() -> {
            if (hasPermission()) bind();
            fire();
        });
        Shizuku.addBinderDeadListener(() -> {
            service = null;
            binding = false;
            fire();
        });
        Shizuku.addRequestPermissionResultListener((requestCode, grantResult) -> {
            if (grantResult == PackageManager.PERMISSION_GRANTED) bind();
            fire();
        });
    }

    public static void addListener(Listener l) { listeners.add(l); }
    public static void removeListener(Listener l) { listeners.remove(l); }

    private static void fire() {
        if (app != null) App.vfs.invalidateGrants();
        for (Listener l : listeners) l.onShizukuChanged();
    }

    public static boolean isInstalled() {
        try {
            app.getPackageManager().getPackageInfo(SHIZUKU_PKG, 0);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public static boolean isRunning() {
        try {
            return Shizuku.pingBinder() && !Shizuku.isPreV11();
        } catch (Throwable t) {
            return false;
        }
    }

    public static boolean hasPermission() {
        try {
            return isRunning() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            return false;
        }
    }

    public static State state() {
        if (hasPermission()) return State.READY;
        if (isRunning()) return State.NO_PERMISSION;
        return isInstalled() ? State.NOT_RUNNING : State.NOT_INSTALLED;
    }

    public static String stateText() {
        switch (state()) {
            case READY: return service != null ? "已连接（shell 权限）" : "已授权";
            case NO_PERMISSION: return "运行中，尚未授权本应用";
            case NOT_RUNNING: return "已安装，但未启动";
            default: return "未安装";
        }
    }

    public static void requestPermission() {
        if (!isRunning()) return;
        if (hasPermission()) {
            bind();
            return;
        }
        Shizuku.requestPermission(REQ_CODE);
    }

    public static synchronized void bind() {
        if (service != null || binding || !hasPermission()) return;
        binding = true;
        bindLatch = new CountDownLatch(1);
        try {
            Shizuku.bindUserService(ARGS, CONN);
        } catch (Throwable t) {
            binding = false;
            bindLatch.countDown();
        }
    }

    /** 可用时返回服务；会在必要时绑定并等待（不要在主线程调用等待） */
    public static IFileService get() {
        IFileService s = service;
        if (s != null && s.asBinder().pingBinder()) return s;
        service = null;
        if (!hasPermission()) return null;
        bind();
        if (Looper.myLooper() == Looper.getMainLooper()) return null;
        CountDownLatch l = bindLatch;
        try {
            if (l != null) l.await(8, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
        }
        return service;
    }

    /** 当前是否可通过 Shizuku 访问（不阻塞） */
    public static boolean usable() {
        return hasPermission();
    }
}
