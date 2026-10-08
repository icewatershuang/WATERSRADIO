package com.waters.radio;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Calendar;

/**
 * WATERS RADIO · 定时与闹钟的原生排程器（v58）
 * ------------------------------------------------------------------
 * 网页把「闹钟 / 自动开机 / 自动关机」这份 schedule 通过 JS 桥交给我们，
 * 这里用 AlarmManager 预约**精确**一次性闹钟（RTC_WAKEUP）：
 *   · 每个闹钟一个 requestCode（1000 + 序号）；
 *   · 自动开机 = 2001、自动关机 = 2002；
 *   · 触发后由 AlarmReceiver 写入「待触发项」并唤起界面，随后调用 scheduleAll()
 *     续排下一次（一次性闹钟必须自己续，setRepeating 无法表达「周几」）。
 *
 * 兼容性：
 *   · setExact 需 API 19+，低版本回退 set()（可能有几分钟偏差，旧设备可接受）；
 *   · PendingIntent.FLAG_IMMUTABLE 需 API 23+（API 31+ 强制要求）；
 *   · 设备**完全断电关机**时，任何第三方 App 都无法开机 —— 那需要系统级
 *     「定时开机」功能；本排程器只能做到「设备未断电（待机/亮屏/黑屏）时准时唤起」。
 */
public class AlarmScheduler {

    private static final String TAG = "WatersSchedule";
    static final String ACTION_ALARM = "com.waters.radio.ALARM";
    static final String PREF = "waters_sched";
    static final String KEY_SCHEDULE = "schedule_json";
    static final String KEY_PENDING = "pending_json";

    /* requestCode 段：闹钟 1000+，开关机各占一个固定码 */
    static final int BASE_ALARM = 1000;
    static final int MAX_ALARM = 50;
    static final int ID_POWERON = 2001;
    static final int ID_POWEROFF = 2002;

    /* ----------------------------------------------------------------
     * schedule 存取
     * -------------------------------------------------------------- */
    static void saveSchedule(Context c, String json) {
        try {
            SharedPreferences p = c.getSharedPreferences(PREF, Context.MODE_PRIVATE);
            p.edit().putString(KEY_SCHEDULE, json == null ? "" : json).commit();
        } catch (Throwable t) { Log.w(TAG, "saveSchedule", t); }
    }

    static String loadSchedule(Context c) {
        try {
            SharedPreferences p = c.getSharedPreferences(PREF, Context.MODE_PRIVATE);
            return p.getString(KEY_SCHEDULE, "");
        } catch (Throwable t) { return ""; }
    }

    /* 待触发项：AlarmReceiver 写入，网页启动后由 getPendingAlarm() 取走并清空 */
    static void savePending(Context c, String json) {
        try {
            SharedPreferences p = c.getSharedPreferences(PREF, Context.MODE_PRIVATE);
            p.edit().putString(KEY_PENDING, json == null ? "" : json).commit();
        } catch (Throwable t) { Log.w(TAG, "savePending", t); }
    }

    static String takePending(Context c) {
        try {
            SharedPreferences p = c.getSharedPreferences(PREF, Context.MODE_PRIVATE);
            String v = p.getString(KEY_PENDING, "");
            if (v != null && v.length() > 0) p.edit().putString(KEY_PENDING, "").commit();
            return v == null ? "" : v;
        } catch (Throwable t) { return ""; }
    }

    /* ----------------------------------------------------------------
     * 排程
     * -------------------------------------------------------------- */
    static void scheduleAll(Context c) {
        cancelAll(c);
        String raw = loadSchedule(c);
        if (raw == null || raw.length() == 0) return;
        try {
            JSONObject o = new JSONObject(raw);

            JSONArray arr = o.optJSONArray("alarms");
            if (arr != null) {
                for (int i = 0; i < arr.length() && i < MAX_ALARM; i++) {
                    JSONObject a = arr.optJSONObject(i);
                    if (a == null) continue;
                    int h = a.optInt("h", 7);
                    int m = a.optInt("m", 0);
                    JSONArray days = a.optJSONArray("days");
                    long at = nextOccurrence(h, m, days);
                    JSONObject payload = new JSONObject();
                    payload.put("action", "alarm");
                    payload.put("name", a.optString("name", ""));
                    payload.put("url", a.optString("url", ""));
                    setExact(c, at, BASE_ALARM + i, payload.toString());
                }
            }

            JSONObject on = o.optJSONObject("powerOn");
            if (on != null && on.optBoolean("on", false)) {
                String mode = on.optString("mode", "single");
                long at = nextOccurrence(on.optInt("h", 7), on.optInt("m", 0), modeDays(mode));
                JSONObject payload = new JSONObject();
                payload.put("action", "poweron");
                payload.put("mode", mode);
                setExact(c, at, ID_POWERON, payload.toString());
            }

            JSONObject off = o.optJSONObject("powerOff");
            if (off != null && off.optBoolean("on", false)) {
                String mode = off.optString("mode", "single");
                long at = nextOccurrence(off.optInt("h", 23), off.optInt("m", 0), modeDays(mode));
                JSONObject payload = new JSONObject();
                payload.put("action", "poweroff");
                payload.put("mode", mode);
                setExact(c, at, ID_POWEROFF, payload.toString());
            }
            Log.i(TAG, "排程完成");
        } catch (Throwable t) { Log.w(TAG, "scheduleAll", t); }
    }

