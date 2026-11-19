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
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class SendActivity extends Activity {

    private static final int REQ_PICK_WX = 1001;
    private static final int REQ_PICK_SYS = 1002;

    private final List<TransferClient.Item> items = new ArrayList<>();
    private final Handler ui = new Handler(Looper.getMainLooper());

    private LinearLayout deviceContainer;
    private View emptyHint, quickCard, progressCard;
    private TextView tvCount, tvFiles, tvSearchStatus, tvProgressTitle, tvProgressDetail;
    private TextView tvQuickTitle, tvQuickSub;
    private ProgressBar pbProgress;
    private EditText etIp;

    private Prefs prefs;
    private volatile boolean scanning;
    private volatile boolean busy;
    private int autoRetries;
    private long sendStartMs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_send);
        prefs = new Prefs(this);

        deviceContainer = findViewById(R.id.device_container);
        emptyHint = findViewById(R.id.empty_hint);
        quickCard = findViewById(R.id.quick_card);
        progressCard = findViewById(R.id.progress_card);
        tvCount = findViewById(R.id.tv_count);
        tvFiles = findViewById(R.id.tv_files);
        tvSearchStatus = findViewById(R.id.tv_search_status);
        tvProgressTitle = findViewById(R.id.tv_progress_title);
        tvProgressDetail = findViewById(R.id.tv_progress_detail);
        tvQuickTitle = findViewById(R.id.tv_quick_title);
        tvQuickSub = findViewById(R.id.tv_quick_sub);
        pbProgress = findViewById(R.id.pb_progress);
        etIp = findViewById(R.id.et_ip);

        findViewById(R.id.btn_back).setOnClickListener(v -> finish());
        findViewById(R.id.pick_card).setOnClickListener(v -> pickFromWx());
        findViewById(R.id.btn_refresh).setOnClickListener(v -> {
            autoRetries = 0;
            scan();
        });
        findViewById(R.id.btn_connect).setOnClickListener(v -> connectManual());
        quickCard.setOnClickListener(v -> {
            if (!prefs.hasLastDevice()) return;
            quickSend();
        });

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
        if (resultCode != RESULT_OK || data == null) return;
        if (requestCode == REQ_PICK_WX) {
            String[] paths = data.getStringArrayExtra(WxPickActivity.EXTRA_PATHS);
            if (paths == null) return;
            List<Uri> uris = new ArrayList<>();
            for (String p : paths) {
                if (p == null) continue;
                if (p.startsWith("content://") || p.startsWith("file://")) {
                    uris.add(Uri.parse(p));
                } else {
                    uris.add(Uri.fromFile(new File(p)));
                }
            }
            addUris(uris);
        } else if (requestCode == REQ_PICK_SYS) {
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

    private void pickFromWx() {
        startActivityForResult(new Intent(this, WxPickActivity.class), REQ_PICK_WX);
    }

    private void pickSystem() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        try {
            startActivityForResult(i, REQ_PICK_SYS);
        } catch (Exception e) {
            Toast.makeText(this, "没有可用的文件选择器", Toast.LENGTH_SHORT).show();
        }
    }

    // ---------------- 外部分享 / 打开 ----------------

    private void handleIntent(Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        if (action == null) return;

        List<Uri> uris = new ArrayList<>();
        if (Intent.ACTION_SEND.equals(action)) {
            Uri u = intent.getParcelableExtra(Intent.EXTRA_STREAM);
            if (u != null) uris.add(u);
            if (uris.isEmpty()) {
                CharSequence text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT);
                if (!TextUtils.isEmpty(text)) {
                    Uri t = writeTextFile(text.toString());
                    if (t != null) uris.add(t);
                }
            }
        } else if (Intent.ACTION_SEND_MULTIPLE.equals(action)) {
            ArrayList<Uri> list = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
            if (list != null) uris.addAll(list);
        } else if (Intent.ACTION_VIEW.equals(action)) {
            Uri u = intent.getData();
            if (u != null) uris.add(u);
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
        if (uris.isEmpty()) return;
        tvFiles.setText("正在读取 " + uris.size() + " 个文件…");
        new Thread(() -> {
            final List<TransferClient.Item> added = new ArrayList<>();
            int failed = 0;
            Set<String> exist = new HashSet<>();
            for (TransferClient.Item it : items) exist.add(it.name + "|" + it.size);
            for (Uri u : uris) {
                if (u == null) continue;
                try {
                    TransferClient.Item it = TransferClient.resolve(getApplicationContext(), u);
                    if (exist.contains(it.name + "|" + it.size)) continue;
                    added.add(it);
                } catch (Exception e) {
                    failed++;
                }
            }
            final int failCount = failed;
            ui.post(() -> {
                items.addAll(added);
                refreshFileUi();
                if (failCount > 0) {
                    Toast.makeText(this, "有 " + failCount + " 个文件读取失败", Toast.LENGTH_SHORT).show();
                }
                if (!added.isEmpty() && prefs.hasLastDevice()) {
                    // 从微信「用其他应用打开」进来时，直接给一个一键发送入口
                    showQuickCard();
                }
            });
        }, "ld-resolve").start();
    }

    private void refreshFileUi() {
        if (items.isEmpty()) {
            tvCount.setText("未选择");
            tvFiles.setText("点这里选文件：微信里刚保存的、下载的、手机里的都在");
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

    private void showQuickCard() {
        if (!prefs.hasLastDevice() || items.isEmpty()) {
            Ui.gone(quickCard, true);
            return;
        }
        String nm = prefs.lastName();
        if (TextUtils.isEmpty(nm)) nm = prefs.lastIp();
        tvQuickTitle.setText("发送到 " + nm);
        tvQuickSub.setText("上次连接的设备 · " + prefs.lastIp() + " · 点一下直接发");
        Ui.gone(quickCard, false);
    }

    // ---------------- 设备发现 ----------------

    private void scan() {
        if (scanning || isFinishing() || isDestroyed()) return;
        scanning = true;
        tvSearchStatus.setText("正在搜索同一 Wi-Fi 下的设备…");
        final List<String> selfIps = Util.localIps();
        new Thread(() -> {
            List<DiscoveryClient.Device> list = DiscoveryClient.scan(getApplicationContext(), 1500);
            if (list.isEmpty()) {
                ui.post(() -> {
                    if (!isFinishing()) {
                        tvSearchStatus.setText("广播没响应，正在逐个扫描 "
                                + (selfIps.isEmpty() ? "本机" : selfIps.get(0)) + " 所在网段…");
                    }
                });
                list = DiscoveryClient.sweep(selfIps, 9000);
            }
            final List<DiscoveryClient.Device> result = list;
            ui.post(() -> {
                scanning = false;
                if (isFinishing()) return;
                List<DiscoveryClient.Device> filtered = new ArrayList<>();
                for (DiscoveryClient.Device d : result) {
                    if (!selfIps.contains(d.ip)) filtered.add(d);
                }
                renderDevices(filtered);
            });
        }, "ld-scan").start();
    }

    private void renderDevices(List<DiscoveryClient.Device> list) {
        deviceContainer.removeAllViews();
        if (list.isEmpty()) {
            Ui.gone(emptyHint, false);
            if (autoRetries < 2) {
                autoRetries++;
                tvSearchStatus.setText("正在搜索同一 Wi-Fi 下的设备…");
                ui.postDelayed(this::scan, 2000);
            } else {
                String me = Util.localIp();
                tvSearchStatus.setText("没找到设备 · 本机 " + me
                        + "。请确认：①两台设备连的是同一个 Wi-Fi（不是访客网络 / 蜂窝）"
                        + "②平板上「接收文件」开关是开着的，页面会显示一串地址"
                        + "③还不行就在下面直接输平板显示的地址");
            }
            return;
        }
        autoRetries = 0;
        Ui.gone(emptyHint, true);
        tvSearchStatus.setText("找到 " + list.size() + " 台设备 · 点一下就开始发");
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

        String initial = d.name == null || d.name.isEmpty() ? "?"
                : d.name.substring(0, 1).toUpperCase(Locale.CHINA);
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
        final String raw = etIp.getText().toString().trim();
        final DiscoveryClient.Device parsed = DiscoveryClient.parse(raw);
        if (parsed == null) {
            Toast.makeText(this, "请输入平板页面上显示的地址，如 192.168.1.23", Toast.LENGTH_SHORT).show();
            return;
        }
        if (items.isEmpty()) {
            Toast.makeText(this, "先选择要发送的文件", Toast.LENGTH_SHORT).show();
            pickFromWx();
            return;
        }
        if (busy) {
            Toast.makeText(this, "正在发送中，请稍候", Toast.LENGTH_SHORT).show();
            return;
        }
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(etIp.getWindowToken(), 0);

        // 先握手确认这个地址上真的有轻传在跑，顺便拿到真实端口。
        // 直接盲连的话，端口不对只会得到「超时」这种没用的提示。
        tvSearchStatus.setText("正在连接 " + parsed.ip + " …");
        new Thread(() -> {
            final DiscoveryClient.Device found = DiscoveryClient.probeAny(parsed.ip, parsed.port);
            ui.post(() -> {
                if (isFinishing()) return;
                if (found == null) {
                    tvSearchStatus.setText("连不上 " + parsed.ip
                            + "：这个地址上没有轻传在监听。检查地址是否输错、平板是否已开启接收");
                    Toast.makeText(SendActivity.this,
                            "连不上 " + parsed.ip + "，请确认平板上的地址和开关",
                            Toast.LENGTH_LONG).show();
                } else {
                    tvSearchStatus.setText("已找到 " + found.label() + " · " + found.ip + ":" + found.port);
                    sendTo(found.ip, found.port, found.name);
                }
            });
        }, "ld-probe").start();
    }

    /**
     * 走「上次设备」的快捷通道。
     *
     * 平板重连 Wi-Fi 后 IP 通常会变，直接拿存下来的地址连，只会得到一个
     * 莫名其妙的「连不上」。所以先探一下，探不到就自动重新搜索。
     */
    private void quickSend() {
        if (busy) {
            Toast.makeText(this, "正在发送中，请稍候", Toast.LENGTH_SHORT).show();
            return;
        }
        if (items.isEmpty()) {
            Toast.makeText(this, "先选择要发送的文件", Toast.LENGTH_SHORT).show();
            pickFromWx();
            return;
        }
        final String ip = prefs.lastIp();
        final int port = prefs.lastPort();
        tvSearchStatus.setText("正在确认 " + ip + " …");
        new Thread(() -> {
            final DiscoveryClient.Device found = DiscoveryClient.probeAny(ip, port);
            ui.post(() -> {
                if (isFinishing()) return;
                if (found != null) {
                    sendTo(found.ip, found.port, found.name);
                } else {
                    tvSearchStatus.setText("上次的 " + ip + " 已经失效（IP 变了），正在重新搜索…");
                    autoRetries = 0;
                    scan();
                }
            });
        }, "ld-quick").start();
    }

    // ---------------- 发送 ----------------

    private void sendTo(final String ip, final int port, final String name) {
        if (busy) {
            Toast.makeText(this, "正在发送中，请稍候", Toast.LENGTH_SHORT).show();
            return;
        }
        if (items.isEmpty()) {
            Toast.makeText(this, "先选择要发送的文件", Toast.LENGTH_SHORT).show();
            pickFromWx();
            return;
        }
        busy = true;
        final int count = items.size();
        final long totalBytes = TransferClient.totalSize(items);
        sendStartMs = System.currentTimeMillis();

        Ui.gone(progressCard, false);
        tvProgressTitle.setText("正在连接 " + (TextUtils.isEmpty(name) ? ip : name) + "…");
        pbProgress.setProgress(0);
        tvProgressDetail.setText("0%");

        final List<TransferClient.Item> snapshot = new ArrayList<>(items);

        new TransferClient(this, ip, port, snapshot, new TransferClient.Listener() {
            @Override
            public void onConnected() {
                ui.post(() -> tvProgressTitle.setText("正在发送 " + count + " 个文件…"));
            }

            @Override
            public void onFileStart(int index, String fileName) {
                ui.post(() -> tvProgressTitle.setText(
                        "正在发送 " + (index + 1) + "/" + count + "：" + fileName));
            }

            @Override
            public void onProgress(int index, long done, long total) {
                final int permille = (int) ((index + (total > 0 ? (double) done / total : 1))
                        * 1000 / Math.max(1, count));
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
                    if (ok) {
                        prefs.setLastDevice(ip, port, name);
                        pbProgress.setProgress(1000);
                        tvProgressTitle.setText("发送完成 · " + count + " 个文件");
                        tvProgressDetail.setText(Util.humanSize(totalBytes) + " · 用时 "
                                + String.format(Locale.US, "%.1f", ms / 1000.0) + " 秒"
                                + " · " + Util.speedText(totalBytes, ms));
                        Toast.makeText(SendActivity.this, "发送成功", Toast.LENGTH_SHORT).show();
                        items.clear();
                        refreshFileUi();
                        Ui.gone(quickCard, true);
                        ui.postDelayed(() -> Ui.gone(progressCard, true), 3000);
                    } else {
                        pbProgress.setProgress(0);
                        tvProgressTitle.setText("发送失败");
                        tvProgressDetail.setText(message);
                        Toast.makeText(SendActivity.this, message, Toast.LENGTH_LONG).show();
                    }
                });
            }
        }).start();
    }

    @Override
    protected void onResume() {
        super.onResume();
        showQuickCard();
    }
}
