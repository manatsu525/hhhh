package com.lanfile.manager;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.util.SparseBooleanArray;
import android.util.TypedValue;
import android.view.ActionMode;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.SearchView;
import android.widget.TextView;
import android.widget.Toast;

import com.lanfile.manager.archive.ArcEntry;
import com.lanfile.manager.archive.ArchiveReader;
import com.lanfile.manager.archive.Formats;
import com.lanfile.manager.archive.PasswordException;
import com.lanfile.manager.core.Util;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.Collator;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 压缩包查看器：不用解压就能浏览压缩包里的目录，点开单个文件查看（临时解压到应用缓存，离开后自动删除），
 * 也可以只解压选中的文件 / 文件夹。压缩包里的压缩包可以继续点开。
 */
public class ArchiveActivity extends Activity implements AbsListView.MultiChoiceModeListener {

    /** 压缩包路径（Vfs 路径） */
    public static final String EXTRA_PATH = "path";
    /** 显示名称（嵌套打开时，缓存里的文件名可能和原名不同） */
    public static final String EXTRA_NAME = "name";
    /** 默认解压到的目录 */
    public static final String EXTRA_DEST = "dest";

    private ArchiveReader reader;
    private String displayName;
    private String destParent;
    private File session;
    private String cur = "";
    private String filter = "";
    private final Map<String, Integer> scrollPos = new HashMap<>();
    private final Map<String, File> viewed = new HashMap<>();

