package com.lanfile.manager;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;

import com.lanfile.manager.core.Util;
import com.lanfile.manager.core.Vfs;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 全盘扫描：遍历一次，同时得到「文件分类」和「垃圾清理」的结果。
 * 已授权时会一并扫描 Android/data、Android/obb（零宽字符 / Shizuku / SAF，均经由 AndroidVfs）。
 * 结果只保存在内存里，不写入磁盘。
 */
final class StorageScanner {

    // ---------------- 文件分类 ----------------
    static final int C_IMAGE = 0, C_AUDIO = 1, C_VIDEO = 2, C_DOC = 3, C_APK = 4, C_ARCHIVE = 5, C_LARGE = 6, C_COUNT = 7;
    static final String[] C_NAMES = {"图片", "音乐", "视频", "文档", "安装包", "压缩包", "大文件"};
    static final int[] C_ICONS = {R.drawable.ft_image, R.drawable.ft_audio, R.drawable.ft_video, R.drawable.ft_doc,
            R.drawable.ft_apk, R.drawable.ft_archive, R.drawable.ft_file};
    static final long LARGE = 50L * 1024 * 1024;

    private static final Set<String>[] C_EXT = exts(
            "jpg jpeg png gif webp bmp heic heif svg tif tiff raw dng ico avif",
            "mp3 flac wav aac m4a ogg wma ape opus amr mid midi aiff alac dsf dff m4b",
            "mp4 mkv avi mov wmv flv webm m4v 3gp ts rmvb rm mpg mpeg vob f4v m2ts",
            "doc docx xls xlsx ppt pptx pdf txt md epub csv wps et dps rtf odt ods odp mobi azw azw3 chm djvu pages numbers key",
            "apk apks xapk apkm",
            "zip rar 7z tar gz tgz bz2 xz zst lz4 lzma cab iso jar z tbz2 txz");

    private static Set<String>[] exts(String... groups) {
        @SuppressWarnings("unchecked")
        Set<String>[] out = new Set[groups.length];
        for (int i = 0; i < groups.length; i++) out[i] = new HashSet<>(Arrays.asList(groups[i].split(" ")));
        return out;
    }

    /** 按扩展名分类，不属于任何分类返回 -1（大文件不在此判断） */
    static int category(String name) {
        String ext = Util.ext(name);
        if (ext.isEmpty()) return -1;
        for (int i = 0; i < C_EXT.length; i++) if (C_EXT[i].contains(ext)) return i;
        return -1;
    }

    // ---------------- 垃圾清理 ----------------
    static final int J_CACHE = 0, J_RESIDUE = 1, J_THUMB = 2, J_TEMP = 3, J_LOG = 4, J_APK = 5, J_EMPTY = 6, J_COUNT = 7;
    static final String[] J_NAMES = {"应用缓存", "卸载残留", "缩略图缓存", "临时文件", "日志文件", "安装包", "空文件夹"};
    static final String[] J_DESC = {
            "各应用 Android/data/…/cache 里的缓存，删除后应用会按需重新生成",
            "已卸载应用留在 Android/data、Android/obb 里的文件",
            "相册等生成的 .thumbnails 缩略图，删除后会自动重建",
            "tmp、temp、~$ 开头的临时文件，以及未下载完的文件",
            "log、xlog 日志文件",
            "已安装或已损坏的安装包默认勾选；未安装的需要你自己决定",
            "不含任何文件的文件夹（不包括系统标准目录和隐藏目录）"};

    /** 存储根目录下不会被当作「空文件夹」清理的标准目录 */
    private static final Set<String> STD_DIRS = new HashSet<>(Arrays.asList(
            "Alarms", "Android", "Audiobooks", "DCIM", "Documents", "Download", "Movies", "Music", "Notifications",
            "Pictures", "Podcasts", "Recordings", "Ringtones", "Screenshots"));

    static final class Item {
        String path, name;
        /** 展示名（如应用名），null 时用 name */
        String label;
        /** 附加说明（如「应用已卸载」） */
        String note;
        boolean dir, hidden, checked;
        long size, mtime;

        String title() {
            return label != null ? label : name;
        }
    }

    static final class Result {
        final List<List<Item>> cats = new ArrayList<>();
        final List<List<Item>> junk = new ArrayList<>();
        int files;
        long totalSize;
        /** 是否成功扫描到了 Android/data 里的内容 */
        boolean privateScanned;
        long finishedAt;

        Result() {
            for (int i = 0; i < C_COUNT; i++) cats.add(new ArrayList<Item>());
            for (int i = 0; i < J_COUNT; i++) junk.add(new ArrayList<Item>());
        }

        long catSize(int c) {
            long s = 0;
            for (Item it : cats.get(c)) s += it.size;
            return s;
        }

