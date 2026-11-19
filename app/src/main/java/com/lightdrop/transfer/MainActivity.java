package com.lightdrop.transfer;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import com.lightdrop.transfer.core.Prefs;
import com.lightdrop.transfer.core.UpdateManager;

public class MainActivity extends Activity {

    private static final long CHECK_INTERVAL = 24L * 60 * 60 * 1000;
    private Prefs prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        prefs = new Prefs(this);

        findViewById(R.id.card_send).setOnClickListener(v ->
                startActivity(new Intent(this, SendActivity.class)));
        findViewById(R.id.card_receive).setOnClickListener(v ->
                startActivity(new Intent(this, ReceiveActivity.class)));
        findViewById(R.id.btn_settings).setOnClickListener(v ->
                startActivity(new Intent(this, SettingsActivity.class)));
        findViewById(R.id.btn_update).setOnClickListener(v -> manualCheck());

        ((TextView) findViewById(R.id.tv_version)).setText("轻传 v" + Prefs.versionName(this));
    }

    @Override
    protected void onResume() {
        super.onResume();
        maybeAutoCheck();
    }

    private void maybeAutoCheck() {
        if (!prefs.autoUpdate()) return;
        if (prefs.updateSource().isEmpty()) return;
        long now = System.currentTimeMillis();
        if (now - prefs.lastUpdateCheck() < CHECK_INTERVAL) return;
        prefs.setLastUpdateCheck(now);
        UpdateManager.check(this, prefs.updateSource(), (info, hasNew, error) -> {
            if (hasNew && info != null && !isFinishing()) {
                UpdateManager.showUpdateDialog(this, info);
            }
        });
    }

    private void manualCheck() {
        String src = prefs.updateSource();
        if (src.isEmpty()) {
            Toast.makeText(this, "请先在设置里填写更新源（GitHub 用户名/仓库名）", Toast.LENGTH_LONG).show();
            startActivity(new Intent(this, SettingsActivity.class));
            return;
        }
        Toast.makeText(this, "正在检查更新…", Toast.LENGTH_SHORT).show();
        prefs.setLastUpdateCheck(System.currentTimeMillis());
        UpdateManager.check(this, src, (info, hasNew, error) -> {
            if (isFinishing()) return;
            if (error != null) {
                Toast.makeText(this, "检查失败：" + error, Toast.LENGTH_LONG).show();
            } else if (hasNew && info != null) {
                UpdateManager.showUpdateDialog(this, info);
            } else {
                Toast.makeText(this, "已是最新版本 v" + Prefs.versionName(this), Toast.LENGTH_SHORT).show();
            }
        });
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            View decor = getWindow().getDecorView();
            decor.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        }
    }
}
