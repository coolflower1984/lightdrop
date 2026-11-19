package com.lightdrop.transfer;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import com.lightdrop.transfer.core.Incoming;
import com.lightdrop.transfer.core.Prefs;
import com.lightdrop.transfer.core.Receiver;
import com.lightdrop.transfer.core.Ui;
import com.lightdrop.transfer.core.Util;
import com.lightdrop.transfer.net.TransferServer;

import java.util.Locale;

public class ReceiveActivity extends Activity implements TransferServer.Listener {

    private static final int REQ_STORAGE = 2001;
    private static final int REQ_NOTIFICATION = 2002;

    private final Handler ui = new Handler(Looper.getMainLooper());

    private Prefs prefs;
    private Receiver receiver;
    private boolean syncing;
    private boolean wantStart;

    private Switch sw;
    private TextView tvIp, tvDevName, tvLiveTitle, tvLiveDetail, tvSwitchDesc;
    private ProgressBar pbLive;
    private View liveCard, liveProgress, tvEmpty;
    private LinearLayout fileContainer;

    private int sessionCount;
    private int doneCount;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_receive);
        prefs = new Prefs(this);
        receiver = Receiver.get(this);

        sw = findViewById(R.id.sw_receive);
        tvIp = findViewById(R.id.tv_ip);
        tvDevName = findViewById(R.id.tv_devname);
        tvLiveTitle = findViewById(R.id.tv_live_title);
        tvLiveDetail = findViewById(R.id.tv_live_detail);
        tvSwitchDesc = findViewById(R.id.tv_switch_desc);
        pbLive = findViewById(R.id.pb_live);
        liveCard = findViewById(R.id.live_card);
        liveProgress = findViewById(R.id.live_progress);
        tvEmpty = findViewById(R.id.tv_empty);
        fileContainer = findViewById(R.id.file_container);

        findViewById(R.id.btn_back).setOnClickListener(v -> finish());
        findViewById(R.id.btn_copy).setOnClickListener(v -> copyAddress());
        findViewById(R.id.btn_open_folder).setOnClickListener(v -> openFolder());
        findViewById(R.id.btn_selftest).setOnClickListener(v -> selfTest());

        sw.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (syncing) return;
            prefs.setReceiveAuto(isChecked);
            if (isChecked) tryStart();
            else stopReceiving();
        });

        renderHistory();
        syncFromReceiver();
        if (!receiver.isRunning() && prefs.receiveAuto()) {
            syncing = true;
            sw.setChecked(true);
            syncing = false;
            tryStart();
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        receiver.addListener(this);
        syncFromReceiver();
    }

    @Override
    protected void onStop() {
        receiver.removeListener(this);
        super.onStop();
    }

    // ---------------- 开关与权限 ----------------

    private void tryStart() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
                && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            wantStart = true;
            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_STORAGE);
            return;
        }
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, REQ_NOTIFICATION);
        }
        startReceiving();
    }

    private void startReceiving() {
        tvSwitchDesc.setText("正在启动接收服务…");
        Ui.gone(liveCard, false);
        tvIp.setText(Util.localIp());
        tvDevName.setText("设备名：" + prefs.deviceName());
        ReceiveService.start(this);
        ui.postDelayed(this::syncFromReceiver, 900);
    }

    /** 显示「IP:端口」，网页上传也需要带上端口。 */
    private String addressText(int port) {
        java.util.List<String> ips = Util.localIps();
        if (ips.isEmpty()) return "未连接网络";
        String ip = ips.get(0);
        if (port <= 0) return ip;
        return ip + ":" + port;
    }

    private void stopReceiving() {
        ReceiveService.stop(this);
        Ui.gone(liveCard, true);
        Ui.gone(liveProgress, true);
        tvSwitchDesc.setText("关闭后其他设备无法发现本机");
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_STORAGE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                if (wantStart) startReceiving();
            } else {
                syncing = true;
                sw.setChecked(false);
                syncing = false;
                Toast.makeText(this, "没有存储权限无法保存文件", Toast.LENGTH_LONG).show();
            }
        } else if (requestCode == REQ_NOTIFICATION) {
            if (grantResults.length > 0 && grantResults[0] != PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "未授予通知权限：后台接收仍可用，但看不到状态通知",
                        Toast.LENGTH_LONG).show();
            }
        }
    }

    private void syncFromReceiver() {
        boolean running = receiver.isRunning();
        if (sw.isChecked() != running) {
            syncing = true;
            sw.setChecked(running);
            syncing = false;
        }
        if (running) {
            int port = receiver.port();
            String addr = addressText(port);
            tvIp.setText(addr);
            tvDevName.setText("设备名：" + receiver.deviceName()
                    + (port > 0 ? " · 浏览器打开 http://" + addr + " 也能上传" : ""));
            Ui.gone(liveCard, false);
            tvSwitchDesc.setText("已在后台接收，可锁屏或切到其他应用");
        } else if (sw.isChecked()) {
            tvSwitchDesc.setText("正在启动接收服务…");
        } else {
            Ui.gone(liveCard, true);
            tvSwitchDesc.setText("关闭后其他设备无法发现本机");
        }
    }

    private void copyAddress() {
        String addr = tvIp.getText().toString();
        ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("轻传地址", addr));
            Toast.makeText(this, "已复制 " + addr, Toast.LENGTH_SHORT).show();
        }
    }

    private void openFolder() {
        try {
            Uri uri = Uri.parse("content://com.android.externalstorage.documents/document/primary%3A"
                    + Uri.encode("Download/" + Incoming.FOLDER));
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(uri, "vnd.android.document/directory");
            startActivity(i);
        } catch (Exception e) {
            Toast.makeText(this, "保存位置：Download/" + Incoming.FOLDER, Toast.LENGTH_LONG).show();
        }
    }

    /** 本机自检：直接连自己一次，确认协议链路真的通了。 */
    private void selfTest() {
        final int port = receiver.port();
        if (port <= 0) {
            Toast.makeText(this, "接收服务还没启动，先把上面的开关打开", Toast.LENGTH_LONG).show();
            return;
        }
        Toast.makeText(this, "正在自检…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            final com.lightdrop.transfer.net.DiscoveryClient.Device d =
                    com.lightdrop.transfer.net.DiscoveryClient.probe("127.0.0.1", port, 2000);
            ui.post(() -> {
                if (isFinishing()) return;
                StringBuilder sb = new StringBuilder();
                sb.append("接收服务：").append(d != null ? "正常，已验证到「" + d.name + "」" : "无响应");
                sb.append("\n监听端口：").append(port);
                sb.append("\n本机地址：").append(android.text.TextUtils.join(" / ", Util.localIps()));
                sb.append("\n\n如果这里显示「正常」，说明平板这边没问题，");
                sb.append("连不上就是两台设备网络不通：检查是否同一个 Wi-Fi、路由器有没有开「AP 隔离」、");
                sb.append("或者改用手机浏览器打开 http://").append(Util.localIp()).append(':').append(port);
                new android.app.AlertDialog.Builder(this)
                        .setTitle("自检结果")
                        .setMessage(sb.toString())
                        .setPositiveButton("知道了", null)
                        .show();
            });
        }, "ld-selftest").start();
    }

    // ---------------- 服务事件（非 UI 线程） ----------------

    @Override
    public void onListening(final int port, final String deviceName) {
        ui.post(() -> {
            String addr = addressText(port);
            tvIp.setText(addr);
            tvDevName.setText("设备名：" + deviceName + " · 浏览器打开 http://" + addr + " 也能上传");
            Ui.gone(liveCard, false);
            tvSwitchDesc.setText("已在后台接收，可锁屏或切到其他应用");
            syncing = true;
            sw.setChecked(true);
            syncing = false;
        });
    }

    @Override
    public void onListenFailed(final String message) {
        ui.post(() -> {
            syncing = true;
            sw.setChecked(false);
            syncing = false;
            Ui.gone(liveCard, true);
            tvSwitchDesc.setText("开启失败，点开关重试");
            Toast.makeText(this, "无法监听端口：" + message
                    + "。请关闭其他占用同一端口的应用后重试", Toast.LENGTH_LONG).show();
        });
    }

    @Override
    public void onSessionStart(final int count) {
        sessionCount = count;
        doneCount = 0;
        ui.post(() -> {
            Ui.gone(liveProgress, false);
            tvLiveTitle.setText("正在接收 " + count + " 个文件…");
            tvLiveDetail.setText("0%");
            pbLive.setProgress(0);
        });
    }

    @Override
    public void onFileStart(final int index, final String name, long size) {
        ui.post(() -> tvLiveTitle.setText("正在接收 " + (index + 1) + "/" + sessionCount + "：" + name));
    }

    @Override
    public void onProgress(int index, long done, long total) {
        final int permille = (int) ((index + (total > 0 ? (double) done / total : 1)) * 1000
                / Math.max(1, sessionCount));
        final String detail = String.format(Locale.US, "%d%% · %s / %s",
                permille / 10, Util.humanSize(done), Util.humanSize(total));
        ui.post(() -> {
            pbLive.setProgress(permille);
            tvLiveDetail.setText(detail);
        });
    }

    @Override
    public void onFileDone(int index, final String name, final String uri, final long size) {
        doneCount++;
        prefs.addReceived(name, uri, size);
        ui.post(() -> {
            renderHistory();
            tvEmpty.setVisibility(View.GONE);
        });
    }

    @Override
    public void onSessionEnd(final boolean ok, final String message) {
        ui.post(() -> {
            if (ok) {
                tvLiveTitle.setText("接收完成 · " + doneCount + " 个文件");
                pbLive.setProgress(1000);
                Toast.makeText(this, "已收到 " + doneCount + " 个文件", Toast.LENGTH_SHORT).show();
            } else {
                tvLiveTitle.setText("传输中断");
                tvLiveDetail.setText(message);
            }
            ui.postDelayed(() -> Ui.gone(liveProgress, true), 2500);
        });
    }

    // ---------------- 历史列表 ----------------

    private void renderHistory() {
        fileContainer.removeAllViews();
        java.util.List<Prefs.Item> list = prefs.history();
        if (list.isEmpty()) {
            tvEmpty.setVisibility(View.VISIBLE);
            return;
        }
        tvEmpty.setVisibility(View.GONE);
        int limit = Math.min(list.size(), 30);
        for (int i = 0; i < limit; i++) {
            fileContainer.addView(fileRow(list.get(i)));
        }
    }

    private View fileRow(final Prefs.Item item) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackgroundResource(R.drawable.ripple_row);
        row.setPadding(Ui.dp(this, 14), Ui.dp(this, 12), Ui.dp(this, 14), Ui.dp(this, 12));
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rp.bottomMargin = Ui.dp(this, 8);
        row.setLayoutParams(rp);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView name = Ui.text(this, item.name, 14, getColor(R.color.text_primary), false);
        name.setMaxLines(1);
        name.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        col.addView(name);
        TextView sub = Ui.text(this, Util.humanSize(item.size) + " · " + Util.timeAgo(item.time),
                11, getColor(R.color.text_tertiary), false);
        sub.setPadding(0, Ui.dp(this, 3), 0, 0);
        col.addView(sub);
        row.addView(col, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView open = Ui.text(this, "打开", 13, getColor(R.color.primary), true);
        open.setPadding(Ui.dp(this, 10), Ui.dp(this, 6), Ui.dp(this, 10), Ui.dp(this, 6));
        row.addView(open);

        View.OnClickListener openListener = v -> openItem(item);
        open.setOnClickListener(openListener);
        row.setOnClickListener(openListener);
        return row;
    }

    private void openItem(final Prefs.Item item) {
        if (item.uri == null || item.uri.isEmpty()) {
            Toast.makeText(this, "文件地址失效", Toast.LENGTH_SHORT).show();
            return;
        }
        final Uri uri = Uri.parse(item.uri);
        if ("file".equals(uri.getScheme())) {
            MediaScannerConnection.scanFile(this, new String[]{uri.getPath()}, null,
                    (path, contentUri) -> {
                        if (contentUri != null) launchView(contentUri, item.name);
                        else ui.post(() -> Toast.makeText(this, "文件已不存在", Toast.LENGTH_SHORT).show());
                    });
        } else {
            launchView(uri, item.name);
        }
    }

    private void launchView(Uri uri, String name) {
        Intent i = new Intent(Intent.ACTION_VIEW);
        i.setDataAndType(uri, Util.mimeOf(name));
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivity(i);
        } catch (Exception e) {
            Toast.makeText(this, "没有可以打开该文件的应用", Toast.LENGTH_SHORT).show();
        }
    }
}
