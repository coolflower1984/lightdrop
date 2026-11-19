package com.lightdrop.transfer;

import android.app.Activity;
import android.os.Build;
import android.os.Bundle;
import android.widget.EditText;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import com.lightdrop.transfer.core.Prefs;
import com.lightdrop.transfer.core.UpdateManager;

public class SettingsActivity extends Activity {

    private Prefs prefs;
    private EditText etName, etUpdate;
    private Switch swAuto;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
        prefs = new Prefs(this);

        etName = findViewById(R.id.et_name);
        etUpdate = findViewById(R.id.et_update);
        swAuto = findViewById(R.id.sw_auto_update);

        etName.setText(prefs.deviceName());
        etUpdate.setText(prefs.updateSource());
        swAuto.setChecked(prefs.autoUpdate());

        findViewById(R.id.btn_back).setOnClickListener(v -> finish());
        findViewById(R.id.btn_check_update).setOnClickListener(v -> checkUpdate());

        swAuto.setOnCheckedChangeListener((b, checked) -> prefs.setAutoUpdate(checked));

        ((TextView) findViewById(R.id.tv_version_detail)).setText(
                "Android " + Build.VERSION.RELEASE + " · " + Build.MANUFACTURER + " " + Build.MODEL
                        + "\n版本号 " + Prefs.versionName(this) + " (" + Prefs.versionCode(this) + ")");
    }

    @Override
    protected void onPause() {
        save();
        super.onPause();
    }

    private void save() {
        String name = etName.getText().toString().trim();
        if (!name.isEmpty()) prefs.setDeviceName(name);
        prefs.setUpdateSource(etUpdate.getText().toString().trim());
        prefs.setAutoUpdate(swAuto.isChecked());
    }

    private void checkUpdate() {
        save();
        String src = prefs.updateSource();
        if (src.isEmpty()) {
            Toast.makeText(this, "请先填写更新源，例如 GitHub 的用户名/仓库名", Toast.LENGTH_LONG).show();
            return;
        }
        Toast.makeText(this, "正在检查更新…", Toast.LENGTH_SHORT).show();
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
}
