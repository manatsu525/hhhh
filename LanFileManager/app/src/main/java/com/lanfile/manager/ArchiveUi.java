package com.lanfile.manager;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.text.InputType;
import android.view.LayoutInflater;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import com.lanfile.manager.archive.ArcEntry;
import com.lanfile.manager.archive.ArchiveReader;
import com.lanfile.manager.archive.ArchiveWriter;
import com.lanfile.manager.archive.Extractor;
import com.lanfile.manager.archive.Formats;
import com.lanfile.manager.archive.PasswordException;
import com.lanfile.manager.archive.Progress;
import com.lanfile.manager.core.Util;
import com.lanfile.manager.core.Vfs;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.util.Collection;
import java.util.List;

/** 压缩、解压的对话框和后台操作（文件管理器和压缩包查看器共用） */
final class ArchiveUi {
    private ArchiveUi() {}

    interface Done<T> {
        void done(T t);
    }

    // ======================= 密码 =======================

    static void askPassword(final Activity a, String name, boolean wrong, final Done<String> ok, final Runnable cancel) {
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(a, 20);
        box.setPadding(pad, dp(a, 8), pad, 0);
        final EditText et = new EditText(a);
        et.setSingleLine(true);
        et.setHint("密码");
        et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        box.addView(et);
        final CheckBox show = new CheckBox(a);
        show.setText("显示密码");
        show.setOnCheckedChangeListener((b, c) -> {
            et.setInputType(InputType.TYPE_CLASS_TEXT | (c ? InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD : InputType.TYPE_TEXT_VARIATION_PASSWORD));
            et.setSelection(et.length());
        });
        box.addView(show);
        AlertDialog d = new AlertDialog.Builder(a)
                .setTitle(wrong ? "密码错误，请重新输入" : "需要密码")
                .setMessage("「" + name + "」已加密")
                .setView(box)
                .setNegativeButton("取消", (x, w) -> {
                    if (cancel != null) cancel.run();
                })
                .setOnCancelListener(x -> {
                    if (cancel != null) cancel.run();
                })
                .setPositiveButton("确定", (x, w) -> ok.done(et.getText().toString()))
                .create();
        d.show();
        showKeyboard(et);
    }

    // ======================= 解压 =======================

    static final class ExtractTarget {
        String dir;
        Extractor.Conflict conflict;
    }

    /** 选择解压位置：parentDir 下新建「baseName」文件夹，或直接解压到 parentDir */
    static void chooseExtractTarget(final Activity a, final String parentDir, String baseName, final Done<ExtractTarget> ok,
                                    final Runnable cancel) {
        final boolean[] chosen = new boolean[1];
        View v = LayoutInflater.from(a).inflate(R.layout.dialog_extract, null);
        final RadioButton toFolder = v.findViewById(R.id.xToFolder);
        final RadioButton toHere = v.findViewById(R.id.xToHere);
        final EditText folder = v.findViewById(R.id.xFolder);
        final RadioGroup conflict = v.findViewById(R.id.xConflict);
        TextView where = v.findViewById(R.id.xWhere);
        where.setText("位置：" + pretty(parentDir));
        folder.setText(baseName);
        toFolder.setOnCheckedChangeListener((b, c) -> folder.setEnabled(c));
        folder.setOnFocusChangeListener((x, f) -> {
            if (f) toFolder.setChecked(true);
        });
        toHere.setText("直接解压到「" + Util.nameOf(parentDir) + "」");
        new AlertDialog.Builder(a)
                .setTitle("解压到")
                .setView(v)
                .setNegativeButton("取消", null)
                .setPositiveButton("解压", (d, w) -> {
                    ExtractTarget t = new ExtractTarget();
                    if (toFolder.isChecked()) {
                        String n = folder.getText().toString().trim();
                        if (n.isEmpty() || n.contains("/") || n.equals(".") || n.equals("..")) {
                            toast(a, "文件夹名称无效");
                            return;
                        }
                        t.dir = Util.join(parentDir, n);
                    } else {
                        t.dir = parentDir;
                    }
                    int c = conflict.getCheckedRadioButtonId();
                    t.conflict = c == R.id.xOverwrite ? Extractor.Conflict.OVERWRITE
                            : c == R.id.xSkip ? Extractor.Conflict.SKIP : Extractor.Conflict.RENAME;
                    chosen[0] = true;
                    ok.done(t);
                })
                .setOnDismissListener(x -> {
                    if (!chosen[0] && cancel != null) cancel.run();
                })
                .show();
    }

