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
        String fallback = null;
        try {
            List<NetworkInterface> ifs = Collections.list(NetworkInterface.getNetworkInterfaces());
            // 先找 wlan，再退回其它
            for (int pass = 0; pass < 2; pass++) {
                for (NetworkInterface ni : ifs) {
                    if (!ni.isUp() || ni.isLoopback()) continue;
                    String nm = ni.getName() == null ? "" : ni.getName();
                    boolean wlan = nm.startsWith("wlan") || nm.startsWith("ap") || nm.startsWith("swlan");
                    if (pass == 0 && !wlan) continue;
                    if (pass == 1 && wlan) continue;
                    for (InetAddress addr : Collections.list(ni.getInetAddresses())) {
                        if (!(addr instanceof Inet4Address)) continue;
                        if (addr.isLoopbackAddress()) continue;
                        String ip = addr.getHostAddress();
                        if (ip == null || ip.startsWith("169.254")) continue;
                        if (pass == 0) return ip;
                        if (fallback == null) fallback = ip;
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return fallback == null ? "0.0.0.0" : fallback;
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
