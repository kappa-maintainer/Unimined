package xyz.wagyourtail.unimined.internal.minecraft.patch.forge

import com.google.gson.JsonObject
import kotlinx.coroutines.runBlocking
import org.apache.commons.lang3.ArchUtils
import org.apache.commons.lang3.SystemUtils
import org.gradle.api.JavaVersion
import org.gradle.api.Project
import org.gradle.api.artifacts.Dependency
import org.gradle.api.file.FileCollection
import org.jetbrains.annotations.ApiStatus
import xyz.wagyourtail.unimined.api.minecraft.patch.forge.OutlandsPatcher
import xyz.wagyourtail.unimined.api.minecraft.MinecraftJar
import xyz.wagyourtail.unimined.api.minecraft.task.AbstractRemapJarTask
import xyz.wagyourtail.unimined.api.mod.JarMetadataConfig
import xyz.wagyourtail.unimined.api.runs.RunConfig
import xyz.wagyourtail.unimined.api.unimined
import xyz.wagyourtail.unimined.internal.mapping.task.ExportMappingsTaskImpl
import xyz.wagyourtail.unimined.internal.minecraft.MinecraftProvider
import xyz.wagyourtail.unimined.internal.minecraft.patch.forge.fg3.FG3MinecraftTransformer
import xyz.wagyourtail.unimined.internal.minecraft.patch.jarmod.JarModMinecraftTransformer
import xyz.wagyourtail.unimined.mapping.EnvType
import xyz.wagyourtail.unimined.internal.minecraft.resolver.Library
import xyz.wagyourtail.unimined.internal.minecraft.resolver.parseAllLibraries
import xyz.wagyourtail.unimined.internal.minecraft.transform.fixes.FixParamAnnotations
import xyz.wagyourtail.unimined.util.FinalizeOnRead
import xyz.wagyourtail.unimined.util.LazyMutable
import xyz.wagyourtail.unimined.util.getFiles
import xyz.wagyourtail.unimined.mapping.formats.tsrg.TsrgV1Writer
import java.io.File
import java.nio.file.Files
import java.net.URI
import java.nio.file.FileSystem
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.Callable
import kotlin.io.path.absolutePathString
import kotlin.io.path.createDirectories
import xyz.wagyourtail.unimined.internal.minecraft.patch.forge.fg3.mcpconfig.MCPConfig

