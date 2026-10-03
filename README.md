# WATERS RADIO · 唱片机横版

民谣 / 流行音乐公号「经济上行期之声」配套电台播放器。当前阶段以**网页版**为准，读者点开链接即用。

## 网页版

- 文件：根目录 `index.html`（配合同目录 `hls.min.js` 使用，离线可解析 HLS）。
- 托管：GitHub Pages，部署 `main` 分支根目录，访问 <https://icewatershuang.github.io/WATERSRADIO/>。

## 界面设计

参照白胶唱片机参考图重做的**横版拟物风**：

- **左栏 · 唱片机**：CSS 绘制的黑胶唱片（真实纹路）+ 蓝色唱片芯 + 唱针；播放时唱片旋转、唱针落下，暂停即抬起。
- **右栏 · 控制台**：上一台 / 收藏 / 播放 / 静音 / 下一台 + 音量滑条，下方为精选一键直达与 Tab（收藏 / 预设 / 全球 / 定制）。
- 配色：奶白底 + 白色卡片配双向柔和阴影（拟物），蓝色 `#3f7ce8` 作点缀。
- 标题：手写花体「Waters Radio」，左端与主显示屏对齐、竖屏时底端与设置键底端对齐，带凸面雕刻质感。
- 版式：整页不滚动，唱片尺寸随屏幕高度自适应；竖屏窄机自动退化为上下堆叠。

## 功能

1. **首播台 = WLTW（106.7 Lite FM，纽约）**：首次打开即定位并尝试播放。
   - 主源（HTTPS HLS）：`https://stream.revma.ihrhls.com/zc1477/hls.m3u8?streamid=1477`
   - 备源：`http://stream.revma.ihrhls.com/zc1477`
   - 两条同名自动合并为多源，主源失效时秒切备源，不空档。
2. **精选台（一键直达，3 个）**：WLTW · 纽约、80 后音悦台、广东音乐之声。
3. **完整保留原程序能力**：934+ 预设电台、收藏、全球搜索（radio-browser）、自定义流、多源热备、设置后台（校验 / 巡检 / 黑名单 / 备份恢复 / 日志）全部不变；仅去除原文件的腾讯 beacon 外部埋点。

## 代理（http 流的 https 中转）

浏览器会拦截 https 页面里的 `http://` 音频流，需要一层 https 中转。播放器内置**代理池 + 健康分自动切换**：多路自建通道（Cloudflare Worker）互为热备，任一路挂掉自动顶上，不中断播放。

部署方法见 [`worker/部署说明.md`](worker/部署说明.md)（免费，约 3 分钟；建议部署 2~3 份实现真热备）。

## 本地预览

```bash
cd waters-radio
python3 -m http.server 8080
# 浏览器打开 http://localhost:8080
```

## 目录结构

```
.
├── index.html     # 网页版（唯一数据源）
├── hls.min.js     # 本地 HLS 解析库（离线可用）
├── worker/        # Cloudflare Worker 代理源码 + 部署说明
├── android/       # WebView 壳工程（构建时自动把根目录网页打进 assets）
├── .github/workflows/
│   └── build-apk.yml  # 推送后自动编译 Debug APK
├── README.md
└── .nojekyll      # 让 Pages 原样服务所有文件
```

## 已知限制

- 网页版受浏览器自动播放策略限制，首次打开会显示「点击播放 WLTW ▶」，点一下即播。
- 数据（收藏、音量、黑名单等）保存在浏览器本机 localStorage，换设备前请在设置里「备份」。

> APK 版本：推送到 `main` 后由 GitHub Actions 自动编译，产物在该 workflow 的 Artifacts 里下载（`waters-radio-apk`）。
