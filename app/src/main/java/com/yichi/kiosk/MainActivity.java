package com.yichi.kiosk;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Kiosk 锁屏主界面
 *
 * v1.1.0 新增：
 *  - 崩溃安全模式：5 分钟内连续崩溃 2 次不再加载 WebView，
 *    显示原生逃生页（重试 / 凭 PIN 进系统设置），防止「默认桌面 + 崩溃循环」变砖
 *  - 方向锁定：跟随系统 / 固定横屏 / 固定竖屏
 *  - 修复 v1.0.2 引入的 bug：onKeyDown/onKeyUp 被误删导致音量键拦截失效
 */
public class MainActivity extends Activity {

    private WebView webView;
    private KioskConfig cfg;
    private FrameLayout root;
    private View loadingView;
    private TextView loadingText;
    private boolean safeMode = false;

    // 管理手势：左上角双击 → 右下角双击（v1.1.0 起为唯一入口，防误触最好）
    // 判定：左上区域内 700ms 内点两下 → 4 秒内在右下区域内 700ms 内点两下
    private long tlTap1 = 0;   // 左上第一击
    private long tlTap2 = 0;   // 左上第二击（双击成立）
    private long brTap1 = 0;   // 右下第一击
    private int blockCount = 0; // 域锁连续拦截计数（防跳转循环）
    private static final long DOUBLE_TAP_MS = 700;
    private static final long SEQ_WINDOW_MS = 4000;
    private static final int CORNER_SIZE_DP = 100;

    // 通知栏拦截挡板：悬浮在屏幕最顶部的透明窗口（Fully Kiosk 同款机制）。
    // 系统的"顶部下滑"手势在落到 App 前会被挡板窗口吃掉，通知栏就拉不出来。
    // 需要用户授予「显示在其他应用上层」权限（设置页有引导按钮）。
    private View statusBarGuard;

