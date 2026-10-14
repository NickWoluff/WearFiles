package com.nickwoluff.wearfiles

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.progressSemantics
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.DriveFileMove
import androidx.compose.material.icons.filled.Share
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Density
import kotlin.math.roundToInt
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.navArgument
import androidx.wear.compose.foundation.ScrollInfoProvider
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.foundation.requestFocusOnHierarchyActive
import androidx.wear.compose.foundation.rotary.RotaryScrollableDefaults
import androidx.wear.compose.foundation.rotary.rotaryScrollable
import androidx.wear.compose.material3.*
import androidx.wear.compose.material3.lazy.rememberTransformationSpec
import androidx.wear.compose.material3.lazy.transformedHeight
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import java.io.File

@Composable
fun WatchApp(state: BrowserState, actions: BrowserActions, navController: NavHostController, dialog: String?,
    notice: WatchNotice?, dismissNotice: (Int) -> Unit) {
    val density = LocalDensity.current
    val systemConfig = LocalConfiguration.current
    val layoutConfig = remember(systemConfig, state.settings.layout) {
        // 官方列表、弹窗和底部按钮也读取屏幕形状，统一为应用内选择的布局。
        Configuration(systemConfig).apply {
            screenLayout = (screenLayout and Configuration.SCREENLAYOUT_ROUND_MASK.inv()) or
                if (state.settings.layout == ScreenLayout.ROUND) Configuration.SCREENLAYOUT_ROUND_YES
                else Configuration.SCREENLAYOUT_ROUND_NO
        }
    }
    val colors = remember(state.settings.accent, state.settings.palette) { watchColors(state.settings) }
    CompositionLocalProvider(LocalConfiguration provides layoutConfig,
        LocalDensity provides Density(density.density * state.settings.scale / 100f, density.fontScale)) {
    MaterialTheme(colorScheme = colors, motionScheme = MotionScheme.expressive()) {
        WatchHapticFeedbackProvider {
        AppScaffold(timeText = { WatchTimeText() }) {
            val currentState by rememberUpdatedState(state)
            val currentActions by rememberUpdatedState(actions)
            val root = state.root
            val screens = remember { mutableStateMapOf<String, BrowserState>() }
            SideEffect {
                screens[state.location.key] = state
                if (screens.size > 32) screens.keys.firstOrNull {
                    it != state.location.key && it != state.backLocation?.key
                }?.let { screens.remove(it) }
            }
            // 官方导航负责进入动效、侧滑背景、返回取消和列表状态的保存恢复。
            SwipeDismissableNavHost(navController = navController,
                startDestination = BrowserLocation(Page.HOME, root).route) {
                composable("screen/{kind}/{directory}?file={file}", arguments = listOf(
                    // 导航图的初始入口使用参数默认值，之后的导航由具体路由传入。
                    navArgument("kind") { type = NavType.StringType; defaultValue = Page.HOME.name },
                    navArgument("directory") { type = NavType.StringType; defaultValue = root },
                    navArgument("file") { type = NavType.StringType; defaultValue = "" }
                )) { entry ->
                    val state = currentState
                    val actions = currentActions
                    val page = Page.valueOf(requireNotNull(entry.arguments?.getString("kind")))
                    val path = requireNotNull(entry.arguments?.getString("directory"))
                    val location = BrowserLocation(page, path, entry.arguments?.getString("file")?.takeIf { it.isNotEmpty() })
                    val snapshot = screens[location.key]
                    // 主页快捷目录会直接建立父级；未展示过的父级也读取真实内容。
                    var fallback by remember(location) { mutableStateOf<BrowserState?>(null) }
                    LaunchedEffect(location, state.access, state.hidden, state.newest) {
                        if (snapshot == null && location != state.location) {
                            val result = if (page == Page.FILES && state.access)
                                StorageRepository(File(state.root)).list(path, state.hidden, state.newest, android.os.Build.VERSION.SDK_INT >= 30)
                            else DirectoryResult()
                            fallback = state.copy(page = page, path = path,
                                title = if (page == Page.HOME) "嗷呜文件" else if (path == state.root) "内部存储" else File(path).name,
                                entries = result.entries, error = result.error, loading = false, selected = null)
                        }
                    }
                    val shown = if (location == state.location) state else snapshot ?: fallback
                    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                        shown?.let { WatchScreen(it.copy(settings = state.settings, busy = state.busy), actions) }
                    }
                }
            }
            OperationDialogs(state, actions, dialog)
            notice?.let { current -> key(current.id) {
                WatchFloatingNotice(current) { dismissNotice(current.id) }
            } }
        }
        }
    }
    }
}

