package xyz.wagyourtail.unimined.internal.mods

import org.gradle.api.InvalidUserDataException
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.Dependency
import org.gradle.api.artifacts.ResolvedArtifact
import org.jetbrains.annotations.ApiStatus
import xyz.wagyourtail.unimined.api.minecraft.MinecraftConfig
import xyz.wagyourtail.unimined.api.minecraft.patch.forge.ForgeLikePatcher
import xyz.wagyourtail.unimined.api.mod.BundledJar
import xyz.wagyourtail.unimined.api.mod.ModRemapConfig
import xyz.wagyourtail.unimined.api.mod.ModsConfig
import xyz.wagyourtail.unimined.api.mod.ContainedMark
import xyz.wagyourtail.unimined.api.mod.ContainedModule
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
     * discovered from that classpath anyway. Cleanroom's `outlands.dev.extrapath` is the one place where
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

    /**
     * Rewrites the access transformers of [modDevImplementation] entries into the dev namespace.
     *
     * Lazy because it reads the patcher and the mapping identity, both of which are only final once the project has
     * been evaluated - and because a project without such dependencies never has to pay for the mappings fingerprint.
     */
    private val devAtNormalizer by lazy {
        DevAtNormalizer(
            logger = project.logger,
            cacheDirectory = project.unimined.getLocalCache().resolve("devMods"),
            fromNamespace = minecraft.mcPatcher.prodNamespace,
            toNamespace = minecraft.mappings.devNamespace,
            isLegacy = (minecraft.mcPatcher as? ForgeLikePatcher<*>)?.remapAtToLegacy == true,
            mappingsId = { minecraft.mappings.combinedNames() },
            mappings = { minecraft.mappings.resolve() },
        )
    }

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

    private fun requireEvaluated(what: String) {
        // A remap configuration's original artifacts are settled by afterEvaluate, so resolving before that can
        // snapshot the dependency list mid-edit. Anything that needs the answer must ask at execution time.
        if (!project.state.executed) {
            throw IllegalStateException(
                "[Unimined/Contained ${project.path}:${minecraft.sourceSet.name}] $what was requested before the " +
                    "project finished evaluating. Bundled dependencies are resolved from configurations, so ask for " +
                    "them from a task (doLast, or a lazy task input) instead of from the build script body.",
            )
        }
    }

    /**
     * Every file the dev class path contains, with the role it plays there.
     *
     * The three sources are asked in order of authority: a dependency declared in a remap configuration is a mod and its
     * bundled copy has to be the production-namespace original rather than the remapped one; a dependency declared in
     * [modDevImplementation] is a mod that only exists in dev names; everything else is an ordinary library. Modules
     * already seen are not overwritten by a later source, so a dependency that is both a mod and reachable transitively
     * stays a mod.
     */
    private fun classpathEntries(marks: List<ContainedMark>): List<ContainedPlanner.Entry> {
        val entries = mutableListOf<ContainedPlanner.Entry>()
        val known = mutableSetOf<ContainedModule>()

        fun add(artifact: ResolvedArtifact, file: File, role: ContainedPlanner.Role) {
            val classifier = artifact.classifier
            val module = ContainedModule(artifact.moduleVersion.id.group, artifact.name, classifier)
            if (!known.add(module)) {
                project.logger.debug("[Unimined/Contained] {} already listed as {}, ignoring the {} copy", module, artifact.moduleVersion.id, role)
                return
            }
            entries += ContainedPlanner.Entry(module, artifact.moduleVersion.id.version, file, role)
        }

        for (provider in remapConfigsResolved.values.distinct()) {
            for ((artifact, file) in provider.prodArtifacts()) {
                // A remap configuration expects production names, so a `dev`-classified artifact reaching it is either
                // a mistake or something published in dev names on purpose. Either way it must not be packed: the
                // published jar would carry mcp names. Classifying it here is what makes the refusal possible, since
                // the file itself gives no hint.
                val role =
                    if (artifact.classifier == "dev") {
                        ContainedPlanner.Role.DEV_CLASSIFIER
                    } else {
                        ContainedPlanner.Role.REMAPPED
                    }
                add(artifact, file, role)
            }
        }
        for (configuration in listOf(modDevImplementation, modLibrary)) {
            for (artifact in configuration.resolvedConfiguration.resolvedArtifacts) {
                add(artifact, artifact.file, ContainedPlanner.Role.DEV_NAMESPACE)
            }
        }

        // Only worth resolving the whole class path when a mark was not answered by the mod configurations, which is
        // what tells a plain library apart from a mod.
        val unanswered = marks.any { mark -> mark.module != null && entries.none { it.module == mark.module } }
        if (unanswered) {
            val runtime = project.configurations.findByName(minecraft.sourceSet.runtimeClasspathConfigurationName)
            if (runtime != null) {
                for (artifact in runtime.resolvedConfiguration.resolvedArtifacts) {
                    val group = artifact.moduleVersion.id.group
                    if (group.startsWith(ModRemapProvider.REMAPPED_GROUP_PREFIX)) {
                        // The remapped output of a mod, already listed by its production original above.
                        continue
                    }
                    add(artifact, artifact.file, ContainedPlanner.Role.PLAIN)
                }
            }
        }
        return entries
    }

    private val reportedContainedMessages = mutableSetOf<String>()

    override fun contained(vararg dependencies: Any) {
        val marks = project.unimined.containedMarks
        for (dependency in dependencies) {
            val resolved = if (dependency is Dependency) dependency else project.dependencies.create(dependency)
            marks.declare(resolved, dependency.toString(), "mods { ${minecraft.sourceSet.name} }")
        }
    }

    override fun getBundledJars(): List<BundledJar> {
        val marks = project.unimined.containedMarks.declared()
        if (marks.isEmpty()) {
            return emptyList()
        }
        requireEvaluated("the bundled dependency list")
        val devClassPath = minecraft.sourceSet.runtimeClasspath.files.map { it.absoluteFile.normalize() }.toSet()
        val result = ContainedPlanner.plan(
            marks,
            classpathEntries(marks),
            project.unimined.containedMarks::resolve,
            devClassPath,
        )
        for (message in result.warnings) {
            if (reportedContainedMessages.add(message)) {
                project.logger.warn("[Unimined/Contained ${project.path}:${minecraft.sourceSet.name}] $message")
            }
        }
        if (result.errors.isNotEmpty()) {
            throw InvalidUserDataException(
                "[Unimined/Contained ${project.path}:${minecraft.sourceSet.name}] cannot bundle " +
                    "${result.errors.size} dependenc${if (result.errors.size == 1) "y" else "ies"}:\n" +
                    result.errors.joinToString("\n") { "  - $it" },
            )
        }
        return result.jars
    }

    override fun getClasspath(): Set<File> {
        // [modDevImplementation] entries are never remapped, but they are still mods, so they belong
        // in the same set as the remapped mods. A loader that builds its own realm needs them there:
        // Cleanroom's outlands.dev.extrapath puts every entry on LaunchClassLoader *and* into
        // LibraryManager.getCandidates(), which is exactly where CleanroomModDiscoverer looks for
        // mods. Leaving them out means a dev-classifier mod jar is loaded by the application
        // classloader instead and is never discovered as a mod at all.
        //
        // Their access transformers are a separate axis: the classes are dev-named by definition, but
        // the AT was authored in whichever namespace the jar was built for, and a published one is
        // srg-named. That file is what [devAtNormalizer] rewrites, on a copy - see there for why.
        return (
            remapConfigsResolved.keys.flatMap { it.resolve() } +
                listOf(modDevImplementation, modLibrary).flatMap { it.resolve() }.map { devAtNormalizer.normalize(it) }
        ).toSet()
    }

    override fun modFile(entry: File): File? {
        // Reading the class path is also what normalizes the dev jars, which is what fills the replacement map this
        // falls back to. Asking the other way around would answer "not a mod" for every rewritten jar.
        if (getClasspath().contains(entry)) return entry
        return devAtNormalizer.replacementFor(entry)
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
