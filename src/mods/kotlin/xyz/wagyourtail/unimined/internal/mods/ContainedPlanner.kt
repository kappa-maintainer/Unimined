package xyz.wagyourtail.unimined.internal.mods

import org.jetbrains.annotations.ApiStatus
import xyz.wagyourtail.unimined.api.mod.BundledJar
import xyz.wagyourtail.unimined.api.mod.ContainedKind
import xyz.wagyourtail.unimined.api.mod.ContainedMark
import xyz.wagyourtail.unimined.api.mod.ContainedModule
import java.io.File

/**
 * Decides which bundled entries a jar gets, from the marks the build script declared and the artifacts the project
 * actually resolved.
 *
 * Split out of [ModsProvider] because it is the part that can be wrong: everything here is a pure function of two
 * lists, so the interesting cases — a dependency that resolves to two files, one that is only available in dev names,
 * a declaration that no class path contains — are testable without a build.
 */
@ApiStatus.Internal
object ContainedPlanner {

    /**
     * Where an artifact came from, which is what decides whether its classes only make sense in a development
     * environment.
     */
    enum class Role {
        /** Declared in a remap configuration: the [Entry.file] is its production-namespace original. */
        REMAPPED,

        /** Declared in `modDevImplementation`: already in dev names, so it must not be packed. */
        DEV_NAMESPACE,

        /**
         * Declared in a remap configuration under the `dev` classifier: already in dev names.
         *
         * A remap configuration expects production names, so this is either a mistake or an artifact that is
         * published in dev names on purpose - either way packing it would put mcp names into a published jar.
         */
        DEV_CLASSIFIER,

        /** An ordinary dependency: not remapped, not a mod, safe to pack as a library. */
        PLAIN,
    }

    /** One artifact on the dev class path. */
    data class Entry(
        val module: ContainedModule?,
        val version: String?,
        val file: File,
        val role: Role,
    )

    data class Result(
        val jars: List<BundledJar>,
        val warnings: List<String>,
        val errors: List<String>,
    )

    /**
     * @param marks what the build script declared, in declaration order
     * @param entries the artifacts the project resolved, one per file
     * @param resolveFallback resolves a declaration that no class path contains, in declaration order
     */
    /**
     * @param devClassPath the files a development run will have on its class path, used only to say whether a bundled
     *                     dependency that no module identity matched is reachable there
     */
    fun plan(
        marks: List<ContainedMark>,
        entries: List<Entry>,
        resolveFallback: (ContainedMark) -> List<File>,
        devClassPath: Set<File> = emptySet(),
    ): Result {
        val jars = mutableListOf<BundledJar>()
        val warnings = mutableListOf<String>()
        val errors = mutableListOf<String>()

        for (mark in marks) {
            val matching = if (mark.module == null) emptyList() else entries.filter { it.module == mark.module }
            if (matching.isEmpty()) {
                val resolved = resolveFallback(mark)
                if (resolved.isEmpty()) {
                    errors += "${mark.notation} (declared by ${mark.declaredBy}) could not be resolved, so there is " +
                        "nothing to bundle"
                    continue
                }
                for (file in resolved) {
                    if (!isJar(file)) {
                        errors += "${mark.notation} resolves to ${file.name}, which is not a jar"
                        continue
                    }
                    // A declaration that names a file has no identity to match a module against, so the only honest
                    // question is whether that file is on the dev class path. Asking it directly avoids warning about
                    // a dependency the project does compile against.
                    if (file.absoluteFile.normalize() !in devClassPath) {
                        warnings += "${mark.notation} (declared by ${mark.declaredBy}) is bundled but is not on the dev " +
                            "class path: development runs will not see it"
                    }
                    jars += BundledJar(
                        id = null,
                        version = null,
                        file = file,
                        kind = ContainedKind.LIB,
                    )
                }
                continue
            }

            // A module can be reachable through more than one configuration; the most authoritative role decides,
            // because the roles disagree about what the same bytes will do in the produced jar. A dev-namespace copy
            // is outranked by a remapped one (the remapped entry's file is the production original again), and a
            // remapped copy outranks a plain one (same artifact, but only the remapped entry knows it is a mod).
            val entry = matching.minBy { it.role.ordinal }
            val files = matching
                .filter { it.role == entry.role }
                .map { it.file }
                .distinctBy { it.absoluteFile.normalize() }
            if (files.size > 1) {
                errors += "${mark.notation} resolves to ${files.size} files (${files.joinToString { it.name }}), so the " +
                    "jar cannot say which one it contains: declare each artifact separately"
                continue
            }
            if (entry.role == Role.DEV_NAMESPACE) {
                errors += "${mark.notation} (declared by ${mark.declaredBy}) is only available in the dev namespace, " +
                    "so bundling it would ship class names that do not resolve outside a development environment. " +
                    "Declare it in modImplementation so it gets remapped, or bundle the published artifact."
                continue
            }
            if (entry.role == Role.DEV_CLASSIFIER) {
                errors += "${mark.notation} (declared by ${mark.declaredBy}) carries the dev classifier, so it is " +
                    "already in the dev namespace and bundling it would put mcp names into a published jar. " +
                    "Bundle the artifact that remap configurations expect, which is the one in production names."
                continue
            }
            if (!isJar(entry.file)) {
                errors += "${mark.notation} resolves to ${entry.file.name}, which is not a jar"
                continue
            }
            if (mark.version != null && entry.version != null && mark.version != entry.version) {
                warnings += "${mark.notation} was declared as ${mark.version} but the class path resolved " +
                    "${entry.version}; bundling ${entry.version}, which is what the code was compiled against"
            }
            jars += BundledJar(
                id = mark.module?.id,
                version = entry.version,
                file = entry.file,
                kind = if (entry.role == Role.REMAPPED) ContainedKind.MOD else ContainedKind.LIB,
            )
        }

        // Two different files cannot share one entry name in the jar: one of them would be silently dropped.
        val byFileName = jars.groupBy { it.fileName }
        val kept = mutableListOf<BundledJar>()
        for ((fileName, group) in byFileName) {
            if (group.map { it.file.absoluteFile }.distinct().size > 1) {
                errors += "two bundled dependencies share the file name $fileName " +
                    "(${group.joinToString { it.id ?: it.fileName }}); the jar cannot contain both"
                continue
            }
            kept += group.first()
        }

        return Result(kept, warnings, errors)
    }

    private fun isJar(file: File): Boolean = file.name.endsWith(".jar", ignoreCase = true)
}
