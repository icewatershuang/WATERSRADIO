package com.waters.radio;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

import org.json.JSONObject;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * WATERS RADIO · 前台播放服务（纯原生 API，无 androidx 依赖）
 * ------------------------------------------------------------------
 * 职责：
 *   1. 前台服务 —— 提到前台服务等级，避免锁屏后被系统杀掉；
 *   2. WakeLock（CPU）+ WiFi WakeLock —— 锁屏后 CPU / WiFi 仍可跑音频解码与网络流；
 *   3. MediaSession（API 21+）——
 *        · 蓝牙耳机/车载（AVRCP）的播放暂停/上一首/下一首 自动汇入回调；
 *        · 锁屏界面显示媒体卡片；
 *        · 音量键走系统媒体音量（setPlaybackToLocal + USAGE_MEDIA）。
 *   4. 指令转发：MediaSession 回调 → MainActivity.evalJs → WebView 执行
 *      window.watersApi.next() / prev() / resume() / pause()。
 *
 * API 14-20：无 MediaSession / MediaStyle 通知，只有普通通知 + 实体键 onKeyDown 兜底。
 */
public class RadioPlaybackService extends Service {

    private static final String CHANNEL_ID = "waters_radio_playback";
    private static final int NOTIF_ID = 2026;

    /* v58：JsBridge / 闹钟排程用的应用级 Context（Service 创建时写入，不持有 Activity） */
    private static volatile android.content.Context sAppContext = null;
    /* v7.0：单例引用（JsBridge 静态类借此调用 AsrController） */
    private static volatile RadioPlaybackService sInstance = null;

    /* v7.0：语音识别字幕控制器（离线 Vosk） */
    private AsrController asr;

    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;
    private MediaSession mediaSession;    /* API 21+ */

    /* 当前播放信息（由 JS 桥上报） */
    private volatile String nowName = "";
    private volatile boolean nowPlaying = false;
    private volatile String nowUrl = "";        /* 当前流地址（ICY 元数据抓取用） */
    private volatile String nowTitle = "";       /* ICY 推来的实时曲目（歌手 - 歌名） */
    private volatile IcyMetadataTask icyTask = null;
    private volatile String icyUrl = "";

