package com.lightdrop.transfer.net;

import android.content.Context;
import android.util.Log;

import com.lightdrop.transfer.core.Incoming;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;

/** 接收端：TCP 服务，接收文件并写入下载目录。 */
public class TransferServer {

    private static final String TAG = "LD-Server";

    public static class Meta {
        public String name;
        public long size;
    }

    public interface Listener {
        void onListening(int port, String deviceName);

        void onListenFailed(String message);

        void onSessionStart(int count);

        void onFileStart(int index, String name, long size);

        void onProgress(int index, long done, long total);

        void onFileDone(int index, String name, String uri, long size);

        void onSessionEnd(boolean ok, String message);
    }

    private final Context ctx;
    private final Listener listener;
    private final String deviceName;

    private ServerSocket serverSocket;
    private DiscoveryResponder responder;
    private volatile boolean running;
    private volatile boolean stopped;
    private int port = Protocol.TCP_PORT;

    public TransferServer(Context ctx, String deviceName, Listener listener) {
        this.ctx = ctx.getApplicationContext();
        this.deviceName = deviceName;
        this.listener = listener;
    }

    public int port() {
        return port;
    }

    public void start() {
        Thread t = new Thread(this::listenLoop, "ld-server");
        t.setDaemon(true);
        t.start();
    }

    private void listenLoop() {
        try {
            serverSocket = bind();
            if (stopped) {
                serverSocket.close();
                return;
            }
            port = serverSocket.getLocalPort();
            running = true;
            responder = new DiscoveryResponder(deviceName, port);
            responder.start();
            if (listener != null) listener.onListening(port, deviceName);
        } catch (Exception e) {
            if (listener != null) listener.onListenFailed(String.valueOf(e.getMessage()));
            return;
        }

        while (running) {
            Socket s = null;
            try {
                s = serverSocket.accept();
                handle(s);
            } catch (Exception e) {
                if (!running) break;
                Log.w(TAG, "session error: " + e);
            } finally {
                if (s != null) {
                    try {
                        s.close();
                    } catch (IOException ignored) {
                    }
                }
            }
        }
    }

    private ServerSocket bind() throws IOException {
        try {
            ServerSocket ss = new ServerSocket();
            ss.setReuseAddress(true);
            ss.bind(new InetSocketAddress(Protocol.TCP_PORT));
            return ss;
        } catch (IOException e) {
            ServerSocket ss = new ServerSocket(0);
            ss.setReuseAddress(true);
            return ss;
        }
    }

    private void handle(Socket socket) throws IOException {
        socket.setSoTimeout(30000);
        DataInputStream in = new DataInputStream(
                new BufferedInputStream(socket.getInputStream(), Protocol.CHUNK));
        DataOutputStream out = new DataOutputStream(
                new BufferedOutputStream(socket.getOutputStream()));

        int magic = in.readInt();
        if (magic != Protocol.MAGIC) return;
        int version = in.readInt();
        int count = in.readInt();
        if (count <= 0 || count > 500) {
            out.writeByte(0);
            out.writeUTF("文件数量异常");
            out.flush();
            return;
        }

        List<Meta> metas = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            Meta m = new Meta();
            m.name = in.readUTF();
            m.size = in.readLong();
            metas.add(m);
        }

        if (listener != null) listener.onSessionStart(count);
        out.writeByte(1);
        out.flush();
        socket.setSoTimeout(60000);

        boolean ok = false;
        String err = null;
        Incoming cur = null;
        int index = 0;
        try {
            byte[] buf = new byte[Protocol.CHUNK];
            for (index = 0; index < count; index++) {
                Meta m = metas.get(index);
                if (listener != null) listener.onFileStart(index, m.name, m.size);
                cur = Incoming.open(ctx, m.name, m.size);

                long remaining = m.size;
                long done = 0;
                long lastNotify = 0;
                while (remaining > 0) {
                    int want = (int) Math.min(buf.length, remaining);
                    int r = in.read(buf, 0, want);
                    if (r < 0) throw new EOFException("连接中断");
                    cur.write(buf, 0, r);
                    done += r;
                    remaining -= r;
                    long now = System.currentTimeMillis();
                    if (listener != null && (now - lastNotify > 120 || remaining == 0)) {
                        lastNotify = now;
                        listener.onProgress(index, done, m.size);
                    }
                }
                String uri = cur.finish();
                if (listener != null) listener.onFileDone(index, m.name, uri, m.size);
                cur = null;
            }
            ok = true;
        } catch (Exception e) {
            if (e instanceof java.net.SocketTimeoutException) {
                err = "连接超时，传输中断";
            } else {
                err = e.getMessage() == null ? "传输失败" : e.getMessage();
            }
            if (cur != null) cur.abort();
        } finally {
            if (cur != null) cur.abort();
            if (listener != null) listener.onSessionEnd(ok, err);
        }
    }

    public void stop() {
        stopped = true;
        running = false;
        if (responder != null) {
            responder.stop();
            responder = null;
        }
        if (serverSocket != null) {
            try {
                serverSocket.close();
            } catch (IOException ignored) {
            }
            serverSocket = null;
        }
    }

    public static String describe(int version) {
        return "protocol " + version;
    }
}
