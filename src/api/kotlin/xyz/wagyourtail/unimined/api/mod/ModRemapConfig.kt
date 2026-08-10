package xyz.wagyourtail.unimined.api.mod

import groovy.lang.Closure
import groovy.lang.DelegatesTo
import net.fabricmc.tinyremapper.TinyRemapper
import org.gradle.api.artifacts.Configuration
import org.jetbrains.annotations.ApiStatus
import xyz.wagyourtail.unimined.api.mapping.mixin.MixinRemapOptions
import xyz.wagyourtail.unimined.mapping.Namespace

abstract class ModRemapConfig(val configurations: Set<Configuration>) {

    private val excludes = mutableListOf<Pair<String?, String?>>()

    /**
     * Exclude resolved artifacts from being remapped by module coordinates. Matches on the
     * module group and/or module name; either may be null to act as a wildcard. Excluded
     * artifacts are skipped during remap, are not published to the modTransform repository and
     * are not supplied back to any configuration — they stay on the classpath as the original
     * (unremapped) jars, which is the right choice for language/toolchain libraries such as the
     * Scala runtime that never reference Minecraft classes.
     *
     * @param group exclude every module in this group, or null to match any group
     * @param module exclude this exact module name, or null to match any module
     */
    fun exclude(group: String? = null, module: String? = null) {
        excludes += group to module
    }

    /**
     * Groovy-friendly overload mirroring Gradle's `exclude(group: ..., module: ...)` syntax.
     */
    fun exclude(options: Map<*, *>) {
        exclude(options["group"] as? String, options["module"] as? String)
    }

    @ApiStatus.Internal
    fun shouldExclude(group: String, module: String): Boolean =
        excludes.any { (g, m) -> (g == null || g == group) && (m == null || m == module) }

    @set:ApiStatus.Internal
    abstract var namespace: Namespace

    abstract fun namespace(ns: String)

    abstract fun catchAWNamespaceAssertion()

    @set:ApiStatus.Experimental
    abstract var remapAtToLegacy: Boolean

    /**
     * @since 1.1.0
     */
    abstract fun mixinRemap(action: MixinRemapOptions.() -> Unit)

    /**
     * @since 1.1.0
     */
    fun mixinRemap(
        @DelegatesTo(MixinRemapOptions::class, strategy = Closure.DELEGATE_FIRST)
        action: Closure<*>
    ) {
        mixinRemap {
            action.delegate = this
            action.resolveStrategy = Closure.DELEGATE_FIRST
            action.call()
        }
    }


    @ApiStatus.Experimental
    abstract fun remapper(remapperBuilder: TinyRemapper.Builder.() -> Unit)
}