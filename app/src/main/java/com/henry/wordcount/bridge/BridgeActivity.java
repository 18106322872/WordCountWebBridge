package com.henry.wordcount.bridge;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 桥接 Activity：从微信/千牛「用其他应用打开」被唤起时接收文件，
 * 把文件 POST 到网页版后端 /api/upload（小文件）或 分片上传+commit（大文件），
 * 再调起浏览器打开「?job=」深链显示结果。
 *
 * v1.0.39 修复：
 *   - 默认网址从失效 cpolar 改为当前 Cloudflare Quick Tunnel 地址
 *   - 新增布局 + 进度文字提示（不再白屏转圈）
 *   - 大文件（>5MB）走分片并行上传，避免 Cloudflare 免费隧道单请求超时 524
 *   - 上传前预检服务器连通性，不可达时秒级报错而非卡死 10 分钟
 * v1.0.50 增强：
 *   - 自动发现当前隧道域名：启动/上传前 GET GitHub 发现通道（tunnel-url 分支
 *     tunnel_url.txt），PC 重启换域名也自动跟上，免手动改 URL；失败回退手动/默认
 *   - 设置页新增「自动发现网址」开关
 */
public class BridgeActivity extends Activity {
    static final String PREFS = "wc_bridge_prefs";
    static final String KEY_URL = "server_url";
    static final String DEF_URL = "https://expert-cambridge-identity-walk.trycloudflare.com";

    /**
     * v1.0.50：自动发现通道。PC 端服务后台线程会把当前 Cloudflare Quick Tunnel 域名
     * 写入该 GitHub raw 文件；App 启动/上传前自动拉取，PC 重启换域名也能自动跟上，
     * 彻底免手动改 URL。拉取失败则回退到手动/默认地址。
     */
    static final String DISCOVERY_URL =
            "https://raw.githubusercontent.com/18106322872/WordCountWebBridge/tunnel-url/tunnel_url.txt";
    static final String KEY_AUTO = "auto_url";        // 是否启用自动发现（默认 true）
    static final String KEY_LAST_AUTO = "last_auto_url"; // 上次成功发现的域名缓存

    /** v1.0.39：超过此阈值走分片上传（Cloudflare 免费隧道单请求 ~100s 超时） */
    static final long CHUNK_THRESHOLD = 5 * 1024 * 1024; // 5MB
    static final int CHUNK_SIZE = 6 * 1024 * 1024;       // 每片 6MB（与网页版一致）
    static final int CONCURRENCY = 4;                     // 并发数

