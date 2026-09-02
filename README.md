# WordCount 网页版 · 桥接 APK

让 WordCount 网页版出现在微信/千牛「用其他应用打开」列表里的极简桥接 App。

## 它做什么
1. 你在微信/千牛里点文件的「用其他应用打开」→ 选 **WordCount 网页版**
2. 本 App 把文件 POST 到网页版后端 `/api/upload` 拿到任务 ID
3. 自动调起浏览器打开 `https://<统计网址>/?job=<任务ID>`，网页版轮询并展示统计结果

## 配置统计网址
- 桌面上点开本 App 图标 → 设置页 → 填入统计网址（默认已填 `https://expert-cambridge-identity-walk.trycloudflare.com`，即当前 Cloudflare Quick Tunnel 地址）
- **重要**：Cloudflare Quick Tunnel 的域名每次 PC 重启都会变。PC 重启后需进 App 设置把网址改成新的 Cloudflare 地址（双击电脑上的 `open_tunnel.vbs` 可看到最新地址），**无需重新打包**

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

## 文件结构
- `app/src/main/java/com/henry/wordcount/bridge/BridgeActivity.java` —— 接收文件并上传
- `app/src/main/java/com/henry/wordcount/bridge/SettingsActivity.java` —— 配置网址
- `app/src/main/AndroidManifest.xml` —— 注册文档类型处理器（出现在「打开」列表）
- `.github/workflows/build-apk.yml` —— tag=apk 自动出包
