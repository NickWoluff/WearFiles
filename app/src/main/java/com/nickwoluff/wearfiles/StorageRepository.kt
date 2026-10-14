package com.nickwoluff.wearfiles

import java.io.File
import java.io.IOException
import java.text.DecimalFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class FileEntry(val path: String, val name: String, val directory: Boolean, val bytes: Long, val modified: Long)
data class DirectoryResult(val entries: List<FileEntry> = emptyList(), val error: String? = null)
data class StorageSpace(val available: Long = 0, val total: Long = 0)
data class FileOperationResult(val success: Boolean, val message: String)
data class StorageAccessCheck(val readable: Boolean = false, val writable: Boolean = false, val error: String? = null) {
    val complete: Boolean get() = readable && writable && error == null
}

/** 共享存储的所有磁盘操作都在 IO 线程执行，读取失败不会冒充空目录。 */
class StorageRepository(val root: File) {
    /** 用独占临时文件验证创建、读取、删除；不以目录可见或权限标志代替实际读写能力。 */
    suspend fun verifyAccess(): StorageAccessCheck = withContext(Dispatchers.IO) {
        var probe: File? = null
        var readable = false
        var writable = false
        var error: String? = null
        try {
            readable = root.listFiles() != null
            if (!readable) return@withContext StorageAccessCheck(error = "无法读取共享存储")
            probe = File.createTempFile(".aowu-access-", ".tmp", root)
            val content = byteArrayOf(65, 79, 87, 85)
            probe.writeBytes(content)
            writable = true
            readable = probe.readBytes().contentEquals(content)
            if (!readable) error = "共享存储文件读取检查失败"
        } catch (_: Exception) {
            error = if (readable && !writable) "共享存储写入检查失败，请检查授权或剩余空间" else "共享存储读写检查失败"
        } finally {
            try {
                probe?.let { if (it.exists() && !it.delete()) error = "临时文件删除检查失败，请检查写入权限" }
            } catch (_: Exception) { error = "临时文件删除检查失败，请检查写入权限" }
        }
        StorageAccessCheck(readable, writable, error)
    }

    private fun withinRoot(file: File): Boolean {
        val base = root.canonicalPath
        val path = file.canonicalPath
        return path == base || path.startsWith(base + File.separator)
    }

    suspend fun space(): StorageSpace = withContext(Dispatchers.IO) {
        StorageSpace(root.usableSpace, root.totalSpace)
    }

