package xyz.wagyourtail.unimined.api.mod

import groovy.lang.Closure
import groovy.lang.DelegatesTo
import org.gradle.api.artifacts.Configuration
import xyz.wagyourtail.unimined.mapping.Namespace
import java.io.File

abstract class ModsConfig {

    fun remap(config: Configuration) {
        remap(listOf(config)) {}
    }

    fun remap(vararg config: Configuration) {
        remap(config.toList()) {}
    }

    fun remap(config: List<Configuration>) {
        remap(config) {}
    }

    fun remap(
        config: Configuration,
        action: ModRemapConfig.() -> Unit
    ) {
        remap(listOf(config), action)
    }

    fun remap(
        vararg config: Configuration,
        action: ModRemapConfig.() -> Unit
    ) {
        remap(config.toList(), action)
    }

    abstract fun remap(config: List<Configuration>, action: ModRemapConfig.() -> Unit)


    fun remap(
        config: Configuration,
        @DelegatesTo(value = ModRemapConfig::class, strategy = Closure.DELEGATE_FIRST)
        action: Closure<*>
    ) {
        remap(config) {
            action.delegate = this
            action.resolveStrategy = Closure.DELEGATE_FIRST
            action.call()
        }
    }

    fun remap(
        config: List<Configuration>,
        @DelegatesTo(value = ModRemapConfig::class, strategy = Closure.DELEGATE_FIRST)
        action: Closure<*>
    ) {
        remap(config) {
            action.delegate = this
            action.resolveStrategy = Closure.DELEGATE_FIRST
            action.call()
        }
    }

    abstract fun modImplementation(action: ModRemapConfig.() -> Unit)

    /**
     * Marks dependencies as bundled inside the produced jar, from the `mods` block.
     *
     * Equivalent to calling `Project.contained(...)` in the `dependencies` block: both write to the same project-wide
     * list, because a bundled dependency is a fact about the jar and not about one source set. The `mods` spelling
     * exists for build scripts that configure everything Unimined-related in one place, and for Kotlin scripts that
     * would otherwise need an import.
     *
     * The kind of each entry is derived from the configuration the dependency is declared in: one that is treated as a
     * mod is bundled as a mod, anything else as a library.
     *
     * @see getBundledJars
     */
    abstract fun contained(vararg dependencies: Any)

    /**
     * Resolves what the produced jar should bundle.
     *
     * Each marked dependency is matched against the resolution that already happened, so the bundled copy is the one
     * the code was compiled against; a dependency that no class path contains is resolved from its own declaration.
     * Entries that cannot be bundled — a dev-namespace jar, two files claiming one name, an unresolvable declaration —
     * are reported as errors rather than silently skipped, because every way this can go wrong produces a jar that
     * looks fine and fails at runtime.
     *
     * Call this at execution time (`doLast`, or from a task input): it resolves configurations, and a remap
     * configuration's original artifacts are only settled once the project has been evaluated.
     */
    abstract fun getBundledJars(): List<BundledJar>

    fun modImplementation(
        @DelegatesTo(value = ModRemapConfig::class, strategy = Closure.DELEGATE_FIRST)
        action: Closure<*>
    ) {
        modImplementation {
            action.delegate = this
            action.resolveStrategy = Closure.DELEGATE_FIRST
            action.call()
        }
    }

    abstract fun getClasspathAs(
        namespace: Namespace,
        classpath: Set<File>
    ): Set<File>

    /**
     * The mod classpath: every jar that came from a mod configuration, i.e. the `modImplementation`
     * artifacts (remapped into the dev namespace) plus the `modDevImplementation` entries, which are
     * already dev-namespace and are therefore never remapped. Regular `implementation`, `compileOnly`
     * and `runtimeOnly` dependencies are not part of it.
     *
     * Loaders that build their own realm use this to tell mods apart from ordinary libraries, so any
     * new consumer has to include everything that must be handed over to that realm — Cleanroom
     * turns the returned files into `outlands.dev.extrapath`, which puts each entry on
     * `LaunchClassLoader` *and* into its mod-candidate set.
     */
    abstract fun getClasspath(): Set<File>
}