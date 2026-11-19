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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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
    private final ExecutorService pool = Executors.newCachedThreadPool();
    private volatile boolean running;
    private volatile boolean stopped;
    private volatile int port = Protocol.TCP_PORT;

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

        // 每条连接交给线程池，避免一条半开连接把后面所有设备堵在 accept 里。
        while (running) {
            try {
                final Socket s = serverSocket.accept();
                pool.execute(() -> handle(s));
            } catch (Exception e) {
                if (!running) break;
                Log.w(TAG, "accept: " + e);
            }
        }
    }

    /** 从 46000 依次往后试，最多 10 个端口，避免回退到随机端口导致发送端连不上。 */
    private ServerSocket bind() throws IOException {
        IOException last = null;
        for (int i = 0; i < Protocol.PORT_TRIES; i++) {
            int p = Protocol.TCP_PORT + i;
            try {
                ServerSocket ss = new ServerSocket();
                ss.setReuseAddress(true);
                // 显式绑 IPv4 通配地址，避免某些设备绑到 IPv6-only 后 IPv4 连不进来。
                ss.bind(new InetSocketAddress("0.0.0.0", p), 64);
                return ss;
            } catch (IOException e) {
                last = e;
            }
        }
        throw last == null ? new IOException("端口不可用") : last;
    }

    private void handle(Socket socket) {
        String err = null;
        boolean ok = false;
        Incoming cur = null;
        try {
            socket.setTcpNoDelay(true);
            socket.setSoTimeout(20000);

            // 先看前 4 个字节：HTTP 请求（GET/POST）走网页上传，其余走二进制协议。
            java.io.PushbackInputStream pis = new java.io.PushbackInputStream(
                    new BufferedInputStream(socket.getInputStream(), Protocol.CHUNK), 8);
            byte[] head = new byte[4];
            int got = 0;
            while (got < 4) {
                int r = pis.read(head, got, 4 - got);
                if (r < 0) break;
                got += r;
            }
            if (got == 0) return;
            pis.unread(head, 0, got);

            if (MiniHttp.looksHttp(head, got)) {
                socket.setSoTimeout(180000);
                MiniHttp.handle(ctx, pis, socket.getOutputStream(), listener);
                return;
            }

            DataInputStream in = new DataInputStream(pis);
            DataOutputStream out = new DataOutputStream(
                    new BufferedOutputStream(socket.getOutputStream()));

            int magic = in.readInt();
            if (magic != Protocol.MAGIC) return;
            int version = in.readInt();
            if (version != Protocol.VERSION) {
                out.writeByte(0);
                out.writeUTF("版本不一致，请把两台设备都更新到最新版");
                out.flush();
                return;
            }
            int mode = in.readInt();

            if (mode == Protocol.MODE_PROBE) {
                out.writeUTF(probeJson());
                out.flush();
                return;
            }
            if (mode != Protocol.MODE_SEND) {
                out.writeByte(0);
                out.writeUTF("协议不匹配");
                out.flush();
                return;
            }

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
            socket.setSoTimeout(120000);

            long total = 0;
            try {
                byte[] buf = new byte[Protocol.CHUNK];
                for (int index = 0; index < count; index++) {
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
                    total += m.size;
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
            }

            // 只有真正落盘完成才回 1，发送端据此判定结果。
            out.writeByte(ok ? 1 : 0);
            out.writeLong(total);
            if (!ok) out.writeUTF(err == null ? "接收失败" : err);
            out.flush();

            if (listener != null) listener.onSessionEnd(ok, err);
        } catch (Exception e) {
            Log.w(TAG, "handle: " + e);
            if (cur != null) cur.abort();
            if (listener != null) listener.onSessionEnd(false, e.getMessage() == null ? "传输失败" : e.getMessage());
        } finally {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }

    private String probeJson() {
        StringBuilder sb = new StringBuilder(96);
        sb.append("{\"n\":\"").append(escape(deviceName)).append("\",\"p\":").append(port)
                .append(",\"v\":").append(Protocol.VERSION).append('}');
        return sb.toString();
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
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
        pool.shutdownNow();
    }

    public static String describe(int version) {
        return "protocol " + version;
    }
}
