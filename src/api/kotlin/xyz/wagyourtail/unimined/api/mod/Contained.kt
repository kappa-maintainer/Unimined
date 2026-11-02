package xyz.wagyourtail.unimined.api.mod

import org.jetbrains.annotations.ApiStatus
import java.io.File

/**
 * What a jar bundled inside this mod is, in the production game.
 *
 * Mirrors the `kind` field of the loader's `containedDeps` declaration, which is the only thing that decides how a
 * bundled entry is treated at runtime: a `MOD` is extracted into the mods directory and discovered like any other
 * mod, a `LIB` is only put on the class path.
 *
 * The kind is *derived* from the role the dependency has in the dev environment (see [BundledJar.kind]), never
 * declared twice: a dependency that is treated as a mod is bundled as a mod.
 */
enum class ContainedKind {
    MOD,
    LIB,
}

/**
 * The identity of a dependency, as Gradle resolves it: `group:name[:classifier]`.
 *
 * A classifier is part of the identity because two artifacts of one module are different files, and the loader
 * arbitrates bundled versions per identity.
 */
@ApiStatus.Internal
data class ContainedModule(
    val group: String,
    val name: String,
    val classifier: String?,
) {
    /** @return `group:name`, or `group:name:classifier` when this module has one */
    val id: String
        get() = if (classifier == null) "$group:$name" else "$group:$name:$classifier"

    override fun toString(): String = id
}

/**
 * One dependency that belongs inside the produced jar.
 *
 * Returned by [ModsConfig.getBundledJars] so that a loader-specific writer can copy [file] into the jar and
 * declare it in that loader's metadata. Unimined does not decide where inside the jar an entry goes: `META-INF/libraries`
 * (Outlands), `META-INF/jars` (Fabric) and `META-INF/jarjar` (Forge) are different conventions, and only the loader knows
 * which one it reads.
 *
 * [file] is always the **production-namespace** artifact. Bundling the dev-namespace jar instead would ship a jar whose
 * class names only resolve in a development environment, which is why a dependency that is only available in dev names
 * is refused rather than silently packed.
 */
data class BundledJar(
    /**
     * `group:name[:classifier]`, or `null` when the dependency has no coordinates (a `files(...)` dependency).
     *
     * Null is the honest answer and it is also the only one that can be written into metadata: inventing an identity
     * from the file name would put a fake library id into the jar, and a second jar bundling the real one would then
     * never be recognised as the same library.
     */
    val id: String?,
    /** The resolved version, or `null` when the dependency has no coordinates (a `files(...)` dependency). */
    val version: String?,
    /** The production-namespace jar to copy into the produced jar. */
    val file: File,
    /**
     * Whether the loader should treat this entry as a mod of its own ([ContainedKind.MOD]) or as a plain library
     * ([ContainedKind.LIB]).
     */
    val kind: ContainedKind,
) {
    /** The entry's file name, which is what a loader sees on disk once it is extracted. */
    val fileName: String
        get() = file.name

    /** Whether the loader's per-identity version arbitration applies, i.e. whether [id] and [version] are known. */
    val hasIdentity: Boolean
        get() = id != null && version != null
}

/**
 * One bundled dependency, as declared.
 *
 * The Gradle [Dependency] itself is deliberately not part of this: it is needed only to resolve a declaration that no
 * class path contains, which [ContainedMarks] does on its own. Keeping it out is what makes the decision of *what* gets
 * bundled a pure function of declarations and resolved artifacts, and therefore testable without a build.
 */
@ApiStatus.Internal
data class ContainedMark(
    /** The dependency as it was written in the build script, for messages. */
    val notation: String,
    /** The module this names, or `null` for a `files(...)` dependency. */
    val module: ContainedModule?,
    /** The version as declared, used to report a disagreement with what the class path resolved. */
    val version: String?,
    /** Where it was declared, for messages: `dependencies {}` or `mods { main }`. */
    val declaredBy: String,
)
