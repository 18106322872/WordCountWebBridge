package com.henry.wordcount.bridge;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Bundle;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

/**
 * 设置入口（桌面图标）：配置「统计网址」。
 * v1.0.50：新增「自动发现网址」开关（默认开）。开启时 App 启动/上传前自动从
 * GitHub 发现通道（tunnel-url 分支 tunnel_url.txt）拉取当前 Cloudflare Quick Tunnel
 * 域名，PC 重启换域名也自动跟上，无需手动改；关闭时才用下方手动地址。
 * v1.0.56：统计网址改为下拉框（frp / dpdns.org / 127.0.0.1），默认 frp；
 * 默认关闭「自动发现」以让下拉框选择直接生效（选哪个就用哪个）；
 * 新增可选「访问密码」字段，连接前自动 POST 授权，frp 免浏览器授权即可用。
 */
public class SettingsActivity extends Activity {
    static final String PREFS = "wc_bridge_prefs";
    static final String KEY_URL = "server_url";
    static final String DEF_URL = "https://dx.frp-boy.com:41086";
    static final String[] URL_OPTIONS = {
            "https://dx.frp-boy.com:41086",   // 默认（樱花 frp，电信快）
            "https://wordcount.dpdns.org",     // Cloudflare 固定域名（兜底，无需密码）
            "http://127.0.0.1:8000"            // 本机（同机调试）
    };
    static final String KEY_AUTO = "auto_url";
    static final String KEY_LAST_AUTO = "last_auto_url";
    static final String KEY_WORD_VERIFY = "word_verify";   // v1.0.55：Word 精确模式（默认开）
    static final String KEY_ACCESS_PW = "access_pw";       // v1.0.56：frp 访问密码（可选）
    static final String DISCOVERY_URL =
            "https://raw.githubusercontent.com/18106322872/WordCountWebBridge/tunnel-url/tunnel_url.txt";

    private Spinner spUrl;
    private EditText etPw;
    private CheckBox cbAuto;
    private CheckBox cbWordVerify;
    private TextView tvDef;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        // v1.1.6：标题下显示版本号（不透明常规文字，重装多次后易混淆）
        try {
            android.content.pm.PackageInfo pi =
                    getPackageManager().getPackageInfo(getPackageName(), 0);
            ((TextView) findViewById(R.id.tv_version)).setText("版本 v" + pi.versionName);
        } catch (Exception ignore) { }

        spUrl = findViewById(R.id.sp_url);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, URL_OPTIONS);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spUrl.setAdapter(adapter);

        cbAuto = findViewById(R.id.cb_auto);
        tvDef = findViewById(R.id.tv_def);
        etPw = findViewById(R.id.et_pw);

        SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
        // v1.0.56：默认关闭自动发现，让下拉框选择直接生效（选哪个就用哪个）
        boolean auto = sp.getBoolean(KEY_AUTO, false);
        cbAuto.setChecked(auto);
        applyAutoUi(sp, auto);

        // 选中已保存/默认网址
        String saved = sp.getString(KEY_URL, DEF_URL);
        int sel = 0;
        for (int i = 0; i < URL_OPTIONS.length; i++) {
            if (URL_OPTIONS[i].equals(saved)) { sel = i; break; }
        }
        spUrl.setSelection(sel);

        // 访问密码（可选）
        etPw.setText(sp.getString(KEY_ACCESS_PW, ""));

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
            String url = URL_OPTIONS[spUrl.getSelectedItemPosition()];
            sp.edit().putString(KEY_URL, url).apply();
            sp.edit().putString(KEY_ACCESS_PW, etPw.getText().toString().trim()).apply();
            Toast.makeText(this, "已保存：" + url, Toast.LENGTH_SHORT).show();
        });

        btnOpen.setOnClickListener(v -> {
            String url = URL_OPTIONS[spUrl.getSelectedItemPosition()];
            openInSystemBrowser(url);
        });
    }

    /** 自动发现开启时禁用手动下拉框与密码框并提示当前域名；关闭时恢复可选 */
    private void applyAutoUi(SharedPreferences sp, boolean auto) {
        spUrl.setEnabled(!auto);
        etPw.setEnabled(!auto);
        if (auto) {
            String last = sp.getString(KEY_LAST_AUTO, null);
            tvDef.setText(last != null && !last.isEmpty()
                    ? "自动发现已开启，当前域名：\n" + last
                    : "自动发现已开启，将从服务器获取当前域名");
        } else {
            tvDef.setText("从下拉框选择统计网址（默认 frp）");
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
