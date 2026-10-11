---
name: waters-radio-subtitle-sync
summary: 文档型 skill——讲清 WATERS RADIO「同步字幕」板块的工作原理：网页 Web Audio 采 PCM → 原生 sherpa-onnx 离线流式识别 → 网页逐词折行渲染，以及 v9 起可选的在线翻译增强与全屏大字模式。
description: >
  当有人问 WATERS RADIO（icewatershuang/WATERSRADIO 项目）的「同步字幕 / 语音字幕 / 离线识别 / 字幕怎么来的 / 字幕为什么不滚动 / 全屏大字字幕 / 在线翻译增强」相关工作原
  理、架构、数据流、坑点时触发。本 skill 是原理说明书，不替你改代码，只把三层链路（采集→识别→渲染）、关键不变量与文件索引讲清楚，便于后续二次开发或排错。
---

# WATERS RADIO · 同步字幕板块工作原理

## 这个板块解决什么
把一个外语直播电台（多为英语）的**实时语音**，在本地设备上识别成**与播放同步的文字字幕**，显示在 LED 屏底部字幕带（正常模式）或全屏大字区（v9 纯净全屏模式）。
核心是「**离线、流式、实时**」：不依赖云端识别，语音一出口就逐词冒出，延迟在 1 秒以内。

## 三层架构（一条数据链）
```
[网页 Web Audio] 采集 PCM ──base64 过桥──▶ [原生 sherpa-onnx] 流式识别 ──__onAsr()──▶ [网页] 逐词折行渲染
   MediaElementSource          WatersNative.asrPcm(b64)        AsrController.feedPcm        renderSubtitle()
```
- **网页侧**（`app-web/index.html`，也是 APK 内置 `assets/index.html` 的来源）：负责音频采集、字幕 UI、与用户交互。
- **原生侧**（`android/app/.../RadioPlaybackService.java` 内 `JsBridge` + `AsrController.java`）：负责真正的语音识别（sherpa-onnx 流式 Zipformer 模型，模型在 `assets/asr/`）。
- 两侧通过 `window.WatersNative`（原生注入的 JS 桥）互相调用。

## 数据流时序
1. 用户打开「语音字幕」开关 → 网页 `setAsr(true)` → 桥 `WatersNative.setAsr('1')` → 原生 `AsrController.setEnabled(true)`。
2. 同一时刻网页启动 **Web Audio 采集图**：`audioCtx.createMediaElementSource(audio)`（**一生只能建一次**，全局复用）→ `ScriptProcessorNode(4096,1,1)`。
3. 每个音频回调里：下混单声道 → 按 `audioCtx.sampleRate/16000` 重采样到 **16kHz** → 量化 16-bit → `btoa` 成 base64 → `WatersNative.asrPcm(b64)` 过桥。
4. 原生 `AsrController.feedPcm(pcm)` 把字节喂进 sherpa 流式识别器；模型持续产出：
   - **partial**（中间结果）：当前已识别的**累计**文本；
   - **final**（一句话结束）：该句定稿文本 + 端点检测（约 0.5s）。
5. 原生每次结果都 `callJs("window.__onAsr(" + quote(text) + "," + isFinal + ")")`。
6. 网页 `window.__onAsr(text, isFinal)` 把文本写进 `asrCur` 并调 `renderSubtitle()`，把字幕逐词、按 LED 屏宽度折行、零滚动地渲染出来。

## ① 网页端采集（关键约束）
- `MediaElementSource` 必须由同一个 `<audio>` 元素创建，且**全局只建一次**（第二次会抛错）；源与播放共用同一 `audioCtx`。
- 重采样用最简单的「跨步取点」（`step = sampleRate/16000`），够用；chunk 大小 4096 是实测最优（≈100ms），别乱改。
- 只有 `WatersNative.asrPcm` 存在（即跑在 APK 里且设备 API≥21、非低内存）才采；浏览器里 `asrAvailable()` 返回 false，整项开关隐藏。

## ② 原生识别（sherpa-onnx，v8.0 起取代 Vosk）
- 模型：`assets/asr/` 下 encoder/decoder/joiner 的 **int8 onnx** + `tokens.txt`（~70MB，CI 构建时下载，不在仓库）。**必须** `build.gradle` 里 `androidResources{ noCompress 'onnx' }`，否则 `AssetManager.openFd()` 取不到 fd。
- API 坑（写死别踩）：`createStream("")` 无参版不存在；`getResult(stream)` 返回的是 `OnlineRecognizerResult` 不是 String，要 `.getText()`。
- 兼容性坑：sherpa 1.13.8 的 `libonnxruntime.so` ELF min SDK 注记是 27，CI 里用 `patch_onnx_minapi.py` 降到 21；且要显式 `implementation 'org.jetbrains.kotlin:kotlin-stdlib:1.9.24'`，否则真机开字幕抛 `NoClassDefFoundError`（kotlin 类=0）。
- 当前内置**仅英文**流式模型；设中文会静默回退英文。

