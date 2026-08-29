package com.henry.wordcount.bridge;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

/**
 * 设置入口（桌面图标）：配置「统计网址」。默认填好当前的 cpolar 地址，
 * 万一将来网址变了，进这里改一下即可，APK 无需重新打包。
 */
public class SettingsActivity extends Activity {
    static final String PREFS = "wc_bridge_prefs";
    static final String KEY_URL = "server_url";
    static final String DEF_URL = "https://7cf1f05b.r9.cpolar.cn";

    private EditText etUrl;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        etUrl = findViewById(R.id.et_url);
        TextView tvDef = findViewById(R.id.tv_def);
        tvDef.setText("默认：" + DEF_URL);

        SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
        etUrl.setText(sp.getString(KEY_URL, DEF_URL));

        Button btnSave = findViewById(R.id.btn_save);
        Button btnOpen = findViewById(R.id.btn_open);

        btnSave.setOnClickListener(v -> {
            String url = etUrl.getText().toString().trim();
            if (url.isEmpty()) url = DEF_URL;
            sp.edit().putString(KEY_URL, url).apply();
            Toast.makeText(this, "已保存：" + url, Toast.LENGTH_SHORT).show();
        });

        btnOpen.setOnClickListener(v -> {
            String url = etUrl.getText().toString().trim();
            if (url.isEmpty()) url = DEF_URL;
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        });
    }
}
