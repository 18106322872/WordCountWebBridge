# WordCount 网页版 · 桥接 APK

让 WordCount 网页版出现在微信/千牛「用其他应用打开」列表里的极简桥接 App。

## 它做什么
1. 你在微信/千牛里点文件的「用其他应用打开」→ 选 **WordCount 网页版**
2. 本 App 把文件 POST 到网页版后端 `/api/upload` 拿到任务 ID
3. 自动调起浏览器打开 `https://<统计网址>/?job=<任务ID>`，网页版轮询并展示统计结果

## 配置统计网址
- 设置页默认开启 **「自动发现网址」**：App 启动/上传前会自动从 GitHub 发现通道
  （`18106322872/WordCountWebBridge` 仓库 `tunnel-url` 分支的 `tunnel_url.txt`）拉取
  PC 端服务实时发布的当前 Cloudflare Quick Tunnel 域名。**PC 重启换域名也能自动跟上，无需手动改**
- PC 端 `WordCountWeb` 服务后台线程每 15s 检测 `cloudflared/tunnel.log`，域名一变就把新地址
  PUT 到上述发现通道（需要 `cloudflared/.publish_token` 里的 GitHub Token）
- 若发现通道不可用，App 自动回退到手动/默认地址。想完全手动控制可关掉「自动发现网址」开关，
  自行在设置页填网址（默认 `https://expert-cambridge-identity-walk.trycloudflare.com`）

## 本地构建（可选）
需要 Android SDK + JDK17：
```
gradle clean assembleDebug
# 产物：app/build/outputs/apk/debug/WordCountWebBridge.apk
```

## 通过 GitHub Actions 出包（推荐）
1. 在 GitHub 新建仓库（如 `WordCountWebBridge`），把本目录内容 push 上去
2. 打 tag 触发 CI 自动构建并发布 APK：
   ```
   git tag apk
   git push --tags
   ```
3. 等 Actions 跑完，到仓库 Releases 里下载 `WordCountWebBridge.apk`

## Word 精确模式（v1.0.55 起）
- 桥接 App 是**原生上传**（微信/千牛「用其他应用打开」→ 原生 POST，不经过网页 JS），
  所以网页里的「Word 精确模式」勾选框对桥接已传完的文件无效。
- **设置页新增「Word 精确模式」开关（默认开启）**：开启后桥接上传会自动带 `word_verify=1`，
  让 .docx 走本机 Word 精确统计（与电脑版口径一致）；关闭则走程序自有快速口径。
- 若关闭后仍想某次精确统计，可在浏览器里直接拖入文件（走网页 JS，勾选框生效）。

## 文件结构
- `app/src/main/java/com/henry/wordcount/bridge/BridgeActivity.java` —— 接收文件并上传
- `app/src/main/java/com/henry/wordcount/bridge/SettingsActivity.java` —— 配置网址
- `app/src/main/AndroidManifest.xml` —— 注册文档类型处理器（出现在「打开」列表）
- `.github/workflows/build-apk.yml` —— tag=apk 自动出包