        /** 从分类结果里移除已删除的路径（含其子路径） */
        void forget(Set<String> paths) {
            if (paths.isEmpty()) return;
            for (List<Item> l : cats) {
                List<Item> keep = new ArrayList<>(l.size());
                for (Item it : l) if (!deleted(it.path, paths)) keep.add(it);
                l.clear();
                l.addAll(keep);
            }
        }

        private static boolean deleted(String path, Set<String> paths) {
            for (String p = path; p.length() > 1; p = Util.parent(p)) if (paths.contains(p)) return true;
            return false;
        }
    }

    static final class Progress {
        volatile boolean cancelled;
        volatile String current = "";
        volatile int files;
    }

    /** 最近一次的扫描结果（只在内存里） */
    static volatile Result last;

    private final Context ctx;
    private final AndroidVfs vfs;
    private final Progress p;
    private final Result r = new Result();
    private final PackageManager pm;
    private final Set<String> installed = new HashSet<>();
    private final Map<String, String> labelCache = new HashMap<>();
    private boolean residueOk;

    StorageScanner(Context c, AndroidVfs vfs, Progress p) {
        this.ctx = c.getApplicationContext();
        this.vfs = vfs;
        this.p = p;
        this.pm = ctx.getPackageManager();
    }

    Result run() throws MainActivity.CancelledException {
        loadInstalled();
        r.privateScanned = Build.VERSION.SDK_INT < 30;   // Android 10 及以下可直接访问
        for (Vfs.Entry root : vfs.roots()) walk(root.path, root.path, 0, false, false);
        analyzeApks();
        r.finishedAt = System.currentTimeMillis();
        last = r;
        return r;
    }

    @SuppressWarnings("deprecation")
    private void loadInstalled() {
        try {
            int flags = Build.VERSION.SDK_INT >= 24 ? PackageManager.MATCH_UNINSTALLED_PACKAGES : PackageManager.GET_UNINSTALLED_PACKAGES;
            for (PackageInfo pi : pm.getInstalledPackages(flags)) installed.add(pi.packageName);
        } catch (Exception ignored) {
        }
        // 防御：如果系统没给「查询全部应用」的能力，拿到的列表会很短，此时不判断卸载残留，以免误删
        residueOk = installed.size() >= 30 && installed.contains(ctx.getPackageName());
    }

    private String appLabel(String pkg) {
        String l = labelCache.get(pkg);
        if (l != null) return l;
        try {
            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            CharSequence cs = ai.loadLabel(pm);
            l = cs == null ? pkg : cs.toString();
        } catch (Exception e) {
            l = pkg;
        }
        labelCache.put(pkg, l);
        return l;
    }

    private static String rel(String root, String path) {
        return path.length() <= root.length() ? "" : path.substring(root.length() + 1);
    }

    private static boolean looksLikePkg(String name) {
        return name.indexOf('.') > 0 && !name.startsWith(".") && name.matches("[A-Za-z0-9_.]+");
    }

    /**
     * 递归遍历目录。
     * @param junk 当前目录整体已被当作垃圾（如 cache、卸载残留），里面的文件只计大小，不再分类或单独列出
     * @return {子树总大小, 子树文件数}（读不到的目录按「有文件」处理，避免被当成空文件夹）
     */
    private long[] walk(String root, String dir, int depth, boolean hidden, boolean junk) throws MainActivity.CancelledException {
        if (p.cancelled) throw new MainActivity.CancelledException();
        if (depth > 48) return new long[]{0, 1};
        List<Vfs.Entry> kids;
        try {
            kids = vfs.list(dir);
        } catch (IOException e) {
            return new long[]{0, 1};
        }
        p.current = dir;
        String rel = rel(root, dir);
        boolean inAndroid = rel.equals("Android") || rel.startsWith("Android/");
        boolean isBase = rel.equals("Android/data") || rel.equals("Android/obb");
        boolean isAppDataDir = rel.startsWith("Android/data/") && rel.indexOf('/', "Android/data/".length()) < 0;
        long size = 0, files = 0;
        for (Vfs.Entry e : kids) {
            if (e.locked) {
                files++;
                continue;
            }
            boolean h = hidden || e.name.startsWith(".");
            if (e.dir) {
                int kind = -1;
                String label = null, note = null;
                if (!junk) {
                    if (isBase && residueOk && looksLikePkg(e.name) && !installed.contains(e.name)) {
                        kind = J_RESIDUE;
                        note = "应用已卸载 · " + rel(root, e.path);
                    } else if (isAppDataDir && e.name.equals("cache")) {
                        kind = J_CACHE;
                        String pkg = Util.nameOf(dir);
                        label = appLabel(pkg);
                        note = pkg;
                    } else if (!inAndroid && e.name.equals(".thumbnails")) {
                        kind = J_THUMB;
                        note = rel(root, e.path);
                    }
                }
                long[] s = walk(root, e.path, depth + 1, h, junk || kind >= 0);
                size += s[0];
                files += s[1];
                if (isBase && s[1] > 0 && vfs.isRestricted(e.path)) r.privateScanned = true;
                if (kind >= 0) {
                    if (s[0] > 0 || (kind == J_RESIDUE && s[1] > 0)) {
                        Item it = item(e, s[0]);
                        it.label = label;
                        it.note = note;
                        it.checked = true;
                        r.junk.get(kind).add(it);
                    }
                } else if (!junk && !inAndroid && !h && s[1] == 0 && !(depth == 0 && STD_DIRS.contains(e.name))) {
                    addEmpty(e, root);
                }
            } else {
                files++;
                size += e.size;
                p.files++;
                r.files++;
                r.totalSize += e.size;
                if (junk) continue;
                int c = category(e.name);
                if (c >= 0) addCat(c, e, h);
                if (e.size >= LARGE) addCat(C_LARGE, e, h);
                junkFile(e, root);
            }
        }
        return new long[]{size, files};
    }

