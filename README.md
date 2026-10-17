# -
锁定浏览器特点网页
---

## 六、防卸载（可选）

设置里点 **激活设备管理器** → 激活后无法在应用管理里常规卸载，需先到 `设置 → 安全 → 设备管理器` 取消激活。

---

## 七、已知限制与补强（重要）

### 限制 1：HOME 键无法屏蔽（所有普通 App 都做不到）——但可用「桌面接管」解决

Android 不允许普通应用拦截 HOME 键。**本 App 已内置 Fully Kiosk 同款的解决方案：**

> **设置 → 桌面接管 → 「设为默认桌面」按钮 → 系统弹选择框 → 选「车间生产系统」+「始终」**

设为默认桌面后：

- 工人按 Home 键 → **只会回到本应用**，回不到系统桌面，打不开其他应用
- 开机 → 系统直接进本应用（比自启动广播更可靠）
- 保存 Kiosk 锁定时，若未设置会**自动弹窗引导**（和 Fully Kiosk 一样的流程）
- 恢复方法：`设置 → 应用 → 默认应用 → 主屏幕应用` 改回原桌面；取消锁定时 App 也会提醒

备选方案（不想接管桌面时）：

| 方案  | 做法  | 效果  |
| --- | --- | --- |
| 系统屏幕固定 | `设置 → 安全 → 更多安全设置 → 屏幕固定` → 钉住本 App | HOME/返回/多任务全部失效 |
| ADB 设备所有者 | 每台连电脑跑 `dpm set-device-owner` | 最彻底，但每台需删账户 + 电脑操作 |

### 限制 2：通知栏可能被边缘下拉唤出

沉浸式模式能隐藏状态栏，但个别 ROM 从屏幕顶部边缘下拉仍可能唤出快捷面板。补强同上（屏幕固定）。

### 限制 3：开机自启在部分 ROM 可能失效

Android 10+ 限制后台启动 Activity。已尽力兼容（BOOT_COMPLETED / QUICKBOOT / LOCKED_BOOT / MY_PACKAGE_REPLACED 四个广播 + startActivity），配合 ZUI 的「自启动 + 电池白名单」基本可用。若某台仍失效，把它设为默认桌面后可彻底解决。

### 强烈建议叠加：路由器防火墙白名单

16 台平板 MAC 绑 IP → 只放行生产系统服务器地址，其他全部拦截。
**即使工人退出锁屏、甚至关掉 App，平板也访问不了任何其他网站或下载任何东西。**

---

## 八、本地编译（方式 B）

1. 装 [Android Studio](https://developer.android.com/studio)（自带 JDK 17 + SDK）
2. 用 Android Studio 打开 `kiosk-app` 目录，等待 Gradle Sync
3. 生成签名密钥（在工程根目录执行，Windows CMD）：
  
  ```
  keytool -genkeypair -v -keystore kiosk-release.jks -alias kiosk -keyalg RSA -keysize 2048 -validity 10000 -storepass kiosk2026 -keypass kiosk2026 -dname "CN=Kiosk, OU=IT, O=Yichi, L=Fujian, ST=Fujian, C=CN"
  ```
  
  macOS / Linux / Git Bash 用 `\` 续行。
4. 菜单 **Build → Build Bundle(s) / APK(s) → Build APK(s)**
5. 产物：`app/build/outputs/apk/release/app-release.apk`

> 若不想把密码写在 build.gradle 里，可在根目录建 `keystore.properties`：
> 
> ```
> storeFile=kiosk-release.jks
> storePassword=你的密码
> keyAlias=kiosk
> keyPassword=你的密码
> ```
> 
> 该文件已在 `.gitignore` 中，不会误提交。

只想要能装的包、不急 release：直接 **Build → Build APK(s)** 用 debug 包也可以，功能完全一样。

---

## 九、排错速查

| 现象  | 原因  | 处理  |
| --- | --- | --- |
| `Plugin [id: 'com.android.application'] was not found` | settings.gradle 缺仓库 | v1.0.1 已修；确认文件已上传 |
| `Keystore file not found` | 没生成 jks | 执行 keytool 命令，或先只打 debug 包 |
| 装到平板闪退 | 看 logcat | 开 USB 调试，`adb logcat \\| grep yichi` |
| 开机不自启 | ROM 杀后台 | ZUI 三个开关；或设为默认桌面 |
| 按 Home 能回桌面 | 平台限制 | 设为默认桌面 / 屏幕固定 |
| 点生产系统里的链接被误拦 | 生产系统跨了域名 | 设置里关掉「锁定访问域名」，或让 IT 统一域名 |
| 靠屏幕边缘的按钮点不动 | 边缘拦截误吞 | v1.0.2 已修（只在明确横向滑动时拦） |
| 设置进不去 | 忘记 PIN | PIN 弹窗点「忘记密码？用锁屏密码」；平板没设锁屏密码则重装 APK（会清数据） |
| App 连续崩溃、重启也白屏 | 崩溃循环 | v1.1.0 自动进安全模式：选「重新尝试启动」或输密码进系统设置 |
| 工人转动平板页面排版乱 | 屏幕旋转 | 设置 → 屏幕方向 → 固定横屏 |
| 工人下拉看到通知 | 状态栏未隐藏 | 设置里确认「隐藏状态栏」已勾；通知内容要彻底没有需去系统通知管理逐个关 |

---

## 十、文件结构

```
kiosk-app/
├── app/
│   ├── build.gradle                    # 应用构建配置（签名可外部覆盖）
│   └── src/main/
│       ├── AndroidManifest.xml         # 含 HOME 类别，可设为默认桌面
│       ├── java/com/yichi/kiosk/
│       │   ├── MainActivity.java       # 锁屏主界面（WebView + 手势 + PIN + 崩溃安全模式）
│       │   ├── SettingsActivity.java   # 设置（SAF 导入导出 + 细粒度开关 + 桌面接管）
│       │   ├── KioskConfig.java        # 配置读写 + PIN 哈希 + 崩溃计数
│       │   ├── KioskApp.java           # Application：全局崩溃捕获 → 计数 → 触发安全模式
│       │   ├── BootReceiver.java       # 开机自启
│       │   └── AdminReceiver.java      # 设备管理器（防卸载）
│       └── res/
└── .github/workflows/build.yml         # 云端自动编译
```