    private void applyStatusBarGuard() {
        WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        boolean need = cfg.isKioskEnabled() && cfg.isHideStatusbar()
                && Settings.canDrawOverlays(this);
        if (need && statusBarGuard == null) {
            try {
                View guard = new View(this);
                int type = (Build.VERSION.SDK_INT >= 26)
                        ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                        : WindowManager.LayoutParams.TYPE_PHONE; // Android 8.0 以下
                WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                        WindowManager.LayoutParams.MATCH_PARENT,
                        (int) (40 * getResources().getDisplayMetrics().density),
                        type,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                        PixelFormat.TRANSLUCENT);
                // 修复：不指定 gravity 时悬浮窗默认显示在屏幕中央，挡板根本没在顶部！
                lp.gravity = android.view.Gravity.TOP;
                wm.addView(guard, lp);
                statusBarGuard = guard;
            } catch (Exception e) {
                statusBarGuard = null; // 无权限或系统不允许，静默降级（还有边缘拦截兜底）
            }
        } else if (!need && statusBarGuard != null) {
            try {
                wm.removeView(statusBarGuard);
            } catch (Exception ignored) {
            }
            statusBarGuard = null;
        }
    }

    // 触摸判定（区分点击与滑动）
    private float downX, downY;
    private long downTime;
    private int touchSlop;

    // 侧滑返回拦截：默认只拦左边缘（ZUI 返回手势方向）
    private static final boolean BLOCK_RIGHT_EDGE = false;
    private static final int EDGE_SWIPE_DP = 24;
    // 顶部边缘拦截：吞掉从屏幕最顶边下划的手势，防止拉出通知栏
    private static final int TOP_EDGE_DP = 28;
    // 本次手势是否起始于被封锁的边缘区（起了头就整条手势都吃掉）
    private boolean gestureFromBlockedEdge = false;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private Runnable reloadRunnable;

    // 加载超时检测：25 秒还没加载完就显示错误页（正常内网页面几秒内必开）
    private static final long LOAD_TIMEOUT_MS = 25_000;
    private final Runnable loadTimeoutRunnable = () -> {
        if (loadingView != null && loadingView.getVisibility() == View.VISIBLE
                && !safeMode) {
            showError("加载超时（超过 25 秒）\n\n可能原因：\n"
                    + "1. 生产系统网址填错（请进设置核对）\n"
                    + "2. 内网证书问题（新版本已放行自签名证书，请确认装的是最新 APK）\n"
                    + "3. 登录跳转被域名锁定拦截（进设置暂时关掉「锁定访问域名」再试）\n\n"
                    + "点击屏幕可重试");
        }
    };

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        cfg = new KioskConfig(this);

        applyOrientation();

        // 亮屏直达：屏幕熄灭再亮起时直接显示本应用，不经过系统锁屏输密界面。
        // 系统锁屏密码仍在后台生效 —— 只挡「离开本应用」，不挡「使用本应用」，
        // 平板锁屏密码同时保留着 PIN 忘记密码的兜底功能。
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        } else {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                    | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        }

        // PIN 初始化必须放在崩溃检查之前：
        // 安全模式逃生页的「进入系统设置」要验 PIN，若此时哈希还没落地会永远验证不过
        if (!cfg.hasPin()) {
            cfg.setPin(KioskConfig.DEFAULT_PIN);
        }

        // 崩溃安全模式：5 分钟内崩溃 ≥2 次 → 不加载 WebView，进入原生逃生页
        if (cfg.isCrashLooping()) {
            enterSafeMode();
            return;
        }

        touchSlop = ViewConfiguration.get(this).getScaledTouchSlop();

        root = new FrameLayout(this);
        root.setBackgroundColor(Color.WHITE);

        buildWebView();

        loadingView = buildLoadingView();
        root.addView(loadingView);

        setContentView(root);
        startBarsWatcher(); // 状态栏被滑出 → 立刻收回去
        handler.post(barPollRunnable); // 主动巡检兜底

        showLoading("正在加载生产系统…");
        loadHome();

        applyKeepAwake();
        scheduleReload();
    }

    // ---------- 方向锁定 ----------

    private void applyOrientation() {
        int o = cfg.getOrientation();
        if (o == 1) {
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE);
        } else if (o == 2) {
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT);
        } else {
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
        }
    }

    // ---------- 崩溃安全模式 ----------

    /**
     * 逃生页：纯原生控件，不碰 WebView，本身不会崩。
     * 「重试」清空崩溃计数并重启界面；
     * 「进系统设置」需 PIN，用于把默认桌面改回原桌面等自救操作。
     */
    private void enterSafeMode() {
        safeMode = true;

        LinearLayout ll = new LinearLayout(this);
        ll.setOrientation(LinearLayout.VERTICAL);
        ll.setGravity(Gravity.CENTER);
        ll.setBackgroundColor(Color.parseColor("#141005"));
        int pad = (int) (32 * getResources().getDisplayMetrics().density);
        ll.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("⚠ Kiosk 安全模式");
        title.setTextColor(Color.parseColor("#FFB300"));
        title.setTextSize(24);
        title.setGravity(Gravity.CENTER);
        ll.addView(title);

        TextView desc = new TextView(this);
        desc.setText("\n应用连续崩溃，已自动暂停网页加载以保护平板。\n\n"
                + "可选操作：\n"
                + "• 点「重新尝试启动」—— 若只是偶发崩溃，即可恢复正常\n"
                + "• 点「进入系统设置」—— 需管理员密码，可把默认桌面改回原桌面后再处理\n");
        desc.setTextColor(Color.parseColor("#E0E0E0"));
        desc.setTextSize(15);
        desc.setGravity(Gravity.CENTER);
        ll.addView(desc);

        Button btnRetry = new Button(this);
        btnRetry.setText("重新尝试启动");
        btnRetry.setAllCaps(false);
        btnRetry.setOnClickListener(v -> {
            cfg.clearCrash();
            recreate(); // 计数已清，重走 onCreate 正常路径
        });
        ll.addView(btnRetry);

        Button btnSys = new Button(this);
        btnSys.setText("进入系统设置（需密码）");
        btnSys.setAllCaps(false);
        btnSys.setOnClickListener(v -> askPinThen(() -> {
            try {
                startActivity(new Intent(Settings.ACTION_SETTINGS));
            } catch (Exception e) {
                Toast.makeText(this, "无法打开系统设置", Toast.LENGTH_SHORT).show();
            }
        }));
        ll.addView(btnSys);

        setContentView(ll);
    }

    // ---------- WebView ----------

    @SuppressLint("SetJavaScriptEnabled")
    private void buildWebView() {
        webView = new WebView(this);
        FrameLayout.LayoutParams wlp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        webView.setLayoutParams(wlp);

        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        s.setSupportZoom(true);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        s.setJavaScriptCanOpenWindowsAutomatically(true);
        s.setTextZoom(cfg.getZoom());

        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                return handleNavigation(req.getUrl());
            }

            @Override
            @SuppressWarnings("deprecation")
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return handleNavigation(Uri.parse(url));
            }

            @Override
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                super.onPageStarted(view, url, favicon);
                if (!cfg.isDomainLock() || TextUtils.isEmpty(url)) return;
                Uri u = Uri.parse(url);
                String host = u.getHost();
                if (host == null) return;
                if (!isAllowedHost(host)) {
                    view.stopLoading();
                    handler.post(() -> {
                        blockCount++;
                        if (blockCount >= 2) {
                            // 反复被拦 = 登录流程跳到了别的域名，停止循环重试并明示
                            blockCount = 0;
                            showError("登录跳转被「域名锁定」拦截\n\n"
                                    + "生产系统跳到了另一个域名（" + host + "）。\n"
                                    + "解决：进设置 → 暂时关闭「锁定访问域名」试一次。\n"
                                    + "若确认需要该域名长期放行，请联系开发把白名单加上。\n\n"
                                    + "点击屏幕可重试");
                        } else {
                            Toast.makeText(MainActivity.this,
                                    "已拦截跳转：" + host, Toast.LENGTH_SHORT).show();
                            loadHome();
                        }
                    });
                } else {
                    blockCount = 0;
                }
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                hideLoading();
                // 页面正常加载完成 → 崩溃计数清零（安全模式的判定基准）
                cfg.clearCrash();
            }

            @Override
            public void onReceivedError(WebView view, int errorCode,
                                        String description, String failingUrl) {
                if (!TextUtils.isEmpty(failingUrl) && failingUrl.equals(cfg.getUrl())) {
                    showError("网络连接失败\n\n请检查：\n1. 平板是否连上厂区 WiFi\n2. 生产系统服务器是否正常\n\n" + description);
                }
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest req,
                                        android.webkit.WebResourceError error) {
                // 新 API：只要是主框架失败就显示错误页（不再要求网址与首页完全相等，
                // 否则登录跳转后的地址加载失败会白屏无提示）
                if (req.isForMainFrame()) {
                    showError("页面加载失败\n\n请检查：\n1. 平板是否连上厂区 WiFi\n2. 生产系统服务器是否正常\n\n"
                            + error.getDescription());
                }
            }

            @Override
            public void onReceivedSslError(WebView view,
                                           android.webkit.SslErrorHandler handler,
                                           android.net.http.SslError error) {
                // 内网生产系统常见自签名证书，WebView 默认静默取消 → 白屏。
                // 放行安全性由「域名锁定」兜底：只能访问生产系统域名，放行的也只是它。
                handler.proceed();
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                if (newProgress >= 85) hideLoading();
            }

            @Override
            public boolean onCreateWindow(WebView view, boolean isDialog,
                                          boolean isUserGesture, android.os.Message resultMsg) {
                // 拒绝新窗口：window.open / target=_blank 不开新窗，同域跳转走主 WebView
                return false;
            }
        });

        root.addView(webView);
    }

    private void loadHome() {
        String url = cfg.getUrl();
        if (TextUtils.isEmpty(url)) {
            showError("尚未配置生产系统地址\n\n左上角双击，再右下角双击，进入设置");
            return;
        }
        try {
            webView.loadUrl(url);
        } catch (Exception e) {
            showError("地址无法打开：" + url);
        }
    }

    /**
     * 统一的导航判定：返回 true = 拦截该次跳转
     * 与 Fully Kiosk 的 URL 白名单机制对齐。
     */
    private boolean handleNavigation(Uri u) {
        if (u == null || u.getScheme() == null) return true;
        String scheme = u.getScheme().toLowerCase();

        if (!scheme.equals("http") && !scheme.equals("https")) {
            return true; // tel/mailto/intent/file 等全部拦掉
        }

        if (!cfg.isDomainLock()) {
            return false;
        }

        String host = u.getHost();
        if (isAllowedHost(host)) {
            return false;
        }

        final String blocked = (host == null ? u.toString() : host);
        handler.post(() -> Toast.makeText(MainActivity.this,
                "已限制访问：" + blocked + "\n本平板仅可使用生产系统",
                Toast.LENGTH_SHORT).show());
        return true;
    }

    /**
     * 域名白名单：host 与生产系统相同，或为其子域。
     * （用户已确认：生产系统为自带账号密码登录，无跨域 SSO，无需额外白名单）
     */
    private boolean isAllowedHost(String host) {
        if (TextUtils.isEmpty(host)) return false;
        String homeHost = Uri.parse(cfg.getUrl()).getHost();
        if (TextUtils.isEmpty(homeHost)) return false;

        host = host.toLowerCase();
        homeHost = homeHost.toLowerCase();

        if (host.equals(homeHost)) return true;
        return host.endsWith("." + homeHost);
    }

    // ---------- 加载/错误页 ----------

    private View buildLoadingView() {
        LinearLayout ll = new LinearLayout(this);
        ll.setOrientation(LinearLayout.VERTICAL);
        ll.setGravity(Gravity.CENTER);
        ll.setBackgroundColor(Color.parseColor("#0F1117"));
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        ll.setLayoutParams(lp);

        TextView tv = new TextView(this);
        tv.setTextColor(Color.parseColor("#E0E0E0"));
        tv.setTextSize(18);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(48, 48, 48, 48);
        loadingText = tv;
        ll.addView(tv);
        return ll;
    }

    private void showLoading(String msg) {
        if (loadingView != null) {
            loadingText.setText(msg);
            loadingView.setVisibility(View.VISIBLE);
            handler.removeCallbacks(loadTimeoutRunnable);
            handler.postDelayed(loadTimeoutRunnable, LOAD_TIMEOUT_MS);
        }
    }

    private void showError(String msg) {
        if (loadingView != null) {
            loadingText.setText(msg + "\n\n（点击屏幕重试）");
            loadingText.setClickable(true);
            loadingText.setOnClickListener(v -> {
                showLoading("正在重新加载…");
                loadHome();
            });
            loadingView.setVisibility(View.VISIBLE);
        }
    }

    private void hideLoading() {
        if (loadingView != null) {
            loadingView.setVisibility(View.GONE);
            handler.removeCallbacks(loadTimeoutRunnable);
        }
    }

    private void applyKeepAwake() {
        if (cfg.isKeepAwake()) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
    }

    private void scheduleReload() {
        if (reloadRunnable != null) {
            handler.removeCallbacks(reloadRunnable);
            reloadRunnable = null;
        }
        final int mins = cfg.getReloadMins();
        if (mins <= 0) return;
        reloadRunnable = new Runnable() {
            @Override
            public void run() {
                if (webView != null) webView.reload();
                handler.postDelayed(this, mins * 60_000L);
            }
        };
        handler.postDelayed(reloadRunnable, mins * 60_000L);
    }

    // ---------- 全屏沉浸 ----------

    // 状态栏巡检：每 300ms 主动检查一次系统栏是否被滑出，展开就立刻收回。
    // 被动监听在 ZUI 上不可靠（部分机型不回调），主动轮询保证收得回去。
    private static final long BAR_POLL_MS = 300;
    private final Runnable barPollRunnable = this::pollBars;

    @SuppressWarnings("deprecation")
    private void pollBars() {
        if (cfg != null && cfg.isKioskEnabled() && !safeMode) {
            int vis = getWindow().getDecorView().getSystemUiVisibility();
            boolean navVisible = (vis & View.SYSTEM_UI_FLAG_HIDE_NAVIGATION) == 0;
            boolean statusVisible = (vis & View.SYSTEM_UI_FLAG_FULLSCREEN) == 0;
            boolean statusRelevant = cfg.isHideStatusbar();
            if (navVisible || (statusRelevant && statusVisible)) {
                collapseNotificationPanels();
                enterImmersive();
            }
        }
        if (!isDestroyed() && !isFinishing()) {
            handler.postDelayed(barPollRunnable, BAR_POLL_MS);
        }
    }

    /**
     * 检测到状态栏/导航栏被滑出（如工人下拉通知栏）时，
     * 立刻收起通知面板并重新进入沉浸式 —— 效果：刚滑下来就弹回去。
     * 与悬浮挡板（拦手势）叠加：挡板拦得住最好，拦不住也能秒收回。
     */
    @SuppressWarnings("deprecation")
    private void startBarsWatcher() {
        final View decor = getWindow().getDecorView();
        decor.setOnSystemUiVisibilityChangeListener(vis -> {
            boolean navVisible = (vis & View.SYSTEM_UI_FLAG_HIDE_NAVIGATION) == 0;
            boolean statusVisible = (vis & View.SYSTEM_UI_FLAG_FULLSCREEN) == 0;
            if ((navVisible || statusVisible) && !safeMode) {
                handler.postDelayed(() -> {
                    collapseNotificationPanels();
                    if (cfg.isKioskEnabled()) enterImmersive();
                }, 80);
            }
        });
    }

    /** 通过反射调用系统的「收起通知栏面板」（公开 API 里没有） */
    private void collapseNotificationPanels() {
        try {
            Object sb = getSystemService("statusbar");
            sb.getClass().getMethod("collapsePanels").invoke(sb);
        } catch (Throwable ignored) {
            // 部分系统禁用了此接口：静默降级，至少重新进入沉浸式把栏藏回去
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus && !safeMode && cfg.isKioskEnabled()) enterImmersive();
    }

    @SuppressWarnings("deprecation")
    private void enterImmersive() {
        View decor = getWindow().getDecorView();
        int flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY;
        if (cfg.isHideStatusbar()) {
            // 细粒度开关：隐藏状态栏（下滑不出通知栏）
            flags |= View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_FULLSCREEN;
        }
        decor.setSystemUiVisibility(flags);
    }

    @SuppressWarnings("deprecation")
    private void exitImmersive() {
        View decor = getWindow().getDecorView();
        decor.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }

    // ---------- 按键拦截 ----------

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (safeMode || !cfg.isKioskEnabled()) {
            return super.onKeyDown(keyCode, event);
        }
        if (isBlockedKey(keyCode)) return true;
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (safeMode || !cfg.isKioskEnabled()) {
            return super.onKeyUp(keyCode, event);
        }
        if (isBlockedKey(keyCode)) return true;
        return super.onKeyUp(keyCode, event);
    }

    /**
     * 屏蔽清单：音量键（可配置）、菜单键、相机键、搜索键。
     * BACK / HOME / APP_SWITCH 不在此列：
     *  - BACK 走 onBackPressed（网页内后退，见下）
     *  - HOME / APP_SWITCH 靠「默认桌面接管」约束
     */
    private boolean isBlockedKey(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_VOLUME_UP:
            case KeyEvent.KEYCODE_VOLUME_DOWN:
            case KeyEvent.KEYCODE_VOLUME_MUTE:
                return cfg.isBlockVolume(); // 细粒度开关：设置里可放开音量键
            case KeyEvent.KEYCODE_MENU:
            case KeyEvent.KEYCODE_CAMERA:
            case KeyEvent.KEYCODE_SEARCH:
                return true;
            default:
                return false;
        }
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        if (safeMode || !cfg.isKioskEnabled()) {
            super.onBackPressed();
            return;
        }
        // Kiosk 模式：优先让网页后退一页；已到首页则停留，不退出 App
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
            return;
        }
        // 已在首页 —— 什么都不做，留在生产系统
    }

    // ---------- 手势 ----------

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        if (safeMode) {
            return super.dispatchTouchEvent(ev);
        }

        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = ev.getX();
                downY = ev.getY();
                downTime = System.currentTimeMillis();
                // 边缘手势拦截仅在锁定开启时生效
                gestureFromBlockedEdge = cfg.isKioskEnabled() && isEdgeSwipeStart(ev.getX(), ev.getY());
                if (gestureFromBlockedEdge) {
                    return true; // 起点在边缘封锁区：整条手势吞掉（拦左滑返回 + 顶部下拉通知栏）
                }
                break;

            case MotionEvent.ACTION_MOVE:
                if (gestureFromBlockedEdge) return true;
                if (cfg.isKioskEnabled() && isRealEdgeSwipe(ev)) return true;
                break;

            case MotionEvent.ACTION_UP:
                if (gestureFromBlockedEdge) {
                    gestureFromBlockedEdge = false;
                    return true;
                }
                if (cfg.isKioskEnabled() && isRealEdgeSwipe(ev)) return true;
                float dx = Math.abs(ev.getX() - downX);
                float dy = Math.abs(ev.getY() - downY);
                long dt = System.currentTimeMillis() - downTime;
                if (dx < touchSlop && dy < touchSlop && dt < 800) {
                    // 管理手势入口任何时候有效（锁定前免密，锁定后需 PIN）
                    handleTap(ev.getX(), ev.getY());
                }
                break;

            case MotionEvent.ACTION_CANCEL:
                if (gestureFromBlockedEdge) {
                    gestureFromBlockedEdge = false;
                    return true;
                }
                if (cfg.isKioskEnabled() && isEdgeSwipeStart(downX, downY)) return true;
                break;
        }
        return super.dispatchTouchEvent(ev);
    }

    /** 起点（含顶部边缘）是否落在封锁区 */
    private boolean isEdgeSwipeStart(float x, float y) {
        float edge = EDGE_SWIPE_DP * getResources().getDisplayMetrics().density;
        float top = TOP_EDGE_DP * getResources().getDisplayMetrics().density;
        if (y < top) return true;                    // 顶部：拦下拉通知栏
        if (x < edge) return true;                   // 左缘：拦侧滑返回
        if (BLOCK_RIGHT_EDGE && x > root.getWidth() - edge) return true;
        return false;
    }

    private boolean isRealEdgeSwipe(MotionEvent ev) {
        if (!isEdgeSwipeStart(downX, downY)) return false;
        float dx = Math.abs(ev.getX() - downX);
        float dy = Math.abs(ev.getY() - downY);
        return dx > touchSlop * 2 && dx > dy;
    }

    /**
     * 管理手势：左上角双击 → 右下角双击。
     * 工人在网页中间区域的正常点按永远不会触发；
     * 角落区域（100dp 见方）生产系统页面一般没有可点元素，误触率最低。
     */
    private void handleTap(float x, float y) {
        long now = System.currentTimeMillis();
        int w = root.getWidth();
        int h = root.getHeight();
        float px = CORNER_SIZE_DP * getResources().getDisplayMetrics().density;

        boolean inTopLeft = x < px && y < px;
        boolean inBottomRight = x > w - px && y > h - px;

        if (inTopLeft) {
            if (now - tlTap1 < DOUBLE_TAP_MS) {
                tlTap2 = now; // 左上双击成立
            } else {
                tlTap1 = now;
                tlTap2 = 0;
            }
            brTap1 = 0;
            return;
        }

        if (inBottomRight) {
            // 必须在左上双击成立后的时间窗内
            if (tlTap2 > 0 && now - tlTap2 < SEQ_WINDOW_MS) {
                if (brTap1 > 0 && now - brTap1 < DOUBLE_TAP_MS) {
                    tlTap1 = tlTap2 = brTap1 = 0;
                    askPin();
                } else {
                    brTap1 = now;
                }
            } else {
                tlTap1 = tlTap2 = brTap1 = 0;
            }
        }
    }

    // ---------- PIN 校验 ----------

    private void askPin() {
        askPinThen(this::openSettings);
    }

    /**
     * PIN 弹窗
     *
     * v1.1.0 两处增强：
     *  1. 免密配置区：Kiosk 锁定尚未开启时（首次部署/调试阶段），
     *     直接放行进设置，不再要求 PIN —— 首次配置不用先猜默认密码。
     *  2. 防忘密码：PIN 弹窗提供「忘记密码？用锁屏密码」入口，
     *     调起系统锁屏验证（KeyguardManager），通过后视同管理员。
     *     受设置里的「允许锁屏密码兜底」开关控制。
     */
    private void askPinThen(final Runnable onSuccess) {
        // 免密配置区：锁定未开启时无需密码即可进设置
        if (!cfg.isKioskEnabled() && !safeMode) {
            onSuccess.run();
            return;
        }

        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        input.setHint("请输入管理员密码");
        input.setGravity(Gravity.CENTER);
        input.setTextSize(22);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (24 * getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad / 2, pad, 0);
        box.addView(input);

        AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setTitle("管理员验证")
                .setView(box)
                .setCancelable(true)
                .setPositiveButton("确定", null)
                .setNegativeButton("取消", null);

        // 忘记密码兜底：用平板系统锁屏密码验证（需平板已设锁屏密码）
        final boolean allowCred = cfg.isAllowDeviceCred();
        if (allowCred) {
            builder.setNeutralButton("忘记密码？用锁屏密码", null);
        }

        final AlertDialog dlg = builder.create();

        dlg.setOnShowListener(d -> {
            // 修复：全屏/沉浸式状态下系统经常不主动弹键盘，
            // 弹窗一显示就强制把焦点给输入框并请求输入法
            input.requestFocus();
            if (dlg.getWindow() != null) {
                dlg.getWindow().setSoftInputMode(
                        WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
            }
            dlg.getButton(AlertDialog.BUTTON_POSITIVE)
                    .setOnClickListener(v -> {
                        String pin = input.getText().toString().trim();
                        if (cfg.checkPin(pin)) {
                            dlg.dismiss();
                            onSuccess.run();
                        } else {
                            input.setText("");
                            Toast.makeText(MainActivity.this, "密码错误", Toast.LENGTH_SHORT).show();
                        }
                    });
            if (allowCred) {
                dlg.getButton(AlertDialog.BUTTON_NEUTRAL)
                        .setOnClickListener(v -> {
                            pendingAfterCredential = onSuccess;
                            dlg.dismiss();
                            requestDeviceCredential();
                        });
            }
        });

        dlg.show();
    }

    // ---------- 系统锁屏密码兜底验证 ----------

    private static final int REQ_DEVICE_CRED = 1002;
    private Runnable pendingAfterCredential;

    /** 调起系统锁屏密码/指纹/人脸验证，通过则视为管理员（防止忘记 PIN 被锁死） */
    private void requestDeviceCredential() {
        try {
            android.app.KeyguardManager km =
                    (android.app.KeyguardManager) getSystemService(KEYGUARD_SERVICE);
            if (km == null || !km.isKeyguardSecure()) {
                Toast.makeText(this,
                        "本平板未设置系统锁屏密码，无法使用该功能\n请先在系统设置中配置锁屏密码",
                        Toast.LENGTH_LONG).show();
                return;
            }
            Intent i = km.createConfirmDeviceCredentialIntent(
                    "管理员验证", "输入平板锁屏密码以继续（管理员备用通道）");
            if (i == null) {
                Toast.makeText(this, "系统不支持锁屏验证", Toast.LENGTH_LONG).show();
                return;
            }
            startActivityForResult(i, REQ_DEVICE_CRED);
        } catch (Exception e) {
            Toast.makeText(this, "无法调起锁屏验证：" + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void openSettings() {
        Intent i = new Intent(this, SettingsActivity.class);
        startActivityForResult(i, 1001);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        // 系统锁屏密码兜底验证结果
        if (requestCode == REQ_DEVICE_CRED) {
            if (resultCode == RESULT_OK && pendingAfterCredential != null) {
                Runnable r = pendingAfterCredential;
                pendingAfterCredential = null;
                Toast.makeText(this, "锁屏验证通过，已作为管理员放行", Toast.LENGTH_SHORT).show();
                r.run();
            } else {
                pendingAfterCredential = null;
                Toast.makeText(this, "验证已取消或失败", Toast.LENGTH_SHORT).show();
            }
            return;
        }

        if (requestCode != 1001) return;

        cfg = new KioskConfig(this);
        applyKeepAwake();
        applyOrientation();
        applyStatusBarGuard();
        if (webView != null) {
            webView.getSettings().setTextZoom(cfg.getZoom());
        }
        String target = cfg.getUrl();
        String current = webView == null ? null : webView.getUrl();
        if (!TextUtils.equals(target, current)) {
            showLoading("正在加载生产系统…");
            loadHome();
        }
        scheduleReload();

        if (cfg.isKioskEnabled()) {
            enterImmersive();
        } else {
            exitImmersive();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 修复：onPause 里 webView.onPause() 后，回来必须 onResume()，
        // 否则页面显示着但整个网页暂停、点不动（切后台再回来必现）
        if (webView != null) webView.onResume();
        applyStatusBarGuard(); // 权限可能刚在系统设置里授予，回来时重试挂挡板
        if (!safeMode && cfg.isKioskEnabled()) {
            enterImmersive();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (webView != null) webView.onPause();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(barPollRunnable);
        if (statusBarGuard != null) {
            try {
                ((WindowManager) getSystemService(WINDOW_SERVICE)).removeView(statusBarGuard);
            } catch (Exception ignored) {
            }
            statusBarGuard = null;
        }
        if (reloadRunnable != null) handler.removeCallbacks(reloadRunnable);
        if (webView != null) {
            try {
                if (root != null) root.removeView(webView);
                webView.stopLoading();
                webView.setWebChromeClient(null);
                webView.setWebViewClient(null);
                webView.destroy();
            } catch (Exception ignored) {
            }
            webView = null;
        }
        super.onDestroy();
    }
}
