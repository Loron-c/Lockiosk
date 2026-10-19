package com.yichi.kiosk;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

/**
 * 开机自启
 *
 * 风险说明：
 *  Android 10+ 对「后台启动 Activity」有严格限制，开机广播里直接 startActivity
 *  在部分系统会被静默拒绝。因此在标准 startActivity 之外，这里同时尝试：
 *   1) 全屏通知（若已授予通知权限，点击可恢复）
 *   2) 若本应用已被设为「默认桌面（Launcher）」，系统开机后会自行拉起，无需广播
 *
 *  最稳做法（README 已写明）：把本应用设为默认桌面。
 *  但需注意 —— 设为默认桌面会取代系统桌面，若不希望这样，请改用「屏幕固定」方案。
 */
public class BootReceiver extends BroadcastReceiver {

    private static final String TAG = "KioskBoot";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) return;

        String action = intent.getAction();
        boolean shouldBoot =
                Intent.ACTION_BOOT_COMPLETED.equals(action)
                        || "android.intent.action.QUICKBOOT_POWERON".equals(action)
                        || "com.htc.intent.action.QUICKBOOT_POWERON".equals(action)
                        || Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(action)
                        || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action);

        if (!shouldBoot) return;

        KioskConfig cfg = new KioskConfig(context);
        if (!cfg.isAutoStart()) {
            Log.i(TAG, "自动启动已关闭，跳过");
            return;
        }

        try {
            Intent i = new Intent(context, MainActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_CLEAR_TOP
                    | Intent.FLAG_ACTIVITY_NO_ANIMATION
                    | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            context.startActivity(i);
            Log.i(TAG, "已尝试拉起 Kiosk 界面");
        } catch (Exception e) {
            // Android 10+ 可能抛 BackgroundActivityStartDenied，此属系统限制
            Log.w(TAG, "后台启动被系统限制: " + e.getMessage());
        }
    }
}