    private TextView tvStatus;
    private ProgressBar progressBar;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_bridge);
        tvStatus = findViewById(R.id.tv_status);
        progressBar = findViewById(R.id.progress_bar);
        handleIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleIntent(intent);
    }

    private void setStatus(final String msg) {
        if (tvStatus != null) {
            runOnUiThread(() -> tvStatus.setText(msg));
        }
    }

    private void handleIntent(Intent intent) {
        if (intent == null) { finish(); return; }

        Uri uri = null;
        String name = "共享文件";

        if (Intent.ACTION_VIEW.equals(intent.getAction())) {
            uri = intent.getData();
            name = guessName(uri);
        } else if (Intent.ACTION_SEND.equals(intent.getAction())) {
            Uri stream = intent.getParcelableExtra(Intent.EXTRA_STREAM);
            if (stream != null) {
                uri = stream;
                name = guessName(stream);
            }
        }

        if (uri == null) {
            Toast.makeText(this, "未收到文件，请从微信/千牛「用其他应用打开」选择本应用", Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        final Uri upUri = uri;
        final String upName = name;
        final String upType = intent.getType();
        new Thread(() -> doUpload(upUri, upName, upType)).start();
    }

    private String guessName(Uri uri) {
        String result = "共享文件";
        try {
            if ("content".equals(uri.getScheme())) {
                Cursor c = getContentResolver().query(uri,
                        new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null);
                if (c != null) {
                    int idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    if (idx >= 0 && c.moveToFirst()) result = c.getString(idx);
                    c.close();
                }
            }
            if (result == null || "共享文件".equals(result)) {
                String p = uri.getLastPathSegment();
                if (p != null) result = p;
            }
        } catch (Exception ignore) { }
        return result;
    }

    // ==================== v1.0.50：自动发现当前隧道域名 ====================

    /** GET 发现通道文件，提取其中的 trycloudflare.com 域名；失败返回 null */
    private String fetchDiscoveryUrl() {
        try {
            URL u = new URL(DISCOVERY_URL);
            HttpURLConnection c = (HttpURLConnection) u.openConnection();
            c.setRequestMethod("GET");
            c.setConnectTimeout(8000);
            c.setReadTimeout(8000);
            int code = c.getResponseCode();
            if (code < 200 || code >= 300) { c.disconnect(); return null; }
            InputStream in = c.getInputStream();
            byte[] buf = new byte[256];
            int n = in.read(buf);
            c.disconnect();
            if (n <= 0) return null;
            String s = new String(buf, 0, n, StandardCharsets.UTF_8);
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("https://[A-Za-z0-9-]+\\.trycloudflare\\.com")
                    .matcher(s);
            if (m.find()) return m.group();
        } catch (Exception ignore) { }
        return null;
    }

    /** 解析实际使用的服务器地址：自动发现优先，失败回退手动/默认 */
    private String resolveBaseUrl() {
        SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
        if (sp.getBoolean(KEY_AUTO, true)) {
            String d = fetchDiscoveryUrl();
            if (d != null) {
                sp.edit().putString(KEY_LAST_AUTO, d).apply();
                return d;
            }
            String last = sp.getString(KEY_LAST_AUTO, null);
            if (last != null && !last.isEmpty()) return last;
        }
        String manual = sp.getString(KEY_URL, DEF_URL).trim();
        if (manual.isEmpty()) manual = DEF_URL;
        return manual;
    }

    private void doUpload(Uri uri, String name, String mime) {
        try {
            // v1.0.50：优先自动发现当前隧道域名，失败回退手动/默认
            String base = resolveBaseUrl();
            if (base.endsWith("/")) base = base.substring(0, base.length() - 1);

            // v1.0.39：连通性预检 —— 2 秒内连不上就秒报错，不卡 10 分钟
            setStatus("正在连接服务器…");
            if (!checkReachable(base)) {
                throw new Exception("无法连接统计服务器（" + base + "）。请检查：\n" +
                        "① 电脑是否开机且 WordCountWeb 服务运行中\n" +
                        "② 手机和电脑是否在同一网络（或电脑有外网穿透）\n" +
                        "③ 进入本 App 设置页确认网址正确");
            }

            InputStream is = getContentResolver().openInputStream(uri);
            byte[] data = readAll(is);

            String jobId;
            long sizeBytes = data.length;
            String sizeStr = formatSize(sizeBytes);

            if (sizeBytes > CHUNK_THRESHOLD) {
                // v1.0.39：大文件走分片并行上传（与网页版 v1.0.33 一致）
                setStatus("正在上传（" + sizeStr + "，分片并行）…");
                jobId = uploadChunked(base, name, data);
            } else {
                setStatus("正在上传（" + sizeStr + "）…");
                jobId = uploadSingle(base + "/api/upload", name, mime, data);
            }

            // 调起系统浏览器打开网页版并定位到该任务（v1.0.51：强制系统浏览器，避开夸克/UC 照片-only 限制）
            setStatus("正在跳转浏览器…");
            String target = base + "/?job=" + jobId + "&name=" + Uri.encode(name);
            openInSystemBrowser(target);
            runOnUiThread(() ->
                    Toast.makeText(this, "已跳转，请在浏览器查看统计结果", Toast.LENGTH_SHORT).show());
            finish();
        } catch (Exception e) {
            final String msg = e.getMessage();
            runOnUiThread(() ->
                    Toast.makeText(this, "上传失败：" + (msg == null ? "未知错误" : msg), Toast.LENGTH_LONG).show());
            finish();
        }
    }

    // ==================== 连通性预检 ====================

    private boolean checkReachable(String baseUrl) {
        try {
            URL url = new URL(baseUrl + "/api/health");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(5000);  // 5 秒连接超时
            conn.setReadTimeout(10000);    // 10 秒读取超时
            int code = conn.getResponseCode();
            conn.disconnect();
            return code >= 200 && code < 400;
        } catch (Exception e) {
            return false;
        }
    }

    // ==================== 单请求上传（小文件 ≤5MB）====================

    private static String uploadSingle(String urlStr, String fileName, String mime, byte[] data) throws Exception {
        String boundary = "----WCbridge" + System.currentTimeMillis();
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setConnectTimeout(600000);
        conn.setReadTimeout(600000);
        conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);

        try (OutputStream os = conn.getOutputStream()) {
            StringBuilder sb = new StringBuilder();
            sb.append("--").append(boundary).append("\r\n");
            sb.append("Content-Disposition: form-data; name=\"files\"; filename=\"")
                    .append(fileName).append("\"\r\n");
            sb.append("Content-Type: ").append(mime == null ? "application/octet-stream" : mime).append("\r\n\r\n");
            os.write(sb.toString().getBytes(StandardCharsets.UTF_8));
            os.write(data);
            os.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        }

        int code = conn.getResponseCode();
        InputStream in = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
        String resp = new String(readAll(in), StandardCharsets.UTF_8);
        if (code < 200 || code >= 300) throw new Exception("HTTP " + code + " " + resp);

        JSONObject obj = new JSONObject(resp);
        JSONArray jobs = obj.getJSONArray("jobs");
        if (jobs.length() == 0) throw new Exception("服务端未返回任务");
        return jobs.getJSONObject(0).getString("job_id");
    }

    // ==================== v1.0.39：分片并行上传（大文件 >5MB）====================
    // 与网页版 v1.0.33 策略一致：
    //   1. 文件切分为 6MB 片
    //   2. 并发 POST /api/upload_chunk（每片只落盘）
    //   3. 全部完成后再 POST /api/upload_commit 合并+启动统计

    private String uploadChunked(String base, String fileName, byte[] data) throws Exception {
        String uid = Long.toHexString(System.nanoTime()); // 唯一标识本次上传
        int totalChunks = Math.max(1, (int) ((data.length + CHUNK_SIZE - 1) / CHUNK_SIZE));

        // 并发上传所有分片
        Thread[] threads = new Thread[Math.min(CONCURRENCY, totalChunks)];
        Throwable[] errors = new Throwable[1];
        errors[0] = null;

        for (int t = 0; t < threads.length; t++) {
            final int workerId = t;
            threads[t] = new Thread(() -> {
                for (int i = workerId; i < totalChunks; i += threads.length) {
                    if (errors[0] != null) return; // 已出错，停止
                    try {
                        int start = i * CHUNK_SIZE;
                        int end = Math.min(start + CHUNK_SIZE, data.length);
                        byte[] chunk = java.util.Arrays.copyOfRange(data, start, end);
                        postChunk(base, uid, fileName, i, totalChunks, chunk);

                        // 更新进度
                        int done = Math.min(i + 1, totalChunks);
                        setStatus("正在上传… (" + done + "/" + totalChunks + " 片)");
                    } catch (Exception e) {
                        synchronized (errors) {
                            if (errors[0] == null) errors[0] = e;
                        }
                    }
                }
            });
            threads[t].start();
        }

        for (Thread t : threads) t.join(30000); // 每个线程最多等 30s
        if (errors[0] != null) throw new Exception("分片上传失败: " + errors[0].getMessage());

        // 全部到达 → commit 合并+启动统计
        setStatus("正在合并并启动统计…");
        return commitUpload(base, uid, fileName, totalChunks);
    }

    private void postChunk(String base, String uid, String name,
                           int index, int total, byte[] chunkData) throws Exception {
        String boundary = "----WCchunk" + System.currentTimeMillis();
        URL url = new URL(base + "/api/upload_chunk");
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setConnectTimeout(60000);  // 单片 60s 超时
        conn.setReadTimeout(60000);
        conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);

        try (OutputStream os = conn.getOutputStream()) {
            writePart(os, boundary, "uid", null, uid.getBytes(StandardCharsets.UTF_8));
            writePart(os, boundary, "name", null, name.getBytes(StandardCharsets.UTF_8));
            writePart(os, boundary, "index", null, String.valueOf(index).getBytes(StandardCharsets.UTF_8));
            writePart(os, boundary, "total", null, String.valueOf(total).getBytes(StandardCharsets.UTF_8));
            writePart(os, boundary, "data", "application/octet-stream", chunkData);
            os.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        }

        int code = conn.getResponseCode();
        InputStream in = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
        String resp = new String(readAll(in), StandardCharsets.UTF_8);
        conn.disconnect();
        if (code < 200 || code >= 300) {
            throw new Exception("分片 " + index + " HTTP " + code + ": " + resp.substring(0, Math.min(200, resp.length())));
        }
    }

    private String commitUpload(String base, String uid, String name, int total) throws Exception {
        String boundary = "----WCcommit" + System.currentTimeMillis();
        URL url = new URL(base + "/api/upload_commit");
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setConnectTimeout(30000);
        conn.setReadTimeout(30000);
        conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);

        try (OutputStream os = conn.getOutputStream()) {
            writePart(os, boundary, "uid", null, uid.getBytes(StandardCharsets.UTF_8));
            writePart(os, boundary, "name", null, name.getBytes(StandardCharsets.UTF_8));
            writePart(os, boundary, "total", null, String.valueOf(total).getBytes(StandardCharsets.UTF_8));
            os.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        }

        int code = conn.getResponseCode();
        InputStream in = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
        String resp = new String(readAll(in), StandardCharsets.UTF_8);
        conn.disconnect();
        if (code < 200 || code >= 300) throw new Exception("合并失败 HTTP " + code + ": " + resp);

        JSONObject obj = new JSONObject(resp);
        return obj.getString("job_id");
    }

    private static void writePart(OutputStream os, String boundary,
                                   String name, String filename, byte[] value) throws Exception {
        StringBuilder sb = new StringBuilder();
        sb.append("--").append(boundary).append("\r\n");
        sb.append("Content-Disposition: form-data; name=\"").append(name).append("\"");
        if (filename != null) sb.append("; filename=\"").append(filename).append("\"");
        sb.append("\r\n\r\n");
        os.write(sb.toString().getBytes(StandardCharsets.UTF_8));
        os.write(value);
        os.write("\r\n".getBytes(StandardCharsets.UTF_8));
    }

    // ==================== 工具方法 ====================

    private static byte[] readAll(InputStream is) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
        is.close();
        return bos.toByteArray();
    }

    private static String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
        return String.format("%.1f GB", bytes / (1024.0 * 1024 * 1024));
    }

    // ==================== v1.0.53：用默认浏览器打开（用户已在系统设置中设好系统浏览器）====================

    /**
     * 打开 URL。直接 startActivity(ACTION_VIEW) 不指定包名、不用 chooser，
     * Android 会使用用户在「设置→默认应用」中设定的默认浏览器。
     * 用户只需在系统设置里把默认浏览器从夸克/UC 改成系统自带即可。
     */
    private void openInSystemBrowser(String url) {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            startActivity(i);
        } catch (Exception e) {
            // 静默失败，不影响上传完成提示
        }
    }
}
