package com.yichi.kiosk;

import android.accessibilityservice.AccessibilityService;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityWindowInfo;

import java.util.List;

/**
 * 通知面板自动关闭（无障碍服务）
 *
 * ZUI 的通知栏下拉面板不走系统栏通道，悬浮挡板 / WindowInsetsController
 * 都收不回它。无障碍服务能感知「系统界面弹出了面板」，此时代按一次
 * 返回键即可把面板关掉 —— 效果：工人刚滑出通知栏立刻缩回（Fully Kiosk 同款原理）。
 *
 * 注意：本服务只在 Kiosk 锁定开启时生效；未锁定时完全静默，不影响正常使用。
 */
public class AutoCloseService extends AccessibilityService {

    private long lastBackAt = 0;
    private static final long BACK_DEBOUNCE_MS = 800;

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;
        if (!new KioskConfig(this).isKioskEnabled()) return; // 未锁定时不干预

        // 修复：只认「系统界面(systemui)弹出的、类名含状态栏/通知特征」的窗口变化。
        // 旧版另有一个 WINDOWS_CHANGED 分支用「焦点窗口非系统栏」判定面板 —— 但焦点窗口
        // 永远是前台 App 自己的窗口，判定恒真，会把 PIN 弹窗等普通窗口误杀（代按 BACK 关掉）。
        if (event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;

        CharSequence cn = event.getClassName();
        CharSequence pkg = event.getPackageName();
        boolean shadeOpen = pkg != null && "com.android.systemui".contentEquals(pkg)
                && cn != null
                && (cn.toString().contains("StatusBar")
                || cn.toString().contains("Notification")
                || cn.toString().contains("Shade"));

        // 去抖：避免连续事件把 BACK 发给网页导致异常后退
        if (shadeOpen && System.currentTimeMillis() - lastBackAt > BACK_DEBOUNCE_MS) {
            lastBackAt = System.currentTimeMillis();
            performGlobalAction(GLOBAL_ACTION_BACK);
        }
    }

    @Override
    public void onInterrupt() {
    }
}
