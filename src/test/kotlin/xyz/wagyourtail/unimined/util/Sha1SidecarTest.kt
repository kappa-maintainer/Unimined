package xyz.wagyourtail.unimined.util

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createFile
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class Sha1SidecarTest {

    @TempDir
    lateinit var tempDir: Path

    private fun file(content: String): Path = tempDir.resolve("test.bin").apply {
        writeText(content)
    }

    @Test
    fun `verifies and caches hash in sidecar on first check`() {
        val path = file("hello world")
        val sha1 = path.getSha1()

        assertTrue(testSha1(-1, sha1, path))
        // sidecar written after first verified hash
        val sidecar = tempDir.resolve("test.bin.sha1")
        assertTrue(sidecar.exists())
        assertTrue(sidecar.readText().endsWith(sha1))
    }

    @Test
    fun `reuses sidecar without re-hashing unchanged file`() {
        val path = file("hello world")
        val sha1 = path.getSha1()
        assertTrue(testSha1(-1, sha1, path))
        val sidecarMtime = tempDir.resolve("test.bin.sha1").toFile().lastModified()

        // second check: same size+mtime, should hit the sidecar (no error, still true)
        assertTrue(testSha1(-1, sha1, path))
        assertEquals(sidecarMtime, tempDir.resolve("test.bin.sha1").toFile().lastModified())
    }

    @Test
    fun `mismatched content fails even with cached sidecar`() {
        val path = file("hello world")
        val sha1 = path.getSha1()
        assertTrue(testSha1(-1, sha1, path))
        // corrupt sidecar with a wrong hash but matching size+mtime
        tempDir.resolve("test.bin.sha1").writeText("${path.toFile().length()} ${path.toFile().lastModified()} deadbeef")

        assertFalse(testSha1(-1, sha1, path))
    }

    @Test
    fun `invalidates sidecar when file changes`() {
        val path = file("hello world")
        val sha1 = path.getSha1()
        assertTrue(testSha1(-1, sha1, path))

        // modify content (same length, new mtime)
        path.writeText("hello worlD")
        assertFalse(testSha1(-1, sha1, path))
        // new hash validates and refreshes the sidecar
        assertTrue(testSha1(-1, path.getSha1(), path))
    }

    @Test
    fun `useSidecar=false always re-hashes`() {
        val path = file("hello world")
        val sha1 = path.getSha1()
        assertTrue(testSha1(-1, sha1, path, useSidecar = false))
        // corrupt sidecar; with useSidecar=false the real hash wins
        tempDir.resolve("test.bin.sha1").writeText("${path.toFile().length()} ${path.toFile().lastModified()} deadbeef")
        assertTrue(testSha1(-1, sha1, path, useSidecar = false))
        // and the sidecar was refreshed
        assertTrue(tempDir.resolve("test.bin.sha1").readText().endsWith(sha1))
    }

    @Test
    fun `empty file still hashes correctly`() {
        val path = tempDir.resolve("empty.bin").createFile()
        val sha1 = path.getSha1()
        assertTrue(testSha1(0, sha1, path))
        assertFalse(testSha1(0, "0000000000000000000000000000000000000000", path))
    }
}
