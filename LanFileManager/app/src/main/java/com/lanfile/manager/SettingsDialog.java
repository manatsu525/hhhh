package com.lanfile.manager;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Environment;
import android.util.TypedValue;
import android.view.View;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;

import com.lanfile.manager.core.Hub;

final class SettingsDialog {
    private SettingsDialog() {}

    static void show(final Activity a, final Runnable onChanged) {
        int pad = dp(a, 20);
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(pad, dp(a, 8), pad, 0);

        final CheckBox hidden = check(a, box, "显示隐藏文件（以 . 开头）", Prefs.showHidden());

        header(a, box, "电脑发来的文件保存到");
        final RadioGroup rg = new RadioGroup(a);
        RadioButton r1 = new RadioButton(a);
        r1.setId(View.generateViewId());
        r1.setText("系统「下载」目录\n" + Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).getAbsolutePath());
        RadioButton r2 = new RadioButton(a);
        r2.setId(View.generateViewId());
        r2.setText("应用专属目录（卸载 App 时自动一并删除）");
        rg.addView(r1);
        rg.addView(r2);
        rg.check(Prefs.recvPrivate() ? r2.getId() : r1.getId());
        box.addView(rg);
        final int r2id = r2.getId();
        note(a, box, "保存到「下载」目录的文件属于你自己的文件，卸载后会保留；App 不会在存储里创建任何自己的文件夹。");

        header(a, box, "电脑互传");
        final CheckBox browse = check(a, box, "允许电脑浏览/下载/上传手机文件", Prefs.allowBrowse());
        final CheckBox pin = check(a, box, "电脑连接时需要输入访问码（推荐）", Prefs.pinRequired());

        ScrollView sv = new ScrollView(a);
        sv.addView(box);
        new AlertDialog.Builder(a)
                .setTitle("设置")
                .setView(sv)
                .setNegativeButton("取消", null)
                .setPositiveButton("保存", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int w) {
                        Prefs.setShowHidden(hidden.isChecked());
                        Prefs.setRecvPrivate(rg.getCheckedRadioButtonId() == r2id);
                        Prefs.setAllowBrowse(browse.isChecked());
                        Prefs.setPinRequired(pin.isChecked());
                        Hub.get().setAllowBrowse(browse.isChecked());
                        Hub.get().setPinRequired(pin.isChecked());
                        if (onChanged != null) onChanged.run();
                    }
                })
                .show();
    }

    private static CheckBox check(Activity a, LinearLayout box, String text, boolean v) {
        CheckBox c = new CheckBox(a);
        c.setText(text);
        c.setChecked(v);
        c.setPadding(0, dp(a, 10), 0, dp(a, 10));
        box.addView(c);
        return c;
    }

    private static void header(Activity a, LinearLayout box, String t) {
        TextView tv = new TextView(a);
        tv.setText(t);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        tv.setTextColor(a.getColor(R.color.accent));
        tv.setPadding(0, dp(a, 16), 0, dp(a, 4));
        box.addView(tv);
    }

    private static void note(Activity a, LinearLayout box, String t) {
        TextView tv = new TextView(a);
        tv.setText(t);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        tv.setAlpha(0.7f);
        tv.setPadding(dp(a, 32), 0, 0, dp(a, 4));
        box.addView(tv);
    }

    static int dp(Activity a, int v) {
        return (int) (v * a.getResources().getDisplayMetrics().density + 0.5f);
    }
}
