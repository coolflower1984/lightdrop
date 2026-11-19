package com.lightdrop.transfer.core;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

/** 检查更新：支持 GitHub Release（用户名/仓库名）或自定义 version.json。 */
public class UpdateManager {

    public static class Info {
        public String version;
        public String url;
        public String notes;
    }

    public interface Callback {
        /** 有新版或查询结束都会回调；error 非空表示失败。 */
        void onResult(Info info, boolean hasNew, String error);
    }

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    public static void check(final Context ctx, final String source, final Callback cb) {
        new Thread(() -> {
            Info info = null;
            String error = null;
            try {
                if (TextUtils.isEmpty(source)) {
                    error = "还没配置更新源";
                } else {
                    info = fetch(source);
                    if (info == null) error = "没有找到可下载的安装包";
                }
            } catch (Exception e) {
                error = friendly(e);
            }
            final Info fInfo = info;
            final String fErr = error;
            final boolean hasNew = info != null
                    && compare(info.version, Prefs.versionName(ctx)) > 0;
            MAIN.post(() -> cb.onResult(fInfo, hasNew, fErr));
        }, "ld-update-check").start();
    }

    private static String friendly(Exception e) {
        String m = e.getMessage();
        if (m == null) m = e.getClass().getSimpleName();
        if (m.contains("Unable to resolve host")) return "网络不可用";
        return m;
    }

    private static Info fetch(String source) throws Exception {
        String s = source.trim();
        if (s.startsWith("http://") || s.startsWith("https://")) {
            JSONObject o = new JSONObject(httpGet(s));
            Info info = new Info();
            info.version = o.optString("version", o.optString("tag_name", ""));
            info.url = o.optString("url", o.optString("apk", ""));
            info.notes = o.optString("notes", o.optString("body", ""));
            if (TextUtils.isEmpty(info.version) || TextUtils.isEmpty(info.url)) return null;
            return info;
        }

        String repo = s.replace("https://github.com/", "").replace("http://github.com/", "");
        while (repo.endsWith("/")) repo = repo.substring(0, repo.length() - 1);
        JSONObject o = new JSONObject(httpGet("https://api.github.com/repos/" + repo + "/releases/latest"));
        Info info = new Info();
        info.version = o.optString("tag_name", "").replaceFirst("^[vV]", "");
        info.notes = o.optString("body", "");
        JSONArray assets = o.optJSONArray("assets");
        if (assets != null) {
            String fallback = null;
            for (int i = 0; i < assets.length(); i++) {
                JSONObject a = assets.optJSONObject(i);
                if (a == null) continue;
                String name = a.optString("name", "");
                String url = a.optString("browser_download_url", "");
                if (name.toLowerCase().endsWith(".apk")) {
                    info.url = url;
                    break;
                }
                if (fallback == null) fallback = url;
            }
            if (TextUtils.isEmpty(info.url)) info.url = fallback;
        }
        if (TextUtils.isEmpty(info.version) || TextUtils.isEmpty(info.url)) return null;
        return info;
    }

    private static String httpGet(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(12000);
        c.setReadTimeout(15000);
        c.setRequestProperty("User-Agent", "LightDrop-Updater");
        c.setRequestProperty("Accept", "application/json, application/vnd.github+json");
        try {
            int code = c.getResponseCode();
            if (code < 200 || code >= 300) {
                throw new Exception("服务器返回 " + code);
            }
            StringBuilder sb = new StringBuilder();
            BufferedReader br = new BufferedReader(new InputStreamReader(c.getInputStream(), "UTF-8"));
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
            br.close();
            return sb.toString();
        } finally {
            c.disconnect();
        }
    }

    /** 语义化版本比较：1.2.10 > 1.2.9。 */
    public static int compare(String a, String b) {
        if (a == null || b == null) return 0;
        String[] x = a.replaceAll("[^0-9.]", "").split("\\.");
        String[] y = b.replaceAll("[^0-9.]", "").split("\\.");
        int n = Math.max(x.length, y.length);
        for (int i = 0; i < n; i++) {
            int xi = i < x.length && !x[i].isEmpty() ? safeInt(x[i]) : 0;
            int yi = i < y.length && !y[i].isEmpty() ? safeInt(y[i]) : 0;
            if (xi != yi) return xi > yi ? 1 : -1;
        }
        return 0;
    }

    private static int safeInt(String s) {
        try {
            return Integer.parseInt(s);
        } catch (Exception e) {
            return 0;
        }
    }