@Composable
private fun WatchTimeText() {
    if (LocalConfiguration.current.isScreenRound) {
        TimeText()
    } else {
        // 复用官方时间源，自动响应时间、时区和 12/24 小时制变化。
        val timeSource = TimeTextDefaults.rememberTimeSource(TimeTextDefaults.timeFormat())
        Box(Modifier.fillMaxWidth().padding(top = 4.dp), contentAlignment = Alignment.TopCenter) {
            Text(timeSource.currentTime(), modifier = Modifier.clearAndSetSemantics {},
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1)
        }
    }
}

@Composable
private fun WatchScreen(state: BrowserState, actions: BrowserActions) {
    val config = LocalConfiguration.current
    val rows = browserRows(state, actions).filter { it.key != "count" }
    val horizontal = (config.screenWidthDp * when {
        config.isScreenRound && state.page == Page.ADB -> 0.12f
        config.isScreenRound -> 0.052f
        else -> 0.04f
    }).dp
    // 命令卡片很长，使用普通列表避免圆屏变换压缩长文本。
    if (config.isScreenRound && state.page != Page.ADB) {
        val listState = rememberTransformingLazyColumnState()
        val spec = rememberTransformationSpec()
        WatchScaffold(state, actions, remember(listState) { ScrollInfoProvider(listState) },
            PaddingValues(horizontal = horizontal)) { padding ->
            TransformingLazyColumn(state = listState, contentPadding = padding, modifier = Modifier.fillMaxSize()) {
                item(key = "title") {
                    ListHeader(
                        modifier = Modifier.fillMaxWidth().transformedHeight(this, spec)
                            .minimumVerticalContentPadding(ListHeaderDefaults.minimumTopListContentPadding),
                        transformation = SurfaceTransformation(spec)
                    ) { WatchTitle(state) }
                }
                items(rows.size, key = { rows[it].key }) { index ->
                    WatchRow(rows[index], Modifier.fillMaxWidth().transformedHeight(this, spec)
                        .minimumVerticalContentPadding(ButtonDefaults.minimumVerticalListContentPadding), SurfaceTransformation(spec))
                }
            }
        }
    } else {
        val listState = rememberLazyListState()
        val focusRequester = remember { FocusRequester() }
        WatchScaffold(state, actions, remember(listState) { ScrollInfoProvider(listState) },
            PaddingValues(start = horizontal, end = horizontal, top = 28.dp, bottom = 28.dp)) { padding ->
            LazyColumn(state = listState, contentPadding = padding, modifier = Modifier.fillMaxSize()
                .requestFocusOnHierarchyActive()
                .rotaryScrollable(RotaryScrollableDefaults.behavior(listState), focusRequester),
                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                item(key = "title") { ListHeader(Modifier.fillMaxWidth()) { WatchTitle(state) } }
                items(rows, key = { it.key }) { row -> WatchRow(row, Modifier.fillMaxWidth()) }
            }
        }
    }
}

@Composable
private fun WatchScaffold(state: BrowserState, actions: BrowserActions, scroll: ScrollInfoProvider,
    padding: PaddingValues, content: @Composable BoxScope.(PaddingValues) -> Unit) {
    if (state.page == Page.FILES || state.page == Page.HOME || state.page == Page.MOVE || state.page == Page.ADB) {
        ScreenScaffold(scrollInfoProvider = scroll, contentPadding = padding,
            edgeButton = {
                EdgeButton(onClick = when (state.page) {
                    Page.HOME -> actions.settingsPage; Page.MOVE -> actions.confirmMove
                    Page.ADB -> actions.skipAccess; else -> actions.tools
                }, enabled = !state.busy && (state.page != Page.MOVE || (!state.loading && state.error == null)),
                    buttonSize = EdgeButtonSize.Small) {
                    Icon(when (state.page) { Page.HOME -> Icons.Default.Settings; Page.MOVE, Page.ADB -> Icons.Default.Check; else -> Icons.Default.MoreHoriz },
                        contentDescription = when (state.page) { Page.HOME -> "应用设置"; Page.MOVE -> "移到这里"; Page.ADB -> "跳过并进入软件"; else -> "目录操作" },
                        modifier = Modifier.size(EdgeButtonDefaults.SmallIconSize))
                }
            }, content = content)
    } else ScreenScaffold(scrollInfoProvider = scroll, contentPadding = padding, content = content)
}

