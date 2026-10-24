package com.lanfile.manager;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentResolver;
import android.content.Intent;
import android.database.Cursor;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import com.lanfile.manager.core.Util;
import com.lanfile.manager.core.Vfs;
import com.lanfile.manager.editor.EditorView;
import com.lanfile.manager.editor.Searcher;
import com.lanfile.manager.editor.TextBuffer;
import com.lanfile.manager.editor.TextIO;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.PatternSyntaxException;

/**
 * 内置文本编辑器（类似 Windows 记事本）。
 * 零残留：不写缓存、不自动备份；保存时先写同目录下的隐藏临时文件 .xxx.lanpart 再替换原文件，
 * 临时文件登记在 Prefs.partials 里，异常退出后下次启动会被 App 自动清理。
 */
public class EditorActivity extends Activity implements EditorView.Listener {

    public static final String EXTRA_PATH = "path";

    private static final int REQ_SAVE_AS = 41;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final ExecutorService finder = Executors.newSingleThreadExecutor();

    private EditorView ed;
    private View findBar, replaceRow, loadingBox;
    private EditText findInput, replaceInput;
    private TextView findCount, status, loadingText;
    private ProgressBar loadingBar;
    private CheckBox optCase, optWord, optRegex;

    // 文件来源：path（经由 AndroidVfs，支持 Android/data 等）或 uri（其他 app 传来的 content://）
    private String path;
    private Uri uri;
    private String name = "未命名";
    private long fileSize;

    private String charset = "UTF-8";
    private boolean bom;
    private int eol = TextIO.EOL_LF;
    private boolean metaDirty;

    private TextBuffer savedBuf;
    private int savedMod;
    private boolean loaded, busy, forceReadOnly;
    private volatile boolean loadCancel;

    private Searcher searcher;
    private Future<?> countJob;
    private int lastUndoState = -1;
    private boolean statusPosted;