    /* ------------------------------------------------------------
     * Service 生命周期
     * ---------------------------------------------------------- */
    @Override
    public void onCreate() {
        super.onCreate();
        sAppContext = getApplicationContext();
        sInstance = this;
        asr = new AsrController(this);   /* v7.0：离线语音识别（按设备能力开启） */
        createChannel();
        if (Build.VERSION.SDK_INT >= 21) setupMediaSession();
        acquireLocks();
        startForeground(NOTIF_ID, buildNotification());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && intent.hasExtra("name")) {
            nowName = intent.getStringExtra("name");
            nowPlaying = intent.getBooleanExtra("playing", false);
            nowUrl = intent.getStringExtra("url"); if (nowUrl == null) nowUrl = "";
            nowTitle = intent.getStringExtra("title"); if (nowTitle == null) nowTitle = "";
            updateSession();
            startIcyIfNeeded();
            /* v7.0：播放状态变化时同步语音识别（开播换流 / 停播关识别） */
            if (asr != null) {
                if (nowPlaying) asr.onStreamChanged(nowUrl);
                else asr.onPlaybackStopped();
            }
        }
        /* START_STICKY：被系统杀掉后自动重启，尽量保住后台播放 */
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        stopIcy();
        if (asr != null) asr.release();   /* v7.0：释放 Vosk 模型 */
        sInstance = null;
        releaseLocks();
        if (mediaSession != null) {
            mediaSession.release();
            mediaSession = null;
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    /* ------------------------------------------------------------
     * WakeLock：锁屏继续播放的关键
     * ---------------------------------------------------------- */
    @SuppressWarnings("deprecation")
    private void acquireLocks() {
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm != null && wakeLock == null) {
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "WatersRadio:playback");
            wakeLock.setReferenceCounted(false);
            wakeLock.acquire();
        }
        /* WiFi WakeLock：锁屏时 WiFi 不进省电模式，HLS 流不断 */
        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (wm != null && wifiLock == null) {
            /* 常量名是 WIFI_MODE_FULL_HIGH_PERF（API 12，无 "FORMANCE" 后缀） */
            int mode = Build.VERSION.SDK_INT >= 12
                ? WifiManager.WIFI_MODE_FULL_HIGH_PERF
                : WifiManager.WIFI_MODE_FULL;
            wifiLock = wm.createWifiLock(mode, "WatersRadio:wifi");
            wifiLock.setReferenceCounted(false);
            wifiLock.acquire();
        }
    }

    private void releaseLocks() {
        if (wakeLock != null && wakeLock.isHeld()) { wakeLock.release(); wakeLock = null; }
        if (wifiLock != null && wifiLock.isHeld()) { wifiLock.release(); wifiLock = null; }
    }

    /* ------------------------------------------------------------
     * 转发给 WebView：MainActivity.evalJs 直接执行；
     * Activity 已不在（从最近任务划掉）时，拉起 Activity 并把 JS 塞 Intent 兜底。
     * ---------------------------------------------------------- */
    private void callJs(String jsExpression) {
        if (MainActivity.evalJs(jsExpression)) return;
        Intent i = new Intent(this, MainActivity.class);
        i.setAction("com.waters.radio.JS");
        i.putExtra("js", jsExpression);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(i);
    }

    /* v7.0：把识别出的字幕文本回传主页（window.__onAsr） */
    void callAsrJs(String text, boolean isFinal) {
        callJs("window.__onAsr && window.__onAsr(" + JSONObject.quote(text) + "," + (isFinal ? "true" : "false") + ")");
    }

    /* v7.0：识别相关提示（用网页的 toast） */
    void callAsrToast(String msg) {
        callJs("window.toast && window.toast(" + JSONObject.quote(msg) + ")");
    }

    /* ------------------------------------------------------------
     * MediaSession（API 21+）：蓝牙 AVRCP + 锁屏卡片 + 音量键
     * ---------------------------------------------------------- */
    private void setupMediaSession() {
        mediaSession = new MediaSession(this, "WatersRadio");

        mediaSession.setCallback(new MediaSession.Callback() {
            @Override public void onPlay()    { callJs("watersApi.resume()"); }
            @Override public void onPause()   { callJs("watersApi.pause()"); }
            @Override public void onSkipToNext()     { callJs("watersApi.next()"); }
            @Override public void onSkipToPrevious() { callJs("watersApi.prev()"); }
        });

        /* 声明接收媒体按钮（蓝牙键必开） */
        mediaSession.setFlags(
            MediaSession.FLAG_HANDLES_MEDIA_BUTTONS |
            MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);

        /* 音量控制走系统媒体音量流（蓝牙音量键=调媒体音量）。
           框架 MediaSession 只有 setPlaybackToLocal(AudioAttributes) 这一档
           （int 流 ID 那个重载是旧 RemoteControlClient 的），必须包一层。 */
        AudioAttributes attrs = new AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build();
        mediaSession.setPlaybackToLocal(attrs);

        /* 点通知/锁屏卡片 → 回主界面 */
        Intent back = new Intent(this, MainActivity.class);
        back.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        mediaSession.setSessionActivity(makePi(back, 0));

        mediaSession.setActive(true);
        updateSession();
    }

    /* 把当前播放信息同步到 MediaSession 元数据 + 通知栏 */
    private void updateSession() {
        if (Build.VERSION.SDK_INT >= 21 && mediaSession != null) {
            android.media.MediaMetadata.Builder md = new android.media.MediaMetadata.Builder();
            String sessionTitle = !nowTitle.isEmpty() ? nowTitle
                : (nowName.isEmpty() ? "WATERS RADIO" : nowName);
            md.putString(android.media.MediaMetadata.METADATA_KEY_TITLE, sessionTitle);
            md.putString(android.media.MediaMetadata.METADATA_KEY_ARTIST, "Waters Radio");
            md.putLong(android.media.MediaMetadata.METADATA_KEY_DURATION, -1);  /* 直播流未知时长 */
            mediaSession.setMetadata(md.build());

            PlaybackState ps = new PlaybackState.Builder()
                .setActions(
                    PlaybackState.ACTION_PLAY |
                    PlaybackState.ACTION_PAUSE |
                    PlaybackState.ACTION_PLAY_PAUSE |
                    PlaybackState.ACTION_SKIP_TO_NEXT |
                    PlaybackState.ACTION_SKIP_TO_PREVIOUS |
                    PlaybackState.ACTION_STOP)
                .setState(nowPlaying
                    ? PlaybackState.STATE_PLAYING
                    : PlaybackState.STATE_PAUSED,
                    PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1.0f)
                .build();
            mediaSession.setPlaybackState(ps);
        }
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(NOTIF_ID, buildNotification());
    }

    /* ------------------------------------------------------------
     * ICY / 流内元数据抓取（APK 独有）
     * ------------------------------------------------------------------
     * 音频实际由 WebView 的 <audio> 播放，原生无法直接拿到段内 ICY；
     * 故在此另开一条「只读取元数据」的并行连接：带 Icy-MetaData:1 请求，
     * 按 icy-metaint 间隔解析 StreamTitle，推回 WebView 的 window.__onIcy。
     * 代价：与播放共用同一流，移动网络下带宽约翻倍（仅 Icecast/SHOUTcast 裸流支持，
     * HLS 走 ID3 不在本实现范围）。stop 时断开。
     * ---------------------------------------------------------- */
    private interface IcyListener { void onTitle(String t); }

    private void startIcyIfNeeded() {
        if (!nowPlaying || nowUrl.isEmpty()) { stopIcy(); return; }
        String u = nowUrl.toLowerCase();
        if (u.contains(".m3u8") || u.contains(".m3u") || u.contains(".pls")) { stopIcy(); return; }
        if (icyTask != null && icyUrl != null && icyUrl.equals(nowUrl)) return; // 已在抓同一路
        stopIcy();
        icyUrl = nowUrl;
        icyTask = new IcyMetadataTask(nowUrl, new IcyListener() {
            @Override public void onTitle(String t) {
                nowTitle = t;
                updateSession();
                callJs("window.__onIcy && window.__onIcy(" + JSONObject.quote(t) + ")");
            }
        });
        icyTask.start();
    }

    private void stopIcy() {
        if (icyTask != null) { icyTask.cancel(); icyTask = null; }
        icyUrl = "";
    }

    private static class IcyMetadataTask extends Thread {
        private final String url;
        private final IcyListener listener;
        private volatile boolean stopped = false;
        private String lastTitle = "";

        IcyMetadataTask(String url, IcyListener l) { this.url = url; this.listener = l; }
        void cancel() { stopped = true; this.interrupt(); }

        @Override
        public void run() {
            HttpURLConnection conn = null;
            try {
                conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setRequestProperty("Icy-MetaData", "1");
                conn.setConnectTimeout(8000);
                conn.setReadTimeout(0);
                conn.connect();
                String mi = conn.getHeaderField("icy-metaint");
                int metaint = -1;
                if (mi != null) { try { metaint = Integer.parseInt(mi.trim()); } catch (Exception ignore) {} }
                if (metaint <= 0) return; // 服务器不支持 ICY 元数据
                InputStream in = conn.getInputStream();
                byte[] buf = new byte[metaint];
                while (!stopped) {
                    int total = 0;
                    while (total < metaint) {
                        int r = in.read(buf, total, metaint - total);
                        if (r < 0) return;
                        total += r;
                        if (stopped) return;
                    }
                    int lenByte = in.read();
                    if (lenByte < 0) return;
                    int metaLen = lenByte * 16;
                    if (metaLen > 0) {
                        byte[] meta = new byte[metaLen];
                        int got = 0;
                        while (got < metaLen) {
                            int r = in.read(meta, got, metaLen - got);
                            if (r < 0) return;
                            got += r;
                        }
                        String title = parseStreamTitle(new String(meta, "UTF-8"));
                        if (!title.isEmpty() && !title.equals(lastTitle)) {
                            lastTitle = title;
                            if (listener != null) listener.onTitle(title);
                        }
                    }
                }
            } catch (Exception ignore) {
                // 网络异常：静默退出，下次播放再试
            } finally {
                if (conn != null) try { conn.disconnect(); } catch (Exception ignore) {}
            }
        }

        /* 形如：StreamTitle='歌手 - 歌名';StreamUrl=''; */
        private String parseStreamTitle(String s) {
            int i = s.indexOf("StreamTitle=");
            if (i < 0) return "";
            int q1 = s.indexOf('\'', i);
            int q2 = s.indexOf('\'', q1 + 1);
            if (q1 < 0 || q2 < 0) return "";
            return s.substring(q1 + 1, q2).trim();
        }
    }

    /* ------------------------------------------------------------
     * 通知栏
     * ---------------------------------------------------------- */
    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID, "WATERS RADIO 播放",
                NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("锁屏与状态栏的播放控件");
            ch.setShowBadge(false);
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.createNotificationChannel(ch);
        }
    }

    @SuppressWarnings("deprecation")
    private Notification buildNotification() {
        Intent back = new Intent(this, MainActivity.class);
        back.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent contentPi = makePi(back, 0);

        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            b = new Notification.Builder(this, CHANNEL_ID);
        } else {
            b = new Notification.Builder(this);
            b.setPriority(Notification.PRIORITY_LOW);
        }
        b.setSmallIcon(android.R.drawable.ic_media_play)
         .setContentTitle("WATERS RADIO")
         .setContentText(!nowTitle.isEmpty() ? nowTitle
            : (nowName.isEmpty() ? "正在播放电台" : nowName))
         .setContentIntent(contentPi)
         .setOngoing(true)
         .setOnlyAlertOnce(true);

        if (Build.VERSION.SDK_INT >= 21) {
            /* MediaStyle：锁屏显示媒体卡片；三个操作 上一首/播放暂停/下一首 */
            Notification.Action prev = new Notification.Action.Builder(
                null, "上一首", actionPi("watersApi.prev()")).build();
            Notification.Action toggle = new Notification.Action.Builder(
                null, nowPlaying ? "暂停" : "播放",
                actionPi(nowPlaying ? "watersApi.pause()" : "watersApi.resume()")).build();
            Notification.Action next = new Notification.Action.Builder(
                null, "下一首", actionPi("watersApi.next()")).build();
            b.addAction(prev).addAction(toggle).addAction(next);
            b.setStyle(new Notification.MediaStyle()
                .setMediaSession(mediaSession != null ? mediaSession.getSessionToken() : null)
                .setShowActionsInCompactView(0, 1, 2));
            /* MediaStyle 需要一个小图标，用系统媒体图标 */
            b.setSmallIcon(android.R.drawable.ic_media_play);
        }

        return b.build();
    }

    private PendingIntent actionPi(String js) {
        Intent i = new Intent(this, MainActivity.class);
        i.setAction("com.waters.radio.JS");
        i.putExtra("js", js);
        return makePi(i, js.hashCode());
    }

    /* PendingIntent 兼容 API 31+ 的 FLAG_IMMUTABLE 强制要求 */
    private PendingIntent makePi(Intent intent, int requestCode) {
        if (Build.VERSION.SDK_INT >= 31) {
            return PendingIntent.getActivity(this, requestCode, intent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        }
        return PendingIntent.getActivity(this, requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT);
    }

    /* ------------------------------------------------------------
     * JS 桥：WebView 通过 window.WatersNative.xxx() 交互
     * ---------------------------------------------------------- */
    public static class JsBridge {
        /* 让网页判断自己正跑在 APK 的 WebView 里（而非普通浏览器）。
           HTML 据此对 http:// 明文直播流跳过「https 升级 + 代理」，
           直接加载 —— WebView 的 usesCleartextTraffic/networkSecurityConfig
           已放行明文流量，浏览器却做不到。 */
        @android.webkit.JavascriptInterface
        public boolean isApp() { return true; }

        @android.webkit.JavascriptInterface
        public void onNowPlaying(String json) {
            try {
                JSONObject o = new JSONObject(json);
                MainActivity.postToService(
                    o.optString("name", ""),
                    o.optBoolean("playing", false),
                    o.optString("url", ""),
                    o.optString("title", ""));
            } catch (Exception ignored) {}
        }

        /* ---- v58：定时与闹钟（网页 → 原生 AlarmManager）----
           网页把整份 schedule JSON 交过来，由 AlarmScheduler 预约精确闹钟；
           屏幕关掉 / WebView 被回收后也能准时唤起本程序。 */
        @android.webkit.JavascriptInterface
        public void setSchedule(String json) {
            android.content.Context c = ctx();
            if (c == null) return;
            try {
                AlarmScheduler.saveSchedule(c, json);
                AlarmScheduler.scheduleAll(c);
            } catch (Throwable ignored) {}
        }

        /* 网页启动时取走「原生闹钟留下的待触发项」（取完即清空） */
        @android.webkit.JavascriptInterface
        public String getPendingAlarm() {
            android.content.Context c = ctx();
            if (c == null) return "";
            try { return AlarmScheduler.takePending(c); } catch (Throwable ignored) { return ""; }
        }

        /* 自动关机时间到点：停止播放服务 + 回到桌面（真正的断电需系统支持） */
        @android.webkit.JavascriptInterface
        public void standby() {
            android.content.Context c = ctx();
            if (c == null) return;
            try { c.stopService(new android.content.Intent(c, RadioPlaybackService.class)); } catch (Throwable ignored) {}
            try {
                android.content.Intent home = new android.content.Intent(android.content.Intent.ACTION_MAIN);
                home.addCategory(android.content.Intent.CATEGORY_HOME);
                home.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
                c.startActivity(home);
            } catch (Throwable ignored) {}
            try { MainActivity.finishIfRunning(); } catch (Throwable ignored) {}
        }

        /* ---- v60：全屏显示（隐藏状态栏，内容铺满整屏）----
           网页设置里打开「全屏显示」后调 setImmersive("1")，关闭调 "0"。
           实际窗口操作在 MainActivity（UI 线程、持有 Window）里执行。 */
        @android.webkit.JavascriptInterface
        public void setImmersive(String on) {
            try { MainActivity.setImmersiveMode("1".equals(on) || "true".equals(on)); } catch (Throwable ignored) {}
        }

        /* ---- v7.0：语音识别字幕（离线 Vosk）----
           网页「语音字幕」开关 / 语言选择 → 原生启动并行解码 + Vosk 识别，
           结果经 window.__onAsr 回传主页字幕层。能力不满足时 asrAvailable() 返回 false，
           网页端整项隐藏。 */
        @android.webkit.JavascriptInterface
        public boolean asrAvailable() {
            return sInstance != null && sInstance.asr != null && sInstance.asr.isAvailable();
        }

        @android.webkit.JavascriptInterface
        public void setAsr(String on) {
            if (sInstance != null && sInstance.asr != null) {
                boolean en = "1".equals(on) || "true".equals(on);
                sInstance.asr.setEnabled(en, sInstance.nowUrl);
            }
        }

        @android.webkit.JavascriptInterface
        public void setAsrLang(String lang) {
            if (sInstance != null && sInstance.asr != null) sInstance.asr.setLang(lang);
        }

        /* JsBridge 是无 Context 的静态类：优先用 Service 自己，其次 MainActivity */
        private static android.content.Context ctx() {
            if (sAppContext != null) return sAppContext;
            android.content.Context c = MainActivity.appContext();
            return c;
        }
    }
}
