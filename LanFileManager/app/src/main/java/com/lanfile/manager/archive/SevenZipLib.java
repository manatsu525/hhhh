package com.lanfile.manager.archive;

import net.sf.sevenzipjbinding.SevenZip;

/** 7-Zip 原生库的加载。Android 上直接 loadLibrary，桌面 JVM（测试用）从平台 jar 解出 */
public final class SevenZipLib {
    private SevenZipLib() {}

    private static int state;   // 0 未尝试 1 成功 2 失败
    private static Throwable error;

    public static synchronized boolean available() {
        if (state == 0) {
            try {
                if (SevenZip.isInitializedSuccessfully()) {
                    state = 1;
                } else if ("The Android Project".equals(System.getProperty("java.vendor"))) {
                    System.loadLibrary("7-Zip-JBinding");
                    SevenZip.initLoadedLibraries();
                    state = 1;
                } else {
                    SevenZip.initSevenZipFromPlatformJAR();
                    state = 1;
                }
            } catch (Throwable t) {
                error = t;
                state = 2;
            }
        }
        return state == 1;
    }

    public static synchronized String error() {
        return error == null ? "未知错误" : error.getMessage();
    }
}
