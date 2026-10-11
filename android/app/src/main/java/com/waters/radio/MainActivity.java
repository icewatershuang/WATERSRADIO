package com.waters.radio;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

/**
 * WATERS RADIO —— WebView 壳层
 * ------------------------------------------------------------------
 * 所有电台逻辑、多源热备、电台库 UI 都在 HTML 内实现，原生只负责：
 *   1. WebView 加载本地 index.html（UI 100% 与网页版一致）；
 *   2. 启动 RadioPlaybackService（前台服务 + WakeLock + MediaSession），
 *      保证锁屏后音频继续播放、蓝牙耳机/车载可控制；
 *   3. JS 桥：
 *        · 原生 → JS：MediaSession 回调里 evaluateJavascript("watersApi.next()") 等；
 *        · JS → 原生：window.WatersNative.onNowPlaying(json) 上报当前台名/播放状态，
 *          服务用它更新 MediaSession 元数据与通知栏。
 *   4. 通知栏媒体按钮的点击走 PendingIntent 指向本 Activity 的特殊 Intent，
 *      onNewIntent 里取出 js 表达式再 evaluateJavascript（避免在 Service 里持有 Activity 引用）。
 */
public class MainActivity extends Activity {

    private WebView webView;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    /* RadioPlaybackService 调 evalJs() 转发蓝牙/锁屏按键的指令 */
    private static MainActivity instance;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        instance = this;

        webView = new WebView(this);
        setContentView(webView);

        WebSettings ws = webView.getSettings();
        ws.setJavaScriptEnabled(true);
        // 允许媒体自动播放（首次打开即播 WLTW，无需手势）
        ws.setMediaPlaybackRequiresUserGesture(false);
        ws.setAllowFileAccess(true);
        ws.setAllowContentAccess(true);
        ws.setDomStorageEnabled(true);
        ws.setDatabaseEnabled(true);
        ws.setLoadsImagesAutomatically(true);
        // 允许混合内容（部分电台流为 HTTP）
        ws.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        ws.setCacheMode(WebSettings.LOAD_DEFAULT);

        webView.setWebViewClient(new WebViewClient());
        webView.setWebChromeClient(new WebChromeClient());

        /* JS 桥：网页通过 window.WatersNative.onNowPlaying(json) 上报播放状态 */
        webView.addJavascriptInterface(new RadioPlaybackService.JsBridge(), "WatersNative");

        // 加载本地资产，并带上 auto=1 触发自动播放首台
        webView.loadUrl("file:///android_asset/index.html?auto=1");

        /* Android 13+ 通知权限（媒体通知栏需要；用户拒绝也不影响播放） */
        requestNotificationPermissionIfNeeded();

        /* 引导加入电池优化白名单（Doze 模式下避免锁屏断流） */
        requestBatteryOptimizationExemption();

