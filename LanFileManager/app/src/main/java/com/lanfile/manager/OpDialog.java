package com.lanfile.manager;

import android.app.Activity;
import android.app.AlertDialog;
import android.media.MediaScannerConnection;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import com.lanfile.manager.core.Util;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 带进度和取消按钮的后台操作（分类管理、垃圾清理共用），结束后通知媒体库同步 */
final class OpDialog {
    private OpDialog() {}

    private static final ExecutorService ops = Executors.newSingleThreadExecutor();
    private static final Handler ui = new Handler(Looper.getMainLooper());

    static void run(final Activity a, String title, final MainActivity.Op op, final Runnable after) {
        View v = LayoutInflater.from(a).inflate(R.layout.dialog_progress, null);
        final TextView pt = v.findViewById(R.id.pText);
        final TextView ps = v.findViewById(R.id.pSub);
        final ProgressBar pb = v.findViewById(R.id.pBar);
        final MainActivity.Progress p = new MainActivity.Progress();
        final AlertDialog d = new AlertDialog.Builder(a).setTitle(title).setView(v).setCancelable(false)
                .setNegativeButton("取消", null).create();
        d.show();
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
                    pb.setProgress((int) (p.done * 1000 / Math.max(1, p.total)));
                    ps.setText(Util.formatSize(p.done) + " / " + Util.formatSize(p.total));
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
            }
            final Exception fe = err;
            final String[] scan;
            synchronized (p.scan) {
                scan = p.scan.toArray(new String[0]);
            }
            if (scan.length > 0) MediaScannerConnection.scanFile(a.getApplicationContext(), scan, null, null);
            ui.post(() -> {
                ui.removeCallbacks(tick);
                if (!a.isFinishing()) d.dismiss();
                if (fe != null && !(fe instanceof MainActivity.CancelledException)) {
                    Toast.makeText(a, "操作失败：" + fe.getMessage(), Toast.LENGTH_SHORT).show();
                }
                if (after != null) after.run();
            });
        });
    }

    /** 记录需要通知媒体库的路径（受限目录不在媒体库里，跳过） */
    static void addScan(MainActivity.Progress p, String path) {
        if (App.vfs.isRestricted(path) || p.scan.size() > 5000) return;
        synchronized (p.scan) {
            p.scan.add(path);
        }
    }
}
