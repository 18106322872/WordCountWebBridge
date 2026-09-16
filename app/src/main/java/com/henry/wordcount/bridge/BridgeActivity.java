package com.henry.wordcount.bridge;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.SslErrorHandler;
import android.webkit.ValueCallback;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.CookieHandler;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * 桥接 Activity：从微信/千牛「用其他应用打开」被唤起时接收文件。
 *
 * v1.1.0 架构重构（对齐手机版程序的"同一页累加"体验）：
 *   旧版（≤v1.0.56）：App 自己用 HttpURLConnection 上传，再 startActivity 调系统浏览器
 *   打开「?job=」，于是 **每分享一个文件就新开一个网页**，且上传进度只能显示在 App
 *   这个没有进度条的页面上。
 *   新版：Activity 本身就是一个 WebView，直接承载网页版；收到分享文件后**不自己上传**，
 *   而是把文件通过「与页面同源的特殊路径」 /__wcbridge__/<token> 交给网页
 *   （在本 Activity 的 shouldInterceptRequest 里拦截该请求、直接回文件字节）。
 *   于是上传、进度条、结果行全部由网页完成：
 *     · 每次分享只是往**同一个页面追加一行**（和手机版程序一样在同一页累加统计）
 *     · 上传进度用**网页自己的进度条**显示（不再是无进度条的 App 页面）
 *     · 走浏览器 FormData 分片上传，天然没有原生 multipart 编码问题
 *
 * v1.0.56 保留：统计网址下拉框（frp / dpdns.org / 127.0.0.1，默认 frp）、
 *   放行 SakuraFrp「自动 HTTPS」自签证书、可选访问密码自动授权。
 * v1.0.39 保留：原生上传兜底路径（网页不可用时使用），并修复其分片 multipart
 *   在数据后多写一个 CRLF 的 bug（曾导致 >6MB 文件在 6MB 边界损坏 → DWG 静默算 0 字）。
 */
public class BridgeActivity extends Activity {
    static final String PREFS = "wc_bridge_prefs";
    static final String KEY_URL = "server_url";
    static final String DEF_URL = "https://dx.frp-boy.com:41086";
    static final String KEY_ACCESS_PW = "access_pw";

    static final String DISCOVERY_URL =
            "https://raw.githubusercontent.com/18106322872/WordCountWebBridge/tunnel-url/tunnel_url.txt";
    static final String KEY_AUTO = "auto_url";
    static final String KEY_LAST_AUTO = "last_auto_url";
    static final String KEY_WORD_VERIFY = "word_verify";

    /** v1.1.0：网页与 App 约定的「同源文件通道」路径前缀 */
    static final String INTERCEPT_PREFIX = "/__wcbridge__/";

    /** 原生上传兜底（网页不可用时）：超过此阈值走分片 */
    static final long CHUNK_THRESHOLD = 5 * 1024 * 1024; // 5MB
    static final int CHUNK_SIZE = 6 * 1024 * 1024;       // 每片 6MB
    static final int CONCURRENCY = 4;

    private WebView webView;
    private View errorBox;
    private TextView tvError;

    private String loadedBase = null;      // WebView 当前已加载（或正在加载）的 base
    private boolean pageReady = false;     // 我们的页面已就绪（window.wcBridgeAdd 可用）
    private boolean authTried = false;     // 访问密码页面内授权只尝试一次

    private final AtomicInteger tokenSeq = new AtomicInteger(0);
    private final Map<String, FileRef> tokenFiles = new ConcurrentHashMap<>();
    private final List<PendingFile> pending = Collections.synchronizedList(new ArrayList<>());

    /** 通过特殊路径交给网页的文件引用 */
    private static class FileRef {
        final Uri uri;
        final String name;
        FileRef(Uri uri, String name) { this.uri = uri; this.name = name; }
    }

    private static class PendingFile {
        final Uri uri;
        final String name;
        final String mime;
        PendingFile(Uri uri, String name, String mime) {
            this.uri = uri; this.name = name; this.mime = mime;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_bridge);
        webView = findViewById(R.id.webview);
        errorBox = findViewById(R.id.error_box);
        tvError = findViewById(R.id.tv_error);

