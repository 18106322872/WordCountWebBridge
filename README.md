# WordCount 网页版 · 桥接 APK

让 WordCount 网页版出现在微信/千牛「用其他应用打开」列表里的极简桥接 App。

## 它做什么（v1.1.0 起）

1. 你在微信/千牛里点文件的「用其他应用打开」→ 选 **WordCount 网页版**
2. App 直接打开**网页版页面本身**（内置 WebView），并把文件交给这个页面
3. 上传、进度条、统计结果全部由网页完成：
   - 再分享一个文件 → **在同一个页面的下面追加一行**（和手机版程序一样同页累加统计），
     而不是每分享一次就新开一个网页
   - 上传进度显示在**网页自己的进度条**上（不再是 App 那个没有进度条的页面）

技术实现：App 在 WebView 的 `shouldInterceptRequest` 里拦截
`/__wcbridge__/<token>` 这个**与页面同源**的特殊路径，直接回文件字节；
网页侧 `window.wcBridgeAdd(name, path)` 拿到文件后走正常的分片上传流程。
好处是文件不经过 base64/JS 字符串，任意大小都能流式交给网页。

> v1.0.39~v1.0.56 的旧流程（App 原生上传 + 调系统浏览器打开 `?job=`）会**每分享一次开一个新网页**，
> 且原生 multipart 分片多写了一个 CRLF，导致 >6MB 的文件在每个分片边界损坏
> （DWG 表现为**静默统计 0 字**）。v1.1.0 已整体改掉；原生上传仅作为网页不可用时的兜底，
> 并已修正该 CRLF bug。

## 配置统计网址

设置页提供 **「统计网址」下拉框**，三个选项直接选：

| 选项 | 用途 |
|---|---|
| `https://dx.frp-boy.com:41086` | **默认**，樱花 frp 国内中转，电信快 |
| `https://wordcount.dpdns.org` | Cloudflare 固定域名，兜底、无需密码 |
| `http://127.0.0.1:8000` | 本机同机调试 |

- **选哪个就用哪个，默认就是 frp**。
- 若 frp 隧道开启了「访问密码」，在设置页 **「frp 访问密码」** 里填入。
  连接前 App 会在页面内自动 POST 授权（`pw=...&persist_auth=on`）并持 cookie，
  之后无需再用浏览器手动授权；留空则需在手机浏览器先访问该网址授权一次。
- 「自动发现网址」开关默认 **关闭**（让下拉框直选生效）；开启后会忽略下拉框，
  改为从 GitHub 发现通道（`18106322872/WordCountWebBridge` 仓库 `tunnel-url` 分支的
  `tunnel_url.txt`）拉取 PC 端实时发布的域名。

## 统计完成通知（v1.1.1）

分享大文件后你可以直接切回微信/千牛，**不用一直盯着页面等结果**：

- 网页在「本页所有文件都统计完」时会调用 `window.WCBridgeNotify.onDone(成功数, 失败数)`
  （App 通过 `addJavascriptInterface` 注入该接口）
- App 随即弹出与**手机版程序完全同款**的通知：
  - 标题 `WordCount 统计完成`，内容 `全部文件已统计完成`（有失败时附带失败个数）
  - 独立通道「统计完成」（默认重要性 + **系统默认提示音**，可在系统设置里改）
  - 点击通知**回到统计页面**看结果；回到前台时该通知自动清除
- Android 13+ 需要通知权限，App 首次进入会申请一次 `POST_NOTIFICATIONS`

> 在浏览器里打开网页版时没有这个接口，`wcBridgeAdd` / 通知调用都会静默跳过，互不影响。

## 本地构建（可选）

需要 Android SDK + JDK17：

```
gradle clean assembleDebug
# 产物：app/build/outputs/apk/debug/WordCountWebBridge.apk
```

## 通过 GitHub Actions 出包（推荐）

1. 打 tag 触发 CI 自动构建并发布 APK：
   ```
   git tag apk
   git push --tags
   ```
2. 等 Actions 跑完，到仓库 Releases 里下载 `WordCountWebBridge.apk`

> CI 坑（已修）：`android-actions/setup-android@v3` 在 runner 镜像升级到 cmdline-tools 16.0 后
> 会因官方废弃 `tools` 包而 `Failed to find package 'tools'` 直接 exit 1。
> 现在改为直接用 runner 自带 SDK 的 `sdkmanager` 装组件。

## Word 精确模式

- v1.1.0 起桥接走的是**网页上传**，所以网页里的「Word 精确模式」勾选框**对桥接文件同样生效**。
- 设置页的「Word 精确模式」开关（默认开启）只作用于**原生上传兜底路径**（网页不可用时）。

## 文件结构

- `app/src/main/java/com/henry/wordcount/bridge/BridgeActivity.java` —— WebView 宿主 + 文件注入 + 原生上传兜底
- `app/src/main/java/com/henry/wordcount/bridge/SettingsActivity.java` —— 配置网址 / 访问密码
- `app/src/main/AndroidManifest.xml` —— 注册文档类型处理器（`singleTask`，多次分享复用同一页面）
- `app/src/main/res/layout/activity_bridge.xml` —— WebView + 打不开时的错误页
- `.github/workflows/build-apk.yml` —— tag=apk 自动出包