    private static Item item(Vfs.Entry e, long size) {
        Item it = new Item();
        it.path = e.path;
        it.name = e.name;
        it.dir = e.dir;
        it.size = size;
        it.mtime = e.mtime;
        return it;
    }

    private void addCat(int c, Vfs.Entry e, boolean hidden) {
        Item it = item(e, e.size);
        it.hidden = hidden;
        r.cats.get(c).add(it);
    }

    /** 后序遍历：父目录也为空时，用父目录替换掉已记录的子目录，只列出最外层的空文件夹 */
    private void addEmpty(Vfs.Entry e, String root) {
        List<Item> l = r.junk.get(J_EMPTY);
        String prefix = e.path + "/";
        while (!l.isEmpty() && l.get(l.size() - 1).path.startsWith(prefix)) l.remove(l.size() - 1);
        Item it = item(e, 0);
        it.note = rel(root, e.path);
        it.checked = true;
        l.add(it);
    }

    private void junkFile(Vfs.Entry e, String root) {
        String n = e.name.toLowerCase(Locale.ROOT);
        String ext = Util.ext(n);
        int kind = -1;
        boolean checked = true;
        String note = rel(root, Util.parent(e.path));
        if (ext.equals("tmp") || ext.equals("temp") || n.startsWith("~$")) {
            kind = J_TEMP;
        } else if (ext.equals("crdownload") || ext.equals("part") || ext.equals("partial") || ext.equals("download")) {
            kind = J_TEMP;
            checked = false;
            note = "可能是未下载完的文件 · " + note;
        } else if (ext.equals("log") || ext.equals("xlog")) {
            kind = J_LOG;
        } else if (C_EXT[C_APK].contains(ext)) {
            kind = J_APK;
            checked = false;
        }
        if (kind < 0) return;
        Item it = item(e, e.size);
        it.checked = checked;
        it.note = note;
        r.junk.get(kind).add(it);
    }

    /** 解析安装包：已安装（同版本或更新版本）或已损坏的默认勾选 */
    @SuppressWarnings("deprecation")
    private void analyzeApks() {
        for (Item it : r.junk.get(J_APK)) {
            if (p.cancelled) return;
            String loc = it.note;
            if (!"apk".equals(Util.ext(it.name)) || vfs.isRestricted(it.path)) {
                it.note = "未解析 · " + loc;
                continue;
            }
            p.current = it.path;
            PackageInfo pi = null;
            try {
                pi = pm.getPackageArchiveInfo(it.path, 0);
            } catch (Throwable ignored) {
            }
            if (pi == null || pi.applicationInfo == null) {
                it.note = "安装包已损坏 · " + loc;
                it.checked = true;
                continue;
            }
            ApplicationInfo ai = pi.applicationInfo;
            ai.sourceDir = it.path;
            ai.publicSourceDir = it.path;
            try {
                CharSequence cs = ai.loadLabel(pm);
                if (cs != null) it.label = cs + (pi.versionName != null ? " " + pi.versionName : "");
            } catch (Throwable ignored) {
            }
            long apkVer = Build.VERSION.SDK_INT >= 28 ? pi.getLongVersionCode() : pi.versionCode;
            PackageInfo inst = null;
            try {
                inst = pm.getPackageInfo(pi.packageName, 0);
            } catch (Exception ignored) {
            }
            if (inst == null) {
                it.note = "未安装 · " + loc;
                continue;
            }
            long instVer = Build.VERSION.SDK_INT >= 28 ? inst.getLongVersionCode() : inst.versionCode;
            if (instVer >= apkVer) {
                it.checked = true;
                it.note = (instVer == apkVer ? "已安装" : "已安装更新的版本") + " · " + loc;
            } else {
                it.note = "比已安装的版本新 · " + loc;
            }
        }
    }
}
