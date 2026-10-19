package com.yichi.kiosk;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.security.MessageDigest;
import java.util.Properties;

/**
 * 配置管理：所有 Kiosk 配置项集中读写。
 * 支持导出/导入 JSON 风格配置文件，用于 16 台批量部署。
 */
public class KioskConfig {

    private static final String PREF = "kiosk_prefs";

    public static final String KEY_URL = "home_url";
    public static final String KEY_PIN_HASH = "pin_hash";
    public static final String KEY_TAP_COUNT = "tap_count";
    public static final String KEY_CORNER_GESTURE = "corner_gesture";
    public static final String KEY_KEEP_AWAKE = "keep_awake";
    public static final String KEY_AUTO_START = "auto_start";
    public static final String KEY_KIOSK_ENABLED = "kiosk_enabled";
    public static final String KEY_TITLE = "app_title";
    public static final String KEY_RELOAD_MINS = "reload_mins";
    public static final String KEY_ZOOM = "zoom";
    public static final String KEY_DOMAIN_LOCK = "domain_lock";
    public static final String KEY_ORIENTATION = "orientation";
    public static final String KEY_CRASH_COUNT = "crash_count";
    public static final String KEY_CRASH_LAST = "crash_last";
    public static final String KEY_BLOCK_VOLUME = "block_volume";
    public static final String KEY_HIDE_STATUSBAR = "hide_statusbar";
    public static final String KEY_ALLOW_DEVICE_CRED = "allow_device_cred";

    /** 默认配置：首次启动时使用 */
    public static final String DEFAULT_URL = "https://www.example.com";
    public static final String DEFAULT_PIN = "202608";
    public static final int DEFAULT_TAP_COUNT = 5;

    private final SharedPreferences sp;

