package com.lanfile.manager.core;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Locale;

public final class Util {
    private Util() {}

    public static String json(String s) {
        if (s == null) return "null";
        StringBuilder b = new StringBuilder(s.length() + 2);
        b.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': b.append("\\\""); break;
                case '\\': b.append("\\\\"); break;
                case '\n': b.append("\\n"); break;
                case '\r': b.append("\\r"); break;
                case '\t': b.append("\\t"); break;
                default:
                    if (c < 0x20 || c == 0x2028 || c == 0x2029) {
                        b.append(String.format(Locale.US, "\\u%04x", (int) c));
                    } else b.append(c);
            }
        }
        b.append('"');
        return b.toString();
    }

    /** 去掉路径分隔符和非法字符，防止目录穿越 */
    public static String safeName(String name) {
        if (name == null) name = "";
        name = name.replace('\\', '/');
        int i = name.lastIndexOf('/');
        if (i >= 0) name = name.substring(i + 1);
        StringBuilder b = new StringBuilder();
        for (int k = 0; k < name.length(); k++) {
            char c = name.charAt(k);
            if (c < 0x20 || c == ':' || c == '*' || c == '?' || c == '"' || c == '<' || c == '>' || c == '|') b.append('_');
            else b.append(c);
        }
        String s = b.toString().trim();
        if (s.equals(".") || s.equals("..") || s.isEmpty()) s = "file";
        if (s.length() > 200) {
            String ext = ext(s);
            s = s.substring(0, 180) + (ext.isEmpty() ? "" : "." + ext);
        }
        return s;
    }

    public static String ext(String name) {
        int d = name.lastIndexOf('.');
        if (d <= 0 || d == name.length() - 1) return "";
        return name.substring(d + 1).toLowerCase(Locale.ROOT);
    }

    /** 规范化绝对路径：去掉多余斜杠、"."，拒绝 ".." */
    public static String normPath(String p) {
        if (p == null || p.isEmpty() || p.charAt(0) != '/') return null;
        String[] parts = p.split("/");
        StringBuilder b = new StringBuilder();
        for (String s : parts) {
            if (s.isEmpty() || s.equals(".")) continue;
            if (s.equals("..")) return null;
            b.append('/').append(s);
        }
        return b.length() == 0 ? "/" : b.toString();
    }

    public static String parent(String path) {
        int i = path.lastIndexOf('/');
        if (i <= 0) return "/";
        return path.substring(0, i);
    }

    public static String nameOf(String path) {
        int i = path.lastIndexOf('/');
        return i < 0 ? path : path.substring(i + 1);
    }

    public static boolean isUnder(String path, String root) {
        return path.equals(root) || path.startsWith(root.endsWith("/") ? root : root + "/");
    }

    /** 在 parent 下找一个不冲突的名字：a.txt -> a (1).txt */
    public static String uniqueName(Vfs vfs, String parent, String name) throws IOException {
        if (vfs.stat(join(parent, name)) == null) return name;
        String ext = ext(name);
        String base = ext.isEmpty() ? name : name.substring(0, name.length() - ext.length() - 1);
        for (int i = 1; i < 10000; i++) {
            String n = base + " (" + i + ")" + (ext.isEmpty() ? "" : "." + ext);
            if (vfs.stat(join(parent, n)) == null) return n;
        }
        return base + "_" + System.currentTimeMillis() + (ext.isEmpty() ? "" : "." + ext);
    }

    public static String join(String parent, String name) {
        return parent.endsWith("/") ? parent + name : parent + "/" + name;
    }

    public static long copy(InputStream in, OutputStream out, long limit) throws IOException {
        byte[] buf = new byte[64 * 1024];
        long total = 0;
        while (limit < 0 || total < limit) {
            int want = buf.length;
            if (limit >= 0) want = (int) Math.min(want, limit - total);
            int n = in.read(buf, 0, want);
            if (n < 0) break;
            out.write(buf, 0, n);
            total += n;
        }
        return total;
    }

    public static String formatSize(long s) {
        if (s < 1024) return s + " B";
        if (s < 1024L * 1024) return String.format(Locale.US, "%.1f KB", s / 1024.0);
        if (s < 1024L * 1024 * 1024) return String.format(Locale.US, "%.1f MB", s / 1048576.0);
        return String.format(Locale.US, "%.2f GB", s / 1073741824.0);
    }

    public static String mime(String name) {
        switch (ext(name)) {
            case "html": case "htm": return "text/html; charset=utf-8";
            case "txt": case "log": case "md": case "csv": case "json": case "xml": return "text/plain; charset=utf-8";
            case "js": return "application/javascript";
            case "css": return "text/css";
            case "jpg": case "jpeg": return "image/jpeg";
            case "png": return "image/png";
            case "gif": return "image/gif";
            case "webp": return "image/webp";
            case "bmp": return "image/bmp";
            case "svg": return "image/svg+xml";
            case "heic": return "image/heic";
            case "mp4": case "m4v": return "video/mp4";
            case "webm": return "video/webm";
            case "mkv": return "video/x-matroska";
            case "mov": return "video/quicktime";
            case "mp3": return "audio/mpeg";
            case "m4a": case "aac": return "audio/aac";
            case "flac": return "audio/flac";
            case "wav": return "audio/wav";
            case "ogg": return "audio/ogg";
            case "pdf": return "application/pdf";
            case "zip": return "application/zip";
            case "apk": return "application/vnd.android.package-archive";
            default: return "application/octet-stream";
        }
    }
}
