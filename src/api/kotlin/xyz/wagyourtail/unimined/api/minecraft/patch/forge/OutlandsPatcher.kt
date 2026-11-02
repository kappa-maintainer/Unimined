package xyz.wagyourtail.unimined.api.minecraft.patch.forge

import groovy.lang.Closure
import groovy.lang.DelegatesTo
import xyz.wagyourtail.unimined.api.minecraft.patch.jarmod.JarModPatcher
import xyz.wagyourtail.unimined.api.mod.JarMetadataConfig

interface OutlandsPatcher<T : JarModPatcher> : ForgeLikePatcher<T> {

    /**
     * The jar-level metadata every jar this project produces declares.
     *
     * Written into the produced jar's `mcmod.info`, not into its manifest: the loader reads the declaration first and
     * the manifest attribute only as a fallback, so the declaration is the only copy that has to exist.
     *
     * @see JarMetadataConfig
     */
    val jarMetadata: JarMetadataConfig

    /**
     * Declares the jar-level metadata of the produced jar.
     *
     * ```groovy
     * unimined.minecraft {
     *     outlands {
     *         jarMetadata {
     *             license "MIT"
     *             side "client"
     *             at "accesstransformer.cfg"
     *         }
     *     }
     * }
     * ```
     */
    fun jarMetadata(action: JarMetadataConfig.() -> Unit) {
        jarMetadata.action()
    }

    fun jarMetadata(
        @DelegatesTo(value = JarMetadataConfig::class, strategy = Closure.DELEGATE_FIRST)
        action: Closure<*>,
    ) {
        jarMetadata {
            action.delegate = this
            action.resolveStrategy = Closure.DELEGATE_FIRST
            action.call()
        }
    }
}