    // ============================ 生命周期 ============================

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_editor);
        ed = findViewById(R.id.editor);
        findBar = findViewById(R.id.findBar);
        replaceRow = findViewById(R.id.replaceRow);
        loadingBox = findViewById(R.id.loadingBox);
        loadingBar = findViewById(R.id.loadingBar);
        loadingText = findViewById(R.id.loadingText);
        findInput = findViewById(R.id.findInput);
        replaceInput = findViewById(R.id.replaceInput);
        findCount = findViewById(R.id.findCount);
        status = findViewById(R.id.status);
        optCase = findViewById(R.id.optCase);
        optWord = findViewById(R.id.optWord);
        optRegex = findViewById(R.id.optRegex);

        ed.setListener(this);
        ed.setWrap(Prefs.edWrap());
        ed.setLineNumbers(Prefs.edLines());
        ed.setTextSizeSp(Prefs.edTextSize());
        setupFindBar();

        if (getActionBar() != null) getActionBar().setDisplayHomeAsUpEnabled(true);

        Intent it = getIntent();
        path = it.getStringExtra(EXTRA_PATH);
        if (path == null && it.getData() != null) {
            Uri d = it.getData();
            if ("file".equals(d.getScheme()) && d.getPath() != null) {
                path = d.getPath();
            } else if (ShareProvider.AUTH.equals(d.getAuthority()) && d.getPath() != null) {
                path = Util.normPath(d.getPath());
            } else {
                uri = d;
                boolean writable = Intent.ACTION_EDIT.equals(it.getAction())
                        || (it.getFlags() & Intent.FLAG_GRANT_WRITE_URI_PERMISSION) != 0;
                forceReadOnly = !writable && !canWriteUri(d);
            }
        }
        if (path == null && uri == null) {
            toast("没有可打开的文件");
            finish();
            return;
        }
        name = path != null ? Util.nameOf(path) : queryName(uri);
        updateTitle();
        load(null);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        loadCancel = true;
        countCancelRef[0] = true;
        io.shutdownNow();
        finder.shutdownNow();
    }

    private boolean canWriteUri(Uri u) {
        try {
            return checkCallingOrSelfUriPermission(u, Intent.FLAG_GRANT_WRITE_URI_PERMISSION) == android.content.pm.PackageManager.PERMISSION_GRANTED;
        } catch (Exception e) {
            return false;
        }
    }

    private String queryName(Uri u) {
        try (Cursor c = getContentResolver().query(u, new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE}, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                String n = c.getString(0);
                if (!c.isNull(1)) fileSize = c.getLong(1);
                if (n != null) return n;
            }
        } catch (Exception ignored) {
        }
        String p = u.getLastPathSegment();
        return p != null ? Util.nameOf(p) : "未命名";
    }

    // ============================ 打开 ============================

    private void load(final String forcedCharset) {
        loaded = false;
        loadCancel = false;
        loadingBox.setVisibility(View.VISIBLE);
        loadingBar.setProgress(0);
        loadingBar.setIndeterminate(true);
        loadingText.setText("正在打开…");
        io.execute(() -> {
            try {
                long size = -1;
                InputStream in;
                if (path != null) {
                    Vfs.Entry st = App.vfs.stat(path);
                    if (st == null) throw new IOException("文件不存在");
                    if (st.dir) throw new IOException("这是一个文件夹");
                    size = st.size;
                    in = App.vfs.openRead(path, 0);
                } else {
                    in = getContentResolver().openInputStream(uri);
                    if (in == null) throw new IOException("无法读取");
                    size = fileSize > 0 ? fileSize : -1;
                }
                final long total = size;
                fileSize = Math.max(0, size);
                final long[] lastUi = {0};
                TextIO.Loaded r;
                try {
                    r = TextIO.load(in, size, forcedCharset, (done, all) -> {
                        long now = System.currentTimeMillis();
                        if (now - lastUi[0] > 80 && all > 0) {
                            lastUi[0] = now;
                            final int pr = (int) (done * 1000 / all);
                            ui.post(() -> {
                                loadingBar.setIndeterminate(false);
                                loadingBar.setProgress(pr);
                                loadingText.setText("正在打开… " + Util.formatSize(done) + " / " + Util.formatSize(all));
                            });
                        }
                        return !loadCancel;
                    });
                } finally {
                    in.close();
                }
                final TextIO.Loaded res = r;
                final TextBuffer tb = new TextBuffer(res.chars, res.len, res.lineStarts, res.lineCount);
                res.chars = null;
                res.lineStarts = null;
                ui.post(() -> onLoaded(tb, res, forcedCharset != null));
            } catch (final OutOfMemoryError oom) {
                ui.post(() -> fail("文件太大，内存不足，无法打开"));
            } catch (final TextIO.CancelledException ce) {
                // 用户退出
            } catch (final Exception e) {
                ui.post(() -> fail("打开失败：" + e.getMessage()));
            }
        });
    }

    private void fail(String msg) {
        if (isFinishing()) return;
        new AlertDialog.Builder(this).setTitle("无法打开").setMessage(msg)
                .setPositiveButton("确定", (d, w) -> finish())
                .setOnCancelListener(d -> finish()).show();
    }

    private void onLoaded(TextBuffer tb, TextIO.Loaded r, boolean reopen) {
        if (isFinishing()) return;
        charset = r.charset;
        bom = r.bom;
        eol = r.eol;
        metaDirty = false;
        ed.setBuffer(tb, true);
        savedBuf = tb;
        savedMod = tb.modCount();
        loaded = true;
        loadingBox.setVisibility(View.GONE);
        ed.setReadOnly(forceReadOnly);
        if (searcher != null) applySearch();
        invalidateOptionsMenu();
        updateTitle();
        updateStatus();
        if (r.binaryLike && !reopen) {
            ed.setReadOnly(true);
            new AlertDialog.Builder(this).setTitle("可能是二进制文件")
                    .setMessage("这个文件里有大量不可见字符，可能不是文本文件。编辑后保存可能会损坏它，已用只读方式打开。")
                    .setPositiveButton("保持只读", null)
                    .setNegativeButton("仍然编辑", (d, w) -> {
                        ed.setReadOnly(forceReadOnly);
                        invalidateOptionsMenu();
                    }).show();
        } else if (r.malformed) {
            toast("部分字节无法按 " + charset + " 解码，可在菜单里换编码重新打开");
        } else if (tb.length() == 0 && !forceReadOnly) {
            ed.post(ed::showKeyboard);
        }
    }

    // ============================ 状态 ============================

    private boolean modified() {
        return loaded && (ed.buffer() != savedBuf || ed.buffer().modCount() != savedMod || metaDirty);
    }

    private void updateTitle() {
        String t = (modified() ? "*" : "") + name;
        setTitle(t);
        if (getActionBar() != null) getActionBar().setTitle(t);
    }

    @Override
    public void onEditorChanged() {
        if (statusPosted) return;
        statusPosted = true;
        ui.post(() -> {
            statusPosted = false;
            updateStatus();
            int st = (ed.canUndo() ? 1 : 0) | (ed.canRedo() ? 2 : 0) | (modified() ? 4 : 0);
            if (st != lastUndoState) {
                lastUndoState = st;
                invalidateOptionsMenu();
                updateTitle();
            }
        });
    }

    private void updateStatus() {
        if (!loaded) return;
        int[] lc = ed.lineCol();
        StringBuilder sb = new StringBuilder();
        sb.append("行 ").append(lc[0]).append("，列 ").append(lc[1]);
        int sel = ed.selEnd() - ed.selStart();
        if (sel > 0) sb.append("（选中 ").append(sel).append("）");
        sb.append("  ·  共 ").append(ed.buffer().lineCount()).append(" 行");
        sb.append("  ·  ").append(charset).append(bom ? " BOM" : "");
        sb.append("  ·  ").append(TextIO.EOL_SHORT[eol]);
        if (ed.readOnly()) sb.append("  ·  只读");
        status.setText(sb);
    }

    // ============================ 菜单 ============================

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.editor, menu);
        return true;
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu m) {
        boolean ro = ed.readOnly();
        setEnabled(m.findItem(R.id.e_undo), loaded && !ro && ed.canUndo());
        setEnabled(m.findItem(R.id.e_redo), loaded && !ro && ed.canRedo());
        setEnabled(m.findItem(R.id.e_save), loaded && !ro);
        m.findItem(R.id.e_replace).setEnabled(loaded && !ro);
        m.findItem(R.id.e_wrap).setChecked(ed.wrap());
        m.findItem(R.id.e_lines).setChecked(ed.lineNumbers());
        m.findItem(R.id.e_readonly).setChecked(ro);
        m.findItem(R.id.e_readonly).setEnabled(!forceReadOnly);
        return super.onPrepareOptionsMenu(m);
    }

    private static void setEnabled(MenuItem it, boolean en) {
        it.setEnabled(en);
        if (it.getIcon() != null) it.getIcon().mutate().setAlpha(en ? 255 : 90);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == android.R.id.home) {
            onBackPressed();
        } else if (!loaded || busy) {
            return true;
        } else if (id == R.id.e_undo) {
            ed.undo();
        } else if (id == R.id.e_redo) {
            ed.redo();
        } else if (id == R.id.e_find) {
            openFind(false);
        } else if (id == R.id.e_replace) {
            openFind(true);
        } else if (id == R.id.e_save) {
            save(null);
        } else if (id == R.id.e_saveas) {
            saveAs();
        } else if (id == R.id.e_goto) {
            gotoLine();
        } else if (id == R.id.e_wrap) {
            ed.setWrap(!ed.wrap());
            Prefs.setEdWrap(ed.wrap());
            ed.ensureVisible(ed.cursor(), true);
            invalidateOptionsMenu();
        } else if (id == R.id.e_lines) {
            ed.setLineNumbers(!ed.lineNumbers());
            Prefs.setEdLines(ed.lineNumbers());
            invalidateOptionsMenu();
        } else if (id == R.id.e_readonly) {
            ed.setReadOnly(!ed.readOnly());
            invalidateOptionsMenu();
            updateStatus();
        } else if (id == R.id.e_font) {
            chooseFont();
        } else if (id == R.id.e_charset) {
            chooseSaveCharset();
        } else if (id == R.id.e_eol) {
            chooseEol();
        } else if (id == R.id.e_reopen) {
            reopenWith();
        } else if (id == R.id.e_info) {
            info();
        } else return super.onOptionsItemSelected(item);
        return true;
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent ev) {
        if (ev.isCtrlPressed() && loaded) {
            switch (keyCode) {
                case KeyEvent.KEYCODE_S:
                    if (!ed.readOnly()) save(null);
                    return true;
                case KeyEvent.KEYCODE_F:
                    openFind(false);
                    return true;
                case KeyEvent.KEYCODE_H:
                    if (!ed.readOnly()) openFind(true);
                    return true;
                case KeyEvent.KEYCODE_G:
                    gotoLine();
                    return true;
            }
        }
        if (keyCode == KeyEvent.KEYCODE_F3 && loaded) {
            findStep(!ev.isShiftPressed());
            return true;
        }
        return super.onKeyDown(keyCode, ev);
    }

    @Override
    public void onBackPressed() {
        if (findBar.getVisibility() == View.VISIBLE) {
            closeFind();
            return;
        }
        if (busy) return;
        if (!loaded) {
            loadCancel = true;
            finish();
            return;
        }
        if (modified()) {
            new AlertDialog.Builder(this).setTitle("保存修改？")
                    .setMessage("「" + name + "」有未保存的修改。")
                    .setPositiveButton("保存", (d, w) -> save(this::finish))
                    .setNegativeButton("不保存", (d, w) -> finish())
                    .setNeutralButton("取消", null).show();
            return;
        }
        finish();
    }

    // ============================ 保存 ============================

    private AlertDialog progress(String title) {
        ProgressBar pb = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        pb.setIndeterminate(true);
        FrameLayout f = new FrameLayout(this);
        int p = (int) (20 * getResources().getDisplayMetrics().density);
        f.setPadding(p, p / 2, p, 0);
        f.addView(pb);
        return new AlertDialog.Builder(this).setTitle(title).setView(f).setCancelable(false).show();
    }

    private void save(final Runnable after) {
        if (busy) return;
        if (path == null && uri == null) return;
        ed.finishActionMode();
        final TextBuffer b = ed.buffer();
        final char[] arr = b.compact();
        final int len = b.length();
        final int mod = b.modCount();
        final String cs = charset;
        final boolean withBom = bom;
        final int e = eol;
        final String p = path;
        final Uri u = uri;
        busy = true;
        final AlertDialog d = progress("正在保存…");
        io.execute(() -> {
            Exception err = null;
            try {
                if (p != null) writePath(p, arr, len, cs, withBom, e);
                else writeUri(u, arr, len, cs, withBom, e);
            } catch (Exception ex) {
                err = ex;
            } catch (OutOfMemoryError oom) {
                err = new IOException("内存不足");
            }
            final Exception fe = err;
            ui.post(() -> {
                busy = false;
                if (!isFinishing()) d.dismiss();
                if (fe == null) {
                    savedBuf = b;
                    savedMod = mod;
                    metaDirty = false;
                    lastUndoState = -1;
                    onEditorChanged();
                    updateTitle();
                    toast("已保存");
                    setResult(RESULT_OK);
                    if (after != null) after.run();
                } else if (fe instanceof TextIO.UnmappableException) {
                    final TextIO.UnmappableException ue = (TextIO.UnmappableException) fe;
                    new AlertDialog.Builder(this).setTitle("编码不支持")
                            .setMessage(fe.getMessage())
                            .setPositiveButton("改用 UTF-8 保存", (dd, w) -> {
                                charset = "UTF-8";
                                bom = false;
                                metaDirty = true;
                                updateStatus();
                                save(after);
                            })
                            .setNeutralButton("查看该字符", (dd, w) -> ed.select(ue.offset, Math.min(ed.buffer().length(), ue.offset + 1), true))
                            .setNegativeButton("取消", null).show();
                } else {
                    new AlertDialog.Builder(this).setTitle("保存失败")
                            .setMessage(String.valueOf(fe.getMessage()) + "\n\n可以尝试「另存为」到其他位置。")
                            .setPositiveButton("另存为…", (dd, w) -> saveAs())
                            .setNegativeButton("取消", null).show();
                }
            });
        });
    }

    /** 先写临时文件，成功后再替换原文件；中途失败原文件不受影响 */
    private void writePath(String p, char[] arr, int len, String cs, boolean withBom, int e) throws IOException {
        String parent = Util.parent(p);
        String nm = Util.nameOf(p);
        String tmpName = "." + nm + ".lanpart";
        String tmpPath = Util.join(parent, tmpName);
        Prefs.trackPartial(tmpPath, true);
        boolean ok = false;
        try {
            if (!App.vfs.isRestricted(p)) {
                File f = new File(p);
                File tmp = new File(tmpPath);
                try (FileOutputStream fos = new FileOutputStream(tmp)) {
                    TextIO.save(arr, len, fos, cs, withBom, e, null);
                    fos.getFD().sync();
                }
                if (!tmp.renameTo(f)) {
                    // 个别文件系统不支持覆盖式 rename
                    if (f.exists() && !f.delete()) throw new IOException("无法覆盖原文件（没有写入权限？）");
                    if (!tmp.renameTo(f)) throw new IOException("无法写入原文件");
                }
                ok = true;
                MediaScannerConnection.scanFile(getApplicationContext(), new String[]{p}, null, null);
            } else {
                if (App.vfs.stat(tmpPath) != null) App.vfs.delete(tmpPath);
                try (OutputStream os = App.vfs.create(parent, tmpName)) {
                    TextIO.save(arr, len, os, cs, withBom, e, null);
                }
                if (App.vfs.stat(p) != null) App.vfs.delete(p);
                App.vfs.rename(tmpPath, nm);
                ok = true;
            }
        } finally {
            if (!ok) {
                try {
                    if (!App.vfs.isRestricted(tmpPath)) {
                        //noinspection ResultOfMethodCallIgnored
                        new File(tmpPath).delete();
                    } else if (App.vfs.stat(tmpPath) != null) App.vfs.delete(tmpPath);
                } catch (Exception ignored) {
                }
            }
            Prefs.trackPartial(tmpPath, false);
        }
    }

    private void writeUri(Uri u, char[] arr, int len, String cs, boolean withBom, int e) throws IOException {
        ContentResolver cr = getContentResolver();
        OutputStream os;
        try {
            os = cr.openOutputStream(u, "wt");
        } catch (Exception ex) {
            os = null;
        }
        if (os == null) {
            try {
                os = cr.openOutputStream(u, "w");
            } catch (Exception ex) {
                throw new IOException("对方应用不允许写回这个文件");
            }
        }
        if (os == null) throw new IOException("对方应用不允许写回这个文件");
        try (OutputStream o = os) {
            TextIO.save(arr, len, o, cs, withBom, e, null);
        }
    }

    private void saveAs() {
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("text/plain");
        i.putExtra(Intent.EXTRA_TITLE, name);
        try {
            startActivityForResult(i, REQ_SAVE_AS);
        } catch (Exception e) {
            toast("系统没有可用的文件选择器");
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_SAVE_AS && res == RESULT_OK && data != null && data.getData() != null) {
            Uri u = data.getData();
            // 能换算成普通路径就走路径（支持原子替换），否则直接写 content uri
            String p = pathFromDocUri(u);
            if (p != null && !App.vfs.isRestricted(p) && new File(p).exists()) {
                path = p;
                uri = null;
            } else {
                path = null;
                uri = u;
            }
            name = path != null ? Util.nameOf(path) : queryName(u);
            forceReadOnly = false;
            ed.setReadOnly(false);
            metaDirty = true;
            updateTitle();
            save(null);
        }
    }

    private static String pathFromDocUri(Uri u) {
        try {
            if (!"com.android.externalstorage.documents".equals(u.getAuthority())) return null;
            String id = android.provider.DocumentsContract.getDocumentId(u);
            int c = id.indexOf(':');
            if (c < 0) return null;
            String vol = id.substring(0, c), rel = id.substring(c + 1);
            if ("primary".equalsIgnoreCase(vol)) return Util.join("/storage/emulated/0", rel);
            return Util.join("/storage/" + vol, rel);
        } catch (Exception e) {
            return null;
        }
    }

    // ============================ 设置类对话框 ============================

    private void gotoLine() {
        final EditText et = new EditText(this);
        et.setInputType(InputType.TYPE_CLASS_NUMBER);
        et.setHint("1 - " + ed.buffer().lineCount());
        et.setText(String.valueOf(ed.lineCol()[0]));
        et.selectAll();
        FrameLayout f = new FrameLayout(this);
        int p = (int) (20 * getResources().getDisplayMetrics().density);
        f.setPadding(p, p / 3, p, 0);
        f.addView(et);
        final AlertDialog d = new AlertDialog.Builder(this).setTitle("跳转到行").setView(f)
                .setNegativeButton("取消", null)
                .setPositiveButton("跳转", (di, w) -> {
                    try {
                        int n = Integer.parseInt(et.getText().toString().trim());
                        ed.gotoLine(n - 1);
                        ed.requestFocus();
                    } catch (NumberFormatException ignored) {
                    }
                }).create();
        d.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
        d.show();
    }

    private void chooseFont() {
        final int[] sizes = {10, 12, 13, 14, 15, 16, 18, 20, 22, 24, 28, 32};
        String[] labels = new String[sizes.length];
        int cur = Math.round(ed.textSizeSp());
        int sel = -1;
        for (int i = 0; i < sizes.length; i++) {
            labels[i] = sizes[i] + " sp";
            if (sizes[i] == cur) sel = i;
        }
        new AlertDialog.Builder(this).setTitle("字体大小（也可以双指缩放）")
                .setSingleChoiceItems(labels, sel, (d, w) -> {
                    ed.setTextSizeSp(sizes[w]);
                    Prefs.setEdTextSize(sizes[w]);
                    d.dismiss();
                }).show();
    }

    private void chooseSaveCharset() {
        final String[] opts = new String[TextIO.CHARSETS.length + 1];
        opts[0] = "UTF-8";
        opts[1] = "UTF-8（带 BOM）";
        System.arraycopy(TextIO.CHARSETS, 1, opts, 2, TextIO.CHARSETS.length - 1);
        int sel = -1;
        for (int i = 0; i < opts.length; i++) {
            String cs = i == 1 ? "UTF-8" : (i == 0 ? "UTF-8" : opts[i]);
            boolean b = i == 1 || (bom && cs.startsWith("UTF-16"));
            if (cs.equals(charset) && (i > 1 || b == bom)) sel = i;
        }
        new AlertDialog.Builder(this).setTitle("保存时使用的编码")
                .setSingleChoiceItems(opts, sel, (d, w) -> {
                    String cs = w <= 1 ? "UTF-8" : opts[w];
                    boolean b = w == 1 || (cs.startsWith("UTF-16") && bom);
                    if (!cs.equals(charset) || b != bom) {
                        charset = cs;
                        bom = b;
                        metaDirty = true;
                        updateStatus();
                        updateTitle();
                        invalidateOptionsMenu();
                    }
                    d.dismiss();
                }).show();
    }

    private void chooseEol() {
        new AlertDialog.Builder(this).setTitle("换行符")
                .setSingleChoiceItems(TextIO.EOL_NAMES, eol, (d, w) -> {
                    if (w != eol) {
                        eol = w;
                        metaDirty = true;
                        updateStatus();
                        updateTitle();
                    }
                    d.dismiss();
                }).show();
    }

    private void reopenWith() {
        final String[] opts = TextIO.CHARSETS;
        Runnable pick = () -> new AlertDialog.Builder(this).setTitle("用以下编码重新打开")
                .setItems(opts, (d, w) -> {
                    ed.setBuffer(new TextBuffer(), true); // 先释放旧内容占用的内存
                    load(opts[w]);
                }).show();
        if (modified()) {
            new AlertDialog.Builder(this).setTitle("放弃修改？")
                    .setMessage("重新打开会丢弃未保存的修改。")
                    .setPositiveButton("继续", (d, w) -> pick.run())
                    .setNegativeButton("取消", null).show();
        } else pick.run();
    }

    private void info() {
        TextBuffer b = ed.buffer();
        StringBuilder sb = new StringBuilder();
        sb.append("名称：").append(name).append('\n');
        if (path != null) sb.append("位置：").append(Util.parent(path)).append('\n');
        else sb.append("来源：").append(uri).append('\n');
        if (fileSize > 0) sb.append("打开时大小：").append(Util.formatSize(fileSize)).append('\n');
        sb.append("字符数：").append(b.length()).append('\n');
        sb.append("行数：").append(b.lineCount()).append('\n');
        sb.append("编码：").append(charset).append(bom ? "（带 BOM）" : "").append('\n');
        sb.append("换行符：").append(TextIO.EOL_NAMES[eol]);
        new AlertDialog.Builder(this).setTitle("文件信息").setMessage(sb).setPositiveButton("确定", null).show();
    }

    // ============================ 查找 / 替换 ============================

    private final Runnable searchDebounce = this::applySearch;

    private void setupFindBar() {
        findInput.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                ui.removeCallbacks(searchDebounce);
                ui.postDelayed(searchDebounce, 250);
            }
        });
        findInput.setOnEditorActionListener((v, act, ev) -> {
            if (act == EditorInfo.IME_ACTION_SEARCH || act == EditorInfo.IME_ACTION_DONE
                    || (ev != null && ev.getKeyCode() == KeyEvent.KEYCODE_ENTER && ev.getAction() == KeyEvent.ACTION_DOWN)) {
                ui.removeCallbacks(searchDebounce);
                applySearch();
                findStep(true);
                return true;
            }
            return false;
        });
        View.OnClickListener opt = v -> applySearch();
        optCase.setOnClickListener(opt);
        optWord.setOnClickListener(opt);
        optRegex.setOnClickListener(opt);
        findViewById(R.id.findNext).setOnClickListener(v -> findStep(true));
        findViewById(R.id.findPrev).setOnClickListener(v -> findStep(false));
        findViewById(R.id.findClose).setOnClickListener(v -> closeFind());
        findViewById(R.id.toggleReplace).setOnClickListener(v -> {
            if (ed.readOnly()) {
                toast("只读模式下不能替换");
                return;
            }
            showReplace(replaceRow.getVisibility() != View.VISIBLE);
        });
        findViewById(R.id.replaceOne).setOnClickListener(v -> replaceOne());
        findViewById(R.id.replaceAll).setOnClickListener(v -> replaceAll());
    }

    private void showReplace(boolean show) {
        replaceRow.setVisibility(show ? View.VISIBLE : View.GONE);
        ((TextView) findViewById(R.id.toggleReplace)).setText(show ? "替换 ▴" : "替换 ▾");
    }

    private void openFind(boolean replace) {
        if (!loaded) return;
        findBar.setVisibility(View.VISIBLE);
        showReplace(replace && !ed.readOnly());
        // 选中的短文本作为查找内容
        int s = ed.selStart(), e = ed.selEnd();
        if (e > s && e - s < 200) {
            String t = ed.buffer().substring(s, e);
            if (t.indexOf('\n') < 0) findInput.setText(t);
        }
        findInput.requestFocus();
        findInput.selectAll();
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        findInput.postDelayed(() -> imm.showSoftInput(findInput, 0), 100);
        applySearch();
    }

    private void closeFind() {
        findBar.setVisibility(View.GONE);
        searcher = null;
        ed.setSearcher(null);
        countCancelRef[0] = true;
        ed.requestFocus();
    }

    private void applySearch() {
        if (findBar.getVisibility() != View.VISIBLE) return;
        String q = findInput.getText().toString();
        countCancelRef[0] = true;
        if (q.isEmpty()) {
            searcher = null;
            ed.setSearcher(null);
            findCount.setText("");
            return;
        }
        try {
            searcher = new Searcher(q, optCase.isChecked(), optWord.isChecked(), optRegex.isChecked());
        } catch (PatternSyntaxException e) {
            searcher = null;
            ed.setSearcher(null);
            findCount.setText("正则有误");
            return;
        }
        ed.setSearcher(searcher);
        startCount();
    }

    private void startCount() {
        if (searcher == null) return;
        final Searcher s = searcher;
        final TextBuffer b = ed.buffer();
        final char[] arr = b.compact();
        final int len = b.length();
        final int mod = b.modCount();
        final boolean[] cancel = new boolean[1];
        countCancelRef[0] = true;
        countCancelRef = cancel;
        findCount.setText(len > 2_000_000 ? "…" : "");
        if (countJob != null) countJob.cancel(false);
        // 用独立的取消标记，避免新旧任务互相影响
        countJob = finder.submit(() -> {
            int n;
            try {
                n = s.count(arr, len, cancel);
            } catch (RuntimeException | StackOverflowError e) {
                n = -2;
            }
            final int fn = n;
            ui.post(() -> {
                if (s != searcher || b != ed.buffer() || b.modCount() != mod) return;
                if (fn == -2) findCount.setText("出错");
                else if (fn >= 0) findCount.setText(fn == 0 ? "无结果" : fn + " 个");
            });
        });
    }

    private boolean[] countCancelRef = new boolean[1];

    private void findStep(final boolean forward) {
        if (searcher == null) {
            if (findBar.getVisibility() != View.VISIBLE) openFind(false);
            return;
        }
        final Searcher s = searcher;
        final TextBuffer b = ed.buffer();
        final char[] arr = b.compact();
        final int len = b.length();
        final int mod = b.modCount();
        final int from = forward ? ed.selEnd() : ed.selStart();
        finder.execute(() -> {
            int[] r;
            boolean wrapped = false;
            try {
                if (forward) {
                    r = s.next(arr, len, from);
                    if (r == null && from > 0) {
                        r = s.next(arr, len, 0);
                        wrapped = r != null;
                    }
                } else {
                    r = s.prev(arr, len, from);
                    if (r == null && from < len) {
                        r = s.prev(arr, len, len);
                        wrapped = r != null;
                    }
                }
            } catch (RuntimeException | StackOverflowError e) {
                r = null;
            }
            final int[] fr = r;
            final boolean fw = wrapped;
            ui.post(() -> {
                if (b != ed.buffer() || b.modCount() != mod) return;
                if (fr == null) {
                    toast("找不到「" + s.query + "」");
                    return;
                }
                if (fw) toast(forward ? "已从开头继续查找" : "已从末尾继续查找");
                ed.select(fr[0], fr[1], true);
            });
        });
    }

    private void replaceOne() {
        if (searcher == null || ed.readOnly()) return;
        TextBuffer b = ed.buffer();
        int s = ed.selStart(), e = ed.selEnd();
        if (e > s) {
            char[] arr = b.compact();
            int len = b.length();
            if (searcher.matchesAt(arr, len, s, e)) {
                String rep = searcher.replacement(arr, len, s, e, replaceInput.getText().toString());
                ed.replaceSelection(rep);
                startCount();
            }
        }
        findStep(true);
    }

    private void replaceAll() {
        if (searcher == null || ed.readOnly() || busy) return;
        final Searcher s = searcher;
        final String rep = replaceInput.getText().toString();
        final TextBuffer b = ed.buffer();
        final char[] arr = b.compact();
        final int len = b.length();
        busy = true;
        final AlertDialog d = progress("正在全部替换…");
        finder.execute(() -> {
            String err = null;
            TextBuffer nb = null;
            int count = 0;
            try {
                if ((long) len + 1024 > TextIO.charBudget()) {
                    err = "内存不足，无法对这么大的文件执行全部替换";
                } else {
                    Searcher.ReplaceResult r = s.replaceAll(arr, len, rep, new boolean[1]);
                    count = r.count;
                    if (count > 0) nb = new TextBuffer(r.chars, r.len, null, 0);
                }
            } catch (OutOfMemoryError oom) {
                err = "内存不足，无法完成全部替换";
            } catch (RuntimeException | StackOverflowError ex) {
                err = "替换出错：" + ex.getMessage();
            }
            final String fe = err;
            final TextBuffer fnb = nb;
            final int fc = count;
            ui.post(() -> {
                busy = false;
                if (!isFinishing()) d.dismiss();
                if (fe != null) {
                    toast(fe);
                    return;
                }
                if (b != ed.buffer()) return;
                if (fnb == null) {
                    toast("没有可替换的内容");
                    return;
                }
                ed.replaceWhole(fnb);
                toast("已替换 " + fc + " 处");
                startCount();
            });
        });
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
