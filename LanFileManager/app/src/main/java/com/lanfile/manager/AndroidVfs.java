package com.lanfile.manager;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.UriPermission;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.provider.DocumentsContract;
import android.provider.DocumentsContract.Document;

import com.lanfile.manager.core.LocalVfs;
import com.lanfile.manager.core.Util;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Android 文件系统：
 * - 普通路径：java.io.File（需"所有文件访问权限"）
 * - Android 11+ 的 Android/data、Android/obb：参照 ZArchiver，透明地走 SAF（DocumentsContract）。
 *   Android 11/12 授权整个 Android/data（或 obb）一次即可；Android 13+ 系统不允许，改为逐个 app 目录授权。
 *   授权记录由系统保存，卸载应用时系统自动回收，不产生残留。
 */
public class AndroidVfs extends LocalVfs {

    public static final String EXT_AUTH = "com.android.externalstorage.documents";

    private final Context ctx;
    private final ContentResolver cr;
    private final Map<String, String> volumeIds = new HashMap<>();   // 根路径 -> SAF 卷 ID（primary / XXXX-XXXX）
    private final Map<String, String> labels = new HashMap<>();
    private volatile List<Uri> treeCache;
    private volatile Map<String, String> appLabels;
    // 零宽字符绕过：每个"卷根/data|obb"是否可直接用 File 访问（null=未探测）
    private final Map<String, Boolean> zwCache = new HashMap<>();

    public AndroidVfs(Context c) {
        super(new ArrayList<File>());
        ctx = c.getApplicationContext();
        cr = ctx.getContentResolver();
        refreshRoots();
    }

    // ================= 存储卷 =================

    public void refreshRoots() {
        List<File> dirs = new ArrayList<>();
        volumeIds.clear();
        labels.clear();
        zwCache.clear();
        File primary = Environment.getExternalStorageDirectory();
        StorageManager sm = (StorageManager) ctx.getSystemService(Context.STORAGE_SERVICE);
        if (sm != null && Build.VERSION.SDK_INT >= 24) {
            try {
                for (StorageVolume v : sm.getStorageVolumes()) {
                    if (!Environment.MEDIA_MOUNTED.equals(v.getState())) continue;
                    File d = volumeDir(v);
                    if (d == null) continue;
                    String path = d.getAbsolutePath();
                    if (volumeIds.containsKey(path)) continue;
                    dirs.add(d);
                    String id = v.isPrimary() ? "primary" : (v.getUuid() != null ? v.getUuid() : d.getName());
                    volumeIds.put(path, id);
                    String desc = v.getDescription(ctx);
                    labels.put(path, v.isPrimary() ? "内部存储" : (desc == null ? d.getName() : desc));
                }
            } catch (Exception ignored) {
            }
        }
        if (dirs.isEmpty()) {
            dirs.add(primary);
            volumeIds.put(primary.getAbsolutePath(), "primary");
            labels.put(primary.getAbsolutePath(), "内部存储");
        }
        setRoots(dirs);
    }

    private static File volumeDir(StorageVolume v) {
        if (Build.VERSION.SDK_INT >= 30) return v.getDirectory();
        try {
            Method m = StorageVolume.class.getMethod("getPathFile");
            return (File) m.invoke(v);
        } catch (Exception e) {
            try {
                Method m = StorageVolume.class.getMethod("getPath");
                return new File((String) m.invoke(v));
            } catch (Exception e2) {
                return null;
            }
        }
    }

    @Override
    public List<Entry> roots() {
        List<Entry> list = super.roots();
        for (Entry e : list) e.label = labels.get(e.path);
        return list;
    }

    public String primaryRoot() {
        return Environment.getExternalStorageDirectory().getAbsolutePath();
    }

    /** 返回 path 所在卷的根路径 */
    public String rootOf(String path) {
        for (Entry r : super.roots()) if (Util.isUnder(path, r.path)) return r.path;
        return null;
    }

    public String labelOf(String root) {
        String l = labels.get(root);
        return l == null ? Util.nameOf(root) : l;
    }