## ③ 网页渲染（renderSubtitle，v8.0.5 起的「逐词折行」模型）
- 状态：当前句 `asrCur`、是否定稿 `asrCurFinal`、是否在听 `asrListening`。
- 逐词折行逻辑（`appendWord`）：
  - partial 文本 `asrCur.toLowerCase().split(/\s+/)` 成词，新词以 `.sub-word.pop` 上浮淡入进入当前行；
  - 当前行累计宽度（`offsetWidth`）触到 LED 右缘（`wrap.clientWidth`）且已有 ≥1 词 → 该词移到**新行（从左边起）**；
  - 多行 flex 列底部锚定（`justify-content:flex-end`），超出 `SUB_MAX_LINES`（竖屏 3 / 横屏 2）从顶部整行裁切（不是像素滚动）。
- **新句判定**：`sameSentence` 前缀校验——已显示词不是当前文本前缀（纠错/换句）→ 整屏清空重建（无逐词动画）。

## 关键不变量 / 坑点（改字幕前必读）
1. **partial 是「累计文本」不是增量**：`"the"→"the cat"→"the cat sat"`。任何「原句 + 分隔 + 原句」的重复拼接都会翻倍出怪句（v8.0.4 修过）。
2. **固定屏高 + 字幕带固定高度**：`.screen-main` 固定高（竖屏 228 / 横屏 204，`overflow:hidden`），字幕带用 `height:calc()` 而非内容自适应，否则会与时钟/圆盘行重叠。横屏 204 仅容 2 行、竖屏 228 给 3 行。
3. **横向滚动 / 跑马灯 与「固定屏高 + 逐行」互斥**：用户要的是按屏宽换行，换行判定必须用**真实 DOM 宽度**，不能靠 CSS 自动折行（nowrap 量 offsetWidth）。
4. **主题差异**：`eink`/`mono` 主题要去字幕 glow（`text-shadow:none`），`mono` 是纯黑白。
5. **`asrAvailable()` 竞态**：桥在 `RadioPlaybackService` 的 `asr` 对象创建后才可用；页面启动期探测常返回 false → 整项被永久隐藏。修复 = 「启动 + 每次打开设置」都复查（`syncSubtitleAvailability`）。
6. **CJK 长句**：中文无空格，`split(/\s+/)` 会得到一整句一个 token，正常字幕会整段不折行；全屏大字区改用 CSS 自动换行（`word-break:break-word`）避免溢出。

## v9 起新增：在线翻译增强 + 全屏大字模式
- **在线翻译增强（可选，默认关）**：在设置里逐个开关「免费翻译源」（如 MyMemory en→zh，免密钥），把 ASR 的 **final** 段译为中文，与本地模型配合提高正确率。统一出口 `getDisplayText()` = 翻译优先否则原文；**正常字幕与全屏字幕共用同一份文本，保证一致**。
- **纯净全屏模式（v9 新）**：设置里开「纯净全屏模式」并选竖屏/横屏 → 网页置 `html[data-fs=portrait|landscape]` + 调 `WatersNative.setOrientation` 锁方向 + `setImmersive` 隐藏状态栏；CSS 强制纯黑白、隐藏一切无关元素，**只保留 LED 屏 / 频谱仪 / 预设电台**，剩余空间用翻译后的同步字幕（大字、CSS 自动换行）填满。该模式是**叠加**在既有功能上的新分支，默认关，不动其它成熟功能。

## 文件索引（二次开发/排错直奔这里）
- 网页采集 + 渲染 + 桥调用：`app-web/index.html`
  - 采集图：`createMediaElementSource` / `createScriptProcessor(4096)` / 重采样 / `WatersNative.asrPcm`（搜这些关键词）
  - 回调与渲染：`window.__onAsr`（搜 `__onAsr`）、`renderSubtitle`、`appendWord`、`clearSubtitle`、`setAsr`、`setAsrLang`
  - 全屏 + 翻译：`data-fs`、`pureFsOn/applyPureFs`、`TRANSLATORS`、`getDisplayText`、`#fsSubtitle`
- 原生桥：`android/app/src/main/java/com/waters/radio/RadioPlaybackService.java` 内 `class JsBridge`（`setAsr`/`setAsrLang`/`asrPcm`/`setImmersive`/`setOrientation`/`asrAvailable`）
- 原生识别：`android/app/src/main/java/com/waters/radio/AsrController.java`（`feedPcm`、`isAvailable`、sherpa 流式调用、`callJs("window.__onAsr(...)")`）
- 窗口/方向：`MainActivity.java`（`setImmersiveMode`、`setOrientation`、`applyImmersive`）
- 版本指纹见项目 MEMORY.md（v7.x Vosk → v8.0 sherpa → v8.0.4~8.0.6 字幕打磨 → v9 全屏大字+在线翻译）。

## 发布链（改完字幕/全屏后怎么上线）
四件套脚本 `_wsh_{push,wait_build,release,build_dl}_vNNN.py`（从上一版 `sed` 派生）：Git Data API 单提交（blob→tree→commit→ref）直推 `main` → poll 名为 **`Build APK`** 的 workflow run（避开 Pages 自带的 `pages build and deployment`）→ 建 Release 经 **`uploads.github.com`** 挂 APK（勿打 `api.github.com`）→ 分块下载构件校验（`classes.dex` 里 `kotlin_in_dex`、资源指纹等）。令牌在 `icewatershuang` 账号，存本机文件读取，不进对话/日志。
