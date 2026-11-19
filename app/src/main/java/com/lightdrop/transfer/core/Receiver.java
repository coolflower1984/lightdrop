package com.lightdrop.transfer.core;

import android.content.Context;

import com.lightdrop.transfer.net.TransferServer;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 接收服务的进程内单例：真正的 TransferServer 放在这里跑，
 * 前台服务与界面都只订阅它的事件，这样退出界面不会中断接收。
 */
public class Receiver implements TransferServer.Listener {

    private static Receiver sInstance;

    private final Context appCtx;
    private final List<TransferServer.Listener> listeners = new CopyOnWriteArrayList<>();
    private TransferServer server;
    private String deviceName = "Android";
    private int port = 0;

    private Receiver(Context ctx) {
        appCtx = ctx.getApplicationContext();
    }

    public static synchronized Receiver get(Context ctx) {
        if (sInstance == null) sInstance = new Receiver(ctx);
        return sInstance;
    }

    public boolean isRunning() {
        return server != null;
    }

    public int port() {
        return port;
    }

    public String deviceName() {
        return deviceName;
    }

    public synchronized void start(String name) {
        if (server != null) return;
        deviceName = name;
        server = new TransferServer(appCtx, name, this);
        server.start();
    }

    public synchronized void stop() {
        if (server != null) {
            server.stop();
            server = null;
        }
    }

    public void addListener(TransferServer.Listener l) {
        if (l != null && !listeners.contains(l)) listeners.add(l);
    }

    public void removeListener(TransferServer.Listener l) {
        listeners.remove(l);
    }

    // ---- 转发给所有订阅者 ----

    @Override
    public void onListening(int p, String name) {
        port = p;
        for (TransferServer.Listener l : listeners) l.onListening(p, name);
    }

    @Override
    public void onListenFailed(String message) {
        for (TransferServer.Listener l : listeners) l.onListenFailed(message);
    }

    @Override
    public void onSessionStart(int count) {
        for (TransferServer.Listener l : listeners) l.onSessionStart(count);
    }

    @Override
    public void onFileStart(int index, String name, long size) {
        for (TransferServer.Listener l : listeners) l.onFileStart(index, name, size);
    }

    @Override
    public void onProgress(int index, long done, long total) {
        for (TransferServer.Listener l : listeners) l.onProgress(index, done, total);
    }

    @Override
    public void onFileDone(int index, String name, String uri, long size) {
        for (TransferServer.Listener l : listeners) l.onFileDone(index, name, uri, size);
    }

    @Override
    public void onSessionEnd(boolean ok, String message) {
        for (TransferServer.Listener l : listeners) l.onSessionEnd(ok, message);
    }
}
