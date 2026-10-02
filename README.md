# WATERS RADIO · 单屏精简版

民谣 / 流行音乐公号「经济上行期之声」配套电台播放器。

- **网页版**：根目录 `index.html`（可直接用 GitHub Pages 托管，读者点链接即用）。
- **安卓 APK**：`android/` 原生 WebView 工程，通过 GitHub Actions 自动编译出包。

## 这个版本做了什么

1. **单屏不滚动 · 苹果播放器简约风**：顶部紧凑 Now Playing + 播放/音量控制，下方「精选」一键直达，底部 Tab（收藏 / 预设 / 全球 / 定制）切换，整页不滚动、各面板内部滚动。
2. **首播台 = WLTW（106.7 Lite FM，纽约）**：首次打开即定位并（在 APK 内）自动播放 WLTW。
   - 主源（HTTPS HLS）：`https://stream.revma.ihrhls.com/zc1477/hls.m3u8?streamid=1477`
   - 备源：`http://stream.revma.ihrhls.com/zc1477`
   - 两条同名自动合并为多源，主源失效时秒切换备源，不空档。
3. **精选台（一键直达）**：WLTW · 纽约、80 后音悦台、广东音乐之声、CRI 世界华声。
   - 注：CRI 音乐台原想用 CRI HIT FM，但其公开流地址（带 session 参数）已失效（HTTP 400）；改用稳定可用的 **CRI 世界华声**（`http://sk.cri.cn/hxfh.m3u8`）。如有更准的 CRI 音乐台可替换。
4. **完整保留原程序能力**：934+ 预设电台、收藏、全球搜索（radio-browser）、自定义流、多源热备、设置后台（校验/巡检/黑名单/备份恢复/日志）全部不变。仅去除了原文件的腾讯 beacon 外部埋点。

## 本地预览网页版

```bash
cd waters-radio
python3 -m http.server 8080
# 浏览器打开 http://localhost:8080
```

## 构建并发布 APK（GitHub Actions 自动出包）

仓库根目录 Push 后，Actions 工作流 `.github/workflows/build-apk.yml` 会：

1. 用 JDK 17 + Android SDK（runner 自带）搭建编译环境；
2. 把根目录 `index.html` / `hls.min.js` 同步进安卓 `assets/`（单一数据源，网页与 APK 永远一致）；
3. 用 Gradle 8.9 + AGP 8.5 编译 `assembleDebug`，产出自动签名的调试 APK；
4. 在 Actions 页面的 Artifacts 中下载 `waters-radio-apk` 即可安装分发。

> 调试 APK 可直接安装侧载；若要上架或正式分发，建议在仓库 Secrets 中配置签名密钥并改用 `assembleRelease`。

## 目录结构

```
.
├── index.html              # 网页版（也是 APK 加载的同一份）
├── hls.min.js              # 本地 HLS 解析库（离线可用）
├── android/                # 原生 WebView 安卓工程
│   ├── build.gradle
│   ├── settings.gradle
│   └── app/
│       ├── build.gradle
│       └── src/main/
│           ├── AndroidManifest.xml
│           ├── java/com/waters/radio/MainActivity.java
│           ├── res/values/styles.xml
│           ├── res/drawable/ic_launcher.xml
│           └── res/xml/network_security_config.xml
└── .github/workflows/build-apk.yml
```

## 已知限制

- 安卓端用 WebView 承载音频：前台/后台多数情况可继续播放，但系统内存紧张时可能被回收导致断流（如需「锁屏/熄屏稳定后台播放」，可后续升级为前台 Service + ExoPlayer 直连流地址方案）。
- 网页版受浏览器自动播放策略限制，首次打开会显示「点击播放 WLTW ▶」，点一下即播（APK 内已放开自动播放）。