    // ================= 受限目录判断 =================

    /** 相对卷根的路径，如 "Android/data/com.x/files"；不在任何卷下返回 null */
    private String rel(String path) {
        String root = rootOf(path);
        if (root == null) return null;
        if (path.length() == root.length()) return "";
        return path.substring(root.length() + 1);
    }

    /** 是否需要走 SAF */
    public boolean isRestricted(String path) {
        if (Build.VERSION.SDK_INT < 30) return false;
        String r = rel(path);
        if (r == null) return false;
        String pkg = ctx.getPackageName();
        for (String base : new String[]{"Android/data", "Android/obb"}) {
            if (r.equals(base)) return true;
            if (r.startsWith(base + "/")) {
                // 自己的专属目录可以直接用 File 访问
                return !(r.equals(base + "/" + pkg) || r.startsWith(base + "/" + pkg + "/"));
            }
        }
        return false;
    }

    /** Android 13+ 时 Android/data 本身无法授权，只能按 app 授权 */
    private boolean perAppMode() {
        return Build.VERSION.SDK_INT >= 33;
    }

    private boolean isRestrictedBase(String path) {
        String r = rel(path);
        return r != null && (r.equals("Android/data") || r.equals("Android/obb"));
    }

    /** 访问 path 需要授权的那个目录 */
    public String grantPathFor(String path) {
        String root = rootOf(path);
        String r = rel(path);
        if (root == null || r == null) return path;
        String[] seg = r.split("/");
        if (seg.length < 2) return path;
        String base = root + "/" + seg[0] + "/" + seg[1];
        if (!perAppMode() || seg.length < 3) return base;
        return base + "/" + seg[2];
    }

    public String docId(String path) {
        String root = rootOf(path);
        if (root == null) return null;
        String id = volumeIds.get(root);
        if (id == null) id = "primary";
        String r = rel(path);
        return id + ":" + (r == null ? "" : r);
    }

    /** 授权申请时的初始定位 Uri */
    public Uri initialUriFor(String grantPath) {
        return DocumentsContract.buildDocumentUri(EXT_AUTH, docId(grantPath));
    }

    public void invalidateGrants() {
        treeCache = null;
    }

    private List<Uri> trees() {
        List<Uri> t = treeCache;
        if (t != null) return t;
        t = new ArrayList<>();
        for (UriPermission p : cr.getPersistedUriPermissions()) {
            Uri u = p.getUri();
            if (EXT_AUTH.equals(u.getAuthority()) && p.isReadPermission()) t.add(u);
        }
        treeCache = t;
        return t;
    }

    private Uri treeFor(String docId) {
        Uri best = null;
        int bestLen = -1;
        for (Uri u : trees()) {
            String tid;
            try {
                tid = DocumentsContract.getTreeDocumentId(u);
            } catch (Exception e) {
                continue;
            }
            if ((docId.equals(tid) || docId.startsWith(tid.endsWith(":") ? tid : tid + "/")) && tid.length() > bestLen) {
                best = u;
                bestLen = tid.length();
            }
        }
        return best;
    }

    public boolean hasGrant(String path) {
        return zwUsable(path) || ShizukuHelper.usable() || treeFor(docId(path)) != null;
    }

    // ================= 零宽字符绕过（Android 14+ 免授权直读写） =================
    // 原理：系统校验路径时会忽略零宽空格(U+200B)，但底层文件系统照常解析到真实目录。
    // 因此 "Android/\u200bdata/..." 可绕过对 Android/data 的访问限制。该漏洞在 Android 16 仍有效，
    // 一旦被官方修复会自动失效，此时回退到 Shizuku / SAF。

    private static final char ZW = '\u200b';

    /** 把受限路径里的 Android/data、Android/obb 改写成带零宽字符的别名路径 */
    private String zwPath(String path) {
        String root = rootOf(path);
        String r = rel(path);
        if (root == null || r == null) return null;
        for (String base : new String[]{"Android/data", "Android/obb"}) {
            if (r.equals(base) || r.startsWith(base + "/")) {
                String alias = "Android/" + ZW + base.substring("Android/".length());
                return root + "/" + alias + r.substring(base.length());
            }
        }
        return null;
    }

