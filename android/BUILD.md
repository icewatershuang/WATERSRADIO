# WATERS RADIO APK 构建说明

## 一、这份工程是什么

在 `waters-radio/android/` 下有一份完整的 Android WebView 壳工程。它做的事：

| 功能 | 实现层 |
|---|---|
| 界面 UI（奶白拟物唱片机、电台库弹窗、所有设置） | HTML（`app/src/main/assets/index.html`），**与网页版一字不差** |
| 电台多源热备 / 收藏 / 全球搜索 / 定制 | HTML |
| 后台常驻 + 锁屏继续播放 | 原生 `RadioPlaybackService`（前台服务 + WakeLock + WiFi WakeLock） |
| 蓝牙遥控（AVRCP）：上一首/下一首/播放/暂停 | 原生 `MediaSession` → JS `watersApi.next()/prev()...` |
| 音量增减 | 蓝牙音量键 → 系统媒体音量（`MediaSession.setPlaybackToLocal(STREAM_MUSIC)`） |
| 锁屏/通知栏媒体卡片 | 原生 `Notification.MediaStyle` |

**音频本身仍在 HTML 的 `<audio>`/hls.js 里播**——原生层只负责"保活+转发按键"，这让网页的播放逻辑 100% 复用。

## 二、版本兼容（重要）

- `minSdk 14`（Android 4.0）：APK **装得上**
- ⚠️ **Android 4.0~4.3 装上是白屏**——那几个版本的系统 WebView 是古董 WebKit，不支持 HLS/CSS Grid/现代 JS。**实际能正常用的最低版本是 Android 5.0（API 21）**
- Android 5.0 ~ 16：正常工作。MediaSession 是 API 21 起的，代码里做了运行时判断

## 三、构建步骤（Android Studio）

