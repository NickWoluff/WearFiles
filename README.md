# 🐾 嗷呜文件 (WearFiles)

![Version](https://img.shields.io/badge/version-1.0.0-brightgreen?style=flat-square)
![Android](https://img.shields.io/badge/Android-7.1%2B-3DDC84?style=flat-square&logo=android&logoColor=white)

**专为圆形与方形智能手表打造的文件管理器，嗷呜！**

在手腕上浏览内部存储，整理文件与文件夹，自定义喜欢的主题。嗷呜文件参考 AnExplorer 的文件浏览结构，使用 AndroidX 官方 Wear Material 3 组件与 Expressive 动效，适配手表上的触控与返回操作。

## ✨ 核心特性 (Features)

- **圆屏、方屏适配**：首次启动自动识别屏幕形状，也可在设置中手动切换。圆屏使用曲线时钟和弧形提示，方屏使用直线布局。
- **存储一目了然**：首页显示内部存储占用进度与可用容量，提供下载、图片、音乐和文档快捷入口。
- **文件整理**：浏览文件与文件夹，按名称或修改时间排序，切换隐藏文件显示；支持新建文件夹、重命名、移动与删除。
- **打开与分享**：调用设备上支持相应文件的应用，分享时选择可接收文件的应用。
- **手表交互**：右滑或系统返回键返回，底部鹅卵石按钮打开目录操作与应用设置；长按唤出操作菜单，删除前再次确认。
- **主题自定义**：六种强调色、六种调色板风格，显示缩放可在 80%–130% 之间调整，默认 100%，重启后保留设置。
- **悬浮提示**：操作结果显示在底部，长消息连续滚动并在两端淡入淡出。
- **检查更新**：支持手动检查与自动检查开关；自动检查默认开启，仅发现新版本时提示。

## 📥 下载与安装 (Installation)

**应用商店安装**：前往 **微思应用商店** 手表端，搜索“嗷呜文件”下载安装。

**ADB 手动安装**：

1. 前往 [Releases](https://github.com/NickWoluff/WearFiles/releases) 页面下载 APK。
2. 在手表上开启**开发者模式**和 **ADB 调试 / 无线调试**，完成连接。
3. 使用甲壳虫 ADB 助手、WearOS 工具箱，或电脑端 ADB 安装：

   ```bash
   adb install -r WearFiles_vX.X.X.apk


## 📂 使用指南 (Usage)

- **浏览文件**：首页点击“内部存储”，或直接进入常用目录。
- **返回上级**：右滑或按系统返回键；Android 15 及以上从屏幕左边缘开始右滑。
- **目录操作**：点击文件列表底部的 `…` 鹅卵石按钮，进行新建文件夹、排序或隐藏文件设置。
- **修改文件**：长按文件或文件夹，选择重命名、移动或删除。新建与重命名调用手表系统输入界面。
- **打开与分享**：点击文件进入详情，选择打开或分享；设备没有接收应用时会显示悬浮提示。
- **应用设置**：点击首页底部的设置图标，调整主题、屏幕布局、存储权限与更新选项。

移动目前限于同一共享存储卷。重命名与移动不会覆盖同名项目，移动会阻止将文件夹放入自身或其子目录；删除文件夹时会检查受限路径与符号链接。

## 🔐 存储权限 (Permissions)

首次使用会引导授权，并实际检查共享存储的读取、创建、写入、读回与删除，避免只显示文件夹却无法读写文件的情况。

- **Android 11 及以上**：需要系统授予“管理所有文件”权限。
- **Android 7.1–10**：需要读取和写入存储两项权限。
- `Android/data` 与 `Android/obb` 仍可能受系统限制，授予权限后也不保证可访问。

### 系统授权不可用时

部分修改版手表系统可能缺少授权页面，或授权后仍无法正常读写。应用会显示 ADB 指引，点击卡片可复制以下全部命令：

```bash
adb shell pm grant com.nickwoluff.wearfiles android.permission.READ_EXTERNAL_STORAGE
adb shell pm grant com.nickwoluff.wearfiles android.permission.WRITE_EXTERNAL_STORAGE
adb shell appops set com.nickwoluff.wearfiles MANAGE_EXTERNAL_STORAGE allow
```

较新的 Android 系统可能拒绝前两条旧存储权限命令，请继续执行第三条。实际访问范围仍由手表系统决定。

点击指引底部的对勾鹅卵石按钮可跳过；跳过后不会自动展示，也不会获得额外权限。之后可通过 **应用设置 → 存储权限** 重新查看指引。

## 🎨 主题与显示 (Appearance)

在 **应用设置 → 主题设置** 中可以调整：

- **强调色**：紫色、蓝色、青色、绿色、橙色、粉色。
- **调色板风格**：Tonal Spot、Vibrant、Expressive、Neutral、Monochrome、Fidelity。
- **显示缩放**：80%–130%，每档 5%，支持一键恢复 100%。

强调色与调色板各通过一个入口打开单选菜单。配色使用 Google Material Color Utilities，页面背景保持黑色。

若系统识别的屏幕形状不正确，可在 **应用设置 → 屏幕布局** 手动选择“方屏”或“圆屏”。

## 🔄 检查更新 (Updates)

在 **应用设置 → 检查更新** 手动查询 [GitHub Releases](https://github.com/NickWoluff/WearFiles/releases) 的最新正式版本。入口显示当前版本，检查结果使用手表悬浮提示反馈。

**自动检查更新** 默认开启，每次启动检查一次；无更新或检查失败时保持静默。关闭后仍可手动检查，开关状态会保存。

发现新版本时通过官方手表弹窗引导前往 Release 页面。若手表没有浏览器，可在手机浏览器访问上述地址。更新功能提供下载页面入口，安装仍需用户自行完成。

版本标签支持 `1.0.0`、`v1.0.0` 等数字格式，按数字段比较，草稿与预发布版本不作为正式更新。仓库尚无正式 Release 时，手动检查会提示“暂无正式发布版本”。

<a id="development"></a>

## 🛠️ 开发与构建 (Development)

使用 **JDK 21** 与项目自带的 Gradle Wrapper。当前配置为 `compileSdk 37.0`、`targetSdk 36`、`minSdk 25`，包名为 `com.nickwoluff.wearfiles`。

在项目根目录执行：

```powershell
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug --console=plain
```

macOS / Linux 使用 `./gradlew` 替代 `gradlew.bat`。调试 APK 位于 `app/build/outputs/apk/debug/app-debug.apk`。

<details>
<summary>Windows 构建出现 Java NIO 套接字 Invalid argument</summary>

在项目根目录设置临时目录后重新构建：

```powershell
New-Item -ItemType Directory -Force .socket-tmp | Out-Null
$socketPath = (Resolve-Path .socket-tmp).Path
$env:JAVA_TOOL_OPTIONS = "-Djdk.net.unixdomain.tmpdir=$socketPath"
.\gradlew.bat --no-daemon :app:assembleDebug :app:testDebugUnitTest :app:lintDebug --console=plain
```

</details>

项目包含 18 项单元测试，覆盖存储读写、文件操作、路径边界、版本比较、更新状态与动态版权。模拟器验证使用圆表 454×454 和方表 402×476，详细范围与结果见 [验证记录](VERIFICATION.md)。物理手表尚未验证。

`tools/EmulatorUi.ps1` 提供模拟器点击、滚动、界面导出与截图功能。截图保存在 `screenshots/`，与本机配置、构建输出、签名文件和第三方参考文件一起由 `.gitignore` 排除。

测试工具通过 `ANDROID_HOME`（或 `ANDROID_SDK_ROOT`）与 `JAVA_HOME` 定位 SDK 和 JDK；界面工具也可使用 PATH 中的 `adb` 或通过 `-Adb` 指定路径，分享测试构建工具支持 `-Sdk`、`-Jdk` 参数。

开发参考：[Wear OS 列表](https://developer.android.com/training/wearables/compose/lists) · [屏幕尺寸适配](https://developer.android.com/training/wearables/compose/screen-size) · [导航](https://developer.android.com/training/wearables/compose/navigation) · [共享存储与所有文件访问](https://developer.android.com/training/data-storage/manage-all-files)

## 💬 交流与反馈 (Community)

使用时遇到问题或有功能建议，欢迎提交至 [Issues](https://github.com/NickWoluff/WearFiles/issues)。或：

>加入 **嗷呜系列官方QQ交流群**：`暂无`

同系列应用：[🐾 嗷呜计算器](https://github.com/NickWoluff/WearCalculator)

© 2026 Nick Woluff. All Rights Reserved.
