package xyz.wagyourtail.unimined.internal.mods

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.gradle.api.logging.Logger
import org.jetbrains.annotations.ApiStatus
import java.nio.file.Path
import java.util.jar.JarFile
import java.util.jar.Manifest

/**
 * The access transformer files a jar declares, read the way the loader reads them.
 *
 * Two carriers exist and they do not have the same age: `FMLAT` in the manifest, and the `at` key of the `jar` section
 * of `mcmod.info`, which replaces it. The loader prefers the declaration and only falls back to the manifest when the
 * declaration is absent or empty (`JarDeclaration.accessTransformersOf`), so this does the same - a dependency that
 * moved its declaration into `mcmod.info` and dropped the manifest attribute would otherwise reach a development run
 * with nothing remapped, and an access transformer in the wrong namespace is not an error at runtime, it is a rule
 * that silently never matches.
 *
 * Both carriers list paths relative to `META-INF/`, which is what `ModAccessTransformer.addJar` prepends before it
 * looks an entry up.
 */
@ApiStatus.Internal
object JarAccessTransformers {

    /** Manifest attribute the `at` key replaces, still read for jars that declare no section. */
    const val MANIFEST_KEY = "FMLAT"

    /**
     * The file the declaration lives in.
     *
     * Spelled here rather than imported from the writer: that one is in the minecraft source set, which the mods
     * source set cannot see, and the loader's key is a fixed contract either way.
     */
    const val METADATA_ENTRY = "mcmod.info"

    /** @return the declared AT paths of [jar], or an empty list when it declares none */
    fun of(jar: Path, logger: Logger): List<String> =
        JarFile(jar.toFile()).use { jarFile ->
            of(
                jarFile.manifest,
                jarFile.getEntry(METADATA_ENTRY)?.let { entry ->
                    jarFile.getInputStream(entry).use { it.readBytes() }
                },
                logger,
                jar.fileName.toString(),
            )
        }

    fun of(
        manifest: Manifest?,
        metadata: ByteArray?,
        logger: Logger,
        jarName: String = "the jar",
    ): List<String> {
        val declared = metadata?.let { declaredIn(it, logger, jarName) } ?: emptyList()
        if (declared.isNotEmpty()) {
            return declared
        }
        return splitPaths(manifest?.mainAttributes?.getValue(MANIFEST_KEY))
    }

    private fun declaredIn(raw: ByteArray, logger: Logger, jarName: String): List<String> {
        val text = raw.decodeToString()
        // Cheap prefilter, the same reasoning the loader and the stripper use: most jars have no jar section at all, so
        // most of the mods directory never reaches the parser.
        if (!text.contains("\"jar\"")) {
            return emptyList()
        }
        val root = try {
            JsonParser.parseString(text)
        } catch (e: Exception) {
            logger.warn(
                "[Unimined/Mods] {}: the mcmod.info is not readable ({}), so its at declaration is ignored",
                jarName,
                e.message,
            )
            return emptyList()
        }
        val section = (root as? JsonObject)?.get("jar") ?: return emptyList()
        if (section !is JsonObject) {
            logger.warn(
                "[Unimined/Mods] {}: the jar section of the mcmod.info is not an object, so its at declaration is ignored",
                jarName,
            )
            return emptyList()
        }
        return when (val at = section.get("at")) {
            null -> emptyList()
            // An array element is one path and is deliberately not split again: a file name with a space is the one
            // thing the manifest could not express. Same rule as the loader's.
            is JsonArray -> at.mapNotNull { element ->
                if (element.isJsonPrimitive && element.asJsonPrimitive.isString) {
                    element.asString.trim().ifEmpty { null }
                } else {
                    logger.warn(
                        "[Unimined/Mods] {}: the at declaration of the mcmod.info contains {}, which is not a string; " +
                            "the entry is skipped",
                        jarName,
                        element,
                    )
                    null
                }
            }

            // A single string is the shape `FMLAT` had, so it is split on whitespace.
            else -> if (at.isJsonPrimitive && at.asJsonPrimitive.isString) {
                splitPaths(at.asString)
            } else {
                logger.warn(
                    "[Unimined/Mods] {}: the at declaration of the mcmod.info is neither a string nor an array; " +
                        "falling back to the manifest",
                    jarName,
                )
                emptyList()
            }
        }
    }

    private fun splitPaths(value: String?): List<String> {
        if (value.isNullOrBlank()) {
            return emptyList()
        }
        return value.trim().split(WHITESPACE).filter { it.isNotBlank() }
    }

    private val WHITESPACE = Regex("\\s+")
}