1. 装 [Android Studio](https://developer.android.com/studio)（自带 JDK 17 与 Android SDK）
2. **打开工程**：`File → Open`，选 `waters-radio/android/` 目录
3. **等 Gradle Sync 完成**（第一次要下载依赖，可能几分钟）
4. **生成签名 keystore**（只需要做一次）：
   - 菜单 `Build → Generate Signed App Bundle / APK → APK → Create new...`
   - 按提示填 Key store path（选 `app/waters-release.keystore`，这样 build.gradle 里配置的路径才对）、密码（当前 build.gradle 配的是 `waters2026`，改了密码就同步改 build.gradle）、别名（当前配的是 `waters-radio`）
   - 生成完先别继续点 Build——直接**取消** Generate 向导，keystore 文件已经生成了
5. **Build APK**：菜单 `Build → Build App Bundle(s) / APK(s) → Build APK(s)`
6. APK 输出在 `app/build/outputs/apk/release/app-release.apk`（或 `debug/app-debug.apk`）
7. 传到手机安装（微信/USB/网盘都行；安装时允许"未知来源应用"）

### 命令行方式（可选）

```bash
cd waters-radio/android
# Windows
gradlew.bat assembleRelease
# macOS / Linux
./gradlew assembleRelease
```
> 第一次用命令行会报"gradle wrapper 不存在"，在 Android Studio 终端里跑一次 `gradle wrapper --gradle-version 8.7` 即可生成。

## 四、换自己的正式签名（可选）

`app/waters-release.keystore` 是临时签名。正式发布建议用你自己的：

```bash
keytool -genkey -v -keystore waters-release.keystore -alias waters-radio \
  -keyalg RSA -keysize 2048 -validity 10000
```

然后把 build.gradle 里 `storePassword` / `keyPassword` / `keyAlias` 改成你的。

**⚠️ 换 keystore 后旧 APK 不能直接覆盖安装**（签名对不上），要先卸载再装。所以第一次就选好 keystore、以后别再换。

## 五、怎么验证蓝牙遥控

1. 手机蓝牙连上遥控器（AVRCP 媒体遥控：耳机、车载、媒体按键盒等）
2. 打开 WATERS RADIO，随便放一个台
3. 按遥控器的"下一首" → 应该跳到电台库的下一个台（跳台范围=当前激活 tab 的列表，默认是收藏列表）
4. 按遥控器的"播放/暂停" → 应该停/继续
5. 按遥控器的音量 +/- → 手机媒体音量条应该变化
6. **锁屏后再按遥控器** → 依然能控制、声音继续（这是核心验证项）
7. 锁屏界面应该出现媒体卡片（歌名 + 播放暂停/上下首按钮）

## 六、怎么验证锁屏后台播放

1. 打开 App，放一个台
2. 按 Home 键回桌面 → 声音应继续
3. 按电源键锁屏 → 等半分钟 → 声音应继续
4. 状态栏应有"正在播放"通知，锁屏界面应有媒体卡片
5. **注意**：如果某台是 HTTP 明文流，Android 9+ 可能拦截（`usesCleartextTraffic="true"` 已开，但个别设备仍会拦）。用 HTTPS 的台测试最准

## 七、常见问题

**Q: 点安装报"应用未安装"？**
多半是签名问题——先卸载旧版再装。

**Q: 锁屏后声音断了？**
看是不是 WiFi 进了省电模式。设置里把 WATERS RADIO 加入电池优化白名单（不优化），部分国产 ROM 需要手动允许"后台运行"。

**Q: 蓝牙遥控没反应？**
- 确认遥控是"媒体遥控"（AVRCP）而不是键盘模拟（HID）。HID 遥控的按键会走实体键事件路径（`onKeyDown`），已做兜底，但部分键值映射可能不同
- Android 12+ 第一次连接蓝牙设备后，要去系统设置里给 WATERS RADIO 授予"附近设备"权限

**Q: 想把 HTML 更新到最新版？**
```
cp ../index.html ../hls.min.js app/src/main/assets/
```
（即把仓库根目录最新的 `waters-radio/index.html` 覆盖到 APK 资产里，然后重新 Build）

## 八、开机自启动 + 常驻后台（当网络收音机用）

本应用支持**开机自动启动 + 自动起播**，把旧安卓设备变成一台一开机就响的网络收音机：

- **自动起播**：开机拉起后加载 `index.html?auto=1`，自动播放首台 WLTW（网页内已有双保险：静音起播 + 首次触碰兜底）。
- **常驻后台**：前台服务 + `START_STICKY`（被杀自动重启）+ CPU WakeLock + WiFi WakeLock，锁屏、熄屏都继续播。

### 按系统的启用步骤（关键）

开机自启动受各厂商系统管控，需要用户**手动放行一次**（这是系统安全策略，任何第三方 App 都无法绕过）：

| 系统 | 需要手动开启的项 |
|---|---|
| 原生 Android 8+ | 设置 → 应用 → WATERS RADIO → 电池 →「不受限制」 |
| Android 10+ | 首次运行会弹「忽略电池优化」请求 → 点允许（已自动引导） |
| 小米 MIUI/HyperOS | 设置 → 应用 → WATERS RADIO → 自启动「允许」+ 省电策略「无限制」 |
| 华为 EMUI/HarmonyOS | 手机管家 → 应用启动管理 → WATERS RADIO → 手动管理（自启动/关联启动/后台活动 全开） |
| OPPO ColorOS | 设置 → 应用 → 自启动管理 → 允许 |
| vivo OriginOS | i管家 → 应用管理 → 权限管理 → 自启动 → 允许 |
| 三星 One UI | 设置 → 电池 → 后台使用限制 → 从不休眠应用 → 添加 WATERS RADIO |

> **省电原则**：媒体前台服务 + 加入「电池优化白名单」后，系统会把本应用当作"正在播放"处理，Doze 模式不会掐断其网络；其余组件不常驻、不轮询，除音频解码和网络流外几乎零额外耗电。牺牲的少量电量换来的是"锁屏不断流"，这是做常驻网络收音机的必要取舍。

## 九、已知限制

- **Android 4.0~4.3 装上白屏**（见第二节）
- 从"最近任务"里划掉 App = 彻底关闭，声音会停（这是系统行为，符合用户直觉）
- 部分国产 ROM（MIUI/EMUI/ColorOS）默认杀后台，需要用户手动把 App 加入"省电白名单/后台运行允许"——这是系统策略，任何 App 都绕不开
- 音量键控制的是**系统媒体音量**（0~15 档），不是网页里的音量滑块（0~100%）。两者独立