    public KioskConfig(Context ctx) {
        this.sp = ctx.getApplicationContext().getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    public String getUrl() {
        return sp.getString(KEY_URL, DEFAULT_URL);
    }

    public void setUrl(String url) {
        sp.edit().putString(KEY_URL, url).apply();
    }

    public String getTitle() {
        return sp.getString(KEY_TITLE, "生产系统");
    }

    public void setTitle(String t) {
        sp.edit().putString(KEY_TITLE, t).apply();
    }

    /** 首次启动时是否已经初始化过 PIN */
    public boolean hasPin() {
        return sp.contains(KEY_PIN_HASH);
    }

    /** 设置 PIN（只存 SHA-256 哈希，不存明文） */
    public void setPin(String pin) {
        sp.edit().putString(KEY_PIN_HASH, sha256(pin)).apply();
    }

    /**
     * 校验 PIN
     *
     * 修复：原实现在 stored == null 时允许默认 PIN 通过并落地保存，
     * 这会让「清空数据」变成绕过密码的后门。现在一律要求哈希比对，
     * 首次启动时 MainActivity 已负责写入默认 PIN 的哈希。
     */
    public boolean checkPin(String pin) {
        if (pin == null || pin.isEmpty()) return false;
        String stored = sp.getString(KEY_PIN_HASH, null);
        if (stored == null) {
            // 理论上不会走到这里（onCreate 已落地默认 PIN），兜底也不放行
            return false;
        }
        return stored.equals(sha256(pin));
    }

    /** 退出/进入配置需要连点的次数 */
    public int getTapCount() {
        return sp.getInt(KEY_TAP_COUNT, DEFAULT_TAP_COUNT);
    }

    public void setTapCount(int n) {
        sp.edit().putInt(KEY_TAP_COUNT, Math.max(3, n)).apply();
    }

    /** 是否启用对角双击手势退出（更防误触） */
    public boolean isCornerGesture() {
        return sp.getBoolean(KEY_CORNER_GESTURE, false);
    }

    public void setCornerGesture(boolean b) {
        sp.edit().putBoolean(KEY_CORNER_GESTURE, b).apply();
    }

    public boolean isKeepAwake() {
        return sp.getBoolean(KEY_KEEP_AWAKE, true);
    }

    public void setKeepAwake(boolean b) {
        sp.edit().putBoolean(KEY_KEEP_AWAKE, b).apply();
    }

    public boolean isAutoStart() {
        return sp.getBoolean(KEY_AUTO_START, true);
    }

    public void setAutoStart(boolean b) {
        sp.edit().putBoolean(KEY_AUTO_START, b).apply();
    }

    /** Kiosk 锁定是否已开启（开启后 UI 才拦截按键、禁下拉） */
    public boolean isKioskEnabled() {
        return sp.getBoolean(KEY_KIOSK_ENABLED, false);
    }

    public void setKioskEnabled(boolean b) {
        sp.edit().putBoolean(KEY_KIOSK_ENABLED, b).apply();
    }

    /** 自动刷新间隔（分钟），0 = 不自动刷新 */
    public int getReloadMins() {
        return sp.getInt(KEY_RELOAD_MINS, 0);
    }

    public void setReloadMins(int m) {
        sp.edit().putInt(KEY_RELOAD_MINS, Math.max(0, m)).apply();
    }

    public int getZoom() {
        return sp.getInt(KEY_ZOOM, 100);
    }

    public void setZoom(int z) {
        sp.edit().putInt(KEY_ZOOM, Math.min(200, Math.max(50, z))).apply();
    }

    /**
     * 网址域名锁定：开启后 WebView 只能停留在生产系统同源域名内，
     * 点任何外部链接（百度、淘宝等）都会被拦截。
     * 这是「平板只能使用特定网页」的核心实现，默认开启。
     */
    public boolean isDomainLock() {
        return sp.getBoolean(KEY_DOMAIN_LOCK, true);
    }

    public void setDomainLock(boolean b) {
        sp.edit().putBoolean(KEY_DOMAIN_LOCK, b).apply();
    }

    // ---------- 方向锁定：0=跟随系统 1=固定横屏 2=固定竖屏 ----------

    public int getOrientation() {
        return sp.getInt(KEY_ORIENTATION, 0);
    }

    public void setOrientation(int o) {
        sp.edit().putInt(KEY_ORIENTATION, Math.max(0, Math.min(2, o))).apply();
    }

    // ---------- 细粒度锁定开关（v1.1.0） ----------

    /** 锁定状态下是否屏蔽音量键（防止工人调音量/截图组合键），默认开 */
    public boolean isBlockVolume() {
        return sp.getBoolean(KEY_BLOCK_VOLUME, true);
    }

    public void setBlockVolume(boolean b) {
        sp.edit().putBoolean(KEY_BLOCK_VOLUME, b).apply();
    }

    /** 锁定状态下是否隐藏状态栏（下滑不出通知栏），默认开 */
    public boolean isHideStatusbar() {
        return sp.getBoolean(KEY_HIDE_STATUSBAR, true);
    }

    public void setHideStatusbar(boolean b) {
        sp.edit().putBoolean(KEY_HIDE_STATUSBAR, b).apply();
    }

    /**
     * 防忘密码：是否允许用「平板系统锁屏密码」作为备用管理员验证。
     * 开启后 PIN 弹窗出现「忘记密码？用锁屏密码」入口，
     * 通过系统级验证即可进设置改回 PIN。默认开。
     */
    public boolean isAllowDeviceCred() {
        return sp.getBoolean(KEY_ALLOW_DEVICE_CRED, true);
    }

    public void setAllowDeviceCred(boolean b) {
        sp.edit().putBoolean(KEY_ALLOW_DEVICE_CRED, b).apply();
    }

    // ---------- 崩溃安全模式计数（不导出） ----------

    private static final long CRASH_WINDOW_MS = 5 * 60_000L;

    /** App 崩溃时调用：5 分钟内的连续崩溃累加，否则重新从 1 开始 */
    public void recordCrash() {
        long now = System.currentTimeMillis();
        long last = sp.getLong(KEY_CRASH_LAST, 0);
        int count = (now - last < CRASH_WINDOW_MS) ? sp.getInt(KEY_CRASH_COUNT, 0) + 1 : 1;
        sp.edit().putInt(KEY_CRASH_COUNT, count)
                .putLong(KEY_CRASH_LAST, now)
                .commit(); // 崩溃路径必须同步落盘
    }

    /** 是否处于崩溃循环（5 分钟内 ≥2 次） */
    public boolean isCrashLooping() {
        long last = sp.getLong(KEY_CRASH_LAST, 0);
        int count = sp.getInt(KEY_CRASH_COUNT, 0);
        return count >= 2 && System.currentTimeMillis() - last < CRASH_WINDOW_MS;
    }

    /** 页面正常加载完成 / 用户手动重试时清零 */
    public void clearCrash() {
        sp.edit().putInt(KEY_CRASH_COUNT, 0)
                .putLong(KEY_CRASH_LAST, 0)
                .commit();
    }

    // ---------- 导出 / 导入（批量部署用） ----------

    public boolean exportTo(File out) {
        try {
            Properties p = new Properties();
            p.setProperty("url", getUrl());
            p.setProperty("title", getTitle());
            p.setProperty("tapCount", String.valueOf(getTapCount()));
            p.setProperty("cornerGesture", String.valueOf(isCornerGesture()));
            p.setProperty("keepAwake", String.valueOf(isKeepAwake()));
            p.setProperty("autoStart", String.valueOf(isAutoStart()));
            p.setProperty("kioskEnabled", String.valueOf(isKioskEnabled()));
            p.setProperty("reloadMins", String.valueOf(getReloadMins()));
            p.setProperty("zoom", String.valueOf(getZoom()));
            p.setProperty("domainLock", String.valueOf(isDomainLock()));
            p.setProperty("orientation", String.valueOf(getOrientation()));
            p.setProperty("blockVolume", String.valueOf(isBlockVolume()));
            p.setProperty("hideStatusbar", String.valueOf(isHideStatusbar()));
            p.setProperty("allowDeviceCred", String.valueOf(isAllowDeviceCred()));
            // PIN 哈希一并导出，其余平板导入后 PIN 完全一致
            p.setProperty("pinHash", sp.getString(KEY_PIN_HASH, ""));
            FileOutputStream fos = new FileOutputStream(out);
            p.store(fos, "Kiosk provisioning config");
            fos.close();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public boolean importFrom(File in) {
        try {
            Properties p = new Properties();
            FileInputStream fis = new FileInputStream(in);
            p.load(fis);
            fis.close();
            SharedPreferences.Editor e = sp.edit();
            if (p.getProperty("url") != null) e.putString(KEY_URL, p.getProperty("url"));
            if (p.getProperty("title") != null) e.putString(KEY_TITLE, p.getProperty("title"));
            if (p.getProperty("tapCount") != null)
                e.putInt(KEY_TAP_COUNT, Integer.parseInt(p.getProperty("tapCount")));
            if (p.getProperty("cornerGesture") != null)
                e.putBoolean(KEY_CORNER_GESTURE, Boolean.parseBoolean(p.getProperty("cornerGesture")));
            if (p.getProperty("keepAwake") != null)
                e.putBoolean(KEY_KEEP_AWAKE, Boolean.parseBoolean(p.getProperty("keepAwake")));
            if (p.getProperty("autoStart") != null)
                e.putBoolean(KEY_AUTO_START, Boolean.parseBoolean(p.getProperty("autoStart")));
            if (p.getProperty("kioskEnabled") != null)
                e.putBoolean(KEY_KIOSK_ENABLED, Boolean.parseBoolean(p.getProperty("kioskEnabled")));
            if (p.getProperty("reloadMins") != null)
                e.putInt(KEY_RELOAD_MINS, Integer.parseInt(p.getProperty("reloadMins")));
            if (p.getProperty("zoom") != null)
                e.putInt(KEY_ZOOM, Integer.parseInt(p.getProperty("zoom")));
            if (p.getProperty("domainLock") != null)
                e.putBoolean(KEY_DOMAIN_LOCK, Boolean.parseBoolean(p.getProperty("domainLock")));
            if (p.getProperty("orientation") != null)
                e.putInt(KEY_ORIENTATION, Integer.parseInt(p.getProperty("orientation")));
            if (p.getProperty("blockVolume") != null)
                e.putBoolean(KEY_BLOCK_VOLUME, Boolean.parseBoolean(p.getProperty("blockVolume")));
            if (p.getProperty("hideStatusbar") != null)
                e.putBoolean(KEY_HIDE_STATUSBAR, Boolean.parseBoolean(p.getProperty("hideStatusbar")));
            if (p.getProperty("allowDeviceCred") != null)
                e.putBoolean(KEY_ALLOW_DEVICE_CRED, Boolean.parseBoolean(p.getProperty("allowDeviceCred")));
            String ph = p.getProperty("pinHash");
            if (ph != null && !ph.isEmpty()) e.putString(KEY_PIN_HASH, ph);
            e.apply();
            return true;
        } catch (Exception ex) {
            return false;
        }
    }

    // ---------- 工具 ----------

    public static String sha256(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(s.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return String.valueOf(s.hashCode());
        }
    }
}
