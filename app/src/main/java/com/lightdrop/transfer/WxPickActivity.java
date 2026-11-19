package com.lightdrop.transfer;

import android.app.Activity;
import android.content.ClipData;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.lightdrop.transfer.core.Ui;
import com.lightdrop.transfer.core.Util;
import com.lightdrop.transfer.core.WxFiles;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 微信文件入口。
 *
 * 微信不提供系统分享，所以这里直接从微信自己的落地目录和 MediaStore 里
 * 找最近文件，用户在微信里「保存到手机」后回来一键发送即可。
 */
public class WxPickActivity extends Activity {

    public static final String EXTRA_PATHS = "paths";

    private static final int REQ_PERM = 3101;
    private static final int REQ_BROWSE = 3102;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final List<WxFiles.Entry> all = new ArrayList<>();
    private final Set<String> selected = new LinkedHashSet<>();

    private LinearLayout listContainer;
    private View emptyBox, permCard;
    private TextView tvStatus, tvSelected, tabAll, tabWx;
    private boolean wxOnly;
    private volatile boolean loading;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_pick);

        listContainer = findViewById(R.id.list_container);
        emptyBox = findViewById(R.id.empty_box);
        permCard = findViewById(R.id.perm_card);
        tvStatus = findViewById(R.id.tv_status);
        tvSelected = findViewById(R.id.tv_selected);
        tabAll = findViewById(R.id.tab_all);
        tabWx = findViewById(R.id.tab_wx);

        findViewById(R.id.btn_back).setOnClickListener(v -> finish());
        findViewById(R.id.btn_browse).setOnClickListener(v -> browse());
        findViewById(R.id.btn_perm).setOnClickListener(v ->
                WxFiles.requestPermission(this, REQ_PERM));
        findViewById(R.id.btn_rescan).setOnClickListener(v -> load());
        findViewById(R.id.btn_confirm).setOnClickListener(v -> confirm());

        tabAll.setOnClickListener(v -> switchTab(false));
        tabWx.setOnClickListener(v -> switchTab(true));

        updateSelected();
        load();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_PERM) {
            permCard.setVisibility(WxFiles.hasPermission(this) ? View.GONE : View.VISIBLE);
            load();
        } else if (requestCode == REQ_BROWSE && resultCode == RESULT_OK && data != null) {
            List<String> paths = new ArrayList<>();
            ClipData clip = data.getClipData();
            if (clip != null) {
                for (int i = 0; i < clip.getItemCount(); i++) {
                    Uri u = clip.getItemAt(i).getUri();
                    if (u != null) paths.add(u.toString());
                }
            } else if (data.getData() != null) {
                paths.add(data.getData().toString());
            }
            if (!paths.isEmpty()) {
                Intent out = new Intent();
                out.putExtra(EXTRA_PATHS, paths.toArray(new String[0]));
                setResult(RESULT_OK, out);
                finish();
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!WxFiles.hasPermission(this)) {
            permCard.setVisibility(View.VISIBLE);
        }
    }

    private void switchTab(boolean wx) {
        if (wxOnly == wx) return;
        wxOnly = wx;
        tabAll.setBackgroundResource(wx ? R.drawable.bg_chip_gray : R.drawable.bg_chip_soft);
        tabAll.setTextColor(getColor(wx ? R.color.text_secondary : R.color.primary));
        tabWx.setBackgroundResource(wx ? R.drawable.bg_chip_soft : R.drawable.bg_chip_gray);
        tabWx.setTextColor(getColor(wx ? R.color.primary : R.color.text_secondary));
        render();
    }

    private void load() {
        if (loading) return;
        loading = true;
        tvStatus.setText("正在读取最近文件…");
        new Thread(() -> {
            final List<WxFiles.Entry> list = WxFiles.recent(this, 7, 300);
            ui.post(() -> {
                loading = false;
                all.clear();
                all.addAll(list);
                permCard.setVisibility(WxFiles.hasPermission(this) ? View.GONE : View.VISIBLE);
                render();
            });
        }, "ld-wx-scan").start();
    }

    private void render() {
        listContainer.removeAllViews();
        List<WxFiles.Entry> shown = new ArrayList<>();
        for (WxFiles.Entry e : all) {
            if (wxOnly && !e.wx) continue;
            shown.add(e);
        }

        int wxCount = 0;
        for (WxFiles.Entry e : all) if (e.wx) wxCount++;

        if (shown.isEmpty()) {
            emptyBox.setVisibility(View.VISIBLE);
            tvStatus.setText(wxOnly ? "最近 7 天没有微信文件" : "最近 7 天没有可发送的文件");
            return;
        }
        emptyBox.setVisibility(View.GONE);
        tvStatus.setText(String.format(Locale.CHINA,
                "最近 7 天 · %d 个文件，其中微信文件 %d 个", all.size(), wxCount));

        int limit = Math.min(shown.size(), 200);
        for (int i = 0; i < limit; i++) {
            listContainer.addView(row(shown.get(i)));
        }
    }

    private View row(final WxFiles.Entry e) {
        final boolean on = selected.contains(e.path);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setGravity(Gravity.CENTER_VERTICAL);
        box.setBackgroundResource(on ? R.drawable.bg_chip_soft : R.drawable.ripple_row);
        box.setPadding(Ui.dp(this, 14), Ui.dp(this, 12), Ui.dp(this, 14), Ui.dp(this, 12));
        box.setClickable(true);
        box.setFocusable(true);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        bp.bottomMargin = Ui.dp(this, 8);
        box.setLayoutParams(bp);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);

        TextView name = Ui.text(this, e.name, 14,
                getColor(R.color.text_primary), false);
        name.setMaxLines(1);
        name.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        col.addView(name);

        StringBuilder sub = new StringBuilder();
        sub.append(Util.humanSize(e.size)).append(" · ").append(Util.timeAgo(e.time));
        if (e.wx) sub.append(" · 微信");
        TextView subTv = Ui.text(this, sub.toString(), 11, getColor(R.color.text_tertiary), false);
        subTv.setPadding(0, Ui.dp(this, 3), 0, 0);
        col.addView(subTv);
        box.addView(col, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView mark = Ui.text(this, on ? "✓" : "", 15,
                getColor(R.color.primary), true);
        mark.setGravity(Gravity.CENTER);
        box.addView(mark, new LinearLayout.LayoutParams(Ui.dp(this, 28), Ui.dp(this, 28)));

        box.setOnClickListener(v -> {
            if (selected.contains(e.path)) selected.remove(e.path);
            else selected.add(e.path);
            render();
            updateSelected();
        });
        return box;
    }

    private void updateSelected() {
        if (selected.isEmpty()) {
            tvSelected.setText("未选择文件");
            return;
        }
        long total = 0;
        int matched = 0;
        for (WxFiles.Entry e : all) {
            if (selected.contains(e.path)) {
                total += e.size;
                matched++;
            }
        }
        tvSelected.setText("已选 " + matched + " 个 · 共 " + Util.humanSize(total));
    }

    private void confirm() {
        if (selected.isEmpty()) {
            Toast.makeText(this, "先点一下要发送的文件", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent out = new Intent();
        out.putExtra(EXTRA_PATHS, selected.toArray(new String[0]));
        setResult(RESULT_OK, out);
        finish();
    }

    private void browse() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        try {
            startActivityForResult(i, REQ_BROWSE);
        } catch (Exception ex) {
            Toast.makeText(this, "没有可用的文件选择器", Toast.LENGTH_SHORT).show();
        }
    }
}
