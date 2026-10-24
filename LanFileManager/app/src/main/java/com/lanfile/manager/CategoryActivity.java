package com.lanfile.manager;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.SparseBooleanArray;
import android.view.ActionMode;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.SearchView;
import android.widget.TextView;
import android.widget.Toast;

import com.lanfile.manager.core.Util;

import java.text.Collator;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 文件分类：图片、音乐、视频、文档、安装包、压缩包、大文件 */
public class CategoryActivity extends Activity implements AbsListView.MultiChoiceModeListener {

    private AndroidVfs vfs;
    private StorageScanner.Result result;
    private StorageScanner.Progress scanning;
    private int cat = -1;
    private String filter = "";

    private View overview, detail, privateHint;
    private LinearLayout grid;
    private TextView summary, detailInfo, emptyView;
    private ProgressBar scanBar;
    private ListView list;
    private final Adapter adapter = new Adapter();
    private ActionMode actionMode;
    private MenuItem searchItem;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final SimpleDateFormat df = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_category);
        vfs = App.vfs;
        overview = findViewById(R.id.overview);
        detail = findViewById(R.id.detail);
        privateHint = findViewById(R.id.privateHint);
        grid = findViewById(R.id.grid);
        summary = findViewById(R.id.summary);
        detailInfo = findViewById(R.id.detailInfo);
        emptyView = findViewById(R.id.empty);
        scanBar = findViewById(R.id.scanBar);
        list = findViewById(R.id.list);
        list.setAdapter(adapter);
        list.setChoiceMode(ListView.CHOICE_MODE_MULTIPLE_MODAL);
        list.setMultiChoiceModeListener(this);
        list.setOnItemClickListener((p, v, pos, id) -> open(adapter.getItem(pos)));
        findViewById(R.id.cleanEntry).setOnClickListener(v -> startActivity(new Intent(this, CleanActivity.class)));
        privateHint.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle("扫描应用私有目录")
                .setMessage("当前状态：" + ShizukuHelper.stateText()
                        + "\n\nAndroid 11 及以上，访问 Android/data、Android/obb 需要授权。推荐使用 Shizuku：在文件管理主界面的菜单 →「Shizuku（访问应用私有目录）」中授权，然后回到这里点「重新扫描」。")
                .setPositiveButton("去授权", (d, w) -> startActivity(new Intent(this, MainActivity.class)
                        .putExtra(MainActivity.EXTRA_PATH, vfs.primaryRoot() + "/Android/data")))
                .setNegativeButton("关闭", null).show());
        if (getActionBar() != null) {
            getActionBar().setTitle("文件分类");
            getActionBar().setDisplayHomeAsUpEnabled(true);
        }
        buildGrid();
        refresh();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (scanning != null) return;
        StorageScanner.Result last = StorageScanner.last;
        if (last == null) {
            scan();
        } else if (last != result) {
            // 首次进入，或者「清理垃圾」重新扫描过
            result = last;
            refresh();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (scanning != null) scanning.cancelled = true;
        io.shutdownNow();
    }

    // ======================= 扫描 =======================

    private void scan() {
        if (scanning != null) return;
        final StorageScanner.Progress p = new StorageScanner.Progress();
        scanning = p;
        scanBar.setVisibility(View.VISIBLE);
        final Runnable tick = new Runnable() {
            @Override
            public void run() {
                if (scanning != p) return;
                summary.setText("正在扫描… 已发现 " + p.files + " 个文件\n" + shortPath(p.current));
                ui.postDelayed(this, 200);
            }
        };
        ui.post(tick);
        io.execute(() -> {
            StorageScanner.Result r = null;
            try {
                r = new StorageScanner(this, vfs, p).run();
            } catch (Exception ignored) {
            }
            final StorageScanner.Result fr = r;
            ui.post(() -> {
                if (scanning == p) scanning = null;
                ui.removeCallbacks(tick);
                if (isFinishing()) return;
                scanBar.setVisibility(View.GONE);
                if (fr != null) result = fr;
                refresh();
            });
        });
    }

    private String shortPath(String p) {
        String root = p == null ? null : vfs.rootOf(p);
        if (root == null) return p == null ? "" : p;
        return vfs.labelOf(root) + p.substring(root.length());
    }

    // ======================= 总览 =======================

    private final List<View> tiles = new ArrayList<>();

    private void buildGrid() {
        LinearLayout row = null;
        for (int i = 0; i < StorageScanner.C_COUNT; i++) {
            if (i % 2 == 0) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                grid.addView(row);
            }
            View t = LayoutInflater.from(this).inflate(R.layout.item_category, row, false);
            ((ImageView) t.findViewById(R.id.icon)).setImageResource(StorageScanner.C_ICONS[i]);
            ((TextView) t.findViewById(R.id.name)).setText(StorageScanner.C_NAMES[i]
                    + (i == StorageScanner.C_LARGE ? "（≥" + Util.formatSize(StorageScanner.LARGE).replace(".0", "") + "）" : ""));
            final int c = i;
            t.setOnClickListener(v -> openCat(c));
            row.addView(t);
            tiles.add(t);
        }
        if (StorageScanner.C_COUNT % 2 == 1 && row != null) {
            View pad = new View(this);
            pad.setLayoutParams(new LinearLayout.LayoutParams(0, 1, 1));
            row.addView(pad);
        }
    }

    private List<StorageScanner.Item> visible(int c) {
        List<StorageScanner.Item> out = new ArrayList<>();
        if (result == null) return out;
        boolean hidden = Prefs.showHidden();
        for (StorageScanner.Item it : result.cats.get(c)) if (hidden || !it.hidden) out.add(it);
        return out;
    }

    private void refresh() {
        for (int i = 0; i < tiles.size(); i++) {
            TextView info = tiles.get(i).findViewById(R.id.info);
            if (result == null) {
                info.setText(scanning != null ? "扫描中…" : "—");
                continue;
            }
            List<StorageScanner.Item> l = visible(i);
            long s = 0;
            for (StorageScanner.Item it : l) s += it.size;
            info.setText(l.size() + " 项 · " + Util.formatSize(s));
        }
        if (scanning == null) {
            if (result != null) {
                summary.setText("共扫描 " + result.files + " 个文件，" + Util.formatSize(result.totalSize)
                        + " · " + new SimpleDateFormat("HH:mm", Locale.CHINA).format(new Date(result.finishedAt)) + " 更新");
            } else summary.setText("扫描失败，请确认已授予文件访问权限");
        }
        privateHint.setVisibility(result != null && !result.privateScanned && scanning == null ? View.VISIBLE : View.GONE);
        if (cat >= 0) showList();
    }

    // ======================= 分类列表 =======================

    private void openCat(int c) {
        if (result == null) {
            toast("正在扫描，请稍候");
            return;
        }
        cat = c;
        overview.setVisibility(View.GONE);
        detail.setVisibility(View.VISIBLE);
        if (getActionBar() != null) getActionBar().setTitle(StorageScanner.C_NAMES[c]);
        list.setSelection(0);
        showList();
        invalidateOptionsMenu();
    }

    private void closeCat() {
        cat = -1;
        filter = "";
        if (actionMode != null) actionMode.finish();
        if (searchItem != null && searchItem.isActionViewExpanded()) searchItem.collapseActionView();
        detail.setVisibility(View.GONE);
        overview.setVisibility(View.VISIBLE);
        if (getActionBar() != null) getActionBar().setTitle("文件分类");
        refresh();
        invalidateOptionsMenu();
    }

    private void showList() {
        List<StorageScanner.Item> l = visible(cat);
        String f = filter.toLowerCase(Locale.ROOT);
        if (!f.isEmpty()) {
            List<StorageScanner.Item> m = new ArrayList<>();
            for (StorageScanner.Item it : l) if (it.name.toLowerCase(Locale.ROOT).contains(f)) m.add(it);
            l = m;
        }
        sort(l);
        long s = 0;
        for (StorageScanner.Item it : l) s += it.size;
        detailInfo.setText(l.size() + " 个文件 · " + Util.formatSize(s) + " · " + SORT_NAMES[Prefs.catSort()]);
        adapter.set(l);
        emptyView.setText(f.isEmpty() ? "没有" + StorageScanner.C_NAMES[cat] : "没有匹配「" + filter + "」的文件");
        emptyView.setVisibility(l.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private static final String[] SORT_NAMES = {"按时间（新→旧）", "按时间（旧→新）", "按大小（大→小）", "按大小（小→大）", "按名称"};

    private void sort(List<StorageScanner.Item> l) {
        final int by = Prefs.catSort();
        final Collator col = Collator.getInstance(Locale.CHINA);
        Collections.sort(l, new Comparator<StorageScanner.Item>() {
            @Override
            public int compare(StorageScanner.Item a, StorageScanner.Item b) {
                switch (by) {
                    case 1: return Long.compare(a.mtime, b.mtime);
                    case 2: return Long.compare(b.size, a.size);
                    case 3: return Long.compare(a.size, b.size);
                    case 4: return col.compare(a.name.toLowerCase(Locale.ROOT), b.name.toLowerCase(Locale.ROOT));
                    default: return Long.compare(b.mtime, a.mtime);
                }
            }
        });
    }

    @Override
    public void onBackPressed() {
        if (cat >= 0) closeCat();
        else super.onBackPressed();
    }

    // ======================= 打开 =======================

    private void open(StorageScanner.Item it) {
        if (MainActivity.isTextFile(it.name) && !"svg".equals(Util.ext(it.name))) {
            startActivity(new Intent(this, EditorActivity.class).putExtra(EditorActivity.EXTRA_PATH, it.path));
            return;
        }
        Intent i = new Intent(Intent.ACTION_VIEW);
        i.setDataAndType(ShareProvider.uriFor(it.path), ShareProvider.mimeOf(it.name));
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivity(i);
        } catch (ActivityNotFoundException e) {
            i.setDataAndType(ShareProvider.uriFor(it.path), "*/*");
            try {
                startActivity(Intent.createChooser(i, "打开方式"));
            } catch (ActivityNotFoundException e2) {
                toast("没有可以打开此文件的应用");
            }
        }
    }

    private void locate(StorageScanner.Item it) {
        startActivity(new Intent(this, MainActivity.class)
                .putExtra(MainActivity.EXTRA_PATH, Util.parent(it.path))
                .putExtra(MainActivity.EXTRA_FOCUS, it.name));
    }

    // ======================= 多选 =======================

    private List<StorageScanner.Item> selected() {
        List<StorageScanner.Item> out = new ArrayList<>();
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
        getMenuInflater().inflate(R.menu.category_select, menu);
        return true;
    }

    @Override
    public boolean onPrepareActionMode(ActionMode mode, Menu menu) {
        int n = list.getCheckedItemCount();
        menu.findItem(R.id.cs_locate).setVisible(n == 1);
        menu.findItem(R.id.cs_props).setVisible(n == 1);
        return true;
    }

    @Override
    public void onItemCheckedStateChanged(ActionMode mode, int position, long id, boolean checked) {
        long s = 0;
        for (StorageScanner.Item it : selected()) s += it.size;
        mode.setTitle("已选 " + list.getCheckedItemCount() + " 项");
        mode.setSubtitle(Util.formatSize(s));
        mode.invalidate();
    }

    @Override
    public boolean onActionItemClicked(ActionMode mode, MenuItem item) {
        final List<StorageScanner.Item> sel = selected();
        int id = item.getItemId();
        if (id == R.id.cs_all) {
            for (int i = 0; i < adapter.getCount(); i++) list.setItemChecked(i, true);
            return true;
        }
        if (sel.isEmpty()) return true;
        if (id == R.id.cs_send) {
            ArrayList<String> paths = new ArrayList<>();
            for (StorageScanner.Item it : sel) paths.add(it.path);
            mode.finish();
            TransferActivity.sendPaths(this, paths);
        } else if (id == R.id.cs_delete) {
            confirmDelete(sel, mode);
        } else if (id == R.id.cs_share) {
            share(sel);
            mode.finish();
        } else if (id == R.id.cs_locate) {
            mode.finish();
            locate(sel.get(0));
        } else if (id == R.id.cs_props) {
            mode.finish();
            props(sel.get(0));
        }
        return true;
    }

    @Override
    public void onDestroyActionMode(ActionMode mode) {
        actionMode = null;
    }

    private void share(List<StorageScanner.Item> sel) {
        ArrayList<Uri> uris = new ArrayList<>();
        for (StorageScanner.Item it : sel) uris.add(ShareProvider.uriFor(it.path));
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

    private void confirmDelete(final List<StorageScanner.Item> sel, final ActionMode mode) {
        long s = 0;
        for (StorageScanner.Item it : sel) s += it.size;
        String what = sel.size() == 1 ? "「" + sel.get(0).name + "」" : sel.size() + " 个文件";
        new AlertDialog.Builder(this)
                .setTitle("删除")
                .setMessage("确定永久删除" + what + "（" + Util.formatSize(s) + "）吗？此操作无法撤销。")
                .setNegativeButton("取消", null)
                .setPositiveButton("删除", (d, w) -> {
                    mode.finish();
                    final Set<String> gone = new HashSet<>();
                    OpDialog.run(this, "正在删除", p -> {
                        for (StorageScanner.Item it : sel) {
                            if (p.cancelled) throw new MainActivity.CancelledException();
                            p.current = it.name;
                            vfs.delete(it.path);
                            synchronized (gone) {
                                gone.add(it.path);
                            }
                            OpDialog.addScan(p, it.path);
                            p.count++;
                        }
                    }, () -> {
                        if (result != null) result.forget(gone);
                        refresh();
                        toast("已删除 " + gone.size() + " 个文件");
                    });
                })
                .show();
    }

    private void props(StorageScanner.Item it) {
        StringBuilder sb = new StringBuilder();
        sb.append("名称：").append(it.name).append('\n');
        sb.append("位置：").append(shortPath(Util.parent(it.path))).append('\n');
        sb.append("类型：").append(ShareProvider.mimeOf(it.name)).append('\n');
        if (it.mtime > 0) sb.append("修改时间：").append(df.format(new Date(it.mtime))).append('\n');
        sb.append("大小：").append(Util.formatSize(it.size)).append("（").append(it.size).append(" 字节）");
        new AlertDialog.Builder(this).setTitle("属性").setMessage(sb.toString())
                .setNeutralButton("打开所在文件夹", (d, w) -> locate(it))
                .setPositiveButton("确定", null).show();
    }

    // ======================= 菜单 =======================

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.category, menu);
        searchItem = menu.findItem(R.id.c_search);
        SearchView sv = (SearchView) searchItem.getActionView();
        sv.setQueryHint("按文件名筛选");
        sv.setOnQueryTextListener(new SearchView.OnQueryTextListener() {
            @Override
            public boolean onQueryTextSubmit(String q) {
                return true;
            }

            @Override
            public boolean onQueryTextChange(String q) {
                filter = q == null ? "" : q.trim();
                if (cat >= 0) showList();
                return true;
            }
        });
        return true;
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        menu.findItem(R.id.c_search).setVisible(cat >= 0);
        menu.findItem(R.id.c_sort).setVisible(cat >= 0);
        menu.findItem(R.id.c_hidden).setChecked(Prefs.showHidden());
        return super.onPrepareOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == android.R.id.home) {
            onBackPressed();
        } else if (id == R.id.c_sort) {
            new AlertDialog.Builder(this).setTitle("排序方式")
                    .setSingleChoiceItems(SORT_NAMES, Prefs.catSort(), (d, w) -> {
                        Prefs.setCatSort(w);
                        showList();
                        d.dismiss();
                    }).show();
        } else if (id == R.id.c_hidden) {
            Prefs.setShowHidden(!Prefs.showHidden());
            refresh();
        } else if (id == R.id.c_rescan) {
            if (actionMode != null) actionMode.finish();
            vfs.refreshRoots();
            vfs.invalidateApps();
            scan();
            refresh();
        } else return super.onOptionsItemSelected(item);
        return true;
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    private final class Adapter extends BaseAdapter {
        private List<StorageScanner.Item> items = new ArrayList<>();

        void set(List<StorageScanner.Item> l) {
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
        public StorageScanner.Item getItem(int position) {
            return items.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View v, ViewGroup parent) {
            if (v == null) v = LayoutInflater.from(CategoryActivity.this).inflate(R.layout.item_file, parent, false);
            StorageScanner.Item it = items.get(position);
            TextView name = v.findViewById(R.id.name);
            TextView info = v.findViewById(R.id.info);
            ImageView icon = v.findViewById(R.id.icon);
            name.setText(it.name);
            StringBuilder sb = new StringBuilder(Util.formatSize(it.size));
            if (it.mtime > 0) sb.append(" · ").append(df.format(new Date(it.mtime)));
            sb.append(" · ").append(shortPath(Util.parent(it.path)));
            info.setText(sb.toString());
            Thumbs.load(CategoryActivity.this, icon, it.path, it.name, false, vfs.isRestricted(it.path));
            return v;
        }
    }
}
