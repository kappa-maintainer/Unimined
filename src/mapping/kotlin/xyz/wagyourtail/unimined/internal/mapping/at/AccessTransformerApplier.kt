package xyz.wagyourtail.unimined.internal.mapping.at

import kotlinx.coroutines.runBlocking
import net.fabricmc.tinyremapper.OutputConsumerPath
import net.fabricmc.tinyremapper.TinyRemapper
import okio.buffer
import okio.sink
import okio.use
import org.gradle.api.logging.Logger
import xyz.wagyourtail.commonskt.reader.CharReader
import xyz.wagyourtail.unimined.mapping.EnvType
import xyz.wagyourtail.unimined.mapping.Namespace
import xyz.wagyourtail.unimined.mapping.formats.at.ATReader
import xyz.wagyourtail.unimined.mapping.formats.at.ATWriter
import xyz.wagyourtail.unimined.mapping.formats.at.LegacyATReader
import xyz.wagyourtail.unimined.mapping.formats.at.LegacyATWriter
import xyz.wagyourtail.unimined.mapping.formats.aw.AWWriter
import xyz.wagyourtail.unimined.mapping.jvms.four.three.three.MethodDescriptor
import xyz.wagyourtail.unimined.mapping.jvms.four.three.two.FieldDescriptor
import xyz.wagyourtail.unimined.mapping.jvms.four.two.one.InternalName
import xyz.wagyourtail.unimined.mapping.tree.AbstractMappingTree
import xyz.wagyourtail.unimined.mapping.tree.MemoryMappingTree
import xyz.wagyourtail.unimined.mapping.visitor.ClassVisitor
import xyz.wagyourtail.unimined.mapping.visitor.FieldVisitor
import xyz.wagyourtail.unimined.mapping.visitor.MappingVisitor
import xyz.wagyourtail.unimined.mapping.visitor.MethodVisitor
import xyz.wagyourtail.unimined.mapping.visitor.delegate.NullDelegator
import xyz.wagyourtail.unimined.mapping.visitor.delegate.delegator
import java.io.*
import java.nio.file.Path
import kotlin.io.path.*

object AccessTransformerApplier {

    fun noAccessMappings(mappings: AbstractMappingTree): AbstractMappingTree {
        val tempMappings = MemoryMappingTree()
        mappings.accept(tempMappings.delegator(object : NullDelegator() {
            override fun visitClass(delegate: MappingVisitor, names: Map<Namespace, InternalName>): ClassVisitor? {
                return default.visitClass(delegate, names)
            }

            override fun visitField(
                delegate: ClassVisitor,
                names: Map<Namespace, Pair<String, FieldDescriptor?>>
            ): FieldVisitor? {
                return default.visitField(delegate, names)
            }

            override fun visitMethod(
                delegate: ClassVisitor,
                names: Map<Namespace, Pair<String, MethodDescriptor?>>
            ): MethodVisitor? {
                return default.visitMethod(delegate, names)
            }

        }))
        return tempMappings
    }

    fun at2aw(at: Path, output: Path, namespace: String, mappings: AbstractMappingTree, isLegacy: Boolean, logger: Logger): Path {
        val temp = noAccessMappings(mappings)
        runBlocking {
            if (isLegacy) {
                LegacyATReader.read(at.readText(), temp, envType = EnvType.JOINED, mapOf("source" to namespace))
            } else {
                ATReader.read(at.readText(), temp, envType = EnvType.JOINED, mapOf("source" to namespace))
            }
        }
        output.sink().buffer().use {
            temp.accept(AWWriter.write(it), listOf(Namespace(namespace)))
        }
        return output
    }


