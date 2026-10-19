package com.yichi.kiosk;

import android.app.admin.DeviceAdminReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * 设备管理器接收器 —— 激活后无法通过常规方式卸载本 App，
 * 防止车间工人偷偷卸载锁定软件。
 * 在设置界面里手动激活，不激活也不影响锁屏功能。
 */
public class AdminReceiver extends DeviceAdminReceiver {
    @Override
    public void onEnabled(Context context, Intent intent) {
        super.onEnabled(context, intent);
    }

    @Override
    public void onDisabled(Context context, Intent intent) {
        super.onDisabled(context, intent);
    }
}
