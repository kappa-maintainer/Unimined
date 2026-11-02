package xyz.wagyourtail.unimined.internal.minecraft.patch.forge

import org.gradle.api.InvalidUserDataException
import org.gradle.api.Project
import org.jetbrains.annotations.ApiStatus
import xyz.wagyourtail.unimined.api.mod.BundledJar
import xyz.wagyourtail.unimined.api.mod.JarMetadataConfig
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile
import xyz.wagyourtail.unimined.util.openZipFileSystem

/**
 * Writes the jar-level metadata a build declared into the jar it just produced.
 *
 * Only `mcmod.info` is touched. The manifest keys these replace (`ModSide`, `FMLAT`, `FMLCorePlugin`,
 * `FMLCorePluginContainsFMLMod`, `ContainedDeps`) are read as a fallback for jars that predate the section, so writing
 * both would create two sources that can disagree - the loader warns when they do, but the second copy exists only to
 * be a duplicate.
 */
@ApiStatus.Internal
class OutlandsMetadataWriter(
    private val project: Project,
    private val config: JarMetadataConfig,
) {

    /**
     * @param jar the produced jar, edited in place
     * @throws InvalidUserDataException when the section cannot be written as declared
     */
    fun apply(
        jar: Path,
        bundled: List<BundledJar> = emptyList(),
    ) {
        if (config.isEmpty() && bundled.isEmpty()) {
            return
        }
        jar.openZipFileSystem(mapOf("mutable" to true)).use { fs ->
            val copied = copyBundledJars(fs, jar, bundled)

            val entry = fs.getPath(McmodInfoMerger.ENTRY_NAME)
            val existing = if (entry.isRegularFile()) Files.readAllBytes(entry) else null

            when (val result = McmodInfoMerger.merge(existing, config, copied.map { it.first })) {
                is McmodInfoMerger.Result.Merged -> {
                    Files.write(entry, result.bytes)
                    result.notes.forEach { project.logger.warn("[Unimined/Outlands ${project.path}] {}", it) }
                    project.logger.info(
                        "[Unimined/Outlands] wrote the jar section into {} ({})",
                        jar.fileName,
                        describe(copied.size),
                    )
                    validateContainedDeps(fs, jar, result.containedDepPaths)
                }
                is McmodInfoMerger.Result.Failed -> {
                    throw InvalidUserDataException(
                        "[Unimined/Outlands ${project.path}] cannot write the jar section of ${jar.fileName}:\n" +
                            result.errors.joinToString("\n") { "  - $it" },
                    )
                }
                McmodInfoMerger.Result.Unchanged -> Unit
            }

            validateAccessTransformers(fs, jar)
        }
    }

    /**
     * Puts each bundled dependency next to the mods it belongs with, inside the jar.
     *
     * The location is the one the loader reads: it looks for the entry's path as declared, then retries under
     * `META-INF/libraries/`. The file name is kept as the artifact publishes it, because a bundled *mod* is extracted
     * into the mods directory and an existing file of the same name is reused rather than rewritten - which only works
     * if both are called the same thing.
     *
     * @return one declared entry per copied file, with the identity the resolution gave it
     */
    private fun copyBundledJars(
        fs: java.nio.file.FileSystem,
        jar: Path,
        bundled: List<BundledJar>,
    ): List<Pair<McmodInfoMerger.ProvidedDep, Path>> {
        if (bundled.isEmpty()) {
            return emptyList()
        }
        val libraries = fs.getPath("META-INF/libraries")
        Files.createDirectories(libraries)
        return bundled.map { dep ->
            val target = libraries.resolve(dep.fileName)
            if (target.isRegularFile()) {
                // The author may have placed the same file in resources as well; the resolved artifact is what the build
                // vouches for, so it wins, and saying so is cheaper than a mystery when the two differ.
                project.logger.info(
                    "[Unimined/Outlands] {} already contained {}; replacing it with the resolved artifact",
                    jar.fileName,
                    dep.fileName,
                )
            }
            Files.copy(dep.file.toPath(), target, StandardCopyOption.REPLACE_EXISTING)
            McmodInfoMerger.ProvidedDep(
                path = "META-INF/libraries/${dep.fileName}",
                id = dep.id,
                version = dep.version,
                kind = dep.kind.name,
            ) to target
        }
    }

    /**
     * Checks that everything the finished `mcmod.info` declares is really in the jar.
     *
     * The entries this build copies are checked too, on purpose: it costs one lookup each and it catches the case where
     * a declaration names a path the copy step wrote somewhere else. An entry the author declared by hand is also
     * covered, and that is the case nothing else can notice - the loader would extract nothing and report nothing.
     */
    private fun validateContainedDeps(
        fs: java.nio.file.FileSystem,
        jar: Path,
        declaredPaths: List<String>,
    ) {
        if (declaredPaths.isEmpty()) {
            return
        }
        val missing =
            declaredPaths.filter { path ->
                !fs.getPath(path).exists() && !fs.getPath("META-INF").resolve(path).exists()
            }
        if (missing.isNotEmpty()) {
            throw InvalidUserDataException(
                "[Unimined/Outlands ${project.path}] ${jar.fileName} declares bundled dependencies that are not in the " +
                    "jar: ${missing.joinToString { it }}. The loader would find nothing to extract and say nothing " +
                    "about it. Add the file, or remove the entry from the mcmod.info.",
            )
        }
    }

    /**
     * Checks that every declared access transformer is actually in the jar.
     *
     * The loader looks the entry up and ignores it when it is absent - `ModAccessTransformer.addJar` is a `if (entry !=
     * null)` with no else - so a misspelt path costs nothing at build time and everything at runtime. This is the only
     * place that can notice.
     */
    private fun validateAccessTransformers(
        fs: java.nio.file.FileSystem,
        jar: Path,
    ) {
        if (config.accessTransformers.isEmpty() || !config.accessTransformerValidation) {
            return
        }
        val missing =
            config.accessTransformers.filter { path ->
                val resolved = fs.getPath("META-INF").resolve(path)
                !resolved.exists()
            }
        if (missing.isNotEmpty()) {
            throw InvalidUserDataException(
                "[Unimined/Outlands ${project.path}] ${jar.fileName} declares access transformers that are not in the " +
                    "jar: ${missing.joinToString { "META-INF/$it" }}. The loader skips a missing one silently, so this " +
                    "would produce a jar that looks right and applies no access transformer. Either add the file " +
                    "under META-INF/ or drop it from jarMetadata { at ... }. " +
                    "Set accessTransformerValidation false to opt out.",
            )
        }
    }

    private fun describe(copied: Int): String =
        buildString {
            if (copied > 0) {
                append(copied).append(" bundled dependenc").append(if (copied == 1) "y" else "ies")
            }
            config.license?.let {
                if (isNotEmpty()) append(", ")
                append("license ").append(it)
            }
            config.side?.let {
                if (isNotEmpty()) append(", ")
                append("side ").append(it.wireName())
            }
            config.loadOrder?.let {
                if (isNotEmpty()) append(", ")
                append("loadOrder ").append(it.wireName())
            }
            config.coreMod?.let {
                if (isNotEmpty()) append(", ")
                append("coremod ").append(it)
            }
            config.alsoMod?.let {
                if (isNotEmpty()) append(", ")
                append("alsoMod ").append(it)
            }
            if (config.accessTransformers.isNotEmpty()) {
                if (isNotEmpty()) append(", ")
                append("at ").append(config.accessTransformers.joinToString(", "))
            }
        }
}
