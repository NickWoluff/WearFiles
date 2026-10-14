package com.nickwoluff.wearfiles

import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class StorageRepositoryTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun accessCheckVerifiesActualFileReadWriteAndLeavesNoProbe() = runBlocking {
        val root = temp.newFolder("access")
        File(root, "keep.txt").writeText("不可修改")
        val check = StorageRepository(root).verifyAccess()
        assertTrue(check.complete)
        assertEquals(listOf("keep.txt"), root.listFiles()!!.map { it.name })
        assertEquals("不可修改", File(root, "keep.txt").readText())
    }

    @Test fun accessCheckRejectsMissingStorageAndNonDirectory() = runBlocking {
        val root = temp.newFolder("invalid-access")
        val file = File(root, "file.txt").apply { writeText("保留") }
        assertFalse(StorageRepository(File(root, "missing")).verifyAccess().complete)
        assertFalse(StorageRepository(file).verifyAccess().complete)
        assertEquals("保留", file.readText())
    }

    @Test fun sharedStorageListsRealDirectoriesAndKeepsFoldersFirst() = runBlocking {
        val root = temp.newFolder("shared")
        File(root, "Download").mkdir()
        File(root, "Music").mkdir()
        File(root, "a.txt").writeText("hello")
        File(root, ".hidden").writeText("secret")
        val repo = StorageRepository(root)
        val result = repo.list(root.path, false, false, true)
        assertNull(result.error)
        assertEquals(listOf("Download", "Music", "a.txt"), result.entries.map { it.name })
        assertEquals(4, repo.list(root.path, true, false, true).entries.size)
    }

    @Test fun unreadableAndRestrictedDirectoriesAreNotReportedAsEmpty() = runBlocking {
        val root = temp.newFolder("shared")
        val repo = StorageRepository(root)
        assertNotNull(repo.list(File(root, "missing").path, false, false, true).error)
        assertNotNull(repo.list(File(root, "Android/data").path, false, false, true).error)
        assertNotNull(repo.list(File(root, "Android/obb").path, false, false, true).error)
        assertNotNull(repo.list(root.parent, false, false, true).error)
        assertNull(repo.list(root.path, false, false, true).error)
    }

    @Test fun creatingFoldersRejectsTraversalAndNameCollisions() = runBlocking {
        val root = temp.newFolder("shared")
        val repo = StorageRepository(root)
        assertEquals("已创建 测试目录", repo.createFolder(root.path, "测试目录"))
        assertTrue(File(root, "测试目录").isDirectory)
        assertEquals("已存在同名文件或文件夹", repo.createFolder(root.path, "测试目录"))
        assertFalse(validFolderName("../outside"))
        assertFalse(validFolderName(".."))
        assertFalse(validFolderName("bad\\name"))
        assertEquals("此位置不属于共享存储", repo.createFolder(root.parent, "outside"))
    }

    @Test fun sizeUnitsFollowBinaryBoundaries() {
        assertEquals("1023 B", fileSize(1023))
        assertEquals("1 KB", fileSize(1024))
        assertEquals("1 MB", fileSize(1024 * 1024))
        assertEquals("1 GB", fileSize(1024L * 1024 * 1024))
    }

    @Test fun renameAndMoveKeepFileContentsAndNeverOverwrite() = runBlocking {
        val root = temp.newFolder("operations")
        val repo = StorageRepository(root)
        val source = File(root, "a.txt").apply { writeText("保留内容") }
        val collision = File(root, "b.txt").apply { writeText("不可覆盖") }
        assertFalse(repo.rename(source.path, collision.name).success)
        assertEquals("不可覆盖", collision.readText())
        assertTrue(repo.rename(source.path, "renamed.txt").success)
        val renamed = File(root, "renamed.txt")
        assertEquals("保留内容", renamed.readText())
        val destination = File(root, "destination").apply { mkdir() }
        File(destination, renamed.name).writeText("目标内容")
        assertFalse(repo.move(renamed.path, destination.path).success)
        assertEquals("目标内容", File(destination, renamed.name).readText())
        val empty = File(root, "empty").apply { mkdir() }
        assertTrue(repo.move(renamed.path, empty.path).success)
        assertFalse(renamed.exists())
        assertEquals("保留内容", File(empty, renamed.name).readText())
    }

    @Test fun folderOperationsHandleContentsAndRejectUnsafeDestinations() = runBlocking {
        val root = temp.newFolder("folders")
        val repo = StorageRepository(root)
        val folder = File(root, "folder").apply { mkdir() }
        val child = File(folder, "child").apply { mkdir() }
        File(child, "file.txt").writeText("nested")
        assertFalse(repo.move(folder.path, child.path).success)
        assertFalse(repo.move(folder.path, folder.path).success)
        assertFalse(repo.move(folder.path, root.parent).success)
        assertFalse(repo.rename(folder.path, "../escape").success)
        assertFalse(repo.delete(root.path).success)
        assertFalse(repo.delete(root.parent).success)
        assertTrue(repo.rename(folder.path, "renamed").success)
        val renamed = File(root, "renamed")
        assertEquals("nested", File(renamed, "child/file.txt").readText())
        val destination = File(root, "destination").apply { mkdir() }
        assertTrue(repo.move(renamed.path, destination.path).success)
        val moved = File(destination, "renamed")
        assertEquals("nested", File(moved, "child/file.txt").readText())
        assertTrue(repo.delete(moved.path).success)
        assertFalse(moved.exists())
        assertTrue(destination.isDirectory)
    }

    @Test fun deletingAndroidParentRejectsRestrictedContentsBeforeAnyDeletion() = runBlocking {
        val root = temp.newFolder("restricted")
        val android = File(root, "Android").apply { mkdir() }
        val safe = File(android, "media").apply { mkdir() }
        File(safe, "keep.txt").writeText("keep")
        File(android, "data").mkdir()
        val repo = StorageRepository(root)
        assertFalse(repo.delete(android.path).success)
        assertEquals("keep", File(safe, "keep.txt").readText())
        assertFalse(repo.rename(File(android, "data").path, "renamed").success)
        assertFalse(repo.move(safe.path, File(android, "data").path).success)
    }
}
