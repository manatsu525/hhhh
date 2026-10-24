package com.lanfile.manager;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.HashSet;
import java.util.Set;

/** 设置保存在应用内部存储（/data/data/包名/shared_prefs），卸载即删除 */
public final class Prefs {
    private static SharedPreferences sp;

    static void init(Context c) {
        sp = c.getSharedPreferences("settings", Context.MODE_PRIVATE);
    }

    public static boolean showHidden() { return sp.getBoolean("hidden", false); }
    public static void setShowHidden(boolean b) { sp.edit().putBoolean("hidden", b).apply(); }

    /** 0 名称 1 时间 2 大小 3 类型 */
    public static int sortBy() { return sp.getInt("sort", 0); }
    public static boolean sortDesc() { return sp.getBoolean("desc", false); }
    public static void setSort(int by, boolean desc) { sp.edit().putInt("sort", by).putBoolean("desc", desc).apply(); }

    /** 文件分类列表：0 时间新→旧 1 时间旧→新 2 大小大→小 3 大小小→大 4 名称 */
    public static int catSort() { return sp.getInt("catSort", 0); }
    public static void setCatSort(int by) { sp.edit().putInt("catSort", by).apply(); }

    /** true: 收到的文件存到应用专属目录（卸载时被系统一起删除） */
    public static boolean recvPrivate() { return sp.getBoolean("recvPrivate", false); }
    public static void setRecvPrivate(boolean b) { sp.edit().putBoolean("recvPrivate", b).apply(); }

    public static boolean allowBrowse() { return sp.getBoolean("browse", true); }
    public static void setAllowBrowse(boolean b) { sp.edit().putBoolean("browse", b).apply(); }

    public static boolean pinRequired() { return sp.getBoolean("pin", true); }
    public static void setPinRequired(boolean b) { sp.edit().putBoolean("pin", b).apply(); }

    // 文本编辑器
    public static boolean edWrap() { return sp.getBoolean("edWrap", true); }
    public static void setEdWrap(boolean b) { sp.edit().putBoolean("edWrap", b).apply(); }
    public static boolean edLines() { return sp.getBoolean("edLines", true); }
    public static void setEdLines(boolean b) { sp.edit().putBoolean("edLines", b).apply(); }
    public static float edTextSize() { return sp.getFloat("edSize", 15f); }
    public static void setEdTextSize(float f) { sp.edit().putFloat("edSize", f).apply(); }

    public static synchronized Set<String> partials() {
        return new HashSet<>(sp.getStringSet("partials", new HashSet<String>()));
    }

    public static synchronized void trackPartial(String path, boolean active) {
        Set<String> s = partials();
        if (active) s.add(path); else s.remove(path);
        sp.edit().putStringSet("partials", s).commit();
    }
}
