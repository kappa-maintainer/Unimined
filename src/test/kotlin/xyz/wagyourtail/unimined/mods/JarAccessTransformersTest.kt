package xyz.wagyourtail.unimined.mods

import org.gradle.api.Project
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import xyz.wagyourtail.unimined.internal.mods.JarAccessTransformers
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.Attributes
import java.util.jar.Manifest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * The declaration a dependency carries, read with the loader's precedence: the `at` key of the `jar` section of
 * `mcmod.info` first, the `FMLAT` manifest attribute only as a fallback.
 *
 * The case that matters is the first one: Unimined writes the declaration and writes no manifest attribute at all, so a
 * jar built here is invisible to anything that only knows `FMLAT`.
 */
class JarAccessTransformersTest {

    @TempDir
    lateinit var dir: Path

    private lateinit var project: Project

    @BeforeEach
    fun setUp() {
        project = ProjectBuilder.builder().withProjectDir(dir.toFile()).build()
    }

    /** A manifest that survives a round trip through a jar: without `Manifest-Version` `JarFile.manifest` is null. */
    private fun manifest(fmlat: String): Manifest =
        Manifest().apply {
            mainAttributes[Attributes.Name.MANIFEST_VERSION] = "1.0"
            mainAttributes[Attributes.Name("FMLAT")] = fmlat
        }

    private fun metadata(json: String): ByteArray = json.toByteArray(StandardCharsets.UTF_8)

    @Test
    fun `the manifest attribute is split on whitespace`() {
        assertEquals(
            listOf("accesstransformer.cfg", "foo_at.cfg"),
            JarAccessTransformers.of(manifest("  accesstransformer.cfg   foo_at.cfg  "), null, project.logger),
        )
    }

    @Test
    fun `the declaration is read and wins over the manifest`() {
        val declared = JarAccessTransformers.of(
            manifest("legacy_at.cfg"),
            metadata("""{"jar":{"schemaVersion":1,"license":"MIT","at":["at/foo.cfg"]}}"""),
            project.logger,
        )
        assertEquals(listOf("at/foo.cfg"), declared)
    }

    @Test
    fun `an empty declaration falls back to the manifest`() {
        val declared = JarAccessTransformers.of(
            manifest("legacy_at.cfg"),
            metadata("""{"jar":{"schemaVersion":1,"license":"MIT","at":[]}}"""),
            project.logger,
        )
        assertEquals(listOf("legacy_at.cfg"), declared)
    }

    @Test
    fun `the missing declaration falls back to the manifest`() {
        val declared = JarAccessTransformers.of(
            manifest("legacy_at.cfg"),
            metadata("""{"jar":{"schemaVersion":1,"license":"MIT"}}"""),
            project.logger,
        )
        assertEquals(listOf("legacy_at.cfg"), declared)
    }

    @Test
    fun `a string declaration is split the way the manifest value was`() {
        val declared = JarAccessTransformers.of(
            null,
            metadata("""{"jar":{"schemaVersion":1,"license":"MIT","at":"a.cfg b.cfg"}}"""),
            project.logger,
        )
        assertEquals(listOf("a.cfg", "b.cfg"), declared)
    }

    @Test
    fun `an array element is one path and is not split again`() {
        val declared = JarAccessTransformers.of(
            null,
            metadata("""{"jar":{"schemaVersion":1,"license":"MIT","at":["with space.cfg"]}}"""),
            project.logger,
        )
        assertEquals(listOf("with space.cfg"), declared)
    }

    @Test
    fun `an entry of the wrong type is skipped, the rest are kept`() {
        val declared = JarAccessTransformers.of(
            null,
            metadata("""{"jar":{"schemaVersion":1,"license":"MIT","at":["a.cfg",true,7,"b.cfg"]}}"""),
            project.logger,
        )
        assertEquals(listOf("a.cfg", "b.cfg"), declared)
    }

    @Test
    fun `a legacy array root has no jar section`() {
        val declared = JarAccessTransformers.of(
            manifest("legacy_at.cfg"),
            metadata("""[{"modid":"foo","name":"Foo"}]"""),
            project.logger,
        )
        assertEquals(listOf("legacy_at.cfg"), declared)
    }

    @Test
    fun `a jar section of the wrong type falls back instead of throwing`() {
        val declared = JarAccessTransformers.of(
            manifest("legacy_at.cfg"),
            metadata("""{"jar":"not an object"}"""),
            project.logger,
        )
        assertEquals(listOf("legacy_at.cfg"), declared)
    }

    @Test
    fun `an unreadable mcmod info falls back instead of throwing`() {
        val declared = JarAccessTransformers.of(
            manifest("legacy_at.cfg"),
            metadata("""{"jar": {"""),
            project.logger,
        )
        assertEquals(listOf("legacy_at.cfg"), declared)
    }

    @Test
    fun `nothing declared anywhere is an empty list`() {
        assertEquals(emptyList<String>(), JarAccessTransformers.of(null, null, project.logger))
        assertEquals(emptyList<String>(), JarAccessTransformers.of(Manifest(), null, project.logger))
    }

    @Test
    fun `a jar is read from its manifest and its mcmod info`() {
        val jar = dir.resolve("dep.jar")
        ZipOutputStream(Files.newOutputStream(jar)).use { out ->
            out.putNextEntry(ZipEntry("META-INF/MANIFEST.MF"))
            manifest("legacy_at.cfg").write(out)
            out.closeEntry()
            out.putNextEntry(ZipEntry("mcmod.info"))
            out.write(metadata("""{"jar":{"schemaVersion":1,"license":"MIT","at":["at/foo.cfg"]}}"""))
            out.closeEntry()
        }
        assertEquals(listOf("at/foo.cfg"), JarAccessTransformers.of(jar, project.logger))
    }

    @Test
    fun `a jar without metadata uses its manifest`() {
        val jar = dir.resolve("legacy.jar")
        ZipOutputStream(Files.newOutputStream(jar)).use { out ->
            out.putNextEntry(ZipEntry("META-INF/MANIFEST.MF"))
            manifest("legacy_at.cfg").write(out)
            out.closeEntry()
        }
        assertEquals(listOf("legacy_at.cfg"), JarAccessTransformers.of(jar, project.logger))
    }
}
