package com.yichi.kiosk;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.ArrayAdapter;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * 设置界面
 *
 * 修复记录 v1.0.1：
 *  - 移除「临时退出 Kiosk」按钮（工人点一下就能逃出锁定，严重漏洞）。
 *    改为「取消 Kiosk 锁定」需二次输入 PIN 确认。
 *  - 导出/导入改用 SAF 文件选择器（ACTION_CREATE_DOCUMENT / ACTION_OPEN_DOCUMENT），
 *    绕开 Android 10+ 分区存储限制，不再依赖 WRITE_EXTERNAL_STORAGE。
 *  - 同时保留「导出到应用私有目录」作为兜底路径。
 *  - 保存时校验 URL 合法性，避免空地址导致白屏。
 */
public class SettingsActivity extends Activity {

    private static final int REQ_EXPORT = 2001;
    private static final int REQ_IMPORT = 2002;

    /** 供 MainActivity 判断：本设置页是否正开着（开着时不触发「自动回前台」） */
    private static boolean open = false;

    public static boolean isOpen() {
        return open;
    }

    private KioskConfig cfg;

    private EditText etUrl;
    private EditText etTitle;
    private EditText etReload;
    private EditText etZoom;
    private CheckBox cbKiosk;
    private CheckBox cbDomainLock;
    private CheckBox cbKeepAwake;
    private CheckBox cbAutoStart;
    private CheckBox cbBlockVolume;
    private CheckBox cbHideStatusbar;
    private CheckBox cbDeviceCred;
    private TextView tvHomeStatus;
    private Spinner spOrientation;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        open = true;
        cfg = new KioskConfig(this);

        ScrollView sv = new ScrollView(this);
        LinearLayout ll = new LinearLayout(this);
        ll.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        ll.setPadding(pad, pad, pad, pad);
        sv.addView(ll);

        ll.addView(sectionTitle("生产系统"));
        etTitle = addInput(ll, "系统名称（显示在标题栏）", cfg.getTitle(),
                InputType.TYPE_CLASS_TEXT);
        etUrl = addInput(ll, "系统网址 URL（生产系统为 https；裸地址默认自动补 https://）",
                cfg.getUrl(),
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);

        ll.addView(sectionTitle("锁定设置"));
        cbKiosk = addCheck(ll, "启用 Kiosk 锁定（屏蔽音量键 / 隐藏状态栏 / 限制外部网站）",
                cfg.isKioskEnabled());
        cbDomainLock = addCheck(ll,
                "锁定访问域名（只允许生产系统域名，点外部链接自动拦截）\n建议保持开启",
                cfg.isDomainLock());
        cbKeepAwake = addCheck(ll, "保持屏幕常亮（不自动息屏）", cfg.isKeepAwake());
        cbAutoStart = addCheck(ll, "开机自动启动", cfg.isAutoStart());

        ll.addView(sectionTitle("细粒度锁定项（仅在 Kiosk 锁定开启后生效）"));
        cbBlockVolume = addCheck(ll, "屏蔽音量键（防止工人调音量/误触截图组合键）", cfg.isBlockVolume());
        cbHideStatusbar = addCheck(ll,
                "隐藏状态栏（下滑不出通知栏）\n注意：通知内容本身属于各应用，App 无法禁用其他应用的通知，"
                + "如需彻底干净请在系统通知管理里逐个关闭",
                cfg.isHideStatusbar());
        cbDeviceCred = addCheck(ll,
                "允许用「平板锁屏密码」兜底验证（防止忘记管理员密码被锁死）\n"
                + "需平板已在系统中设置锁屏密码；PIN 弹窗将出现「忘记密码」入口",
                cfg.isAllowDeviceCred());

