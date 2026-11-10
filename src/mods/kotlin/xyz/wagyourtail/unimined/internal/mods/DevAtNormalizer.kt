package xyz.wagyourtail.unimined.internal.mods

import kotlinx.coroutines.runBlocking
import org.gradle.api.logging.Logger
import org.jetbrains.annotations.ApiStatus
import xyz.wagyourtail.unimined.internal.mapping.at.AccessTransformerApplier
import xyz.wagyourtail.unimined.mapping.Namespace
import xyz.wagyourtail.unimined.mapping.formats.at.ATReader
import xyz.wagyourtail.unimined.mapping.formats.at.ATWriter
import xyz.wagyourtail.unimined.mapping.tree.AbstractMappingTree
import xyz.wagyourtail.unimined.util.getShortSha1
import xyz.wagyourtail.unimined.util.openZipFileSystem
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.jar.JarFile
import kotlin.io.path.*

/**
 * Rewrites the access transformers of a jar that is consumed in the development namespace, without touching its classes.
 *
 * ## Why this is needed even though the jar is not remapped
 *
 * `modDevImplementation` holds jars whose *classes* are already in dev names, so remapping them would be wrong. Their
 * access transformers are a different matter: an AT is authored in one namespace and the namespace it lands in depends
 * on who built the jar, not on the configuration it is consumed from. A published jar carries srg names because that is
 * what production reads, and a jar produced outside Unimined keeps that file verbatim when its classes are deobfuscated
 * (ForgeGradle's `TaskExtractDepAts` only *applies* dependency ATs, it never rewrites their content). The result is a
 * jar whose classes resolve in dev and whose AT rules never match: the loader keys rules by class name, so an srg rule
 * against mcp classes is not an error, it is a rule that is silently never applied.
 *
 * So this rewrites the AT entries only, `prod -> dev`, and leaves everything else byte-identical: classes, manifest,
 * `mcmod.info`, nested jars. When the rewrite changes something, the output is a copy cached next to the other derived
 * artifacts - the input lives in the dependency cache and is read-only (and shared with every other build). When it
 * changes nothing (a jar whose dev jar was built here, so its AT is already dev-named) the jar itself is returned, so
 * that a dependency needing no fix keeps the file the resolver handed out.
 *
 * ## Why the rewrite can be done without knowing the input's namespace
 *
 * A rule naming something the mapping tree does not resolve is written back unchanged, and srg/mcp share every class
 * name while `field_1234`-style names only exist in srg. Content already in dev names therefore passes through as
 * identity - which also means a jar built by Unimined (dev-named AT since the source file is authored that way) is not
 * damaged by being handed to this.
 *
 * ## What it deliberately does not do
 *
 * - no class remap, no mixin remap, no access widener: the classes are already dev-named;
 * - no `containedDeps` strip. A jar in this configuration is dev-named, so its nested jars are not in a state this
 *   class could judge; the remap path strips the declaration because it knows the outer jar is production-named.
 */