    /** 弹出「发现新版本」对话框。 */
    public static void showUpdateDialog(final Activity act, final Info info) {
        if (act.isFinishing()) return;
        String notes = info.notes == null ? "" : info.notes.trim();
        if (notes.length() > 400) notes = notes.substring(0, 400) + "…";
        String msg = "新版本 v" + info.version + "（当前 v" + Prefs.versionName(act) + "）"
                + (notes.isEmpty() ? "" : "\n\n" + notes);
        new AlertDialog.Builder(act)
                .setTitle("发现新版本")
                .setMessage(msg)
                .setPositiveButton("立即更新", (d, w) -> downloadAndInstall(act, info))
                .setNegativeButton("以后再说", null)
                .show();
    }

    public static void downloadAndInstall(final Activity act, final Info info) {
        final LinearLayout box = new LinearLayout(act);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (20 * act.getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad, pad, pad);

        final TextView tv = new TextView(act);
        tv.setText("正在下载…");
        tv.setTextSize(13);
        tv.setTextColor(Color.parseColor("#77808F"));
        final ProgressBar pb = new ProgressBar(act, null, android.R.attr.progressBarStyleHorizontal);
        pb.setMax(1000);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = pad / 2;
        box.addView(pb, lp);
        box.addView(tv, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        tv.setGravity(Gravity.CENTER);

        final AlertDialog dialog = new AlertDialog.Builder(act)
                .setTitle("下载更新")
                .setView(box)
                .setNegativeButton("取消", null)
                .setCancelable(false)
                .create();
        dialog.show();

        new Thread(() -> {
            File out = null;
            String error = null;
            try {
                File dir = new File(act.getCacheDir(), "updates");
                if (!dir.exists() && !dir.mkdirs()) throw new Exception("缓存目录不可用");
                File[] olds = dir.listFiles();
                if (olds != null) {
                    for (File old : olds) {
                        //noinspection ResultOfMethodCallIgnored
                        if (old.isFile()) old.delete();
                    }
                }
                out = new File(dir, "lightdrop-" + info.version + ".apk");

                HttpURLConnection c = (HttpURLConnection) new URL(info.url).openConnection();
                c.setConnectTimeout(15000);
                c.setReadTimeout(30000);
                c.setRequestProperty("User-Agent", "LightDrop-Updater");
                c.setInstanceFollowRedirects(true);
                c.connect();
                int code = c.getResponseCode();
                if (code < 200 || code >= 300) throw new Exception("下载失败 " + code);
                long total = c.getContentLength();
                InputStream is = c.getInputStream();
                FileOutputStream fos = new FileOutputStream(out);
                byte[] buf = new byte[64 * 1024];
                long done = 0;
                int r;
                long last = 0;
                while ((r = is.read(buf)) > 0) {
                    fos.write(buf, 0, r);
                    done += r;
                    long now = System.currentTimeMillis();
                    if (now - last > 100) {
                        last = now;
                        final int pct = total > 0 ? (int) (done * 1000 / total) : -1;
                        final long dDone = done;
                        final long dTotal = total;
                        MAIN.post(() -> {
                            if (pct >= 0) pb.setProgress(pct);
                            tv.setText(Util.humanSize(dDone) + " / " + Util.humanSize(dTotal));
                        });
                    }
                }
                is.close();
                fos.close();
                if (total > 0 && done != total) throw new Exception("下载不完整");
            } catch (Exception e) {
                error = e.getMessage() == null ? "下载失败" : e.getMessage();
            }
            final String fErr = error;
            final File fOut = out;
            MAIN.post(() -> {
                dialog.dismiss();
                if (fErr != null) {
                    Toast.makeText(act, "更新失败：" + fErr, Toast.LENGTH_LONG).show();
                    return;
                }
                install(act, fOut);
            });
        }, "ld-update-download").start();
    }

    private static void install(Activity act, File apk) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                && !act.getPackageManager().canRequestPackageInstalls()) {
            Toast.makeText(act, "请先允许「轻传」安装未知来源应用", Toast.LENGTH_LONG).show();
            try {
                Intent i = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + act.getPackageName()));
                act.startActivity(i);
            } catch (Exception ignored) {
            }
            return;
        }
        Uri uri = ApkProvider.uriFor(apk);
        Intent i = new Intent(Intent.ACTION_VIEW);
        i.setDataAndType(uri, "application/vnd.android.package-archive");
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            act.startActivity(i);
        } catch (Exception e) {
            Toast.makeText(act, "无法调起安装器：" + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }
}
