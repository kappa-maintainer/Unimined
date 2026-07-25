package xyz.wagyourtail.unimined.internal.mods

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonParser
import com.google.gson.JsonObject
import kotlinx.coroutines.runBlocking
import net.fabricmc.loom.util.kotlin.KotlinClasspathService
import net.fabricmc.loom.util.kotlin.KotlinRemapperClassloader
import net.fabricmc.tinyremapper.InputTag
import net.fabricmc.tinyremapper.NonClassCopyMode
import net.fabricmc.tinyremapper.OutputConsumerPath
import net.fabricmc.tinyremapper.TinyRemapper
import org.gradle.api.InvalidUserDataException
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.Dependency
import org.gradle.api.artifacts.ExternalModuleDependency
import org.gradle.api.artifacts.ResolvedArtifact
import xyz.wagyourtail.unimined.api.mapping.mixin.MixinRemapOptions
import xyz.wagyourtail.unimined.api.minecraft.MinecraftConfig
import xyz.wagyourtail.unimined.api.minecraft.patch.forge.ForgeLikePatcher
import xyz.wagyourtail.unimined.api.mod.ModRemapConfig
import xyz.wagyourtail.unimined.api.unimined
import xyz.wagyourtail.unimined.internal.mapping.at.AccessTransformerApplier
import xyz.wagyourtail.unimined.internal.mapping.aw.AccessWidenerApplier
import xyz.wagyourtail.unimined.internal.mapping.extension.MixinRemapExtension
import xyz.wagyourtail.unimined.mapping.Namespace
import xyz.wagyourtail.unimined.util.*
import java.io.*
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.StandardCopyOption
import java.util.concurrent.CompletableFuture
import java.util.jar.JarFile
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.name

class ModRemapProvider(config: Set<Configuration>, val project: Project, val provider: MinecraftConfig) : ModRemapConfig(config) {

    override var namespace: Namespace by FinalizeOnRead(LazyMutable { provider.mcPatcher.prodNamespace })

    private var catchAWNs by FinalizeOnRead(false)

    private data class RemappedCoordinates(
        val group: String,
        val module: String,
        val version: String,
        val extension: String,
        val directory: Path,
        val binary: Path,
        val sources: Path,
        val pom: Path,
        val moduleMetadata: Path
    )

    private val remappedFilesToConfigurations = mutableMapOf<String, Configuration>()

    override fun namespace(ns: String) {
        // kotlin reflection is weird
        val delegate: FinalizeOnRead<Namespace> = ModRemapProvider::class.getField("namespace")!!.getDelegate(this) as FinalizeOnRead<Namespace>
        delegate.setValueIntl(LazyMutable { provider.mappings.checkedNs(ns) })
    }

    override fun catchAWNamespaceAssertion() {
        catchAWNs = true
    }

    override var remapAtToLegacy: Boolean by FinalizeOnRead(LazyMutable { (provider.mcPatcher as? ForgeLikePatcher<*>)?.remapAtToLegacy == true })
    override fun mixinRemap(action: MixinRemapOptions.() -> Unit) {
        mixinRemap = action
    }

    var mixinRemap: MixinRemapOptions.() -> Unit by FinalizeOnRead {}
    var tinyRemapSettings: TinyRemapper.Builder.() -> Unit by FinalizeOnRead {}

    var config: Configuration.() -> Unit by FinalizeOnRead {
        exclude(
            mapOf(
                "group" to "net.fabricmc",
                "module" to "fabric-loader"
            )
        )
        exclude(
            mapOf(
                "group" to "net.legacyfabric",
                "module" to "fabric-loader"
            )
        )
        exclude(
            mapOf(
                "group" to "org.quiltmc",
                "module" to "quilt-loader"
            )
        )
    }

    private val originalDeps = defaultedMapOf<Configuration, Set<Dependency>> {
        project.logger.info("[Unimined/ModRemapper] Original Deps: $it")
        for (dep in it.dependencies) {
            project.logger.info("[Unimined/ModRemapper]    $dep")
        }
        it.dependencies.toSet()
    }

