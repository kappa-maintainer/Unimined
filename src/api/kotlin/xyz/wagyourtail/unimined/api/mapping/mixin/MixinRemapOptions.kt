package xyz.wagyourtail.unimined.api.mapping.mixin

import org.jetbrains.annotations.ApiStatus

/**
 * How a mod's mixins are remapped while the mod is remapped into the dev namespace.
 *
 * A mod jar declares its mixin targets with class references (which the class remapper handles by
 * itself) but the injection selectors — `@Inject(method = "…")`, `@At(target = "…")`, … — are
 * *strings*, and Loom leaves those in the namespace the mod was written in (the `named` side of the
 * `named:intermediary` refmap it ships). Turning them into the dev namespace needs a mixin-aware
 * remapper; one of the three implementations must be enabled for that to happen.
 *
 * By default (with no call to any of these methods) BaseMixin is enabled: existing refmaps are used
 * to resolve selectors that are in the mod's own namespace, and everything is written back into the
 * dev namespace. Fabric's default is to [off] this instead unless the dev namespace is the namespace
 * the mods' selectors are already in — Fabric Loader's dev remapper only translates intermediary →
 * dev, so remapping here is both unnecessary and undesirable in that case.
 *
 * @since 1.1.0
 */
interface MixinRemapOptions {

    fun enableMixinExtra()

    fun enableBaseMixin()

    fun enableJarModAgent()

    @ApiStatus.Experimental
    fun reset()

    @ApiStatus.Experimental
    fun resetMetadataReader()

    @ApiStatus.Experimental
    fun resetHardRemapper()

    @ApiStatus.Experimental
    fun resetRefmapBuilder()
    fun off()

    /**
     * Keep the annotations self-contained instead of writing a new refmap: the remapped references
     * are written straight into the annotation values and the manifest is marked so that nothing
     * translates them again at runtime. Use this when the dev namespace is final, i.e. when no
     * runtime remapper will touch the mod (which is the case when the dev namespace is not one the
     * loader knows how to remap refmaps into).
     *
     * @param keys the metadata readers whose refmap output is dropped, `BaseMixin` and `JarModAgent`
     *   by default
     */
    fun disableRefmap()
    fun disableRefmap(keys: List<String> = listOf("BaseMixin", "JarModAgent"))
}