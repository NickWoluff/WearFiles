package com.nickwoluff.wearfiles

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.apache.commons.compress.archivers.ar.ArArchiveEntry
import org.apache.commons.compress.archivers.ar.ArArchiveOutputStream
import org.apache.commons.compress.archivers.cpio.CpioArchiveEntry
import org.apache.commons.compress.archivers.cpio.CpioArchiveOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ArchiveRepositoryTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun zipCanBeBrowsedAsVirtualDirectoriesWithoutExtracting() = runBlocking {
        val root = temp.newFolder("browse")
        val zip = File(root, "sample.zip")
        ZipOutputStream(zip.outputStream()).use { out ->
            out.putNextEntry(ZipEntry("Docs/")); out.closeEntry()
            out.putNextEntry(ZipEntry("Docs/你好.txt")); out.write("内容".toByteArray()); out.closeEntry()
            out.putNextEntry(ZipEntry("root.txt")); out.write(byteArrayOf(1, 2)); out.closeEntry()
        }
        val repo = ArchiveRepository()
        val listing = repo.list(archivePath(zip.path), false, false)
        assertNull(listing.error)
        assertEquals(listOf("Docs", "root.txt"), listing.entries.map { it.name })
        assertTrue(listing.entries.first().directory)
        val nested = repo.list(listing.entries.first().path, false, false)
        assertEquals(listOf("你好.txt"), nested.entries.map { it.name })
        assertTrue(nested.entries.single().inArchive)
        assertEquals(listOf("sample.zip"), root.listFiles()!!.map { it.name })
    }

    @Test fun compressesAndSafelyExtractsWithoutOverwriting() = runBlocking {
        val root = temp.newFolder("operations")
        val folder = File(root, "文件夹").apply { mkdir() }
        File(folder, "hello.txt").writeText("你好")
        val repo = ArchiveRepository()
        assertTrue(repo.compress(folder.path).success)
        val archive = File(root, "文件夹.zip")
        assertTrue(archive.isFile)
        val destination = File(root, "destination").apply { mkdir() }
        assertTrue(repo.extract(archive.path, destination.path).success)
        assertEquals("你好", File(destination, "文件夹/hello.txt").readText())
        assertFalse(repo.extract(archive.path, destination.path).success)
        assertEquals("你好", File(root, "文件夹/hello.txt").readText())
    }

    @Test fun rejectsZipSlipEntriesBeforeWritingOutsideDestination() = runBlocking {
        val root = temp.newFolder("zip-slip")
        val destination = File(root, "dest").apply { mkdir() }
        val archive = File(root, "malicious.zip")
        ZipOutputStream(archive.outputStream()).use { out ->
            out.putNextEntry(ZipEntry("../escaped.txt")); out.write("bad".toByteArray()); out.closeEntry()
        }
        assertFalse(ArchiveRepository().extract(archive.path, destination.path).success)
        assertFalse(File(root, "escaped.txt").exists())
        assertTrue(destination.listFiles().isNullOrEmpty())
    }

    @Test fun virtualArchivePathRoundTripsNestedDirectories() {
        val path = archivePath("/storage/emulated/0/文档.zip", "a/b")
        assertEquals(ArchiveLocation("/storage/emulated/0/文档.zip", "a/b/"), archiveLocation(path))
    }

    @Test fun supportedFormatsRoundTripAndAllowSingleEntryExtraction() = runBlocking {
        ArchiveFormat.entries.forEach { format ->
            val root = temp.newFolder("roundtrip-${format.name.lowercase()}")
            val source = File(root, "payload").apply { mkdir() }
            File(source, "hello.txt").writeText("archive-${format.name}")
            val repo = ArchiveRepository()
            if (format.singleFileOnly) {
                File(source, "hello.txt").copyTo(File(root, "single.txt"))
                assertTrue("${format.name} compress", repo.compress(File(root, "single.txt").path, format).success)
            } else {
                assertTrue("${format.name} compress", repo.compress(source.path, format).success)
            }
            val compressed = if (format.singleFileOnly) File(root, "single.txt${format.suffix}")
                else File(root, "payload${format.suffix}")
            assertTrue("${format.name} created", compressed.isFile)
            val top = repo.list(archivePath(compressed.path), false, false)
            assertNull("${format.name} list error: ${top.error}", top.error)
            val entry = if (format.singleFileOnly) top.entries.single() else {
                val directory = top.entries.single()
                repo.list(directory.path, false, false).entries.single()
            }
            assertEquals("${format.name} entry", if (format.singleFileOnly) "single.txt" else "hello.txt", entry.name)
            val materialized = repo.materialize(entry.path, File(root, "cache").apply { mkdir() })
            assertNotNull("${format.name} materialize", materialized)
            assertEquals(if (format.singleFileOnly) "archive-${format.name}" else "archive-${format.name}", materialized!!.readText())
            assertTrue("${format.name} extract entry", repo.extractFile(entry.path, File(root, "out").apply { mkdir() }.path).success)
            val outputName = if (format.singleFileOnly) "single.txt" else "hello.txt"
            assertEquals("archive-${format.name}", File(root, "out/$outputName").readText())
            assertTrue("${format.name} extract archive", repo.extract(compressed.path, File(root, "full").apply { mkdir() }.path).success)
        }
    }

    @Test fun rejectsSingleFileCompressionForDirectories() = runBlocking {
        val folder = temp.newFolder("single-only")
        assertFalse(ArchiveRepository().compress(folder.path, ArchiveFormat.GZIP).success)
        assertFalse(File(folder.parentFile, "${folder.name}.gz").exists())
    }

    @Test fun zipBasedExtensionsOpenLikeZip() = runBlocking {
        val root = temp.newFolder("zip-aliases")
        val repo = ArchiveRepository()
        listOf("apk", "jar", "cbz", "apks").forEach { extension ->
            val archive = File(root, "sample.$extension")
            ZipOutputStream(archive.outputStream()).use { out ->
                out.putNextEntry(ZipEntry("readme.txt"))
                out.write("hello".toByteArray())
                out.closeEntry()
            }
            val entry = repo.list(archivePath(archive.path), false, false).entries.single()
            assertEquals("readme.txt", entry.name)
            assertTrue(repo.extractFile(entry.path, File(root, "out-$extension").apply { mkdir() }.path).success)
            assertEquals("hello", File(root, "out-$extension/readme.txt").readText())
        }
    }

    @Test fun arAndCpioCanBeBrowsedAndExtracted() = runBlocking {
        val root = temp.newFolder("legacy-formats")
        val payload = "hello legacy archive".toByteArray()
        val ar = File(root, "sample.ar")
        ArArchiveOutputStream(ar.outputStream()).use { out ->
            out.putArchiveEntry(ArArchiveEntry("hello.txt", payload.size.toLong()))
            out.write(payload)
            out.closeArchiveEntry()
        }
        val cpio = File(root, "sample.cpio")
        CpioArchiveOutputStream(cpio.outputStream()).use { out ->
            out.putArchiveEntry(CpioArchiveEntry("hello.txt", payload.size.toLong()))
            out.write(payload)
            out.closeArchiveEntry()
        }
        val repo = ArchiveRepository()
        listOf(ar, cpio).forEach { archive ->
            val entry = repo.list(archivePath(archive.path), false, false).entries.single()
            assertEquals("hello.txt", entry.name)
            val single = File(root, "single-${archive.extension}").apply { mkdir() }
            assertTrue(repo.extractFile(entry.path, single.path).success)
            assertArrayEquals(payload, File(single, "hello.txt").readBytes())
            val all = File(root, "all-${archive.extension}").apply { mkdir() }
            assertTrue(repo.extract(archive.path, all.path).success)
            assertArrayEquals(payload, File(all, "hello.txt").readBytes())
        }
    }
}
