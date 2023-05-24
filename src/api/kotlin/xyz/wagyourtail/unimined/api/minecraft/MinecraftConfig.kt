package xyz.wagyourtail.unimined.api.minecraft

import groovy.lang.Closure
import groovy.lang.DelegatesTo
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.artifacts.Configuration
import org.gradle.api.tasks.SourceSet
import org.gradle.configurationcache.extensions.capitalized
import org.jetbrains.annotations.ApiStatus
import xyz.wagyourtail.unimined.api.mapping.MappingNamespace
import xyz.wagyourtail.unimined.api.mapping.MappingsConfig
import xyz.wagyourtail.unimined.api.minecraft.patch.MergedPatcher
import xyz.wagyourtail.unimined.api.minecraft.remap.MinecraftRemapConfig
import xyz.wagyourtail.unimined.api.minecraft.resolver.MinecraftData
import xyz.wagyourtail.unimined.api.minecraft.patch.MinecraftPatcher
import xyz.wagyourtail.unimined.api.mod.ModsConfig
import xyz.wagyourtail.unimined.api.runs.RunsConfig
import xyz.wagyourtail.unimined.api.task.RemapJarTask
import xyz.wagyourtail.unimined.util.FinalizeOnRead
import xyz.wagyourtail.unimined.util.LazyMutable
import xyz.wagyourtail.unimined.util.MustSet
import java.io.File
import java.nio.file.Path

/**
 * add minecraft to a sourceSet
 *
 * usage:
 * ```groovy
 * unimined.minecraft {
 *
 *     version "1.12.2"
 *
 *     // auto-set to combined on 1.3+
 *     side "combined"
 *
 *     mappings {
 *         // see MappingsConfig
 *     }
 *
 *
 *     // select mod loader, for full options, see PatchProviders
 *     /*
 *     forge {
 *         // see ForgePatcher
 *     }
 *     */
 *     /*
 *     fabric {
 *         // see FabricLikePatcher
 *     }
 *     */
 *     /*
 *     merged {
 *         // see MergedPatcher
 *     }
 *     */
 *
 *
 * }
 * ```
 *
 * @see MappingsConfig
 * @see PatchProviders
 * @since 1.0.0
 */
abstract class MinecraftConfig(val project: Project, val sourceSet: SourceSet) : PatchProviders {

    @set:ApiStatus.Internal
    var side by FinalizeOnRead(LazyMutable { if (minecraftData.isPreCombined) error("must set \"side\" for minecraft to either \"client\" or \"server\"") else EnvType.COMBINED })

    fun side(sideConf: String) {
        side = EnvType.valueOf(sideConf.uppercase())
    }

    var version: String by FinalizeOnRead(MustSet())

    fun version(version: String) {
        this.version = version
    }

    @set:ApiStatus.Internal
    abstract var mcPatcher: MinecraftPatcher

    abstract val mappings: MappingsConfig
    abstract val mods: ModsConfig
    abstract val runs: RunsConfig
    abstract val minecraftData: MinecraftData
    abstract val minecraftRemapper: MinecraftRemapConfig

    fun mappings(action: MappingsConfig.() -> Unit) {
        mappings.action()
    }

    fun mappings(
        @DelegatesTo(value = MappingsConfig::class, strategy = Closure.DELEGATE_FIRST)
        action: Closure<*>
    ) {
        mappings {
            action.delegate = this
            action.resolveStrategy = Closure.DELEGATE_FIRST
            action.call()
        }
    }

    abstract fun merged(action: (MergedPatcher) -> Unit)

    fun merged(
        @DelegatesTo(
            value = MergedPatcher::class,
            strategy = Closure.DELEGATE_FIRST
        ) action: Closure<*>
    ) {
        merged {
            action.delegate = it
            action.resolveStrategy = Closure.DELEGATE_FIRST
            action.call()
        }
    }

    fun remap(task: Task) {
        remap(task) {}
    }

    fun remap(task: Task, action: RemapJarTask.() -> Unit) {
        remap(task, "remap${task.name.capitalized()}", action)
    }

    fun remap(
        task: Task,
        @DelegatesTo(value = RemapJarTask::class, strategy = Closure.DELEGATE_FIRST)
        action: Closure<*>
    ) {
        remap(task) {
            action.delegate = this
            action.resolveStrategy = Closure.DELEGATE_FIRST
            action.call()
        }
    }

    fun remap(task: Task, name: String) {
        remap(task, name) {}
    }

    abstract fun remap(task: Task, name: String, action: RemapJarTask.() -> Unit)

    fun remap(
        task: Task,
        name: String,
        @DelegatesTo(value = RemapJarTask::class, strategy = Closure.DELEGATE_FIRST)
        action: Closure<*>
    ) {
        remap(task, name) {
            action.delegate = this
            action.resolveStrategy = Closure.DELEGATE_FIRST
            action.call()
        }
    }

    fun mods(action: ModsConfig.() -> Unit) {
        mods.action()
    }

    fun mods(
        @DelegatesTo(value = ModsConfig::class, strategy = Closure.DELEGATE_FIRST)
        action: Closure<*>
    ) {
        mods {
            action.delegate = this
            action.resolveStrategy = Closure.DELEGATE_FIRST
            action.call()
        }
    }

    fun runs(action: RunsConfig.() -> Unit) {
        runs.action()
    }

    fun runs(
        @DelegatesTo(value = RunsConfig::class, strategy = Closure.DELEGATE_FIRST)
        action: Closure<*>
    ) {
        runs {
            action.delegate = this
            action.resolveStrategy = Closure.DELEGATE_FIRST
            action.call()
        }
    }

    @ApiStatus.Internal
    abstract fun getMinecraft(
        namespace: MappingNamespace,
        fallbackNamespace: MappingNamespace
    ): Path

    @get:ApiStatus.Internal
    abstract val minecraftFileDev: File

    @get:ApiStatus.Internal
    abstract val minecraftLibraries: Configuration

    @ApiStatus.Internal
    abstract fun isMinecraftJar(path: Path): Boolean
}