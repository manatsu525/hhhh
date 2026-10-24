package com.lanfile.manager;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.storage.StorageManager;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseExpandableListAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.ExpandableListView;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import com.lanfile.manager.core.Util;
import com.lanfile.manager.core.Vfs;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 垃圾清理：先分析，列出可清理的项目，由用户勾选后再删除 */
public class CleanActivity extends Activity {

    private static final int REQ_SYS_CACHE = 31;

    private AndroidVfs vfs;
    private StorageScanner.Progress scanning;
    /** 只放有内容的分组：每组 {类型, 项目列表} */
    private final List<Integer> kinds = new ArrayList<>();
    private final List<List<StorageScanner.Item>> groups = new ArrayList<>();
    private boolean privateScanned = true;

    private TextView bigSize, headSub, current, emptyView;
    private ProgressBar bar;
    private Button cleanBtn;
    private ExpandableListView list;
    private final Adapter adapter = new Adapter();

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_clean);
        vfs = App.vfs;
        bigSize = findViewById(R.id.bigSize);
        headSub = findViewById(R.id.headSub);
        current = findViewById(R.id.current);
        emptyView = findViewById(R.id.empty);
        bar = findViewById(R.id.bar);
        cleanBtn = findViewById(R.id.cleanBtn);
        list = findViewById(R.id.list);
        if (Build.VERSION.SDK_INT >= 30) list.addFooterView(sysCacheFooter(), null, false);
        list.setAdapter(adapter);
        list.setOnChildClickListener((p, v, g, c, id) -> {
            StorageScanner.Item it = adapter.getChild(g, c);
            it.checked = !it.checked;
            changed();
            return true;
        });
        list.setOnItemLongClickListener((p, v, pos, id) -> {
            long pp = list.getExpandableListPosition(pos);
            if (ExpandableListView.getPackedPositionType(pp) != ExpandableListView.PACKED_POSITION_TYPE_CHILD) return false;
            itemMenu(adapter.getChild(ExpandableListView.getPackedPositionGroup(pp), ExpandableListView.getPackedPositionChild(pp)));
            return true;
        });
        cleanBtn.setOnClickListener(v -> confirmClean());
        if (getActionBar() != null) {
            getActionBar().setTitle("清理垃圾");
            getActionBar().setDisplayHomeAsUpEnabled(true);
        }
        analyze();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (scanning != null) scanning.cancelled = true;
        io.shutdownNow();
    }

    // ======================= 分析 =======================

    private void analyze() {
        if (scanning != null) return;
        final StorageScanner.Progress p = new StorageScanner.Progress();
        scanning = p;
        kinds.clear();
        groups.clear();
        adapter.notifyDataSetChanged();
        emptyView.setVisibility(View.GONE);
        bar.setVisibility(View.VISIBLE);
        current.setVisibility(View.VISIBLE);
        cleanBtn.setEnabled(false);
        cleanBtn.setText("正在分析…");
        bigSize.setText("分析中");
        final Runnable tick = new Runnable() {
            @Override
            public void run() {
                if (scanning != p) return;
                headSub.setText("已扫描 " + p.files + " 个文件");
                current.setText(shortPath(p.current));
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
                bar.setVisibility(View.GONE);
                current.setVisibility(View.GONE);
                if (fr == null) {
                    bigSize.setText("分析失败");
                    headSub.setText("请确认已授予文件访问权限");
                    cleanBtn.setText("重新分析");
                    cleanBtn.setEnabled(true);
                    cleanBtn.setOnClickListener(v -> {
                        cleanBtn.setOnClickListener(x -> confirmClean());
                        analyze();
                    });
                    return;
                }
                showResult(fr);
            });
        });
    }

    private void showResult(StorageScanner.Result r) {
        privateScanned = r.privateScanned;
        kinds.clear();
        groups.clear();
        for (int k = 0; k < StorageScanner.J_COUNT; k++) {
            List<StorageScanner.Item> l = r.junk.get(k);
            if (l.isEmpty()) continue;
            Collections.sort(l, new Comparator<StorageScanner.Item>() {
                @Override
                public int compare(StorageScanner.Item a, StorageScanner.Item b) {
                    if (a.checked != b.checked) return a.checked ? -1 : 1;
                    return Long.compare(b.size, a.size);
                }
            });
            kinds.add(k);
            groups.add(l);
        }
        adapter.notifyDataSetChanged();
        changed();
    }

    /** 勾选变化后刷新顶部数字和按钮 */
    private void changed() {
        long total = 0, sel = 0;
        int n = 0, all = 0;
        for (List<StorageScanner.Item> l : groups) {
            for (StorageScanner.Item it : l) {
                total += it.size;
                all++;
                if (it.checked) {
                    sel += it.size;
                    n++;
                }
            }
        }
        bigSize.setText(Util.formatSize(sel));
        String sub = all == 0 ? "没有发现可清理的垃圾" : "已选 " + n + " 项 · 共发现 " + all + " 项，" + Util.formatSize(total);
        if (!privateScanned) sub += "\n未授权访问 Android/data，应用缓存和卸载残留没有扫描（可用 Shizuku 授权）";
        headSub.setText(sub);
        cleanBtn.setEnabled(n > 0);
        cleanBtn.setText(n > 0 ? "清理选中（" + Util.formatSize(sel) + "）" : "未选择任何项目");
        emptyView.setText("手机很干净，没有发现可清理的垃圾");
        emptyView.setVisibility(all == 0 ? View.VISIBLE : View.GONE);
        adapter.notifyDataSetChanged();
    }

    private String shortPath(String p) {
        String root = p == null ? null : vfs.rootOf(p);
        if (root == null) return p == null ? "" : p;
        return vfs.labelOf(root) + p.substring(root.length());
    }

    // ======================= 清理 =======================

    private void confirmClean() {
        final List<StorageScanner.Item> sel = new ArrayList<>();
        final List<Integer> selKinds = new ArrayList<>();
        long size = 0;
        for (int g = 0; g < groups.size(); g++) {
            for (StorageScanner.Item it : groups.get(g)) {
                if (!it.checked) continue;
                sel.add(it);
                selKinds.add(kinds.get(g));
                size += it.size;
            }
        }
        if (sel.isEmpty()) return;
        final long total = size;
        new AlertDialog.Builder(this)
                .setTitle("清理垃圾")
                .setMessage("将永久删除选中的 " + sel.size() + " 项（" + Util.formatSize(size) + "），无法撤销。确定清理吗？")
                .setNegativeButton("取消", null)
                .setPositiveButton("清理", (d, w) -> clean(sel, selKinds, total))
                .show();
    }

    private void clean(final List<StorageScanner.Item> sel, final List<Integer> selKinds, long total) {
        final Set<String> gone = new HashSet<>();
        final long[] freed = new long[1];
        final int[] failed = new int[1];
        OpDialog.run(this, "正在清理", p -> {
            p.total = total;
            for (int i = 0; i < sel.size(); i++) {
                if (p.cancelled) throw new MainActivity.CancelledException();
                StorageScanner.Item it = sel.get(i);
                p.current = it.title();
                try {
                    remove(it, selKinds.get(i), p);
                    synchronized (gone) {
                        gone.add(it.path);
                    }
                    freed[0] += it.size;
                } catch (MainActivity.CancelledException e) {
                    throw e;
                } catch (IOException e) {
                    failed[0]++;
                }
                p.done += it.size;
                p.count++;
            }
        }, () -> {
            for (List<StorageScanner.Item> l : groups) {
                for (int i = l.size() - 1; i >= 0; i--) if (gone.contains(l.get(i).path)) l.remove(i);
            }
            for (int g = groups.size() - 1; g >= 0; g--) {
                if (groups.get(g).isEmpty()) {
                    groups.remove(g);
                    kinds.remove(g);
                }
            }
            StorageScanner.Result last = StorageScanner.last;
            if (last != null) last.forget(gone);
            changed();
            String msg = "已清理 " + gone.size() + " 项，释放 " + Util.formatSize(freed[0]);
            if (failed[0] > 0) msg += "\n" + failed[0] + " 项删除失败（可能没有权限或正在被使用）";
            new AlertDialog.Builder(this).setTitle("清理完成").setMessage(msg).setPositiveButton("确定", null).show();
        });
    }

    private void remove(StorageScanner.Item it, int kind, MainActivity.Progress p) throws IOException {
        if (kind == StorageScanner.J_CACHE || kind == StorageScanner.J_THUMB) {
            // 只清空内容，保留目录本身，应用不用重新创建；个别文件删不掉时继续删其余的
            IOException err = null;
            for (Vfs.Entry k : vfs.list(it.path)) {
                if (p.cancelled) throw new MainActivity.CancelledException();
                try {
                    vfs.delete(k.path);
                } catch (IOException e) {
                    err = e;
                }
            }
            if (err != null) throw err;
            return;
        }
        if (kind == StorageScanner.J_EMPTY) {
            // 分析之后里面可能又放了文件，删除前再确认一次
            if (!vfs.isRestricted(it.path) && !emptyTree(new File(it.path), 0)) throw new IOException("文件夹已不为空");
            vfs.delete(it.path);
            return;
        }
        vfs.delete(it.path);
        if (!it.dir) OpDialog.addScan(p, it.path);
    }

    private static boolean emptyTree(File f, int depth) {
        if (depth > 48) return false;
        File[] k = f.listFiles();
        if (k == null) return !f.exists();
        for (File x : k) if (!x.isDirectory() || !emptyTree(x, depth + 1)) return false;
        return true;
    }

    // ======================= 其他 =======================

    private void itemMenu(final StorageScanner.Item it) {
        final String folder = it.dir ? it.path : Util.parent(it.path);
        StringBuilder sb = new StringBuilder();
        sb.append(it.title()).append('\n');
        if (it.note != null) sb.append(it.note).append('\n');
        sb.append('\n').append("位置：").append(shortPath(it.path)).append('\n');
        sb.append("大小：").append(Util.formatSize(it.size));
        new AlertDialog.Builder(this).setTitle(it.dir ? "文件夹" : "文件")
                .setMessage(sb.toString())
                .setNeutralButton("打开所在文件夹", (d, w) -> {
                    Intent i = new Intent(this, MainActivity.class).putExtra(MainActivity.EXTRA_PATH, folder);
                    if (!it.dir) i.putExtra(MainActivity.EXTRA_FOCUS, it.name);
                    startActivity(i);
                })
                .setPositiveButton("确定", null).show();
    }

    @android.annotation.TargetApi(30)
    private View sysCacheFooter() {
        View v = LayoutInflater.from(this).inflate(R.layout.item_clean_group, list, false);
        ((TextView) v.findViewById(R.id.arrow)).setText("⚙");
        ((TextView) v.findViewById(R.id.name)).setText("清理所有应用的内部缓存");
        ((TextView) v.findViewById(R.id.info)).setText("/data/data 里的缓存只有系统能删除。点击后由系统弹窗确认并执行，不影响应用数据");
        v.findViewById(R.id.check).setVisibility(View.GONE);
        ((TextView) v.findViewById(R.id.size)).setText("›");
        v.setOnClickListener(x -> {
            try {
                startActivityForResult(new Intent(StorageManager.ACTION_CLEAR_APP_CACHE), REQ_SYS_CACHE);
            } catch (ActivityNotFoundException | SecurityException e) {
                toast("此系统不支持该功能");
            }
        });
        return v;
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_SYS_CACHE && resultCode == RESULT_OK) toast("系统已清理所有应用的缓存");
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.clean, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == android.R.id.home) {
            finish();
        } else if (id == R.id.k_rescan) {
            vfs.refreshRoots();
            vfs.invalidateApps();
            analyze();
        } else return super.onOptionsItemSelected(item);
        return true;
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    private final class Adapter extends BaseExpandableListAdapter {
        @Override
        public int getGroupCount() {
            return groups.size();
        }

        @Override
        public int getChildrenCount(int g) {
            return groups.get(g).size();
        }

        @Override
        public List<StorageScanner.Item> getGroup(int g) {
            return groups.get(g);
        }

        @Override
        public StorageScanner.Item getChild(int g, int c) {
            return groups.get(g).get(c);
        }

        @Override
        public long getGroupId(int g) {
            return kinds.get(g);
        }

        @Override
        public long getChildId(int g, int c) {
            return c;
        }

        @Override
        public boolean hasStableIds() {
            return false;
        }

        @Override
        public boolean isChildSelectable(int g, int c) {
            return true;
        }

        @Override
        public View getGroupView(final int g, boolean expanded, View v, ViewGroup parent) {
            if (v == null) v = LayoutInflater.from(CleanActivity.this).inflate(R.layout.item_clean_group, parent, false);
            final List<StorageScanner.Item> l = groups.get(g);
            int kind = kinds.get(g);
            long total = 0, sel = 0;
            int n = 0;
            for (StorageScanner.Item it : l) {
                total += it.size;
                if (it.checked) {
                    sel += it.size;
                    n++;
                }
            }
            ((TextView) v.findViewById(R.id.arrow)).setText(expanded ? "▾" : "▸");
            ((TextView) v.findViewById(R.id.name)).setText(StorageScanner.J_NAMES[kind]);
            ((TextView) v.findViewById(R.id.info)).setText(l.size() + " 项" + (n > 0 && n < l.size() ? "，已选 " + n + " 项" : "")
                    + " · " + StorageScanner.J_DESC[kind]);
            ((TextView) v.findViewById(R.id.size)).setText(n > 0 && n < l.size()
                    ? Util.formatSize(sel) + " / " + Util.formatSize(total) : Util.formatSize(total));
            CheckBox cb = v.findViewById(R.id.check);
            final boolean allChecked = n == l.size();
            cb.setChecked(n > 0);
            cb.setAlpha(n > 0 && !allChecked ? 0.5f : 1f);
            cb.setOnClickListener(x -> {
                for (StorageScanner.Item it : l) it.checked = !allChecked;
                changed();
            });
            return v;
        }

        @Override
        public View getChildView(int g, int c, boolean last, View v, ViewGroup parent) {
            if (v == null) v = LayoutInflater.from(CleanActivity.this).inflate(R.layout.item_clean_child, parent, false);
            final StorageScanner.Item it = groups.get(g).get(c);
            ((TextView) v.findViewById(R.id.name)).setText(it.title());
            ((TextView) v.findViewById(R.id.info)).setText(it.note != null ? it.note : shortPath(it.path));
            ((TextView) v.findViewById(R.id.size)).setText(kinds.get(g) == StorageScanner.J_EMPTY ? "" : Util.formatSize(it.size));
            Thumbs.load(CleanActivity.this, (ImageView) v.findViewById(R.id.icon), it.path, it.name, it.dir, vfs.isRestricted(it.path));
            CheckBox cb = v.findViewById(R.id.check);
            cb.setChecked(it.checked);
            cb.setOnClickListener(x -> {
                it.checked = ((CheckBox) x).isChecked();
                changed();
            });
            return v;
        }
    }
}
