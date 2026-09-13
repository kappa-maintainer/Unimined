package xyz.wagyourtail.unimined.internal.mods

import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.jetbrains.annotations.ApiStatus
import xyz.wagyourtail.unimined.api.minecraft.MinecraftConfig
import xyz.wagyourtail.unimined.api.mod.ModRemapConfig
import xyz.wagyourtail.unimined.api.mod.ModsConfig
import xyz.wagyourtail.unimined.api.unimined
import xyz.wagyourtail.unimined.mapping.Namespace
import xyz.wagyourtail.unimined.util.FinalizeOnRead
import xyz.wagyourtail.unimined.util.defaultedMapOf
import xyz.wagyourtail.unimined.util.getField
import xyz.wagyourtail.unimined.util.withSourceSet
import java.io.File
import java.nio.file.Path
import kotlin.io.path.createDirectories

class ModsProvider(val project: Project, val minecraft: MinecraftConfig) : ModsConfig() {

    private val remapConfigs = mutableMapOf<Set<Configuration>, ModRemapProvider.() -> Unit>()

    val modImplementation = project.configurations.maybeCreate("modImplementation".withSourceSet(minecraft.sourceSet)).also {
        minecraft.sourceSet.apply {
            compileClasspath += it
            runtimeClasspath += it
        }
        remap(it)
    }

    /**
     * Companion to [modImplementation] for mods that are **already in the dev namespace**, most
     * commonly jars published with a `dev` classifier (for example
     * `com.cleanroommc:scalar:1.0.0:dev`).
     *
     * Dependencies declared here are added to the mod compile/runtime classpath exactly as
     * declared, are never remapped and are never copied into the modTransform repository. They are
     * nonetheless still treated as mods: they are part of [getClasspath], so a loader that builds
     * its own realm hands them to the mod classloader and to mod discovery instead of leaving them
     * behind on the application classpath.
     *
     * Plain libraries that only need to be on the classpath (a language runtime, ASM, ...) do not
     * need this configuration and should use the regular `implementation`, `compileOnly` or
     * `runtimeOnly` configurations — those are never remapped either.
     *
     * On loaders that build no realm of their own (Fabric, Forge) this is behaviourally equivalent to
     * `implementation`: there the jar only has to sit on the compile/runtime classpath, and mods are
     * discovered from that classpath anyway. Cleanroom's `crl.dev.extrapath` is the one place where
     * the distinction between a mod and a library is observable today.
     */
    val modDevImplementation = project.configurations.maybeCreate("modDevImplementation".withSourceSet(minecraft.sourceSet)).also {
        minecraft.sourceSet.apply {
            compileClasspath += it
            runtimeClasspath += it
        }
    }

    /**
     * Former name of [modDevImplementation], kept as an alias so existing builds keep working.
     *
     * Deprecated because "library" described the wrong use case: the artifacts that actually need
     * this configuration are dev-namespace mods, not libraries.
     */
    val modLibrary = project.configurations.maybeCreate("modLibrary".withSourceSet(minecraft.sourceSet)).also {
        minecraft.sourceSet.apply {
            compileClasspath += it
            runtimeClasspath += it
        }
    }

    private var default by FinalizeOnRead<ModRemapProvider.() -> Unit> {}

    val remapConfigsResolved = mutableMapOf<Configuration, ModRemapProvider>()

    fun modTransformFolder(): Path {
        return project.unimined.getLocalCache().resolve("modTransform").createDirectories()
    }

    override fun remap(config: List<Configuration>, action: ModRemapConfig.() -> Unit) {
        val intersect = remapConfigs.keys.firstOrNull { config.intersect(it).isNotEmpty() }?.intersect(config.toSet())
        if (intersect != null) {
            throw IllegalArgumentException("cannot have configuration(s) in multiple remaps $intersect")
        }
        val configSet = config.toSet()
        val old = remapConfigs[configSet]
        remapConfigs[configSet] = {
            if (old != null) {
                old()
            } else {
                default()
            }
            action()
        }
    }

    @ApiStatus.Internal
    fun default(action: ModRemapConfig.() -> Unit) {
        val prev: FinalizeOnRead<ModRemapProvider.() -> Unit> = ModsProvider::class.getField("default")!!.getDelegate(this) as FinalizeOnRead<ModRemapProvider.() -> Unit>
        val old: ModRemapProvider.() -> Unit = prev.value as ModRemapProvider.() -> Unit
        default = {
            old(this)
            action()
        }
    }

    override fun modImplementation(action: ModRemapConfig.() -> Unit) {
        val old = remapConfigs[setOf(modImplementation)]
        remapConfigs[setOf(modImplementation)] = {
            if (old != null) {
                old()
            } else {
                default()
            }
            action()
        }
    }

    fun afterEvaluate() {
        if (modLibrary.dependencies.isNotEmpty()) {
            project.logger.warn(
                "[Unimined/Mods ${project.path}:${minecraft.sourceSet.name}] the `modLibrary` configuration is " +
                    "deprecated, rename it to `modDevImplementation` (it is an alias, so nothing else changes).",
            )
        }
        for ((config, action) in remapConfigs) {
            val remapSettings = ModRemapProvider(config, project, minecraft)
            for (c in config) {
                remapConfigsResolved[c] = remapSettings
            }
            remapSettings.action()
            if (minecraft.obfuscated) remapSettings.doRemap()
        }
    }

    override fun getClasspath(): Set<File> {
        // [modDevImplementation] entries are never remapped, but they are still mods, so they belong
        // in the same set as the remapped mods. A loader that builds its own realm needs them there:
        // Cleanroom's crl.dev.extrapath puts every entry on LaunchClassLoader *and* into
        // LibraryManager.getCandidates(), which is exactly where CleanroomModDiscoverer looks for
        // mods. Leaving them out means a dev-classifier mod jar is loaded by the application
        // classloader instead and is never discovered as a mod at all.
        return (
            remapConfigsResolved.keys.flatMap { it.resolve() } +
                modDevImplementation.resolve() +
                modLibrary.resolve()
        ).toSet()
    }

    override fun getClasspathAs(namespace: Namespace, classpath: Set<File>): Set<File> {
        val remapCp = classpath.associateWith { file ->
            remapConfigsResolved.values.firstNotNullOfOrNull { conf -> conf.getConfigForFile(file, namespace)?.let { conf to it } }
        }
        val nonRemap = remapCp.mapNotNull { if (it.value == null) it.key else null }
        project.logger.info("[Unimined/ModRemapper] getting classpath as $namespace")
        val remap = remapCp.values.filterNotNull()
        val map = defaultedMapOf<ModRemapProvider, MutableSet<Configuration>> { mutableSetOf() }
        for ((m, c) in remap) {
            map[m].add(c)
        }
        val remapOutputs = mutableSetOf<Configuration>()
        for (m in map.keys) {
            val def = defaultedMapOf<Configuration, Configuration> { project.configurations.detachedConfiguration() }
            m.doRemap(namespace, def)
            remapOutputs.addAll(def.values)
        }
        val files = remapOutputs.flatMap { it.resolve() }
        for (file in nonRemap) {
            project.logger.info("[Unimined/ModRemapper]    unremapped: $file")
        }
        for (file in files) {
            project.logger.info("[Unimined/ModRemapper]    remapped: $file")
        }
        return nonRemap.toSet() + files
    }

}