    /** 当前卷的 data/obb 能否用零宽字符免授权访问（探测一次并缓存） */
    private boolean zwUsable(String path) {
        if (Build.VERSION.SDK_INT < 34) return false;   // 该漏洞自 Android 14 起出现
        String root = rootOf(path);
        String r = rel(path);
        if (root == null || r == null) return false;
        String base = r.startsWith("Android/obb") ? "Android/obb" : "Android/data";
        String key = root + "/" + base;
        Boolean cached = zwCache.get(key);
        if (cached != null) return cached;
        boolean ok = false;
        try {
            File probe = new File(root + "/Android/" + ZW + base.substring("Android/".length()));
            ok = probe.canRead();
        } catch (Exception ignored) {
        }
        zwCache.put(key, ok);
        return ok;
    }

    /** 优先用零宽字符得到一个可直接 File 访问的路径；不可用返回 null */
    private File zwFile(String path) {
        if (!zwUsable(path)) return null;
        String zw = zwPath(path);
        return zw == null ? null : new File(zw);
    }

    public void invalidateZw() {
        zwCache.clear();
    }

    // ================= Shizuku（shell 权限） =================

    private IFileService shz() {
        return ShizukuHelper.usable() ? ShizukuHelper.get() : null;
    }

    private static IOException io(Exception e) {
        String m = e.getMessage();
        return new IOException(m == null || m.isEmpty() ? "Shizuku 调用失败" : m);
    }

    private static Entry parseLine(String dir, String line) {
        String[] p = line.split("\\|", 4);
        if (p.length < 4) return null;
        Entry e = new Entry();
        e.dir = "1".equals(p[0]);
        try { e.size = Long.parseLong(p[1]); } catch (NumberFormatException ignored) {}
        try { e.mtime = Long.parseLong(p[2]); } catch (NumberFormatException ignored) {}
        e.name = p[3];
        e.path = dir == null ? null : Util.join(dir, e.name);
        return e;
    }

    /** 用户在授权界面选择完成后调用；返回是否是期望的目录 */
    public boolean acceptGrant(Uri tree, String expectedGrantPath) {
        int flags = Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
        String tid = DocumentsContract.getTreeDocumentId(tree);
        String want = docId(expectedGrantPath);
        boolean ok = tid.equals(want) || want.startsWith(tid + "/") || want.startsWith(tid.endsWith(":") ? tid : tid + "/");
        if (!ok) return false;
        try {
            cr.takePersistableUriPermission(tree, flags);
        } catch (SecurityException e) {
            return false;
        }
        invalidateGrants();
        return true;
    }

    private Uri docUri(String path) throws NeedAccessException {
        String id = docId(path);
        Uri tree = treeFor(id);
        if (tree == null) throw new NeedAccessException(grantPathFor(path));
        return DocumentsContract.buildDocumentUriUsingTree(tree, id);
    }

    // ================= Vfs 实现 =================

    private static final String[] COLS = {
            Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE,
            Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED
    };