    private val originalDepsFiles = defaultedMapOf<Configuration, Map<ResolvedArtifact, File>> {
        val detached = project.configurations.detachedConfiguration().apply(this@ModRemapProvider.config)
        detached.dependencies.addAll(originalDeps[it])
        val resolved = mutableMapOf<ResolvedArtifact, File>()
        project.logger.info("[Unimined/ModRemapper] Original Dep Files: $it")
        for (r in detached.resolvedConfiguration.resolvedArtifacts) {
            if (r.extension == "pom") continue
            project.logger.info("[Unimined/ModRemapper]    $r -> ${r.file}")
            resolved[r] = r.file
        }

        for ((module, artifacts) in resolved.keys.groupBy { artifact -> artifact.moduleVersion.id }) {
            require(artifacts.size <= 1) {
                "Remapped mod module $module resolved to multiple artifacts. " +
                    "An unclassified mod dependency must resolve to exactly one artifact, and a classified " +
                    "mod dependency is expected to select exactly one artifact: " +
                    artifacts.joinToString { artifact -> artifact.stringify() }
            }
        }
        resolved
    }

    private fun coordinatesFor(artifact: ResolvedArtifact): RemappedCoordinates {
        require(artifact.extension == null || artifact.extension == "jar") {
            "Remapped mod dependencies must be JARs: ${artifact.stringify()}"
        }
        // A classified input is republished as the synthetic component's main
        // artifact. The remapped group isolates it from the original module while
        // retaining conventional binary and sources file names.
        val group = "remapped_${artifact.moduleVersion.id.group}"
        val module = artifact.name
        val version = artifact.moduleVersion.id.version
        val extension = artifact.extension ?: "jar"
        val fileName = "$module-$version.$extension"
        val directory = (provider.mods as ModsProvider).modTransformFolder()
            .resolve(group.replace('.', File.separatorChar))
            .resolve(module)
            .resolve(version)
        return RemappedCoordinates(
            group,
            module,
            version,
            extension,
            directory,
            directory.resolve(fileName),
            directory.resolve("$module-$version-sources.jar"),
            directory.resolve("$module-$version.pom"),
            directory.resolve("$module-$version.module")
        )
    }

    private val originalDepsSourceFiles = defaultedMapOf<Configuration, Map<ResolvedArtifact, File?>> {
        val resolved = mutableMapOf<ResolvedArtifact, File?>()
        for (artifact in originalDepsFiles[it].keys) {
            if (artifact.moduleVersion.id.group == "curse.maven") {
                resolved[artifact] = null
                continue
            }
            // Sources are always resolved from the unclassified module coordinates.
            // A classified binary such as -dev or -universal is republished as the
            // synthetic component's main artifact, but its source lookup remains the
            // standard group:name:version:sources request.
            val sourceDep = project.dependencies.create(mapOf(
                "group" to artifact.moduleVersion.id.group,
                "name" to artifact.name,
                "version" to artifact.moduleVersion.id.version,
                "classifier" to "sources",
                "ext" to "jar"
            )).also { dependency ->
                (dependency as? ExternalModuleDependency)?.isTransitive = false
            }
            try {
                val detached = project.configurations.detachedConfiguration()
                detached.dependencies.add(sourceDep)
                val sourceFile = detached.resolvedConfiguration.resolvedArtifacts
                    .firstOrNull { a -> a.extension != "pom" }
                    ?.file
                resolved[artifact] = sourceFile
                if (sourceFile != null) {
                    project.logger.info("[Unimined/ModRemapper]    Source: $sourceDep -> $sourceFile")
                }
            } catch (e: Exception) {
                project.logger.info("[Unimined/ModRemapper]    No source artifact for ${artifact.stringify()}: ${e.message}")
                resolved[artifact] = null
            }
        }
        resolved
    }

