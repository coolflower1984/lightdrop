package com.lightdrop.transfer.core;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.text.TextUtils;
import android.webkit.MimeTypeMap;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public class Util {

    public static String humanSize(long bytes) {
        if (bytes < 0) return "--";
        if (bytes < 1024) return bytes + " B";
        double k = bytes / 1024.0;
        if (k < 1024) return String.format(Locale.US, "%.0f KB", k);
        double m = k / 1024.0;
        if (m < 1024) return String.format(Locale.US, "%.1f MB", m);
        return String.format(Locale.US, "%.2f GB", m / 1024.0);
    }

    public static String queryName(Context ctx, Uri uri) {
        String name = null;
        Cursor c = null;
        try {
            c = ctx.getContentResolver().query(uri, null, null, null, null);
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) name = c.getString(idx);
            }
        } catch (Exception ignored) {
        } finally {
            if (c != null) c.close();
        }
        if (TextUtils.isEmpty(name)) {
            name = uri.getLastPathSegment();
        }
        if (TextUtils.isEmpty(name)) name = "file";
        int slash = name.lastIndexOf('/');
        if (slash >= 0) name = name.substring(slash + 1);
        return name;
    }

    public static long querySize(Context ctx, Uri uri) {
        long size = -1;
        Cursor c = null;
        try {
            c = ctx.getContentResolver().query(uri, null, null, null, null);
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(OpenableColumns.SIZE);
                if (idx >= 0 && !c.isNull(idx)) size = c.getLong(idx);
            }
        } catch (Exception ignored) {
        } finally {
            if (c != null) c.close();
        }
        return size;
    }

    public static String mimeOf(String name) {
        int dot = name.lastIndexOf('.');
        if (dot >= 0 && dot < name.length() - 1) {
            String ext = name.substring(dot + 1).toLowerCase(Locale.US);
            String m = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
            if (m != null) return m;
        }
        return "application/octet-stream";
    }

    public static String safeName(String name) {
        if (name == null) return "file";
        String n = name.replace('\\', '_').replace('/', '_').replace("\u0000", "");
        n = n.trim();
        if (n.isEmpty()) n = "file";
        if (n.length() > 120) {
            int dot = n.lastIndexOf('.');
            String ext = "";
            if (dot > 0 && n.length() - dot <= 12) ext = n.substring(dot);
            n = n.substring(0, Math.min(100, n.length())) + ext;
        }
        return n;
    }

    /** 取本机局域网 IPv4，优先 wlan0。 */
    public static String localIp() {
        List<String> all = localIps();
        return all.isEmpty() ? "0.0.0.0" : all.get(0);
    }

    /**
     * 本机全部 IPv4 地址，Wi-Fi / 热点 / 以太网在前，蜂窝数据在后。
     *
     * 只取一个地址是个坑：手机开着移动数据时，某些机型会把 10.x 的蜂窝地址
     * 排到 wlan 前面，按它去扫网段就永远找不到平板。
     */
    public static List<String> localIps() {
        List<String> direct = new java.util.ArrayList<>();
        List<String> cellular = new java.util.ArrayList<>();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                String nm = ni.getName() == null ? "" : ni.getName().toLowerCase(Locale.US);
                if (nm.startsWith("rmnet") || nm.startsWith("ccmni") || nm.startsWith("pdp")
                        || nm.startsWith("dummy") || nm.startsWith("tun") || nm.startsWith("ppp")) {
                    // 蜂窝 / VPN / 隧道：留着当参考，但不能用来扫局域网
                    collectV4(ni, cellular);
                    continue;
                }
                collectV4(ni, direct);
            }
        } catch (Exception ignored) {
        }
        List<String> out = new java.util.ArrayList<>(direct);
        for (String s : cellular) if (!out.contains(s)) out.add(s);
        return out;
    }

    private static void collectV4(NetworkInterface ni, List<String> out) {
        for (InetAddress addr : Collections.list(ni.getInetAddresses())) {
            if (!(addr instanceof Inet4Address)) continue;
            if (addr.isLoopbackAddress()) continue;
            String ip = addr.getHostAddress();
            if (ip == null || ip.startsWith("169.254")) continue;
            if (!out.contains(ip)) out.add(ip);
        }
    }

    /** 由本机地址推出 /24 网段前缀，例如 192.168.1.。 */
    public static String prefixOf(String ip) {
        if (ip == null) return null;
        int dot = ip.lastIndexOf('.');
        if (dot <= 0) return null;
        return ip.substring(0, dot + 1);
    }

    public static String timeAgo(long t) {
        if (t <= 0) return "";
        long d = System.currentTimeMillis() - t;
        if (d < 60_000) return "刚刚";
        if (d < 3_600_000) return (d / 60_000) + " 分钟前";
        if (d < 86_400_000L) return (d / 3_600_000) + " 小时前";
        if (d < 7 * 86_400_000L) return (d / 86_400_000L) + " 天前";
        java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("M月d日", Locale.CHINA);
        return f.format(new java.util.Date(t));
    }

    public static String speedText(long bytes, long ms) {
        if (ms <= 0) return "";
        double bps = bytes * 1000.0 / ms;
        return String.format(Locale.US, "%.1f MB/s", bps / 1024 / 1024);
    }
}
