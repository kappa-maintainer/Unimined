package xyz.wagyourtail.unimined.metadata

import org.gradle.api.InvalidUserDataException
import org.gradle.api.Project
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import xyz.wagyourtail.unimined.api.mod.BundledJar
import xyz.wagyourtail.unimined.api.mod.ContainedKind
import xyz.wagyourtail.unimined.api.mod.JarMetadataConfig
import xyz.wagyourtail.unimined.internal.minecraft.patch.forge.OutlandsMetadataWriter
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile
import kotlin.io.path.readBytes
import xyz.wagyourtail.unimined.util.openZipFileSystem

/**
 * Covers the part that edits the produced jar: writing the section in, leaving it alone when nothing is declared, and
 * refusing an access transformer the jar does not actually contain.
 *
 * The last one is the whole reason this step exists in a build - the loader looks the entry up and skips it silently, so
 * a misspelt path is invisible until someone notices the access transformer had no effect.
 */
class OutlandsMetadataWriterTest {

    @TempDir
    lateinit var dir: Path

    private lateinit var project: Project

    @BeforeEach
    fun setUp() {
        project = ProjectBuilder.builder().withProjectDir(dir.toFile()).build()
    }

    /** Builds a jar with the given entries; a `null` content marks a directory-less empty file. */
    private fun jar(
        name: String,
        vararg entries: Pair<String, String>,
    ): Path {
        val path = dir.resolve(name)
        ZipOutputStream(Files.newOutputStream(path)).use { out ->
            for ((entryName, content) in entries) {
                out.putNextEntry(ZipEntry(entryName))
                out.write(content.toByteArray(StandardCharsets.UTF_8))
                out.closeEntry()
            }
        }
        return path
    }

    private fun write(
        jar: Path,
        action: JarMetadataConfig.() -> Unit,
    ) {
        OutlandsMetadataWriter(project, JarMetadataConfig().apply(action)).apply(jar)
    }

    private fun entry(
        jar: Path,
        name: String,
    ): String? =
        jar.openZipFileSystem().use { fs ->
            val path = fs.getPath(name)
            if (path.isRegularFile()) Files.readString(path) else null
        }

    @Test
    fun `the section is written into a jar that has no mcmod info`() {
        val jar = jar("mod.jar", "com/example/Mod.class" to "bytecode")

        write(jar) { license("MIT") }

        val text = entry(jar, "mcmod.info")
        assertTrue(text != null, "mcmod.info should have been created")
        assertTrue(text!!.contains("\"license\""), text)
        assertTrue(text.contains("\"schemaVersion\""), text)
    }

    @Test
    fun `an existing mod list survives`() {
        val jar = jar("mod.jar", "mcmod.info" to """[{"modid": "example", "name": "Example"}]""")

        write(jar) { license("MIT") }

        val text = entry(jar, "mcmod.info")!!
        assertTrue(text.contains("\"modList\""), text)
        assertTrue(text.contains("example"), text)
    }

    @Test
    fun `an empty configuration does not touch the jar`() {
        val jar = jar("mod.jar", "mcmod.info" to """{"modList": []}""")
        val before = jar.readBytes()

        write(jar) {}

        assertArrayEquals(before, jar.readBytes())
    }

    @Test
    fun `an empty configuration does not create a metadata file`() {
        val jar = jar("mod.jar", "com/example/Mod.class" to "bytecode")

        write(jar) {}

        assertNull(entry(jar, "mcmod.info"))
    }

    /**
     * The task re-reads its source jar on every run, so writing twice has to converge - otherwise the jar differs on each
     * build and the task is never up to date.
     */
    @Test
    fun `writing twice produces the same jar`() {
        val jar = jar(
            "mod.jar",
            "mcmod.info" to """[{"modid": "example"}]""",
            "META-INF/accesstransformer.cfg" to "access widen",
        )

        write(jar) { license("MIT"); at("accesstransformer.cfg") }
        val once = entry(jar, "mcmod.info")
        write(jar) { license("MIT"); at("accesstransformer.cfg") }

        assertEquals(once, entry(jar, "mcmod.info"))
    }

