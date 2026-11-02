package xyz.wagyourtail.unimined.api.mod

import org.jetbrains.annotations.ApiStatus
import java.util.Locale

/**
 * Where a jar wants to sit in the order jars are opened for coremod discovery.
 *
 * The three positions, not a number: what packs express with a `!` or `000_` filename prefix is "before everything",
 * and a score would make "why is this third" unanswerable from the file listing alone.
 */
enum class LoadOrder {
    FIRST,
    NORMAL,
    LAST,
    ;

    /** @return the spelling the loader reads, which is lower case */
    fun wireName(): String = name.lowercase(Locale.ENGLISH)

    companion object {
        /** @return the value named by [text], or `null` when it names none */
        fun parse(text: String): LoadOrder? =
            when (text.trim().lowercase(Locale.ENGLISH)) {
                "first" -> FIRST
                "normal", "default" -> NORMAL
                "last" -> LAST
                else -> null
            }
    }
}

/**
 * Which side of the game a jar belongs to.
 *
 * [BOTH] is the default. The other two make the jar disappear on the opposite side completely, so a mod depending on it
 * reports a missing dependency instead of loading against half of it.
 */
enum class Side {
    BOTH,
    CLIENT,
    SERVER,
    ;

    fun wireName(): String = name.lowercase(Locale.ENGLISH)

    companion object {
        /** @return the value named by [text], or `null` when it names none */
        fun parse(text: String): Side? =
            when (text.trim().uppercase(Locale.ENGLISH)) {
                "BOTH", "ANY", "*" -> BOTH
                "CLIENT" -> CLIENT
                "SERVER" -> SERVER
                else -> null
            }
    }
}

/**
 * The `jar` section a build writes into its `mcmod.info`: what the jar says about itself, as opposed to what it says
 * about the mods inside it.
 *
 * ```groovy
 * unimined.minecraft {
 *     outlands {
 *         jarMetadata {
 *             license "MIT"
 *             side "client"
 *             at "accesstransformer.cfg"
 *         }
 *     }
 * }
 * ```
 *
 * ## Why this exists at all
 *
 * These keys replace manifest attributes (`ModSide`, `FMLAT`, `FMLCorePlugin`, `FMLCorePluginContainsFMLMod`,
 * `ContainedDeps`), and the loader reads the declaration first and the attribute only as a fallback. Writing them here
 * is what lets a build stop hand-writing `jar { manifest { attributes ... } }`.
 *
 * ## What the build decides and what you decide
 *
 * `schemaVersion` and `license` are filled in by the build: the first is its own constant, the second is the one thing
 * it cannot invent, so it fails rather than guesses. Everything else is yours - a wrong `side` makes a mod vanish on one
 * side and a wrong `coremod` points at a class that does not exist, so nothing here has a default and nothing is
 * derived from the project.
 *
 * A key that the jar's own `mcmod.info` already declares with a *different* value fails the build instead of picking a
 * winner: both sides are explicit statements, and silently preferring one changes behaviour without saying so.
 */
@ApiStatus.Experimental
class JarMetadataConfig {

    /** Explicitly set the schema version. The build writes its own value and refuses a higher one. */
    var schemaVersion: Int? = null

    /** The jar's licence, as an SPDX identifier and/or a link to the licence text. Required to declare a section. */
    var license: String? = null

    var loadOrder: LoadOrder? = null

    var side: Side? = null

    /** The class name of this jar's `IFMLLoadingPlugin`. */
    var coreMod: String? = null

    /** Whether this jar is also a mod, on top of being loaded early. */
    var alsoMod: Boolean? = null

    /** Access transformer files this jar carries, as paths relative to `META-INF/`. */
    val accessTransformers: MutableList<String> = mutableListOf()

    /**
     * Whether each declared access transformer must exist in the produced jar.
     *
     * On by default: the loader looks the entry up and silently ignores a missing one, so a misspelt path would
     * otherwise cost a debugging session to find. Turn it off only when the file is added by a later task.
     */
    var accessTransformerValidation: Boolean = true

    fun license(value: String) {
        license = value
    }

    fun schemaVersion(value: Int) {
        schemaVersion = value
    }

    fun loadOrder(value: String) {
        loadOrder = LoadOrder.parse(value)
            ?: throw IllegalArgumentException(
                "unknown loadOrder \"$value\". Expected one of: first, normal, last.",
            )
    }

    fun side(value: String) {
        side = Side.parse(value)
            ?: throw IllegalArgumentException(
                "unknown side \"$value\". Expected one of: both, client, server.",
            )
    }

    fun coreMod(value: String) {
        coreMod = value
    }

    fun alsoMod(value: Boolean = true) {
        alsoMod = value
    }

    /** Declares access transformer paths, each relative to `META-INF/`. */
    fun at(vararg paths: String) {
        accessTransformers += paths.toList()
    }

    fun accessTransformerValidation(value: Boolean) {
        accessTransformerValidation = value
    }

    /** @return whether anything was declared, i.e. whether this build has a `jar` section to write at all */
    @ApiStatus.Internal
    fun isEmpty(): Boolean =
        schemaVersion == null &&
            license == null &&
            loadOrder == null &&
            side == null &&
            coreMod == null &&
            alsoMod == null &&
            accessTransformers.isEmpty()

    /** @return a stable description of the configuration, for a task input */
    @ApiStatus.Internal
    fun fingerprint(): String =
        buildString {
            append("schema=").append(schemaVersion)
            append(";license=").append(license)
            append(";loadOrder=").append(loadOrder?.wireName())
            append(";side=").append(side?.wireName())
            append(";coremod=").append(coreMod)
            append(";alsoMod=").append(alsoMod)
            append(";at=").append(accessTransformers.joinToString(","))
            append(";validateAt=").append(accessTransformerValidation)
        }
}
