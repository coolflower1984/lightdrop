package com.lightdrop.transfer.core;

import android.Manifest;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.provider.Settings;
import android.text.TextUtils;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 查找手机里最近的文件，重点是微信发来的。
 *
 * 关于微信目录的实际情况（Android 11 起）：
 * · /sdcard/Android/data/com.tencent.mm/… 是聊天文件的真实位置，
 *   但系统对该目录做了硬限制，任何第三方应用都读不到，即使有「所有文件访问」权限。
 * · /sdcard/Android/media/com.tencent.mm/… 是微信的媒体目录，这个可以读。
 * · 微信里「保存到手机」的文件会落到 /sdcard/Download 或 /sdcard/tencent/MicroMsg/Download。
 *
 * 所以这里把所有可读的来源都扫一遍；真正拿不到的只有「聊天里没保存过的原件」，
 * 那种情况只能从微信里选「用其他应用打开」交给轻传。
 */
public class WxFiles {

    public static class Entry {
        /** 展示与去重用的标识。 */
        public String path;
        /** 真正拿去发送的 uri，可能是 content:// 也可能是 file://。 */
        public String uri;
        public String name;
        public long size;
        public long time;
        public boolean wx;
        /** 来源说明，例如「微信」「下载」。 */
        public String source;

        public Uri sendUri() {
            if (!TextUtils.isEmpty(uri)) return Uri.parse(uri);
            return Uri.fromFile(new File(path));
        }
    }