    fun getConfigForFile(file: File): Configuration? = runBlocking {
        val configuration = remappedFilesToConfigurations[file.absoluteFile.normalize().path]
        configuration?.let {
            project.logger.debug("[Unimined/ModRemapper] {} is an output of {}", file, it)
            return@runBlocking it
        }
        null
    }

    private fun constructRemapper(
        fromNs: Namespace,
        toNs: Namespace,
        mc: Path
    ): CompletableFuture<Pair<TinyRemapper, MixinRemapExtension>> = runBlocking {
        val remapperB = TinyRemapper.newRemapper()
            .withMappings(
                provider.mappings.getTRMappings(
                    fromNs to toNs,
                    false
                )
            )
            .skipLocalVariableMapping(true)
            .propagateUnmappedSuper(provider.mcPatcher !is ForgeLikePatcher<*>)
            .ignoreConflicts(true)
            .threads(Runtime.getRuntime().availableProcessors())
            .extraRemapper(provider.mappings.getExtraRemapper(
                fromNs to toNs
            ))
        val classpath = KotlinClasspathService.getOrCreateIfRequired(project)
        if (classpath != null) {
            remapperB.extension(KotlinRemapperClassloader.create(classpath).tinyRemapperExtension)
        }
        val mixinExtension = MixinRemapExtension(
                project.logger,
                allowImplicitWildcards = true
            )
        mixinExtension.enableBaseMixin()
        mixinRemap(mixinExtension)
        remapperB.extension(mixinExtension)

        tinyRemapSettings(remapperB)
        val remapper = remapperB.build()
        val future = mixinExtension.readClassPath(remapper,
                *(provider.minecraftLibraries.files.map { it.toPath() } + listOf(mc))
                    .toTypedArray()
            )
        future.thenApply { remapper to mixinExtension }
    }

    override fun remapper(remapperBuilder: TinyRemapper.Builder.() -> Unit) {
        tinyRemapSettings = remapperBuilder
    }