    /**
     * Whether one entry of a jar is an access transformer this build has to rewrite.
     *
     * Two things can name one. The file name convention every loader inherited (`accesstransformer.cfg`, `*_at.cfg`),
     * which this build has to accept because a jar may carry an AT it never declares; and a declared list - Forge's
     * `FMLAT` manifest attribute, or the `at` key of the `jar` section of `mcmod.info` that replaces it. Declared paths
     * are relative to `META-INF/`, because that is how the loader resolves them (`META-INF/<path>`), so a declaration is
     * compared against the entry *path* as well as against its file name: `at/foo.cfg` is a path with a directory in
     * it, and comparing names alone silently misses it - which is the failure mode that matters here, because a
     * forgotten AT is not an error, it is a rule that never applies.
     *
     * The name comparison is kept for the shapes that predate the declaration: a jar that ships `foo_at.cfg` at the
     * root, or a declaration that names only the file (`"accesstransformer.cfg"`), both of which are common.
     */
    fun isAtEntry(entryPath: String, atPaths: List<String> = emptyList()): Boolean {
        val path = entryPath.removePrefix("/")
        val name = path.substringAfterLast('/')
        if (name == "accesstransformer.cfg" || name.endsWith("_at.cfg")) return true
        if (atPaths.isEmpty()) return false
        return atPaths.any { declared ->
            val declaredPath = declared.trim().removePrefix("/")
            declaredPath.isNotEmpty() && (
                path == declaredPath ||
                    path == "META-INF/$declaredPath" ||
                    name == declaredPath
                )
        }
    }

    fun isAtEntry(relativePath: Path, atPaths: List<String> = emptyList()): Boolean =
        isAtEntry(relativePath.entryPath(), atPaths)

    /**
     * The entry path as it is spelled inside a jar: `/`-separated, no leading separator.
     *
     * Needed because [relativePath] may come from a zip file system (where it already is) or from the default one
     * (where it is not), and a declaration is written in the former.
     */
    private fun Path.entryPath(): String = buildString {
        for (i in 0 until nameCount) {
            if (i > 0) append('/')
            append(getName(i))
        }
    }

    /** Reads the rules of one access transformer, keeping comments and blank lines so a rewrite can round-trip them. */
    fun readAt(input: InputStream, isLegacy: Boolean): List<ATReader.ATItem> = runBlocking {
        if (isLegacy) {
            LegacyATReader.readData(CharReader(input.readBytes().decodeToString()), true)
        } else {
            ATReader.readData(CharReader(input.readBytes().decodeToString()), true)
        }
    }

    /** Writes rules back. [output] is flushed but left open, because the caller owns it. */
    fun writeAt(items: List<ATReader.ATItem>, output: OutputStream, isLegacy: Boolean) {
        val writer = output.bufferedWriter()
        try {
            if (isLegacy) {
                LegacyATWriter.writeData(items, writer::append)
            } else {
                ATWriter.writeData(items, writer::append)
            }
        } finally {
            writer.flush()
        }
    }

    /**
     * Rewrites the rules of one access transformer from [fromNamespace] into [toNamespace].
     *
     * A rule naming a class or member the mapping tree does not know is written back unchanged. That is what makes this
     * safe to apply without first guessing which namespace the file is in: srg and mcp share every class name, and a
     * `field_1234`-style name never exists in the readable namespace, so content already in the target namespace comes
     * out as identity while srg names are the only thing that actually changes.
     *
     * [output] is flushed but left open, because the caller owns it.
     */
    fun remapAt(
        input: InputStream,
        output: OutputStream,
        fromNamespace: Namespace,
        toNamespace: Namespace,
        isLegacy: Boolean,
        mappings: AbstractMappingTree,
    ) {
        val remapped = ATWriter.remapMappings(
            readAt(input, isLegacy),
            noAccessMappings(mappings),
            fromNamespace,
            toNamespace,
        )
        writeAt(remapped, output, isLegacy)
    }

    class AtRemapper(
        val logger: Logger,
        val fromNamespace: Namespace,
        val toNamespace: Namespace,
        val isLegacy: Boolean = false,
        val atPaths: List<String> = emptyList(),
        val mappings: AbstractMappingTree,
    ): OutputConsumerPath.ResourceRemapper {

        override fun canTransform(remapper: TinyRemapper, relativePath: Path): Boolean =
            isAtEntry(relativePath, atPaths)

        override fun transform(
            destinationDirectory: Path,
            relativePath: Path,
            input: InputStream,
            remapper: TinyRemapper
        ) {
            val output = destinationDirectory.resolve(relativePath)
            output.parent.createDirectories()
            output.outputStream().use {
                remapAt(input, it, fromNamespace, toNamespace, isLegacy, mappings)
            }
        }
    }
}