    @Test
    fun `a declared access transformer that is in the jar is accepted`() {
        val jar = jar(
            "mod.jar",
            "mcmod.info" to """{"modList": []}""",
            "META-INF/accesstransformer.cfg" to "access widen",
        )

        write(jar) { license("MIT"); at("accesstransformer.cfg") }

        assertTrue(entry(jar, "mcmod.info")!!.contains("accesstransformer.cfg"))
    }

    /**
     * The loader's lookup is `if (entry != null)` with no else, so a missing file produces a jar that looks right and
     * applies nothing. This is the only place that can catch it.
     */
    @Test
    fun `a declared access transformer that is missing fails the build`() {
        val jar = jar("mod.jar", "mcmod.info" to """{"modList": []}""")

        val error = assertThrows<InvalidUserDataException> {
            write(jar) { license("MIT"); at("accesstransformer.cfg") }
        }

        assertTrue(error.message!!.contains("META-INF/accesstransformer.cfg"), error.message!!)
        assertTrue(error.message!!.contains("silently"), error.message!!)
        assertTrue(error.message!!.contains("accessTransformerValidation false"), error.message!!)
    }

    @Test
    fun `validation can be turned off for files a later task adds`() {
        val jar = jar("mod.jar", "mcmod.info" to """{"modList": []}""")

        write(jar) {
            license("MIT")
            at("later.cfg")
            accessTransformerValidation(false)
        }

        assertTrue(entry(jar, "mcmod.info")!!.contains("later.cfg"))
    }

    @Test
    fun `a conflict fails without writing anything`() {
        val original = """{"jar": {"schemaVersion": 1, "license": "EPL-2.0"}}"""
        val jar = jar("mod.jar", "mcmod.info" to original)

        val error = assertThrows<InvalidUserDataException> {
            write(jar) { license("MIT") }
        }

        assertTrue(error.message!!.contains("EPL-2.0"), error.message!!)
        assertEquals(original, entry(jar, "mcmod.info"))
    }

    // ---- bundled dependencies -----------------------------------------------------------------------------------

    private fun bundled(
        file: Path,
        id: String? = "com.example:lib",
        version: String? = "1.0",
        kind: ContainedKind = ContainedKind.LIB,
    ) = BundledJar(id, version, file.toFile(), kind)

    private fun artifact(
        name: String,
        content: String = "classes",
    ): Path {
        val path = dir.resolve(name)
        Files.writeString(path, content)
        return path
    }

    private fun written(
        jar: Path,
        bundled: List<BundledJar>,
        action: JarMetadataConfig.() -> Unit = { license("MIT") },
    ) {
        OutlandsMetadataWriter(project, JarMetadataConfig().apply(action)).apply(jar, bundled)
    }

    @Test
    fun `a bundled dependency is copied into the jar and declared`() {
        val jar = jar("mod.jar", "mcmod.info" to """{"modList": []}""")
        val lib = artifact("lib-1.0.jar", "library bytes")

        written(jar, listOf(bundled(lib)))

        assertEquals("library bytes", entry(jar, "META-INF/libraries/lib-1.0.jar"))
        val text = entry(jar, "mcmod.info")!!
        assertTrue(text.contains("META-INF/libraries/lib-1.0.jar"), text)
        assertTrue(text.contains("com.example:lib"), text)
        assertTrue(text.contains("\"kind\": \"LIB\""), text)
    }

    @Test
    fun `a mod is declared as a mod`() {
        val jar = jar("mod.jar", "mcmod.info" to """{"modList": []}""")

        written(jar, listOf(bundled(artifact("dep.jar"), kind = ContainedKind.MOD)))

        assertTrue(entry(jar, "mcmod.info")!!.contains("\"kind\": \"MOD\""))
    }

