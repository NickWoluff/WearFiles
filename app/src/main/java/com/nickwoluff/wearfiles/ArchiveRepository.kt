package com.nickwoluff.wearfiles

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import java.util.zip.ZipFile
import org.apache.commons.compress.archivers.ArchiveOutputStream
import org.apache.commons.compress.archivers.ar.ArArchiveInputStream
import org.apache.commons.compress.archivers.arj.ArjArchiveInputStream
import org.apache.commons.compress.archivers.cpio.CpioArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile
import org.apache.commons.compress.compressors.CompressorStreamFactory
import org.apache.commons.compress.compressors.CompressorOutputStream
import com.github.junrar.Archive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val ARCHIVE_MARKER = "!/"

internal enum class ArchiveType { ZIP, TAR, SEVEN_Z, RAR, ARJ, CPIO, AR, COMPRESSED_SINGLE, UNSUPPORTED }

enum class ArchiveFormat(val label: String, val suffix: String, val singleFileOnly: Boolean = false) {
    ZIP("ZIP", ".zip"), SEVEN_Z("7z", ".7z"), TAR("TAR", ".tar"),
    TAR_GZ("TAR.GZ", ".tar.gz"), TAR_BZ2("TAR.BZ2", ".tar.bz2"), TAR_XZ("TAR.XZ", ".tar.xz"),
    TAR_LZMA("TAR.LZMA", ".tar.lzma"),
    GZIP("GZIP（单文件）", ".gz", true), BZIP2("BZIP2（单文件）", ".bz2", true),
    XZ("XZ（单文件）", ".xz", true), LZMA("LZMA（单文件）", ".lzma", true)
}

internal fun archiveType(name: String): ArchiveType = when {
    name.endsWith(".tar.gz", true) || name.endsWith(".tgz", true) || name.endsWith(".tar.bz2", true) ||
        name.endsWith(".tbz2", true) || name.endsWith(".tar.xz", true) || name.endsWith(".txz", true) ||
        name.endsWith(".tar.lzma", true) || name.endsWith(".tlz", true) || name.endsWith(".tar.z", true) ||
        name.endsWith(".taz", true) -> ArchiveType.TAR
    name.substringAfterLast('.', "").lowercase() in setOf("zip", "jar", "apk", "apks", "xapk", "aar", "war", "ear", "cbz") -> ArchiveType.ZIP
    name.endsWith(".tar", true) -> ArchiveType.TAR
    name.endsWith(".7z", true) -> ArchiveType.SEVEN_Z
    name.endsWith(".rar", true) || name.endsWith(".cbr", true) -> ArchiveType.RAR
    name.endsWith(".arj", true) -> ArchiveType.ARJ
    name.endsWith(".cpio", true) -> ArchiveType.CPIO
    name.endsWith(".ar", true) -> ArchiveType.AR
    name.endsWith(".gz", true) || name.endsWith(".gzip", true) || name.endsWith(".bz2", true) ||
        name.endsWith(".xz", true) || name.endsWith(".lzma", true) || name.endsWith(".z", true) -> ArchiveType.COMPRESSED_SINGLE
    else -> ArchiveType.UNSUPPORTED
}

internal fun isArchiveName(name: String): Boolean = archiveType(name) != ArchiveType.UNSUPPORTED

internal data class ArchiveLocation(val archive: String, val directory: String)

internal fun archiveLocation(path: String): ArchiveLocation? {
    if (!path.startsWith("archive:")) return null
    val value = path.removePrefix("archive:")
    val marker = value.indexOf(ARCHIVE_MARKER)
    if (marker < 0) return null
    return ArchiveLocation(value.substring(0, marker), value.substring(marker + ARCHIVE_MARKER.length))
}

internal fun archivePath(archive: String, directory: String = ""): String =
    "archive:$archive$ARCHIVE_MARKER${directory.trim('/')}${if (directory.isNotEmpty()) "/" else ""}"