    /* v61：执行方式 → 重复日期（0=周日…6=周六）。
       single / daily → 空数组 = 每天（single 的「只执行一次」由触发后关掉开关实现，
       见 disablePower()，这样网页与原生两边语义一致）。 */
    private static JSONArray modeDays(String mode) {
        JSONArray d = new JSONArray();
        if ("workday".equals(mode)) {
            int[] wd = {1, 2, 3, 4, 5};
            for (int x : wd) d.put(x);
        } else if ("weekend".equals(mode)) {
            d.put(0); d.put(6);
        }
        return d;
    }

    /* v61：单次执行的自动开关机 —— 触发后在原生保存的 schedule 里关掉开关，
       scheduleAll() 就不会再续排；网页下次启动消费待触发项时也会同步关掉自己的开关。 */
    static void disablePower(Context c, boolean powerOn) {
        try {
            String raw = loadSchedule(c);
            if (raw == null || raw.length() == 0) return;
            JSONObject o = new JSONObject(raw);
            JSONObject p = o.optJSONObject(powerOn ? "powerOn" : "powerOff");
            if (p != null) {
                p.put("on", false);
                saveSchedule(c, o.toString());
                Log.i(TAG, (powerOn ? "自动打开" : "自动关闭") + "为单次执行，已触发并自动关闭");
            }
        } catch (Throwable t) { Log.w(TAG, "disablePower", t); }
    }

    static void cancelAll(Context c) {
        try {
            AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
            if (am == null) return;
            for (int i = 0; i < MAX_ALARM; i++) cancel(am, c, BASE_ALARM + i);
            cancel(am, c, ID_POWERON);
            cancel(am, c, ID_POWEROFF);
        } catch (Throwable t) { Log.w(TAG, "cancelAll", t); }
    }

    private static void cancel(AlarmManager am, Context c, int requestCode) {
        try {
            Intent i = new Intent(c, AlarmReceiver.class);
            i.setAction(ACTION_ALARM);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
            PendingIntent pi = PendingIntent.getBroadcast(c, requestCode, i, flags | PendingIntent.FLAG_NO_CREATE);
            if (pi != null) am.cancel(pi);
        } catch (Throwable ignored) {}
    }

    /** 下一次触发时刻：今天该时刻已过就顺延；days 为空表示每天 */
    private static long nextOccurrence(int h, int m, JSONArray days) {
        Calendar cal = Calendar.getInstance();
        cal.set(Calendar.HOUR_OF_DAY, h);
        cal.set(Calendar.MINUTE, m);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        long now = System.currentTimeMillis();
        if (cal.getTimeInMillis() <= now) cal.add(Calendar.DAY_OF_YEAR, 1);
        for (int k = 0; k < 8; k++) {
            if (hitDays(days, cal)) return cal.getTimeInMillis();
            cal.add(Calendar.DAY_OF_YEAR, 1);
        }
        return cal.getTimeInMillis();
    }

    /** days 用 0=周日 … 6=周六（与网页 JS 的 Date.getDay() 一致） */
    private static boolean hitDays(JSONArray days, Calendar cal) {
        if (days == null || days.length() == 0) return true;
        int dow = cal.get(Calendar.DAY_OF_WEEK) - 1;   // Calendar: 1=周日 → 0
        for (int i = 0; i < days.length(); i++) {
            if (days.optInt(i, -1) == dow) return true;
        }
        return false;
    }

    private static void setExact(Context c, long triggerAt, int requestCode, String payload) {
        try {
            Intent i = new Intent(c, AlarmReceiver.class);
            i.setAction(ACTION_ALARM);
            i.putExtra("payload", payload);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
            PendingIntent pi = PendingIntent.getBroadcast(c, requestCode, i, flags);
            AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
            if (am == null) return;
            if (Build.VERSION.SDK_INT >= 19) am.setExact(AlarmManager.RTC_WAKEUP, triggerAt, pi);
            else am.set(AlarmManager.RTC_WAKEUP, triggerAt, pi);
        } catch (Throwable t) { Log.w(TAG, "setExact", t); }
    }
}
