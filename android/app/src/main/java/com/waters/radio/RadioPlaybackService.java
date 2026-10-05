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

    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;
    private MediaSession mediaSession;    /* API 21+ */

    /* 当前播放信息（由 JS 桥上报） */
    private volatile String nowName = "";
    private volatile boolean nowPlaying = false;

    /* ------------------------------------------------------------
     * Service 生命周期
     * ---------------------------------------------------------- */
    @Override
    public void onCreate() {
        super.onCreate();
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
            updateSession();
        }
        /* START_STICKY：被系统杀掉后自动重启，尽量保住后台播放 */
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
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
            md.putString(android.media.MediaMetadata.METADATA_KEY_TITLE,
                nowName.isEmpty() ? "WATERS RADIO" : nowName);
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
         .setContentText(nowName.isEmpty() ? "正在播放电台" : nowName)
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
     * JS 桥：WebView 通过 window.WatersNative.onNowPlaying(json) 上报
     * ---------------------------------------------------------- */
    public static class JsBridge {
        @android.webkit.JavascriptInterface
        public void onNowPlaying(String json) {
            try {
                JSONObject o = new JSONObject(json);
                MainActivity.postToService(o.optString("name", ""), o.optBoolean("playing", false));
            } catch (Exception ignored) {}
        }
    }
}
