package com.yichi.kiosk;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.util.List;

/**
 * 通知面板自动关闭（无障碍服务）
 *
 * 机制：感知「系统界面(systemui)弹出了占屏很大的窗口」= 通知面板被展开，
 * 此时代按一次返回键即可把面板关掉 —— 效果：刚滑出通知栏立刻缩回。
 *
 * 双通道保证生效：
 *  1) 事件通道：WINDOW_STATE_CHANGED（类名含 StatusBar/Notification/Shade 的快速路径）
 *     + WINDOWS_CHANGED（遍历窗口列表按「包名 + 占屏高度」判定）
 *  2) 巡检通道：部分 ROM（如小米 HyperOS）展开面板时可能两种事件都不广播，
 *     因此服务连接后每 700ms 主动扫描一次窗口列表兜底
 *
 * 安全边界：
 *  - 只认 com.android.systemui 包，不会误伤本应用自己的窗口（PIN 弹窗等）
 *  - 状态栏/导航栏高度很小（<25% 屏高），高度过滤天然排除
 *  - 本服务只在 Kiosk 锁定开启时生效；未锁定时完全静默
 */
public class AutoCloseService extends AccessibilityService {

    private long lastBackAt = 0;
    private static final long BACK_DEBOUNCE_MS = 800;
    private static final long POLL_MS = 700;
    private static final int SHADE_MIN_HEIGHT_RATIO = 4; // 占屏高度 > 1/4 才认作展开的面板

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable poller = this::pollOnce;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        handler.post(poller); // 启动巡检兜底
    }

    @Override
    public boolean onUnbind(Intent intent) {
        handler.removeCallbacks(poller);
        return super.onUnbind(intent);
    }

    /** 巡检通道：ROM 不广播事件时也能感知面板 */
    private void pollOnce() {
        try {
            if (new KioskConfig(this).isKioskEnabled() && !KioskApp.isAdminSession()
                    && hasExpandedSystemUiPanel()) {
                sendBack();
            }
        } catch (Throwable ignored) {
        }
        handler.postDelayed(poller, POLL_MS);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;
        if (!new KioskConfig(this).isKioskEnabled()) return; // 未锁定时不干预
        if (KioskApp.isAdminSession()) return; // 管理会话期间静默（管理员可能在解系统锁屏）

        int type = event.getEventType();
        boolean shadeOpen;
        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            // 快速路径：类名直接带状态栏/通知特征
            CharSequence cn = event.getClassName();
            CharSequence pkg = event.getPackageName();
            shadeOpen = pkg != null && "com.android.systemui".contentEquals(pkg)
                    && cn != null
                    && (cn.toString().contains("StatusBar")
                    || cn.toString().contains("Notification")
                    || cn.toString().contains("Shade"));
        } else if (type == AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
            shadeOpen = hasExpandedSystemUiPanel();
        } else {
            return;
        }

        if (shadeOpen) sendBack();
    }

    private void sendBack() {
        if (System.currentTimeMillis() - lastBackAt > BACK_DEBOUNCE_MS) {
            lastBackAt = System.currentTimeMillis();
            performGlobalAction(GLOBAL_ACTION_BACK);
        }
    }

    /** 屏幕上是否存在「systemui 拥有、且占屏高度 > 1/4」的窗口 = 展开中的通知面板 */
    private boolean hasExpandedSystemUiPanel() {
        List<AccessibilityWindowInfo> windows = getWindows();
        if (windows == null || windows.isEmpty()) return false;
        int screenH = getResources().getDisplayMetrics().heightPixels;
        if (screenH <= 0) return false;
        for (AccessibilityWindowInfo w : windows) {
            try {
                if (w.getType() == AccessibilityWindowInfo.TYPE_INPUT_METHOD) continue;
                AccessibilityNodeInfo root = w.getRoot();
                if (root == null) continue;
                if (!"com.android.systemui".contentEquals(String.valueOf(root.getPackageName()))) {
                    continue;
                }
                Rect b = new Rect();
                w.getBoundsInScreen(b);
                if (b.height() > screenH / SHADE_MIN_HEIGHT_RATIO) return true;
            } catch (Throwable ignored) {
                // 个别系统 getRoot() 可能受限：跳过该窗口
            }
        }
        return false;
    }

    @Override
    public void onInterrupt() {
        handler.removeCallbacks(poller);
    }
}
