package com.lightdrop.transfer.net;

import android.util.Log;

import org.json.JSONObject;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.net.SocketTimeoutException;

/** 接收端：监听 UDP 广播并应答，让发送端能自动发现本机。 */
public class DiscoveryResponder implements Runnable {

    private static final String TAG = "LD-Discovery";

    private final String deviceName;
    private final int tcpPort;
    private DatagramSocket socket;
    private volatile boolean running;
    private volatile boolean stopped;

    public DiscoveryResponder(String deviceName, int tcpPort) {
        this.deviceName = deviceName;
        this.tcpPort = tcpPort;
    }

    public void start() {
        Thread t = new Thread(this, "ld-discovery");
        t.setDaemon(true);
        t.start();
    }

    public void stop() {
        stopped = true;
        running = false;
        if (socket != null) {
            try {
                socket.close();
            } catch (Exception ignored) {
            }
            socket = null;
        }
    }

    @Override
    public void run() {
        try {
            socket = new DatagramSocket(null);
            socket.setReuseAddress(true);
            socket.setBroadcast(true);
            // 显式绑 IPv4 通配，避免绑到 IPv6-only 后收不到局域网广播
            socket.bind(new InetSocketAddress("0.0.0.0", Protocol.UDP_PORT));
            socket.setSoTimeout(1000);
        } catch (SocketException e) {
            Log.w(TAG, "bind failed: " + e.getMessage());
            return;
        }
        if (stopped) {
            socket.close();
            return;
        }
        running = true;
        byte[] buf = new byte[512];
        while (running) {
            try {
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                socket.receive(p);
                String msg = new String(p.getData(), p.getOffset(), p.getLength(), "UTF-8");
                if (!msg.startsWith("LIGHTDROP/")) continue;

                JSONObject o = new JSONObject();
                o.put("n", deviceName);
                o.put("p", tcpPort);
                o.put("v", Protocol.VERSION);
                byte[] out = (o.toString() + "\n").getBytes("UTF-8");
                // 单播回给发送端
                socket.send(new DatagramPacket(out, out.length, p.getAddress(), p.getPort()));
            } catch (SocketTimeoutException ignored) {
            } catch (Exception e) {
                if (!running) break;
            }
        }
    }
}
