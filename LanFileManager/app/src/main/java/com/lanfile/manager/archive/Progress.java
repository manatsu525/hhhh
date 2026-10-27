package com.lanfile.manager.archive;

import java.util.ArrayList;
import java.util.List;

/** 后台操作的进度与取消标志（文件管理器和压缩/解压共用） */
public class Progress {
    public volatile boolean cancelled;
    public volatile long total = -1, done;
    public volatile String current = "";
    public volatile int count;
    /** 操作结束后需要通知媒体库的路径 */
    public final List<String> scan = new ArrayList<>();

    public void check() throws CancelledException {
        if (cancelled) throw new CancelledException();
    }
}
