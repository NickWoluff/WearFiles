package com.nickwoluff.wearfiles

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.*
import androidx.compose.ui.graphics.vector.ImageVector
import java.io.File
import java.text.DateFormat
import java.util.Date

internal enum class RowKind { ACTION, SECTION, INFO, SCALE, COMMANDS, FOOTER }
internal data class BrowserRow(
    val key: String, val label: String, val detail: String? = null,
    val icon: ImageVector = Icons.Default.Folder, val kind: RowKind = RowKind.ACTION,
    val primary: Boolean = false, val checked: Boolean? = null, val click: () -> Unit = {},
    val longClick: (() -> Unit)? = null, val chosen: Boolean = false,
    val scale: Int = 100, val changeScale: (Int) -> Unit = {}, val enabled: Boolean = true,
    val usedFraction: Float? = null, val showProgress: Boolean = false
)

internal fun browserRows(s: BrowserState, a: BrowserActions): List<BrowserRow> = buildList {
    when (s.page) {
        Page.HOME -> {
            add(BrowserRow("storage", "内部存储", if (s.space.total > 0) "${fileSize(s.space.available)} 可用 / ${fileSize(s.space.total)}"
                else "正在读取容量…", Icons.Default.Storage, primary = true, click = { a.browse(s.root) },
                usedFraction = if (s.space.total > 0)
                    (1.0 - s.space.available.toDouble() / s.space.total).toFloat().coerceIn(0f, 1f) else null,
                showProgress = true))
            if (!s.access) add(BrowserRow("access", "允许存储访问", "首次使用需要授权", Icons.Default.Security, click = a.accessPage))
            add(BrowserRow("shortcuts", "常用目录", kind = RowKind.SECTION))
            add(BrowserRow("download", "下载", "Download", Icons.Default.Download, click = { a.browse(File(s.root, "Download").path) }))
            add(BrowserRow("pictures", "图片", "Pictures", Icons.Default.Image, click = { a.browse(File(s.root, "Pictures").path) }))
            add(BrowserRow("music", "音乐", "Music", Icons.Default.MusicNote, click = { a.browse(File(s.root, "Music").path) }))
            add(BrowserRow("documents", "文档", "Documents", Icons.Default.Description, click = { a.browse(File(s.root, "Documents").path) }))
        }
        Page.FILES, Page.MOVE -> {
            add(BrowserRow("count", if (s.loading) "正在读取…" else "${s.entries.size} 个项目",
                kind = RowKind.SECTION))
            if (s.error != null) {
                add(BrowserRow("retry", "重新读取", icon = Icons.Default.Refresh, click = a.refresh))
            } else if (!s.loading && s.entries.none { s.page != Page.MOVE || it.directory })
                add(BrowserRow("empty", if (s.page == Page.MOVE) "没有子文件夹" else "此目录为空",
                    if (s.page == Page.MOVE) "点按底部按钮移到此目录" else "可在目录操作中创建文件夹", kind = RowKind.INFO))
            s.entries.filter { s.page != Page.MOVE || it.directory }.forEach { entry -> add(BrowserRow(entry.path, entry.name,
                if (entry.directory) "文件夹" else fileSize(entry.bytes), fileIcon(entry), click = { a.select(entry) },
                longClick = if (s.page == Page.FILES) ({ a.operations(entry) }) else null, enabled = !s.busy)) }
        }
        Page.TOOLS -> {
            add(BrowserRow("create", "新建文件夹", icon = Icons.Default.CreateNewFolder, primary = true, click = a.create, enabled = !s.busy))
            add(BrowserRow("refresh", "刷新目录", icon = Icons.Default.Refresh, click = a.refresh))
            add(BrowserRow("hidden", "显示隐藏文件", icon = Icons.Default.Visibility, checked = s.hidden, click = a.toggleHidden))
            add(BrowserRow("order", "最近修改优先", "关闭时按名称排列", Icons.AutoMirrored.Filled.Sort, checked = s.newest, click = a.toggleOrder))
            add(BrowserRow("home", "回到主页", icon = Icons.Default.Home, click = a.home))
        }
        Page.SETTINGS -> {
            add(BrowserRow("theme", "主题设置", "颜色、调色板与显示缩放", Icons.Default.Palette, primary = true, click = a.themePage))
            add(BrowserRow("layout", "屏幕布局", s.settings.layout.label, Icons.Default.Watch, click = a.layoutPicker))
            add(BrowserRow("access", "存储权限", if (s.access) "读写检查通过" else "未完成授权", Icons.Default.Security, click = a.accessPage))
            add(BrowserRow("check-update", if (s.checkingUpdate) "正在检查…" else "检查更新",
                "当前版本 ${s.localVersion}", Icons.Default.Update, click = a.checkUpdate, enabled = !s.checkingUpdate))
            add(BrowserRow("auto-update", "自动检查更新", if (s.autoCheckUpdates) "启动时检查" else "仅手动检查",
                Icons.Default.Autorenew, checked = s.autoCheckUpdates, click = a.toggleAutoUpdate))
            add(BrowserRow("copyright", s.copyright, kind = RowKind.FOOTER))
        }
        Page.THEME -> {
            add(BrowserRow("scale", "显示缩放", kind = RowKind.SCALE, scale = s.settings.scale,
                changeScale = { a.appearance(s.settings.copy(scale = it)) }))
            add(BrowserRow("reset-scale", "恢复 100%", icon = Icons.Default.RestartAlt,
                click = { a.appearance(s.settings.copy(scale = 100)) }))
            add(BrowserRow("accent", "强调色", accents.firstOrNull { it.argb == s.settings.accent }?.label,
                Icons.Default.Palette, click = a.accentPicker))
            add(BrowserRow("palette", "调色板风格", s.settings.palette.label,
                Icons.Default.Style, click = a.palettePicker))
        }
        Page.ACCESS -> {
            add(BrowserRow("permission", when { s.checkingAccess -> "正在检查存储权限"; s.access -> "读写检查通过"; else -> "需要完整读写授权" },
                when { s.checkingAccess -> "正在验证共享存储文件的读取、写入和删除。"
                    s.access -> "已验证共享存储读写，可以浏览文件和文件夹。Android/data 和 obb 仍受系统限制。"
                    s.accessCheck.error != null -> s.accessCheck.error
                    android.os.Build.VERSION.SDK_INT < 30 -> "请允许读取和写入存储两项权限，返回后自动检查。"
                    s.canRequestAccess -> "请开启“管理所有文件”，仅允许照片或媒体权限无法浏览全部文件。返回后自动检查读写。"
                    else -> "系统未提供“管理所有文件”授权入口，请查看 ADB 授权指引。应用无法自行授予系统权限。" },
                kind = RowKind.INFO))
            if (!s.access && s.canRequestAccess) add(BrowserRow("grant", "去授权", icon = Icons.Default.Settings,
                primary = true, click = a.requestAccess, enabled = !s.checkingAccess))
            add(BrowserRow("check", "重新检查", icon = Icons.Default.Refresh, click = a.recheckAccess, enabled = !s.checkingAccess))
            add(BrowserRow("adb", "ADB 授权指引", icon = Icons.Default.Terminal, click = a.adbGuide))
            if (s.access) add(BrowserRow("browse", "浏览内部存储", icon = Icons.Default.Storage, primary = true, click = { a.browse(s.root) }))
        }
        Page.ADB -> {
            add(BrowserRow("adb-info", if (s.access) "读写检查通过" else "手动授权",
                "连接电脑并开启 ADB 调试，依次执行下面三条命令。点按卡片复制全部命令；底部对勾可跳过，之后不再自动显示。",
                kind = RowKind.INFO))
            add(BrowserRow("adb-commands", "点按复制全部命令", s.adbCommands,
                kind = RowKind.COMMANDS, click = a.copyAdbCommands))
            add(BrowserRow("adb-note", "版本差异",
                "新 Android 若提示前两条权限无法授予，请继续执行第三条。跳过不会授予权限，可在设置的存储权限中再次查看。",
                kind = RowKind.INFO))
            add(BrowserRow("check", if (s.checkingAccess) "正在检查…" else "重新检查授权",
                icon = Icons.Default.Refresh, click = a.recheckAccess, enabled = !s.checkingAccess))
            if (!s.access && s.canRequestAccess) add(BrowserRow("grant", "重试系统授权",
                icon = Icons.Default.Settings, click = a.requestAccess))
        }
        Page.DETAILS -> s.selected?.let { file ->
            add(BrowserRow("name", file.name, kind = RowKind.INFO))
            add(BrowserRow("open", "打开文件", icon = Icons.AutoMirrored.Filled.OpenInNew, primary = true, click = a.openFile))
            add(BrowserRow("share", "分享文件", icon = Icons.Default.Share, click = a.shareFile))
            add(BrowserRow("size", "大小", fileSize(file.bytes), kind = RowKind.INFO))
            add(BrowserRow("modified", "修改时间", DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(file.modified)), kind = RowKind.INFO))
            add(BrowserRow("path", "位置", file.path, kind = RowKind.INFO))
        }
    }
}

internal fun fileIcon(entry: FileEntry): ImageVector = if (entry.directory) Icons.Default.Folder else when (entry.name.substringAfterLast('.', "").lowercase()) {
    "jpg", "jpeg", "png", "webp", "gif", "heic" -> Icons.Default.Image
    "mp3", "wav", "flac", "m4a", "ogg" -> Icons.Default.MusicNote
    "mp4", "mkv", "webm", "avi" -> Icons.Default.Movie
    "apk" -> Icons.Default.Android
    "zip", "rar", "7z", "tar", "gz" -> Icons.Default.Inventory2
    "txt", "pdf", "doc", "docx", "md" -> Icons.Default.Description
    else -> Icons.AutoMirrored.Filled.InsertDriveFile
}
