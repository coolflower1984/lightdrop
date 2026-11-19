package com.lightdrop.transfer.core;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;

/** 一个正在写入的接收文件，负责创建目标、写入与收尾。 */
public class Incoming {

    public static final String FOLDER = "轻传";
    private static final String REL_PATH = Environment.DIRECTORY_DOWNLOADS + "/" + FOLDER + "/";

    public final String displayName;
    public final long size;

    private final Context ctx;
    private OutputStream os;
    private Uri mediaUri;
    private File legacyFile;
    private boolean done;
    private long written;

    private Incoming(Context ctx, String displayName, long size) {
        this.ctx = ctx.getApplicationContext();
        this.displayName = displayName;
        this.size = size;
    }

    public static Incoming open(Context ctx, String rawName, long size) throws IOException {
        String name = Util.safeName(rawName);
        Incoming in = new Incoming(ctx, name, size);
        in.prepare();
        return in;
    }

    private void prepare() throws IOException {
        ContentResolver cr = ctx.getContentResolver();
        String mime = Util.mimeOf(displayName);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            String unique = uniqueMediaName(cr, displayName);
            ContentValues cv = new ContentValues();
            cv.put(MediaStore.Downloads.DISPLAY_NAME, unique);
            cv.put(MediaStore.Downloads.MIME_TYPE, mime);
            cv.put(MediaStore.Downloads.RELATIVE_PATH, REL_PATH);
            cv.put(MediaStore.Downloads.IS_PENDING, 1);
            mediaUri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
            if (mediaUri == null) throw new IOException("无法创建文件");
            os = cr.openOutputStream(mediaUri);
            if (os == null) throw new IOException("无法写入文件");
        } else {
            File dir = new File(Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_DOWNLOADS), FOLDER);
            if (!dir.exists() && !dir.mkdirs()) {
                throw new IOException("无法创建目录 " + dir.getAbsolutePath());
            }
            File f = new File(dir, uniqueFile(dir, displayName));
            legacyFile = f;
            os = new FileOutputStream(f);
        }
    }

    public void write(byte[] buf, int off, int len) throws IOException {
        os.write(buf, off, len);
        written += len;
    }

    /** 收尾，返回可用于打开/分享的 uri 字符串。 */
    public String finish() throws IOException {
        if (done) return uriString();
        os.flush();
        os.close();
        os = null;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && mediaUri != null) {
            ContentValues cv = new ContentValues();
            cv.put(MediaStore.Downloads.IS_PENDING, 0);
            ctx.getContentResolver().update(mediaUri, cv, null, null);
        } else if (legacyFile != null) {
            // 让系统媒体库立即收录，文件管理器/相册里能看到
            try {
                android.media.MediaScannerConnection.scanFile(ctx,
                        new String[]{legacyFile.getAbsolutePath()}, null, null);
            } catch (Exception ignored) {
            }
        }
        done = true;
        return uriString();
    }

    public void abort() {
        try {
            if (os != null) os.close();
        } catch (IOException ignored) {
        }
        os = null;
        if (mediaUri != null) {
            try {
                ctx.getContentResolver().delete(mediaUri, null, null);
            } catch (Exception ignored) {
            }
        }
        if (legacyFile != null && legacyFile.exists()) {
            //noinspection ResultOfMethodCallIgnored
            legacyFile.delete();
        }
    }

    public long written() {
        return written;
    }

    @SuppressWarnings("deprecation")
    private String uriString() {
        if (mediaUri != null) return mediaUri.toString();
        if (legacyFile != null) return Uri.fromFile(legacyFile).toString();
        return "";
    }

    private static String uniqueMediaName(ContentResolver cr, String name) {
        String candidate = name;
        int i = 1;
        while (mediaExists(cr, candidate)) {
            candidate = withSuffix(name, i++);
        }
        return candidate;
    }

    private static boolean mediaExists(ContentResolver cr, String name) {
        android.database.Cursor c = null;
        try {
            c = cr.query(MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    new String[]{MediaStore.Downloads._ID},
                    MediaStore.Downloads.DISPLAY_NAME + "=? AND " + MediaStore.Downloads.RELATIVE_PATH + "=?",
                    new String[]{name, REL_PATH}, null);
            return c != null && c.getCount() > 0;
        } catch (Exception e) {
            return false;
        } finally {
            if (c != null) c.close();
        }
    }

    private static String uniqueFile(File dir, String name) {
        String candidate = name;
        int i = 1;
        while (new File(dir, candidate).exists()) {
            candidate = withSuffix(name, i++);
        }
        return candidate;
    }

    private static String withSuffix(String name, int index) {
        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            return name.substring(0, dot) + " (" + index + ")" + name.substring(dot);
        }
        return name + " (" + index + ")";
    }

    public static File folder() {
        return new File(Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOWNLOADS), FOLDER);
    }
}
