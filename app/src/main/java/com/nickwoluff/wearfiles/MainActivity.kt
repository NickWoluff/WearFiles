package com.nickwoluff.wearfiles

import android.Manifest
import android.app.RemoteInput
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.webkit.MimeTypeMap
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.core.content.FileProvider
import androidx.wear.input.RemoteInputIntentHelper
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import androidx.navigation.NavController
import java.io.File
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private var resumeTick by mutableIntStateOf(0)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { FileManagerApp(this, resumeTick) }
    }
    override fun onResume() { super.onResume(); resumeTick++ }
}
enum class Page { HOME, FILES, TOOLS, ACCESS, ADB, DETAILS, SETTINGS, THEME, MOVE }
enum class NoticeKind { INFO, SUCCESS, ERROR }
data class WatchNotice(val id: Int, val message: String, val kind: NoticeKind)
private data class DirectoryQuery(val path: String, val hidden: Boolean, val newest: Boolean)
data class BrowserLocation(val page: Page, val path: String, val selectedPath: String? = null) {
    val key: String get() = "$page:$path:${selectedPath.orEmpty()}"
    val route: String get() = "screen/${page.name}/${Uri.encode(path)}?file=${Uri.encode(selectedPath.orEmpty())}"
}
data class BrowserState(
    val page: Page, val path: String, val root: String, val title: String,
    val access: Boolean, val canRequestAccess: Boolean, val loading: Boolean,
    val entries: List<FileEntry>, val error: String?, val space: StorageSpace,
    val hidden: Boolean, val newest: Boolean, val selected: FileEntry?, val settings: AppSettings,
    val busy: Boolean, val backLocation: BrowserLocation?, val shareTargets: List<ShareTarget> = emptyList(),
    val checkingAccess: Boolean = false, val accessCheck: StorageAccessCheck = StorageAccessCheck(),
    val adbCommands: String = ""
) {
    val location: BrowserLocation get() = BrowserLocation(page, if (page == Page.HOME) root else path,
        if (page == Page.DETAILS) selected?.path else null)
}
data class BrowserActions(
    val home: () -> Unit, val browse: (String) -> Unit, val select: (FileEntry) -> Unit,
    val tools: () -> Unit, val accessPage: () -> Unit, val requestAccess: () -> Unit,
    val refresh: () -> Unit, val create: () -> Unit, val toggleHidden: () -> Unit,
    val toggleOrder: () -> Unit, val openFile: () -> Unit, val shareFile: () -> Unit,
    val settingsPage: () -> Unit, val themePage: () -> Unit, val appearance: (AppSettings) -> Unit,
    val accentPicker: () -> Unit, val palettePicker: () -> Unit, val layoutPicker: () -> Unit,
    val operations: (FileEntry) -> Unit, val rename: () -> Unit, val move: () -> Unit,
    val confirmMove: () -> Unit, val askDelete: () -> Unit, val delete: () -> Unit,
    val dismissDialog: () -> Unit, val shareTo: (ShareTarget) -> Unit, val recheckAccess: () -> Unit,
    val adbGuide: () -> Unit, val copyAdbCommands: () -> Unit, val skipAccess: () -> Unit
)

