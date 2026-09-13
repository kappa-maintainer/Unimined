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
     * turns the returned files into `crl.dev.extrapath`, which puts each entry on
     * `LaunchClassLoader` *and* into its mod-candidate set.
     */
    abstract fun getClasspath(): Set<File>
}