package com.lightdrop.transfer.net;

import org.json.JSONObject;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 发送端：UDP 广播搜索局域网内的接收端。 */
public class DiscoveryClient {

    public static class Device {
        public String ip;
        public String name;
        public int port;
    }

    public static List<Device> scan(int timeoutMs) {
        List<Device> found = new ArrayList<>();
        DatagramSocket socket = null;
        try {
            socket = new DatagramSocket();
            socket.setBroadcast(true);
            socket.setSoTimeout(300);

            byte[] req = Protocol.DISCOVER_REQ.getBytes("UTF-8");
            for (InetAddress addr : broadcastAddresses()) {
                try {
                    socket.send(new DatagramPacket(req, req.length, addr, Protocol.UDP_PORT));
                } catch (Exception ignored) {
                }
            }

            long deadline = System.currentTimeMillis() + timeoutMs;
            Map<String, Device> map = new HashMap<>();
            byte[] buf = new byte[1024];
            while (System.currentTimeMillis() < deadline) {
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
                } catch (java.net.SocketTimeoutException te) {
                    // 继续等
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