    /** Android 11+ 用「所有文件访问」，旧系统用读存储权限。 */
    public static boolean hasPermission(Context ctx) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return Environment.isExternalStorageManager();
        }
        return ctx.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED;
    }

    /** 跳去系统设置里开权限。 */
    public static void requestPermission(android.app.Activity act, int reqCode) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                Intent i = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                i.setData(Uri.parse("package:" + act.getPackageName()));
                act.startActivityForResult(i, reqCode);
                return;
            } catch (Exception ignored) {
            }
            try {
                act.startActivityForResult(
                        new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION), reqCode);
                return;
            } catch (Exception ignored) {
            }
        }
        act.requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, reqCode);
    }

    private static class Dir {
        File file;
        int depth;
        String source;

        Dir(File f, int d, String s) {
            file = f;
            depth = d;
            source = s;
        }
    }

    /** 候选目录，按优先级排列。 */
    private static List<Dir> dirs() {
        File ext = Environment.getExternalStorageDirectory();
        List<Dir> list = new ArrayList<>();
        // 微信「保存到手机」的默认落点
        add(list, new File(ext, "Download"), 2, "下载");
        // 微信自己的公共目录（旧版 / 部分机型）
        add(list, new File(ext, "tencent/MicroMsg/Download"), 3, "微信");
        add(list, new File(ext, "tencent/MicroMsg/WeiXin"), 2, "微信");
        // Android 11+ 唯一可读的微信目录
        add(list, new File(ext, "Android/media/com.tencent.mm"), 4, "微信");
        // 微信保存到相册的图片视频
        add(list, new File(ext, "DCIM/WeChat"), 2, "微信");
        add(list, new File(ext, "Pictures/WeChat"), 2, "微信");
        // 聊天文件的真实位置：Android 11+ 读不到，Android 10 及以下可以
        add(list, new File(ext, "Android/data/com.tencent.mm/MicroMsg/Download"), 3, "微信");
        add(list, new File(ext, "Android/data/com.tencent.mm/MicroMsg/WeiXin"), 2, "微信");
        return list;
    }

    private static void add(List<Dir> list, File f, int depth, String source) {
        if (f.isDirectory()) list.add(new Dir(f, depth, source));
    }

    /**
     * 最近的文件，按时间倒序。
     *
     * @param days  只看最近多少天
     * @param limit 上限
     */
    public static List<Entry> recent(Context ctx, int days, int limit) {
        long since = System.currentTimeMillis() - Math.max(1, days) * 86_400_000L;
        List<Entry> all = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        // 1. 目录直读（有「所有文件访问」时能读到微信的公共目录）
        for (Dir d : dirs()) {
            collect(d.file, d.depth, since, d.source, all, seen);
        }

        // 2. MediaStore 补充（下载、相册、文档，不需要权限也能拿到自己可见的部分）
        queryMediaStore(ctx, since, all, seen);

        Collections.sort(all, new Comparator<Entry>() {
            @Override
            public int compare(Entry a, Entry b) {
                return Long.compare(b.time, a.time);
            }
        });
        if (all.size() > limit) return new ArrayList<>(all.subList(0, limit));
        return all;
    }

    private static void collect(File dir, int depth, long since, String source,
                                List<Entry> out, Set<String> seen) {
        if (out.size() > 2500) return;
        File[] files;
        try {
            files = dir.listFiles();
        } catch (Exception e) {
            return;
        }
        if (files == null) return;
        for (File f : files) {
            String nm = f.getName();
            if (nm.startsWith(".")) continue;
            if (f.isDirectory()) {
                String low = nm.toLowerCase(Locale.US);
                if (low.equals("cache") || low.equals("thumb") || low.equals("thumbnails")
                        || low.equals("avatar") || low.equals("sns") || low.equals("emoji")) {
                    continue;
                }
                if (depth > 0) collect(f, depth - 1, since, source, out, seen);
                continue;
            }
            String path = f.getAbsolutePath();
            if (seen.contains(path)) continue;
            if (!validName(nm)) continue;
            long size = f.length();
            if (size <= 0) continue;
            long t = f.lastModified();
            if (t < since) continue;
            Entry e = new Entry();
            e.path = path;
            e.uri = Uri.fromFile(f).toString();
            e.name = nm;
            e.size = size;
            e.time = t;
            e.wx = isWxPath(path) || "微信".equals(source);
            e.source = source;
            seen.add(path);
            out.add(e);
        }
    }

    private static void queryMediaStore(Context ctx, long since, List<Entry> out, Set<String> seen) {
        ContentResolver cr = ctx.getContentResolver();
        Uri base = MediaStore.Files.getContentUri("external");
        boolean modern = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q;
        String[] proj;
        if (modern) {
            proj = new String[]{
                    MediaStore.MediaColumns._ID,
                    MediaStore.MediaColumns.DISPLAY_NAME,
                    MediaStore.MediaColumns.SIZE,
                    MediaStore.MediaColumns.DATE_MODIFIED,
                    MediaStore.MediaColumns.DATA,
                    MediaStore.MediaColumns.RELATIVE_PATH
            };
        } else {
            proj = new String[]{
                    MediaStore.MediaColumns._ID,
                    MediaStore.MediaColumns.DISPLAY_NAME,
                    MediaStore.MediaColumns.SIZE,
                    MediaStore.MediaColumns.DATE_MODIFIED,
                    MediaStore.MediaColumns.DATA
            };
        }
        String sel = MediaStore.MediaColumns.DATE_MODIFIED + " > ?";
        String[] args = {String.valueOf(since / 1000L)};
        Cursor c = null;
        try {
            c = cr.query(base, proj, sel, args,
                    MediaStore.MediaColumns.DATE_MODIFIED + " DESC");
            if (c == null) return;
            int iId = c.getColumnIndex(MediaStore.MediaColumns._ID);
            int iName = c.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME);
            int iSize = c.getColumnIndex(MediaStore.MediaColumns.SIZE);
            int iTime = c.getColumnIndex(MediaStore.MediaColumns.DATE_MODIFIED);
            int iData = c.getColumnIndex(MediaStore.MediaColumns.DATA);
            int iRel = modern ? c.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH) : -1;
            int n = 0;
            while (c.moveToNext() && n < 600) {
                n++;
                String name = iName >= 0 ? c.getString(iName) : null;
                if (TextUtils.isEmpty(name) || !validName(name)) continue;
                long size = iSize >= 0 ? c.getLong(iSize) : 0;
                if (size <= 0) continue;

                String data = iData >= 0 ? c.getString(iData) : null;
                String rel = iRel >= 0 ? c.getString(iRel) : null;
                if (data != null && isOwnFolder(data)) continue;
                if (rel != null && rel.contains("轻传")) continue;

                String key = data != null ? data : ("#id" + (iId >= 0 ? c.getLong(iId) : n));
                if (seen.contains(key)) continue;

                Entry e = new Entry();
                e.path = data;
                if (iId >= 0) {
                    e.uri = ContentUris.withAppendedId(base, c.getLong(iId)).toString();
                } else if (data != null) {
                    e.uri = Uri.fromFile(new File(data)).toString();
                } else {
                    continue;
                }
                e.name = name;
                e.size = size;
                e.time = iTime >= 0 ? c.getLong(iTime) * 1000L : 0;
                e.wx = isWxPath(data == null ? "" : data) || (rel != null && rel.contains("WeChat"));
                e.source = e.wx ? "微信" : "手机";
                seen.add(key);
                out.add(e);
            }
        } catch (Exception ignored) {
        } finally {
            if (c != null) c.close();
        }
    }

    private static boolean isOwnFolder(String path) {
        String p = path.toLowerCase(Locale.US);
        return p.contains("/download/轻传/") || p.contains("/download/轻传".toLowerCase(Locale.US));
    }

    /** 过滤掉缩略图、缓存、临时文件。 */
    private static boolean validName(String name) {
        String n = name.toLowerCase(Locale.US);
        if (n.endsWith(".tmp") || n.endsWith(".temp") || n.endsWith(".thumbs")) return false;
        if (n.startsWith("th_") && n.lastIndexOf('.') > 0) return false;
        return true;
    }

    public static boolean isWxPath(String path) {
        if (path == null) return false;
        String p = path.toLowerCase(Locale.US);
        return p.contains("weixin") || p.contains("wechat") || p.contains("micromsg")
                || p.contains("com.tencent.mm") || path.contains("微信");
    }

    /** 微信目录是否可读（用来提示用户为什么看不到缓存文件）。 */
    public static boolean wxDirReadable() {
        for (Dir d : dirs()) {
            String p = d.file.getAbsolutePath();
            if (!p.contains("com.tencent.mm")) continue;
            try {
                File[] fs = d.file.listFiles();
                if (fs != null && fs.length > 0) return true;
            } catch (Exception ignored) {
            }
        }
        return false;
    }

    /** 微信聊天原件是否可读（Android 11+ 一定为 false）。 */
    public static boolean chatCacheReadable() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) return false;
        File f = new File(Environment.getExternalStorageDirectory(),
                "Android/data/com.tencent.mm/MicroMsg/Download");
        try {
            File[] fs = f.listFiles();
            return fs != null && fs.length > 0;
        } catch (Exception e) {
            return false;
        }
    }
}
