package com.lightdrop.transfer.net;

import android.content.Context;
import android.net.Uri;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.List;

/** 发送端：连接接收端并推送文件。 */
public class TransferClient implements Runnable {

    public static class Item {
        public Uri uri;
        public String name;
        public long size;
    }

    public interface Listener {
        void onConnected();

        void onFileStart(int index, String name);

        void onProgress(int index, long done, long total);

        void onDone(boolean ok, String message);
    }

    private final Context ctx;
    private final String host;
    private final int port;
    private final List<Item> items;
    private final Listener listener;

    public TransferClient(Context ctx, String host, int port, List<Item> items, Listener listener) {
        this.ctx = ctx.getApplicationContext();
        this.host = host;
        this.port = port;
        this.items = items;
        this.listener = listener;
    }

    public void start() {
        Thread t = new Thread(this, "ld-send");
        t.setDaemon(true);
        t.start();
    }

    @Override
    public void run() {
        Socket socket = null;
        try {
            socket = new Socket();
            socket.connect(new InetSocketAddress(host, port), 8000);
            socket.setSendBufferSize(256 * 1024);
            socket.setTcpNoDelay(true);

            DataOutputStream out = new DataOutputStream(
                    new BufferedOutputStream(socket.getOutputStream(), Protocol.CHUNK));
            DataInputStream in = new DataInputStream(
                    new BufferedInputStream(socket.getInputStream()));

            out.writeInt(Protocol.MAGIC);
            out.writeInt(Protocol.VERSION);
            out.writeInt(items.size());
            for (Item it : items) {
                out.writeUTF(it.name);
                out.writeLong(it.size);
            }
            out.flush();

            int ack = in.readByte();
            if (ack != 1) {
                String msg = "对方拒绝接收";
                try {
                    msg = in.readUTF();
                } catch (Exception ignored) {
                }
                if (listener != null) listener.onDone(false, msg);
                return;
            }
            if (listener != null) listener.onConnected();

            byte[] buf = new byte[Protocol.CHUNK];
            for (int i = 0; i < items.size(); i++) {
                Item it = items.get(i);
                if (listener != null) listener.onFileStart(i, it.name);
                InputStream is = ctx.getContentResolver().openInputStream(it.uri);
                if (is == null) throw new IOException("无法读取 " + it.name);
                BufferedInputStream bis = new BufferedInputStream(is, Protocol.CHUNK);
                long done = 0;
                long last = 0;
                int r;
                while ((r = bis.read(buf)) > 0) {
                    out.write(buf, 0, r);
                    done += r;
                    long now = System.currentTimeMillis();
                    if (listener != null && (now - last > 120 || done >= it.size)) {
                        last = now;
                        listener.onProgress(i, done, it.size);
                    }
                }
                bis.close();
                out.flush();
            }
            socket.shutdownOutput();
            if (listener != null) listener.onDone(true, "");
        } catch (Exception e) {
            String msg;
            if (e instanceof java.net.ConnectException) {
                msg = "无法连接，请确认对方已开启接收且在同一 Wi-Fi";
            } else if (e instanceof java.net.SocketTimeoutException) {
                msg = "连接超时，检查两台设备是否在同一网络";
            } else {
                msg = e.getMessage() == null ? "连接失败" : e.getMessage();
            }
            if (listener != null) listener.onDone(false, msg);
        } finally {
            if (socket != null) {
                try {
                    socket.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    /** 大小未知的 uri（少数微信内容）先落到缓存再发，保证协议能按长度分帧。 */
    public static Item resolve(Context ctx, Uri uri) throws IOException {
        Item it = new Item();
        it.uri = uri;
        it.name = com.lightdrop.transfer.core.Util.queryName(ctx, uri);
        it.size = com.lightdrop.transfer.core.Util.querySize(ctx, uri);
        if (it.size <= 0) {
            File dir = new File(ctx.getCacheDir(), "outbox");
            if (!dir.exists() && !dir.mkdirs()) throw new IOException("缓存目录不可用");
            File tmp = new File(dir, it.name);
            InputStream is = ctx.getContentResolver().openInputStream(uri);
            if (is == null) throw new IOException("无法读取 " + it.name);
            FileOutputStream fos = new FileOutputStream(tmp);
            byte[] buf = new byte[Protocol.CHUNK];
            int r;
            long total = 0;
            while ((r = is.read(buf)) > 0) {
                fos.write(buf, 0, r);
                total += r;
            }
            is.close();
            fos.close();
            it.uri = Uri.fromFile(tmp);
            it.size = total;
        }
        return it;
    }

    public static long totalSize(List<Item> items) {
        long s = 0;
        for (Item i : items) s += Math.max(0, i.size);
        return s;
    }

    static void closeQuietly(OutputStream os) {
        try {
            if (os != null) os.close();
        } catch (IOException ignored) {
        }
    }
}
