package com.waters.radio;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.PowerManager;
import android.util.Log;

/**
 * WATERS RADIO · 闹钟 / 自动开关机的到点接收器（v58）
 * ------------------------------------------------------------------
 * AlarmManager 到点后回调到这里：
 *   1. 把「该做什么」写进 SharedPreferences（待触发项），
 *      网页启动后由 window.WatersNative.getPendingAlarm() 取走执行 ——
 *      这样即便 WebView 还没起来，指令也不会丢；
 *   2. 拉起前台播放服务 + 主界面（自动开关机除外）；
 *   3. 自动关机时间到：停掉播放服务，不强行开界面，让设备安静下来；
 *   4. 续排下一次（一次性闹钟必须自己续）。
 *
 * ⚠️ 设备完全断电关机时收不到任何广播 —— 那需要系统级「定时开机」；
 *    本接收器覆盖的是「设备通电但待机 / 黑屏 / 在别的应用里」的情形。
 */
public class AlarmReceiver extends BroadcastReceiver {

    private static final String TAG = "WatersAlarm";

    @Override
    public void onReceive(Context context, Intent intent) {
        String payload = intent != null ? intent.getStringExtra("payload") : null;
        if (payload == null || payload.length() == 0) payload = "{\"action\":\"alarm\"}";

        String action = "alarm";
        try {
            org.json.JSONObject o = new org.json.JSONObject(payload);
            action = o.optString("action", "alarm");
        } catch (Throwable ignored) {}

        Log.i(TAG, "到点：" + action + " → " + payload);

        /* 1) 写待触发项（网页启动后消费） */
        AlarmScheduler.savePending(context, payload);

        if ("poweroff".equals(action)) {
            /* 自动关机：停止播放服务 + 结束页面，不再强行唤醒界面 */
            try { context.stopService(new Intent(context, RadioPlaybackService.class)); } catch (Throwable ignored) {}
            try {
                MainActivity.finishIfRunning();
            } catch (Throwable ignored) {}
        } else {
            /* 闹钟 / 自动开机：短暂点亮屏幕，再拉起服务与界面 */
            wakeScreen(context);
            Intent svc = new Intent(context, RadioPlaybackService.class);
            svc.putExtra("autostart", true);
            try {
                if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(svc);
                else context.startService(svc);
            } catch (Throwable t) { Log.w(TAG, "start service", t); }

            Intent act = new Intent(context, MainActivity.class);
            act.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            act.putExtra("alarm", 1);
            try { context.startActivity(act); } catch (Throwable t) { Log.w(TAG, "start activity", t); }
        }

        /* 续排下一次 */
        try { AlarmScheduler.scheduleAll(context); } catch (Throwable t) { Log.w(TAG, "reschedule", t); }
    }

    /** 亮屏几秒：让「到点自动打开本程序」这件事被用户看见 */
    private void wakeScreen(Context c) {
        try {
            PowerManager pm = (PowerManager) c.getSystemService(Context.POWER_SERVICE);
            if (pm == null) return;
            int flags = PowerManager.FULL_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP | PowerManager.ON_AFTER_RELEASE;
            PowerManager.WakeLock wl = pm.newWakeLock(flags, "waters:alarm");
            wl.acquire(5000);
        } catch (Throwable ignored) {}
    }
}
