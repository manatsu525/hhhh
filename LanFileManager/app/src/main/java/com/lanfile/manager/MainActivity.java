package com.lanfile.manager;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.provider.Settings;
import android.util.SparseBooleanArray;
import android.util.TypedValue;
import android.view.ActionMode;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.SearchView;
import android.widget.TextView;
import android.widget.Toast;

import com.lanfile.manager.core.Util;
import com.lanfile.manager.core.Vfs;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.Collator;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity implements AbsListView.MultiChoiceModeListener, ShizukuHelper.Listener {

    public static final String EXTRA_PICK = "pick";
    public static final String EXTRA_PATH = "path";
    public static final String EXTRA_PATHS = "paths";
    private static final int REQ_TREE = 21;
    private static final int REQ_STORAGE = 22;

    private AndroidVfs vfs;
    private boolean pickMode;
    private String cur;
    private List<Vfs.Entry> all = new ArrayList<>();
    private String filter = "";
    private final Map<String, Integer> scrollPos = new HashMap<>();
    private int loadGen;
    private boolean loadedOnce;

    private ListView list;
    private FileAdapter adapter;
    private TextView emptyView;
    private ProgressBar loading;
    private View permPanel, pasteBar;
    private TextView pasteText;
    private LinearLayout crumbs;
    private HorizontalScrollView crumbScroll;

    private List<Vfs.Entry> clip;
    private boolean clipCut;
    private String pendingGrant, pendingTarget;
    private ActionMode actionMode;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final ExecutorService ops = Executors.newSingleThreadExecutor();
    private final SimpleDateFormat df = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA);

    // ======================= 生命周期 =======================

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        vfs = App.vfs;
        pickMode = getIntent().getBooleanExtra(EXTRA_PICK, false);

        list = findViewById(R.id.list);
        emptyView = findViewById(R.id.empty);
        loading = findViewById(R.id.loading);
        permPanel = findViewById(R.id.permPanel);
        pasteBar = findViewById(R.id.pasteBar);
        pasteText = findViewById(R.id.pasteText);
        crumbs = findViewById(R.id.crumbs);
        crumbScroll = findViewById(R.id.crumbScroll);

        adapter = new FileAdapter();
        list.setAdapter(adapter);
        list.setChoiceMode(ListView.CHOICE_MODE_MULTIPLE_MODAL);
        list.setMultiChoiceModeListener(this);
        list.setOnItemClickListener((p, v, pos, id) -> onItemClick(pos));

        findViewById(R.id.permBtn).setOnClickListener(v -> requestStorage());
        findViewById(R.id.pasteBtn).setOnClickListener(v -> paste());
        findViewById(R.id.pasteCancel).setOnClickListener(v -> clearClip());

        if (getActionBar() != null) {
            if (pickMode) {
                getActionBar().setTitle("选择要发送到电脑的文件");
                getActionBar().setSubtitle("点选文件，然后点右上角「发送」");
                getActionBar().setDisplayHomeAsUpEnabled(true);
            }
        }
        String start = getIntent().getStringExtra(EXTRA_PATH);
        cur = start != null ? start : vfs.primaryRoot();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        String p = intent.getStringExtra(EXTRA_PATH);
        if (p != null) load(p);
    }

    @Override
    protected void onPause() {
        super.onPause();
        ShizukuHelper.removeListener(this);
    }

    @Override
    public void onShizukuChanged() {
        ui.post(() -> {
            if (isFinishing()) return;
            if (pendingTarget != null && ShizukuHelper.usable()) {
                String t = pendingTarget;
                pendingTarget = null;
                toast("已通过 Shizuku 获得访问权限");
                load(t);
            } else if (cur != null && vfs.isRestricted(cur) || "Android".equals(Util.nameOf(String.valueOf(cur)))) {
                load(cur);
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        ShizukuHelper.addListener(this);
        if (hasStorage()) {
            permPanel.setVisibility(View.GONE);
            vfs.refreshRoots();
            load(cur);
        } else {
            permPanel.setVisibility(View.VISIBLE);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        io.shutdownNow();
    }

    // ======================= 权限 =======================

    private boolean hasStorage() {
        if (Build.VERSION.SDK_INT >= 30) return Environment.isExternalStorageManager();
        return checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestStorage() {
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:" + getPackageName())));
            } catch (Exception e) {
                startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
            }
        } else {
            requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_STORAGE);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode == REQ_STORAGE && hasStorage()) {
            permPanel.setVisibility(View.GONE);
            load(cur);
        }
    }

    /** 申请访问 Android/data、obb：优先 Shizuku（Android 14+ 唯一可靠方式），也可用系统授权（SAF） */
    private void askGrant(final String grantPath, final String target) {
        String name = Util.nameOf(grantPath);
        boolean perApp = Build.VERSION.SDK_INT >= 33 && !name.equals("data") && !name.equals("obb");
        boolean safBlocked = Build.VERSION.SDK_INT >= 34;
        final ShizukuHelper.State st = ShizukuHelper.state();
        StringBuilder msg = new StringBuilder();
        msg.append("系统限制普通应用访问各 app 的私有目录，有两种方式可以解决：\n\n");
        msg.append("① Shizuku（推荐").append(safBlocked ? "，Android 14 及以上基本只能用它" : "").append("）\n");
        msg.append("授权一次即可访问所有应用的 data / obb，速度快。当前状态：").append(ShizukuHelper.stateText()).append("\n");
        if (st == ShizukuHelper.State.NOT_INSTALLED) msg.append("需要先安装 Shizuku，并按其说明通过无线调试启动。\n");
        else if (st == ShizukuHelper.State.NOT_RUNNING) msg.append("请打开 Shizuku，用「无线调试」或 root 方式启动服务后回到这里。\n");
        msg.append("\n② 系统授权（SAF）\n");
        if (perApp) msg.append("需要逐个应用授权：在系统选择器中直接点「使用此文件夹」→「允许」。");
        else msg.append("在系统选择器中不要切换目录，直接点「使用此文件夹」→「允许」，只需一次。");
        if (safBlocked) msg.append("\n部分 Android 14 及以上系统已禁止此方式，选择器会提示「无法使用此文件夹」。");
        msg.append("\n\n两种授权都由系统/Shizuku 保存，卸载本应用后自动失效，不留残留。");

        String shzBtn;
        switch (st) {
            case NO_PERMISSION: shzBtn = "用 Shizuku 授权"; break;
            case NOT_RUNNING: shzBtn = "打开 Shizuku"; break;
            case READY: shzBtn = "重新连接 Shizuku"; break;
            default: shzBtn = "下载 Shizuku";
        }
        new AlertDialog.Builder(this)
                .setTitle("需要授权访问")
                .setMessage(msg.toString())
                .setPositiveButton(shzBtn, (d, w) -> shizukuAction(st, target))
                .setNeutralButton(safBlocked ? "尝试系统授权" : "系统授权", (d, w) -> launchTree(grantPath, target))
                .setNegativeButton("取消", null)
                .show();
    }

    private void shizukuAction(ShizukuHelper.State st, String target) {
        pendingTarget = target;
        switch (st) {
            case NO_PERMISSION:
                ShizukuHelper.requestPermission();
                break;
            case READY:
                ShizukuHelper.bind();
                if (target != null) load(target);
                break;
            case NOT_RUNNING:
                Intent i = getPackageManager().getLaunchIntentForPackage(ShizukuHelper.SHIZUKU_PKG);
                if (i != null) startActivity(i);
                else toast("无法打开 Shizuku");
                break;
            default:
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://shizuku.rikka.app/zh-hans/download/")));
                } catch (ActivityNotFoundException e) {
                    toast("请在浏览器中搜索 Shizuku 下载");
                }
        }
    }

    private void shizukuStatus() {
        final ShizukuHelper.State st = ShizukuHelper.state();
        String action;
        switch (st) {
            case NO_PERMISSION: action = "授权"; break;
            case NOT_RUNNING: action = "打开 Shizuku"; break;
            case READY: action = "打开应用私有目录"; break;
            default: action = "下载 Shizuku";
        }
        new AlertDialog.Builder(this)
                .setTitle("Shizuku")
                .setMessage("状态：" + ShizukuHelper.stateText() + "\n\n"
                        + "Shizuku 让本应用以 shell 身份访问 Android/data、Android/obb（Android 14 及以上的推荐方式）。\n\n"
                        + "启动方法：安装 Shizuku → 打开它 → 用「无线调试」配对并启动（或使用 root）→ 回到本应用授权。\n"
                        + "注意：未 root 的手机重启后需要在 Shizuku 中重新启动一次。")
                .setPositiveButton(action, (d, w) -> {
                    if (st == ShizukuHelper.State.READY) load(vfs.primaryRoot() + "/Android/data");
                    else shizukuAction(st, vfs.primaryRoot() + "/Android/data");
                })
                .setNegativeButton("关闭", null)
                .show();
    }

    private void launchTree(String grantPath, String target) {
        pendingGrant = grantPath;
        pendingTarget = target;
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        if (Build.VERSION.SDK_INT >= 26) i.putExtra(DocumentsContract.EXTRA_INITIAL_URI, vfs.initialUriFor(grantPath));
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        try {
            startActivityForResult(i, REQ_TREE);
        } catch (ActivityNotFoundException e) {
            toast("系统缺少文件选择器，无法授权");
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_TREE) return;
        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            if (Build.VERSION.SDK_INT >= 34) {
                new AlertDialog.Builder(this).setTitle("未完成授权")
                        .setMessage("如果系统提示「无法使用此文件夹」，说明你的系统版本已禁止此授权方式（部分 Android 14 及以上设备）。这种情况下只能借助 Shizuku 等工具访问。")
                        .setPositiveButton("知道了", null).show();
            } else toast("已取消授权");
            return;
        }
        final String gp = pendingGrant, target = pendingTarget;
        if (gp == null) return;
        if (vfs.acceptGrant(data.getData(), gp)) {
            toast("授权成功");
            load(target != null ? target : gp);
        } else {
            new AlertDialog.Builder(this)
                    .setTitle("选择的目录不对")
                    .setMessage("需要授权的是「" + gp.replace(vfs.primaryRoot(), "内部存储") + "」。\n请在选择器打开后不要切换目录，直接点「使用此文件夹」。\n\n如果该应用从未创建过私有目录，选择器会定位不到它，这是正常的。")
                    .setNegativeButton("取消", null)
                    .setPositiveButton("重试", (d, w) -> launchTree(gp, target))
                    .show();
        }
    }

    // ======================= 目录加载 =======================

    private void load(final String path) {
        if (path == null) return;
        final int gen = ++loadGen;
        loading.setVisibility(View.VISIBLE);
        io.execute(() -> {
            try {
                final List<Vfs.Entry> l = vfs.list(path);
                if (!vfs.isRestricted(path)) {
                    for (Vfs.Entry e : l) {
                        if (e.dir && !vfs.isRestricted(e.path)) {
                            String[] k = new File(e.path).list();
                            e.childCount = k == null ? -1 : k.length;
                        }
                    }
                }
                ui.post(() -> {
                    if (gen != loadGen || isFinishing()) return;
                    loading.setVisibility(View.GONE);
                    show(path, l);
                });
            } catch (final Vfs.NeedAccessException e) {
                ui.post(() -> {
                    if (gen != loadGen) return;
                    loading.setVisibility(View.GONE);
                    if (!loadedOnce) show(vfs.primaryRoot(), new ArrayList<Vfs.Entry>());
                    askGrant(e.grantPath, path);
                });
            } catch (final IOException e) {
                ui.post(() -> {
                    if (gen != loadGen) return;
                    loading.setVisibility(View.GONE);
                    toast(e.getMessage());
                    if (!loadedOnce && !path.equals(vfs.primaryRoot())) load(vfs.primaryRoot());
                });
            }
        });
    }

    private void show(String path, List<Vfs.Entry> l) {
        if (cur != null && loadedOnce) scrollPos.put(cur, list.getFirstVisiblePosition());
        boolean same = path.equals(cur);
        cur = path;
        all = l;
        loadedOnce = true;
        int keep = list.getFirstVisiblePosition();
        if (!same) list.setAdapter(adapter);   // 换目录时重置列表，顺带清掉选中框位置
        refreshView();
        if (!same) clearPressFeedback();
        Integer pos = scrollPos.get(path);
        if (same) list.setSelection(keep);
        else list.setSelection(pos != null ? pos : 0);
        updateCrumbs();
        updateUpButton();
        if (getActionBar() != null && !pickMode) {
            String root = vfs.rootOf(path);
            getActionBar().setTitle(root != null && root.equals(path) ? vfs.labelOf(root) : Util.nameOf(path));
        }
    }

    /**
     * 点进目录时列表已换成下一级内容，但点击时的水波纹/按下高亮还会在新列表的同一位置播放完，
     * 看起来像是还停留在上一级。这里立即把它们清掉；列表在按压结束后还会再触发一次，故延迟再清一次。
     */
    private void clearPressFeedback() {
        Runnable r = () -> {
            list.setPressed(false);
            android.graphics.drawable.Drawable sel = list.getSelector();
            if (sel != null) {
                sel.setState(android.util.StateSet.NOTHING);
                sel.setVisible(false, false);
                sel.setVisible(true, false);
                sel.jumpToCurrentState();
            }
            for (int i = 0; i < list.getChildCount(); i++) {
                View c = list.getChildAt(i);
                c.setPressed(false);
                c.jumpDrawablesToCurrentState();
            }
            list.jumpDrawablesToCurrentState();
            list.invalidate();
        };
        r.run();
        list.postDelayed(r, android.view.ViewConfiguration.getPressedStateDuration() + 16);
        list.postDelayed(r, android.view.ViewConfiguration.getTapTimeout() + 120);
    }

    /** 左上角返回箭头：不在存储根目录时显示，点击回到上一级 */
    private boolean canGoUp() {
        String root = cur == null ? null : vfs.rootOf(cur);
        return cur != null && root != null && !cur.equals(root);
    }

    private void updateUpButton() {
        if (getActionBar() == null) return;
        getActionBar().setDisplayHomeAsUpEnabled(pickMode || canGoUp());
        getActionBar().setHomeActionContentDescription("返回上一级");
    }

    private void refreshView() {
        boolean hidden = Prefs.showHidden();
        String f = filter.toLowerCase(Locale.ROOT);
        List<Vfs.Entry> out = new ArrayList<>();
        for (Vfs.Entry e : all) {
            if (!hidden && e.name.startsWith(".")) continue;
            if (!f.isEmpty()) {
                String n = (e.label != null ? e.label + " " : "") + e.name;
                if (!n.toLowerCase(Locale.ROOT).contains(f)) continue;
            }
            out.add(e);
        }
        sort(out);
        adapter.set(out);
        emptyView.setText(f.isEmpty() ? "空文件夹" : "没有匹配「" + filter + "」的文件");
        emptyView.setVisibility(out.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private void sort(List<Vfs.Entry> l) {
        final int by = Prefs.sortBy();
        final boolean desc = Prefs.sortDesc();
        final Collator col = Collator.getInstance(Locale.CHINA);
        Collections.sort(l, new Comparator<Vfs.Entry>() {
            @Override
            public int compare(Vfs.Entry a, Vfs.Entry b) {
                if (a.dir != b.dir) return a.dir ? -1 : 1;
                int r;
                switch (by) {
                    case 1: r = Long.compare(a.mtime, b.mtime); break;
                    case 2: r = Long.compare(a.size, b.size); break;
                    case 3: r = Util.ext(a.name).compareTo(Util.ext(b.name)); if (r == 0) r = name(a, b); break;
                    default: r = name(a, b);
                }
                return desc ? -r : r;
            }

            int name(Vfs.Entry a, Vfs.Entry b) {
                String an = a.label != null ? a.label : a.name;
                String bn = b.label != null ? b.label : b.name;
                return col.compare(an.toLowerCase(Locale.ROOT), bn.toLowerCase(Locale.ROOT));
            }
        });
    }

    private void updateCrumbs() {
        crumbs.removeAllViews();
        String root = vfs.rootOf(cur);
        if (root == null) {
            addCrumb(cur, cur, true);
            return;
        }
        addCrumb(vfs.labelOf(root), root, root.equals(cur));
        String acc = root;
        if (cur.length() > root.length()) {
            for (String seg : cur.substring(root.length() + 1).split("/")) {
                acc = acc + "/" + seg;
                TextView sep = new TextView(this);
                sep.setText("›");
                sep.setTextColor(getColor(android.R.color.darker_gray));
                sep.setPadding(dp(2), 0, dp(2), 0);
                crumbs.addView(sep);
                addCrumb(seg, acc, acc.equals(cur));
            }
        }
        crumbScroll.post(() -> crumbScroll.fullScroll(View.FOCUS_RIGHT));
    }

    private void addCrumb(String label, final String path, boolean last) {
        TextView t = new TextView(this);
        t.setText(label);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        t.setPadding(dp(8), dp(10), dp(8), dp(10));
        TypedValue tv = new TypedValue();
        getTheme().resolveAttribute(android.R.attr.selectableItemBackground, tv, true);
        t.setBackgroundResource(tv.resourceId);
        if (last) {
            t.setTextColor(getTextColor(android.R.attr.textColorPrimary));
            t.setTypeface(null, android.graphics.Typeface.BOLD);
        } else {
            t.setTextColor(getColor(R.color.accent));
            t.setOnClickListener(v -> load(path));
        }
        crumbs.addView(t);
    }

    private int getTextColor(int attr) {
        TypedValue tv = new TypedValue();
        getTheme().resolveAttribute(attr, tv, true);
        return getColorStateList(tv.resourceId).getDefaultColor();
    }

    @Override
    public void onBackPressed() {
        String root = vfs.rootOf(cur);
        if (cur != null && root != null && !cur.equals(root)) {
            load(Util.parent(cur));
        } else {
            super.onBackPressed();
        }
    }

    // ======================= 点击/打开 =======================

    private void onItemClick(int pos) {
        Vfs.Entry e = adapter.getItem(pos);
        if (e.dir) {
            load(e.path);
            return;
        }
        if (pickMode) {
            list.setItemChecked(pos, !list.isItemChecked(pos));
            return;
        }
        openFile(e, null);
    }

    private static final java.util.Set<String> TEXT_EXT = new java.util.HashSet<>(java.util.Arrays.asList(
            "txt", "text", "log", "md", "markdown", "csv", "tsv", "json", "jsonl", "xml", "html", "htm", "xhtml", "css", "scss", "less",
            "js", "mjs", "cjs", "ts", "jsx", "tsx", "vue", "java", "kt", "kts", "gradle", "groovy", "c", "h", "cpp", "hpp", "cc", "cxx",
            "cs", "py", "pyw", "rb", "go", "rs", "php", "swift", "dart", "lua", "pl", "pm", "r", "m", "scala", "sh", "bash", "zsh",
            "bat", "cmd", "ps1", "ini", "conf", "cfg", "config", "properties", "prop", "yaml", "yml", "toml", "sql", "srt", "ass",
            "ssa", "vtt", "lrc", "nfo", "tex", "rst", "diff", "patch", "env", "gitignore", "editorconfig", "rc", "reg", "inf",
            "asm", "s", "makefile", "cmake", "dockerfile", "proto", "plist", "svg", "smali", "list", "lst", "url"));

    static boolean isTextFile(String name) {
        String ext = Util.ext(name).toLowerCase(Locale.ROOT);
        if (TEXT_EXT.contains(ext)) return true;
        if (ext.isEmpty()) {
            String n = name.toLowerCase(Locale.ROOT);
            return n.equals("makefile") || n.equals("dockerfile") || n.equals("readme") || n.equals("license") || n.startsWith(".");
        }
        String m = ShareProvider.mimeOf(name);
        return m != null && m.startsWith("text/");
    }

    private void openEditor(String path) {
        startActivity(new Intent(this, EditorActivity.class).putExtra(EditorActivity.EXTRA_PATH, path));
    }

    private void openFile(Vfs.Entry e, String forceMime) {
        if (forceMime == null && isTextFile(e.name) && !"svg".equalsIgnoreCase(Util.ext(e.name))) {
            openEditor(e.path);
            return;
        }
        if ("builtin/editor".equals(forceMime)) {
            openEditor(e.path);
            return;
        }
        Intent i = new Intent(Intent.ACTION_VIEW);
        i.setDataAndType(ShareProvider.uriFor(e.path), forceMime != null ? forceMime : ShareProvider.mimeOf(e.name));
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            if (forceMime != null) startActivity(Intent.createChooser(i, "打开方式"));
            else startActivity(i);
        } catch (ActivityNotFoundException ex) {
            openAs(e);
        }
    }

    private void openAs(final Vfs.Entry e) {
        final String[] labels = {"内置文本编辑器", "文本（其他应用）", "图片", "视频", "音频", "其他（全部应用）"};
        final String[] types = {"builtin/editor", "text/*", "image/*", "video/*", "audio/*", "*/*"};
        new AlertDialog.Builder(this).setTitle("以何种类型打开")
                .setItems(labels, (d, w) -> openFile(e, types[w])).show();
    }

    // ======================= 多选操作 =======================

    private List<Vfs.Entry> selected() {
        List<Vfs.Entry> out = new ArrayList<>();
        SparseBooleanArray cb = list.getCheckedItemPositions();
        if (cb == null) return out;
        for (int i = 0; i < cb.size(); i++) {
            if (cb.valueAt(i) && cb.keyAt(i) < adapter.getCount()) out.add(adapter.getItem(cb.keyAt(i)));
        }
        return out;
    }

    @Override
    public boolean onCreateActionMode(ActionMode mode, Menu menu) {
        actionMode = mode;
        getMenuInflater().inflate(R.menu.select, menu);
        if (pickMode) {
            menu.findItem(R.id.s_send).setTitle("发送");
            for (int id : new int[]{R.id.s_copy, R.id.s_cut, R.id.s_rename, R.id.s_delete, R.id.s_share, R.id.s_open_with, R.id.s_props}) {
                menu.findItem(id).setVisible(false);
            }
        }
        return true;
    }

    @Override
    public boolean onPrepareActionMode(ActionMode mode, Menu menu) {
        int n = list.getCheckedItemCount();
        if (!pickMode) {
            menu.findItem(R.id.s_rename).setVisible(n == 1);
            menu.findItem(R.id.s_props).setVisible(n == 1);
            List<Vfs.Entry> s = selected();
            menu.findItem(R.id.s_open_with).setVisible(n == 1 && !s.isEmpty() && !s.get(0).dir);
        }
        return true;
    }

    @Override
    public void onItemCheckedStateChanged(ActionMode mode, int position, long id, boolean checked) {
        mode.setTitle("已选 " + list.getCheckedItemCount() + " 项");
        mode.invalidate();
    }

    @Override
    public boolean onActionItemClicked(ActionMode mode, MenuItem item) {
        final List<Vfs.Entry> sel = selected();
        int id = item.getItemId();
        if (id == R.id.s_all) {
            for (int i = 0; i < adapter.getCount(); i++) list.setItemChecked(i, true);
            return true;
        }
        if (sel.isEmpty()) return true;
        if (id == R.id.s_send) {
            ArrayList<String> paths = new ArrayList<>();
            for (Vfs.Entry e : sel) paths.add(e.path);
            if (pickMode) {
                setResult(RESULT_OK, new Intent().putStringArrayListExtra(EXTRA_PATHS, paths));
                finish();
            } else {
                mode.finish();
                TransferActivity.sendPaths(this, paths);
            }
        } else if (id == R.id.s_copy || id == R.id.s_cut) {
            clip = sel;
            clipCut = id == R.id.s_cut;
            mode.finish();
            pasteText.setText((clipCut ? "已剪切 " : "已复制 ") + sel.size() + " 项，进入目标文件夹后粘贴");
            pasteBar.setVisibility(View.VISIBLE);
        } else if (id == R.id.s_delete) {
            confirmDelete(sel, mode);
        } else if (id == R.id.s_rename) {
            mode.finish();
            rename(sel.get(0));
        } else if (id == R.id.s_share) {
            share(sel);
            mode.finish();
        } else if (id == R.id.s_open_with) {
            mode.finish();
            openAs(sel.get(0));
        } else if (id == R.id.s_props) {
            mode.finish();
            props(sel.get(0));
        }
        return true;
    }

    @Override
    public void onDestroyActionMode(ActionMode mode) {
        actionMode = null;
    }

    private void clearClip() {
        clip = null;
        pasteBar.setVisibility(View.GONE);
    }

    private void share(List<Vfs.Entry> sel) {
        ArrayList<Uri> uris = new ArrayList<>();
        for (Vfs.Entry e : sel) if (!e.dir) uris.add(ShareProvider.uriFor(e.path));
        if (uris.isEmpty()) {
            toast("文件夹无法直接分享，可用「发送到电脑」");
            return;
        }
        Intent i;
        if (uris.size() == 1) {
            i = new Intent(Intent.ACTION_SEND);
            i.setType(ShareProvider.mimeOf(sel.get(0).name));
            i.putExtra(Intent.EXTRA_STREAM, uris.get(0));
        } else {
            i = new Intent(Intent.ACTION_SEND_MULTIPLE);
            i.setType("*/*");
            i.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
        }
        ClipData cd = ClipData.newRawUri("", uris.get(0));
        for (int k = 1; k < uris.size(); k++) cd.addItem(new ClipData.Item(uris.get(k)));
        i.setClipData(cd);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(Intent.createChooser(i, "分享"));
    }

    // ======================= 文件操作 =======================

    interface Op {
        void run(Progress p) throws Exception;
    }

    static final class Progress {
        volatile boolean cancelled;
        volatile long total = -1, done;
        volatile String current = "";
        volatile int count;
        final List<String> scan = new ArrayList<>();
    }

    static final class CancelledException extends IOException {
        CancelledException() { super("已取消"); }
    }

    private void runOp(String title, final Op op, final Runnable after) {
        View v = LayoutInflater.from(this).inflate(R.layout.dialog_progress, null);
        final TextView pt = v.findViewById(R.id.pText);
        final TextView ps = v.findViewById(R.id.pSub);
        final ProgressBar pb = v.findViewById(R.id.pBar);
        final Progress p = new Progress();
        final AlertDialog d = new AlertDialog.Builder(this).setTitle(title).setView(v).setCancelable(false)
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
            if (scan.length > 0) MediaScannerConnection.scanFile(getApplicationContext(), scan, null, null);
            ui.post(() -> {
                ui.removeCallbacks(tick);
                if (!isFinishing()) d.dismiss();
                if (fe != null && !(fe instanceof CancelledException)) toast("操作失败：" + fe.getMessage());
                if (after != null) after.run();
            });
        });
    }

    private void addScan(Progress p, String path) {
        if (vfs.isRestricted(path) || p.scan.size() > 5000) return;
        synchronized (p.scan) {
            p.scan.add(path);
        }
    }

    private long totalSize(Vfs.Entry e, Progress p) throws IOException {
        if (p.cancelled) throw new CancelledException();
        if (!e.dir) return e.size;
        long t = 0;
        for (Vfs.Entry k : vfs.list(e.path)) t += totalSize(k, p);
        return t;
    }

    private void paste() {
        if (clip == null) return;
        final List<Vfs.Entry> items = clip;
        final boolean cut = clipCut;
        final String dest = cur;
        runOp(cut ? "正在移动" : "正在复制", p -> {
            long total = 0;
            for (Vfs.Entry e : items) {
                if (Util.isUnder(dest, e.path)) throw new IOException("不能把文件夹粘贴到它自己里面");
                boolean fast = cut && !vfs.isRestricted(e.path) && !vfs.isRestricted(dest)
                        && String.valueOf(vfs.rootOf(e.path)).equals(vfs.rootOf(dest));
                if (!fast) total += totalSize(e, p);
            }
            p.total = total;
            for (Vfs.Entry e : items) {
                if (p.cancelled) throw new CancelledException();
                if (cut && Util.parent(e.path).equals(dest)) continue;
                String name = Util.uniqueName(vfs, dest, e.name);
                p.current = e.name;
                if (cut && vfs.fastMove(e.path, dest, name)) {
                    addScan(p, e.path);
                    addScan(p, Util.join(dest, name));
                    p.count++;
                    continue;
                }
                copyRec(e, dest, name, p);
                if (cut) {
                    collectForScan(e, p);
                    vfs.delete(e.path);
                }
            }
        }, () -> {
            if (cut) clearClip();
            load(cur);
        });
    }

    private void copyRec(Vfs.Entry src, String destDir, String name, Progress p) throws IOException {
        if (p.cancelled) throw new CancelledException();
        if (src.dir) {
            String nd = vfs.mkdir(destDir, name);
            for (Vfs.Entry k : vfs.list(src.path)) copyRec(k, nd, k.name, p);
            return;
        }
        p.current = src.name;
        String destPath = Util.join(destDir, name);
        boolean ok = false;
        try (InputStream in = vfs.openRead(src.path, 0); OutputStream out = vfs.create(destDir, name)) {
            byte[] buf = new byte[256 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                if (p.cancelled) throw new CancelledException();
                out.write(buf, 0, n);
                p.done += n;
            }
            ok = true;
        } finally {
            if (!ok) {
                try { vfs.delete(destPath); } catch (IOException ignored) {}
            }
        }
        if (src.mtime > 0 && !vfs.isRestricted(destPath)) {
            //noinspection ResultOfMethodCallIgnored
            new File(destPath).setLastModified(src.mtime);
        }
        p.count++;
        addScan(p, destPath);
    }

    /** 删除前收集路径，删除后通知媒体库移除记录，避免相册里出现"幽灵"条目 */
    private void collectForScan(Vfs.Entry e, Progress p) {
        if (vfs.isRestricted(e.path)) return;
        if (!e.dir) {
            addScan(p, e.path);
            return;
        }
        try {
            for (Vfs.Entry k : vfs.list(e.path)) {
                if (p.scan.size() > 5000) return;
                collectForScan(k, p);
            }
        } catch (IOException ignored) {
        }
    }

    private void confirmDelete(final List<Vfs.Entry> sel, final ActionMode mode) {
        String what = sel.size() == 1 ? "「" + (sel.get(0).label != null ? sel.get(0).label : sel.get(0).name) + "」" : sel.size() + " 个项目";
        new AlertDialog.Builder(this)
                .setTitle("删除")
                .setMessage("确定永久删除" + what + "吗？此操作无法撤销。")
                .setNegativeButton("取消", null)
                .setPositiveButton("删除", (d, w) -> {
                    mode.finish();
                    runOp("正在删除", p -> {
                        for (Vfs.Entry e : sel) {
                            if (p.cancelled) throw new CancelledException();
                            p.current = e.name;
                            collectForScan(e, p);
                            vfs.delete(e.path);
                            p.count++;
                        }
                    }, () -> load(cur));
                })
                .show();
    }

    private EditText nameInput(String initial) {
        EditText et = new EditText(this);
        et.setSingleLine(true);
        et.setText(initial);
        return et;
    }

    private View padded(View v) {
        FrameLayout f = new FrameLayout(this);
        f.setPadding(dp(20), dp(8), dp(20), 0);
        f.addView(v);
        return f;
    }

    private void rename(final Vfs.Entry e) {
        final EditText et = nameInput(e.name);
        int dot = e.dir ? -1 : e.name.lastIndexOf('.');
        et.setSelection(0, dot > 0 ? dot : e.name.length());
        AlertDialog d = new AlertDialog.Builder(this).setTitle("重命名").setView(padded(et))
                .setNegativeButton("取消", null)
                .setPositiveButton("确定", (di, w) -> {
                    final String n = et.getText().toString().trim();
                    if (n.isEmpty() || n.equals(e.name)) return;
                    if (n.contains("/")) {
                        toast("名称不能包含 /");
                        return;
                    }
                    ops.execute(() -> {
                        try {
                            String np = vfs.rename(e.path, n);
                            if (!vfs.isRestricted(np)) {
                                MediaScannerConnection.scanFile(getApplicationContext(), new String[]{e.path, np}, null, null);
                            }
                            ui.post(() -> load(cur));
                        } catch (final IOException ex) {
                            ui.post(() -> toast(ex.getMessage()));
                        }
                    });
                }).create();
        showWithKeyboard(d, et);
    }

    private void mkdir() {
        final EditText et = nameInput("新建文件夹");
        et.selectAll();
        AlertDialog d = new AlertDialog.Builder(this).setTitle("新建文件夹").setView(padded(et))
                .setNegativeButton("取消", null)
                .setPositiveButton("创建", (di, w) -> {
                    final String n = et.getText().toString().trim();
                    if (n.isEmpty() || n.contains("/")) {
                        toast("名称无效");
                        return;
                    }
                    final String dir = cur;
                    ops.execute(() -> {
                        try {
                            if (vfs.stat(Util.join(dir, n)) != null) throw new IOException("已存在同名项目");
                            vfs.mkdir(dir, n);
                            ui.post(() -> load(cur));
                        } catch (final IOException ex) {
                            ui.post(() -> toast(ex.getMessage()));
                        }
                    });
                }).create();
        showWithKeyboard(d, et);
    }

    private void newTextFile() {
        final EditText et = nameInput("新建文本.txt");
        et.setSelection(0, "新建文本".length());
        AlertDialog d = new AlertDialog.Builder(this).setTitle("新建文本文件").setView(padded(et))
                .setNegativeButton("取消", null)
                .setPositiveButton("创建", (di, w) -> {
                    final String n = et.getText().toString().trim();
                    if (n.isEmpty() || n.contains("/")) {
                        toast("名称无效");
                        return;
                    }
                    final String dir = cur;
                    ops.execute(() -> {
                        try {
                            String p = Util.join(dir, n);
                            if (vfs.stat(p) != null) throw new IOException("已存在同名项目");
                            vfs.create(dir, n).close();
                            if (!vfs.isRestricted(p)) {
                                MediaScannerConnection.scanFile(getApplicationContext(), new String[]{p}, null, null);
                            }
                            ui.post(() -> {
                                load(cur);
                                openEditor(p);
                            });
                        } catch (final IOException ex) {
                            ui.post(() -> toast(ex.getMessage()));
                        }
                    });
                }).create();
        showWithKeyboard(d, et);
    }

    private void showWithKeyboard(AlertDialog d, final EditText et) {
        d.show();
        et.requestFocus();
        et.postDelayed(() -> {
            InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null) imm.showSoftInput(et, InputMethodManager.SHOW_IMPLICIT);
        }, 150);
    }

    private void props(final Vfs.Entry e) {
        final StringBuilder sb = new StringBuilder();
        sb.append("名称：").append(e.name).append('\n');
        if (e.label != null) sb.append("应用：").append(e.label).append('\n');
        sb.append("位置：").append(Util.parent(e.path)).append('\n');
        sb.append("类型：").append(e.dir ? "文件夹" : ShareProvider.mimeOf(e.name)).append('\n');
        if (e.mtime > 0) sb.append("修改时间：").append(df.format(new Date(e.mtime))).append('\n');
        if (!e.dir) sb.append("大小：").append(Util.formatSize(e.size)).append("（").append(e.size).append(" 字节）");
        else sb.append("大小：计算中…");
        final AlertDialog d = new AlertDialog.Builder(this).setTitle("属性").setMessage(sb.toString())
                .setPositiveButton("确定", null).show();
        if (e.dir) {
            ops.execute(() -> {
                final long[] r = new long[3];
                walk(e.path, r, 0);
                ui.post(() -> {
                    String s = sb.toString().replace("计算中…", Util.formatSize(r[0]) + "\n包含：" + r[1] + " 个文件，" + r[2] + " 个文件夹");
                    if (d.isShowing()) d.setMessage(s);
                });
            });
        }
    }

    private void walk(String path, long[] r, int depth) {
        if (depth > 64) return;
        try {
            for (Vfs.Entry k : vfs.list(path)) {
                if (k.dir) {
                    r[2]++;
                    walk(k.path, r, depth + 1);
                } else {
                    r[0] += k.size;
                    r[1]++;
                }
            }
        } catch (IOException ignored) {
        }
    }

    // ======================= 菜单 =======================

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.main, menu);
        if (pickMode) {
            menu.findItem(R.id.m_transfer).setVisible(false);
            menu.findItem(R.id.m_settings).setVisible(false);
            menu.findItem(R.id.m_about).setVisible(false);
            menu.findItem(R.id.m_shizuku).setVisible(false);
        }
        MenuItem s = menu.findItem(R.id.m_search);
        SearchView sv = (SearchView) s.getActionView();
        sv.setQueryHint("在当前文件夹中筛选");
        sv.setOnQueryTextListener(new SearchView.OnQueryTextListener() {
            @Override
            public boolean onQueryTextSubmit(String q) {
                return true;
            }

            @Override
            public boolean onQueryTextChange(String q) {
                filter = q == null ? "" : q.trim();
                refreshView();
                return true;
            }
        });
        s.setOnActionExpandListener(new MenuItem.OnActionExpandListener() {
            @Override
            public boolean onMenuItemActionExpand(MenuItem item) {
                return true;
            }

            @Override
            public boolean onMenuItemActionCollapse(MenuItem item) {
                filter = "";
                refreshView();
                return true;
            }
        });
        return true;
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        menu.findItem(R.id.m_hidden).setChecked(Prefs.showHidden());
        return super.onPrepareOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == android.R.id.home) {
            if (canGoUp()) load(Util.parent(cur));
            else if (pickMode) finish();
        } else if (id == R.id.m_transfer) {
            startActivity(new Intent(this, TransferActivity.class));
        } else if (id == R.id.m_storage) {
            chooseLocation();
        } else if (id == R.id.m_mkdir) {
            mkdir();
        } else if (id == R.id.m_newtext) {
            newTextFile();
        } else if (id == R.id.m_sort) {
            chooseSort();
        } else if (id == R.id.m_hidden) {
            Prefs.setShowHidden(!Prefs.showHidden());
            refreshView();
        } else if (id == R.id.m_settings) {
            SettingsDialog.show(this, this::refreshView);
        } else if (id == R.id.m_about) {
            about();
        } else if (id == R.id.m_shizuku) {
            shizukuStatus();
        } else return super.onOptionsItemSelected(item);
        return true;
    }

    private void chooseLocation() {
        final List<String> labels = new ArrayList<>();
        final List<String> paths = new ArrayList<>();
        for (Vfs.Entry r : vfs.roots()) {
            labels.add((r.label != null ? r.label : r.name) + "\n" + r.path);
            paths.add(r.path);
        }
        String pr = vfs.primaryRoot();
        labels.add("下载\n" + pr + "/Download");
        paths.add(pr + "/Download");
        labels.add("应用私有数据（Android/data）");
        paths.add(pr + "/Android/data");
        labels.add("应用数据包（Android/obb）");
        paths.add(pr + "/Android/obb");
        if (Prefs.recvPrivate()) {
            File own = getExternalFilesDir("received");
            if (own != null) {
                labels.add("电脑发来的文件（应用专属目录）");
                paths.add(own.getAbsolutePath());
            }
        }
        new AlertDialog.Builder(this).setTitle("位置")
                .setItems(labels.toArray(new String[0]), (d, w) -> {
                    vfs.invalidateApps();
                    load(paths.get(w));
                }).show();
    }

    private void chooseSort() {
        final String[] items = {"名称（A→Z）", "名称（Z→A）", "修改时间（新→旧）", "修改时间（旧→新）", "大小（大→小）", "大小（小→大）", "类型"};
        final int[][] map = {{0, 0}, {0, 1}, {1, 1}, {1, 0}, {2, 1}, {2, 0}, {3, 0}};
        int checked = 0;
        for (int i = 0; i < map.length; i++) {
            if (map[i][0] == Prefs.sortBy() && (map[i][1] == 1) == Prefs.sortDesc()) checked = i;
        }
        new AlertDialog.Builder(this).setTitle("排序方式")
                .setSingleChoiceItems(items, checked, (d, w) -> {
                    Prefs.setSort(map[w][0], map[w][1] == 1);
                    refreshView();
                    d.dismiss();
                }).show();
    }

    private void about() {
        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.app_name))
                .setMessage("• 完整的手机文件管理：浏览、搜索、复制、移动、重命名、删除、分享、属性\n"
                        + "• 支持访问各应用私有目录（Android/data、Android/obb）：Shizuku 或系统授权两种方式\n"
                        + "• 电脑互传：电脑无需安装任何软件，浏览器打开手机上显示的地址即可互发消息和文件\n"
                        + "• 内置文本编辑器：几百 MB 的大文件也能流畅编辑，支持查找、替换、正则、跳转行、多种编码\n\n"
                        + "隐私与零残留：\n"
                        + "• 不联网上传任何数据，只在局域网内工作\n"
                        + "• 设置只保存在应用内部存储，聊天记录只在内存里，缩略图不写入磁盘\n"
                        + "• 不在存储中创建任何自己的文件夹，禁用了系统备份\n"
                        + "• 文本编辑器不写缓存、不自动备份；保存时的临时文件替换完成即消失，异常退出也会在下次启动时清理\n"
                        + "• 目录授权由系统保存，Shizuku 授权由 Shizuku 保存，卸载时都会自动失效\n"
                        + "• 卸载后手机中不会留下本应用的任何文件（你主动保存到「下载」的文件除外，那是你的文件）")
                .setPositiveButton("确定", null).show();
    }

    // ======================= 工具 =======================

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private final class FileAdapter extends BaseAdapter {
        private List<Vfs.Entry> items = new ArrayList<>();

        void set(List<Vfs.Entry> l) {
            items = l;
            if (actionMode != null) actionMode.finish();
            list.clearChoices();
            notifyDataSetChanged();
        }

        @Override
        public int getCount() {
            return items.size();
        }

        @Override
        public Vfs.Entry getItem(int position) {
            return items.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View v, ViewGroup parent) {
            if (v == null) v = LayoutInflater.from(MainActivity.this).inflate(R.layout.item_file, parent, false);
            Vfs.Entry e = items.get(position);
            TextView name = v.findViewById(R.id.name);
            TextView info = v.findViewById(R.id.info);
            ImageView icon = v.findViewById(R.id.icon);
            v.findViewById(R.id.lock).setVisibility(e.locked ? View.VISIBLE : View.GONE);
            name.setText(e.label != null ? e.label : e.name);
            StringBuilder sb = new StringBuilder();
            if (e.label != null && !e.label.startsWith(e.name)) sb.append(e.name).append(" · ");
            if (e.dir) {
                if (e.locked) sb.append("需要授权，点击授权");
                else if (e.childCount >= 0) sb.append(e.childCount).append(" 项");
            } else sb.append(Util.formatSize(e.size));
            if (e.mtime > 0 && !e.locked) {
                if (sb.length() > 0 && !sb.toString().endsWith(" · ")) sb.append(" · ");
                sb.append(df.format(new Date(e.mtime)));
            }
            String s = sb.toString();
            if (s.endsWith(" · ")) s = s.substring(0, s.length() - 3);
            info.setText(s);
            Thumbs.load(MainActivity.this, icon, e.path, e.name, e.dir, vfs.isRestricted(e.path));
            icon.setAlpha(e.locked ? 0.55f : 1f);
            return v;
        }
    }
}
