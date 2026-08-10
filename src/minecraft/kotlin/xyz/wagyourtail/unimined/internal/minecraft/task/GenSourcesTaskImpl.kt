package xyz.wagyourtail.unimined.internal.minecraft.task

import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction
import xyz.wagyourtail.unimined.api.minecraft.task.GenSourcesTask
import xyz.wagyourtail.unimined.api.unimined
import xyz.wagyourtail.unimined.internal.minecraft.MinecraftProvider
import xyz.wagyourtail.unimined.internal.minecraft.patch.forge.ForgeLikeMinecraftTransformer
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import javax.inject.Inject
import kotlin.io.path.exists
import kotlin.io.path.nameWithoutExtension

abstract class GenSourcesTaskImpl
    @Inject
    constructor(
        @get:Internal val provider: MinecraftProvider,
    ) : GenSourcesTask() {
        init {
            // inputs/outputs so Gradle can skip the task (up-to-date) when nothing changed.
            // Per-step fingerprint caching inside MCPConfig additionally avoids re-running the
            // shared mcp_config pipeline when only project-local inputs (e.g. the AT file) change.
            val sourcesJar =
                project.provider {
                    val mc = provider.getMcDevFile()
                    mc.resolveSibling("${mc.nameWithoutExtension}-sources.jar")
                }
            inputs.files(project.provider { provider.sourceSet.compileClasspath })
            inputs.files(project.provider { provider.mappings.mappings.files })
            inputs.file(project.provider { provider.getMcDevFile().toFile() })
            inputs.files(
                project.provider {
                    val patcher = provider.mcPatcher
                    if (patcher is ForgeLikeMinecraftTransformer) {
                        patcher.accessTransformer?.let { listOf(it) } ?: emptyList()
                    } else {
                        emptyList()
                    }
                },
            )
            inputs.property("side", project.provider { provider.side.name })
            inputs.property("obfuscated", project.provider { provider.obfuscated })
            outputs.file(project.provider { sourcesJar.get().toFile() })
        }

        @TaskAction
        fun run() {
            val mcDevFile = provider.getMcDevFile()
            val sourcesJar = mcDevFile.resolveSibling("${mcDevFile.nameWithoutExtension}-sources.jar")
            val linemappedJar = mcDevFile.resolveSibling("${mcDevFile.nameWithoutExtension}-linemapped.jar")

            // TODO: add method to get sources from mcProvider (ie run forge 1 step further)
            provider.mcPatcher.createSourcesJar(provider.sourceSet.compileClasspath, mcDevFile, sourcesJar, linemappedJar, provider.side)
            logger.info("[Unimined/GenSources ${this.path}] sources jar generated at $sourcesJar")
            if (linemappedJar != null) {
                logger.info("[Unimined/GenSources ${this.path}] linemapped jar generated at $linemappedJar")
            }
            publishSourcesToLocalMaven(sourcesJar)
        }

        /**
         * Copies the generated sources jar into the global local Maven repository tree (next to
         * the dev jar published at configuration time) and refreshes the sourcesElements variant
         * of the component's .module so IDEA's modern auxiliary-artifact resolver picks the
         * sources up on the next sync.
         */
        private fun publishSourcesToLocalMaven(sourcesJar: Path) {
            if (!sourcesJar.exists()) return
            val publishModuleMetadata = project.repositories.none { it is org.gradle.api.artifacts.repositories.IvyArtifactRepository }
            val baseDir = project.unimined.getGlobalCache().resolve("maven")
            val dir =
                xyz.wagyourtail.unimined.util.LocalMaven.coordinatesDirectory(
                    baseDir,
                    provider.mavenGroup,
                    provider.minecraftDepName,
                    provider.version,
                )
            val sourcesName = "${provider.minecraftDepName}-${provider.version}-sources.jar"
            Files.copy(sourcesJar, dir.resolve(sourcesName), StandardCopyOption.REPLACE_EXISTING)
            if (publishModuleMetadata) {
                xyz.wagyourtail.unimined.util.LocalMaven.addSourcesVariant(
                    dir,
                    provider.minecraftDepName,
                    provider.version,
                    sourcesName,
                )
            }
        }
    }
