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

    @Override
    public void onCreate() {
        super.onCreate();

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