    /** 后台解压，遇到加密时询问密码后重试 */
    static void extract(final Activity a, final ArchiveReader r, final Collection<ArcEntry> sel, final String base,
                        final ExtractTarget t, final String archiveName, final Done<Boolean> finished) {
        final boolean[] ok = new boolean[1];
        OpRunner.run(a, "正在解压", p -> {
            mkdirs(t.dir);
            Extractor.extract(r, sel, base, App.vfs, t.dir, t.conflict, p, (path, mtime) -> written(p, path, mtime));
            ok[0] = true;
        }, () -> {
            if (ok[0] && finished != null) finished.done(true);
        }, e -> {
            if (e instanceof PasswordException) {
                askPassword(a, archiveName, ((PasswordException) e).wrong, pw -> {
                    r.setPassword(pw);
                    extract(a, r, sel, base, t, archiveName, finished);
                }, null);
                return true;
            }
            return false;
        });
    }

    static void mkdirs(String dir) throws IOException {
        if (App.vfs.stat(dir) != null) return;
        mkdirs(Util.parent(dir));
        App.vfs.mkdir(Util.parent(dir), Util.nameOf(dir));
    }

    private static void written(Progress p, String path, long mtime) {
        if (App.vfs.isRestricted(path)) return;
        if (mtime > 0) {
            //noinspection ResultOfMethodCallIgnored
            new File(path).setLastModified(mtime);
        }
        synchronized (p.scan) {
            if (p.scan.size() < 5000) p.scan.add(path);
        }
    }

    /** 文件管理器里「解压到…」：先打开压缩包（必要时询问密码），再选位置解压全部 */
    static void extractFromFile(final Activity a, final Vfs.Entry archive, final Runnable after) {
        openReader(a, archive.path, archive.name, null, r -> chooseExtractTarget(a, Util.parent(archive.path), Formats.baseName(archive.name), t ->
                extract(a, r, r.entries(), "", t, archive.name, ok -> {
                    closeQuietly(r);
                    toast(a, "已解压到 " + pretty(t.dir));
                    if (after != null) after.run();
                }), () -> closeQuietly(r)));
    }

    /** 在后台打开压缩包；受限目录里的压缩包先复制到应用缓存 */
    static void openReader(final Activity a, final String path, final String name, final String password, final Done<ArchiveReader> ok) {
        final ArchiveReader[] out = new ArchiveReader[1];
        OpRunner.run(a, "正在读取压缩包", p -> {
            p.current = name;
            File f = localFile(a, path, name, p);
            out[0] = ArchiveReader.open(f, name, password);
        }, () -> {
            if (out[0] != null) ok.done(out[0]);
        }, e -> {
            if (e instanceof PasswordException) {
                askPassword(a, name, ((PasswordException) e).wrong, pw -> openReader(a, path, name, pw, ok), null);
                return true;
            }
            return false;
        });
    }

    /** 普通路径直接返回；Android/data 等受限路径复制到缓存（随后自动清理） */
    static File localFile(Activity a, String path, String name, Progress p) throws IOException {
        if (!App.vfs.isRestricted(path)) return new File(path);
        File dir = new File(new File(a.getCacheDir(), "arc"), "src" + System.nanoTime());
        if (!dir.mkdirs() && !dir.isDirectory()) throw new IOException("无法创建缓存目录");
        File f = new File(dir, Util.safeName(name));
        Vfs.Entry st = App.vfs.stat(path);
        p.total = st == null ? -1 : st.size;
        try (InputStream in = App.vfs.openRead(path, 0); OutputStream os = new FileOutputStream(f)) {
            byte[] buf = new byte[256 * 1024];
            int k;
            while ((k = in.read(buf)) > 0) {
                p.check();
                os.write(buf, 0, k);
                p.done += k;
            }
        } catch (IOException e) {
            App.deleteTree(dir);
            throw e;
        }
        p.total = -1;
        p.done = 0;
        return f;
    }

