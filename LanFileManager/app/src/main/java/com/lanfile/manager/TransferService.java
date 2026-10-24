package com.lanfile.manager;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.MediaScannerConnection;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkRequest;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Environment;
import android.os.IBinder;

import com.lanfile.manager.core.Hub;
import com.lanfile.manager.core.Vfs;
import com.lanfile.manager.core.WebServer;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;

/** 前台服务：运行局域网 HTTP 服务器 */
public class TransferService extends Service {

    public static final String ACTION_STOP = "com.lanfile.manager.STOP";
    private static final String CH_RUN = "run";
    private static final String CH_MSG = "msg";
    private static final int NID_RUN = 1;
    private static final int NID_MSG = 2;
    public static final int PORT = 8899;

    private static volatile boolean running;
    private WebServer server;
    private WifiManager.WifiLock wifiLock;
    private ConnectivityManager.NetworkCallback netCb;
    private Hub.Listener hubListener;
    private byte[] indexHtml;

    public static boolean isRunning() {
        return running;
    }

    public static void start(Context c) {
        Intent i = new Intent(c, TransferService.class);
        if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i);
        else c.startService(i);
    }

    public static void stop(Context c) {
        c.stopService(new Intent(c, TransferService.class));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (Build.VERSION.SDK_INT >= 26 && nm != null) {
            nm.createNotificationChannel(new NotificationChannel(CH_RUN, "互传服务运行状态", NotificationManager.IMPORTANCE_LOW));
            nm.createNotificationChannel(new NotificationChannel(CH_MSG, "电脑发来的消息", NotificationManager.IMPORTANCE_HIGH));
        }
        Hub hub = Hub.get();
        hub.setDeviceName(Build.MANUFACTURER.substring(0, 1).toUpperCase() + Build.MANUFACTURER.substring(1) + " " + Build.MODEL);
        hub.setAllowBrowse(Prefs.allowBrowse());
        hub.setPinRequired(Prefs.pinRequired());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        startAsForeground("正在启动…");
        if (server == null) {
            server = new WebServer(Hub.get(), platform);
            try {
                int port = server.start(PORT);
                running = true;
                Hub.get().setRunning(true, port, urls(port));
                acquireLocks();
                watchNetwork();
                hubListener = new Hub.Listener() {
                    @Override
                    public void onHubChanged() {}

                    @Override
                    public void onIncoming(Hub.Message m) {
                        notifyIncoming(m);
                    }
                };
                Hub.get().addListener(hubListener);
                updateNotification();
            } catch (IOException e) {
                server = null;
                Hub.get().setRunning(false, 0, null);
                stopSelf();
            }
        }
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        if (server != null) server.stop();
        server = null;
        if (hubListener != null) Hub.get().removeListener(hubListener);
        Hub.get().setRunning(false, 0, null);
        if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
        if (netCb != null) {
            try {
                ((ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE)).unregisterNetworkCallback(netCb);
            } catch (Exception ignored) {}
        }
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.cancel(NID_MSG);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private List<String> urls(int port) {
        List<String> ips = WebServer.lanAddresses();
        for (int i = 0; i < ips.size(); i++) ips.set(i, "http://" + ips.get(i) + (port == 80 ? "" : ":" + port));
        return ips;
    }

    public static void refreshUrls() {
        Hub h = Hub.get();
        if (!h.isRunning()) return;
        List<String> ips = WebServer.lanAddresses();
        for (int i = 0; i < ips.size(); i++) ips.set(i, "http://" + ips.get(i) + ":" + h.getPort());
        h.setUrls(ips);
    }

    private void acquireLocks() {
        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
        if (wm != null) {
            int mode = Build.VERSION.SDK_INT >= 29 ? WifiManager.WIFI_MODE_FULL_LOW_LATENCY : WifiManager.WIFI_MODE_FULL_HIGH_PERF;
            wifiLock = wm.createWifiLock(mode, "lanfile:transfer");
            wifiLock.setReferenceCounted(false);
            wifiLock.acquire();
        }
    }

    private void watchNetwork() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        if (cm == null) return;
        netCb = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network network) { changed(); }

            @Override
            public void onLost(Network network) { changed(); }

            void changed() {
                refreshUrls();
                updateNotification();
            }
        };
        try {
            cm.registerNetworkCallback(new NetworkRequest.Builder().build(), netCb);
        } catch (Exception ignored) {
            netCb = null;
        }
    }

    // ================= 通知 =================

    private PendingIntent openIntent() {
        Intent i = new Intent(this, TransferActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private Notification build(String text) {
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CH_RUN) : new Notification.Builder(this);
        PendingIntent stop = PendingIntent.getService(this, 1,
                new Intent(this, TransferService.class).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE);
        b.setSmallIcon(R.drawable.ic_swap)
                .setContentTitle("电脑互传已开启")
                .setContentText(text)
                .setOngoing(true)
                .setContentIntent(openIntent())
                .addAction(new Notification.Action.Builder(null, "停止", stop).build());
        return b.build();
    }

    private void startAsForeground(String text) {
        Notification n = build(text);
        if (Build.VERSION.SDK_INT >= 29) startForeground(NID_RUN, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        else startForeground(NID_RUN, n);
    }

    private void updateNotification() {
        List<String> u = Hub.get().getUrls();
        String t = u.isEmpty() ? "未连接 Wi-Fi，请连接 Wi-Fi 或开启热点" : "电脑浏览器访问 " + u.get(0);
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null && running) nm.notify(NID_RUN, build(t));
    }

    private void notifyIncoming(Hub.Message m) {
        if (TransferActivity.visible) return;
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm == null) return;
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CH_MSG) : new Notification.Builder(this);
        boolean text = "text".equals(m.type);
        b.setSmallIcon(R.drawable.ic_swap)
                .setContentTitle(text ? "电脑发来消息" : ("folder".equals(m.type) ? "电脑发来文件夹" : "电脑发来文件"))
                .setContentText(text ? m.text : m.name)
                .setStyle(new Notification.BigTextStyle().bigText(text ? m.text : m.name))
                .setAutoCancel(true)
                .setContentIntent(openIntent());
        if (Build.VERSION.SDK_INT < 26) b.setPriority(Notification.PRIORITY_HIGH);
        nm.notify(NID_MSG, b.build());
    }

    // ================= 平台适配 =================

    static String receiveDir(Context c) {
        if (Prefs.recvPrivate()) {
            File d = c.getExternalFilesDir("received");
            if (d != null) {
                //noinspection ResultOfMethodCallIgnored
                d.mkdirs();
                return d.getAbsolutePath();
            }
        }
        File dl = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        if (!dl.exists()) {
            //noinspection ResultOfMethodCallIgnored
            dl.mkdirs();
        }
        if (dl.canWrite()) return dl.getAbsolutePath();
        File d = c.getExternalFilesDir("received");
        if (d != null) {
            //noinspection ResultOfMethodCallIgnored
            d.mkdirs();
            return d.getAbsolutePath();
        }
        return c.getCacheDir().getAbsolutePath();
    }

    private final WebServer.Platform platform = new WebServer.Platform() {
        @Override
        public byte[] asset(String name) throws IOException {
            if ("index.html".equals(name) && indexHtml != null) return indexHtml;
            try (InputStream in = getAssets().open("web/" + name)) {
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                byte[] b = new byte[16384];
                int n;
                while ((n = in.read(b)) > 0) bo.write(b, 0, n);
                byte[] r = bo.toByteArray();
                if ("index.html".equals(name)) indexHtml = r;
                return r;
            }
        }

        @Override
        public Vfs vfs() {
            return App.vfs;
        }

        @Override
        public String receiveDir() {
            return TransferService.receiveDir(TransferService.this);
        }

        @Override
        public void onFileWritten(String path) {
            if (!App.vfs.isRestricted(path)) {
                MediaScannerConnection.scanFile(getApplicationContext(), new String[]{path}, null, null);
            }
        }

        @Override
        public void trackPartial(String path, boolean active) {
            Prefs.trackPartial(path, active);
        }
    };
}