        // 原生兜底路径（HttpURLConnection）也要放行自签证书；cookie 自动管理用于 frp 访问密码
        installRelaxedTls();
        CookieHandler.setDefault(new java.net.CookieManager(null, java.net.CookiePolicy.ACCEPT_ALL));

        setupWebView();

        findViewById(R.id.btn_retry).setOnClickListener(v -> {
            pageReady = false;
            loadedBase = null;
            errorBox.setVisibility(View.GONE);
            webView.setVisibility(View.VISIBLE);
            loadBase(resolveBaseUrlCached());
        });
        findViewById(R.id.btn_settings).setOnClickListener(v ->
                startActivity(new Intent(this, SettingsActivity.class)));

        handleIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        // v1.1.0：singleTask + 复用同一个 WebView → 新文件追加到同一页面的新行
        handleIntent(intent);
    }

    // ==================== WebView 配置 ====================

    private void setupWebView() {
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        // 服务端会更新页面脚本，禁用缓存确保拿到最新版（页面无外部静态资源，代价很小）
        s.setCacheMode(WebSettings.LOAD_NO_CACHE);
        if (Build.VERSION.SDK_INT >= 21) {
            s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        }
        s.setUserAgentString(s.getUserAgentString() + " WCBridge/1.1.0");

        // frp 访问密码用 cookie 保持授权
        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        if (Build.VERSION.SDK_INT >= 21) cm.setAcceptThirdPartyCookies(webView, true);

        webView.setWebViewClient(new WebViewClient() {
            /** SakuraFrp「自动 HTTPS」是自签证书，WebView 必须放行，否则页面根本打不开 */
            @Override
            public void onReceivedSslError(WebView view, SslErrorHandler handler,
                                           android.net.http.SslError error) {
                handler.proceed();
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                onPageLoaded();
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request,
                                        WebResourceError error) {
                if (request != null && request.isForMainFrame()) showError(describe(error));
            }

            @Override
            public void onReceivedError(WebView view, int errorCode,
                                        String description, String failingUrl) {
                if (Build.VERSION.SDK_INT < 23) showError(description);
            }

            /**
             * v1.1.0 核心：拦截网页对我们「同源文件通道」的请求，直接回文件字节。
             * 这样文件不经过 base64/JS 字符串，任意大小都能流式交给网页，
             * 由网页用标准 FormData 分片上传（进度条也在网页里）。
             */
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view,
                                                              WebResourceRequest request) {
                try {
                    Uri u = request.getUrl();
                    String path = u.getPath();
                    if (path == null || !path.startsWith(INTERCEPT_PREFIX)) return null;
                    String token = path.substring(INTERCEPT_PREFIX.length());
                    FileRef ref = tokenFiles.get(token);
                    if (ref == null) {
                        return new WebResourceResponse("text/plain", "utf-8", 404, "Not Found",
                                new HashMap<String, String>(),
                                new ByteArrayInputStream("token not found".getBytes(StandardCharsets.UTF_8)));
                    }
                    InputStream in = getContentResolver().openInputStream(ref.uri);
                    if (in == null) {
                        return new WebResourceResponse("text/plain", "utf-8", 500, "Open Failed",
                                new HashMap<String, String>(),
                                new ByteArrayInputStream("open failed".getBytes(StandardCharsets.UTF_8)));
                    }
                    Map<String, String> headers = new HashMap<>();
                    // 不设 Content-Length：由流的 EOF 决定长度，避免 provider 上报的 SIZE
                    // 与实际字节数不一致时被 Chromium 判为 ERR_CONTENT_LENGTH_MISMATCH
                    headers.put("Cache-Control", "no-store");
                    return new WebResourceResponse(guessMime(ref.name), null, 200, "OK", headers, in);
                } catch (Exception e) {
                    try {
                        return new WebResourceResponse("text/plain", "utf-8", 500, "Error",
                                new HashMap<String, String>(),
                                new ByteArrayInputStream(("io error: " + e.getMessage())
                                        .getBytes(StandardCharsets.UTF_8)));
                    } catch (Exception ignore) {
                        return null;
                    }
                }
            }
        });
    }

    private String describe(WebResourceError e) {
        if (e == null) return "页面加载失败";
        try { return String.valueOf(e.getDescription()); } catch (Exception ignore) { return "页面加载失败"; }
    }

    private void showError(String msg) {
        pageReady = false;
        runOnUiThread(() -> {
            webView.setVisibility(View.GONE);
            errorBox.setVisibility(View.VISIBLE);
            tvError.setText("打不开统计网页\n\n" + (msg == null ? "" : msg)
                    + "\n\n网址：" + (loadedBase == null ? resolveBaseUrlCached() : loadedBase)
                    + "\n\n请检查：① 电脑是否开机且 WordCountWeb 服务在运行 ② 手机网络是否正常"
                    + " ③ 网址是否选对（设置页）");
        });
    }

    // ==================== 文件接收 ====================

    private void handleIntent(Intent intent) {
        if (intent == null) { finish(); return; }

        String action = intent.getAction();
        Uri uri = null;
        String name = "共享文件";
        String mime = intent.getType();

        if (Intent.ACTION_VIEW.equals(action)) {
            uri = intent.getData();
            if (uri != null) name = guessName(uri);
        } else if (Intent.ACTION_SEND.equals(action)) {
            Uri stream = intent.getParcelableExtra(Intent.EXTRA_STREAM);
            if (stream != null) { uri = stream; name = guessName(stream); }
        } else if (Intent.ACTION_SEND_MULTIPLE.equals(action)) {
            // 多选分享：多个文件一次性追加多行
            ArrayList<Uri> list = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
            if (list != null && !list.isEmpty()) {
                for (Uri u2 : list) enqueue(u2, guessName(u2), mime);
                ensureLoaded();
                return;
            }
        }

        if (uri == null) {
            Toast.makeText(this, "未收到文件，请从微信/千牛「用其他应用打开」选择本应用",
                    Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        enqueue(uri, name, mime);
        ensureLoaded();
    }

    private void enqueue(Uri uri, String name, String mime) {
        pending.add(new PendingFile(uri, name, mime));
    }

    /** 保证 WebView 已加载目标页面；已就绪则立刻投递待处理文件 */
    private void ensureLoaded() {
        new Thread(() -> {
            String base = resolveBaseUrl();
            if (base.endsWith("/")) base = base.substring(0, base.length() - 1);
            authenticateIfNeeded(base);   // frp 访问密码：先用 HttpURLConnection 授权（对兜底路径有效）
            final String fb = base;
            runOnUiThread(() -> {
                if (pageReady && fb.equals(loadedBase)) {
                    flushPending();
                } else if (fb.equals(loadedBase) && webView.getUrl() != null) {
                    // 页面正在加载中，等 onPageFinished 再投递
                } else {
                    pageReady = false;
                    loadedBase = fb;
                    errorBox.setVisibility(View.GONE);
                    webView.setVisibility(View.VISIBLE);
                    loadBase(fb);
                }
            });
        }).start();
    }

    private void loadBase(String base) {
        loadedBase = base;
        authTried = false;
        // 页面内部把相对路径解析到这个 base
        webView.loadUrl(base + "/");
    }

    private void onPageLoaded() {
        // 每次页面加载完成都探测一次钩子；拿不到才考虑授权/兜底
        webView.evaluateJavascript("typeof window.wcBridgeAdd==='function'",
                (ValueCallback<String>) v -> {
                    if ("true".equals(v)) {
                        pageReady = true;
                        flushPending();
                        return;
                    }
                    // 不是我们的页面 → 多半是 frp「访问密码」的授权页：在页面内 POST 授权后重载一次
                    SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
                    String pw = sp.getString(KEY_ACCESS_PW, "").trim();
                    if (!authTried && !pw.isEmpty() && !isLocal(loadedBase)) {
                        authTried = true;
                        injectAuthAndReload(pw);
                        return;
                    }
                    // 授权后仍拿不到钩子（例如网页版本过旧）→ 交原生上传兜底
                    probeAndFlush();
                });
    }

    /** 页面里执行授权 POST（与页面同源，成功会 Set-Cookie 到 WebView 的 CookieManager） */
    private void injectAuthAndReload(String pw) {
        String js = "(function(){try{"
                + "fetch('/',{method:'POST',cache:'no-store',"
                + "headers:{'Content-Type':'application/x-www-form-urlencoded'},"
                + "body:'pw='+encodeURIComponent(" + JSONObject.quote(pw) + ")+'&persist_auth=on'})"
                + ".then(function(){location.reload();})"
                + ".catch(function(){location.reload();});"
                + "}catch(e){location.reload();}})();";
        webView.evaluateJavascript(js, null);
    }

    /** 页面钩子不可用：把待处理文件交给原生上传兜底（旧行为，可用但不累加） */
    private void probeAndFlush() {
        pageReady = false;
        List<PendingFile> todo;
        synchronized (pending) {
            todo = new ArrayList<>(pending);
            pending.clear();
        }
        if (todo.isEmpty()) return;
        for (PendingFile f : todo) {
            new Thread(() -> doNativeUpload(f.uri, f.name, f.mime)).start();
        }
    }

    /** 把待处理文件注入网页（追加行） */
    private void flushPending() {
        List<PendingFile> todo;
        synchronized (pending) {
            todo = new ArrayList<>(pending);
            pending.clear();
        }
        if (todo.isEmpty()) return;
        if (tokenFiles.size() > 32) tokenFiles.clear();   // 防无限增长
        for (PendingFile f : todo) {
            String token = "t" + tokenSeq.incrementAndGet() + "_" + System.currentTimeMillis();
            tokenFiles.put(token, new FileRef(f.uri, f.name));
            String path = INTERCEPT_PREFIX + token;
            String js = "window.wcBridgeAdd(" + JSONObject.quote(f.name) + ","
                    + JSONObject.quote(path) + ");";
            webView.evaluateJavascript(js, null);
        }
    }

    // ==================== 设置读取 ====================

    private boolean isLocal(String base) {
        return base != null && (base.contains("127.0.0.1") || base.contains("localhost"));
    }

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
        if (sp.getBoolean(KEY_AUTO, false)) {
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

    /** 供错误页/重试用的同步版本（不发网络请求，避免主线程阻塞） */
    private String resolveBaseUrlCached() {
        SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
        String manual = sp.getString(KEY_URL, DEF_URL).trim();
        if (manual.isEmpty()) manual = DEF_URL;
        return manual;
    }

    /**
     * 若设置了 frp 访问密码，连接前先 POST 授权并持 cookie。
     * 文档：POST pw=<密码>&persist_auth=on 到隧道根地址即完成授权。
     */
    private void authenticateIfNeeded(String base) {
        SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
        String pw = sp.getString(KEY_ACCESS_PW, "").trim();
        if (pw.isEmpty()) return;
        if (isLocal(base)) return;
        try {
            URL u = new URL(base + "/");
            HttpURLConnection c = (HttpURLConnection) u.openConnection();
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setConnectTimeout(8000);
            c.setReadTimeout(8000);
            c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            String body = "pw=" + URLEncoder.encode(pw, "UTF-8") + "&persist_auth=on";
            try (OutputStream os = c.getOutputStream()) {
                os.write(body.getBytes(StandardCharsets.UTF_8));
            }
            c.getResponseCode();
            c.disconnect();
        } catch (Exception ignore) { }
    }

    /**
     * 放行自签证书（兜底的原生上传路径用；WebView 侧由 onReceivedSslError 放行）。
     * SakuraFrp「自动 HTTPS」用自签 CA，系统默认校验会直接 SSLHandshakeException。
     */
    private void installRelaxedTls() {
        try {
            javax.net.ssl.TrustManager[] tm = new javax.net.ssl.TrustManager[]{
                    new X509TrustManager() {
                        public void checkClientTrusted(X509Certificate[] chain, String authType) { }
                        public void checkServerTrusted(X509Certificate[] chain, String authType) { }
                        public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
                    }
            };
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, tm, new java.security.SecureRandom());
            HttpsURLConnection.setDefaultSSLSocketFactory(ctx.getSocketFactory());
            HttpsURLConnection.setDefaultHostnameVerifier((hostname, session) -> true);
        } catch (Exception ignore) { }
    }

    // ==================== 文件信息 ====================

    private long querySize(Uri uri) {
        try {
            if ("content".equals(uri.getScheme())) {
                Cursor c = getContentResolver().query(uri,
                        new String[]{OpenableColumns.SIZE}, null, null, null);
                if (c != null) {
                    int idx = c.getColumnIndex(OpenableColumns.SIZE);
                    long v = -1;
                    if (idx >= 0 && c.moveToFirst()) v = c.getLong(idx);
                    c.close();
                    return v;
                }
            }
        } catch (Exception ignore) { }
        return -1;
    }

    private String guessMime(String name) {
        try {
            // 注意：MimeTypeMap.getFileExtensionFromUrl 对中文文件名会返回空，
            // 故直接从文件名取扩展名
            int dot = (name == null) ? -1 : name.lastIndexOf('.');
            if (dot >= 0 && dot < name.length() - 1) {
                String ext = name.substring(dot + 1).toLowerCase();
                String m = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
                if (m != null) return m;
            }
        } catch (Exception ignore) { }
        return "application/octet-stream";
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

    // ==================== 原生上传兜底（网页钩子不可用时）====================
    // 仅当网页版页面拿不到 window.wcBridgeAdd（例如版本太旧）时才走到这里。
    // v1.1.0 修复：分片 multipart 在数据后多写了一个 CRLF，导致每个分片边界多 2 字节，
    //   >6MB 的文件会损坏（DWG 表现为静默 0 字）。此处改为标准 multipart 结尾。

    private void doNativeUpload(Uri uri, String name, String mime) {
        try {
            String base = resolveBaseUrl();
            if (base.endsWith("/")) base = base.substring(0, base.length() - 1);
            authenticateIfNeeded(base);

            if (!checkReachable(base)) {
                throw new Exception("无法连接统计服务器（" + base + "）。请检查电脑是否开机、"
                        + "WordCountWeb 服务是否运行，并在设置页确认网址");
            }

            InputStream is = getContentResolver().openInputStream(uri);
            byte[] data = readAll(is);
            long sizeBytes = data.length;
            String sizeStr = formatSize(sizeBytes);

            SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
            boolean wordVerify = sp.getBoolean(KEY_WORD_VERIFY, true);

            final String tip = sizeBytes > CHUNK_THRESHOLD
                    ? "正在上传（" + sizeStr + "，分片并行）…" : "正在上传（" + sizeStr + "）…";
            runOnUiThread(() -> Toast.makeText(this, tip, Toast.LENGTH_SHORT).show());

            String jobId;
            if (sizeBytes > CHUNK_THRESHOLD) {
                jobId = uploadChunked(base, name, data, wordVerify, sizeBytes);
            } else {
                jobId = uploadSingle(base + "/api/upload", name, mime, data, wordVerify);
            }

            String target = base + "/?job=" + jobId + "&name=" + Uri.encode(name);
            openInSystemBrowser(target);
            runOnUiThread(() -> {
                Toast.makeText(this, "已跳转，请在浏览器查看统计结果", Toast.LENGTH_SHORT).show();
                finish();
            });
        } catch (Exception e) {
            final String msg = e.getMessage();
            runOnUiThread(() -> {
                Toast.makeText(this, "上传失败：" + (msg == null ? "未知错误" : msg),
                        Toast.LENGTH_LONG).show();
                finish();
            });
        }
    }

    private boolean checkReachable(String baseUrl) {
        try {
            URL url = new URL(baseUrl + "/api/health");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(10000);
            int code = conn.getResponseCode();
            conn.disconnect();
            return code >= 200 && code < 400;
        } catch (Exception e) {
            return false;
        }
    }

    /** 单请求上传（小文件 ≤5MB） */
    private static String uploadSingle(String urlStr, String fileName, String mime,
                                       byte[] data, boolean wordVerify) throws Exception {
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
            sb.append("Content-Type: ").append(mime == null ? "application/octet-stream" : mime)
                    .append("\r\n\r\n");
            os.write(sb.toString().getBytes(StandardCharsets.UTF_8));
            os.write(data);
            os.write("\r\n".getBytes(StandardCharsets.UTF_8));
            StringBuilder sbwv = new StringBuilder();
            sbwv.append("--").append(boundary).append("\r\n");
            sbwv.append("Content-Disposition: form-data; name=\"word_verify\"\r\n\r\n");
            sbwv.append(wordVerify ? "1" : "0");
            os.write(sbwv.toString().getBytes(StandardCharsets.UTF_8));
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

    /** 分片并行上传（大文件 >5MB），全部到位后 commit 合并 */
    private String uploadChunked(String base, String fileName, byte[] data,
                                 boolean wordVerify, long totalSize) throws Exception {
        String uid = Long.toHexString(System.nanoTime());
        int totalChunks = Math.max(1, (int) ((data.length + CHUNK_SIZE - 1) / CHUNK_SIZE));

        Thread[] threads = new Thread[Math.min(CONCURRENCY, totalChunks)];
        Throwable[] errors = new Throwable[1];
        errors[0] = null;

        for (int t = 0; t < threads.length; t++) {
            final int workerId = t;
            threads[t] = new Thread(() -> {
                for (int i = workerId; i < totalChunks; i += threads.length) {
                    if (errors[0] != null) return;
                    try {
                        int start = i * CHUNK_SIZE;
                        int end = Math.min(start + CHUNK_SIZE, data.length);
                        byte[] chunk = java.util.Arrays.copyOfRange(data, start, end);
                        postChunk(base, uid, fileName, i, totalChunks, chunk);
                    } catch (Exception e) {
                        synchronized (errors) {
                            if (errors[0] == null) errors[0] = e;
                        }
                    }
                }
            });
            threads[t].start();
        }

        for (Thread t : threads) t.join(30000);
        if (errors[0] != null) throw new Exception("分片上传失败: " + errors[0].getMessage());

        return commitUpload(base, uid, fileName, totalChunks, wordVerify, totalSize);
    }

    private void postChunk(String base, String uid, String name,
                           int index, int total, byte[] chunkData) throws Exception {
        String boundary = "----WCchunk" + System.currentTimeMillis();
        URL url = new URL(base + "/api/upload_chunk");
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setConnectTimeout(60000);
        conn.setReadTimeout(60000);
        conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);

        try (OutputStream os = conn.getOutputStream()) {
            writePart(os, boundary, "uid", null, uid.getBytes(StandardCharsets.UTF_8));
            writePart(os, boundary, "name", null, name.getBytes(StandardCharsets.UTF_8));
            writePart(os, boundary, "index", null, String.valueOf(index).getBytes(StandardCharsets.UTF_8));
            writePart(os, boundary, "total", null, String.valueOf(total).getBytes(StandardCharsets.UTF_8));
            writePart(os, boundary, "data", "application/octet-stream", chunkData);
            // v1.1.0 修复：writePart 已在数据后写过 CRLF，这里不能再多写一个
            //   （旧版写成 "\r\n--boundary--" → 每片多 2 字节 → 文件在分片边界损坏）
            os.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        }

        int code = conn.getResponseCode();
        InputStream in = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
        String resp = new String(readAll(in), StandardCharsets.UTF_8);
        conn.disconnect();
        if (code < 200 || code >= 300) {
            throw new Exception("分片 " + index + " HTTP " + code + ": "
                    + resp.substring(0, Math.min(200, resp.length())));
        }
    }

    private String commitUpload(String base, String uid, String name, int total,
                                boolean wordVerify, long totalSize) throws Exception {
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
            writePart(os, boundary, "word_verify", null, (wordVerify ? "1" : "0").getBytes(StandardCharsets.UTF_8));
            // v1.1.0：让服务端校验合并后字节数，损坏就明确报错而不是静默算 0
            writePart(os, boundary, "expect_size", null, String.valueOf(totalSize).getBytes(StandardCharsets.UTF_8));
            // 同上：writePart 已写过 CRLF
            os.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
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

    private static byte[] readAll(InputStream is) throws Exception {
        if (is == null) throw new Exception("无法读取文件内容");
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

    private void openInSystemBrowser(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception ignore) { }
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
            return;
        }
        super.onBackPressed();
    }
}