    static void closeQuietly(ArchiveReader r) {
        try {
            r.close();
        } catch (Exception ignored) {
        }
        // 受限目录复制过来的临时文件
        File f = r.file();
        File parent = f.getParentFile();
        if (parent != null && parent.getName().startsWith("src") && parent.getParentFile() != null
                && parent.getParentFile().getName().equals("arc")) {
            App.deleteTree(parent);
        }
    }

    // ======================= 压缩 =======================

    static void compressDialog(final Activity a, final List<Vfs.Entry> sel, final String destDir, final Runnable after) {
        View v = LayoutInflater.from(a).inflate(R.layout.dialog_compress, null);
        final EditText name = v.findViewById(R.id.cName);
        final Spinner fmt = v.findViewById(R.id.cFormat);
        final Spinner level = v.findViewById(R.id.cLevel);
        final EditText pw = v.findViewById(R.id.cPassword);
        final CheckBox showPw = v.findViewById(R.id.cShowPw);
        final CheckBox encNames = v.findViewById(R.id.cEncNames);
        final View pwBox = v.findViewById(R.id.cPwBox);
        final TextView pwNote = v.findViewById(R.id.cPwNote);
        final TextView ext = v.findViewById(R.id.cExt);

        String def;
        if (sel.size() == 1) {
            Vfs.Entry e = sel.get(0);
            def = e.dir ? e.name : (e.name.lastIndexOf('.') > 0 ? e.name.substring(0, e.name.lastIndexOf('.')) : e.name);
        } else {
            def = Util.nameOf(destDir);
            if (def.isEmpty()) def = "压缩文件";
        }
        name.setText(def);
        name.setSelection(def.length());

        final ArchiveWriter.Format[] formats = ArchiveWriter.Format.values();
        String[] labels = new String[formats.length];
        for (int i = 0; i < formats.length; i++) labels[i] = formats[i].label;
        ArrayAdapter<String> fa = new ArrayAdapter<>(a, android.R.layout.simple_spinner_item, labels);
        fa.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        fmt.setAdapter(fa);
        int last = Prefs.zipFormat();
        fmt.setSelection(last >= 0 && last < formats.length ? last : 0);

        ArrayAdapter<String> la = new ArrayAdapter<>(a, android.R.layout.simple_spinner_item,
                new String[]{"仅存储（不压缩，最快）", "快速", "标准", "最高（较慢）"});
        la.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        level.setAdapter(la);
        level.setSelection(Prefs.zipLevel());

        final Runnable refresh = () -> {
            ArchiveWriter.Format f = formats[fmt.getSelectedItemPosition()];
            ext.setText("." + f.ext);
            pwBox.setVisibility(f.password ? View.VISIBLE : View.GONE);
            pwNote.setVisibility(f.password ? View.GONE : View.VISIBLE);
            encNames.setVisibility(f == ArchiveWriter.Format.SEVEN_Z ? View.VISIBLE : View.GONE);
            level.setEnabled(f != ArchiveWriter.Format.TAR);
        };
        fmt.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                refresh.run();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
        showPw.setOnCheckedChangeListener((b, c) -> {
            pw.setInputType(InputType.TYPE_CLASS_TEXT | (c ? InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD : InputType.TYPE_TEXT_VARIATION_PASSWORD));
            pw.setSelection(pw.length());
        });
        refresh.run();

        final AlertDialog d = new AlertDialog.Builder(a)
                .setTitle(sel.size() == 1 ? "压缩「" + sel.get(0).name + "」" : "压缩 " + sel.size() + " 项")
                .setView(v)
                .setNegativeButton("取消", null)
                .setPositiveButton("压缩", null)
                .create();
        d.show();
        d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(x -> {
            String n = name.getText().toString().trim();
            if (n.isEmpty() || n.contains("/")) {
                toast(a, "名称无效");
                return;
            }
            ArchiveWriter.Options o = new ArchiveWriter.Options();
            o.format = formats[fmt.getSelectedItemPosition()];
            o.level = o.format == ArchiveWriter.Format.TAR ? ArchiveWriter.STORE : level.getSelectedItemPosition();
            String p = pw.getText().toString();
            o.password = o.format.password && !p.isEmpty() ? p : null;
            o.encryptNames = o.password != null && encNames.isChecked();
            Prefs.setZipOptions(fmt.getSelectedItemPosition(), level.getSelectedItemPosition());
            d.dismiss();
            compress(a, sel, destDir, n, o, after);
        });
    }

