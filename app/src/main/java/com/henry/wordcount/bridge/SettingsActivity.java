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
    static final String DISCOVERY_URL =
            "https://raw.githubusercontent.com/18106322872/WordCountWebBridge/tunnel-url/tunnel_url.txt";

    private EditText etUrl;
    private CheckBox cbAuto;
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

    // ==================== v1.0.51：强制系统浏览器打开（避开夸克/UC 的照片-only 限制）====================

    /**
     * 用系统自带浏览器打开 URL（而非夸克/UC 等第三方浏览器）。
     * 夸克/UC 会把 &lt;input type="file"&gt; 限制为只能选照片，
     * 导致 WordCount 网页版无法上传文件。系统浏览器支持完整文件选择。
     */
    private void openInSystemBrowser(String url) {
        Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
        PackageManager pm = getPackageManager();
        List<ResolveInfo> browsers = pm.queryIntentActivities(i, 0);

        // 优先级：Chrome > AOSP 原生浏览器 > 其他非夸克/UC 浏览器
        String[] preferred = {
                "com.android.chrome",
                "com.google.android.apps.chrome",
                "com.android.browser",
                "org.chromium.chrome",
        };

        for (String pref : preferred) {
            for (ResolveInfo info : browsers) {
                if (pref.equals(info.activityInfo.packageName)) {
                    i.setPackage(pref);
                    startActivity(i);
                    return;
                }
            }
        }

        // 回退：任一非夸克/UC 浏览器
        for (ResolveInfo info : browsers) {
            String pkg = info.activityInfo.packageName.toLowerCase();
            if (!pkg.contains("quark") && !pkg.contains("ucbrowser") && !pkg.contains("ucweb")) {
                i.setPackage(info.activityInfo.packageName);
                startActivity(i);
                return;
            }
        }

        // 兜底：让用户手动选择
        startActivity(Intent.createChooser(i, "请选择系统浏览器打开"));
    }
}