    suspend fun list(path: String, hidden: Boolean, newest: Boolean, restrictedAndroid: Boolean): DirectoryResult =
        withContext(Dispatchers.IO) {
            try {
                val folder = File(path)
                if (!withinRoot(folder)) return@withContext DirectoryResult(error = "此位置不属于共享存储")
                if (restrictedAndroid && restricted(folder)) {
                    return@withContext DirectoryResult(error = "Android 限制访问其他应用的 data 和 obb 目录")
                }
                val files = folder.listFiles() ?: return@withContext DirectoryResult(error =
                    if (!folder.exists()) "此目录已不存在" else "无法读取此目录，请检查存储权限")
                val entries = files.filter { hidden || !it.name.startsWith('.') }.map {
                    FileEntry(it.absolutePath, it.name, it.isDirectory, if (it.isFile) it.length() else 0, it.lastModified())
                }
                val order = if (newest) compareByDescending<FileEntry> { it.directory }.thenByDescending { it.modified }
                    .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }
                else compareByDescending<FileEntry> { it.directory }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }
                DirectoryResult(entries.sortedWith(order))
            } catch (_: SecurityException) {
                DirectoryResult(error = "系统拒绝读取此目录")
            } catch (_: IOException) {
                DirectoryResult(error = "存储读取失败，请重试")
            }
        }

    suspend fun createFolder(parent: String, name: String): String = withContext(Dispatchers.IO) {
        val clean = name.trim()
        if (!validFolderName(clean)) return@withContext "名称不能为空，也不能包含路径分隔符"
        try {
            val folder = File(parent, clean)
            when {
                !withinRoot(folder) -> "此位置不属于共享存储"
                restricted(folder) -> "系统限制修改 Android/data 和 obb 目录"
                folder.exists() -> "已存在同名文件或文件夹"
                folder.mkdir() -> "已创建 $clean"
                else -> "无法创建文件夹，请检查目录写入权限"
            }
        } catch (_: Exception) { "创建失败，请检查目录写入权限" }
    }

    private fun operationFile(path: String): File {
        val original = File(path).absoluteFile
        val file = original.canonicalFile
        require(file == File(original.parentFile?.canonicalFile, original.name)) { "不能修改符号链接指向的项目" }
        require(withinRoot(file) && file != root.canonicalFile) { "不能修改共享存储根目录或外部位置" }
        require(!restricted(file)) { "系统限制修改 Android/data 和 obb 目录" }
        require(file.exists()) { "文件或文件夹已不存在" }
        return file
    }

    private fun restricted(file: File): Boolean {
        val relative = file.canonicalPath.removePrefix(root.canonicalPath).trimStart(File.separatorChar).replace(File.separatorChar, '/')
        return relative == "Android/data" || relative.startsWith("Android/data/") ||
            relative == "Android/obb" || relative.startsWith("Android/obb/")
    }

    suspend fun rename(path: String, name: String): FileOperationResult = withContext(Dispatchers.IO) {
        try {
            val clean = name.trim()
            require(validFolderName(clean)) { "名称不能为空，也不能包含路径分隔符" }
            val source = operationFile(path)
            val target = File(source.parentFile, clean)
            require(withinRoot(target) && !restricted(target)) { "此位置不允许修改" }
            require(source != target) { "名称没有变化" }
            require(!target.exists()) { "已存在同名文件或文件夹" }
            if (source.renameTo(target)) FileOperationResult(true, "已重命名为 $clean")
            else FileOperationResult(false, "重命名失败，请检查写入权限")
        } catch (e: Exception) { FileOperationResult(false, e.message ?: "重命名失败") }
    }

    suspend fun move(path: String, destination: String): FileOperationResult = withContext(Dispatchers.IO) {
        try {
            val source = operationFile(path)
            val folder = File(destination).canonicalFile
            require(withinRoot(folder) && !restricted(folder) && folder.isDirectory) { "目标目录不可用" }
            require(folder != source && !folder.path.startsWith(source.path + File.separator)) { "不能移动到自身或子目录" }
            val target = File(folder, source.name)
            require(target != source) { "文件已在此目录中" }
            require(!target.exists()) { "目标目录已存在同名项目" }
            if (source.renameTo(target)) FileOperationResult(true, "已移动到 ${folder.name}")
            else FileOperationResult(false, "移动失败，请检查目标目录写入权限")
        } catch (e: Exception) { FileOperationResult(false, e.message ?: "移动失败") }
    }

    suspend fun delete(path: String): FileOperationResult = withContext(Dispatchers.IO) {
        try {
            val source = operationFile(path)
            val pending = mutableListOf<File>()
            // 先检查完整目录树，拒绝受限路径和符号链接，避免遍历到目标之外。
            fun collect(file: File) {
                require(withinRoot(file) && !restricted(file)) { "目录包含不允许修改的位置" }
                if (file.isDirectory) {
                    val children = file.listFiles() ?: throw IOException("无法读取文件夹内容")
                    for (child in children) {
                        require(child.canonicalFile == child.absoluteFile) { "目录包含符号链接，无法安全删除" }
                        collect(child)
                    }
                }
                pending.add(file)
            }
            collect(source)
            for (file in pending) if (!file.delete())
                return@withContext FileOperationResult(false, "删除未完成，请检查写入权限并刷新目录")
            FileOperationResult(true, "已删除 ${source.name}")
        } catch (e: Exception) { FileOperationResult(false, e.message ?: "删除失败") }
    }
}

internal fun validFolderName(name: String): Boolean = name.isNotBlank() && name != "." && name != ".." &&
    name.none { it == '/' || it == '\\' || it == '\u0000' }

internal fun fileSize(bytes: Long): String {
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    var value = bytes.coerceAtLeast(0).toDouble()
    var unit = 0
    while (value >= 1024 && unit < units.lastIndex) { value /= 1024; unit++ }
    return "${DecimalFormat("0.#").format(value)} ${units[unit]}"
}