    private ListView list;
    private Adapter adapter;
    private TextView emptyView, summary;
    private LinearLayout crumbs;
    private HorizontalScrollView crumbScroll;
    private ActionMode actionMode;
    private final SimpleDateFormat df = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA);

    // ======================= 生命周期 =======================

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_archive);
        list = findViewById(R.id.list);
        emptyView = findViewById(R.id.empty);
        summary = findViewById(R.id.summary);
        crumbs = findViewById(R.id.crumbs);
        crumbScroll = findViewById(R.id.crumbScroll);
        adapter = new Adapter();
        list.setAdapter(adapter);
        list.setChoiceMode(ListView.CHOICE_MODE_MULTIPLE_MODAL);
        list.setMultiChoiceModeListener(this);
        list.setOnItemClickListener((p, v, pos, id) -> onItemClick(adapter.getItem(pos)));
        findViewById(R.id.extractAll).setOnClickListener(v -> extract(null));
        if (getActionBar() != null) getActionBar().setDisplayHomeAsUpEnabled(true);

        session = new File(new File(getCacheDir(), "arc"), "view" + System.nanoTime());

        Intent it = getIntent();
        String path = it.getStringExtra(EXTRA_PATH);
        Uri uri = it.getData();
        if (path == null && uri != null && "file".equals(uri.getScheme())) path = uri.getPath();
        displayName = it.getStringExtra(EXTRA_NAME);
        destParent = it.getStringExtra(EXTRA_DEST);

        if (path != null) {
            if (displayName == null) displayName = Util.nameOf(path);
            if (destParent == null) destParent = Util.parent(path);
            setTitle(displayName);
            open(path, null);
        } else if (uri != null) {
            // 其他应用用「打开方式」传过来的 content://，先复制到缓存
            if (displayName == null) displayName = queryName(uri);
            if (destParent == null) destParent = Util.join(App.vfs.primaryRoot(), "Download");
            setTitle(displayName);
            copyFromUri(uri);
        } else {
            finish();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (reader != null) ArchiveUi.closeQuietly(reader);
        reader = null;
        // 查看时临时解压的文件随查看器一起删除（零残留）
        App.deleteTree(session);
    }

    private String queryName(Uri uri) {
        String n = null;
        try (Cursor c = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) n = c.getString(0);
        } catch (Exception ignored) {
        }
        if (n == null) n = uri.getLastPathSegment();
        if (n == null) n = "archive";
        return Util.safeName(n);
    }

    private void copyFromUri(final Uri uri) {
        final File[] out = new File[1];
        OpRunner.run(this, "正在读取", p -> {
            p.current = displayName;
            File dir = new File(session, "src");
            if (!dir.mkdirs() && !dir.isDirectory()) throw new IOException("无法创建缓存目录");
            File f = new File(dir, displayName);
            try (InputStream in = getContentResolver().openInputStream(uri); OutputStream os = new FileOutputStream(f)) {
                if (in == null) throw new IOException("无法读取文件");
                byte[] buf = new byte[256 * 1024];
                int k;
                while ((k = in.read(buf)) > 0) {
                    p.check();
                    os.write(buf, 0, k);
                    p.done += k;
                }
            }
            out[0] = f;
        }, () -> {
            if (out[0] != null) open(out[0].getAbsolutePath(), null);
            else finish();
        });
    }

    private void open(final String path, final String password) {
        final ArchiveReader[] out = new ArchiveReader[1];
        OpRunner.run(this, "正在读取压缩包", p -> {
            p.current = displayName;
            File f = ArchiveUi.localFile(this, path, displayName, p);
            out[0] = ArchiveReader.open(f, displayName, password);
        }, () -> {
            if (isDestroyed()) {
                if (out[0] != null) ArchiveUi.closeQuietly(out[0]);
                return;
            }
            if (out[0] != null) {
                reader = out[0];
                if (getActionBar() != null) getActionBar().setSubtitle(reader.format());
                show("");
            } else if (reader == null && !askingPassword) {
                finish();
            }
        }, e -> {
            if (e instanceof PasswordException) {
                askingPassword = true;
                ArchiveUi.askPassword(this, displayName, ((PasswordException) e).wrong, pw -> {
                    askingPassword = false;
                    open(path, pw);
                }, this::finish);
                return true;
            }
            Toast.makeText(this, "无法打开：" + e.getMessage(), Toast.LENGTH_LONG).show();
            return true;
        });
    }

    private boolean askingPassword;

    // ======================= 列表 =======================

    private void show(String dir) {
        if (reader == null) return;
        if (!dir.equals(cur)) scrollPos.put(cur, list.getFirstVisiblePosition());
        boolean same = dir.equals(cur);
        cur = dir;
        List<ArcEntry> l = new ArrayList<>();
        String f = filter.toLowerCase(Locale.ROOT);
        for (ArcEntry e : reader.list(dir)) {
            if (!f.isEmpty() && !e.name().toLowerCase(Locale.ROOT).contains(f)) continue;
            l.add(e);
        }
        final Collator col = Collator.getInstance(Locale.CHINA);
        Collections.sort(l, (a, b) -> {
            if (a.dir != b.dir) return a.dir ? -1 : 1;
            return col.compare(a.name().toLowerCase(Locale.ROOT), b.name().toLowerCase(Locale.ROOT));
        });
        if (!same) list.setAdapter(adapter);
        adapter.set(l);
        Integer pos = scrollPos.get(dir);
        if (!same) list.setSelection(pos != null ? pos : 0);
        emptyView.setText(f.isEmpty() ? "空文件夹" : "没有匹配「" + filter + "」的文件");
        emptyView.setVisibility(l.isEmpty() ? View.VISIBLE : View.GONE);
        updateCrumbs();
        updateSummary();
    }

    private void updateSummary() {
        int files = 0, dirs = 0;
        long size = 0;
        boolean known = true;
        for (ArcEntry e : reader.entries()) {
            if (e.dir) {
                dirs++;
                continue;
            }
            files++;
            if (e.size >= 0) size += e.size;
            else known = false;
        }
        StringBuilder sb = new StringBuilder();
        sb.append(files).append(" 个文件");
        if (dirs > 0) sb.append("，").append(dirs).append(" 个文件夹");
        if (known) sb.append("，共 ").append(Util.formatSize(size));
        if (reader.hasEncrypted()) sb.append(" · 已加密");
        summary.setText(sb.toString());
    }

    private void updateCrumbs() {
        crumbs.removeAllViews();
        addCrumb(displayName, "", cur.isEmpty());
        if (!cur.isEmpty()) {
            String acc = "";
            for (String seg : cur.split("/")) {
                acc = acc.isEmpty() ? seg : acc + "/" + seg;
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
        t.setSingleLine(true);
        t.setMaxWidth(dp(220));
        t.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        TypedValue tv = new TypedValue();
        getTheme().resolveAttribute(android.R.attr.selectableItemBackground, tv, true);
        t.setBackgroundResource(tv.resourceId);
        if (last) {
            TypedValue c = new TypedValue();
            getTheme().resolveAttribute(android.R.attr.textColorPrimary, c, true);
            t.setTextColor(getColorStateList(c.resourceId).getDefaultColor());
            t.setTypeface(null, android.graphics.Typeface.BOLD);
        } else {
            t.setTextColor(getColor(R.color.accent));
            t.setOnClickListener(v -> show(path));
        }
        crumbs.addView(t);
    }

    @Override
    public void onBackPressed() {
        if (reader != null && !cur.isEmpty()) {
            int i = cur.lastIndexOf('/');
            show(i < 0 ? "" : cur.substring(0, i));
        } else {
            super.onBackPressed();
        }
    }

    // ======================= 打开文件 =======================

    private void onItemClick(ArcEntry e) {
        if (e.dir) {
            show(e.path);
            return;
        }
        view(e, null);
    }

    /** 解压到缓存后打开。mime 为 null 时按扩展名自动选择 */
    private void view(final ArcEntry e, final String mime) {
        File done = viewed.get(e.path);
        if (done != null && done.isFile()) {
            launch(e, done, mime);
            return;
        }
        if (e.encrypted && reader.password() == null) {
            ArchiveUi.askPassword(this, displayName, false, pw -> {
                reader.setPassword(pw);
                view(e, mime);
            }, null);
            return;
        }
        final File dir = new File(session, "v" + viewed.size());
        final File out = new File(dir, Util.safeName(e.name()));
        final boolean[] ok = new boolean[1];
        OpRunner.run(this, "正在打开", p -> {
            if (!dir.mkdirs() && !dir.isDirectory()) throw new IOException("无法创建缓存目录");
            p.total = e.size;
            List<ArcEntry> one = new ArrayList<>();
            one.add(e);
            reader.extract(one, new ArchiveReader.Sink() {
                @Override
                public OutputStream open(ArcEntry x) throws IOException {
                    return new java.io.BufferedOutputStream(new FileOutputStream(out), 128 * 1024);
                }

                @Override
                public void close(ArcEntry x, OutputStream o, boolean good) throws IOException {
                    o.close();
                    if (!good) //noinspection ResultOfMethodCallIgnored
                        out.delete();
                }
            }, p);
            ok[0] = out.isFile();
        }, () -> {
            if (ok[0] && !isDestroyed()) {
                if (e.mtime > 0) //noinspection ResultOfMethodCallIgnored
                    out.setLastModified(e.mtime);
                viewed.put(e.path, out);
                launch(e, out, mime);
            }
        }, ex -> {
            if (ex instanceof PasswordException) {
                ArchiveUi.askPassword(this, displayName, ((PasswordException) ex).wrong, pw -> {
                    reader.setPassword(pw);
                    view(e, mime);
                }, null);
                return true;
            }
            return false;
        });
    }

    private void launch(ArcEntry e, File f, String mime) {
        String name = e.name();
        String path = f.getAbsolutePath();
        if ("builtin/archive".equals(mime) || (mime == null && Formats.isArchive(name))) {
            startActivity(new Intent(this, ArchiveActivity.class)
                    .putExtra(EXTRA_PATH, path)
                    .putExtra(EXTRA_NAME, name)
                    .putExtra(EXTRA_DEST, destParent));
            return;
        }
        if ("builtin/editor".equals(mime) || (mime == null && MainActivity.isTextFile(name) && !"svg".equals(Util.ext(name)))) {
            startActivity(new Intent(this, EditorActivity.class).putExtra(EditorActivity.EXTRA_PATH, path));
            return;
        }
        Intent i = new Intent(Intent.ACTION_VIEW);
        i.setDataAndType(ShareProvider.uriFor(path), mime != null ? mime : ShareProvider.mimeOf(name));
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            if (mime != null) startActivity(Intent.createChooser(i, "打开方式"));
            else startActivity(i);
        } catch (ActivityNotFoundException ex) {
            openAs(e);
        }
    }

    private void openAs(final ArcEntry e) {
        final String[] labels = {"内置文本编辑器", "压缩包查看器", "文本（其他应用）", "图片", "视频", "音频", "其他（全部应用）"};
        final String[] types = {"builtin/editor", "builtin/archive", "text/*", "image/*", "video/*", "audio/*", "*/*"};
        new AlertDialog.Builder(this).setTitle("以何种类型打开")
                .setItems(labels, (d, w) -> view(e, types[w])).show();
    }

    // ======================= 解压 =======================

    /** sel 为 null 时解压全部 */
    private void extract(List<ArcEntry> sel) {
        if (reader == null) return;
        final Set<ArcEntry> items = new LinkedHashSet<>();
        final String base;
        String folder = Formats.baseName(displayName);
        if (sel == null) {
            items.addAll(reader.entries());
            base = "";
        } else {
            for (ArcEntry e : sel) {
                if (e.dir) items.addAll(reader.under(e.path));
                else items.add(e);
            }
            base = cur;
            if (sel.size() == 1) folder = sel.get(0).dir ? sel.get(0).name() : Formats.baseName(sel.get(0).name());
        }
        ArchiveUi.chooseExtractTarget(this, destParent, folder, t -> ArchiveUi.extract(this, reader, items, base, t, displayName, ok -> {
            if (isDestroyed()) return;
            new AlertDialog.Builder(this)
                    .setTitle("解压完成")
                    .setMessage("已解压到：\n" + ArchiveUi.pretty(t.dir))
                    .setPositiveButton("打开文件夹", (d, w) -> ArchiveUi.openFolder(this, t.dir))
                    .setNegativeButton("留在这里", null)
                    .show();
        }), null);
    }

    // ======================= 多选 =======================

    private List<ArcEntry> selected() {
        List<ArcEntry> out = new ArrayList<>();
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
        getMenuInflater().inflate(R.menu.archive_select, menu);
        return true;
    }

    @Override
    public boolean onPrepareActionMode(ActionMode mode, Menu menu) {
        List<ArcEntry> s = selected();
        boolean oneFile = s.size() == 1 && !s.get(0).dir;
        menu.findItem(R.id.as_open_with).setVisible(oneFile);
        boolean files = !s.isEmpty();
        for (ArcEntry e : s) if (e.dir) files = false;
        menu.findItem(R.id.as_share).setVisible(files && s.size() <= 20);
        return true;
    }

    @Override
    public void onItemCheckedStateChanged(ActionMode mode, int position, long id, boolean checked) {
        mode.setTitle("已选 " + list.getCheckedItemCount() + " 项");
        mode.invalidate();
    }

    @Override
    public boolean onActionItemClicked(ActionMode mode, MenuItem item) {
        List<ArcEntry> sel = selected();
        int id = item.getItemId();
        if (id == R.id.as_all) {
            for (int i = 0; i < adapter.getCount(); i++) list.setItemChecked(i, true);
            return true;
        }
        if (sel.isEmpty()) return true;
        mode.finish();
        if (id == R.id.as_extract) {
            extract(sel);
        } else if (id == R.id.as_open_with) {
            openAs(sel.get(0));
        } else if (id == R.id.as_share) {
            share(sel);
        }
        return true;
    }

    @Override
    public void onDestroyActionMode(ActionMode mode) {
        actionMode = null;
    }

    /** 分享：先解压到缓存，再交给其他应用 */
    private void share(final List<ArcEntry> sel) {
        if (sel.isEmpty()) return;
        boolean enc = false;
        for (ArcEntry e : sel) enc |= e.encrypted;
        if (enc && reader.password() == null) {
            ArchiveUi.askPassword(this, displayName, false, pw -> {
                reader.setPassword(pw);
                share(sel);
            }, null);
            return;
        }
        final File dir = new File(session, "s" + System.nanoTime());
        final ArrayList<Uri> uris = new ArrayList<>();
        OpRunner.run(this, "正在准备分享", p -> {
            if (!dir.mkdirs() && !dir.isDirectory()) throw new IOException("无法创建缓存目录");
            long t = 0;
            for (ArcEntry e : sel) t += Math.max(0, e.size);
            p.total = t;
            final Set<String> used = new java.util.HashSet<>();
            reader.extract(sel, new ArchiveReader.Sink() {
                File f;

                @Override
                public OutputStream open(ArcEntry e) throws IOException {
                    String n = Util.safeName(e.name());
                    for (int i = 1; !used.add(n); i++) n = i + "_" + Util.safeName(e.name());
                    f = new File(dir, n);
                    return new java.io.BufferedOutputStream(new FileOutputStream(f), 128 * 1024);
                }

                @Override
                public void close(ArcEntry e, OutputStream o, boolean good) throws IOException {
                    o.close();
                    if (good) uris.add(ShareProvider.uriFor(f.getAbsolutePath()));
                    else //noinspection ResultOfMethodCallIgnored
                        f.delete();
                }
            }, p);
        }, () -> {
            if (uris.isEmpty() || isDestroyed()) return;
            Intent i;
            if (uris.size() == 1) {
                i = new Intent(Intent.ACTION_SEND);
                i.setType(ShareProvider.mimeOf(sel.get(0).name()));
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
        }, ex -> {
            if (ex instanceof PasswordException) {
                ArchiveUi.askPassword(this, displayName, ((PasswordException) ex).wrong, pw -> {
                    reader.setPassword(pw);
                    share(sel);
                }, null);
                return true;
            }
            return false;
        });
    }

    // ======================= 菜单 =======================

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.archive, menu);
        MenuItem s = menu.findItem(R.id.a_search);
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
                show(cur);
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
                show(cur);
                return true;
            }
        });
        return true;
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        menu.findItem(R.id.a_charset).setVisible(reader != null && reader.supportsCharset());
        return super.onPrepareOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == android.R.id.home) {
            finish();
        } else if (id == R.id.a_extract_all) {
            extract(null);
        } else if (id == R.id.a_charset) {
            chooseCharset();
        } else if (id == R.id.a_info) {
            info();
        } else return super.onOptionsItemSelected(item);
        return true;
    }

    private void chooseCharset() {
        if (reader == null) return;
        final String[] labels = {"自动识别", "UTF-8", "GBK / GB18030（简体中文）", "Big5（繁体中文）", "Shift_JIS（日文）", "EUC-KR（韩文）", "CP437（西文 DOS）"};
        final String[] cs = {null, "UTF-8", "GB18030", "Big5", "Shift_JIS", "EUC-KR", "IBM437"};
        new AlertDialog.Builder(this).setTitle("文件名编码：" + reader.charset())
                .setItems(labels, (d, w) -> {
                    try {
                        reader.setCharset(cs[w]);
                    } catch (Exception e) {
                        Toast.makeText(this, "系统不支持该编码", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    viewed.clear();
                    scrollPos.clear();
                    cur = "";
                    show("");
                }).show();
    }

    private void info() {
        if (reader == null) return;
        int files = 0, dirs = 0, enc = 0;
        long size = 0, packed = 0;
        boolean sizeKnown = true, packedKnown = true;
        for (ArcEntry e : reader.entries()) {
            if (e.dir) {
                dirs++;
                continue;
            }
            files++;
            if (e.encrypted) enc++;
            if (e.size >= 0) size += e.size;
            else sizeKnown = false;
            if (e.packed >= 0) packed += e.packed;
            else packedKnown = false;
        }
        long fileSize = reader.file().length();
        StringBuilder sb = new StringBuilder();
        sb.append("名称：").append(displayName).append('\n');
        sb.append("格式：").append(reader.format()).append('\n');
        sb.append("压缩包大小：").append(Util.formatSize(fileSize)).append('\n');
        sb.append("包含：").append(files).append(" 个文件，").append(dirs).append(" 个文件夹\n");
        if (sizeKnown) {
            sb.append("解压后大小：").append(Util.formatSize(size)).append('\n');
            long p = packedKnown && packed > 0 ? packed : fileSize;
            if (size > 0) sb.append("压缩率：").append(Math.round(p * 100.0 / size)).append("%\n");
        }
        if (enc > 0) sb.append("加密：").append(enc).append(" 个文件已加密\n");
        if (reader.supportsCharset()) sb.append("文件名编码：").append(reader.charset()).append('\n');
        sb.append("默认解压位置：").append(ArchiveUi.pretty(destParent));
        new AlertDialog.Builder(this).setTitle("压缩包信息").setMessage(sb.toString())
                .setPositiveButton("确定", null).show();
    }

    // ======================= 工具 =======================

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private final class Adapter extends BaseAdapter {
        private List<ArcEntry> items = new ArrayList<>();

        void set(List<ArcEntry> l) {
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
        public ArcEntry getItem(int position) {
            return items.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View v, ViewGroup parent) {
            if (v == null) v = LayoutInflater.from(ArchiveActivity.this).inflate(R.layout.item_file, parent, false);
            ArcEntry e = items.get(position);
            TextView name = v.findViewById(R.id.name);
            TextView info = v.findViewById(R.id.info);
            ImageView icon = v.findViewById(R.id.icon);
            v.findViewById(R.id.lock).setVisibility(e.encrypted ? View.VISIBLE : View.GONE);
            name.setText(e.name());
            StringBuilder sb = new StringBuilder();
            if (e.dir) {
                sb.append(reader.list(e.path).size()).append(" 项");
            } else {
                sb.append(e.size >= 0 ? Util.formatSize(e.size) : "大小未知");
                if (e.packed >= 0 && e.size > 0 && e.packed < e.size) {
                    sb.append("（压缩后 ").append(Util.formatSize(e.packed)).append("）");
                }
            }
            if (e.mtime > 0) sb.append(" · ").append(df.format(new Date(e.mtime)));
            info.setText(sb.toString());
            icon.setTag(null);
            icon.setImageResource(Thumbs.iconFor(e.name(), e.dir));
            return v;
        }
    }
}
