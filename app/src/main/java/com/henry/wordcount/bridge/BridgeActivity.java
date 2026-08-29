package com.henry.wordcount.bridge;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * 桥接 Activity：从微信/千牛「用其他应用打开」被唤起时接收文件，
 * 把文件 POST 到网页版后端的 /api/upload，再调起浏览器打开「?job=」深链显示结果。
 */
public class BridgeActivity extends Activity {
    static final String PREFS = "wc_bridge_prefs";
    static final String KEY_URL = "server_url";
    static final String DEF_URL = "https://7cf1f05b.r9.cpolar.cn";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        handleIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleIntent(intent);
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

        Toast.makeText(this, "正在上传到 WordCount 网页版…", Toast.LENGTH_SHORT).show();
        new Thread(() -> doUpload(uri, name, intent.getType())).start();
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

    private void doUpload(Uri uri, String name, String mime) {
        try {
            String base = getSharedPreferences(PREFS, MODE_PRIVATE)
                    .getString(KEY_URL, DEF_URL).trim();
            if (base.endsWith("/")) base = base.substring(0, base.length() - 1);

            InputStream is = getContentResolver().openInputStream(uri);
            byte[] data = readAll(is);
            String jobId = upload(base + "/api/upload", name, mime, data);

            // 调起浏览器打开网页版并定位到该任务
            String target = base + "/?job=" + jobId + "&name=" + Uri.encode(name);
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(target));
            startActivity(Intent.createChooser(i, "用浏览器打开 WordCount 网页版"));
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

    private static byte[] readAll(InputStream is) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
        is.close();
        return bos.toByteArray();
    }

    private static String upload(String urlStr, String fileName, String mime, byte[] data) throws Exception {
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
}