    @Test
    fun `macOS metadata files in the copy are not invented`() {
        // A copy only ever writes the entries it was given, so nothing else can appear in META-INF/libraries.
        val jar = jar("mod.jar", "mcmod.info" to """{"modList": []}""")
        written(jar, listOf(bundled(artifact("a.jar"))))

        jar.openZipFileSystem().use { fs ->
            val names = Files.list(fs.getPath("META-INF/libraries")).use { stream ->
                stream.map { it.fileName.toString() }.toList()
            }
            assertEquals(listOf("a.jar"), names)
        }
    }

    @Test
    fun `an existing copy of the same file is replaced by the resolved artifact`() {
        val jar = jar(
            "mod.jar",
            "mcmod.info" to """{"modList": []}""",
            "META-INF/libraries/lib-1.0.jar" to "stale bytes",
        )

        written(jar, listOf(bundled(artifact("lib-1.0.jar", "fresh bytes"))))

        assertEquals("fresh bytes", entry(jar, "META-INF/libraries/lib-1.0.jar"))
    }

    /**
     * The author's kind wins: a jar may bundle something it does not want discovered as a mod, and no dependency graph
     * can know that.
     */
    @Test
    fun `a kind declared by the author is kept`() {
        val jar = jar(
            "mod.jar",
            "mcmod.info" to """{"jar": {"schemaVersion": 1, "license": "MIT",
                                 "containedDeps": [{"path": "META-INF/libraries/lib-1.0.jar", "kind": "MOD"}]}}""",
        )

        written(jar, listOf(bundled(artifact("lib-1.0.jar"), kind = ContainedKind.LIB)), {})

        val text = entry(jar, "mcmod.info")!!
        assertTrue(text.contains("\"kind\": \"MOD\""), text)
    }

    /**
     * The loader finds nothing to extract and reports nothing, so a declaration with no file behind it is exactly the
     * kind of failure that survives to a release.
     */
    @Test
    fun `a declared dependency that is not in the jar fails the build`() {
        val jar = jar(
            "mod.jar",
            "mcmod.info" to """{"jar": {"schemaVersion": 1, "license": "MIT",
                                 "containedDeps": [{"path": "META-INF/libraries/by-a-task.jar"}]}}""",
        )

        val error = assertThrows<InvalidUserDataException> {
            written(jar, listOf(bundled(artifact("lib-1.0.jar"))), {})
        }

        assertTrue(error.message!!.contains("META-INF/libraries/by-a-task.jar"), error.message!!)
        assertTrue(error.message!!.contains("not in the jar"), error.message!!)
    }

    @Test
    fun `writing the same bundled dependency twice produces the same jar`() {
        val jar = jar("mod.jar", "mcmod.info" to """{"modList": []}""")
        val deps = listOf(bundled(artifact("lib-1.0.jar")))

        written(jar, deps)
        val once = entry(jar, "mcmod.info")
        written(jar, deps)

        assertEquals(once, entry(jar, "mcmod.info"))
    }

    @Test
    fun `bundling without a licence fails and says why`() {
        val jar = jar("mod.jar", "mcmod.info" to """{"modList": []}""")

        val error = assertThrows<InvalidUserDataException> {
            OutlandsMetadataWriter(project, JarMetadataConfig()).apply(jar, listOf(bundled(artifact("lib.jar"))))
        }

        assertTrue(error.message!!.contains("license"), error.message!!)
    }

    @Test
    fun `the jar keeps its other entries`() {
        val jar = jar(
            "mod.jar",
            "com/example/Mod.class" to "bytecode",
            "mcmod.info" to """{"modList": []}""",
        )

        write(jar) { license("MIT") }

        assertEquals("bytecode", entry(jar, "com/example/Mod.class"))
        assertTrue(jar.exists())
    }
}
