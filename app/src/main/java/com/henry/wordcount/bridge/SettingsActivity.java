package com.henry.wordcount.bridge;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

/**
 * 设置入口（桌面图标）：配置「统计网址」。
 * v1.0.50：新增「自动发现网址」开关（默认开）。开启时 App 启动/上传前自动从
 * GitHub 发现通道（tunnel-url 分支 tunnel_url.txt）拉取当前 Cloudflare Quick Tunnel
 * 域名，PC 重启换域名也自动跟上，无需手动改；关闭时才用下方手动地址。
 */
public class SettingsActivity extends Activity {
    static final String PREFS = "wc_bridge_prefs";
    static final String KEY_URL = "server_url";
    static final String DEF_URL = "https://expert-cambridge-identity-walk.trycloudflare.com";
    static final String KEY_AUTO = "auto_url";
    static final String KEY_LAST_AUTO = "last_auto_url";
    static final String KEY_WORD_VERIFY = "word_verify";   // v1.0.55：Word 精确模式（默认开）
    static final String DISCOVERY_URL =
            "https://raw.githubusercontent.com/18106322872/WordCountWebBridge/tunnel-url/tunnel_url.txt";

    private EditText etUrl;
    private CheckBox cbAuto;
    private CheckBox cbWordVerify;
    private TextView tvDef;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        etUrl = findViewById(R.id.et_url);
        cbAuto = findViewById(R.id.cb_auto);
        tvDef = findViewById(R.id.tv_def);

        SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
        boolean auto = sp.getBoolean(KEY_AUTO, true);
        cbAuto.setChecked(auto);
        applyAutoUi(sp, auto);
        etUrl.setText(sp.getString(KEY_URL, DEF_URL));

        // v1.0.55：Word 精确模式开关（默认开），与网页版"默认勾选"一致
        cbWordVerify = findViewById(R.id.cb_word_verify);
        cbWordVerify.setChecked(sp.getBoolean(KEY_WORD_VERIFY, true));
        cbWordVerify.setOnCheckedChangeListener((b, c) -> sp.edit().putBoolean(KEY_WORD_VERIFY, c).apply());

        cbAuto.setOnCheckedChangeListener((buttonView, isChecked) -> {
            sp.edit().putBoolean(KEY_AUTO, isChecked).apply();
            applyAutoUi(sp, isChecked);
        });

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
            openInSystemBrowser(url);
        });
    }

    /** 自动发现开启时禁用手动输入框并提示当前域名；关闭时恢复可编辑 */
    private void applyAutoUi(SharedPreferences sp, boolean auto) {
        etUrl.setEnabled(!auto);
        if (auto) {
            String last = sp.getString(KEY_LAST_AUTO, null);
            tvDef.setText(last != null && !last.isEmpty()
                    ? "自动发现已开启，当前域名：\n" + last
                    : "自动发现已开启，将从服务器获取当前域名");
        } else {
            tvDef.setText("默认：" + DEF_URL);
        }
    }

    // ==================== v1.0.53：用默认浏览器打开（用户已在系统设置中设好系统浏览器）====================

    /**
     * 打开 URL。v1.0.51/v1.0.52 尝试用 queryIntentActivities 检测并过滤浏览器，
     * 但华为等 OEM 设备的系统浏览器不一定会被 ACTION_VIEW 查询返回，
     * 导致误判"未找到系统浏览器"。
     *
     * v1.0.53 改为最简方案：直接 startActivity(ACTION_VIEW) 不指定包名、不用 chooser，
     * Android 会使用用户在「设置→默认应用」中设定的默认浏览器。
     * 用户只需在系统设置里把默认浏览器从夸克/UC 改成系统自带即可。
     */
    private void openInSystemBrowser(String url) {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            startActivity(i);
        } catch (Exception e) {
            Toast.makeText(this, "无法打开浏览器：" + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }
}
