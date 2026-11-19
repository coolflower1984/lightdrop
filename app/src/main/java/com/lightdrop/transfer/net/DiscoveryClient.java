package com.lightdrop.transfer.net;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.ConnectException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 发送端：两条发现通道。
 * 1. UDP 广播（快，但怕路由器 AP 隔离 / 广播过滤）
 * 2. TCP 扫同网段（慢 2 秒，但几乎不会被拦）
 */
public class DiscoveryClient {

    public static class Device {
        public String ip;
        public String name;
        public int port;
    }

    /** UDP 广播扫描。重复发 3 轮，降低单包丢失带来的失败。 */
    public static List<Device> scan(int timeoutMs) {
        List<Device> found = new ArrayList<>();
        DatagramSocket socket = null;
        try {
            socket = new DatagramSocket();
            socket.setBroadcast(true);
            socket.setSoTimeout(300);

            byte[] req = Protocol.DISCOVER_REQ.getBytes("UTF-8");
            Set<InetAddress> targets = broadcastAddresses();

            long deadline = System.currentTimeMillis() + timeoutMs;
            Map<String, Device> map = new HashMap<>();
            byte[] buf = new byte[1024];
            long nextSend = 0;

            while (System.currentTimeMillis() < deadline) {
                long now = System.currentTimeMillis();
                if (now >= nextSend) {
                    nextSend = now + 350;
                    for (InetAddress addr : targets) {
                        try {
                            socket.send(new DatagramPacket(req, req.length, addr, Protocol.UDP_PORT));
                        } catch (Exception ignored) {
                        }
                    }
                }
                try {
                    DatagramPacket p = new DatagramPacket(buf, buf.length);
                    socket.receive(p);
                    String s = new String(p.getData(), p.getOffset(), p.getLength(), "UTF-8").trim();
                    JSONObject o = new JSONObject(s);
                    Device d = new Device();
                    d.name = o.optString("n", "未知设备");
                    d.port = o.optInt("p", Protocol.TCP_PORT);
                    d.ip = p.getAddress().getHostAddress();
                    map.put(d.ip + ":" + d.port, d);
                } catch (java.net.SocketTimeoutException ignored) {
                } catch (Exception ignored) {
                }
            }
            found.addAll(map.values());
        } catch (Exception ignored) {
        } finally {
            if (socket != null) socket.close();
        }
        return found;
    }

    /**
     * TCP 扫同网段：对每个地址发一次 PROBE 握手，接收端回设备名。
     * 用于广播被路由器拦掉的情况。
     */
    public static List<Device> sweep(String myIp, final int timeoutMs) {
        final List<Device> out = Collections.synchronizedList(new ArrayList<>());
        if (myIp == null || myIp.indexOf('.') < 0 || "0.0.0.0".equals(myIp)) return out;
        final String prefix = myIp.substring(0, myIp.lastIndexOf('.') + 1);

        ExecutorService pool = Executors.newFixedThreadPool(48);
        final CountDownLatch latch = new CountDownLatch(254);
        for (int i = 1; i <= 254; i++) {
            final String ip = prefix + i;
            if (ip.equals(myIp)) {
                latch.countDown();
                continue;
            }
            pool.execute(() -> {
                try {
                    Device d = probe(ip, 320);
                    if (d != null) out.add(d);
                } finally {
                    latch.countDown();
                }
            });
        }
        try {
            latch.await(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ignored) {
        }
        pool.shutdownNow();
        return out;
    }

    private static Device probe(String ip, int timeout) {
        Socket s = new Socket();
        try {
            s.connect(new InetSocketAddress(ip, Protocol.TCP_PORT), timeout);
            s.setSoTimeout(2000);
            DataOutputStream out = new DataOutputStream(s.getOutputStream());
            out.writeInt(Protocol.MAGIC);
            out.writeInt(Protocol.VERSION);
            out.writeInt(Protocol.MODE_PROBE);
            out.flush();
            DataInputStream in = new DataInputStream(new BufferedInputStream(s.getInputStream()));
            JSONObject o = new JSONObject(in.readUTF());
            Device d = new Device();
            d.ip = ip;
            d.name = o.optString("n", "Android");
            d.port = o.optInt("p", Protocol.TCP_PORT);
            return d;
        } catch (ConnectException ignored) {
            return null;
        } catch (Exception ignored) {
            return null;
        } finally {
            try {
                s.close();
            } catch (Exception ignored) {
            }
        }
    }

    private static Set<InetAddress> broadcastAddresses() {
        Set<InetAddress> set = new LinkedHashSet<>();
        try {
            set.add(InetAddress.getByName("255.255.255.255"));
        } catch (Exception ignored) {
        }
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InterfaceAddress ia : ni.getInterfaceAddresses()) {
                    InetAddress b = ia.getBroadcast();
                    if (b != null) set.add(b);
                }
            }
        } catch (Exception ignored) {
        }
        return set;
    }

    /** 解析用户手输的地址，支持 "192.168.1.5" 或 "192.168.1.5:46000"。 */
    public static Device parse(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.isEmpty()) return null;
        Device d = new Device();
        int colon = s.lastIndexOf(':');
        if (colon > 0 && colon < s.length() - 1) {
            try {
                d.port = Integer.parseInt(s.substring(colon + 1).trim());
                d.ip = s.substring(0, colon).trim();
            } catch (NumberFormatException e) {
                d.ip = s;
                d.port = Protocol.TCP_PORT;
            }
        } else {
            d.ip = s;
            d.port = Protocol.TCP_PORT;
        }
        d.name = d.ip;
        return d.ip.isEmpty() ? null : d;
    }

    public static InetSocketAddress addr(String ip, int port) {
        try {
            return new InetSocketAddress(InetAddress.getByName(ip), port);
        } catch (Exception e) {
            return null;
        }
    }
}