@ApiStatus.Internal
class DevAtNormalizer(
    private val logger: Logger,
    private val cacheDirectory: Path,
    private val fromNamespace: Namespace,
    private val toNamespace: Namespace,
    private val isLegacy: Boolean,
    /** Identity of the mappings the rewrite depends on, so a mapping change re-normalizes. Read lazily: a project whose
     *  dev dependencies carry no access transformer never has to resolve its mappings for this. */
    private val mappingsId: suspend () -> String,
    private val mappings: suspend () -> AbstractMappingTree,
) {

    private var resolvedMappings: AbstractMappingTree? = null
    private var namesOnlyMappings: AbstractMappingTree? = null
    private var resolvedMappingsId: String? = null

    /** Answers repeated calls (a run transform and a remap task both ask) without re-reading the jar. */
    private val normalized = mutableMapOf<String, File>()

    /**
     * @return the file a development run should use: a rewritten copy in [cacheDirectory] when the jar carries an
     * access transformer whose rules actually move, [jar] itself otherwise. Most jars carry none, and a jar whose rules
     * are already in the target namespace is not worth a copy either, so the common case hands the resolved artifact
     * back untouched.
     */
    fun normalize(jar: File): File {
        if (fromNamespace == toNamespace) return jar
        if (!jar.isFile || !jar.name.endsWith(".jar")) return jar
        normalized[jar.absolutePath]?.let { return it }
        return normalizeUncached(jar).also { normalized[jar.absolutePath] = it }
    }

    private fun normalizeUncached(jar: File): File {
        val declared = try {
            JarAccessTransformers.of(jar.toPath(), logger)
        } catch (e: Exception) {
            logger.warn(
                "[Unimined/ModRemapper] could not read the access transformer declaration of {} ({}); " +
                    "falling back to the file name convention",
                jar.name,
                e.message,
            )
            emptyList()
        }
        val entries = atEntries(jar, declared)
        if (entries.isEmpty()) return jar

        // Rewrite into memory first: a jar whose rules are already in the target namespace (built by Unimined, whose
        // dev jar carries the AT exactly as authored) must keep its identity, otherwise every dependency that needs no
        // fix would be swapped for a copy and the class path would depend on this step existing. The comparison is on
        // the rules, not on the bytes: writing an AT back re-serialises it, so a file that needs no rewrite still comes
        // out with a different byte count (a trailing newline, for one) and comparing text would always say "changed".
        val namesOnly = namesOnlyMappings()
        val rewritten = LinkedHashMap<String, ByteArray>()
        var changed = false
        JarFile(jar).use { jarFile ->
            for (entry in entries) {
                val rules = jarFile.getInputStream(jarFile.getEntry(entry)).use {
                    AccessTransformerApplier.readAt(it, isLegacy)
                }
                val remapped = ATWriter.remapMappings(rules, namesOnly, fromNamespace, toNamespace)
                if (dataRules(rules) != dataRules(remapped)) {
                    changed = true
                }
                val output = ByteArrayOutputStream()
                AccessTransformerApplier.writeAt(remapped, output, isLegacy)
                rewritten[entry] = output.toByteArray()
            }
        }
        if (!changed) {
            logger.info(
                "[Unimined/ModRemapper] the access transformers of {} are already in {}; leaving the jar as it is",
                jar.name,
                toNamespace,
            )
            return jar
        }

        val target = cachedPath(jar)
        val fingerprint = fingerprint(jar)
        val marker = fingerprintFile(target)
        if (target.isRegularFile() && marker.isRegularFile() && marker.readText() == fingerprint) {
            logger.info("[Unimined/ModRemapper] reusing the normalized dev jar {}", target)
            return target.toFile()
        }

        target.parent.createDirectories()
        val temporary = target.resolveSibling("${target.fileName}.tmp")
        temporary.deleteIfExists()
        Files.copy(jar.toPath(), temporary)
        try {
            temporary.openZipFileSystem(mapOf("mutable" to true)).use { fs ->
                for ((entry, bytes) in rewritten) {
                    Files.write(fs.getPath(entry), bytes)
                }
            }
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: Exception) {
            temporary.deleteIfExists()
            target.deleteIfExists()
            marker.deleteIfExists()
            throw e
        }
        marker.writeText(fingerprint)
        logger.info(
            "[Unimined/ModRemapper] rewrote the access transformer{} of {} from {} to {}: {}",
            if (entries.size == 1) "" else "s",
            jar.name,
            fromNamespace,
            toNamespace,
            entries.joinToString(", "),
        )
        return target.toFile()
    }

    /**
     * The copy that replaced [entry], or `null` when it was left as it is.
     *
     * The class path a run starts from holds the resolved artifact, while [normalize] answers with the file to use; this
     * is how a caller that only has the former finds the latter. Populated by [normalize], so it says nothing before the
     * class path has been read once.
     */
    fun replacementFor(entry: File): File? = normalized[entry.absolutePath]?.takeIf { it != entry }

    /** The entries of [jar] this build has to rewrite: the declared ones, plus the ones the name convention names. */
    private fun atEntries(jar: File, declared: List<String>): List<String> =
        JarFile(jar).use { jarFile ->
            jarFile.entries().asSequence()
                .filter { !it.isDirectory }
                .map { it.name }
                .filter { AccessTransformerApplier.isAtEntry(it, declared) }
                .toList()
        }

    private fun mappingTree(): AbstractMappingTree =
        resolvedMappings ?: runBlocking { mappings() }.also { resolvedMappings = it }

    /**
     * The mapping tree reduced to names, which is what an AT rewrite needs. Built once: copying the tree is the
     * expensive part of a rewrite, and every jar of the build would otherwise pay for its own copy.
     */
    private fun namesOnlyMappings(): AbstractMappingTree =
        namesOnlyMappings ?: AccessTransformerApplier.noAccessMappings(mappingTree()).also { namesOnlyMappings = it }

    private fun mappingsIdentity(): String =
        resolvedMappingsId ?: runBlocking { mappingsId() }.also { resolvedMappingsId = it }

    /** The rules without the comments and blank lines, so formatting alone never counts as a change. */
    private fun dataRules(items: List<ATReader.ATItem>): List<ATReader.ATData> = items.filterIsInstance<ATReader.ATData>()

    private fun cachedPath(jar: File): Path =
        cacheDirectory.resolve(jar.toPath().absolute().getShortSha1()).resolve(jar.name)

    private fun fingerprintFile(target: Path): Path =
        target.resolveSibling(".${target.fileName}.unimined-fingerprint")

    /**
     * Everything that changes the rewritten bytes: the direction, the mappings, and the input itself. The input is part
     * of the key because a re-published snapshot keeps its coordinates but not its content.
     */
    private fun fingerprint(jar: File): String =
        "v1;${fromNamespace}>${toNamespace};${mappingsIdentity()};in=${jar.length()}-${jar.lastModified()}"
}