    fun doRemap(
        devNamespace: Namespace = provider.mappings.devNamespace,
        targetConfigurations: Map<Configuration, Configuration> = defaultedMapOf { it }
    ) = runBlocking {
        if (namespace != devNamespace) {
            project.logger.lifecycle("[Unimined/ModRemapper] Remapping mods from $namespace to $devNamespace")

            // resolve original dep files
            val count = configurations.sumOf { originalDepsFiles[it].size }
            if (count == 0) {
                project.logger.lifecycle("[Unimined/ModRemapper] No mods found for remapping")
                return@runBlocking
            }
            project.logger.lifecycle("[Unimined/ModRemapper] Found $count mods for remapping")
            project.logger.info("[Unimined/ModRemapper] remapAtToLegacy: $remapAtToLegacy")
            project.logger.info("[Unimined/ModRemapper] mixinRemap: $mixinRemap")

            val mods = mutableMapOf<ResolvedArtifact, File>()
            for (map in originalDepsFiles.values) {
                mods.putAll(map)
            }
            for ((module, artifacts) in mods.keys.groupBy { artifact -> artifact.moduleVersion.id }) {
                require(artifacts.size <= 1) {
                    "Remapped mod module $module selected different artifacts across remap configurations: " +
                        artifacts.joinToString { artifact -> artifact.stringify() }
                }
            }
            val mc = provider.getMinecraft(namespace)
            val forceReload = project.unimined.forceReload
            val targets = mods.mapValues { mod ->
                val coordinates = coordinatesFor(mod.key)
                coordinates.directory.createDirectories()
                for (configuration in configurations) {
                    if (originalDepsFiles[configuration].containsKey(mod.key)) {
                        remappedFilesToConfigurations[coordinates.binary.toFile().absoluteFile.normalize().path] = configuration
                    }
                }
                mod.value to coordinates.binary.let { it to (it.exists() && !forceReload) }
            }
            project.logger.info("[Unimined/ModRemapper] Remapping Mods: ")
            if (targets.values.none { !it.second.second }) {
                project.logger.info("[Unimined/ModRemapper]    Skipping remap as all mods are already remapped")
                mods.clear()
                mods.putAll(targets.mapValues { it.value.second.first.toFile() })
            } else {
                for (mod in targets) {
                    project.logger.info("[Unimined/ModRemapper]  ${if (mod.value.second.second) "skipping" else "        "} ${mod.value.first} -> ${mod.value.second.first}")
                }
                val remapper = constructRemapper(namespace, devNamespace, mc)
                val tags = preRemapInternal(remapper, targets)
                mods.clear()
                mods.putAll(
                    remapInternal(
                        remapper.join(),
                        tags.join().nonNullValues(),
                        devNamespace
                    )
                )
                mods.putAll(
                    tags.join().filterValues { it == null }.mapValues { targets[it.key]!!.second.first.toFile() }
                )
            }

            // Remove a source artifact left by an earlier resolution if the current
            // dependency no longer provides sources. Metadata is written below only
            // after all current source artifacts have been copied.
            for (artifact in mods.keys) {
                val coordinates = coordinatesFor(artifact)
                if (configurations.none { originalDepsSourceFiles[it][artifact]?.exists() == true }) {
                    coordinates.sources.deleteIfExists()
                }
            }

            // Copy source jars alongside remapped binary jars and publish metadata for
            // the synthetic Maven component. Sources are intentionally not remapped:
            // the source code is already in MCP names, while AT files are documentation
            // resources in an IDE source attachment rather than runtime inputs.
            for (c in configurations) {
                for ((artifact, sourceFile) in originalDepsSourceFiles[c]) {
                    if (sourceFile == null || !sourceFile.exists()) continue
                    project.logger.info("[Unimined/ModRemapper] Copying source jar for ${artifact.stringify()}")
                    copySourceJar(artifact, sourceFile)
                }
            }
            for (artifact in mods.keys) {
                writeMetadata(coordinatesFor(artifact))
            }

            // supply back to proper configs
            for (c in configurations) {
                val outConf = targetConfigurations[c]
                project.logger.info("[Unimined/ModRemapper] Supplying remapped mods to ${c.name}")
                outConf!!.dependencies.clear()
                for (artifact in originalDepsFiles[c].keys) {
                    if (artifact.extension == "pom") continue
                    val coordinates = coordinatesFor(artifact)
                    outConf.dependencies.add(
                        project.dependencies.create(
                            "${coordinates.group}:${coordinates.module}:${coordinates.version}"
                        ).also { dependency ->
                            (dependency as? ExternalModuleDependency)?.isTransitive = false
                        }
                    )
                }
            }
        } else {
            for (c in configurations) {
                val outConf = targetConfigurations[c]
                project.logger.info("[Unimined/ModRemapper] Supplying original mods to ${c.name}")
                val deps = originalDeps[c]
                outConf!!.dependencies.clear()
                outConf.dependencies.addAll(deps)
            }
        }
    }

    private fun preRemapInternal(
        remapper: CompletableFuture<Pair<TinyRemapper, MixinRemapExtension>>,
        deps: Map<ResolvedArtifact, Pair<File, Pair<Path, Boolean>>>
    ): CompletableFuture<Map<ResolvedArtifact, Pair<InputTag, Pair<File, Path>>?>> {
        val output = mutableMapOf<ResolvedArtifact, Pair<InputTag, Pair<File, Path>>?>()
        var future = remapper
        val futures = mutableListOf<CompletableFuture<*>>()
        for ((artifact, data) in deps) {
            val file = data.first
            val target = data.second.first
            val needsRemap = !data.second.second
            project.logger.info("[Unimined/ModRemapper] remap ${needsRemap}; $file -> $target")
            if (file.isDirectory) {
                throw InvalidUserDataException("Cannot remap directory ${file.absolutePath}")
            } else {
                futures += future.thenCompose {
                    if (needsRemap) {
                        val tag = it.first.createInputTag()
                        output[artifact] = tag to (file to target)
                        it.second.readInput(it.first, tag, file.toPath())
                    } else {
                        output[artifact] = null
                        it.second.readClassPath(it.first, file.toPath())
                    }
                }
            }
        }
        return CompletableFuture.allOf(*futures.toTypedArray()).thenApply { output }
    }

