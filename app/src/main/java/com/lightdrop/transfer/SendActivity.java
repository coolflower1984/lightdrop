package com.lightdrop.transfer;

import android.app.Activity;
import android.content.ClipData;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import com.lightdrop.transfer.core.Prefs;
import com.lightdrop.transfer.core.Ui;
import com.lightdrop.transfer.core.Util;
import com.lightdrop.transfer.net.DiscoveryClient;
import com.lightdrop.transfer.net.TransferClient;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class SendActivity extends Activity {

    private static final int REQ_PICK = 1001;

    private final List<TransferClient.Item> items = new ArrayList<>();
    private final Handler ui = new Handler(Looper.getMainLooper());

    private LinearLayout deviceContainer;
    private View emptyHint;
    private TextView tvCount, tvFiles, tvSearchStatus, tvProgressTitle, tvProgressDetail;
    private ProgressBar pbProgress;
    private View progressCard;
    private EditText etIp;

    private volatile boolean scanning;
    private volatile boolean busy;
    private int autoRetries;
    private long sentBytesAtStart;
    private long sendStartMs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_send);

        deviceContainer = findViewById(R.id.device_container);
        emptyHint = findViewById(R.id.empty_hint);
        tvCount = findViewById(R.id.tv_count);
        tvFiles = findViewById(R.id.tv_files);
        tvSearchStatus = findViewById(R.id.tv_search_status);
        tvProgressTitle = findViewById(R.id.tv_progress_title);
        tvProgressDetail = findViewById(R.id.tv_progress_detail);
        pbProgress = findViewById(R.id.pb_progress);
        progressCard = findViewById(R.id.progress_card);
        etIp = findViewById(R.id.et_ip);

        findViewById(R.id.btn_back).setOnClickListener(v -> finish());
        findViewById(R.id.pick_card).setOnClickListener(v -> pickFiles());
        findViewById(R.id.btn_refresh).setOnClickListener(v -> {
            autoRetries = 0;
            scan();
        });
        findViewById(R.id.btn_connect).setOnClickListener(v -> connectManual());

        refreshFileUi();
        handleIntent(getIntent());
        scan();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_PICK && resultCode == RESULT_OK && data != null) {
            List<Uri> uris = new ArrayList<>();
            ClipData clip = data.getClipData();
            if (clip != null) {
                for (int i = 0; i < clip.getItemCount(); i++) {
                    Uri u = clip.getItemAt(i).getUri();
                    if (u != null) uris.add(u);
                }
            } else if (data.getData() != null) {
                uris.add(data.getData());
            }
            addUris(uris);
        }
    }

    private void pickFiles() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        try {
            startActivityForResult(i, REQ_PICK);
        } catch (Exception e) {
            Toast.makeText(this, "没有可用的文件选择器", Toast.LENGTH_SHORT).show();
        }
    }

    private void handleIntent(Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        List<Uri> uris = new ArrayList<>();
        if (Intent.ACTION_SEND.equals(action)) {
            Uri u = intent.getParcelableExtra(Intent.EXTRA_STREAM);
            if (u != null) uris.add(u);
            if (uris.isEmpty()) {
                CharSequence text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT);
                if (!TextUtils.isEmpty(text)) {
                    uris.add(writeTextFile(text.toString()));
                }
            }
        } else if (Intent.ACTION_SEND_MULTIPLE.equals(action)) {
            ArrayList<Uri> list = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
            if (list != null) uris.addAll(list);
        }
        if (uris.isEmpty() && intent.getClipData() != null) {
            ClipData clip = intent.getClipData();
            for (int i = 0; i < clip.getItemCount(); i++) {
                if (clip.getItemAt(i).getUri() != null) uris.add(clip.getItemAt(i).getUri());
            }
        }
        if (!uris.isEmpty()) addUris(uris);
    }

    private Uri writeTextFile(String text) {
        try {
            File f = new File(getCacheDir(), "shared-text-" + System.currentTimeMillis() + ".txt");
            FileOutputStream fos = new FileOutputStream(f);
            fos.write(text.getBytes("UTF-8"));
            fos.close();
            return Uri.fromFile(f);
        } catch (Exception e) {
            return null;
        }
    }

    private void addUris(final List<Uri> uris) {
        tvFiles.setText("正在读取 " + uris.size() + " 个文件…");
        new Thread(() -> {
            final List<TransferClient.Item> added = new ArrayList<>();
            final List<String> failed = new ArrayList<>();
            for (Uri u : uris) {
                if (u == null) continue;
                try {
                    added.add(TransferClient.resolve(getApplicationContext(), u));
                } catch (Exception e) {
                    failed.add(e.getMessage() == null ? "读取失败" : e.getMessage());
                }
            }
            ui.post(() -> {
                items.addAll(added);
                refreshFileUi();
                if (!failed.isEmpty()) {
                    Toast.makeText(this, "有 " + failed.size() + " 个文件读取失败", Toast.LENGTH_SHORT).show();
                }
            });
        }, "ld-resolve").start();
    }

    private void refreshFileUi() {
        if (items.isEmpty()) {
            tvCount.setText("未选择");
            tvFiles.setText("点这里从微信或本地文件中选择，可多选");
            return;
        }
        tvCount.setText(items.size() + " 个 · " + Util.humanSize(TransferClient.totalSize(items)));
        StringBuilder sb = new StringBuilder();
        int show = Math.min(3, items.size());
        for (int i = 0; i < show; i++) {
            if (i > 0) sb.append("\n");
            sb.append(items.get(i).name);
        }
        if (items.size() > show) sb.append("\n…等共 ").append(items.size()).append(" 个文件");
        tvFiles.setText(sb.toString());
    }

    // ---------------- 设备发现 ----------------

    private void scan() {
        if (scanning || isFinishing() || isDestroyed()) return;
        scanning = true;
        tvSearchStatus.setText("正在搜索同一 Wi-Fi 下的设备…");
        new Thread(() -> {
            final List<DiscoveryClient.Device> list = DiscoveryClient.scan(1800);
            final String selfIp = Util.localIp();
            ui.post(() -> {
                scanning = false;
                if (isFinishing()) return;
                List<DiscoveryClient.Device> filtered = new ArrayList<>();
                for (DiscoveryClient.Device d : list) {
                    if (!d.ip.equals(selfIp)) filtered.add(d);
                }
                renderDevices(filtered);
            });
        }, "ld-scan").start();
    }

    private void renderDevices(List<DiscoveryClient.Device> list) {
        deviceContainer.removeAllViews();
        if (list.isEmpty()) {
            Ui.gone(emptyHint, false);
            if (autoRetries < 3) {
                autoRetries++;
                tvSearchStatus.setText("正在搜索同一 Wi-Fi 下的设备…");
                ui.postDelayed(this::scan, 2500);
            } else {
                tvSearchStatus.setText("没找到设备：确认对方已开启「接收文件」，或点右上角刷新");
            }
            return;
        }
        autoRetries = 0;
        Ui.gone(emptyHint, true);
        for (DiscoveryClient.Device d : list) {
            deviceContainer.addView(deviceRow(d));
        }
    }

    private View deviceRow(final DiscoveryClient.Device d) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackgroundResource(R.drawable.ripple_row);
        row.setPadding(Ui.dp(this, 14), Ui.dp(this, 14), Ui.dp(this, 14), Ui.dp(this, 14));
        row.setClickable(true);
        row.setFocusable(true);
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rp.bottomMargin = Ui.dp(this, 10);
        row.setLayoutParams(rp);

        String initial = d.name == null || d.name.isEmpty() ? "?" : d.name.substring(0, 1).toUpperCase(Locale.CHINA);
        TextView avatar = Ui.text(this, initial, 16, getColor(R.color.primary), true);
        avatar.setBackgroundResource(R.drawable.bg_chip_soft);
        avatar.setGravity(Gravity.CENTER);
        row.addView(avatar, new LinearLayout.LayoutParams(Ui.dp(this, 40), Ui.dp(this, 40)));

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(Ui.dp(this, 12), 0, 0, 0);
        col.addView(Ui.text(this, d.name, 15, getColor(R.color.text_primary), true));
        String sub = d.ip + (d.port == 46000 ? "" : ":" + d.port) + " · 点击发送";
        TextView subTv = Ui.text(this, sub, 12, getColor(R.color.text_secondary), false);
        subTv.setPadding(0, Ui.dp(this, 3), 0, 0);
        col.addView(subTv);
        row.addView(col, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        row.addView(Ui.text(this, "›", 22, getColor(R.color.text_tertiary), false));

        row.setOnClickListener(v -> sendTo(d.ip, d.port, d.name));
        return row;
    }

    private void connectManual() {
        String raw = etIp.getText().toString().trim();
        DiscoveryClient.Device d = DiscoveryClient.parse(raw);
        if (d == null) {
            Toast.makeText(this, "请输入正确的 IP 地址", Toast.LENGTH_SHORT).show();
            return;
        }
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(etIp.getWindowToken(), 0);
        sendTo(d.ip, d.port, d.name);
    }

    // ---------------- 发送 ----------------

    private void sendTo(final String ip, final int port, final String name) {
        if (busy) {
            Toast.makeText(this, "正在发送中，请稍候", Toast.LENGTH_SHORT).show();
            return;
        }
        if (items.isEmpty()) {
            Toast.makeText(this, "先选择要发送的文件", Toast.LENGTH_SHORT).show();
            pickFiles();
            return;
        }
        busy = true;
        final int count = items.size();
        final long totalBytes = TransferClient.totalSize(items);
        sentBytesAtStart = 0;
        sendStartMs = System.currentTimeMillis();

        Ui.gone(progressCard, false);
        tvProgressTitle.setText("正在发送到 " + name);
        pbProgress.setProgress(0);
        tvProgressDetail.setText("0%");

        final List<TransferClient.Item> snapshot = new ArrayList<>(items);

        new TransferClient(this, ip, port, snapshot, new TransferClient.Listener() {
            @Override
            public void onConnected() {
            }

            @Override
            public void onFileStart(int index, String fileName) {
                ui.post(() -> tvProgressTitle.setText("正在发送 " + (index + 1) + "/" + count + "：" + fileName));
            }

            @Override
            public void onProgress(int index, long done, long total) {
                final int permille = (int) ((index + (total > 0 ? (double) done / total : 1)) * 1000 / count);
                final String detail = String.format(Locale.US, "%d%% · %s / %s",
                        permille / 10, Util.humanSize(done), Util.humanSize(total));
                ui.post(() -> {
                    pbProgress.setProgress(permille);
                    tvProgressDetail.setText(detail);
                });
            }

            @Override
            public void onDone(final boolean ok, final String message) {
                final long ms = System.currentTimeMillis() - sendStartMs;
                ui.post(() -> {
                    busy = false;
                    pbProgress.setProgress(ok ? 1000 : 0);
                    if (ok) {
                        tvProgressTitle.setText("发送完成 · " + count + " 个文件");
                        tvProgressDetail.setText(Util.humanSize(totalBytes) + " · 用时 "
                                + String.format(Locale.US, "%.1f", ms / 1000.0) + " 秒"
                                + " · " + Util.speedText(totalBytes, ms));
                        Toast.makeText(SendActivity.this, "发送成功", Toast.LENGTH_SHORT).show();
                        items.clear();
                        refreshFileUi();
                        ui.postDelayed(() -> Ui.gone(progressCard, true), 2500);
                    } else {
                        tvProgressTitle.setText("发送失败");
                        tvProgressDetail.setText(message);
                        Toast.makeText(SendActivity.this, "发送失败：" + message, Toast.LENGTH_LONG).show();
                    }
                });
            }
        }).start();
    }
}
