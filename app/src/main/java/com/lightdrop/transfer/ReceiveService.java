package com.lightdrop.transfer;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

import com.lightdrop.transfer.core.Prefs;
import com.lightdrop.transfer.core.Receiver;
import com.lightdrop.transfer.net.TransferServer;

/**
 * 前台服务：让接收端在锁屏、切到其他应用时依然能收文件。
 * 持有 WifiLock 保证链路不休眠，传输期间再额外持有 CPU 唤醒锁。
 */
public class ReceiveService extends Service implements TransferServer.Listener {

    public static final String ACTION_START = "com.lightdrop.transfer.action.START";
    public static final String ACTION_STOP = "com.lightdrop.transfer.action.STOP";

    private static final String CHANNEL_ID = "receive";
    private static final int NOTIF_ID = 1001;

    private NotificationManager nm;
    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;

    public static void start(Context ctx) {
        Intent i = new Intent(ctx, ReceiveService.class).setAction(ACTION_START);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ctx.startForegroundService(i);
        } else {
            ctx.startService(i);
        }
    }

    public static void stop(Context ctx) {
        ctx.stopService(new Intent(ctx, ReceiveService.class));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        createChannel();
        acquireWifiLock();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            // 从通知栏关掉时也记住，免得下次进页面又自动开
            new Prefs(this).setReceiveAuto(false);
            stopSelf();
            return START_NOT_STICKY;
        }
        // 前台化失败也要继续跑：Android 14 对 dataSync 类型有限制，
        // 抛异常时整个服务会被干掉，接收监听也跟着没了。
        try {
            goForeground(buildNotification("轻传已开启接收", "其他设备可发现本机，点此查看", -1));
        } catch (Exception e) {
            android.util.Log.w("LD-Service", "startForeground failed: " + e);
        }

        Receiver r = Receiver.get(this);
        r.addListener(this);
        r.start(new Prefs(this).deviceName());
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        Receiver r = Receiver.get(this);
        r.removeListener(this);
        r.stop();
        releaseLocks();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // ---------------- 通知 ----------------

    private void createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || nm == null) return;
        NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "后台接收",
                NotificationManager.IMPORTANCE_LOW);
        ch.setDescription("在后台持续接收其他设备发来的文件");
        ch.setShowBadge(false);
        nm.createNotificationChannel(ch);
    }

    @SuppressWarnings("deprecation")
    private Notification buildNotification(String title, String text, int progress) {
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            b = new Notification.Builder(this, CHANNEL_ID);
        } else {
            b = new Notification.Builder(this);
        }
        Intent open = new Intent(this, ReceiveActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Intent stop = new Intent(this, ReceiveService.class).setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(this, 1, stop,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        b.setSmallIcon(R.drawable.ic_stat)
                .setContentTitle(title)
                .setContentText(text)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setContentIntent(pi)
                .addAction(0, "停止接收", stopPi);

        if (progress >= 0) {
            b.setProgress(100, progress, false);
        }
        return b.build();
    }

    private void goForeground(Notification n) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, n, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(NOTIF_ID, n);
        }
    }

    private void update(String title, String text, int progress) {
        try {
            if (nm != null) nm.notify(NOTIF_ID, buildNotification(title, text, progress));
        } catch (Exception ignored) {
        }
    }

    // ---------------- 锁 ----------------

    private void acquireWifiLock() {
        try {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
            if (wm != null) {
                wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "lightdrop:wifi");
                wifiLock.setReferenceCounted(false);
                wifiLock.acquire();
            }
        } catch (Exception ignored) {
        }
    }

    private void acquireWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) return;
        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (pm != null) {
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "lightdrop:transfer");
                wakeLock.setReferenceCounted(false);
                wakeLock.acquire(30 * 60 * 1000L);
            }
        } catch (Exception ignored) {
        }
    }

    private void releaseWakeLock() {
        try {
            if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        } catch (Exception ignored) {
        }
        wakeLock = null;
    }

    private void releaseLocks() {
        releaseWakeLock();
        try {
            if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
        } catch (Exception ignored) {
        }
        wifiLock = null;
    }

    // ---------------- 传输事件 ----------------

    @Override
    public void onListening(int port, String deviceName) {
        String ip = com.lightdrop.transfer.core.Util.localIp();
        update("轻传已开启接收", "地址 " + ip + ":" + port + " · 点此查看", -1);
    }

    @Override
    public void onListenFailed(String message) {
        update("接收开启失败", message, -1);
    }

    @Override
    public void onSessionStart(int count) {
        acquireWakeLock();
        update("正在接收文件", "共 " + count + " 个文件", 0);
    }

    @Override
    public void onFileStart(int index, String name, long size) {
        update("正在接收 " + (index + 1) + " 个文件", name, 0);
    }

    @Override
    public void onProgress(int index, long done, long total) {
        int pct = total > 0 ? (int) (done * 100 / total) : 0;
        update("正在接收文件 " + (index + 1), com.lightdrop.transfer.core.Util.humanSize(done)
                + " / " + com.lightdrop.transfer.core.Util.humanSize(total), pct);
    }

    @Override
    public void onFileDone(int index, String name, String uri, long size) {
        update("已保存 " + name, com.lightdrop.transfer.core.Util.humanSize(size), -1);
    }

    @Override
    public void onSessionEnd(boolean ok, String message) {
        releaseWakeLock();
        update(ok ? "接收完成" : "传输中断", ok ? "文件已保存到 Download/轻传" : message, -1);
    }
}