@Composable
private fun WatchTitle(state: BrowserState) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(state.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (state.page == Page.FILES) Text(if (state.loading) "正在读取…" else "${state.entries.size} 个项目",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun WatchRow(row: BrowserRow, modifier: Modifier, transformation: SurfaceTransformation? = null) {
    when (row.kind) {
        RowKind.FOOTER -> ListSubHeader(modifier = modifier, transformation = transformation) {
            Text(row.label, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        RowKind.COMMANDS -> Card(onClick = row.click, modifier = modifier, transformation = transformation) {
            Text(row.label, style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))
            Text(row.detail.orEmpty(), style = MaterialTheme.typography.bodySmall)
        }
        RowKind.SCALE -> Card(modifier = modifier, transformation = transformation) {
            var draft by remember(row.scale) { mutableFloatStateOf(row.scale.toFloat()) }
            Text("显示缩放 ${draft.roundToInt()}%", style = MaterialTheme.typography.titleMedium)
            // Wear Slider 只有增减按钮；此处使用官方 Material 3 可拖动数轴。
            androidx.compose.material3.Slider(value = draft, onValueChange = { draft = it },
                valueRange = 80f..130f, steps = 9,
                onValueChangeFinished = { row.changeScale((draft / 5).roundToInt() * 5) },
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                colors = androidx.compose.material3.SliderDefaults.colors(
                    thumbColor = MaterialTheme.colorScheme.primary,
                    activeTrackColor = MaterialTheme.colorScheme.primary,
                    inactiveTrackColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    activeTickColor = MaterialTheme.colorScheme.onPrimary,
                    inactiveTickColor = MaterialTheme.colorScheme.onSurfaceVariant))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("80%", style = MaterialTheme.typography.bodySmall)
                Text("130%", style = MaterialTheme.typography.bodySmall)
            }
        }
        RowKind.SECTION -> ListSubHeader(modifier = modifier, transformation = transformation) {
            Text(row.label, style = MaterialTheme.typography.titleSmall)
        }
        RowKind.INFO -> Card(modifier = modifier, transformation = transformation) {
            Text(row.label, style = MaterialTheme.typography.titleMedium)
            row.detail?.let { Spacer(Modifier.height(6.dp)); Text(it, style = MaterialTheme.typography.bodyMedium) }
        }
        RowKind.ACTION -> {
            val detail: (@Composable RowScope.() -> Unit)? = row.detail?.let { text ->
                { Column(Modifier.fillMaxWidth()) {
                    if (row.showProgress) {
                        val used = row.usedFraction
                        // 官方指示器通过 snapshotFlow 观察进度，需从最新状态读取而非捕获首帧数值。
                        val latestUsed by rememberUpdatedState(used ?: 0f)
                        Spacer(Modifier.height(6.dp))
                        LinearProgressIndicator(progress = { latestUsed },
                            modifier = if (used == null) Modifier.fillMaxWidth().progressSemantics()
                                else Modifier.fillMaxWidth().progressSemantics(used),
                            strokeWidth = LinearProgressIndicatorDefaults.StrokeWidthSmall,
                            colors = ProgressIndicatorDefaults.colors(
                                indicatorColor = MaterialTheme.colorScheme.onPrimary,
                                trackColor = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.2f)))
                        Spacer(Modifier.height(6.dp))
                    }
                    Text(text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                } }
            }
            if (row.checked != null) {
                SwitchButton(checked = row.checked, onCheckedChange = { row.click() }, modifier = modifier,
                    transformation = transformation, secondaryLabel = detail,
                    icon = { Icon(row.icon, null, modifier = Modifier.size(ButtonDefaults.IconSize)) }) {
                    Text(row.label, maxLines = 2)
                }
            } else {
                Button(onClick = row.click, onLongClick = row.longClick,
                    onLongClickLabel = if (row.longClick != null) "文件操作" else null,
                    enabled = row.enabled, modifier = modifier, transformation = transformation,
                    colors = if (row.primary || row.chosen) ButtonDefaults.buttonColors() else ButtonDefaults.filledTonalButtonColors(),
                    secondaryLabel = detail,
                    icon = { Icon(if (row.chosen) Icons.Default.Check else row.icon, if (row.chosen) "已选中" else null,
                        modifier = Modifier.size(ButtonDefaults.IconSize)) }) {
                    Text(row.label, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun OperationDialogs(state: BrowserState, actions: BrowserActions, dialog: String?) {
    AlertDialog(visible = state.availableVersion != null && dialog == null && !state.checkingAccess &&
        state.page != Page.ACCESS && state.page != Page.ADB,
        onDismissRequest = actions.dismissUpdate,
        title = { Text("发现新版本 ${state.availableVersion.orEmpty()}") },
        text = { Text("可前往 GitHub Release 页面更新") },
        content = {
            item { Button(onClick = actions.openRelease, modifier = Modifier.fillMaxWidth()) {
                Text("前往 Release")
            } }
            item { Button(onClick = actions.dismissUpdate, modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.filledTonalButtonColors()) { Text("稍后再说") } }
        })
    AlertDialog(visible = dialog == "layout", onDismissRequest = actions.dismissDialog,
        title = { Text("屏幕布局") }, content = {
            ScreenLayout.entries.forEach { layout -> item {
                RadioButton(selected = state.settings.layout == layout,
                    onSelect = { actions.appearance(state.settings.copy(layout = layout)); actions.dismissDialog() },
                    modifier = Modifier.fillMaxWidth()) { Text(layout.label) }
            } }
        })
    AlertDialog(visible = dialog == "share", onDismissRequest = actions.dismissDialog,
        title = { Text("分享文件") }, content = {
            state.shareTargets.forEach { target -> item {
                Button(onClick = { actions.shareTo(target) }, modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.filledTonalButtonColors(),
                    icon = { Icon(Icons.Default.Share, null, Modifier.size(ButtonDefaults.IconSize)) }) {
                    Text(target.label, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            } }
        })
    AlertDialog(visible = dialog == "accent", onDismissRequest = actions.dismissDialog,
        title = { Text("强调色") }, content = {
            accents.forEach { accent -> item {
                RadioButton(selected = state.settings.accent == accent.argb,
                    onSelect = { actions.appearance(state.settings.copy(accent = accent.argb)); actions.dismissDialog() },
                    modifier = Modifier.fillMaxWidth()) { Text(accent.label) }
            } }
        })
    AlertDialog(visible = dialog == "palette", onDismissRequest = actions.dismissDialog,
        title = { Text("调色板风格") }, content = {
            PaletteStyle.entries.forEach { palette -> item {
                RadioButton(selected = state.settings.palette == palette,
                    onSelect = { actions.appearance(state.settings.copy(palette = palette)); actions.dismissDialog() },
                    modifier = Modifier.fillMaxWidth()) { Text(palette.label) }
            } }
        })
    AlertDialog(visible = dialog == "operations", onDismissRequest = actions.dismissDialog,
        title = { Text(state.selected?.name.orEmpty(), maxLines = 2, overflow = TextOverflow.Ellipsis) },
        edgeButton = {
            EdgeButton(onClick = actions.askDelete, enabled = !state.busy,
                buttonSize = EdgeButtonSize.Medium,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError)) { Text("删除") }
        },
        content = {
            item { Button(onClick = actions.rename, enabled = !state.busy,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.filledTonalButtonColors(),
                icon = { Icon(Icons.Default.DriveFileRenameOutline, null,
                    modifier = Modifier.size(ButtonDefaults.IconSize)) }) { Text("重命名") } }
            item { Button(onClick = actions.move, enabled = !state.busy,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.filledTonalButtonColors(),
                icon = { Icon(Icons.Default.DriveFileMove, null,
                    modifier = Modifier.size(ButtonDefaults.IconSize)) }) { Text("移动") } }
        })
    AlertDialog(visible = dialog == "delete", onDismissRequest = actions.dismissDialog,
        title = { Text("删除 ${state.selected?.name.orEmpty()}？", maxLines = 3, overflow = TextOverflow.Ellipsis) },
        text = { Text(if (state.selected?.directory == true) "文件夹及其全部内容将被删除，无法撤销。" else "文件将被删除，无法撤销。") },
        // 官方 AlertDialog 仅提供一个 EdgeButton 槽，取消使用标准列表按钮。
        edgeButton = {
            EdgeButton(onClick = actions.delete, enabled = !state.busy,
                buttonSize = EdgeButtonSize.Medium,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError)) { Text("确认删除") }
        },
        content = {
            item { Button(onClick = actions.dismissDialog, enabled = !state.busy,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.filledTonalButtonColors()) { Text("取消") } }
        })
}