    @Override
    public List<Entry> list(String dirPath) throws IOException {
        if (!isRestricted(dirPath)) {
            List<Entry> l = super.list(dirPath);
            if (Build.VERSION.SDK_INT >= 30) markRestrictedChildren(dirPath, l);
            return l;
        }
        File zw = zwFile(dirPath);
        if (zw != null) {
            File[] files = zw.listFiles();
            if (files != null) {
                List<Entry> out = new ArrayList<>(files.length);
                for (File f : files) {
                    Entry e = toEntry(f);
                    e.path = Util.join(dirPath, e.name);   // 对外仍是正常路径，隐藏零宽字符
                    out.add(e);
                }
                if (isRestrictedBase(dirPath)) {
                    Map<String, String> apps = appLabels();
                    for (Entry e : out) e.label = apps.get(e.name);
                }
                return out;
            }
            // 读不到则回退到其他方式
        }
        IFileService sz = shz();
        if (sz != null) {
            String r;
            try {
                r = sz.list(dirPath);
            } catch (RemoteException | RuntimeException e) {
                throw io(e);
            }
            if (r == null) throw new FileNotFoundException(dirPath);
            List<Entry> out = new ArrayList<>();
            for (String line : r.split("\n")) {
                if (line.isEmpty()) continue;
                Entry e = parseLine(dirPath, line);
                if (e != null) out.add(e);
            }
            if (isRestrictedBase(dirPath)) {
                Map<String, String> apps = appLabels();
                for (Entry e : out) e.label = apps.get(e.name);
            }
            return out;
        }
        String id = docId(dirPath);
        Uri tree = treeFor(id);
        if (tree == null) {
            if (perAppMode() && isRestrictedBase(dirPath)) return listApps(dirPath);
            throw new NeedAccessException(grantPathFor(dirPath));
        }
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, id);
        List<Entry> out = new ArrayList<>();
        Cursor c = null;
        try {
            c = cr.query(children, COLS, null, null, null);
            if (c == null) throw new IOException("无法读取目录");
            while (c.moveToNext()) {
                Entry e = new Entry();
                e.name = c.getString(1);
                e.path = Util.join(dirPath, e.name);
                e.dir = Document.MIME_TYPE_DIR.equals(c.getString(2));
                e.size = c.isNull(3) ? 0 : c.getLong(3);
                e.mtime = c.isNull(4) ? 0 : c.getLong(4);
                out.add(e);
            }
        } catch (SecurityException se) {
            invalidateGrants();
            throw new NeedAccessException(grantPathFor(dirPath));
        } catch (IllegalArgumentException | IllegalStateException ex) {
            throw new FileNotFoundException(dirPath);
        } finally {
            if (c != null) c.close();
        }
        if (perAppMode() && isRestrictedBase(dirPath)) {
            // 合并：已授权的 app 目录 + 其他已安装 app
            Map<String, String> apps = appLabels();
            for (Entry e : out) e.label = apps.get(e.name);
        }
        return out;
    }

    /** 在 Android 目录下给 data/obb 加标记，界面上提示"需授权" */
    private void markRestrictedChildren(String dirPath, List<Entry> l) {
        String r = rel(dirPath);
        if (!"Android".equals(r)) return;
        for (Entry e : l) {
            if (e.dir && (e.name.equals("data") || e.name.equals("obb"))) {
                e.label = e.name.equals("data") ? "data（各应用私有数据）" : "obb（应用数据包）";
                e.locked = !perAppMode() && !hasGrant(e.path);
            }
        }
    }

    /** Android 13+：列出已安装应用，逐个授权 */
    private List<Entry> listApps(String base) {
        Map<String, String> apps = appLabels();
        List<Entry> out = new ArrayList<>();
        for (Map.Entry<String, String> a : apps.entrySet()) {
            if (a.getKey().equals(ctx.getPackageName())) continue;
            Entry e = new Entry();
            e.name = a.getKey();
            e.label = a.getValue();
            e.path = Util.join(base, a.getKey());
            e.dir = true;
            e.locked = !hasGrant(e.path);
            out.add(e);
        }
        // 自己的专属目录（如果存在）
        File own = new File(base, ctx.getPackageName());
        if (own.isDirectory()) {
            Entry e = toEntry(own);
            e.label = ctx.getString(R.string.app_name);
            out.add(e);
        }
        return out;
    }

    private Map<String, String> appLabels() {
        Map<String, String> m = appLabels;
        if (m != null) return m;
        m = new HashMap<>();
        PackageManager pm = ctx.getPackageManager();
        try {
            for (ApplicationInfo ai : pm.getInstalledApplications(0)) {
                boolean system = (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0
                        && (ai.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0;
                if (system && pm.getLaunchIntentForPackage(ai.packageName) == null) continue;
                CharSequence l = ai.loadLabel(pm);
                m.put(ai.packageName, l == null ? ai.packageName : l.toString());
            }
        } catch (Exception ignored) {
        }
        appLabels = m;
        return m;
    }

    public void invalidateApps() {
        appLabels = null;
    }

    @Override
    public Entry stat(String path) throws IOException {
        if (!isRestricted(path)) return super.stat(path);
        File zw = zwFile(path);
        if (zw != null && zw.exists()) {
            Entry e = toEntry(zw);
            e.path = path;
            e.name = Util.nameOf(path);
            return e;
        }
        IFileService sz = shz();
        if (sz != null) {
            try {
                String r = sz.stat(path);
                if (r == null) return null;
                Entry e = parseLine(Util.parent(path), r.trim());
                if (e != null) e.path = path;
                return e;
            } catch (RemoteException | RuntimeException e) {
                throw io(e);
            }
        }
        if (isRestrictedBase(path)) {
            Entry e = new Entry();
            e.path = path;
            e.name = Util.nameOf(path);
            e.dir = true;
            return e;
        }
        Uri u;
        try {
            u = docUri(path);
        } catch (NeedAccessException ex) {
            if (perAppMode() && path.equals(grantPathFor(path))) {
                Entry e = new Entry();
                e.path = path;
                e.name = Util.nameOf(path);
                e.dir = true;
                e.locked = true;
                return e;
            }
            throw ex;
        }
        Cursor c = null;
        try {
            c = cr.query(u, COLS, null, null, null);
            if (c == null || !c.moveToFirst()) return null;
            Entry e = new Entry();
            e.path = path;
            e.name = c.getString(1);
            e.dir = Document.MIME_TYPE_DIR.equals(c.getString(2));
            e.size = c.isNull(3) ? 0 : c.getLong(3);
            e.mtime = c.isNull(4) ? 0 : c.getLong(4);
            if (e.name == null || !e.name.equals(Util.nameOf(path))) e.name = Util.nameOf(path);
            return e;
        } catch (Exception ex) {
            return null;
        } finally {
            if (c != null) c.close();
        }
    }

    @Override
    public InputStream openRead(String path, long offset) throws IOException {
        if (!isRestricted(path)) return super.openRead(path, offset);
        File zw = zwFile(path);
        if (zw != null) {
            FileInputStream in = new FileInputStream(zw);
            if (offset > 0) in.getChannel().position(offset);
            return in;
        }
        ParcelFileDescriptor pfd = shzOpen(path, 0);
        if (pfd == null) pfd = cr.openFileDescriptor(docUri(path), "r");
        if (pfd == null) throw new FileNotFoundException(path);
        FileInputStream in = new ParcelFileDescriptor.AutoCloseInputStream(pfd);
        if (offset > 0) in.getChannel().position(offset);
        return in;
    }

    public ParcelFileDescriptor openPfd(String path) throws IOException {
        if (!isRestricted(path)) return ParcelFileDescriptor.open(new File(path), ParcelFileDescriptor.MODE_READ_ONLY);
        File zw = zwFile(path);
        if (zw != null) return ParcelFileDescriptor.open(zw, ParcelFileDescriptor.MODE_READ_ONLY);
        ParcelFileDescriptor pfd = shzOpen(path, 0);
        return pfd != null ? pfd : cr.openFileDescriptor(docUri(path), "r");
    }

    /** Shizuku 不可用时返回 null */
    private ParcelFileDescriptor shzOpen(String path, int mode) throws IOException {
        IFileService sz = shz();
        if (sz == null) return null;
        try {
            ParcelFileDescriptor pfd = sz.open(path, mode);
            if (pfd == null) throw new FileNotFoundException(path);
            return pfd;
        } catch (RemoteException | RuntimeException e) {
            throw io(e);
        }
    }

    @Override
    public OutputStream create(String parentPath, String name) throws IOException {
        if (!isRestricted(parentPath)) return super.create(parentPath, name);
        File zwParent = zwFile(parentPath);
        if (zwParent != null) return new java.io.FileOutputStream(new File(zwParent, name));
        ParcelFileDescriptor w = shzOpen(Util.join(parentPath, name), 1);
        if (w != null) return new ParcelFileDescriptor.AutoCloseOutputStream(w);
        Uri parent = docUri(parentPath);
        Uri doc = DocumentsContract.createDocument(cr, parent, "application/octet-stream", name);
        if (doc == null) throw new IOException("无法创建文件: " + name);
        OutputStream os = cr.openOutputStream(doc, "w");
        if (os == null) throw new IOException("无法写入文件: " + name);
        return os;
    }

    @Override
    public String mkdir(String parentPath, String name) throws IOException {
        if (!isRestricted(parentPath)) return super.mkdir(parentPath, name);
        File zwParent = zwFile(parentPath);
        if (zwParent != null) {
            File d = new File(zwParent, name);
            if (!d.isDirectory() && !d.mkdirs()) throw new IOException("无法创建文件夹: " + name);
            return Util.join(parentPath, name);
        }
        IFileService sz = shz();
        if (sz != null) {
            String np = Util.join(parentPath, name);
            try {
                if (!sz.mkdir(np)) throw new IOException("无法创建文件夹: " + name);
            } catch (RemoteException | RuntimeException e) {
                throw io(e);
            }
            return np;
        }
        Uri parent = docUri(parentPath);
        Uri doc = DocumentsContract.createDocument(cr, parent, Document.MIME_TYPE_DIR, name);
        if (doc == null) throw new IOException("无法创建文件夹: " + name);
        return Util.join(parentPath, name);
    }

    @Override
    public void delete(String path) throws IOException {
        if (!isRestricted(path)) {
            super.delete(path);
            return;
        }
        File zw = zwFile(path);
        if (zw != null) {
            deleteRecursive(zw);
            if (zw.exists()) throw new IOException("删除失败: " + Util.nameOf(path));
            return;
        }
        IFileService sz = shz();
        if (sz != null) {
            try {
                if (!sz.delete(path)) throw new IOException("删除失败");
            } catch (RemoteException | RuntimeException e) {
                throw io(e);
            }
            return;
        }
        if (!DocumentsContract.deleteDocument(cr, docUri(path))) throw new IOException("删除失败");
    }

    @Override
    public String rename(String path, String newName) throws IOException {
        if (!isRestricted(path)) return super.rename(path, newName);
        File zw = zwFile(path);
        if (zw != null) {
            File t = new File(zw.getParentFile(), newName);
            if (t.exists()) throw new IOException("已存在同名项目: " + newName);
            if (!zw.renameTo(t)) throw new IOException("重命名失败");
            return Util.join(Util.parent(path), newName);
        }
        IFileService sz = shz();
        if (sz != null) {
            String np = Util.join(Util.parent(path), newName);
            try {
                if (!sz.rename(path, np)) throw new IOException("重命名失败（可能已存在同名项目）");
            } catch (RemoteException | RuntimeException e) {
                throw io(e);
            }
            return np;
        }
        Uri u = DocumentsContract.renameDocument(cr, docUri(path), newName);
        if (u == null) throw new IOException("重命名失败");
        return Util.join(Util.parent(path), newName);
    }

    /** 两个路径都是普通文件且在同一卷时可直接 rename */
    public boolean fastMove(String src, String destDir, String name) {
        if (isRestricted(src) || isRestricted(destDir)) {
            if (!String.valueOf(rootOf(src)).equals(rootOf(destDir))) return false;
            // 零宽字符可用时直接用 File 在同卷内移动
            File zs = isRestricted(src) ? zwFile(src) : new File(src);
            File zd = isRestricted(destDir) ? zwFile(destDir) : new File(destDir);
            if (zs != null && zd != null) {
                try {
                    if (zs.renameTo(new File(zd, name))) return true;
                } catch (Exception ignored) {
                }
            }
            // 否则用 shell 权限直接移动，速度与普通移动一样
            IFileService sz = shz();
            if (sz == null) return false;
            try {
                return sz.rename(src, Util.join(destDir, name));
            } catch (Exception e) {
                return false;
            }
        }
        return new File(src).renameTo(new File(destDir, name));
    }

    private static void deleteRecursive(File f) {
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) for (File k : kids) deleteRecursive(k);
        }
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }
}
