package com.lightdrop.transfer.core;

import android.Manifest;
import android.content.ContentResolver;
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
 * 查找微信下载的文件。
 *
 * 微信从不走系统分享，所以「长按文件 → 分享到轻传」不可能实现。
 * 可行的落点是：微信里把文件「保存到手机」后，这里能立刻看到并一键发送。
 * 微信的落地目录分散在 Download/WeiXin、tencent/MicroMsg、Android/data/com.tencent.mm 三处，
 * 后两者需要「所有文件访问」权限。
 */
public class WxFiles {

    public static class Entry {
        public String path;
        public String name;
        public long size;
        public long time;
        public boolean wx;

        public Uri uri() {
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

    /** 候选目录，按优先级排列。 */
    public static List<File> dirs() {
        List<File> list = new ArrayList<>();
        File ext = Environment.getExternalStorageDirectory();
        add(list, new File(ext, "Download/WeiXin"));
        add(list, new File(ext, "Download/微信"));
        add(list, new File(ext, "Download/Weixin"));
        add(list, new File(ext, "Download/WeChat"));
        add(list, new File(ext, "tencent/MicroMsg/Download"));
        add(list, new File(ext, "tencent/MicroMsg/WeiXin"));
        add(list, new File(ext, "Android/data/com.tencent.mm/MicroMsg/Download"));
        add(list, new File(ext, "Android/data/com.tencent.mm/MicroMsg/WeiXin"));
        add(list, new File(ext, "Android/data/com.tencent.mm/MicroMsg/xlog"));
        return list;
    }

    private static void add(List<File> list, File f) {
        if (f.isDirectory()) list.add(f);
    }

    /**
     * 最近的文件，按时间倒序。
     *
     * @param days 只看最近多少天
     * @param limit 上限
     */
    public static List<Entry> recent(Context ctx, int days, int limit) {
        long since = System.currentTimeMillis() - days * 86_400_000L;
        List<Entry> all = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        // 1. 目录直读（能读到微信自己的目录）
        for (File dir : dirs()) {
            collect(dir, 2, since, all, seen);
        }

        // 2. MediaStore 补充（下载、相册、文档等）
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

    private static void collect(File dir, int depth, long since,
                                List<Entry> out, Set<String> seen) {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f.isDirectory()) {
                if (depth > 0) collect(f, depth - 1, since, out, seen);
                continue;
            }
            String path = f.getAbsolutePath();
            if (seen.contains(path)) continue;
            long size = f.length();
            if (size <= 0) continue;
            long t = f.lastModified();
            if (t < since) continue;
            Entry e = new Entry();
            e.path = path;
            e.name = f.getName();
            e.size = size;
            e.time = t;
            e.wx = isWxPath(path);
            seen.add(path);
            out.add(e);
        }
    }

    private static void queryMediaStore(Context ctx, long since, List<Entry> out, Set<String> seen) {
        ContentResolver cr = ctx.getContentResolver();
        Uri base = MediaStore.Files.getContentUri("external");
        String[] proj = {
                MediaStore.Files.FileColumns.DISPLAY_NAME,
                MediaStore.Files.FileColumns.SIZE,
                MediaStore.Files.FileColumns.DATE_MODIFIED,
                MediaStore.Files.FileColumns.DATA
        };
        String sel = MediaStore.Files.FileColumns.DATE_MODIFIED + " > ?";
        String[] args = {String.valueOf(since / 1000L)};
        Cursor c = null;
        try {
            c = cr.query(base, proj, sel, args,
                    MediaStore.Files.FileColumns.DATE_MODIFIED + " DESC");
            if (c == null) return;
            int iName = c.getColumnIndex(MediaStore.Files.FileColumns.DISPLAY_NAME);
            int iSize = c.getColumnIndex(MediaStore.Files.FileColumns.SIZE);
            int iTime = c.getColumnIndex(MediaStore.Files.FileColumns.DATE_MODIFIED);
            int iData = c.getColumnIndex(MediaStore.Files.FileColumns.DATA);
            int n = 0;
            while (c.moveToNext() && n < 400) {
                n++;
                if (iData < 0) continue;
                String path = c.getString(iData);
                if (TextUtils.isEmpty(path)) continue;
                if (!validPath(path) || seen.contains(path)) continue;
                File f = new File(path);
                if (!f.isFile()) continue;
                long size = iSize >= 0 ? c.getLong(iSize) : f.length();
                if (size <= 0) continue;
                Entry e = new Entry();
                e.path = path;
                e.name = iName >= 0 ? c.getString(iName) : f.getName();
                if (TextUtils.isEmpty(e.name)) e.name = f.getName();
                e.size = size;
                e.time = (iTime >= 0 ? c.getLong(iTime) * 1000L : f.lastModified());
                e.wx = isWxPath(path);
                seen.add(path);
                out.add(e);
            }
        } catch (Exception ignored) {
        } finally {
            if (c != null) c.close();
        }
    }

    /** 过滤掉缩略图、缓存、我们自己的接收目录。 */
    private static boolean validPath(String path) {
        String p = path.toLowerCase(Locale.US);
        if (p.contains("/cache/") || p.contains("/.thumbnails/")) return false;
        if (p.contains("/download/轻传/")) return false;
        if (p.endsWith(".tmp") || p.endsWith(".temp")) return false;
        return true;
    }

    public static boolean isWxPath(String path) {
        String p = path.toLowerCase(Locale.US);
        return p.contains("weixin") || p.contains("wechat") || p.contains("micromsg")
                || p.contains("tencent") || path.contains("微信");
    }

    /** 微信目录是否可读（用来提示用户开权限）。 */
    public static boolean wxDirReadable() {
        for (File d : dirs()) {
            String p = d.getAbsolutePath();
            if (!p.contains("com.tencent.mm")) continue;
            if (d.canRead() && d.listFiles() != null) return true;
        }
        return false;
    }
}