        /* 启动前台播放服务（保活 + MediaSession + 蓝牙转发） */
        startRadioService();
        handleJsIntent(getIntent());
    }

    /* ------------------------------------------------------------
     * 前台服务
     * ---------------------------------------------------------- */
    private void startRadioService() {
        Intent i = new Intent(this, RadioPlaybackService.class);
        i.putExtra("name", "");
        i.putExtra("playing", false);
        if (Build.VERSION.SDK_INT >= 26) {
            startForegroundService(i);
        } else {
            startService(i);
        }
    }

    /* JS 桥把播放状态变化传给 Service，让它更新 MediaSession/通知 + 抓取 ICY 元数据 */
    static void postToService(final String name, final boolean playing, final String url, final String title) {
        if (instance == null) return;
        instance.runOnUiThread(() -> {
            Intent i = new Intent(instance, RadioPlaybackService.class);
            i.putExtra("name", name);
            i.putExtra("playing", playing);
            i.putExtra("url", url);
            i.putExtra("title", title);
            if (Build.VERSION.SDK_INT >= 26) {
                instance.startForegroundService(i);
            } else {
                instance.startService(i);
            }
        });
    }

    /* Service 里蓝牙/锁屏按键 → 静态调到这里 → WebView 执行 JS。
       返回 false = Activity 已不在（如用户从最近任务划掉），调用方走 Intent 兜底。 */
    static boolean evalJs(final String js) {
        if (instance == null || instance.webView == null) return false;
        instance.mainHandler.post(() -> instance.webView.evaluateJavascript(js, null));
        return true;
    }

    /* v58：自动关机时间到点 —— 页面若在前台就收掉它（真正的断电关机做不到，
       这里做到「停止播放 + 退出界面」，配合 Service 停止即进入安静待机）。 */
    static void finishIfRunning() {
        if (instance == null) return;
        try {
            instance.mainHandler.post(() -> {
                try { instance.moveTaskToBack(true); } catch (Throwable ignored) {}
                try { instance.finish(); } catch (Throwable ignored) {}
            });
        } catch (Throwable ignored) {}
    }

    /* v58：给 JsBridge 用的应用级 Context（Service 创建时写入，避免持有 Activity） */
    static android.content.Context appContext() {
        if (instance != null) return instance.getApplicationContext();
        return null;
    }

    /* ------------------------------------------------------------
     * v60：全屏显示（隐藏状态栏，内容铺满整屏，连状态栏也盖掉）
     * 网页设置「全屏显示」开关 → JsBridge.setImmersive → 这里在 UI 线程执行。
     * ⚠️ 不能叫 setImmersive —— Activity 自带 final 语义的同名方法（API 18+）会冲突
     *   （实测 #148 构建失败：setImmersive(boolean) in MainActivity cannot override
     *    setImmersive(boolean) in Activity），故取名 setImmersiveMode。
     * · API 30+：WindowInsetsController 隐藏 statusBars，滑动临时呼出；
     * · API 16~29：systemUiVisibility 沉浸式标记；
     * · API 14~15：FLAG_FULLSCREEN 窗口标记兜底。
     * ---------------------------------------------------------- */
    static void setImmersiveMode(final boolean on) {
        if (instance == null) return;
        instance.runOnUiThread(() -> applyImmersive(instance, on));
    }

    static void applyImmersive(final Activity act, final boolean on) {
        try {
            final android.view.Window w = act.getWindow();
            if (Build.VERSION.SDK_INT >= 30) {
                android.view.WindowInsetsController c = w.getInsetsController();
                if (c != null) {
                    if (on) {
                        c.hide(android.view.WindowInsets.Type.statusBars());
                        c.setSystemBarsBehavior(android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                    } else {
                        c.show(android.view.WindowInsets.Type.statusBars());
                    }
                }
            } else if (Build.VERSION.SDK_INT >= 16) {
                android.view.View decor = w.getDecorView();
                final int base = android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN;
                decor.setSystemUiVisibility(on
                        ? base | android.view.View.SYSTEM_UI_FLAG_FULLSCREEN
                        : base);
            } else {
                if (on) {
                    w.setFlags(android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN,
                            android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN);
                } else {
                    w.clearFlags(android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN);
                }
            }
        } catch (Throwable ignored) {}
    }

    /* ------------------------------------------------------------
     * 通知栏媒体按钮的点击（PendingIntent → 本 Activity 特殊 Intent）
     * ---------------------------------------------------------- */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleJsIntent(intent);
    }

    private void handleJsIntent(Intent intent) {
        if (intent == null || intent.getAction() == null) return;
        if ("com.waters.radio.JS".equals(intent.getAction())) {
            String js = intent.getStringExtra("js");
            if (js != null) evalJs(js);
        }
    }

    /* ------------------------------------------------------------
     * Android 12+ 蓝牙连接权限（AVRCP 需要；无权限时媒体键仍可用，
     * 只是没有 MediaSessionManager 的部分高级特性）
     * ---------------------------------------------------------- */
    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{ Manifest.permission.POST_NOTIFICATIONS }, 1);
            }
        }
    }

    /* ------------------------------------------------------------
     * 电池优化白名单（省电关键）：
     *   Android 6+ 的 Doze 模式会在锁屏一段时间后掐断网络，导致流断。
     *   媒体前台服务在 Doze 下有豁免，但部分 ROM 实现不完整，为稳妥，
     *   引导用户把本应用加入「不优化」白名单。拒绝不影响使用，只提示一次。
     * ---------------------------------------------------------- */
    private boolean batteryExemptAsked = false;
    private void requestBatteryOptimizationExemption() {
        if (Build.VERSION.SDK_INT < 23) return;      // Doze 从 Android 6 才有
        if (batteryExemptAsked) return;
        android.os.PowerManager pm = (android.os.PowerManager) getSystemService(POWER_SERVICE);
        if (pm == null || pm.isIgnoringBatteryOptimizations(getPackageName())) return;
        batteryExemptAsked = true;
        try {
            Intent i = new Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            i.setData(android.net.Uri.parse("package:" + getPackageName()));
            startActivity(i);
        } catch (Exception ignored) {
            /* 部分设备没有该设置页，静默跳过 */
        }
    }

    /* ------------------------------------------------------------
     * 返回键：先尝试 WebView 后退（弹窗、设置页），否则最小化到后台
     * （返回键不退出应用，保证后台继续播放）
     * ---------------------------------------------------------- */
    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            /* 移到后台而不是 finish()，前台服务继续播放 */
            Intent home = new Intent(Intent.ACTION_MAIN);
            home.addCategory(Intent.CATEGORY_HOME);
            startActivity(home);
        }
    }

    /* 兜底：某些老蓝牙遥控走实体按键事件而不走 MediaSession（Android 5.0 以下） */
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (Build.VERSION.SDK_INT >= 21) return super.onKeyDown(keyCode, event);
        switch (keyCode) {
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                evalJs("watersApi.isPlaying() ? watersApi.pause() : watersApi.resume()");
                return true;
            case KeyEvent.KEYCODE_MEDIA_NEXT:
                evalJs("watersApi.next()");
                return true;
            case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
                evalJs("watersApi.prev()");
                return true;
            case KeyEvent.KEYCODE_VOLUME_UP:
            case KeyEvent.KEYCODE_VOLUME_DOWN:
                /* 音量键走系统默认处理（调媒体音量） */
                return super.onKeyDown(keyCode, event);
        }
        return super.onKeyDown(keyCode, event);
    }

    /* 返回 App 时恢复 WebView 渲染（不影响后台音频） */
    @Override
    protected void onResume() {
        super.onResume();
        if (webView != null) webView.onResume();
    }

    /* ⚠️ 不调 webView.onPause()：它会冻结 JS 计时器与音频进度，
       锁屏后 HLS 流可能断。保活靠前台服务 + WakeLock，这里什么都不做。 */
    @Override
    protected void onPause() {
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        instance = null;
        if (webView != null) {
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }
}
