package com.waters.radio;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/**
 * WATERS RADIO · 开机自启动
 * ------------------------------------------------------------------
 * 收到系统开机广播后，拉起前台播放服务并打开主界面（MainActivity 里
 * 会加载 index.html?auto=1 自动起播 WLTW），让旧设备一开机就变成
 * 一台持续播放的网络收音机。
 *
 * 兼容性说明（重要）：
 *  · API 26+：必须显式声明 RECEIVE_BOOT_COMPLETED 权限（Manifest 已声明）。
 *  · 多数国产 ROM（MIUI/EMUI/ColorOS/Flyme 等）：开机自启动默认关闭，
 *    用户需在「应用管理 → 自启动管理」里手动允许本应用自启动——这是系统
 *    策略，任何第三方 App 都无法绕过。BUILD.md 里有引导说明。
 *  · 从 Android 10（API 29）起，系统对后台启动 Activity 有严格限制：
 *    BOOT_COMPLETED 属于少数豁免场景之一，所以这里可以放心 startActivity。
 */
public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent != null ? intent.getAction() : null;
        if (action == null) return;
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
                && !"android.intent.action.QUICKBOOT_POWERON".equals(action)
                && !"com.htc.intent.action.QUICKBOOT_POWERON".equals(action)) {
            return;
        }

        // 1) 先拉起前台服务（保活 + WakeLock + MediaSession）
        Intent svc = new Intent(context, RadioPlaybackService.class);
        svc.putExtra("autostart", true);
        if (Build.VERSION.SDK_INT >= 26) {
            context.startForegroundService(svc);
        } else {
            context.startService(svc);
        }

        // 2) 再打开主界面（内部加载 index.html?auto=1 自动起播首台）
        Intent act = new Intent(context, MainActivity.class);
        act.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(act);
    }
}