open class OutlandsMinecraftTransformer(project: Project, provider: MinecraftProvider) : ForgeLikeMinecraftTransformer(project, provider, "Outlands"),
    OutlandsPatcher<JarModMinecraftTransformer> {

    companion object {
        /** Proxies the Outlands, Arcseekers, Forge and MCP hosts this loader resolves from. Override with `-Punimined.mirror`. */
        const val DEFAULT_MIRROR: String = "https://nexus.jsdu.cn/repository/"
    }

    override var forgeTransformer: JarModMinecraftTransformer by FinalizeOnRead(OutlandsFG3(project, this))

    init {
        atDependency = project.dependencies.create("top.outlands:accesstransformers:8.3.0")
        atMainClass = "net.minecraftforge.accesstransformer.TransformerProcessor"
    }

    override val jarMetadata = JarMetadataConfig()

    /**
     * Writes the declared jar-level metadata into the jar this task produced.
     *
     * Runs after the loader's own work (`super`), because the metadata describes the finished jar and a later step could
     * still add entries. `output` is the remap task's intermediate file, which is what gets copied into the task's real
     * output afterwards - the same point at which the other loaders insert their nested jars.
     */
    override fun afterRemapJarTask(
        remapJarTask: AbstractRemapJarTask,
        output: Path,
    ) {
        super.afterRemapJarTask(remapJarTask, output)
        OutlandsMetadataWriter(project, jarMetadata).apply(output, provider.mods.getBundledJars())
    }

    /**
     * The declared metadata is part of what the task produces, so changing it has to invalidate the task.
     *
     * Without this a project that adds `side "client"` sees nothing happen, because the remap ran already and its output
     * is still up to date.
     */
    override fun configureRemapJar(task: AbstractRemapJarTask) {
        super.configureRemapJar(task)
        // A Callable, not a value: `configure` runs when the task is realized, which can be before a build script
        // reaches its `outlands { jarMetadata { ... } }` block (a plugin applying it later, an `allprojects` block).
        // Capturing the value here would freeze the configuration at whatever it was at that moment.
        task.inputs.property("unimined.outlands.jarMetadata", Callable { jarMetadata.fingerprint() })
        // Both are Callables for the same reason as above, and both are read at execution time by the writer, so the
        // task has to be invalidated by exactly what it read.
        task.inputs.files(Callable { provider.mods.getBundledJars().map { it.file } })
        task.inputs.property(
            "unimined.outlands.containedDeps",
            Callable { provider.mods.getBundledJars().map { "${it.id}:${it.version}:${it.kind}" } },
        )
        // A bundled declaration that names a file another task writes gives no ordering on its own: resolving it during
        // execution is too late for Gradle to add the edge, so the producers are declared here.
        task.dependsOn(Callable { project.unimined.containedMarks.buildDependencies() })
    }

    @get:ApiStatus.Internal
    @set:ApiStatus.Experimental
    var srgToMCPAsTSRG: Path by FinalizeOnRead(LazyMutable {
        provider.localCache.resolve("mappings").createDirectories().resolve("srg2mcp.tsrg").apply {
            val export = ExportMappingsTaskImpl.ExportImpl(provider.mappings).apply {
                location = toFile()
                type = TsrgV1Writer
                sourceNamespace = prodNamespace
                targetNamespace = setOf(provider.mappings.devNamespace)
            }
            export.validate()
            runBlocking {
                export.exportFunc(provider.mappings.resolve())
            }
        }
    })

    private val vanillaExcludesSet = setOf(
        "com.mojang:patchy:",
        "oshi-project:oshi-core:",
        "com.ibm.icu:icu4j-core-mojang:",
        "net.java.jutils:",
        "org.lwjgl.lwjgl:",
        "io.netty:netty-all:",
        "net.java.jinput:jinput-platform:"
    )

    /**
     * Consume the dev environment the loader publishes instead of rebuilding it: `-srg` is the tree this build
     * compiles against (patched Minecraft + loader, srg names) and `-dev-sources` its sources. Both are the result of
     * the chain the old path runs here (mcp_config -> decompile -> 543 patches -> rename to mcp), so using them only
     * skips work. Set `-Poutlands.materializedDev=false` to fall back to that chain.
     */
    val materializedDev: Boolean =
        project.findProperty("outlands.materializedDev")?.toString()?.toBoolean() ?: true

    private fun loaderArtifact(classifier: String, extension: String): File {
        val forgeDep = forge.dependencies.first()
        val deps = project.configurations.detachedConfiguration()
        val dependency =
            project.dependencies.create("${forgeDep.group}:${forgeDep.name}:${forgeDep.version}:$classifier@$extension")
        deps.dependencies.add(dependency)
        return deps.getFiles(dependency).singleFile
    }

    /** The published srg-named classes (patched Minecraft + loader). */
    val srgClassesJar: File by lazy { loaderArtifact("srg", "jar") }

    /** The published mcp-named sources, including the patched Minecraft tree. */
    val devSourcesJar: File by lazy { loaderArtifact("dev-sources", "jar") }

    override fun addMavens() {
        // One repository in front of everything this loader resolves, because the hosts below are all proxied by it and
        // a cold build otherwise spends most of its time waiting for them one at a time. It is a fallback chain rather
        // than a replacement: the originals stay in the list after it, so an artifact the mirror does not carry is still
        // reachable.
        //
        // The URL is a property so the choice is the build's, not this class's: -Punimined.mirror=<url> points at
        // another mirror, an empty value goes straight to the originals.
        val mirror = (project.findProperty("unimined.mirror") as String?) ?: DEFAULT_MIRROR
        if (mirror.isNotBlank()) {
            val repo = project.repositories.maven { repo ->
                repo.name = "outlands-mirror"
                repo.url = URI.create(mirror)
                repo.content { it.excludeGroup("curse.maven") }
            }
            // Declared first, so it is consulted first: `repositories.maven {}` appends, and appending a mirror after
            // the slow hosts is the same as not having one.
            project.repositories.remove(repo)
            project.repositories.addFirst(repo)
        }
        project.unimined.outlandsMaven()
        project.unimined.minecraftForgeMaven()
        // Our own releases (and the loader's dependency set: cleanmix, mixinextras-common) do not live on the
        // generic mavens, the second one is the mirror those artifacts are served from.
        project.repositories.maven { repo ->
            repo.name = "outlands-releases"
            repo.url = URI.create("https://maven.outlands.top/releases/")
            repo.content { it.excludeGroup("curse.maven") }
        }
        project.repositories.maven { repo ->
            repo.name = "outlands-deps"
            repo.url = URI.create("https://repo.cleanroommc.com/releases/")
            repo.content { it.excludeGroup("curse.maven") }
        }
        project.repositories.removeIf { it.name == "minecraft" }
        project.repositories.maven { repo ->
            repo.name = "minecraft"
            repo.url = URI.create("https://libraries.minecraft.net/")
            repo.content {
                it.excludeGroup("ca.weblite")
            }
        }
    }

    override fun loader(dep: Any, action: Dependency.() -> Unit) {
        forge.dependencies.add(if (dep is String && !dep.contains(":")) {
            if (provider.version != "1.12.2") {
                throw IllegalStateException("Outlands only supports 1.12.2")
            }
            project.dependencies.create("top.outlands:outlands:${dep}@jar")
        } else {
            project.dependencies.create(dep)
        }.apply(action))

        if (forge.dependencies.isEmpty()) {
            throw IllegalStateException("No forge dependency found!")
        }

        if (forge.dependencies.size > 1) {
            throw IllegalStateException("Multiple forge dependencies found, make sure you only have one forge dependency!")
        }

        val forgeDep = forge.dependencies.first()

        if (forgeDep.group != "top.outlands" || forgeDep.name != "outlands") {
            throw IllegalStateException("Invalid outlands dependency found, if you are using multiple dependencies in the forge configuration, make sure the last one is the forge dependency!")
        }
    }

    override val versionJsonJar: File by lazy {
        val forgeDep = forge.dependencies.first()

        val deps = project.configurations.detachedConfiguration()
        val dependency = project.dependencies.create("${forgeDep.group}:${forgeDep.name}:${forgeDep.version}:installer@jar")
        deps.dependencies.add(dependency)
        deps.getFiles(dependency).singleFile
    }

    override fun parseVersionJson(json: JsonObject) {
        val libraries = parseAllLibraries(json.getAsJsonArray("libraries"))
        mainClass = json.get("mainClass").asString
        val args = json.get("minecraftArguments").asString
        provider.addLibraries(libraries.filter {
            !it.name.startsWith("top.outlands:outlands:")
        })
        tweakClassClient = args.split("--tweakClass")[1].trim().substringBefore(" ")
    }

    override fun libraryFilter(library: Library): Library? {
        if (vanillaExcludesSet.any { library.name.startsWith(it) }) {
            return null
        }
        if (library.name.startsWith("org.lwjgl") && library.name.substringAfterLast(":").startsWith("2")) {
            return null
        }
        if (library.name.startsWith("net.java.dev.jna:platform:")) {
            return null
        }
        if (library.name.startsWith("org.apache.httpcomponents:")) {
            return null
        }
        if (library.name.startsWith("ore.lwjgl:") && library.name.split(":").size > 3) {
            if (library.name.split(":")[3] != getLwjglClassifier()) {
                return null
            }
        }
        if (library.name.startsWith("ca.weblite:java-objc-bridge:")) {
            return library.copy(natives = emptyMap())
        }
        return super.libraryFilter(library)
    }

    private fun getLwjglClassifier(): String {
        val processor = ArchUtils.getProcessor()
        var classifier = ""
        if (SystemUtils.IS_OS_WINDOWS) {
            classifier += "windows"
            if (processor.isAarch64) {
                classifier += "-arm64"
            } else if (processor.is32Bit) {
                classifier += "-x86"
            }
        } else if (SystemUtils.IS_OS_LINUX) {
            classifier += "linux"
            if (processor.isAarch64) {
                classifier += "-arm64"
            } else if (processor.isRISCV) {
                classifier += "-riscv64"
            } else if (processor.isPPC) {
                classifier += "-ppc64le"
            } else if (!processor.isX86) {
                classifier += "-arm32"
            }
        } else if (SystemUtils.IS_OS_MAC) {
            classifier += "macos"
            if (!processor.isX86) {
                classifier += "-arm64"
            }
        } else if (SystemUtils.IS_OS_FREE_BSD) {
            classifier = "freebsd"
        }
        return classifier
    }

    override fun applyClientRunTransform(config: RunConfig) {
        super.applyClientRunTransform(config)
        config.properties["mcp_to_srg"] = {
            srgToMCPAsTSRG.absolutePathString()
        }
        // FML reads the deobf map from this property in a dev environment (the resource inside the jar is only used
        // when runtime deobfuscation is on, i.e. in production). The loader's own runs bridge it through
        // MCP_TO_SRG -> com.cleanroommc.boot.Main, but a mod dev run launches Foundation directly.
        config.jvmArgs("-Dnet.minecraftforge.gradle.GradleStart.srg.srg-mcp=${srgToMCPAsTSRG.absolutePathString()}")
        val modClassPath = provider.mods.getClasspath()
        val extraPath = mutableSetOf<File>()
        project.logger.info("Path before: {}", config.classpath.files.toString())
        project.logger.info("Mod paths: {}", modClassPath.toString())
        config.classpath = config.classpath.filter { 
            if (it.isDirectory || modClassPath.contains(it)) {
                extraPath += it
                false
            } else {
                true
            }
        }
        project.logger.info("Path after: {}", config.classpath.files.toString())
        project.logger.info("Extra mod paths: {}", extraPath.toString())
        config.jvmArgs("-Doutlands.dev.extrapath=${extraPath.joinToString(File.pathSeparator) { it.absolutePath }}")
        config.javaVersion = JavaVersion.VERSION_25
    }

    override fun applyServerRunTransform(config: RunConfig) {
        super.applyServerRunTransform(config)
        config.properties["mcp_to_srg"] = {
            srgToMCPAsTSRG.absolutePathString()
        }
        // FML reads the deobf map from this property in a dev environment (the resource inside the jar is only used
        // when runtime deobfuscation is on, i.e. in production). The loader's own runs bridge it through
        // MCP_TO_SRG -> com.cleanroommc.boot.Main, but a mod dev run launches Foundation directly.
        config.jvmArgs("-Dnet.minecraftforge.gradle.GradleStart.srg.srg-mcp=${srgToMCPAsTSRG.absolutePathString()}")
        val modClassPath = provider.mods.getClasspath()
        val extraPath = mutableSetOf<File>()
        project.logger.info("Path before: {}", config.classpath.files.toString())
        project.logger.info("Mod paths: {}", modClassPath.toString())
        config.classpath = config.classpath.filter {
            if (it.isDirectory || modClassPath.contains(it)) {
                extraPath += it
                false
            } else {
                true
            }
        }
        project.logger.info("Path after: {}", config.classpath.files.toString())
        project.logger.info("Extra mod paths: {}", extraPath.toString())
        config.jvmArgs("-Doutlands.dev.extrapath=${extraPath.joinToString(File.pathSeparator) { it.absolutePath }}")
        config.javaVersion = JavaVersion.VERSION_25
    }

    class OutlandsFG3(project: Project, private val olParent: OutlandsMinecraftTransformer): FG3MinecraftTransformer(project, olParent) {

        override val transform: MutableList<(FileSystem) -> Unit> = listOf<(FileSystem) -> Unit>(
            FixParamAnnotations::apply,
        ).toMutableList()

        // override binpatches.pack.lzma meaning it's `userdev3`
        override val userdevClassifier: String = "userdev"

        /**
         * The published jar already is the patched tree, so there is nothing to decompile or patch here. Everything
         * downstream (srg -> mcp remap by Unimined, ATs of the mod, runs) is unchanged.
         */
        override fun transform(minecraft: MinecraftJar): MinecraftJar {
            if (!olParent.materializedDev || minecraft.envType != EnvType.JOINED) return super.transform(minecraft)
            project.logger.lifecycle("[Unimined/Outlands] using the loader's published classes (-srg) instead of running mcp_config")
            val forgeDep = olParent.forge.dependencies.first()
            return MinecraftJar(
                parentPath = minecraft.parentPath,
                name = "outlands",
                envType = minecraft.envType,
                version = forgeDep.version ?: "0",
                patches = minecraft.patches + "materialized",
                mappingNamespace = defaultProdNamespace(),
                awOrAt = null,
                path = olParent.srgClassesJar.toPath(),
            )
        }

        override fun createSourcesJar(
            classpath: FileCollection,
            patchedJar: Path,
            outputPath: Path,
            linemappedPath: Path?,
            side: EnvType,
        ) {
            if (!olParent.materializedDev || side != EnvType.JOINED) {
                super.createSourcesJar(classpath, patchedJar, outputPath, linemappedPath, side)
                return
            }
            project.logger.lifecycle("[Unimined/Outlands] using the loader's published sources (-dev-sources)")
            Files.copy(olParent.devSourcesJar.toPath(), outputPath, StandardCopyOption.REPLACE_EXISTING)
        }

        /**
         * Outlands keeps its 543 patches in mcp names (upstream runs with `srgPatches = false`), but the mcp_config
         * pipeline produces srg-named sources. Rename once before patching instead of regenerating the patch set.
         */
        override fun patchBase(config: MCPConfig): String {
            // The patches are authored against the tree the config produces *before* the cleanup step: mcpcleanup
            // rewraps long lines, which breaks the context lines of every patch that touches a long signature.
            val base = config.finalStepName()
            // The loader patches are written in mcp names, but the mcp_config pipeline runs with the 'stable' channel,
            // which is srg names. Renaming srg -> mcp on sources is exactly what the mavenizer does for the vanilla
            // tree, so its cli is reused here instead of any hand written resolution (srg -> mcp is a plain lookup,
            // the reverse direction would need ast analysis and does not exist anywhere in the stack).
            val csv =
                project.configurations
                    .detachedConfiguration(
                        project.dependencies.create(
                            project.findProperty("outlands.mcpMappings")?.toString()
                                ?: "de.oceanlabs.mcp:mcp_stable:39-1.12@zip",
                        ),
                    ).apply { isTransitive = false }
                    .singleFile
            config.addStep(
                config.FunctionStep(
                    "srgToMcp",
                    base,
                    mutableMapOf(
                        "input" to { config.getResultFor(base).output!!.absolutePathString() },
                        "mappings" to { csv.absolutePath },
                    ),
                    MCPConfig.Function(
                        listOf("top.outlands:minecraft-mavenizer:0.5.22-cr5"),
                        "net.minecraftforge.mcmaven.impl.tasks.RenameSourcesCli",
                        listOf("--mappings", "{mappings}", "--in", "{input}", "--out", "{output}"),
                        listOf(),
                        javaVersion = 25,
                    ),
                ),
            )
            return "srgToMcp"
        }

    }


}
