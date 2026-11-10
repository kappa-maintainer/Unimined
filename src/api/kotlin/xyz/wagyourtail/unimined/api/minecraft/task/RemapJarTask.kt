package xyz.wagyourtail.unimined.api.minecraft.task

import groovy.lang.Closure
import groovy.lang.DelegatesTo
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Optional
import xyz.wagyourtail.unimined.api.mapping.mixin.MixinRemapOptions
import xyz.wagyourtail.unimined.util.JarInterface

interface RemapJarTask : JarInterface<AbstractRemapJarTask>, RemapOptions {

    /**
     * whether to remap AccessTransformers to the legacy format (<=1.7.10)
     */
    @get:Input
    @get:Optional
    val remapATToLegacy: Property<Boolean>

    /**
     * Enable remapping of access wideners in the jar.
     * Enabled by default
     * @since 1.4.1
     */
    @get:Input
    @get:Optional
    val remapAccessWidener: Property<Boolean>

    /**
     * Enable remapping of access transformers in the jar.
     * Enabled by default
     * @since 1.4.1
     */
    @get:Input
    @get:Optional
    val remapAccessTransformer: Property<Boolean>

    /**
     * Entries to treat as access transformers, as paths relative to `META-INF/`.
     *
     * The file name convention (the `accesstransformer.cfg` name and the `_at.cfg` suffix) is only a convention. A
     * loader that reads a declared list - Outlands' `jar.at` in `mcmod.info`, Forge's `FMLAT` - accepts any path, and a
     * jar that uses the declaration to move its AT somewhere else would then be published with its rules still in dev
     * names. Loaders that can declare one set this to what the produced jar declares; nothing is inferred here, because
     * a path only the loader knows is not something a build can guess.
     *
     * @since 1.4.48
     */
    @get:Input
    @get:Optional
    val accessTransformerPaths: ListProperty<String>

    fun mixinRemap(action: MixinRemapOptions.() -> Unit)

    fun mixinRemap(
        @DelegatesTo(value = MixinRemapOptions::class, strategy = Closure.DELEGATE_FIRST)
        action: Closure<*>
    ) {
        mixinRemap {
            action.delegate = this
            action.resolveStrategy = Closure.DELEGATE_FIRST
            action.call()
        }
    }

}