    private fun ResolvedArtifact.stringify() = "${this.moduleVersion.id.group}:${this.name}:${this.moduleVersion.id.version}${this.classifier?.let { ":$it" } ?: ""}${this.extension?.let { "@$it" } ?: ""}"

    private fun remapInternal(
        remapper: Pair<TinyRemapper, MixinRemapExtension>,
        deps: Map<ResolvedArtifact, Pair<InputTag, Pair<File, Path>>>,
        targetNs: Namespace
    ): Map<ResolvedArtifact, File> {
        val output = mutableMapOf<ResolvedArtifact, File>()
        project.logger.info("[Unimined/ModRemapper] Remapping mods to $targetNs")
        for ((artifact, tag) in deps) {
            try {
                remapModInternal(remapper, artifact, tag, targetNs)
            } catch (e: Exception) {
                // delete output
                tag.second.second.deleteIfExists()

                throw IllegalStateException("Failed to remap ${artifact.stringify()} to $targetNs", e)
            }
            output[artifact] = tag.second.second.toFile()
        }
        remapper.first.finish()
        return output
    }

    private fun remapModInternal(
        remapper: Pair<TinyRemapper, MixinRemapExtension>,
        dep: ResolvedArtifact,
        input: Pair<InputTag, Pair<File, Path>>,
        toNs: Namespace
    ) = runBlocking {
        val inpFile = input.second.first
        val targetFile = input.second.second
        val manifest = JarFile(inpFile).use { it.manifest }?.mainAttributes?.getValue("FMLAT")?.split(" ") ?: emptyList()
        project.logger.info("[Unimined/ModRemapper] Remapping mod from $inpFile -> $targetFile with mapping target $toNs")
        try {
            OutputConsumerPath.Builder(targetFile).build().use {
                it.addNonClassFiles(
                    inpFile.toPath(),
                    remapper.first,
                    listOf(
                        AccessWidenerApplier.AwRemapper(
                            AccessWidenerApplier.nsName(provider.mappings, namespace),
                            AccessWidenerApplier.nsName(provider.mappings, toNs),
                            catchAWNs,
                            project.logger
                        ),
                        innerJarStripper,
                        AccessTransformerApplier.AtRemapper(project.logger, namespace, toNs, remapAtToLegacy, manifest, provider.mappings.resolve())
                    ) + NonClassCopyMode.FIX_META_INF.remappers
                )
                remapper.first.apply(it, input.first)
            }

            targetFile.openZipFileSystem(mapOf("mutable" to true)).use {
                remapper.second.insertExtra(input.first, it)
            }
        } catch (e: Exception) {
            targetFile.deleteIfExists()
            throw e
        }
    }

