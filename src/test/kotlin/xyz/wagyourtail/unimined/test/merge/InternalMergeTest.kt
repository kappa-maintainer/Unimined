package xyz.wagyourtail.unimined.test.merge

import org.gradle.testfixtures.ProjectBuilder
import org.gradle.api.tasks.SourceSetContainer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.ClassNode
import xyz.wagyourtail.unimined.api.minecraft.MinecraftJar
import xyz.wagyourtail.unimined.internal.minecraft.MinecraftProvider
import xyz.wagyourtail.unimined.internal.minecraft.patch.NoTransformMinecraftTransformer
import xyz.wagyourtail.unimined.mapping.EnvType
import xyz.wagyourtail.unimined.mapping.Namespace
import xyz.wagyourtail.unimined.util.readZipContents
import xyz.wagyourtail.unimined.util.readZipInputStreamFor
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.outputStream
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class InternalMergeTest {

    @TempDir
    lateinit var tempDir: Path

    private fun classBytes(name: String, methods: List<Pair<String, String>> = listOf()): ByteArray {
        val cw = ClassWriter(0)
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null)
        for ((methodName, desc) in methods) {
            val mv = cw.visitMethod(Opcodes.ACC_PUBLIC, methodName, desc, null, null)
            mv.visitCode()
            mv.visitInsn(Opcodes.RETURN)
            mv.visitMaxs(0, 1)
            mv.visitEnd()
        }
        cw.visitEnd()
        return cw.toByteArray()
    }

    private fun writeJar(
        path: Path,
        entries: Map<String, ByteArray>,
    ) {
        ZipOutputStream(path.outputStream()).use { zip ->
            for ((name, bytes) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
    }

    private fun jar(
        dir: Path,
        env: EnvType,
    ): MinecraftJar =
        MinecraftJar(
            parentPath = dir,
            name = "minecraft",
            envType = env,
            version = "test",
            patches = listOf(),
            mappingNamespace = Namespace("official"),
            awOrAt = null,
        )

    private fun readClassEntry(
        jarPath: Path,
        entryName: String,
    ): ClassNode {
        val node = ClassNode()
        jarPath.readZipInputStreamFor(entryName) { stream ->
            ClassReader(stream).accept(node, 0)
        }
        return node
    }

    private fun provider(): Pair<MinecraftProvider, NoTransformMinecraftTransformer> {
        val project = ProjectBuilder.builder().build()
        project.pluginManager.apply("xyz.wagyourtail.unimined")
        val sourceSet = project.extensions.getByType(SourceSetContainer::class.java).getByName("main")
        val mc = MinecraftProvider(project, sourceSet)
        return mc to NoTransformMinecraftTransformer(project, mc)
    }

    @Test
    fun `merges classes and resources from both jars`() {
        val (mc, transformer) = provider()
        val clientDir = tempDir.resolve("client").createDirectories()
        val serverDir = tempDir.resolve("server").createDirectories()
        val clientJar = jar(clientDir, EnvType.CLIENT)
        val serverJar = jar(serverDir, EnvType.SERVER)

        writeJar(
            clientJar.path,
            mapOf(
                "net/minecraft/ClientOnly.class" to classBytes("net/minecraft/ClientOnly"),
                "net/minecraft/Shared.class" to classBytes("net/minecraft/Shared", listOf("clientMethod" to "()V")),
                "resource.txt" to "client".toByteArray(),
                "META-INF/MANIFEST.MF" to "Manifest-Version: 1.0".toByteArray(),
            ),
        )
        writeJar(
            serverJar.path,
            mapOf(
                "net/minecraft/ServerOnly.class" to classBytes("net/minecraft/ServerOnly"),
                "net/minecraft/Shared.class" to classBytes("net/minecraft/Shared", listOf("serverMethod" to "()V")),
                "resource.txt" to "server".toByteArray(),
                "b/ServerResource.txt" to "srv".toByteArray(),
            ),
        )

        val merged = transformer.internalMerge(clientJar, serverJar)
        assertTrue(merged.path.exists(), "merged jar should be written")

        val names = merged.path.readZipContents()
        assertTrue("net/minecraft/ClientOnly.class" in names, "client-only class missing: $names")
        assertTrue("net/minecraft/ServerOnly.class" in names, "server-only class missing: $names")
        assertTrue("net/minecraft/Shared.class" in names, "shared class missing: $names")
        assertTrue("b/ServerResource.txt" in names, "server-only resource missing: $names")
        // duplicate resource: client copy wins
        assertEquals(
            "client",
            merged.path.readZipInputStreamFor("resource.txt") { it.readBytes().toString(Charsets.UTF_8) },
        )
        // META-INF entries are skipped by the merge
        assertTrue(names.none { it.startsWith("META-INF/") }, "META-INF should be skipped: $names")

        // the shared class carries methods from both sides
        val shared = readClassEntry(merged.path, "net/minecraft/Shared.class")
        val methodNames = shared.methods.map { it.name }.toSet()
        assertTrue("clientMethod" in methodNames, "client method missing from merged class: $methodNames")
        assertTrue("serverMethod" in methodNames, "server method missing from merged class: $methodNames")
    }

    @Test
    fun `same-named class in both jars is merged into one entry`() {
        val (mc, transformer) = provider()
        val clientDir = tempDir.resolve("client").createDirectories()
        val serverDir = tempDir.resolve("server").createDirectories()
        val clientJar = jar(clientDir, EnvType.CLIENT)
        val serverJar = jar(serverDir, EnvType.SERVER)

        writeJar(clientJar.path, mapOf("net/minecraft/Shared.class" to classBytes("net/minecraft/Shared", listOf("clientMethod" to "()V"))))
        writeJar(serverJar.path, mapOf("net/minecraft/Shared.class" to classBytes("net/minecraft/Shared", listOf("serverMethod" to "()V"))))

        val merged = transformer.internalMerge(clientJar, serverJar)
        val names = merged.path.readZipContents().filter { it.endsWith(".class") }
        assertEquals(listOf("net/minecraft/Shared.class"), names)

        val shared = readClassEntry(merged.path, "net/minecraft/Shared.class")
        val methodNames = shared.methods.map { it.name }.toSet()
        assertEquals(setOf("clientMethod", "serverMethod"), methodNames)
    }
}
