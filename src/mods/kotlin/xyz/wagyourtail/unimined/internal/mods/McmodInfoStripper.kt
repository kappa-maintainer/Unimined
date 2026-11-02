package xyz.wagyourtail.unimined.internal.mods

import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.jetbrains.annotations.ApiStatus

/**
 * Removes a mod jar's bundled-dependency declaration from the copy Unimined hands to a development run.
 *
 * ## Why the declaration has to go
 *
 * A dependency is published in production names, and a remap turns its classes into development names. The jars it
 * bundles are copied through untouched, so inside the remapped jar they are still in production names - and depending
 * on the loader they are not even reachable: a development run assembles its class path from the mods directory, and a
 * dependency that only exists on that class path is never opened for extraction.
 *
 * So the declaration in that copy promises something the environment cannot deliver, twice over. Leaving it in also
 * makes the loader warn about every entry ("declares the bundled library X, but it is neither mounted nor extracted"),
 * which is noise pointing at Unimined rather than at the dependency.
 *
 * A bundled jar is supposed to have its own coordinates and be depended on separately; that is what a development run
 * wants anyway, because then it is remapped like any other mod. Removing the declaration is the honest statement that
 * the copy carries no usable bundled entries.
 *
 * ## What is *not* removed
 *
 * The `jar` section itself stays, even when it becomes sparse. Its presence is what tells the loader this jar was
 * written against the current environment, and one of the things that reads it is the LWJGL2 rewrite filter. Dropping
 * the whole section would silently re-enable rewriting for a jar that is up to date - a behaviour change hidden inside
 * what looks like a cleanup.
 */
@ApiStatus.Internal
object McmodInfoStripper {

    /** One entry that was declared and is now gone, kept so the log line can name what disappeared. */
    data class RemovedDep(val path: String, val id: String?, val version: String?, val kind: String?) {
        override fun toString(): String =
            buildString {
                append(path)
                val identity = listOfNotNull(id, version).joinToString(":")
                if (identity.isNotEmpty()) append(" [").append(identity)
                if (kind != null) {
                    if (identity.isNotEmpty()) append(", ")
                    append(kind)
                }
                if (identity.isNotEmpty() || kind != null) append("]")
            }
    }

    sealed interface Result {
        /** Nothing to do: no `jar` section, no `containedDeps`, or a shape that has no jar-level data at all. */
        data object Unchanged : Result

        /** The declaration was removed; [bytes] is the file to write. */
        data class Stripped(val bytes: ByteArray, val removed: List<RemovedDep>) : Result {
            override fun equals(other: Any?): Boolean =
                other is Stripped && bytes.contentEquals(other.bytes) && removed == other.removed

            override fun hashCode(): Int = 31 * bytes.contentHashCode() + removed.hashCode()
        }

        /** The file is not JSON this understands; it is left byte-identical rather than dropped or half-rewritten. */
        data class Unreadable(val reason: String) : Result
    }

    private val gson = GsonBuilder().setPrettyPrinting().create()

    /**
     * @param raw the entry's bytes, as they are inside the jar
     * @return what to write, or [Result.Unchanged] to copy the entry through unchanged
     */
    fun stripContainedDeps(raw: ByteArray): Result {
        val text = raw.toString(Charsets.UTF_8)
        // Cheap pre-filter, same reasoning as the loader's: most jars have no jar section, and reformatting a file for
        // nothing would make an identical jar differ from its input.
        if (!text.contains("\"containedDeps\"")) {
            return Result.Unchanged
        }
        val root = try {
            JsonParser.parseString(text)
        } catch (e: Exception) {
            return Result.Unreadable(e.message ?: e.javaClass.simpleName)
        }
        if (root == null || !root.isJsonObject) {
            // The array form is the legacy shape: it carries mod entries only, so there is nothing jar-level in it.
            return Result.Unchanged
        }
        val jar = root.asJsonObject.get("jar")
        if (jar == null || !jar.isJsonObject) {
            // `"containedDeps"` appears somewhere else in the file (a mod entry, a description). Not ours to touch.
            return Result.Unchanged
        }
        val section = jar.asJsonObject
        val contained = section.get("containedDeps") ?: return Result.Unchanged
        val removed = describeRemoved(contained)
        section.remove("containedDeps")
        return Result.Stripped(gson.toJson(root).toByteArray(Charsets.UTF_8), removed)
    }

    private fun describeRemoved(element: JsonElement): List<RemovedDep> {
        if (!element.isJsonArray) {
            return emptyList()
        }
        return element.asJsonArray.mapNotNull { entry ->
            if (entry == null || !entry.isJsonObject) {
                return@mapNotNull null
            }
            val dep = entry.asJsonObject
            RemovedDep(
                path = dep.stringOrNull("path") ?: return@mapNotNull null,
                id = dep.stringOrNull("id"),
                version = dep.stringOrNull("version"),
                kind = dep.stringOrNull("kind"),
            )
        }
    }

    private fun JsonObject.stringOrNull(key: String): String? =
        get(key)?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotBlank() }
}
