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

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;
        if (!new KioskConfig(this).isKioskEnabled()) return; // 未锁定时不干预

        int type = event.getType();
        boolean shadeOpen;

        if (type == AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
            shadeOpen = hasSystemUiPanel();
        } else if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            CharSequence cn = event.getClassName();
            CharSequence pkg = event.getPackageName();
            shadeOpen = pkg != null && "com.android.systemui".contentEquals(pkg)
                    && cn != null
                    && (cn.toString().contains("StatusBar")
                    || cn.toString().contains("Notification")
                    || cn.toString().contains("Shade"));
        } else {
            return;
        }

        if (shadeOpen) {
            // 面板打开 → 代按返回键关闭（面板关闭后多余的 BACK 由 WebView 正常处理）
            performGlobalAction(GLOBAL_ACTION_BACK);
        }
    }

    /** 当前屏幕上是否存在系统界面弹出的非系统栏窗口（即展开中的面板） */
    private boolean hasSystemUiPanel() {
        List<AccessibilityWindowInfo> windows = getWindows();
        if (windows == null) return false;
        for (AccessibilityWindowInfo w : windows) {
            // 只认「焦点在系统界面、且不是状态栏/输入法」的窗口 = 展开中的面板
            if (w.isFocused() && w.getType() != AccessibilityWindowInfo.TYPE_SYSTEM_BAR
                    && w.getType() != AccessibilityWindowInfo.TYPE_INPUT_METHOD) {
                CharSequence title = null;
                if (android.os.Build.VERSION.SDK_INT >= 33) {
                    title = w.getTitle();
                }
                if (title == null || title.length() == 0
                        || title.toString().contains("通知")
                        || title.toString().contains("Notification")
                        || title.toString().contains("快捷")
                        || title.toString().contains("Shuttle")) {
                    return true;
                }
            }
        }
        return false;
    }

    @Override
    public void onInterrupt() {
    }
}