@Composable
private fun FileManagerApp(activity: MainActivity, resumeTick: Int) {
    val repository = remember { StorageRepository(Environment.getExternalStorageDirectory()) }
    val nav = rememberSwipeDismissableNavController()
    val store = remember { SettingsStore(activity) }
    var settings by remember { mutableStateOf(store.read()) }
    var guideSkipped by remember { mutableStateOf(store.skippedStorageGuide()) }
    var authorizationReturned by rememberSaveable { mutableStateOf(false) }
    val adbCommands = remember { adbPermissionCommands(activity.packageName) }
    var page by rememberSaveable { mutableStateOf(Page.HOME) }
    var path by rememberSaveable { mutableStateOf(repository.root.absolutePath) }
    var hidden by rememberSaveable { mutableStateOf(false) }
    var newest by rememberSaveable { mutableStateOf(false) }
    var refresh by remember { mutableIntStateOf(0) }
    var listing by remember { mutableStateOf(DirectoryResult()) }
    var loading by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var loadedQuery by remember { mutableStateOf<DirectoryQuery?>(null) }
    val cache = remember { mutableStateMapOf<DirectoryQuery, DirectoryResult>() }
    val query = DirectoryQuery(path, hidden, newest)
    var space by remember { mutableStateOf(store.readSpace()) }
    var selected by rememberSaveable(stateSaver = androidx.compose.runtime.saveable.listSaver(
        save = { entry: FileEntry? -> entry?.let { listOf(it.path, it.name, it.directory, it.bytes, it.modified) } ?: emptyList() },
        restore = { if (it.isEmpty()) null else FileEntry(it[0] as String, it[1] as String, it[2] as Boolean, it[3] as Long, it[4] as Long) }
    )) { mutableStateOf<FileEntry?>(null) }
    var dialog by rememberSaveable { mutableStateOf<String?>(null) }
    var shareTargets by remember { mutableStateOf<List<ShareTarget>>(emptyList()) }
    var inputAction by rememberSaveable { mutableStateOf("create") }
    var inputPath by rememberSaveable { mutableStateOf(repository.root.path) }
    var moveSource by rememberSaveable { mutableStateOf<String?>(null) }
    var moveOrigin by rememberSaveable { mutableStateOf(repository.root.path) }
    val scope = rememberCoroutineScope()
    var notice by remember { mutableStateOf<WatchNotice?>(null) }
    var noticeId by remember { mutableIntStateOf(0) }
    fun notify(message: String, kind: NoticeKind = NoticeKind.INFO) {
        notice = WatchNotice(++noticeId, message, kind)
    }
    DisposableEffect(nav) {
        val listener = NavController.OnDestinationChangedListener { _, _, arguments ->
            arguments?.getString("kind")?.let { page = Page.valueOf(it) }
            arguments?.getString("directory")?.let { path = it }
            dialog = null
        }
        nav.addOnDestinationChangedListener(listener)
        onDispose { nav.removeOnDestinationChangedListener(listener) }
    }
    val permissionGranted = remember(resumeTick, refresh) { storageAccess(activity) }
    var accessCheck by remember { mutableStateOf(StorageAccessCheck()) }
    var checkingAccess by remember { mutableStateOf(true) }
    val access = permissionGranted && accessCheck.complete
    val accessIntent = remember {
        if (Build.VERSION.SDK_INT >= 30) listOf(
            Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${activity.packageName}")),
            Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
        ).firstOrNull { it.resolveActivity(activity.packageManager) != null } else null
    }
    val legacyPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { authorizationReturned = true; refresh++ }
    val systemSettings = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { authorizationReturned = true; refresh++ }
    fun invalidate() { cache.clear(); loadedQuery = null; refresh++ }
    fun perform(operation: suspend () -> FileOperationResult, onSuccess: () -> Unit = {}) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                val result = operation()
                notify(result.message, if (result.success) NoticeKind.SUCCESS else NoticeKind.ERROR)
                invalidate()
                if (result.success) onSuccess()
            } finally { busy = false }
        }
    }
    val inputLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val name = result.data?.let { RemoteInput.getResultsFromIntent(it)?.getCharSequence("name")?.toString() }
        if (name != null) {
            val source = inputPath
            if (inputAction == "rename") perform({ repository.rename(source, name) })
            else {
                busy = true
                scope.launch {
                    try {
                        val message = repository.createFolder(source, name)
                        notify(message, if (message.startsWith("已创建")) NoticeKind.SUCCESS else NoticeKind.ERROR)
                        invalidate()
                    } finally { busy = false }
                }
            }
        }
    }
    fun requestName(rename: Boolean) {
        if (busy) return
        dialog = null
        inputAction = if (rename) "rename" else "create"
        inputPath = if (rename) selected?.path ?: return else path
        val intent = RemoteInputIntentHelper.createActionRemoteInputIntent()
        RemoteInputIntentHelper.putRemoteInputsExtra(intent, listOf(RemoteInput.Builder("name")
            .setLabel(if (rename) "新名称" else "文件夹名称").build()))
        RemoteInputIntentHelper.putTitleExtra(intent, if (rename) "重命名 ${selected?.name}" else "新建文件夹")
        RemoteInputIntentHelper.putConfirmLabelExtra(intent, if (rename) "保存" else "创建")
        try { inputLauncher.launch(intent) } catch (_: ActivityNotFoundException) { notify("系统未提供手表文字输入", NoticeKind.ERROR) }
    }
    LaunchedEffect(path, hidden, newest, refresh, access, resumeTick) {
        loading = true
        try {
            space = repository.space()
            store.writeSpace(space)
            listing = if (access) repository.list(path, hidden, newest, Build.VERSION.SDK_INT >= 30)
                else DirectoryResult(error = "需要允许访问内部存储")
            loadedQuery = query
            if (access) {
                cache[query] = listing
                if (cache.size > 32) cache.keys.firstOrNull { it != query }?.let { cache.remove(it) }
            } else cache.clear()
            if ((page == Page.FILES || page == Page.MOVE) && listing.error != null) notify(requireNotNull(listing.error), NoticeKind.ERROR)
        } finally { loading = false }
    }
    fun go(destination: Page, directory: String = path, file: String? = null) {
        page = destination
        path = directory
        // 不使用 singleTop，确保不同目录拥有各自的返回栈和滚动状态。
        nav.navigate(BrowserLocation(destination, directory, file).route)
    }
    LaunchedEffect(permissionGranted, resumeTick, refresh) {
        checkingAccess = true
        try {
            accessCheck = if (permissionGranted) repository.verifyAccess() else StorageAccessCheck()
        } finally { checkingAccess = false }
    }
    // 跳过后不再自动打断使用；设置始终保留手动查看指引的入口。
    LaunchedEffect(checkingAccess, access, guideSkipped, authorizationReturned) {
        if (!checkingAccess && !access && (!guideSkipped || authorizationReturned)) {
            val destination = if ((Build.VERSION.SDK_INT >= 30 && accessIntent == null) ||
                authorizationReturned || accessCheck.error != null) Page.ADB else Page.ACCESS
            if (page != destination) go(destination)
        }
    }
    fun externalFile(share: Boolean, target: ShareTarget? = null) {
        val entry = selected ?: return
        try {
            val file = File(entry.path)
            if (!file.isFile || !file.canRead()) {
                notify("文件不存在或无法读取", NoticeKind.ERROR)
                return
            }
            val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.files", file)
            val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(entry.name.substringAfterLast('.', "").lowercase())
                ?: "application/octet-stream"
            if (share) {
                val intent = fileShareIntent(uri, mime)
                if (target == null) {
                    shareTargets = fileShareTargets(activity.packageManager, intent)
                    if (shareTargets.isEmpty()) notify("未安装支持分享此文件的应用", NoticeKind.ERROR)
                    else dialog = "share"
                } else {
                    dialog = null
                    activity.startActivity(intent.setComponent(target.component))
                }
            } else {
                activity.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
            }
        } catch (_: ActivityNotFoundException) {
            notify(if (share) "分享应用不可用" else "设备上没有支持此文件的应用", NoticeKind.ERROR)
        } catch (_: Exception) {
            notify(if (share) "无法分享此文件，请检查访问权限" else "无法打开此文件，请检查访问权限", NoticeKind.ERROR)
        }
    }
    // 返回先使用目录缓存保持滚动锚点，随后刷新真实内容。
    val visible = if (loadedQuery == query) listing else if (access) cache[query] else null
    val state = BrowserState(page, path, repository.root.path,
        when (page) {
            Page.HOME -> "嗷呜文件"
            Page.FILES -> if (path == repository.root.path) "内部存储" else File(path).name
            Page.TOOLS -> "目录操作"; Page.ACCESS -> "存储权限"; Page.DETAILS -> "文件详情"
            Page.SETTINGS -> "应用设置"; Page.THEME -> "主题设置"
            Page.ADB -> "ADB 授权指引"
            Page.MOVE -> if (path == repository.root.path) "移动到内部存储" else "移动到 ${File(path).name}"
        }, access, Build.VERSION.SDK_INT < 30 || accessIntent != null, loading || visible == null,
        visible?.entries.orEmpty(), visible?.error, space, hidden, newest, selected, settings, busy,
        nav.previousBackStackEntry?.arguments?.let { args ->
            BrowserLocation(Page.valueOf(args.getString("kind") ?: "HOME"), args.getString("directory") ?: repository.root.path,
                args.getString("file")?.takeIf { it.isNotEmpty() })
        }, shareTargets, checkingAccess, accessCheck, adbCommands)
    val actions = BrowserActions(
        home = { nav.popBackStack(BrowserLocation(Page.HOME, repository.root.path).route, false) },
        browse = {
            if (access && page == Page.HOME && it != repository.root.path) go(Page.FILES, repository.root.path)
            go(if (access) Page.FILES else Page.ACCESS, it)
        },
        select = {
            if (page == Page.MOVE) { if (it.directory) go(Page.MOVE, it.path) }
            else if (it.directory) go(Page.FILES, it.path)
            else { selected = it; go(Page.DETAILS, file = it.path) }
        },
        tools = { go(Page.TOOLS) }, accessPage = {
            go(if (guideSkipped || (Build.VERSION.SDK_INT >= 30 && accessIntent == null) ||
                (!access && authorizationReturned)) Page.ADB else Page.ACCESS)
        },
        requestAccess = {
            try {
                if (Build.VERSION.SDK_INT < 30) legacyPermission.launch(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE))
                else if (accessIntent != null) systemSettings.launch(accessIntent)
                else go(Page.ADB)
            } catch (_: ActivityNotFoundException) { authorizationReturned = true; go(Page.ADB) }
            catch (_: SecurityException) { authorizationReturned = true; go(Page.ADB) }
        }, refresh = { invalidate(); notify("已刷新") }, create = { requestName(false) },
        toggleHidden = { hidden = !hidden }, toggleOrder = { newest = !newest },
        openFile = { externalFile(false) }, shareFile = { externalFile(true) },
        settingsPage = { go(Page.SETTINGS) }, themePage = { go(Page.THEME) },
        appearance = { settings = it; store.write(it) },
        accentPicker = { dialog = "accent" }, palettePicker = { dialog = "palette" },
        layoutPicker = { dialog = "layout" },
        operations = { if (!busy) { selected = it; dialog = "operations" } },
        rename = { requestName(true) },
        move = { if (!busy) { moveSource = selected?.path; moveOrigin = path; dialog = null; go(Page.MOVE, repository.root.path) } },
        confirmMove = {
            val source = moveSource
            val destination = path
            if (source != null) perform({ repository.move(source, destination) }) {
                nav.popBackStack(BrowserLocation(Page.FILES, moveOrigin).route, false)
                moveSource = null
            }
        },
        askDelete = { dialog = "delete" },
        delete = { selected?.path?.let { target -> dialog = null; perform({ repository.delete(target) }) } },
        dismissDialog = { dialog = null }, shareTo = { externalFile(true, it) },
        recheckAccess = { refresh++ }, adbGuide = { go(Page.ADB) },
        copyAdbCommands = {
            val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("嗷呜文件 ADB 授权", adbCommands))
            notify("已复制全部三条命令", NoticeKind.SUCCESS)
        }, skipAccess = {
            guideSkipped = true
            authorizationReturned = false
            store.skipStorageGuide()
            nav.popBackStack(BrowserLocation(Page.HOME, repository.root.path).route, false)
            refresh++
        }
    )
    WatchApp(state, actions, nav, dialog, notice) { id -> if (notice?.id == id) notice = null }
}
private fun storageAccess(activity: MainActivity): Boolean = if (Build.VERSION.SDK_INT >= 30)
    Environment.isExternalStorageManager() else
    activity.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED &&
        activity.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
