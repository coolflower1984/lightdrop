package com.lightdrop.transfer.core;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** 本地配置与接收历史。 */
public class Prefs {

    /** 默认更新源（GitHub 用户名/仓库名），可在设置里改。 */
    public static final String DEFAULT_UPDATE_SOURCE = "coolflower1984/lightdrop";

    private static final String FILE = "lightdrop";
    private static final int MAX_HISTORY = 60;

    private final SharedPreferences sp;

    public Prefs(Context ctx) {
        sp = ctx.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    /** 从安装包读取版本名，发版时只需改 build.sh 的版本号。 */
    public static String versionName(Context ctx) {
        try {
            return ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "1.0.0";
        }
    }

    public static int versionCode(Context ctx) {
        try {
            return ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0).versionCode;
        } catch (Exception e) {
            return 1;
        }
    }

    public String deviceName() {
        String n = sp.getString("device_name", "");
        if (TextUtils.isEmpty(n)) {
            n = Build.MODEL == null ? "Android" : Build.MODEL;
        }
        return n;
    }

    public void setDeviceName(String name) {
        sp.edit().putString("device_name", name.trim()).apply();
    }

    public String updateSource() {
        return sp.getString("update_source", DEFAULT_UPDATE_SOURCE);
    }

    public void setUpdateSource(String s) {
        sp.edit().putString("update_source", s.trim()).apply();
    }

    public boolean autoUpdate() {
        return sp.getBoolean("auto_update", true);
    }

    public void setAutoUpdate(boolean b) {
        sp.edit().putBoolean("auto_update", b).apply();
    }

    public long lastUpdateCheck() {
        return sp.getLong("last_check", 0L);
    }

    public void setLastUpdateCheck(long t) {
        sp.edit().putLong("last_check", t).apply();
    }

    public boolean receiveAuto() {
        return sp.getBoolean("receive_auto", true);
    }

    public void setReceiveAuto(boolean b) {
        sp.edit().putBoolean("receive_auto", b).apply();
    }

    // ---- 上次发送过的设备：微信里点一下就能直接发 ----

    public String lastIp() {
        return sp.getString("last_ip", "");
    }

    public int lastPort() {
        return sp.getInt("last_port", 46000);
    }

    public String lastName() {
        return sp.getString("last_name", "");
    }

    public boolean hasLastDevice() {
        return !TextUtils.isEmpty(lastIp());
    }

    public void setLastDevice(String ip, int port, String name) {
        sp.edit()
                .putString("last_ip", ip == null ? "" : ip)
                .putInt("last_port", port)
                .putString("last_name", name == null ? "" : name)
                .apply();
    }

    public void clearLastDevice() {
        sp.edit().remove("last_ip").remove("last_port").remove("last_name").apply();
    }

    public static class Item {
        public String name;
        public String uri;
        public long size;
        public long time;
    }

    public void addReceived(String name, String uri, long size) {
        JSONArray arr = rawHistory();
        JSONObject o = new JSONObject();
        try {
            o.put("n", name);
            o.put("u", uri);
            o.put("s", size);
            o.put("t", System.currentTimeMillis());
        } catch (JSONException ignored) {
        }
        JSONArray out = new JSONArray();
        out.put(o);
        for (int i = 0; i < arr.length() && i < MAX_HISTORY - 1; i++) {
            out.put(arr.opt(i));
        }
        sp.edit().putString("history", out.toString()).apply();
    }

    public void clearHistory() {
        sp.edit().remove("history").apply();
    }

    public List<Item> history() {
        JSONArray arr = rawHistory();
        List<Item> list = new ArrayList<>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            Item it = new Item();
            it.name = o.optString("n");
            it.uri = o.optString("u");
            it.size = o.optLong("s");
            it.time = o.optLong("t");
            list.add(it);
        }
        return list;
    }

    private JSONArray rawHistory() {
        String s = sp.getString("history", "");
        if (TextUtils.isEmpty(s)) return new JSONArray();
        try {
            return new JSONArray(s);
        } catch (JSONException e) {
            return new JSONArray();
        }
    }
}
