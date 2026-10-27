package com.lanfile.manager.archive;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** 按文件名判断压缩格式、去掉压缩扩展名 */
public final class Formats {
    private Formats() {}

    private static final Set<String> EXT = new HashSet<>(Arrays.asList(
            "zip", "zipx", "jar", "7z", "rar", "tar", "gz", "tgz", "gzip", "bz2", "tbz", "tbz2", "bzip2", "xz", "txz",
            "lzma", "tlz", "z", "taz", "cab", "iso", "arj", "lzh", "lha", "cpio", "rpm", "deb", "wim", "swm", "esd",
            "dmg", "xar", "pkg", "udf", "vhd", "chm", "msi", "squashfs", "001", "ar", "a", "obb"));

    /** 双扩展名，从长到短 */
    private static final String[] COMPOUND = {".tar.gz", ".tar.bz2", ".tar.xz", ".tar.lzma", ".tar.z", ".tar.zst"};
    private static final Pattern VOLUME = Pattern.compile("(?i)(\\.part\\d+)?\\.rar$|\\.(7z|zip|rar)?\\.?0*\\d{3}$");

    /** 点开时用内置压缩包查看器打开的文件 */
    public static boolean isArchive(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        int d = n.lastIndexOf('.');
        if (d <= 0) return false;
        String ext = n.substring(d + 1);
        // obb 和 a 太常见于非压缩用途，只在「打开方式」里提供
        if (ext.equals("obb") || ext.equals("a")) return false;
        return EXT.contains(ext);
    }

    /** 「打开方式 → 压缩包」可用（包括 apk、obb 等本质是 zip 的文件） */
    public static boolean canTryOpen(String name) {
        String ext = ext(name);
        return EXT.contains(ext) || ext.equals("apk") || ext.equals("apks") || ext.equals("xapk") || ext.equals("aar")
                || ext.equals("epub") || ext.equals("docx") || ext.equals("xlsx") || ext.equals("pptx")
                || ext.equals("odt") || ext.equals("ods") || ext.equals("odp") || ext.equals("war") || ext.equals("cbz")
                || ext.equals("cbr") || ext.equals("cb7");
    }

    /** 解压时默认的文件夹名：a.tar.gz -> a，a.part1.rar -> a，a.7z.001 -> a */
    public static String baseName(String name) {
        String n = name;
        String low = n.toLowerCase(Locale.ROOT);
        for (String c : COMPOUND) {
            if (low.endsWith(c) && low.length() > c.length()) return n.substring(0, n.length() - c.length());
        }
        java.util.regex.Matcher m = VOLUME.matcher(n);
        if (m.find() && m.start() > 0) {
            String b = n.substring(0, m.start());
            if (!b.isEmpty()) return b;
        }
        int d = n.lastIndexOf('.');
        return d > 0 ? n.substring(0, d) : n + "_files";
    }

    /** 单文件压缩（a.txt.gz）解出来的文件名 */
    public static String singleName(String name) {
        String low = name.toLowerCase(Locale.ROOT);
        if (low.endsWith(".tgz") || low.endsWith(".taz") || low.endsWith(".tbz") || low.endsWith(".tbz2")
                || low.endsWith(".txz") || low.endsWith(".tlz")) {
            return name.substring(0, name.lastIndexOf('.')) + ".tar";
        }
        int d = name.lastIndexOf('.');
        return d > 0 ? name.substring(0, d) : name + ".out";
    }

    public static String ext(String name) {
        int d = name.lastIndexOf('.');
        if (d <= 0 || d == name.length() - 1) return "";
        return name.substring(d + 1).toLowerCase(Locale.ROOT);
    }

    /** 规范化压缩包内路径：统一用 /，去掉空片段、.、..、盘符和非法字符 */
    public static String cleanPath(String p, boolean backslashIsSeparator) {
        if (p == null) return "";
        if (backslashIsSeparator) p = p.replace('\\', '/');
        StringBuilder b = new StringBuilder();
        for (String s : p.split("/")) {
            if (s.isEmpty() || s.equals(".") || s.equals("..")) continue;
            if (b.length() == 0 && s.length() == 2 && s.charAt(1) == ':') continue;   // C:
            if (b.length() > 0) b.append('/');
            b.append(com.lanfile.manager.core.Util.safeName(s));
        }
        return b.toString();
    }
}