    private fun copySourceJar(
        artifact: ResolvedArtifact,
        sourceFile: File
    ) {
        val targetSourceFile = coordinatesFor(artifact).sources
        targetSourceFile.parent.createDirectories()

        if (!project.unimined.forceReload && targetSourceFile.exists()) {
            project.logger.info("[Unimined/ModRemapper]   Skipping source copy (already exists)")
            return
        }

        project.logger.info("[Unimined/ModRemapper]   $sourceFile -> $targetSourceFile")
        try {
            Files.copy(sourceFile.toPath(), targetSourceFile, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: Exception) {
            targetSourceFile.deleteIfExists()
            throw IllegalStateException("Failed to copy source jar for ${artifact.stringify()} to $targetSourceFile", e)
        }
    }

    private fun writeMetadata(coordinates: RemappedCoordinates) {
        coordinates.directory.createDirectories()
        // Synthetic modules intentionally have no transitive dependencies. The remap
        // configuration supplies every resolved mod artifact as its own synthetic
        // dependency, preventing the original, unmapped graph from being reintroduced.
        val sourceExists = coordinates.sources.exists()
        val binaryName = coordinates.binary.fileName.toString()
        val sourceName = coordinates.sources.fileName.toString()

        val pom = buildString {
            append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
            append("<project xmlns=\"http://maven.apache.org/POM/4.0.0\" ")
            append("xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" ")
            append("xsi:schemaLocation=\"http://maven.apache.org/POM/4.0.0 ")
            append("https://maven.apache.org/xsd/maven-4.0.0.xsd\">\n")
            append("  <modelVersion>4.0.0</modelVersion>\n")
            append("  <groupId>${xmlEscape(coordinates.group)}</groupId>\n")
            append("  <artifactId>${xmlEscape(coordinates.module)}</artifactId>\n")
            append("  <version>${xmlEscape(coordinates.version)}</version>\n")
            append("  <packaging>jar</packaging>\n")
            append("</project>\n")
        }
        coordinates.pom.toFile().writeText(pom)

        fun variant(
            name: String,
            category: String,
            usage: String,
            docType: String?,
            fileName: String
        ): JsonObject {
            val attributes = JsonObject().apply {
                addProperty("org.gradle.category", category)
                addProperty("org.gradle.dependency.bundling", "external")
                addProperty("org.gradle.usage", usage)
                if (category == "library") addProperty("org.gradle.libraryelements", "jar")
                if (docType != null) addProperty("org.gradle.docstype", docType)
            }
            val file = JsonObject().apply {
                addProperty("name", fileName)
                addProperty("url", fileName)
            }
            return JsonObject().apply {
                addProperty("name", name)
                add("attributes", attributes)
                add("files", JsonArray().apply { add(file) })
            }
        }

        val variants = JsonArray().apply {
            add(variant("apiElements", "library", "java-api", null, binaryName))
            add(variant("runtimeElements", "library", "java-runtime", null, binaryName))
            if (sourceExists) {
                add(variant("sourcesElements", "documentation", "java-runtime", "sources", sourceName))
            }
        }
        val moduleMetadata = JsonObject().apply {
            addProperty("formatVersion", "1.1")
            add("component", JsonObject().apply {
                addProperty("group", coordinates.group)
                addProperty("module", coordinates.module)
                addProperty("version", coordinates.version)
            })
            add("variants", variants)
        }
        coordinates.moduleMetadata.toFile().writeText(
            GsonBuilder().setPrettyPrinting().create().toJson(moduleMetadata)
        )
    }

    private fun xmlEscape(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")

    private val innerJarStripper: OutputConsumerPath.ResourceRemapper = object : OutputConsumerPath.ResourceRemapper {
        override fun canTransform(remapper: TinyRemapper, relativePath: Path): Boolean {
            return relativePath.name.contains(".mod.json")
        }

        override fun transform(
            destinationDirectory: Path,
            relativePath: Path,
            input: InputStream,
            remapper: TinyRemapper
        ) {
            val output = destinationDirectory.resolve(relativePath)
            output.parent.createDirectories()
            BufferedReader(InputStreamReader(input)).use { reader ->
                val json = JsonParser.parseReader(reader)
                json.asJsonObject.remove("jars")
                BufferedWriter(
                    OutputStreamWriter(
                        BufferedOutputStream(
                            Files.newOutputStream(
                                output,
                                StandardOpenOption.CREATE,
                                StandardOpenOption.TRUNCATE_EXISTING
                            )
                        )
                    )
                ).use { writer ->
                    GsonBuilder().setPrettyPrinting().create().toJson(json, writer)
                }
            }
        }
    }
}