    static void compress(final Activity a, final List<Vfs.Entry> sel, final String destDir, final String baseName,
                         final ArchiveWriter.Options o, final Runnable after) {
        final String[] result = new String[1];
        OpRunner.run(a, "正在压缩", p -> {
            List<ArchiveWriter.Item> items = ArchiveWriter.collect(App.vfs, sel, p);
            p.total = ArchiveWriter.totalSize(items);
            p.done = 0;
            String finalName = baseName + "." + o.format.ext;
            for (int i = 1; App.vfs.stat(Util.join(destDir, finalName)) != null; i++) {
                finalName = baseName + " (" + i + ")." + o.format.ext;
            }
            String partName = "." + finalName + ".lanpart";
            String partPath = Util.join(destDir, partName);
            if (App.vfs.stat(partPath) != null) App.vfs.delete(partPath);
            boolean restricted = App.vfs.isRestricted(destDir);
            Prefs.trackPartial(partPath, true);
            File tmp = null;
            boolean ok = false;
            try {
                if (o.format == ArchiveWriter.Format.SEVEN_Z) {
                    // 7z 需要随机写：普通目录直接写，受限目录先写到缓存再复制过去
                    File target = restricted ? (tmp = new File(new File(a.getCacheDir(), "arc"), "out" + System.nanoTime() + ".7z")) : new File(partPath);
                    //noinspection ResultOfMethodCallIgnored
                    target.getParentFile().mkdirs();
                    try (RandomAccessFile raf = new RandomAccessFile(target, "rw")) {
                        raf.setLength(0);
                        ArchiveWriter.write7z(App.vfs, items, o, raf, p);
                    }
                    if (tmp != null) {
                        p.current = "正在保存…";
                        try (InputStream in = new java.io.FileInputStream(tmp); OutputStream os = App.vfs.create(destDir, partName)) {
                            Util.copy(in, os, -1);
                        }
                    }
                } else {
                    ArchiveWriter.write(App.vfs, items, o, App.vfs.create(destDir, partName), p);
                }
                p.check();
                result[0] = App.vfs.rename(partPath, finalName);
                ok = true;
            } finally {
                if (tmp != null) //noinspection ResultOfMethodCallIgnored
                    tmp.delete();
                if (!ok) {
                    try {
                        App.vfs.delete(partPath);
                    } catch (IOException ignored) {
                    }
                }
                Prefs.trackPartial(partPath, false);
            }
            if (!restricted) {
                synchronized (p.scan) {
                    p.scan.add(result[0]);
                }
            }
        }, () -> {
            if (result[0] != null) toast(a, "已创建 " + Util.nameOf(result[0]));
            if (after != null) after.run();
        });
    }

    // ======================= 工具 =======================

    static String pretty(String path) {
        String root = App.vfs.primaryRoot();
        if (root != null && Util.isUnder(path, root)) return "内部存储" + path.substring(root.length());
        return path;
    }

    static void toast(Activity a, String s) {
        Toast.makeText(a, s, Toast.LENGTH_SHORT).show();
    }

    static int dp(Activity a, int v) {
        return (int) (v * a.getResources().getDisplayMetrics().density + 0.5f);
    }

    static void showKeyboard(final EditText et) {
        et.requestFocus();
        et.postDelayed(() -> {
            InputMethodManager imm = (InputMethodManager) et.getContext().getSystemService(Activity.INPUT_METHOD_SERVICE);
            if (imm != null) imm.showSoftInput(et, InputMethodManager.SHOW_IMPLICIT);
        }, 150);
    }

    static void openFolder(Activity a, String dir) {
        a.startActivity(new Intent(a, MainActivity.class).putExtra(MainActivity.EXTRA_PATH, dir)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP));
    }
}
