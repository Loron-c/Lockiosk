package com.yichi.kiosk;

import android.app.Application;

/**
 * 全局崩溃捕获 → 驱动「崩溃安全模式」
 *
 * 为什么不用物理快捷键做恢复：
 *  App 崩溃后进程已死，任何键都收不到。正确做法是崩溃前先落盘一个计数，
 *  下次启动时 MainActivity 读到「5 分钟内 ≥2 次」就不再加载 WebView，
 *  改为显示原生逃生页 —— 逃生页本身极简，不会崩，平板永远不会「变砖」。
 */
public class KioskApp extends Application {

    /**
     * 「管理会话」窗口期：管理员通过 PIN / 锁屏凭证验证成功后的 5 分钟内，
     * 自动收回通知面板、切回前台、顶部挡板等防护全部静默，
     * 方便管理员进行解锁、进系统设置、拉通知栏查看等维护操作。
     * 工人在窗口期外使用设备，防护照旧。
     * 静态字段：无障碍服务与 Activity 同进程，直接共享。
     */
    private static volatile long adminUntil = 0;
    private static final long ADMIN_SESSION_MS = 5 * 60_000L;

    public static void beginAdminSession() {
        adminUntil = System.currentTimeMillis() + ADMIN_SESSION_MS;
        // 可见提示：避免「刚输完密码防护突然全部失效」被误判为 bug
        try {
            android.widget.Toast.makeText(instance, "管理会话已开启：5 分钟内防护静默（不收回通知栏、不切回前台），到期自动恢复",
                    android.widget.Toast.LENGTH_LONG).show();
        } catch (Throwable ignored) {
        }
    }

    public static boolean isAdminSession() {
        return System.currentTimeMillis() < adminUntil;
    }

    private static volatile KioskApp instance;

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;

        final Thread.UncaughtExceptionHandler defaultHandler =
                Thread.getDefaultUncaughtExceptionHandler();

        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            try {
                // 落盘崩溃计数（同步写，进程马上要死）
                new KioskConfig(this).recordCrash();
            } catch (Throwable ignored) {
                // 计数失败也不能阻塞默认流程
            }
            // 结束进程（覆盖默认 handler 后必须自己收尾，否则卡死）
            android.os.Process.killProcess(android.os.Process.myPid());
            System.exit(10);
        });
    }
}
