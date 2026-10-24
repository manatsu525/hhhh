package com.lanfile.manager.core;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 零依赖的小型 HTTP 服务器。电脑浏览器访问它即可与手机互发消息、互传文件。
 */
public final class WebServer {

    public interface Platform {
        byte[] asset(String name) throws IOException;
        Vfs vfs();
        /** 接收电脑文件的目录 */
        String receiveDir();
        /** 写入了新文件（用于通知媒体库） */
        void onFileWritten(String path);
        /** 记录/清除未完成的临时文件，崩溃后下次启动可清理，避免残留 */
        void trackPartial(String path, boolean active);
    }

    private static final String TMP_SUFFIX = ".lanpart";

    private final Hub hub;
    private final Platform platform;
    private ServerSocket serverSocket;
    private ExecutorService pool;
    private volatile boolean running;
    private final Map<String, int[]> failures = new ConcurrentHashMap<>();
    private final Map<String, Long> lockUntil = new ConcurrentHashMap<>();

    public WebServer(Hub hub, Platform platform) {
        this.hub = hub;
        this.platform = platform;
    }

    /** 从 preferredPort 开始尝试，返回实际端口 */
    public synchronized int start(int preferredPort) throws IOException {
        if (running) return serverSocket.getLocalPort();
        IOException last = null;
        for (int p = preferredPort; p < preferredPort + 20; p++) {
            try {
                ServerSocket ss = new ServerSocket();
                ss.setReuseAddress(true);
                ss.bind(new InetSocketAddress((InetAddress) null, p), 64);
                serverSocket = ss;
                break;
            } catch (IOException e) {
                last = e;
            }
        }
        if (serverSocket == null) throw last != null ? last : new IOException("无可用端口");
        running = true;
        pool = Executors.newCachedThreadPool(new ThreadFactory() {
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "web");
                t.setDaemon(true);
                return t;
            }
        });
        final ServerSocket ss = serverSocket;
        Thread acceptor = new Thread(new Runnable() {
            @Override
            public void run() {
                while (running) {
                    try {
                        final Socket s = ss.accept();
                        pool.execute(new Runnable() {
                            @Override
                            public void run() {
                                handle(s);
                            }
                        });
                    } catch (IOException e) {
                        if (!running) break;
                    }
                }
            }
        }, "web-accept");
        acceptor.setDaemon(true);
        acceptor.start();
        return serverSocket.getLocalPort();
    }

    public synchronized void stop() {
        running = false;
        try {
            if (serverSocket != null) serverSocket.close();
        } catch (IOException ignored) {}
        serverSocket = null;
        if (pool != null) pool.shutdownNow();
        pool = null;
    }

    // ===================== 请求解析 =====================

    static final class Req {
        String method, path, remote;
        Map<String, String> headers = new HashMap<>();
        Map<String, String> params = new HashMap<>();
        InputStream in;
        long contentLength = -1;

        String param(String k) { return params.get(k); }

        String cookie(String name) {
            String c = headers.get("cookie");
            if (c == null) return null;
            for (String part : c.split(";")) {
                String p = part.trim();
                int i = p.indexOf('=');
                if (i > 0 && p.substring(0, i).equals(name)) return p.substring(i + 1);
            }
            return null;
        }
    }

    private static String readLine(InputStream in, int max) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') break;
            if (c != '\r') b.write(c);
            if (b.size() > max) throw new IOException("header too long");
        }
        if (c == -1 && b.size() == 0) return null;
        return new String(b.toByteArray(), StandardCharsets.UTF_8);
    }

    private static String dec(String s) {
        try {
            return URLDecoder.decode(s, "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }

    private void handle(Socket s) {
        OutputStream out = null;
        try {
            s.setSoTimeout(60_000);
            s.setTcpNoDelay(true);
            InputStream in = new BufferedInputStream(s.getInputStream(), 64 * 1024);
            out = new BufferedOutputStream(s.getOutputStream(), 64 * 1024);
            String line = readLine(in, 8192);
            if (line == null) return;
            String[] parts = line.split(" ");
            if (parts.length < 2) return;
            Req r = new Req();
            r.method = parts[0];
            r.remote = s.getInetAddress().getHostAddress();
            String target = parts[1];
            int q = target.indexOf('?');
            r.path = dec(q >= 0 ? target.substring(0, q) : target);
            if (q >= 0) {
                for (String kv : target.substring(q + 1).split("&")) {
                    if (kv.isEmpty()) continue;
                    int e = kv.indexOf('=');
                    if (e < 0) r.params.put(dec(kv), "");
                    else r.params.put(dec(kv.substring(0, e)), dec(kv.substring(e + 1)));
                }
            }
            int headerCount = 0;
            while ((line = readLine(in, 8192)) != null && !line.isEmpty()) {
                int c = line.indexOf(':');
                if (c > 0) r.headers.put(line.substring(0, c).trim().toLowerCase(Locale.ROOT), line.substring(c + 1).trim());
                if (++headerCount > 100) throw new IOException("too many headers");
            }
            String cl = r.headers.get("content-length");
            if (cl != null) {
                try { r.contentLength = Long.parseLong(cl); } catch (NumberFormatException ignored) {}
            }
            r.in = in;
            route(r, out, s);
            out.flush();
        } catch (SocketException ignored) {
        } catch (Exception e) {
            try {
                if (out != null) sendText(out, 500, "text/plain; charset=utf-8", "服务器错误: " + e.getMessage());
            } catch (IOException ignored) {}
        } finally {
            try { if (out != null) out.flush(); } catch (IOException ignored) {}
            try { s.close(); } catch (IOException ignored) {}
        }
    }

    // ===================== 路由 =====================

    private void route(Req r, OutputStream out, Socket s) throws IOException {
        String p = r.path;
        if (p.equals("/") || p.equals("/index.html")) {
            sendBytes(out, 200, "text/html; charset=utf-8", platform.asset("index.html"), "no-cache");
            return;
        }
        if (p.equals("/favicon.ico")) {
            sendBytes(out, 204, "text/plain", new byte[0], null);
            return;
        }
        if (p.equals("/api/state")) {
            boolean auth = hub.isAuthorized(r.cookie("lt"));
            sendJson(out, 200, "{\"auth\":" + auth + ",\"pin\":" + hub.isPinRequired()
                    + ",\"name\":" + Util.json(hub.getDeviceName()) + "}");
            return;
        }
        if (p.equals("/api/login") && r.method.equals("POST")) {
            login(r, out);
            return;
        }
        if (!p.startsWith("/api/")) {
            sendText(out, 404, "text/plain; charset=utf-8", "Not Found");
            return;
        }
        if (!hub.isAuthorized(r.cookie("lt"))) {
            sendJson(out, 401, "{\"error\":\"unauthorized\"}");
            return;
        }
        switch (p) {
            case "/api/events": events(out, s); return;
            case "/api/text": text(r, out); return;
            case "/api/upload": upload(r, out); return;
            case "/api/folderdone": folderDone(r, out); return;
            case "/api/file": messageFile(r, out); return;
            case "/api/ls": ls(r, out); return;
            case "/api/dl": dl(r, out); return;
            case "/api/mkdir": mkdir(r, out); return;
            default: sendJson(out, 404, "{\"error\":\"not found\"}");
        }
    }

    private void login(Req r, OutputStream out) throws IOException {
        Long until = lockUntil.get(r.remote);
        if (until != null && until > System.currentTimeMillis()) {
            long sec = (until - System.currentTimeMillis()) / 1000 + 1;
            sendJson(out, 429, "{\"error\":" + Util.json("尝试次数过多，请 " + sec + " 秒后再试") + "}");
            return;
        }
        String code = new String(readBody(r, 1024), StandardCharsets.UTF_8).trim();
        String token = hub.login(code);
        if (token == null) {
            int[] f = failures.get(r.remote);
            if (f == null) { f = new int[1]; failures.put(r.remote, f); }
            if (++f[0] >= 5) {
                f[0] = 0;
                lockUntil.put(r.remote, System.currentTimeMillis() + 60_000);
            }
            sendJson(out, 403, "{\"error\":\"访问码错误\"}");
            return;
        }
        failures.remove(r.remote);
        String body = "{\"ok\":true}";
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        writeHead(out, 200, "application/json; charset=utf-8", b.length,
                "Set-Cookie: lt=" + token + "; Path=/; HttpOnly; SameSite=Strict; Max-Age=2592000\r\n");
        out.write(b);
    }

    private void events(OutputStream out, Socket s) throws IOException {
        s.setSoTimeout(0);
        String head = "HTTP/1.1 200 OK\r\nContent-Type: text/event-stream; charset=utf-8\r\n"
                + "Cache-Control: no-cache\r\nConnection: keep-alive\r\nX-Accel-Buffering: no\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.UTF_8));
        out.write("retry: 2000\n\n".getBytes(StandardCharsets.UTF_8));
        out.flush();
        Hub.SseClient c = hub.addClient(out);
        try {
            if (!c.send(hub.initJson())) return;
            while (running && !c.closed) {
                try {
                    Thread.sleep(15_000);
                } catch (InterruptedException e) {
                    break;
                }
                if (!c.ping()) break;
            }
        } finally {
            hub.removeClient(c);
            c.closed = true;
        }
    }

    private void text(Req r, OutputStream out) throws IOException {
        String t = new String(readBody(r, 1024 * 1024), StandardCharsets.UTF_8);
        if (t.trim().isEmpty()) {
            sendJson(out, 400, "{\"error\":\"empty\"}");
            return;
        }
        Hub.Message m = hub.addText(true, t);
        sendJson(out, 200, "{\"ok\":true,\"id\":" + m.id + "}");
    }

    /** 解析允许访问的目录（必须在存储卷之内） */
    private String resolve(String path) {
        String n = Util.normPath(path);
        if (n == null) return null;
        for (Vfs.Entry root : platform.vfs().roots()) {
            if (Util.isUnder(n, root.path)) return n;
        }
        String recv = platform.receiveDir();
        if (recv != null && Util.isUnder(n, recv)) return n;
        return null;
    }

    private void upload(Req r, OutputStream out) throws IOException {
        if (!"POST".equals(r.method) && !"PUT".equals(r.method)) {
            sendJson(out, 405, "{\"error\":\"method\"}");
            return;
        }
        if (r.contentLength < 0) {
            sendJson(out, 411, "{\"error\":\"需要 Content-Length\"}");
            return;
        }
        Vfs vfs = platform.vfs();
        String dirParam = r.param("dir");
        boolean toChat = dirParam == null || dirParam.isEmpty();
        String dir;
        if (toChat) {
            dir = platform.receiveDir();
        } else {
            if (!hub.isAllowBrowse()) { sendJson(out, 403, "{\"error\":\"手机端未开启浏览权限\"}"); return; }
            dir = resolve(dirParam);
            if (dir == null) { sendJson(out, 403, "{\"error\":\"目录不允许访问\"}"); return; }
        }
        String rel = r.param("rel");
        if (rel != null && !rel.isEmpty()) {
            String[] segs = rel.replace('\\', '/').split("/");
            for (int i = 0; i < segs.length - 1; i++) {
                String seg = Util.safeName(segs[i]);
                String child = Util.join(dir, seg);
                Vfs.Entry e = vfs.stat(child);
                if (e == null) dir = vfs.mkdir(dir, seg);
                else if (e.dir) dir = child;
                else dir = vfs.mkdir(dir, Util.uniqueName(vfs, dir, seg));
            }
        }
        String name = Util.safeName(r.param("name"));
        String tmpName = Util.uniqueName(vfs, dir, name + TMP_SUFFIX);
        String tmpPath = Util.join(dir, tmpName);
        platform.trackPartial(tmpPath, true);
        long got;
        boolean ok = false;
        try {
            OutputStream fo = vfs.create(dir, tmpName);
            try {
                got = Util.copy(r.in, fo, r.contentLength);
            } finally {
                fo.close();
            }
            ok = got == r.contentLength;
        } finally {
            if (!ok) {
                try { vfs.delete(tmpPath); } catch (IOException ignored) {}
                platform.trackPartial(tmpPath, false);
            }
        }
        if (!ok) {
            sendJson(out, 400, "{\"error\":\"传输中断\"}");
            return;
        }
        String finalName = Util.uniqueName(vfs, dir, name);
        String finalPath;
        try {
            finalPath = vfs.rename(tmpPath, finalName);
        } finally {
            platform.trackPartial(tmpPath, false);
        }
        platform.onFileWritten(finalPath);
        if (toChat && (rel == null || rel.isEmpty())) {
            hub.addFile(true, finalName, got, finalPath, false);
        }
        sendJson(out, 200, "{\"ok\":true,\"name\":" + Util.json(finalName) + "}");
    }

    /** 文件夹上传完成后，在聊天记录里生成一条"文件夹"消息 */
    private void folderDone(Req r, OutputStream out) throws IOException {
        String top = Util.safeName(r.param("name"));
        String path = Util.join(platform.receiveDir(), top);
        Vfs.Entry e = platform.vfs().stat(path);
        if (e == null || !e.dir) {
            sendJson(out, 404, "{\"error\":\"not found\"}");
            return;
        }
        long size = 0;
        try { size = Long.parseLong(r.param("size")); } catch (Exception ignored) {}
        hub.addFile(true, top, size, path, true);
        sendJson(out, 200, "{\"ok\":true}");
    }

    private void messageFile(Req r, OutputStream out) throws IOException {
        long id;
        try {
            id = Long.parseLong(r.param("id"));
        } catch (Exception e) {
            sendJson(out, 400, "{\"error\":\"id\"}");
            return;
        }
        Hub.Message m = hub.find(id);
        if (m == null || m.path == null) {
            sendJson(out, 404, "{\"error\":\"消息不存在\"}");
            return;
        }
        serve(r, out, m.path);
    }

    private void dl(Req r, OutputStream out) throws IOException {
        if (!hub.isAllowBrowse()) { sendJson(out, 403, "{\"error\":\"手机端未开启浏览权限\"}"); return; }
        String path = resolve(r.param("path"));
        if (path == null) { sendJson(out, 403, "{\"error\":\"不允许访问\"}"); return; }
        serve(r, out, path);
    }

    private void serve(Req r, OutputStream out, String path) throws IOException {
        Vfs vfs = platform.vfs();
        Vfs.Entry e;
        try {
            e = vfs.stat(path);
        } catch (Vfs.NeedAccessException ex) {
            sendJson(out, 403, "{\"error\":" + Util.json("需要先在手机上授权访问此目录") + "}");
            return;
        }
        if (e == null) {
            sendJson(out, 404, "{\"error\":\"文件已不存在\"}");
            return;
        }
        if (e.dir) {
            serveZip(out, e);
            return;
        }
        boolean inline = r.param("inline") != null;
        long total = e.size;
        long start = 0, end = total - 1;
        boolean partial = false;
        String range = r.headers.get("range");
        if (range != null && range.startsWith("bytes=") && !range.contains(",") && total > 0) {
            String spec = range.substring(6).trim();
            int dash = spec.indexOf('-');
            try {
                if (dash == 0) {
                    long suffix = Long.parseLong(spec.substring(1));
                    start = Math.max(0, total - suffix);
                } else {
                    start = Long.parseLong(spec.substring(0, dash));
                    String ee = spec.substring(dash + 1);
                    if (!ee.isEmpty()) end = Math.min(total - 1, Long.parseLong(ee));
                }
                if (start > end || start >= total) {
                    writeHead(out, 416, "text/plain", 0, "Content-Range: bytes */" + total + "\r\n");
                    return;
                }
                partial = true;
            } catch (NumberFormatException ignored) {
                start = 0;
                end = total - 1;
            }
        }
        long len = total == 0 ? 0 : end - start + 1;
        StringBuilder extra = new StringBuilder();
        extra.append("Accept-Ranges: bytes\r\n");
        extra.append("Content-Disposition: ").append(inline ? "inline" : "attachment")
                .append("; filename*=UTF-8''").append(encodeRfc5987(e.name)).append("\r\n");
        if (partial) extra.append("Content-Range: bytes ").append(start).append('-').append(end).append('/').append(total).append("\r\n");
        if (inline) extra.append("Cache-Control: private, max-age=3600\r\n");
        InputStream in;
        try {
            in = vfs.openRead(path, start);
        } catch (FileNotFoundException ex) {
            sendJson(out, 404, "{\"error\":\"无法打开文件\"}");
            return;
        }
        try {
            writeHead(out, partial ? 206 : 200, Util.mime(e.name), len, extra.toString());
            Util.copy(in, out, len);
        } finally {
            in.close();
        }
    }

    /** 文件夹实时打包为 zip 流式下载，不在手机上生成临时文件 */
    private void serveZip(OutputStream out, Vfs.Entry dir) throws IOException {
        String extra = "Content-Disposition: attachment; filename*=UTF-8''" + encodeRfc5987(dir.name + ".zip") + "\r\n";
        String head = "HTTP/1.1 200 OK\r\nContent-Type: application/zip\r\nConnection: close\r\n" + extra + "\r\n";
        out.write(head.getBytes(StandardCharsets.UTF_8));
        ZipOutputStream zos = new ZipOutputStream(out, StandardCharsets.UTF_8);
        zos.setLevel(Deflater.BEST_SPEED);
        zipDir(zos, dir.path, dir.name + "/");
        zos.finish();
        zos.flush();
    }

    private void zipDir(ZipOutputStream zos, String path, String prefix) throws IOException {
        Vfs vfs = platform.vfs();
        List<Vfs.Entry> kids;
        try {
            kids = vfs.list(path);
        } catch (IOException e) {
            return;
        }
        if (kids.isEmpty()) {
            zos.putNextEntry(new ZipEntry(prefix));
            zos.closeEntry();
            return;
        }
        for (Vfs.Entry k : kids) {
            if (k.dir) {
                zipDir(zos, k.path, prefix + k.name + "/");
            } else {
                ZipEntry ze = new ZipEntry(prefix + k.name);
                if (k.mtime > 0) ze.setTime(k.mtime);
                zos.putNextEntry(ze);
                try (InputStream in = vfs.openRead(k.path, 0)) {
                    Util.copy(in, zos, -1);
                } catch (IOException ignored) {
                }
                zos.closeEntry();
            }
        }
    }

    private void ls(Req r, OutputStream out) throws IOException {
        if (!hub.isAllowBrowse()) { sendJson(out, 403, "{\"error\":\"手机端未开启浏览权限\"}"); return; }
        Vfs vfs = platform.vfs();
        String path = r.param("path");
        StringBuilder b = new StringBuilder();
        if (path == null || path.isEmpty()) {
            b.append("{\"path\":\"\",\"items\":[");
            List<Vfs.Entry> roots = vfs.roots();
            for (int i = 0; i < roots.size(); i++) {
                if (i > 0) b.append(',');
                Vfs.Entry e = roots.get(i);
                if (e.label == null) e.label = e.name;
                appendEntry(b, e);
            }
            sendJson(out, 200, b.append("]}").toString());
            return;
        }
        String p = resolve(path);
        if (p == null) { sendJson(out, 403, "{\"error\":\"不允许访问\"}"); return; }
        List<Vfs.Entry> list;
        try {
            list = vfs.list(p);
        } catch (Vfs.NeedAccessException e) {
            sendJson(out, 403, "{\"error\":" + Util.json("此目录需要先在手机上授权（在手机端文件管理器中点开该目录即可授权）") + "}");
            return;
        } catch (IOException e) {
            sendJson(out, 404, "{\"error\":" + Util.json(e.getMessage()) + "}");
            return;
        }
        sortEntries(list);
        String parent = null;
        for (Vfs.Entry root : vfs.roots()) {
            if (Util.isUnder(p, root.path) && !p.equals(root.path)) parent = Util.parent(p);
        }
        b.append("{\"path\":").append(Util.json(p)).append(",\"parent\":").append(Util.json(parent == null ? "" : parent))
                .append(",\"items\":[");
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) b.append(',');
            appendEntry(b, list.get(i));
        }
        sendJson(out, 200, b.append("]}").toString());
    }

    private void mkdir(Req r, OutputStream out) throws IOException {
        if (!hub.isAllowBrowse()) { sendJson(out, 403, "{\"error\":\"手机端未开启浏览权限\"}"); return; }
        String dir = resolve(r.param("dir"));
        if (dir == null) { sendJson(out, 403, "{\"error\":\"不允许访问\"}"); return; }
        String name = Util.safeName(r.param("name"));
        try {
            Vfs vfs = platform.vfs();
            vfs.mkdir(dir, Util.uniqueName(vfs, dir, name));
            sendJson(out, 200, "{\"ok\":true}");
        } catch (IOException e) {
            sendJson(out, 500, "{\"error\":" + Util.json(e.getMessage()) + "}");
        }
    }

    public static void sortEntries(List<Vfs.Entry> list) {
        final Collator col = Collator.getInstance(Locale.CHINA);
        Collections.sort(list, new Comparator<Vfs.Entry>() {
            @Override
            public int compare(Vfs.Entry a, Vfs.Entry b) {
                if (a.dir != b.dir) return a.dir ? -1 : 1;
                String an = a.label != null ? a.label : a.name;
                String bn = b.label != null ? b.label : b.name;
                return col.compare(an.toLowerCase(Locale.ROOT), bn.toLowerCase(Locale.ROOT));
            }
        });
    }

    private static void appendEntry(StringBuilder b, Vfs.Entry e) {
        b.append("{\"n\":").append(Util.json(e.name)).append(",\"p\":").append(Util.json(e.path))
                .append(",\"d\":").append(e.dir).append(",\"s\":").append(e.size).append(",\"t\":").append(e.mtime);
        if (e.label != null) b.append(",\"l\":").append(Util.json(e.label));
        if (e.locked) b.append(",\"lk\":true");
        b.append('}');
    }

    // ===================== 输出 =====================

    private static byte[] readBody(Req r, int max) throws IOException {
        if (r.contentLength <= 0) return new byte[0];
        if (r.contentLength > max) throw new IOException("body too large");
        byte[] b = new byte[(int) r.contentLength];
        int off = 0;
        while (off < b.length) {
            int n = r.in.read(b, off, b.length - off);
            if (n < 0) break;
            off += n;
        }
        return b;
    }

    static String encodeRfc5987(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8").replace("+", "%20");
        } catch (Exception e) {
            return "file";
        }
    }

    private static String reason(int code) {
        switch (code) {
            case 200: return "OK";
            case 204: return "No Content";
            case 206: return "Partial Content";
            case 400: return "Bad Request";
            case 401: return "Unauthorized";
            case 403: return "Forbidden";
            case 404: return "Not Found";
            case 405: return "Method Not Allowed";
            case 411: return "Length Required";
            case 416: return "Range Not Satisfiable";
            case 429: return "Too Many Requests";
            default: return "Error";
        }
    }

    private static void writeHead(OutputStream out, int code, String type, long len, String extra) throws IOException {
        StringBuilder h = new StringBuilder();
        h.append("HTTP/1.1 ").append(code).append(' ').append(reason(code)).append("\r\n");
        h.append("Content-Type: ").append(type).append("\r\n");
        h.append("Content-Length: ").append(len).append("\r\n");
        h.append("Connection: close\r\n");
        h.append("X-Content-Type-Options: nosniff\r\n");
        if (extra != null) h.append(extra);
        h.append("\r\n");
        out.write(h.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static void sendBytes(OutputStream out, int code, String type, byte[] body, String cache) throws IOException {
        writeHead(out, code, type, body.length, cache == null ? null : "Cache-Control: " + cache + "\r\n");
        out.write(body);
    }

    private static void sendText(OutputStream out, int code, String type, String body) throws IOException {
        sendBytes(out, code, type, body.getBytes(StandardCharsets.UTF_8), null);
    }

    private static void sendJson(OutputStream out, int code, String json) throws IOException {
        sendBytes(out, code, "application/json; charset=utf-8", json.getBytes(StandardCharsets.UTF_8), "no-store");
    }

    /** 本机所有局域网 IPv4 地址 */
    public static List<String> lanAddresses() {
        List<String> out = new ArrayList<>();
        try {
            java.util.Enumeration<java.net.NetworkInterface> ifs = java.net.NetworkInterface.getNetworkInterfaces();
            if (ifs == null) return out;
            for (java.net.NetworkInterface ni : Collections.list(ifs)) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                String n = ni.getName().toLowerCase(Locale.ROOT);
                if (n.startsWith("rmnet") || n.startsWith("ccmni") || n.startsWith("dummy") || n.startsWith("tun")) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof java.net.Inet4Address && !a.isLoopbackAddress()) {
                        String ip = a.getHostAddress();
                        if (n.startsWith("wlan") || n.startsWith("ap") || n.startsWith("swlan")) out.add(0, ip);
                        else if (a.isSiteLocalAddress()) out.add(ip);
                    }
                }
            }
        } catch (Exception ignored) {}
        return out;
    }
}
