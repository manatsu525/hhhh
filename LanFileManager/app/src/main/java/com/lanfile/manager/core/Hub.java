package com.lanfile.manager.core;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 互传会话中心：消息记录、已登录浏览器、SSE 推送。
 * 全部只保存在内存里，进程结束即消失，不写任何文件。
 */
public final class Hub {

    public static final class Message {
        public long id;
        public boolean fromPc;
        /** text / file / folder */
        public String type;
        public String text;
        public String name;
        public long size;
        public long time;
        /** 文件在手机上的路径 */
        public String path;

        public String toJson() {
            return "{\"id\":" + id + ",\"from\":" + Util.json(fromPc ? "pc" : "phone")
                    + ",\"type\":" + Util.json(type) + ",\"text\":" + Util.json(text)
                    + ",\"name\":" + Util.json(name) + ",\"size\":" + size + ",\"time\":" + time + "}";
        }
    }

    public interface Listener {
        void onHubChanged();
        /** 来自电脑的新消息 */
        void onIncoming(Message m);
    }

    private static final Hub INSTANCE = new Hub();

    public static Hub get() {
        return INSTANCE;
    }

    private final List<Message> messages = new ArrayList<>();
    private long nextId = 1;
    private final Set<String> tokens = ConcurrentHashMap.newKeySet();
    private final List<SseClient> clients = new CopyOnWriteArrayList<>();
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private final SecureRandom rnd = new SecureRandom();

    private volatile String pin = newPin();
    private volatile boolean pinRequired = true;
    private volatile boolean allowBrowse = true;
    private volatile boolean running;
    private volatile int port;
    private volatile List<String> urls = new ArrayList<>();
    private volatile String deviceName = "Android";

    private Hub() {}

    // ---------- 状态 ----------

    public String newPin() {
        return String.format(Locale.US, "%04d", (rnd == null ? new SecureRandom() : rnd).nextInt(10000));
    }

    public void regeneratePin() {
        pin = newPin();
        tokens.clear();
        for (SseClient c : clients) c.close();
        changed();
    }

    public String getPin() { return pin; }
    public boolean isPinRequired() { return pinRequired; }
    public void setPinRequired(boolean b) { pinRequired = b; changed(); }
    public boolean isAllowBrowse() { return allowBrowse; }
    public void setAllowBrowse(boolean b) { allowBrowse = b; changed(); }
    public String getDeviceName() { return deviceName; }
    public void setDeviceName(String n) { deviceName = n; }
    public boolean isRunning() { return running; }
    public int getPort() { return port; }
    public List<String> getUrls() { return urls; }
    public int clientCount() { return clients.size(); }

    public void setRunning(boolean r, int port, List<String> urls) {
        this.running = r;
        this.port = port;
        this.urls = urls == null ? new ArrayList<String>() : urls;
        if (!r) {
            for (SseClient c : clients) c.close();
            clients.clear();
        }
        changed();
    }

    public void setUrls(List<String> urls) {
        this.urls = urls;
        changed();
    }

    // ---------- 认证 ----------

    public String login(String code) {
        if (pinRequired && (code == null || !code.trim().equals(pin))) return null;
        byte[] b = new byte[18];
        rnd.nextBytes(b);
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format(Locale.US, "%02x", x));
        String t = sb.toString();
        tokens.add(t);
        return t;
    }

    public boolean isAuthorized(String token) {
        if (!pinRequired) return true;
        return token != null && tokens.contains(token);
    }

    // ---------- 消息 ----------

    public Message addText(boolean fromPc, String text) {
        Message m = new Message();
        m.fromPc = fromPc;
        m.type = "text";
        m.text = text;
        return add(m);
    }

    public Message addFile(boolean fromPc, String name, long size, String path, boolean folder) {
        Message m = new Message();
        m.fromPc = fromPc;
        m.type = folder ? "folder" : "file";
        m.name = name;
        m.size = size;
        m.path = path;
        return add(m);
    }

    private Message add(Message m) {
        synchronized (this) {
            m.id = nextId++;
            m.time = System.currentTimeMillis();
            messages.add(m);
        }
        broadcast("{\"t\":\"msg\",\"m\":" + m.toJson() + "}");
        changed();
        if (m.fromPc) for (Listener l : listeners) l.onIncoming(m);
        return m;
    }

    public synchronized List<Message> snapshot() {
        return new ArrayList<>(messages);
    }

    public synchronized Message find(long id) {
        for (Message m : messages) if (m.id == id) return m;
        return null;
    }

    public void clear() {
        synchronized (this) {
            messages.clear();
        }
        broadcast("{\"t\":\"clear\"}");
        changed();
    }

    public String initJson() {
        StringBuilder b = new StringBuilder("{\"t\":\"init\",\"name\":").append(Util.json(deviceName))
                .append(",\"browse\":").append(allowBrowse).append(",\"list\":[");
        List<Message> list = snapshot();
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) b.append(',');
            b.append(list.get(i).toJson());
        }
        return b.append("]}").toString();
    }

    // ---------- 监听 ----------

    public void addListener(Listener l) { listeners.add(l); }
    public void removeListener(Listener l) { listeners.remove(l); }

    private void changed() {
        for (Listener l : listeners) l.onHubChanged();
    }

    // ---------- SSE ----------

    public static final class SseClient {
        final OutputStream out;
        volatile boolean closed;

        SseClient(OutputStream out) { this.out = out; }

        synchronized boolean send(String data) {
            if (closed) return false;
            try {
                out.write(("data: " + data + "\n\n").getBytes(StandardCharsets.UTF_8));
                out.flush();
                return true;
            } catch (IOException e) {
                closed = true;
                return false;
            }
        }

        synchronized boolean ping() {
            if (closed) return false;
            try {
                out.write(": ping\n\n".getBytes(StandardCharsets.UTF_8));
                out.flush();
                return true;
            } catch (IOException e) {
                closed = true;
                return false;
            }
        }

        void close() {
            closed = true;
            try { out.close(); } catch (IOException ignored) {}
        }
    }

    SseClient addClient(OutputStream out) {
        SseClient c = new SseClient(out);
        clients.add(c);
        changed();
        return c;
    }

    void removeClient(SseClient c) {
        if (clients.remove(c)) changed();
    }

    void broadcast(String data) {
        Iterator<SseClient> it = clients.iterator();
        while (it.hasNext()) {
            SseClient c = it.next();
            if (!c.send(data)) removeClient(c);
        }
    }
}
