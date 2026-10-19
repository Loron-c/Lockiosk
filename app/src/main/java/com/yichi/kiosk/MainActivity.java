package com.yichi.kiosk;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.graphics.Color;
import android.net.Uri;
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
    private static final long DOUBLE_TAP_MS = 700;
    private static final long SEQ_WINDOW_MS = 4000;
    private static final int CORNER_SIZE_DP = 100;

    // 触摸判定（区分点击与滑动）
    private float downX, downY;
    private long downTime;
    private int touchSlop;

    // 侧滑返回拦截：默认只拦左边缘（ZUI 返回手势方向）
    private static final boolean BLOCK_RIGHT_EDGE = false;
    private static final int EDGE_SWIPE_DP = 24;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private Runnable reloadRunnable;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        cfg = new KioskConfig(this);

        applyOrientation();

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
                        Toast.makeText(MainActivity.this,
                                "已拦截跳转：" + host, Toast.LENGTH_SHORT).show();
                        loadHome();
                    });
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
        if (loadingView != null) loadingView.setVisibility(View.GONE);
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
                if (cfg.isKioskEnabled() && isEdgeSwipeStart(ev.getX())) {
                    return true;
                }
                break;

            case MotionEvent.ACTION_MOVE:
                if (cfg.isKioskEnabled() && isRealEdgeSwipe(ev)) return true;
                break;

            case MotionEvent.ACTION_UP:
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
                if (cfg.isKioskEnabled() && isEdgeSwipeStart(downX)) return true;
                break;
        }
        return super.dispatchTouchEvent(ev);
    }

    private boolean isEdgeSwipeStart(float x) {
        float edge = EDGE_SWIPE_DP * getResources().getDisplayMetrics().density;
        if (x < edge) return true;
        if (BLOCK_RIGHT_EDGE && x > root.getWidth() - edge) return true;
        return false;
    }

    private boolean isRealEdgeSwipe(MotionEvent ev) {
        if (!isEdgeSwipeStart(downX)) return false;
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
