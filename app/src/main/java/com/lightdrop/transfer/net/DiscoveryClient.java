package com.lightdrop.transfer.net;

import com.lightdrop.transfer.core.Util;

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
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 发送端：三条发现通道。
 * 1. UDP 广播（快，但怕路由器 AP 隔离 / 广播过滤）
 * 2. TCP 扫同网段（慢两三秒，但几乎不会被拦）
 * 3. 手动输地址（最终兜底，用 {@link #probeAny} 校验后再发）
 */
public class DiscoveryClient {

    public static class Device {
        public String ip;
        public String name;
        public int port;

        public String label() {
            if (name != null && !name.isEmpty() && !name.equals(ip)) return name;
            return ip;
        }
    }

    /** 探测单个地址时依次尝试的端口，与接收端的端口回退范围一致。 */
    private static final int[] PROBE_PORTS = {
            Protocol.TCP_PORT, Protocol.TCP_PORT + 1, Protocol.TCP_PORT + 2
    };

    /** UDP 广播扫描。重复发多轮，降低单包丢失带来的失败。 */
    public static List<Device> scan(int timeoutMs) {
        return scan(null, timeoutMs);
    }

    /**
     * UDP 广播扫描。
     *
     * 必须持有 MulticastLock：Android 的 Wi-Fi 驱动默认会丢弃不是发给自己的
     * 广播/组播帧，不拿这把锁，很多机型根本收不到应答。
     */
    public static List<Device> scan(android.content.Context ctx, int timeoutMs) {
        List<Device> found = new ArrayList<>();
        DatagramSocket socket = null;
        android.net.wifi.WifiManager.MulticastLock lock = acquireMulticast(ctx);
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
            release(lock);
        }
        return found;
    }

    /** 拿 Wi-Fi 组播锁，没权限或非 WiFi 环境就跳过。 */
    static android.net.wifi.WifiManager.MulticastLock acquireMulticast(android.content.Context ctx) {
        if (ctx == null) return null;
        try {
            android.net.wifi.WifiManager wm = (android.net.wifi.WifiManager)
                    ctx.getApplicationContext().getSystemService(android.content.Context.WIFI_SERVICE);
            if (wm == null) return null;
            android.net.wifi.WifiManager.MulticastLock l = wm.createMulticastLock("lightdrop:mdns");
            l.setReferenceCounted(false);
            l.acquire();
            return l;
        } catch (Exception e) {
            return null;
        }
    }

    static void release(android.net.wifi.WifiManager.MulticastLock lock) {
        try {
            if (lock != null && lock.isHeld()) lock.release();
        } catch (Exception ignored) {
        }
    }

    /** 兼容旧调用：只扫第一个地址所在网段。 */
    public static List<Device> sweep(String myIp, int timeoutMs) {
        List<String> ips = new ArrayList<>();
        if (myIp != null && !myIp.isEmpty()) ips.add(myIp);
        return sweep(ips, timeoutMs);
    }

    /**
     * TCP 扫同网段：对每个地址发一次 PROBE 握手，接收端回设备名。
     * 会扫本机所有网段（Wi-Fi、热点、USB 共享都在内），最多 3 个网段。
     */
    public static List<Device> sweep(List<String> myIps, final int timeoutMs) {
        final List<Device> out = Collections.synchronizedList(new ArrayList<>());
        Set<String> prefixes = new LinkedHashSet<>();
        Set<String> selfIps = new LinkedHashSet<>();
        if (myIps != null) {
            for (String ip : myIps) {
                if (ip == null) continue;
                selfIps.add(ip);
                String p = Util.prefixOf(ip);
                if (p != null) prefixes.add(p);
            }
        }
        if (prefixes.isEmpty()) return out;

        // 控制在 3 个网段以内，避免同时挂着蜂窝 + Wi-Fi + VPN 时扫太久
        List<String> targets = new ArrayList<>(prefixes);
        if (targets.size() > 3) targets = targets.subList(0, 3);

        final int total = targets.size() * 254;
        final CountDownLatch latch = new CountDownLatch(total);
        ExecutorService pool = Executors.newFixedThreadPool(64);
        for (String prefix : targets) {
            for (int i = 1; i <= 254; i++) {
                final String ip = prefix + i;
                if (selfIps.contains(ip)) {
                    latch.countDown();
                    continue;
                }
                pool.execute(() -> {
                    try {
                        Device d = probe(ip, Protocol.TCP_PORT, 600);
                        if (d != null) out.add(d);
                    } finally {
                        latch.countDown();
                    }
                });
            }
        }
        try {
            latch.await(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ignored) {
        }
        pool.shutdownNow();

        // 接收端没占到 46000 时（端口被别的应用占了），补扫备用端口
        if (out.isEmpty() && targets.size() <= 3) {
            final CountDownLatch second = new CountDownLatch(targets.size() * 254);
            ExecutorService pool2 = Executors.newFixedThreadPool(64);
            for (final String prefix : targets) {
                for (int i = 1; i <= 254; i++) {
                    final String ip = prefix + i;
                    if (selfIps.contains(ip)) {
                        second.countDown();
                        continue;
                    }
                    pool2.execute(() -> {
                        try {
                            for (int k = 1; k <= 2; k++) {
                                Device d = probe(ip, Protocol.TCP_PORT + k, 400);
                                if (d != null) {
                                    out.add(d);
                                    break;
                                }
                            }
                        } finally {
                            second.countDown();
                        }
                    });
                }
            }
            try {
                second.await(Math.max(2500, timeoutMs / 2), TimeUnit.MILLISECONDS);
            } catch (InterruptedException ignored) {
            }
            pool2.shutdownNow();
        }
        return out;
    }

    /**
     * 手输地址直连前先探一下，顺手拿到设备名与真实端口。
     * 返回 null 表示这个地址上没有轻传在监听。
     */
    public static Device probeAny(String ip, int preferredPort) {
        if (ip == null || ip.isEmpty()) return null;
        if (preferredPort > 0) {
            Device d = probe(ip, preferredPort, 2500);
            if (d != null) return d;
        }
        for (int port : PROBE_PORTS) {
            if (port == preferredPort) continue;
            Device d = probe(ip, port, 1200);
            if (d != null) return d;
        }
        return null;
    }

    /**
     * 单次探测。返回 null 表示连不上或对端不是轻传。
     *
     * @param timeout 连接超时，局域网内 600ms 足够
     */
    public static Device probe(String ip, int port, int timeout) {
        Socket s = new Socket();
        try {
            s.connect(new InetSocketAddress(ip, port), timeout);
            s.setTcpNoDelay(true);
            s.setSoTimeout(2500);
            DataOutputStream out = new DataOutputStream(s.getOutputStream());
            out.writeInt(Protocol.MAGIC);
            out.writeInt(Protocol.VERSION);
            out.writeInt(Protocol.MODE_PROBE);
            out.flush();
            DataInputStream in = new DataInputStream(new BufferedInputStream(s.getInputStream()));
            JSONObject o = new JSONObject(in.readUTF());
            Device d = new Device();
            d.ip = ip;
            d.port = o.optInt("p", port);
            d.name = o.optString("n", "Android");
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
                String nm = ni.getName() == null ? "" : ni.getName().toLowerCase();
                if (nm.startsWith("rmnet") || nm.startsWith("dummy")) continue;
                for (InterfaceAddress ia : ni.getInterfaceAddresses()) {
                    InetAddress b = ia.getBroadcast();
                    if (b != null) set.add(b);
                }
            }
        } catch (Exception ignored) {
        }
        return set;
    }

    /** 解析用户手输的地址，支持 "192.168.1.5"、"192.168.1.5:46000" 或整段网址。 */
    public static Device parse(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.isEmpty()) return null;
        if (s.startsWith("http://")) s = s.substring(7);
        if (s.startsWith("https://")) s = s.substring(8);
        int slash = s.indexOf('/');
        if (slash > 0) s = s.substring(0, slash);
        s = s.trim();
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

    /** 本机 46001 是否可用（排查端口占用）。 */
    public static boolean udpPortFree() {
        DatagramSocket s = null;
        try {
            s = new DatagramSocket(null);
            s.setReuseAddress(true);
            s.bind(new InetSocketAddress("0.0.0.0", Protocol.UDP_PORT));
            return true;
        } catch (Exception e) {
            return false;
        } finally {
            if (s != null) s.close();
        }
    }
}
