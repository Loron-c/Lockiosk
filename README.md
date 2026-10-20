# Lockiosk — Android Kiosk 锁屏应用

> 自研 Android Kiosk（专用终端锁定）应用：让平板/触控屏设备**只能运行一个指定网页，其他什么都做不了**。
> 等效替代商业软件 Fully Kiosk 的核心能力：零授权成本、可深度定制、构建与签名全程自主可控。

[![Build Kiosk APK](https://github.com/Loron-c/Lockiosk/actions/workflows/main.yml/badge.svg)](https://github.com/Loron-c/Lockiosk/actions/workflows/main.yml)
![Platform](https://img.shields.io/badge/platform-Android%2010%2B-blue)
![Lang](https://img.shields.io/badge/lang-Java%2017-orange)

## 功能一览

| 功能                | 说明                                                                                        |
| ----------------- | ----------------------------------------------------------------------------------------- |
| **全屏网页锁定**        | WebView 全屏加载指定网址，无地址栏、无浏览器 UI                                                             |
| **四层 URL 白名单**    | 导航回调 / 页面加载复查 / 新窗口拒绝 / 协议白名单，覆盖 302 重定向、`window.open`、`target=_blank` 等绕过手段，点外部链接自动拦截并提示 |
| **Launcher 桌面接管** | 注册 `CATEGORY_HOME`，可设为系统默认桌面：按 Home 只会回到本应用，开机直达                                          |
| **通知栏防护**         | 三层机制：顶部悬浮挡板吞掉下拉手势 + AccessibilityService 监听面板弹出代按返回键收回 + 沉浸式隐藏系统栏                         |
| **按键管控**          | 可屏蔽音量键 / 菜单键 / 相机键；返回键在网页内正常后退、到首页停留不退出                                                   |
| **手势解锁管理**        | 左上角双击 → 右下角双击（4 秒内）触发管理员验证，正常操作永不误触                                                       |
| **PIN 安全**        | 密码仅存 SHA-256 哈希，绝不明文落盘；支持系统锁屏凭证兜底验证（KeyguardManager），忘密码也有退路                              |
| **崩溃安全模式**        | Application 级全局崩溃捕获 + 崩溃计数，连续崩溃自动进入原生逃生页，设备不会「变砖」                                         |
| **断网兜底**          | 加载失败显示错误页 + 点击重试；内网自签名证书自动放行（域名白名单兜底安全）                                                   |
| **亮屏直达**          | 亮屏直接显示应用界面，无需先解系统锁屏；系统锁仍保护设备其余部分                                                          |
| **切后台自恢复**        | 任何原因进入后台，自动回到前台                                                                           |
| **细粒度开关**         | 域名锁定 / 音量键 / 状态栏 / 屏幕方向（横竖屏锁定）/ 自动刷新间隔 / 屏幕常亮，全部可配置                                       |
| **批量部署**          | 配置文件导出/导入（SAF 文件选择器，无需任何权限），一台配好其余秒级同步                                                    |
| **防卸载**           | 可选激活设备管理器，防止被常规卸载                                                                         |
| **开机自启**          | 多广播兼容（BOOT_COMPLETED / QUICKBOOT / LOCKED_BOOT 等）                                         |

## 技术栈

`Android (Java)` · `WebView` · `AccessibilityService` · `WindowManager 悬浮窗` · `SharedPreferences` · `GitHub Actions CI/CD` · `JKS 签名管理`

## 设计与实现要点

- **Home 键是 Android 平台限制**（任何普通 App 都收不到 Home 事件），业界标准解法是 Launcher 接管，本项目内置完整引导流程
- **通知面板是系统级手势**，悬浮窗与官方 API 都拦不住展开本身；利用无障碍服务感知面板弹出、代按返回键收回，是与商业产品同款的思路
- **签名一致性决定能否覆盖升级**：CI 使用固定 JKS 密钥（仅存 GitHub Secrets，构建脚本无密钥即失败），杜绝「构建一次换一个签名」导致的升级丢数据
- **密码侧只存哈希**：配置导出同步的也是哈希，任何环节都不出现明文

## 截图

![](C:/Users/721-2/AppData/Roaming/marktext/images/2026-10-20-14-10-44-pic(12).jpg)

![](C:/Users/721-2/AppData/Roaming/marktext/images/2026-10-20-14-10-49-pic(13).jpg)![](C:/Users/721-2/AppData/Roaming/marktext/images/2026-10-20-14-10-53-pic(11).jpg)

<!-- 可补充真机截图并取消注释：

![锁屏主界面](docs/screenshot-main.png)
![管理设置页](docs/screenshot-settings.png)
-->

## 构建与使用

### 云端构建（GitHub Actions，本机零环境）

1. 生成签名密钥并转 base64，配成仓库 Secret `KEYSTORE_B64`（密钥只存 Secret，**绝不提交仓库**）
2. Actions → **Build Kiosk APK** → Run workflow → 下载 Artifacts 中的 `kiosk-apk.zip`

### 配置流程

1. 安装 APK → **左上角双击 → 右下角双击**（4 秒内）→ 免密进入设置（锁定未开启时是免密配置区）
2. 填入目标网址（https，裸地址自动补全）、按需勾选各项锁定开关
3. 设置管理员 PIN → 勾「启用 Kiosk 锁定」→ 保存 → 按引导设为默认桌面
4. 其余设备通过「导入配置文件」同步全部设置

### 设备侧权限配置（联想小新 ZUI 必做，否则后台拉起/开机自启会被系统杀掉）

`设置 → 应用管理 → Lockiosk`：

1. **电池** → 允许后台运行（缺了「切后台自动回前台」不生效）
2. **自启动** → 打开
3. **权限** → 允许「存储」
4. 设置 → 特殊应用权限 → **显示在其他应用上层** → 允许（通知栏挡板 + 后台拉起的前置条件）
5. 设置 → **无障碍** → 开启 Lockiosk 服务（通知面板自动收回）

> 通用规律：**卸载重装后以上权限全部清零，需重新授予**。批量部署时把它固定为装包后的标准流程：装包 → 授权限 → 导入配置。

## 文件结构

```
app/src/main/java/com/yichi/kiosk/
├── MainActivity.java       # 锁屏主界面：WebView、手势、PIN、沉浸式、通知栏防护
├── SettingsActivity.java   # 管理设置：开关、SAF 导入导出、桌面接管、权限引导
├── AutoCloseService.java   # 无障碍服务：通知面板弹出即收回
├── KioskConfig.java        # 配置读写、PIN 哈希、崩溃计数
├── KioskApp.java           # 全局崩溃捕获 → 崩溃安全模式
├── BootReceiver.java       # 开机自启
└── AdminReceiver.java      # 设备管理器（防卸载）
.github/workflows/main.yml  # CI：云端构建 + Secrets 签名
```

## 