/** 归档目录按需扫描；浏览时不向存储写出归档内容。 */
class ArchiveRepository {
    suspend fun list(path: String, hidden: Boolean, newest: Boolean): DirectoryResult = withContext(Dispatchers.IO) {
        val location = archiveLocation(path) ?: return@withContext DirectoryResult(error = "压缩包位置无效")
        val archive = File(location.archive)
        if (!archive.isFile || !archive.canRead()) return@withContext DirectoryResult(error = "压缩包不存在或无法读取")
        try {
            val prefix = location.directory.trim('/').let { if (it.isEmpty()) "" else "$it/" }
            val children = linkedMapOf<String, FileEntry>()
            fun addEntry(raw: String, isDirectory: Boolean, size: Long, modified: Long) {
                val name = safeArchiveName(raw) ?: return
                if (!name.startsWith(prefix) || name == prefix) return
                val remainder = name.removePrefix(prefix)
                val childName = remainder.substringBefore('/')
                if (childName.isEmpty() || (!hidden && childName.startsWith('.'))) return
                val directory = isDirectory || remainder.contains('/')
                val childPath = archivePath(archive.absolutePath, prefix + childName)
                val previous = children[childName]
                children[childName] = FileEntry(childPath, childName, directory,
                    if (directory) 0 else size.coerceAtLeast(0), modified.coerceAtLeast(0), inArchive = true)
                if (previous?.directory == true && !directory) children[childName] = previous
            }
            when (archiveType(archive.name)) {
                ArchiveType.ZIP -> ZipFile(archive).use { input ->
                    input.entries().asSequence().forEach { entry ->
                        addEntry(entry.name, entry.isDirectory, entry.size, entry.time)
                    }
                }
                ArchiveType.TAR -> {
                    val raw = BufferedInputStream(FileInputStream(archive))
                    val decompressed = if (archive.name.endsWith(".tar", true)) raw else CompressorStreamFactory().createCompressorInputStream(raw)
                    TarArchiveInputStream(decompressed).use { input ->
                    while (true) {
                        val entry = input.nextTarEntry ?: break
                        if (!entry.isFile && !entry.isDirectory) continue
                        addEntry(entry.name, entry.isDirectory, entry.size, entry.lastModifiedDate.time)
                    }
                    }
                }
                ArchiveType.SEVEN_Z -> SevenZFile(archive).use { input ->
                    while (true) {
                        val entry = input.nextEntry ?: break
                        addEntry(entry.name, entry.isDirectory, entry.size, entry.lastModifiedDate?.time ?: 0)
                    }
                }
                ArchiveType.RAR -> Archive(archive).use { input ->
                    while (true) {
                        val entry = input.nextFileHeader() ?: break
                        if (entry.redirection != null) continue
                        addEntry(entry.fileName, entry.isDirectory, entry.unpSize, entry.mTime?.time ?: 0)
                    }
                }
                ArchiveType.ARJ -> ArjArchiveInputStream(FileInputStream(archive)).use { input ->
                    while (true) {
                        val entry = input.nextEntry ?: break
                        if (input.canReadEntryData(entry)) addEntry(entry.name, entry.isDirectory, entry.size, entry.lastModifiedDate.time)
                    }
                }
                ArchiveType.CPIO -> CpioArchiveInputStream(FileInputStream(archive)).use { input ->
                    while (true) {
                        val entry = input.nextCPIOEntry ?: break
                        if (entry.isRegularFile || entry.isDirectory) addEntry(entry.name, entry.isDirectory, entry.size, entry.lastModifiedDate.time)
                    }
                }
                ArchiveType.AR -> ArArchiveInputStream(FileInputStream(archive)).use { input ->
                    while (true) {
                        val entry = input.nextArEntry ?: break
                        addEntry(entry.name, false, entry.length, entry.lastModifiedDate.time)
                    }
                }
                ArchiveType.COMPRESSED_SINGLE -> if (prefix.isEmpty()) {
                    val outputName = archive.name.substringBeforeLast('.').ifEmpty { archive.name }
                    addEntry(outputName, false, -1, archive.lastModified())
                }
                else -> return@withContext DirectoryResult(error = "此格式暂不支持浏览")
            }
            val comparator = if (newest) compareByDescending<FileEntry> { it.directory }.thenByDescending { it.modified }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }
            else compareByDescending<FileEntry> { it.directory }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }
            DirectoryResult(children.values.sortedWith(comparator))
        } catch (_: Exception) { DirectoryResult(error = "压缩包损坏、加密或格式暂不支持") }
    }

    suspend fun compress(sourcePath: String, format: ArchiveFormat = ArchiveFormat.ZIP): FileOperationResult = withContext(Dispatchers.IO) {
        var partial: File? = null
        try {
            val source = File(sourcePath).canonicalFile
            require(source.exists() && source.parentFile != null) { "文件或文件夹不存在" }
            require(!format.singleFileOnly || source.isFile) { "GZIP、BZIP2 和 XZ 仅支持压缩单个文件" }
            val target = File(source.parentFile, "${source.name}${format.suffix}")
            require(!target.exists()) { "目标压缩包已存在" }
            partial = target
            val rootName = if (source.isDirectory) source.name else source.name
            fun children(file: File): List<File> {
                require(file.canonicalFile == file.absoluteFile) { "不支持压缩包含符号链接的项目" }
                return if (file.isDirectory) file.listFiles()?.sortedBy { it.name }
                    ?: throw IOException("无法读取文件夹") else emptyList()
            }
            fun walk(file: File, relative: String, add: (File, String) -> Unit) {
                add(file, relative)
                children(file).forEach { walk(it, "$relative/${it.name}", add) }
            }
            when (format) {
                ArchiveFormat.ZIP -> ZipOutputStream(BufferedOutputStream(FileOutputStream(target))).use { output ->
                    walk(source, rootName) { file, relative ->
                        val entryName = if (file.isDirectory) "$relative/" else relative
                        if (file.isDirectory && children(file).isNotEmpty()) return@walk
                        output.putNextEntry(ZipEntry(entryName).apply { time = file.lastModified() })
                        if (file.isFile) FileInputStream(file).use { it.copyTo(output) }
                        output.closeEntry()
                    }
                }
                ArchiveFormat.SEVEN_Z -> SevenZOutputFile(target).use { output ->
                    walk(source, rootName) { file, relative ->
                        if (file.isDirectory && children(file).isNotEmpty()) return@walk
                        val entry = output.createArchiveEntry(file, relative)
                        output.putArchiveEntry(entry)
                        if (file.isFile) FileInputStream(file).use { output.write(it) }
                        output.closeArchiveEntry()
                    }
                }
                ArchiveFormat.TAR, ArchiveFormat.TAR_GZ, ArchiveFormat.TAR_BZ2, ArchiveFormat.TAR_XZ, ArchiveFormat.TAR_LZMA -> {
                    val fileOut = BufferedOutputStream(FileOutputStream(target))
                    val compressed = when (format) {
                        ArchiveFormat.TAR -> fileOut
                        ArchiveFormat.TAR_GZ -> CompressorStreamFactory().createCompressorOutputStream(CompressorStreamFactory.GZIP, fileOut)
                        ArchiveFormat.TAR_BZ2 -> CompressorStreamFactory().createCompressorOutputStream(CompressorStreamFactory.BZIP2, fileOut)
                        ArchiveFormat.TAR_XZ -> CompressorStreamFactory().createCompressorOutputStream(CompressorStreamFactory.XZ, fileOut)
                        else -> CompressorStreamFactory().createCompressorOutputStream(CompressorStreamFactory.LZMA, fileOut)
                    }
                    TarArchiveOutputStream(compressed).use { output ->
                        walk(source, rootName) { file, relative ->
                            if (file.isDirectory && children(file).isNotEmpty()) return@walk
                            val entry = TarArchiveEntry(file, relative).apply { setModTime(file.lastModified()) }
                            output.putArchiveEntry(entry)
                            if (file.isFile) FileInputStream(file).use { it.copyTo(output) }
                            output.closeArchiveEntry()
                        }
                    }
                }
                ArchiveFormat.GZIP, ArchiveFormat.BZIP2, ArchiveFormat.XZ, ArchiveFormat.LZMA -> {
                    val compressor = when (format) {
                        ArchiveFormat.GZIP -> CompressorStreamFactory.GZIP
                        ArchiveFormat.BZIP2 -> CompressorStreamFactory.BZIP2
                        ArchiveFormat.XZ -> CompressorStreamFactory.XZ
                        else -> CompressorStreamFactory.LZMA
                    }
                    CompressorStreamFactory().createCompressorOutputStream(compressor,
                        BufferedOutputStream(FileOutputStream(target))).use { output ->
                        FileInputStream(source).use { it.copyTo(output) }
                    }
                }
            }
            FileOperationResult(true, "已创建 ${target.name}")
        } catch (e: Exception) {
            partial?.delete()
            FileOperationResult(false, e.message ?: "压缩失败")
        }
    }

    suspend fun extract(archivePath: String, destinationPath: String): FileOperationResult = withContext(Dispatchers.IO) {
        val source = File(archivePath).canonicalFile
        val destination = File(destinationPath).canonicalFile
        val staging = File(destination, ".awoo-extract-${System.nanoTime()}")
        val moved = mutableListOf<File>()
        try {
            require(source.isFile && archiveType(source.name) != ArchiveType.UNSUPPORTED) { "不支持此压缩格式" }
            require(destination.isDirectory && destination.canWrite()) { "当前目录不可写" }
            require(staging.mkdir()) { "无法创建临时目录" }
            fun writeEntry(name: String, directory: Boolean, copy: (File) -> Unit) {
                val clean = safeArchiveName(name) ?: throw IOException("压缩包包含不安全的路径")
                val output = File(staging, clean).canonicalFile
                require(output.path.startsWith(staging.path + File.separator) || output == staging) { "压缩包包含越界路径" }
                if (directory) output.mkdirs() else { output.parentFile?.mkdirs(); copy(output) }
            }
            when (archiveType(source.name)) {
                ArchiveType.ZIP -> ZipFile(source).use { input ->
                    input.entries().asSequence().forEach { entry ->
                        writeEntry(entry.name, entry.isDirectory) { output ->
                            input.getInputStream(entry).use { data -> BufferedOutputStream(FileOutputStream(output)).use { data.copyTo(it) } }
                        }
                    }
                }
                ArchiveType.TAR -> {
                    val raw = BufferedInputStream(FileInputStream(source))
                    val decompressed = if (source.name.endsWith(".tar", true)) raw else CompressorStreamFactory().createCompressorInputStream(raw)
                    TarArchiveInputStream(decompressed).use { input ->
                    while (true) {
                        val entry = input.nextTarEntry ?: break
                        require(entry.isFile || entry.isDirectory) { "不支持解压 TAR 链接或特殊项目" }
                        writeEntry(entry.name, entry.isDirectory) { output -> BufferedOutputStream(FileOutputStream(output)).use { input.copyTo(it) } }
                    }
                    }
                }
                ArchiveType.SEVEN_Z -> SevenZFile(source).use { input ->
                    while (true) {
                        val entry = input.nextEntry ?: break
                        writeEntry(entry.name, entry.isDirectory) { output -> BufferedOutputStream(FileOutputStream(output)).use { out ->
                            val buffer = ByteArray(8192); var count: Int
                            while (input.read(buffer).also { count = it } > 0) out.write(buffer, 0, count)
                        } }
                    }
                }
                ArchiveType.RAR -> Archive(source).use { input ->
                    while (true) {
                        val entry = input.nextFileHeader() ?: break
                        require(entry.redirection == null) { "不支持解压 RAR 链接项目" }
                        writeEntry(entry.fileName, entry.isDirectory) { output ->
                            input.getInputStream(entry).use { data -> BufferedOutputStream(FileOutputStream(output)).use { data.copyTo(it) } }
                        }
                    }
                }
                ArchiveType.ARJ -> ArjArchiveInputStream(FileInputStream(source)).use { input ->
                    while (true) {
                        val entry = input.nextEntry ?: break
                        require(input.canReadEntryData(entry)) { "ARJ 中包含不支持的数据" }
                        writeEntry(entry.name, entry.isDirectory) { output -> BufferedOutputStream(FileOutputStream(output)).use { input.copyTo(it) } }
                    }
                }
                ArchiveType.CPIO -> CpioArchiveInputStream(FileInputStream(source)).use { input ->
                    while (true) {
                        val entry = input.nextCPIOEntry ?: break
                        require(entry.isRegularFile || entry.isDirectory) { "不支持解压 CPIO 特殊项目" }
                        writeEntry(entry.name, entry.isDirectory) { output -> BufferedOutputStream(FileOutputStream(output)).use { input.copyTo(it) } }
                    }
                }
                ArchiveType.AR -> ArArchiveInputStream(FileInputStream(source)).use { input ->
                    while (true) {
                        val entry = input.nextArEntry ?: break
                        writeEntry(entry.name, false) { output -> BufferedOutputStream(FileOutputStream(output)).use { input.copyTo(it) } }
                    }
                }
                ArchiveType.COMPRESSED_SINGLE -> {
                    val raw = BufferedInputStream(FileInputStream(source))
                    CompressorStreamFactory().createCompressorInputStream(raw).use { input ->
                        val outputName = source.name.substringBeforeLast('.').ifEmpty { source.name }
                        writeEntry(outputName, false) { output -> BufferedOutputStream(FileOutputStream(output)).use { input.copyTo(it) } }
                    }
                }
                else -> throw IOException("不支持此压缩格式")
            }
            val children = staging.listFiles().orEmpty()
            require(children.none { File(destination, it.name).exists() }) { "目标位置存在同名项目，未覆盖任何文件" }
            children.forEach { child ->
                val target = File(destination, child.name)
                require(child.renameTo(target)) { "写入目标目录失败" }
                moved.add(target)
            }
            staging.delete()
            FileOperationResult(true, "已解压到当前目录")
        } catch (e: Exception) {
            moved.asReversed().forEach { it.deleteRecursively() }
            staging.deleteRecursively()
            FileOperationResult(false, e.message ?: "解压失败")
        }
    }

    suspend fun extractFile(entryPath: String, destinationPath: String): FileOperationResult = withContext(Dispatchers.IO) {
        val location = archiveLocation(entryPath) ?: return@withContext FileOperationResult(false, "归档文件位置无效")
        val entryName = location.directory.trimEnd('/')
        val archive = File(location.archive)
        val destination = File(destinationPath, entryName.substringAfterLast('/')).canonicalFile
        try {
            require(safeArchiveName(entryName) == entryName) { "归档文件路径无效" }
            require(!destination.exists()) { "目标位置已存在同名文件" }
            val temp = File.createTempFile("awoo-entry-", ".tmp", destination.parentFile)
            try {
                when (archiveType(archive.name)) {
                    ArchiveType.ZIP -> ZipFile(archive).use { input ->
                        val item = input.entries().asSequence().firstOrNull { safeArchiveName(it.name) == entryName && !it.isDirectory }
                            ?: throw IOException("归档中找不到此文件")
                        input.getInputStream(item).use { data -> FileOutputStream(temp).use { data.copyTo(it) } }
                    }
                    ArchiveType.TAR -> {
                        val raw = BufferedInputStream(FileInputStream(archive))
                        val decompressed = if (archive.name.endsWith(".tar", true)) raw else CompressorStreamFactory().createCompressorInputStream(raw)
                        TarArchiveInputStream(decompressed).use { input ->
                        var found = false
                        while (true) {
                            val item = input.nextTarEntry ?: break
                            if (safeArchiveName(item.name) == entryName && !item.isDirectory) {
                                FileOutputStream(temp).use { input.copyTo(it) }; found = true; break
                            }
                        }
                        require(found) { "归档中找不到此文件" }
                        }
                    }
                    ArchiveType.SEVEN_Z -> SevenZFile(archive).use { input ->
                        val item = generateSequence { input.nextEntry }.firstOrNull { safeArchiveName(it.name) == entryName && !it.isDirectory }
                            ?: throw IOException("归档中找不到此文件")
                        FileOutputStream(temp).use { output ->
                            val buffer = ByteArray(8192); var count: Int
                            while (input.read(buffer).also { count = it } > 0) output.write(buffer, 0, count)
                        }
                    }
                    ArchiveType.RAR -> Archive(archive).use { input ->
                        var found = false
                        while (true) {
                            val item = input.nextFileHeader() ?: break
                            if (safeArchiveName(item.fileName) == entryName && !item.isDirectory && item.redirection == null) {
                                input.getInputStream(item).use { data -> FileOutputStream(temp).use { data.copyTo(it) } }
                                found = true
                                break
                            }
                        }
                        require(found) { "归档中找不到此文件" }
                    }
                    ArchiveType.ARJ -> ArjArchiveInputStream(FileInputStream(archive)).use { input ->
                        var found = false
                        while (true) {
                            val item = input.nextEntry ?: break
                            if (safeArchiveName(item.name) == entryName && !item.isDirectory && input.canReadEntryData(item)) {
                                FileOutputStream(temp).use { input.copyTo(it) }; found = true; break
                            }
                        }
                        require(found) { "归档中找不到此文件" }
                    }
                    ArchiveType.CPIO -> CpioArchiveInputStream(FileInputStream(archive)).use { input ->
                        var found = false
                        while (true) {
                            val item = input.nextCPIOEntry ?: break
                            if (safeArchiveName(item.name) == entryName && item.isRegularFile) {
                                FileOutputStream(temp).use { input.copyTo(it) }; found = true; break
                            }
                        }
                        require(found) { "归档中找不到此文件" }
                    }
                    ArchiveType.AR -> ArArchiveInputStream(FileInputStream(archive)).use { input ->
                        var found = false
                        while (true) {
                            val item = input.nextArEntry ?: break
                            if (safeArchiveName(item.name) == entryName) {
                                FileOutputStream(temp).use { input.copyTo(it) }; found = true; break
                            }
                        }
                        require(found) { "归档中找不到此文件" }
                    }
                    ArchiveType.COMPRESSED_SINGLE -> {
                        val expectedName = archive.name.substringBeforeLast('.').ifEmpty { archive.name }
                        require(entryName == expectedName) { "归档中找不到此文件" }
                        val raw = BufferedInputStream(FileInputStream(archive))
                        CompressorStreamFactory().createCompressorInputStream(raw).use { input -> FileOutputStream(temp).use { input.copyTo(it) } }
                    }
                    else -> throw IOException("该格式暂不支持单文件提取")
                }
                require(temp.renameTo(destination)) { "无法写入目标目录" }
                FileOperationResult(true, "已提取 ${destination.name}")
            } finally { if (temp.exists()) temp.delete() }
        } catch (e: Exception) { FileOperationResult(false, e.message ?: "提取失败") }
    }

    suspend fun materialize(entryPath: String, cacheDir: File): File? = withContext(Dispatchers.IO) {
        val location = archiveLocation(entryPath) ?: return@withContext null
        val archive = File(location.archive)
        if (!archive.isFile) return@withContext null
        val targetName = location.directory.trimEnd('/').substringAfterLast('/')
        if (safeArchiveName(targetName) != targetName) return@withContext null
        val identity = "${archive.canonicalPath}:${archive.lastModified()}:${archive.length()}:${location.directory}"
        val digest = MessageDigest.getInstance("SHA-256").digest(identity.toByteArray())
            .take(12).joinToString("") { "%02x".format(it) }
        val previewDir = File(cacheDir, "archive-preview-$digest")
        if (!previewDir.isDirectory && !previewDir.mkdir()) return@withContext null
        val cached = File(previewDir, targetName)
        if (cached.isFile) return@withContext cached
        if (!extractFile(entryPath, previewDir.path).success) return@withContext null
        cached.takeIf { it.isFile }
    }
}

private fun safeArchiveName(name: String): String? {
    if (name.isEmpty() || name.contains('\u0000') || name.startsWith('/') || name.startsWith('\\') || Regex("^[A-Za-z]:").containsMatchIn(name)) return null
    val normalized = name.replace('\\', '/')
    val parts = normalized.split('/')
    if (parts.any { it == ".." || it == "." || it.isEmpty() && parts.last() != "" }) return null
    return normalized
}
