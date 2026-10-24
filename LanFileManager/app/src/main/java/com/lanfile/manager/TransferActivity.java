package com.lanfile.manager;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.text.util.Linkify;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import com.lanfile.manager.core.Hub;
import com.lanfile.manager.core.Util;
import com.lanfile.manager.core.Vfs;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class TransferActivity extends Activity implements Hub.Listener {

    static volatile boolean visible;
    private static final int REQ_PICK = 31;

    private final Hub hub = Hub.get();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private TextView status, url, urlMore, pin, msgEmpty;
    private View dot, pinRow;
    private Button toggle;
    private EditText input;
    private ListView msgs;
    private MsgAdapter adapter;
    private int lastCount = -1;
    private final SimpleDateFormat tf = new SimpleDateFormat("HH:mm", Locale.CHINA);

    /** 从文件管理器"发送到电脑" */
    static void sendPaths(final Context c, final List<String> paths) {
        if (!TransferService.isRunning()) TransferService.start(c);
        new Thread(() -> {
            for (String p : paths) {
                try {
                    Vfs.Entry e = App.vfs.stat(p);
                    if (e == null) continue;
                    Hub.get().addFile(false, e.name, e.dir ? -1 : e.size, p, e.dir);
                } catch (Exception ignored) {
                }
            }
        }).start();
        c.startActivity(new Intent(c, TransferActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_transfer);
        if (getActionBar() != null) getActionBar().setDisplayHomeAsUpEnabled(true);
        status = findViewById(R.id.status);
        url = findViewById(R.id.url);
        urlMore = findViewById(R.id.urlMore);
        pin = findViewById(R.id.pin);
        pinRow = findViewById(R.id.pinRow);
        dot = findViewById(R.id.dot);
        toggle = findViewById(R.id.toggle);
        input = findViewById(R.id.input);
        msgs = findViewById(R.id.msgs);
        msgEmpty = findViewById(R.id.msgEmpty);
        adapter = new MsgAdapter();
        msgs.setAdapter(adapter);
        msgs.setOnItemClickListener((p, v, pos, id) -> onMsgClick(adapter.getItem(pos)));
        msgs.setOnItemLongClickListener((p, v, pos, id) -> {
            onMsgLongClick(adapter.getItem(pos));
            return true;
        });

        toggle.setOnClickListener(v -> {
            if (hub.isRunning()) TransferService.stop(this);
            else TransferService.start(this);
        });
        findViewById(R.id.newPin).setOnClickListener(v -> {
            hub.regeneratePin();
            toast("已更换访问码，已连接的电脑需要重新输入");
        });
        url.setOnClickListener(v -> {
            if (!hub.getUrls().isEmpty()) copy(hub.getUrls().get(0), "地址已复制");
        });
        findViewById(R.id.send).setOnClickListener(v -> sendText());
        findViewById(R.id.attach).setOnClickListener(v ->
                startActivityForResult(new Intent(this, MainActivity.class).putExtra(MainActivity.EXTRA_PICK, true), REQ_PICK));

        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        }
        if (!TransferService.isRunning()) TransferService.start(this);
        handleShare(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleShare(intent);
    }

    @Override
    protected void onResume() {
        super.onResume();
        visible = true;
        hub.addListener(this);
        TransferService.refreshUrls();
        render();
    }

    @Override
    protected void onPause() {
        super.onPause();
        visible = false;
        hub.removeListener(this);
    }

    @Override
    public void onHubChanged() {
        ui.post(this::render);
    }

    @Override
    public void onIncoming(Hub.Message m) {}

    private void render() {
        boolean run = hub.isRunning();
        List<String> u = hub.getUrls();
        dot.setBackgroundColor(run ? 0xFF22A06B : 0xFF9AA3AD);
        toggle.setText(run ? "停止" : "启动");
        if (!run) {
            status.setText("服务已停止");
            url.setText("—");
            urlMore.setVisibility(View.GONE);
        } else {
            int n = hub.clientCount();
            status.setText(n > 0 ? "运行中 · 已有 " + n + " 个浏览器连接" : "运行中 · 等待电脑连接");
            if (u.isEmpty()) {
                url.setText("未连接网络");
                urlMore.setText("请连接 Wi-Fi，或打开手机热点让电脑连上来");
                urlMore.setVisibility(View.VISIBLE);
            } else {
                url.setText(u.get(0).replace("http://", ""));
                if (u.size() > 1) {
                    StringBuilder sb = new StringBuilder("其他地址：");
                    for (int i = 1; i < u.size(); i++) sb.append(i > 1 ? "  " : "").append(u.get(i).replace("http://", ""));
                    urlMore.setText(sb);
                    urlMore.setVisibility(View.VISIBLE);
                } else urlMore.setVisibility(View.GONE);
            }
        }
        pinRow.setVisibility(hub.isPinRequired() && run ? View.VISIBLE : View.GONE);
        pin.setText(hub.getPin());
        List<Hub.Message> list = hub.snapshot();
        adapter.set(list);
        msgEmpty.setVisibility(list.isEmpty() ? View.VISIBLE : View.GONE);
        msgs.setVisibility(list.isEmpty() ? View.GONE : View.VISIBLE);
        if (list.size() != lastCount) {
            lastCount = list.size();
            msgs.post(() -> msgs.setSelection(adapter.getCount() - 1));
        }
    }

    private void sendText() {
        String t = input.getText().toString();
        if (t.trim().isEmpty()) return;
        if (!hub.isRunning()) {
            toast("服务未运行");
            return;
        }
        hub.addText(false, t);
        input.setText("");
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_PICK && resultCode == RESULT_OK && data != null) {
            ArrayList<String> paths = data.getStringArrayListExtra(MainActivity.EXTRA_PATHS);
            if (paths != null) sendPaths(this, paths);
        }
    }

    /** 其他应用"分享"到本应用：文字直接发；文件先复制到应用缓存（卸载即清除） */
    private void handleShare(Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        if (!Intent.ACTION_SEND.equals(action) && !Intent.ACTION_SEND_MULTIPLE.equals(action)) return;
        final List<Uri> uris = new ArrayList<>();
        if (Intent.ACTION_SEND.equals(action)) {
            Uri u = intent.getParcelableExtra(Intent.EXTRA_STREAM);
            if (u != null) uris.add(u);
        } else {
            ArrayList<Uri> l = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
            if (l != null) uris.addAll(l);
        }
        final CharSequence text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT);
        intent.setAction(null);
        if (!TransferService.isRunning()) TransferService.start(this);
        if (uris.isEmpty()) {
            if (text != null && text.length() > 0) ui.postDelayed(() -> hub.addText(false, text.toString()), 300);
            return;
        }
        toast("正在准备 " + uris.size() + " 个文件…");
        final Context c = getApplicationContext();
        new Thread(() -> {
            File box = App.outbox(c);
            //noinspection ResultOfMethodCallIgnored
            box.mkdirs();
            for (Uri u : uris) {
                try {
                    String name = displayName(u);
                    File dir = new File(box, String.valueOf(System.nanoTime()));
                    //noinspection ResultOfMethodCallIgnored
                    dir.mkdirs();
                    File f = new File(dir, Util.safeName(name));
                    try (InputStream in = getContentResolver().openInputStream(u); OutputStream out = new FileOutputStream(f)) {
                        if (in == null) continue;
                        Util.copy(in, out, -1);
                    }
                    hub.addFile(false, f.getName(), f.length(), f.getAbsolutePath(), false);
                } catch (Exception e) {
                    ui.post(() -> toast("有文件读取失败：" + e.getMessage()));
                }
            }
            if (text != null && text.length() > 0 && uris.size() == 0) hub.addText(false, text.toString());
        }).start();
    }

    private String displayName(Uri u) {
        String name = null;
        try (Cursor c = getContentResolver().query(u, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) name = c.getString(0);
        } catch (Exception ignored) {
        }
        if (name == null) name = u.getLastPathSegment();
        return name == null ? "file" : name;
    }

    private void onMsgClick(Hub.Message m) {
        if ("text".equals(m.type)) {
            copy(m.text, "已复制到剪贴板");
            return;
        }
        open(m);
    }

    private void open(Hub.Message m) {
        if ("folder".equals(m.type)) {
            startActivity(new Intent(this, MainActivity.class).putExtra(MainActivity.EXTRA_PATH, m.path)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP));
            return;
        }
        Intent i = new Intent(Intent.ACTION_VIEW);
        i.setDataAndType(ShareProvider.uriFor(m.path), ShareProvider.mimeOf(m.name));
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivity(i);
        } catch (ActivityNotFoundException e) {
            toast("没有可以打开此文件的应用");
        }
    }

    private void onMsgLongClick(final Hub.Message m) {
        if ("text".equals(m.type)) {
            new AlertDialog.Builder(this).setItems(new String[]{"复制", "分享"}, (d, w) -> {
                if (w == 0) copy(m.text, "已复制");
                else startActivity(Intent.createChooser(new Intent(Intent.ACTION_SEND).setType("text/plain")
                        .putExtra(Intent.EXTRA_TEXT, m.text), "分享"));
            }).show();
            return;
        }
        boolean inCache = m.path.startsWith(getCacheDir().getAbsolutePath());
        String[] items = inCache ? new String[]{"打开"} : new String[]{"打开", "在文件管理器中显示"};
        new AlertDialog.Builder(this).setTitle(m.name).setItems(items, (d, w) -> {
            if (w == 0) open(m);
            else startActivity(new Intent(this, MainActivity.class)
                    .putExtra(MainActivity.EXTRA_PATH, "folder".equals(m.type) ? m.path : Util.parent(m.path))
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP));
        }).show();
    }

    private void copy(String t, String msg) {
        ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("text", t));
        if (Build.VERSION.SDK_INT < 33) toast(msg);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.transfer, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == android.R.id.home) {
            finish();
        } else if (id == R.id.t_clear) {
            new AlertDialog.Builder(this).setMessage("清空聊天记录？（已收到的文件不会被删除）")
                    .setNegativeButton("取消", null)
                    .setPositiveButton("清空", (d, w) -> {
                        hub.clear();
                        App.clearOutbox(this);
                    }).show();
        } else if (id == R.id.t_settings) {
            SettingsDialog.show(this, this::render);
        } else return super.onOptionsItemSelected(item);
        return true;
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    private final class MsgAdapter extends BaseAdapter {
        private List<Hub.Message> items = new ArrayList<>();

        void set(List<Hub.Message> l) {
            items = l;
            notifyDataSetChanged();
        }

        @Override
        public int getCount() { return items.size(); }

        @Override
        public Hub.Message getItem(int position) { return items.get(position); }

        @Override
        public long getItemId(int position) { return items.get(position).id; }

        @Override
        public boolean hasStableIds() { return true; }

        @Override
        public View getView(int position, View v, ViewGroup parent) {
            if (v == null) v = LayoutInflater.from(TransferActivity.this).inflate(R.layout.item_msg, parent, false);
            Hub.Message m = items.get(position);
            boolean me = !m.fromPc;
            LinearLayout row = v.findViewById(R.id.row);
            row.setGravity(me ? Gravity.END : Gravity.START);
            TextView meta = v.findViewById(R.id.meta);
            meta.setText((me ? "手机" : "电脑") + " · " + tf.format(new Date(m.time)));
            View bubble = v.findViewById(R.id.bubble);
            bubble.setBackgroundResource(me ? R.drawable.bubble_me : R.drawable.bubble_other);
            int fg = me ? 0xFFFFFFFF : getColor(R.color.bubble_other_text);
            TextView text = v.findViewById(R.id.text);
            TextView sub = v.findViewById(R.id.sub);
            ImageView icon = v.findViewById(R.id.ficon);
            text.setTextColor(fg);
            sub.setTextColor(fg);
            text.setLinkTextColor(fg);
            if ("text".equals(m.type)) {
                icon.setVisibility(View.GONE);
                sub.setVisibility(View.GONE);
                text.setText(m.text);
                text.setAutoLinkMask(0);
                Linkify.addLinks(text, Linkify.WEB_URLS);
                text.setMovementMethod(null);
                text.setFocusable(false);
                text.setClickable(false);
                text.setLongClickable(false);
            } else {
                boolean folder = "folder".equals(m.type);
                icon.setVisibility(View.VISIBLE);
                sub.setVisibility(View.VISIBLE);
                text.setText(m.name);
                String s = folder ? "文件夹" : Util.formatSize(m.size);
                sub.setText(s + (me ? " · 电脑可下载" : " · 点击打开"));
                Thumbs.load(TransferActivity.this, icon, m.path, m.name, folder, App.vfs.isRestricted(m.path));
            }
            return v;
        }
    }
}
