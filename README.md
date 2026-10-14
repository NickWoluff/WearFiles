# 嗷呜文件

面向 Wear OS 圆形和方形手表的文件管理器。参考 AnExplorer 的存储入口、常用目录和文件浏览结构，使用 AndroidX 官方 Wear Material 3 组件与 Expressive 动效。

## 界面与导航

- 仅提供手表布局；Manifest 声明 `android.hardware.type.watch` 为必需特性。
- 首次启动通过 `Configuration.isScreenRound` 识别并保存屏幕布局；设置 → 屏幕布局可随时选择方屏或圆屏，重启后保持。圆屏布局使用官方 `TransformingLazyColumn` 和 `SurfaceTransformation`，时钟与悬浮提示使用曲线；方屏布局使用 `LazyColumn`，时钟和悬浮提示使用直线，保留完整矩形宽度。官方弹窗和底部按钮也跟随所选布局。使用 Wear Material 3 默认组件尺寸（按钮默认 52dp、主标签 15sp、副标签 13sp），默认显示缩放 100%。
- 手表由官方 `SwipeDismissableNavHost` 管理页面过渡、右滑返回与系统返回。Android 15 及以上使用系统预测式返回，右滑从屏幕左边缘开始。列表内没有返回行。
- 文件列表末尾使用 `ScreenScaffold` 的底部 `EdgeButton` 槽，鹅卵石形按钮仅显示横向三个点，点击进入目录操作页。
- 首页底部鹅卵石按钮使用 Material 实心设置图标，进入应用设置；存储权限入口位于设置中。
- 长按文件或文件夹打开官方 `AlertDialog` 操作菜单，重命名和移动使用等宽列表项，删除使用底部红色 `EdgeButton`；删除需二次确认，确认页保留标准取消列表项和红色确认 `EdgeButton`。官方 AlertDialog 只有一个鹅卵石按钮槽，没有双鹅卵石布局；移动页仅列出文件夹，底部按钮确认移到当前目录。
- 临时操作结果和读取错误采用底部悬浮提示：参考弦电子书 1.2.0 的深灰半透明弧形条，圆屏使用官方 `CurvedLayout` 与 Material 3 `curvedText`，方屏使用标准 `Text` 和圆角条。长文案连续滚动，圆屏移动曲线锚点，方屏水平移动文字；窗口两端使用透明度渐变，让文字淡入淡出。背景和字形由官方组件渲染，透明度遮罩仅作用于文字层。保留页面交互并自动淡出，支持无障碍延长时间。尺寸、位置与颜色参考弦电子书，字形并非像素完全一致。
- 内部存储按“标题 → 占用进度条 → 可用/总容量”排列，使用官方 `LinearProgressIndicator`。从首帧保留轨道，优先显示上次容量缓存，再异步更新，避免进度条突然插入造成跳动。
- 返回目录时使用目录快照保持滚动锚点，并异步刷新真实内容。快照按路径、隐藏文件设置和排序设置区分，最多保存 32 份。

## 文件能力和权限

支持内部共享存储浏览、下载/图片/音乐/文档快捷入口、按名称或修改时间排序、隐藏文件开关、新建文件夹，以及通过系统应用打开或分享文件。新建和重命名使用手表系统 RemoteInput 输入。

分享前列出实际可接收文件的应用，过滤 Wear OS 占位应用。通过 `FileProvider`、`ClipData` 和临时 URI 读取授权交付文件；没有接收应用时给出悬浮提示。

重命名和移动拒绝覆盖同名项目，移动拒绝自身和子目录。当前移动操作限于同一共享存储卷；删除文件夹前检查完整目录树，拒绝包含受限路径或符号链接的目录。文件操作在 IO 线程执行，并刷新真实目录内容。

## 主题与显示

主题页将“强调色”和“调色板风格”各折叠为一个入口，显示当前值，点击打开官方单选弹窗。提供六种强调色，以及 Tonal Spot、Vibrant、Expressive、Neutral、Monochrome、Fidelity 六种官方调色板。

配色使用 Google Material Color Utilities 算法，OLED 背景保持黑色。Material 库将颜色工具标记为内部 API，因此适配集中在 `AppSettings.kt`，依赖固定为 1.13.0，升级时需检查接口。

