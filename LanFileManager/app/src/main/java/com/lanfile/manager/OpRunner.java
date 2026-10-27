package com.lanfile.manager;

import android.app.Activity;
import android.app.AlertDialog;
import android.media.MediaScannerConnection;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import com.lanfile.manager.archive.CancelledException;
import com.lanfile.manager.archive.Progress;
import com.lanfile.manager.core.Util;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 带进度对话框（可取消）的后台操作：复制、移动、删除、压缩、解压 */
final class OpRunner {
    private OpRunner() {}

    interface Op {
        void run(Progress p) throws Exception;
    }

    interface OnError {
        /** 返回 true 表示已处理（不再弹出默认的失败提示） */
        boolean handle(Exception e);
    }

    private static final ExecutorService ops = Executors.newSingleThreadExecutor();
    private static final Handler ui = new Handler(Looper.getMainLooper());

    static void run(Activity a, String title, Op op, Runnable after) {
        run(a, title, op, after, null);
    }

    static void run(final Activity a, String title, final Op op, final Runnable after, final OnError onError) {
        View v = LayoutInflater.from(a).inflate(R.layout.dialog_progress, null);
        final TextView pt = v.findViewById(R.id.pText);
        final TextView ps = v.findViewById(R.id.pSub);
        final ProgressBar pb = v.findViewById(R.id.pBar);
        final Progress p = new Progress();
        final AlertDialog d = new AlertDialog.Builder(a).setTitle(title).setView(v).setCancelable(false)
                .setNegativeButton("取消", null).create();
        d.show();
        a.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        d.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener(x -> {
            p.cancelled = true;
            pt.setText("正在取消…");
        });
        pb.setIndeterminate(true);
        final Runnable tick = new Runnable() {
            @Override
            public void run() {
                if (!p.cancelled) pt.setText(p.current);
                if (p.total > 0) {
                    pb.setIndeterminate(false);
                    pb.setProgress((int) (Math.min(p.done, p.total) * 1000 / Math.max(1, p.total)));
                    ps.setText(Util.formatSize(p.done) + " / " + Util.formatSize(p.total));
                } else if (p.done > 0) {
                    ps.setText("已处理 " + Util.formatSize(p.done) + (p.count > 0 ? "，" + p.count + " 个文件" : ""));
                } else if (p.count > 0) ps.setText("已处理 " + p.count + " 项");
                ui.postDelayed(this, 200);
            }
        };
        ui.post(tick);
        ops.execute(() -> {
            Exception err = null;
            try {
                op.run(p);
            } catch (Exception e) {
                err = e;
            } catch (OutOfMemoryError e) {
                err = new java.io.IOException("内存不足");
            }
            final Exception fe = err;
            final String[] scan;
            synchronized (p.scan) {
                scan = p.scan.toArray(new String[0]);
            }
            if (scan.length > 0) MediaScannerConnection.scanFile(a.getApplicationContext(), scan, null, null);
            ui.post(() -> {
                ui.removeCallbacks(tick);
                a.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                if (!a.isFinishing() && !a.isDestroyed()) {
                    try {
                        d.dismiss();
                    } catch (Exception ignored) {
                    }
                }
                if (fe != null && !(fe instanceof CancelledException)) {
                    if (onError == null || !onError.handle(fe)) {
                        Toast.makeText(a, "操作失败：" + fe.getMessage(), Toast.LENGTH_LONG).show();
                    }
                }
                if (after != null) after.run();
            });
        });
    }
}