        Button btnOverlay = addButton(ll, "开启「通知栏拦截」权限（每台平板首次需点一次）");
        btnOverlay.setOnClickListener(v -> {
            if (Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "权限已开启，通知栏拦截生效中 ✓", Toast.LENGTH_SHORT).show();
            } else {
                try {
                    Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:" + getPackageName()));
                    startActivity(i);
                    Toast.makeText(this, "请在新页面中允许「车间生产系统」显示在其他应用上层",
                            Toast.LENGTH_LONG).show();
                } catch (Exception e) {
                    Toast.makeText(this, "无法打开权限页面：" + e.getMessage(),
                            Toast.LENGTH_LONG).show();
                }
            }
        });

        ll.addView(sectionTitle("退出方式（管理员）"));
        TextView gestureTip = new TextView(this);
        gestureTip.setText("进入设置手势（固定）：左上角双击 → 右下角双击（4 秒内完成）\n"
                + "工人在网页中间区域的正常点按不会触发，角落区域一般没有可点元素，误触率最低");
        gestureTip.setTextSize(13);
        gestureTip.setPadding(0, dp(4), 0, dp(8));
        ll.addView(gestureTip);

        Button btnPin = addButton(ll, "修改管理员密码");
        btnPin.setOnClickListener(v -> showChangePinDialog());

        ll.addView(sectionTitle("显示与刷新"));

        TextView oriLabel = new TextView(this);
        oriLabel.setText("屏幕方向（防止工人旋转平板导致页面排版乱）");
        oriLabel.setTextSize(13);
        oriLabel.setPadding(0, dp(8), 0, dp(4));
        ll.addView(oriLabel);

        spOrientation = new Spinner(this);
        ArrayAdapter<String> oriAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item,
                new String[]{"跟随系统（可旋转）", "固定横屏（推荐车间使用）", "固定竖屏"});
        oriAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spOrientation.setAdapter(oriAdapter);
        spOrientation.setSelection(cfg.getOrientation());
        ll.addView(spOrientation);

        etZoom = addInput(ll, "网页缩放百分比（50–200，默认 100）",
                String.valueOf(cfg.getZoom()), InputType.TYPE_CLASS_NUMBER);
        etReload = addInput(ll, "自动刷新间隔（分钟，0 = 不刷新；建议设 30–60 防内存累积）",
                String.valueOf(cfg.getReloadMins()), InputType.TYPE_CLASS_NUMBER);

        ll.addView(sectionTitle("批量部署（16 台）"));
        Button btnExport = addButton(ll, "导出配置文件（选择保存位置）");
        btnExport.setOnClickListener(v -> doExportToPicker());

        Button btnImport = addButton(ll, "导入配置文件（选择文件）");
        btnImport.setOnClickListener(v -> doImportFromPicker());

        ll.addView(sectionTitle("桌面接管（推荐，彻底锁住 Home 键）"));
        tvHomeStatus = new TextView(this);
        tvHomeStatus.setTextSize(14);
        tvHomeStatus.setPadding(0, dp(4), 0, dp(8));
        ll.addView(tvHomeStatus);
        refreshHomeStatus();
        Button btnHome = addButton(ll, "设为默认桌面（点击弹出系统选择框）");
        btnHome.setOnClickListener(v -> showHomePicker());
        TextView homeTip = new TextView(this);
        homeTip.setText("说明：设为默认桌面后，工人按 Home 键只会回到本应用，"
                + "无法回到系统桌面，也打不开其他应用 —— 这是替代「屏蔽 Home 键」"
                + "的标准做法（Fully Kiosk 同款机制）。\n"
                + "开机也会自动进入本应用，无需依赖自启动。\n"
                + "恢复方法：设置 → 应用 → 默认应用 → 主屏幕应用 改回原桌面。");
        homeTip.setTextSize(12);
        homeTip.setPadding(0, dp(8), 0, dp(8));
        ll.addView(homeTip);

        ll.addView(sectionTitle("系统级加固（ZUI 特有问题的最终兜底）"));
        TextView hardenTip = new TextView(this);
        hardenTip.setText("开启「屏幕固定」后：下拉通知栏、返回键、多任务全部失效，"
                + "ZUI 上一些 App 拦不住的手势由系统直接锁死（无需连电脑）。\n"
                + "操作：点下方按钮 → 在打开的「安全」设置页里找「屏幕固定/App 固定」→ 打开开关。\n"
                + "启用：多任务界面长按本应用 → 选固定；取消：长按返回键。重启平板后需重新固定。");
        hardenTip.setTextSize(13);
        hardenTip.setPadding(0, dp(4), 0, dp(8));
        ll.addView(hardenTip);
        Button btnPinning = addButton(ll, "打开「屏幕固定」设置（系统安全设置页）");        btnPinning.setOnClickListener(v -> {
            // 逐级兜底：安全设置 → 应用管理
            String[] actions = {
                    "android.settings.SECURITY_SETTINGS",
                    "android.settings.APPLICATION_SETTINGS"
            };
            for (String a : actions) {
                try {
                    Intent i = new Intent(a);
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(i);
                    Toast.makeText(this, "在安全设置里找「屏幕固定 / App 固定」并开启",
                            Toast.LENGTH_LONG).show();
                    return;
                } catch (Exception ignored) {
                }
            }
            Toast.makeText(this, "无法打开，请手动到 设置 → 安全 找「屏幕固定」",
                    Toast.LENGTH_LONG).show();
        });

        ll.addView(sectionTitle("通知面板自动收回（ZUI 必需，推荐开启）"));
        TextView a11yTip = new TextView(this);
        a11yTip.setText("ZUI 的通知栏面板只能由无障碍服务感知并代按返回键关闭（滑出来立刻缩回，"
                + "与 Fully Kiosk 同款原理）。仅监听系统弹窗事件，未开 Kiosk 锁定时完全不干预。\n"
                + "操作：点下方按钮 → 在列表里找到「车间生产系统」→ 打开开关。");
        a11yTip.setTextSize(13);
        a11yTip.setPadding(0, dp(4), 0, dp(8));
        ll.addView(a11yTip);
        Button btnA11y = addButton(ll, "开启「通知面板自动收回」（无障碍设置页）");
        btnA11y.setOnClickListener(v -> {
            try {
                Intent i = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
                Toast.makeText(this, "在无障碍服务列表里找到「车间生产系统」并打开",
                        Toast.LENGTH_LONG).show();
            } catch (Exception e) {
                Toast.makeText(this, "无法打开，请手动到 设置 → 无障碍 开启本应用的服务",
                        Toast.LENGTH_LONG).show();
            }
        });

        ll.addView(sectionTitle("防卸载（可选）"));
        Button btnAdmin = addButton(ll, "激活设备管理器（激活后无法常规卸载）");
        btnAdmin.setOnClickListener(v -> toggleDeviceAdmin());

        ll.addView(sectionTitle("操作"));
        Button btnSave = addButton(ll, "保存并返回");
        btnSave.setOnClickListener(v -> saveAndFinish());

        Button btnUnlock = addButton(ll, "取消 Kiosk 锁定（需再次验证密码）");
        btnUnlock.setOnClickListener(v -> confirmUnlock());

        TextView tip = new TextView(this);
        tip.setText("\n提示：\n"
                + "• Kiosk 锁定未开启时，连点屏幕可直接免密进入设置（首次部署不用猜默认密码）\n"
                + "• 锁定开启后，所有设置保存后立即生效\n"
                + "• 密码以 SHA-256 哈希存储，界面永不显示明文\n"
                + "• 忘记密码：PIN 弹窗点「忘记密码？用锁屏密码」，输平板锁屏密码即可进入\n"
                + "• 若平板也没设锁屏密码，只能重装 APK 恢复默认密码 " + KioskConfig.DEFAULT_PIN + "\n"
                + "• 建议开启「桌面接管」+ 激活设备管理器 + 路由器白名单，三件套齐后基本无死角\n");
        tip.setTextSize(13);
        tip.setPadding(0, dp(16), 0, dp(16));
        ll.addView(tip);

        setContentView(sv);
    }

    @Override
    protected void onDestroy() {
        open = false;
        super.onDestroy();
    }

    // ---------- UI 工具 ----------

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    private TextView sectionTitle(String t) {
        TextView tv = new TextView(this);
        tv.setText(t);
        tv.setTextSize(18);
        tv.setPadding(0, dp(20), 0, dp(8));
        return tv;
    }

    private EditText addInput(LinearLayout parent, String hint, String value, int inputType) {
        TextView label = new TextView(this);
        label.setText(hint);
        label.setTextSize(13);
        label.setPadding(0, dp(8), 0, dp(4));
        parent.addView(label);

        EditText et = new EditText(this);
        et.setInputType(inputType);
        et.setText(value);
        et.setTextSize(15);
        parent.addView(et);
        return et;
    }

    private CheckBox addCheck(LinearLayout parent, String text, boolean checked) {
        CheckBox cb = new CheckBox(this);
        cb.setText(text);
        cb.setTextSize(14);
        cb.setChecked(checked);
        cb.setPadding(0, dp(8), 0, dp(8));
        parent.addView(cb);
        return cb;
    }

    private Button addButton(LinearLayout parent, String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(6);
        b.setLayoutParams(lp);
        parent.addView(b);
        return b;
    }

    // ---------- 密码 ----------

    /** 弹窗显示后强制弹出输入法（修复全屏状态下键盘不弹） */
    private void forceShowKeyboard(android.app.Dialog dlg, EditText et) {
        dlg.setOnShowListener(d -> {
            et.requestFocus();
            if (dlg.getWindow() != null) {
                dlg.getWindow().setSoftInputMode(
                        android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
            }
        });
    }

    private void showChangePinDialog() {
        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        input.setHint("输入新密码（建议 6 位数字）");
        input.setGravity(Gravity.CENTER);
        input.setTextSize(20);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(24), dp(12), dp(24), 0);
        box.addView(input);

        android.app.AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle("修改管理员密码")
                .setView(box)
                .setPositiveButton("确定", (d, w) -> {
                    String p = input.getText().toString().trim();
                    if (p.length() < 4) {
                        Toast.makeText(this, "密码至少 4 位", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    cfg.setPin(p);
                    Toast.makeText(this, "密码已更新", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("取消", null)
                .show();
        forceShowKeyboard(dlg, input);
    }

    /**
     * 取消 Kiosk 锁定
     *
     * 修复：原实现点「确认取消锁定」后，如果用户接着点「保存并返回」，
     * collectFromUi() 会读 cbKiosk 状态写回 —— 由于已同步 setChecked(false)，
     * 结果虽然是关的，但界面文案说「可在本页重新勾选恢复」极易误导，
     * 一旦用户忘了就以为已经恢复、或误勾回锁定导致自我锁死。
     * 现在改为：确认后弹二次提示，并直接保存退出，避免状态歧义。
     */
    private void confirmUnlock() {
        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        input.setHint("输入管理员密码以确认");
        input.setGravity(Gravity.CENTER);
        input.setTextSize(20);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(24), dp(12), dp(24), 0);
        box.addView(input);

        android.app.AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle("取消 Kiosk 锁定")
                .setMessage("取消后平板将恢复正常使用：\n"
                        + "• 工人可按 Home 回桌面、可打开其他应用\n"
                        + "• 网页可自由跳转到外部网站\n"
                        + (isDefaultHome()
                        ? "\n本应用当前是默认桌面，确认后将自动改回系统桌面。\n"
                        : "")
                        + "\n确认继续请输入管理员密码。")
                .setView(box)
                .setPositiveButton("确认取消锁定", (d, w) -> {
                    String p = input.getText().toString().trim();
                    if (!cfg.checkPin(p)) {
                        Toast.makeText(this, "密码错误，未做任何更改", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    // 先收集其余界面配置，再强制关闭锁定，避免后续保存把它改回来
                    collectFromUi();
                    cfg.setKioskEnabled(false);
                    // 取消锁定时自动把默认桌面恢复给系统桌面：
                    // 清除本应用被用户设成的「首选默认」（App 有权清除自己的默认设置）。
                    // 注意：部分 ROM（含部分 ZUI 版本）把默认桌面存在系统私有设置里，
                    // 此调用会静默无效 —— 提示语里兜底引导手动操作。
                    try {
                        getPackageManager().clearPackagePreferredActivities(getPackageName());
                    } catch (Exception ignored) {
                    }
                    Toast.makeText(this,
                            "已取消锁定，平板恢复正常使用\n"
                                    + "若按 Home 仍回到本应用，请手动到：\n"
                                    + "设置 → 应用 → 默认应用 → 主屏幕应用\n"
                                    + "改回 ZUI 桌面",
                            Toast.LENGTH_LONG).show();
                    setResult(RESULT_OK);
                    finish();
                })
                .setNegativeButton("返回", null)
                .show();
        forceShowKeyboard(dlg, input);
    }

    // ---------- 桌面接管（对齐 Fully Kiosk 的 Launcher 机制） ----------

    /**
     * 本应用当前是否是系统默认桌面（Launcher）。
     * 原理：解析 ACTION_MAIN + CATEGORY_HOME 的默认处理器，比对包名。
     */
    private boolean isDefaultHome() {
        try {
            Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
            ResolveInfo ri = getPackageManager().resolveActivity(
                    home, PackageManager.MATCH_DEFAULT_ONLY);
            return ri != null && ri.activityInfo != null
                    && getPackageName().equals(ri.activityInfo.packageName);
        } catch (Exception e) {
            return false;
        }
    }

    /** 刷新「桌面接管」状态显示 */
    private void refreshHomeStatus() {
        if (tvHomeStatus == null) return;
        if (isDefaultHome()) {
            tvHomeStatus.setText("● 当前状态：已是默认桌面 —— Home 键已被锁住 ✓");
            tvHomeStatus.setTextColor(0xFF4CAF50);
        } else {
            tvHomeStatus.setText("○ 当前状态：未设为默认桌面 —— 工人按 Home 键仍可回到系统桌面");
            tvHomeStatus.setTextColor(0xFFFF9800);
        }
    }

    /**
     * 打开系统「主屏幕应用」设置页，用户手动把默认桌面切到本应用。
     *
     * 修复：原实现是发 ACTION_MAIN+CATEGORY_HOME 的 Intent 期望系统弹选择框，
     * 但在 ZUI 等国产 ROM 上该 Intent 只会直接回到当前桌面，不弹框。
     * ACTION_HOME_SETTINGS（API 21+）是所有 ROM 都支持的官方入口。
     */
    private void showHomePicker() {
        // 逐级兜底：主屏幕设置 → 默认应用设置 → 系统设置首页
        Intent[] candidates = new Intent[]{
                new Intent("android.settings.HOME_SETTINGS"),
                new Intent("android.settings.MANAGE_DEFAULT_APPS_SETTINGS"),
                new Intent(android.provider.Settings.ACTION_SETTINGS)
        };
        for (Intent i : candidates) {
            try {
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
                Toast.makeText(this,
                        "在打开的页面里选「主屏幕应用/桌面」→ 选「车间生产系统」",
                        Toast.LENGTH_LONG).show();
                new android.os.Handler(android.os.Looper.getMainLooper())
                        .postDelayed(this::refreshHomeStatus, 800);
                return;
            } catch (Exception ignored) {
            }
        }
        Toast.makeText(this,
                "无法打开系统设置，请手动到：\n设置 → 应用 → 默认应用 → 主屏幕应用\n选择本应用",
                Toast.LENGTH_LONG).show();
    }

    // ---------- 保存 ----------

    private boolean collectFromUi() {
        String url = etUrl.getText().toString().trim();
        if (TextUtils.isEmpty(url)) {
            Toast.makeText(this, "系统网址不能为空", Toast.LENGTH_LONG).show();
            return false;
        }
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            // 生产系统为 https，裸地址默认补 https://（需要 http 请手动写全）
            url = "https://" + url;
        }
        cfg.setUrl(url);
        cfg.setTitle(etTitle.getText().toString().trim());
        cfg.setKioskEnabled(cbKiosk.isChecked());
        cfg.setDomainLock(cbDomainLock.isChecked());
        cfg.setKeepAwake(cbKeepAwake.isChecked());
        cfg.setAutoStart(cbAutoStart.isChecked());
        cfg.setBlockVolume(cbBlockVolume.isChecked());
        cfg.setHideStatusbar(cbHideStatusbar.isChecked());
        cfg.setAllowDeviceCred(cbDeviceCred.isChecked());
        if (spOrientation != null) {
            cfg.setOrientation(spOrientation.getSelectedItemPosition());
        }

        try {
            cfg.setZoom(Integer.parseInt(etZoom.getText().toString().trim()));
        } catch (Exception ignored) {
        }
        try {
            cfg.setReloadMins(Integer.parseInt(etReload.getText().toString().trim()));
        } catch (Exception ignored) {
        }
        return true;
    }

    private void saveAndFinish() {
        if (!collectFromUi()) return;

        // 对齐 Fully Kiosk：开启锁定但未接管桌面时，主动引导设置默认桌面
        if (cbKiosk.isChecked() && !isDefaultHome()) {
            new AlertDialog.Builder(this)
                    .setTitle("建议：设为默认桌面")
                    .setMessage("检测到 Kiosk 锁定已开启，但本应用还不是默认桌面。\n\n"
                            + "不设置的话，工人按 Home 键可以回到系统桌面、打开其他应用。\n\n"
                            + "现在设置吗？（会跳到系统「主屏幕应用」设置页，选「车间生产系统」即可）")
                    .setPositiveButton("现在设置", (d, w) -> {
                        showHomePicker();
                        finishAfterSave();
                    })
                    .setNegativeButton("暂不", (d, w) -> finishAfterSave())
                    .setCancelable(false)
                    .show();
            return;
        }
        finishAfterSave();
    }

    private void finishAfterSave() {
        Toast.makeText(this, "配置已保存", Toast.LENGTH_SHORT).show();
        setResult(RESULT_OK);
        finish();
    }

    // ---------- 导出 / 导入（SAF，绕开分区存储限制） ----------

    private void doExportToPicker() {
        if (!collectFromUi()) return;
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("text/plain");
        i.putExtra(Intent.EXTRA_TITLE, "kiosk-config.properties");
        try {
            startActivityForResult(i, REQ_EXPORT);
        } catch (Exception e) {
            // 系统无文件选择器时，回退到应用私有目录
            exportToPrivateDir();
        }
    }

    private void doImportFromPicker() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        try {
            startActivityForResult(i, REQ_IMPORT);
        } catch (Exception e) {
            Toast.makeText(this, "无法打开文件选择器", Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;

        Uri uri = data.getData();
        if (requestCode == REQ_EXPORT) {
            try {
                File tmp = new File(getCacheDir(), "kiosk-config.properties");
                if (!cfg.exportTo(tmp)) {
                    Toast.makeText(this, "生成配置失败", Toast.LENGTH_LONG).show();
                    return;
                }
                OutputStream os = getContentResolver().openOutputStream(uri);
                if (os == null) {
                    Toast.makeText(this, "无法写入所选位置", Toast.LENGTH_LONG).show();
                    return;
                }
                InputStream is = new java.io.FileInputStream(tmp);
                byte[] buf = new byte[4096];
                int n;
                while ((n = is.read(buf)) > 0) os.write(buf, 0, n);
                is.close();
                os.flush();
                os.close();
                Toast.makeText(this, "配置已导出\n其余平板安装后选「导入配置文件」指向该文件即可",
                        Toast.LENGTH_LONG).show();
            } catch (Exception e) {
                Toast.makeText(this, "导出失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
            }
        } else if (requestCode == REQ_IMPORT) {
            try {
                InputStream is = getContentResolver().openInputStream(uri);
                if (is == null) {
                    Toast.makeText(this, "无法读取所选文件", Toast.LENGTH_LONG).show();
                    return;
                }
                File tmp = new File(getCacheDir(), "kiosk-import.properties");
                FileOutputStream fos = new FileOutputStream(tmp);
                byte[] buf = new byte[4096];
                int n;
                while ((n = is.read(buf)) > 0) fos.write(buf, 0, n);
                is.close();
                fos.close();

                if (cfg.importFrom(tmp)) {
                    Toast.makeText(this, "导入成功，正在重新加载…", Toast.LENGTH_SHORT).show();
                    setResult(RESULT_OK);
                    finish();
                } else {
                    Toast.makeText(this, "导入失败：文件格式不正确", Toast.LENGTH_LONG).show();
                }
            } catch (Exception e) {
                Toast.makeText(this, "导入失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
            }
        }
    }

    /** 兜底：导出到应用私有目录（无需任何权限） */
    private void exportToPrivateDir() {
        File f = new File(getExternalFilesDir(null), "kiosk-config.properties");
        if (f.getParentFile() != null) f.getParentFile().mkdirs();
        if (cfg.exportTo(f)) {
            Toast.makeText(this, "已导出到：\n" + f.getAbsolutePath(), Toast.LENGTH_LONG).show();
        } else {
            Toast.makeText(this, "导出失败", Toast.LENGTH_LONG).show();
        }
    }

    // ---------- 设备管理器 ----------

    private void toggleDeviceAdmin() {
        DevicePolicyManager dpm = (DevicePolicyManager) getSystemService(DEVICE_POLICY_SERVICE);
        ComponentName admin = new ComponentName(this, AdminReceiver.class);
        if (dpm == null) {
            Toast.makeText(this, "系统不支持设备管理器", Toast.LENGTH_SHORT).show();
            return;
        }
        if (dpm.isAdminActive(admin)) {
            dpm.removeActiveAdmin(admin);
            Toast.makeText(this, "已取消设备管理器", Toast.LENGTH_SHORT).show();
        } else {
            Intent i = new Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN);
            i.putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, admin);
            i.putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                    "激活后可防止本应用被车间工人卸载。");
            try {
                startActivity(i);
            } catch (Exception e) {
                Toast.makeText(this, "无法打开设备管理器页面", Toast.LENGTH_LONG).show();
            }
        }
    }
}