显示缩放默认 100%，范围 80%–130%，每档 5%。使用官方 Material 3 可拖动 Slider 数轴，松手后应用整体显示缩放，避免拖动过程中触点跳动。设置通过 SharedPreferences 保存，重启后保持。

## 检查更新

设置页提供“检查更新”和“自动检查更新”开关，使用 WearCalculator 相同的 GitHub 最新正式 Release 检查方式，更新源为 `NickWoluff/WearFiles`。自动检查默认开启，每次应用启动仅检查一次，配置重建保留请求状态；无更新或检查失败时不打扰用户。关闭后仍可手动检查，开关重启后保持。

手动检查显示当前安装版本和检查状态，并通过手表悬浮提示反馈“已是最新版本”、暂无正式发布版本、网络失败或请求过于频繁。发现新版本时使用官方 Wear Material 3 弹窗引导前往 Release 页面；没有浏览器时提示通过手机访问仓库 Releases 地址。版本标签使用数字格式（如 `v0.4.0`），按数字段比较；预发布与草稿不作为正式更新。

设置页最下方显示 `© 2026 Nick Woluff. All Rights Reserved.`，设备年份超过 2026 后显示年份区间，例如 `© 2026-2027 Nick Woluff. All Rights Reserved.`。

Android 11 及以上需要系统授予“管理所有文件”权限；旧版检查读取和写入两项运行时权限。首次使用自动引导，返回后通过共享存储临时文件验证创建、写入、读回和删除，不以能看到文件夹代替授权成功。即使授权，`Android/data` 和 `Android/obb` 仍可能受系统限制。

授权未完成或系统没有授权入口时显示 ADB 指引，一张可点击卡片复制以下全部命令（包名按实际安装包生成）：

```powershell
adb shell pm grant com.nickwoluff.wearfiles android.permission.READ_EXTERNAL_STORAGE
adb shell pm grant com.nickwoluff.wearfiles android.permission.WRITE_EXTERNAL_STORAGE
adb shell appops set com.nickwoluff.wearfiles MANAGE_EXTERNAL_STORAGE allow
```

新 Android 可能不再允许授予前两条旧权限，仍需继续执行第三条。指引底部的对勾鹅卵石按钮可跳过；跳过持久保存，此后不自动展示，可通过设置 → 存储权限重新查看。跳过不授予权限。

APK 版本为 0.3.0（versionCode 3），仅面向手表。存储授权仍由 Android 系统管理。

## 构建与验证

使用 JDK 21，compileSdk 37.0、targetSdk 36、minSdk 25：

```powershell
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug --console=plain
```

APK 位于 `app/build/outputs/apk/debug/app-debug.apk`。`tools/EmulatorUi.ps1` 用于模拟器点击、滚动、界面导出及截图；截图保存在 `screenshots/`。

Windows 用户目录含空格或短文件名时，如遇 Java NIO 套接字 `Invalid argument`，可在项目根目录执行以下命令后构建。临时路径按当前工程位置生成：

```powershell
New-Item -ItemType Directory -Force .socket-tmp | Out-Null
$socketPath = (Resolve-Path .socket-tmp).Path
$env:JAVA_TOOL_OPTIONS = "-Djdk.net.unixdomain.tmpdir=$socketPath"
.\gradlew.bat --no-daemon :app:assembleDebug :app:testDebugUnitTest :app:lintDebug --console=plain
```

源码仓库包含测试工具和验证记录；本机 SDK 配置、构建输出、日志、截图及第三方反编译参考文件由 `.gitignore` 排除。截图文件名用于本地验证记录，未随源码提交。

开发验证设备为圆表 454×454 和方表 402×476。18 项单元测试覆盖目录读取、排序、隐藏文件、创建、重命名、移动、递归删除、同名不覆盖、路径边界、真实存储读写验证，以及版本比较、更新反馈、自动检查开关、请求去重和动态版权。最新实际验证记录见 `VERIFICATION.md`，截图位于 `screenshots/v030-*`。

## 官方参考

- [Wear OS 列表](https://developer.android.com/training/wearables/compose/lists)
- [Wear OS 屏幕尺寸适配](https://developer.android.com/training/wearables/compose/screen-size)
- [Wear OS 导航](https://developer.android.com/training/wearables/compose/navigation)
- [共享存储与所有文件访问](https://developer.android.com/training/data-storage/manage-all-files)
