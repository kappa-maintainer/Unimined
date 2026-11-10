package xyz.wagyourtail.unimined.mods

import kotlinx.coroutines.runBlocking
import org.gradle.api.Project
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import xyz.wagyourtail.unimined.internal.mods.DevAtNormalizer
import xyz.wagyourtail.unimined.mapping.EnvType
import xyz.wagyourtail.unimined.mapping.Namespace
import xyz.wagyourtail.unimined.mapping.formats.tsrg.TsrgV1Reader
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.writeText

/**
 * The dev-namespace side: a jar whose classes are already mcp, whose access transformer is not.
 *
 * That combination is the normal one for anything this build did not produce itself - a published jar carries srg
 * names in its AT, and deobfuscating only the classes leaves the file as it was. The loader keys AT rules by class
 * name, so an srg rule against mcp classes is not an error, it is a rule that never applies; the rewrite here is the
 * only place that can notice and fix it.
 */
class DevAtNormalizerTest {

    @TempDir
    lateinit var dir: Path

    private lateinit var project: Project
    private lateinit var cache: Path

    private val mappings = runBlocking {
        TsrgV1Reader.read(
            """
            net/minecraft/item/ItemStack net/minecraft/item/ItemStack
            ${'\t'}field_77777 stackSize
            ${'\t'}func_12345 getItem
            """.trimIndent(),
            EnvType.JOINED,
            mapOf("source" to "searge", "target" to "mcp"),
        )
    }

    @BeforeEach
    fun setUp() {
        project = ProjectBuilder.builder().withProjectDir(dir.toFile()).build()
        cache = dir.resolve("cache")
    }

    private fun normalizer(
        from: String = "searge",
        to: String = "mcp",
        isLegacy: Boolean = false,
    ) = DevAtNormalizer(
        logger = project.logger,
        cacheDirectory = cache,
        fromNamespace = Namespace(from),
        toNamespace = Namespace(to),
        isLegacy = isLegacy,
        mappingsId = { "test-mappings" },
        mappings = { mappings },
    )

    private fun jar(name: String, vararg entries: Pair<String, String>): File {
        val path = dir.resolve(name)
        ZipOutputStream(Files.newOutputStream(path)).use { out ->
            for ((entryName, content) in entries) {
                out.putNextEntry(ZipEntry(entryName))
                out.write(content.toByteArray(StandardCharsets.UTF_8))
                out.closeEntry()
            }
        }
        return path.toFile()
    }

    private fun entry(jar: File, name: String): String =
        java.util.zip.ZipFile(jar).use { zip ->
            val entry = zip.getEntry(name)
            requireNotNull(entry) { "$name is not in ${jar.name}" }
            zip.getInputStream(entry).use { it.readBytes().toString(StandardCharsets.UTF_8) }
        }

    private fun bytes(jar: File, name: String): ByteArray =
        java.util.zip.ZipFile(jar).use { zip ->
            zip.getInputStream(zip.getEntry(name)).use { it.readBytes() }
        }

    @Test
    fun `an srg named access transformer is rewritten in a copy`() {
        val original = jar(
            "dep.jar",
            "META-INF/accesstransformer.cfg" to "public net.minecraft.item.ItemStack field_77777\n",
            "com/example/Foo.class" to "not really a class",
            "mcmod.info" to """{"jar":{"schemaVersion":1,"license":"MIT","at":["accesstransformer.cfg"]}}""",
        )

        val normalized = normalizer().normalize(original)

        assertNotEquals(original, normalized)
        assertTrue(normalized.toPath().startsWith(cache))
        // The rewrite re-serialises the file, so the trailing newline is not part of the contract - the rules are.
        assertEquals(
            "public net.minecraft.item.ItemStack stackSize",
            entry(normalized, "META-INF/accesstransformer.cfg").trimEnd(),
        )
        // everything that is not the AT is carried over untouched
        assertArrayEquals(bytes(original, "com/example/Foo.class"), bytes(normalized, "com/example/Foo.class"))
        assertEquals(entry(original, "mcmod.info"), entry(normalized, "mcmod.info"))
        // and the input is left alone
        assertTrue(entry(original, "META-INF/accesstransformer.cfg").contains("field_77777"))
    }

    @Test
    fun `a declared access transformer in a subdirectory is rewritten`() {
        val original = jar(
            "subdir.jar",
            "META-INF/at/foo.cfg" to "public net/minecraft/item/ItemStack func_12345\n",
            "mcmod.info" to """{"jar":{"schemaVersion":1,"license":"MIT","at":["at/foo.cfg"]}}""",
        )

        val normalized = normalizer().normalize(original)

        assertNotEquals(original, normalized)
        assertEquals(
            "public net.minecraft.item.ItemStack getItem",
            entry(normalized, "META-INF/at/foo.cfg").trimEnd(),
        )
    }

    @Test
    fun `an access transformer that is already in the dev namespace keeps the jar`() {
        val original = jar(
            "already-dev.jar",
            "META-INF/accesstransformer.cfg" to "public net.minecraft.item.ItemStack stackSize\n",
            "mcmod.info" to """{"jar":{"schemaVersion":1,"license":"MIT","at":["accesstransformer.cfg"]}}""",
        )

        assertEquals(original, normalizer().normalize(original))
        assertTrue(!cache.exists() || cache.listDirectoryEntries().isEmpty())
    }

    @Test
    fun `a rule naming something the mappings do not know is left as it is`() {
        val original = jar(
            "unknown.jar",
            "META-INF/accesstransformer.cfg" to "public com.example.Other modField\n",
        )

        assertEquals(original, normalizer().normalize(original))
    }

    @Test
    fun `a jar without an access transformer is not copied`() {
        val original = jar(
            "plain.jar",
            "com/example/Foo.class" to "not really a class",
            "mcmod.info" to """{"jar":{"schemaVersion":1,"license":"MIT"}}""",
        )

        assertEquals(original, normalizer().normalize(original))
        assertTrue(!cache.exists() || cache.listDirectoryEntries().isEmpty())
    }

    @Test
    fun `the normalized copy is reused until the input or the mappings change`() {
        val original = jar(
            "cached.jar",
            "META-INF/accesstransformer.cfg" to "public net.minecraft.item.ItemStack field_77777\n",
        )

        val first = normalizer().normalize(original)
        val second = normalizer().normalize(original)
        assertEquals(first, second)

        // touching the input invalidates the cached copy instead of serving stale rules
        Files.setLastModifiedTime(original.toPath(), java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() + 10_000))
        val third = normalizer().normalize(original)
        assertEquals(first, third)
        assertTrue(entry(third, "META-INF/accesstransformer.cfg").contains("stackSize"))
    }

    @Test
    fun `the same namespace on both sides is a no-op`() {
        val original = jar(
            "same-ns.jar",
            "META-INF/accesstransformer.cfg" to "public net.minecraft.item.ItemStack field_77777\n",
        )

        assertEquals(original, normalizer(from = "mcp", to = "mcp").normalize(original))
    }

    @Test
    fun `a non jar is returned untouched`() {
        val text = dir.resolve("notes.txt")
        text.writeText("public net.minecraft.item.ItemStack field_77777")
        assertEquals(text.toFile(), normalizer().normalize(text.toFile()))
    }
}
