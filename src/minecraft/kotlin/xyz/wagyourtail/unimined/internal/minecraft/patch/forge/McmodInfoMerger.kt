package xyz.wagyourtail.unimined.internal.minecraft.patch.forge

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.jetbrains.annotations.ApiStatus
import xyz.wagyourtail.unimined.api.mod.JarMetadataConfig
import xyz.wagyourtail.unimined.api.mod.LoadOrder
import xyz.wagyourtail.unimined.api.mod.Side

/**
 * Writes the `jar` section of a `mcmod.info` from what the build declared.
 *
 * Pure on purpose: the interesting parts are the conflict rules and the shapes a file can arrive in, and both are
 * reachable without opening a jar.
 *
 * ## The three inputs, and who owns each key
 *
 * | keys | owner |
 * |---|---|
 * | `schemaVersion` | the build. It writes the schema it understands and refuses one it does not. |
 * | `license` | either. The one thing the build cannot invent, so a section that needs one and has none is an error. |
 * | everything else | the author's declaration, whatever spelling it arrives in. The build does not guess. |
 *
 * ## Why a disagreement fails instead of winning
 *
 * A key that the file already declares and the build declares differently is two explicit statements. Picking one
 * silently changes what the jar does - a mod disappears from one side, a coremod points at a class that does not exist -
 * and the only trace would be the value in the produced file. Failing names both values at once, at the moment the
 * config is written.
 *
 * ## Why the file is not rebuilt from scratch
 *
 * A `mcmod.info` also carries the mod list and whatever else its author wrote. Rebuilding it from a model would drop the
 * fields this build does not know about, so it is edited as a JSON tree: unknown top-level keys and unknown keys inside
 * the `jar` section both survive untouched.
 */
@ApiStatus.Internal
object McmodInfoMerger {

    /** The schema this build writes. A file declaring a higher one is refused rather than silently downgraded. */
    const val CURRENT_SCHEMA = 1

    /** The entry a Forge-family jar carries; also the name the loader reads. */
    const val ENTRY_NAME = "mcmod.info"

    /**
     * One entry the build puts into the jar, described the way the loader reads it.
     *
     * @param path where the entry sits inside the jar, e.g. `META-INF/libraries/guava-31.1-jre.jar`
     * @param id the artifact's identity, or `null` when it has no coordinates - the loader then derives it from the file
     *           name, which is the same reading a legacy manifest declaration gets
     */
    data class ProvidedDep(val path: String, val id: String?, val version: String?, val kind: String)

    sealed interface Result {
        /** Nothing was declared, so the file is copied through byte for byte. */
        data object Unchanged : Result

        /**
         * [bytes] is the file to write.
         *
         * @param containedDepPaths every entry the written file declares, so the caller can check the jar really
         *                          contains them - including the ones the author declared by hand, which the build does
         *                          not copy and therefore cannot vouch for
         * @param notes decisions worth saying out loud but not worth failing over
         */
        data class Merged(
            val bytes: ByteArray,
            val containedDepPaths: List<String> = emptyList(),
            val notes: List<String> = emptyList(),
        ) : Result {
            override fun equals(other: Any?): Boolean =
                other is Merged && bytes.contentEquals(other.bytes) &&
                    containedDepPaths == other.containedDepPaths && notes == other.notes

            override fun hashCode(): Int =
                31 * (31 * bytes.contentHashCode() + containedDepPaths.hashCode()) + notes.hashCode()
        }

        /** The section cannot be written as asked; the messages name both sides of each disagreement. */
        data class Failed(val errors: List<String>) : Result
    }

    private val gson = GsonBuilder().setPrettyPrinting().create()

    /**
     * @param existing the entry's bytes, or `null` when the jar carries no `mcmod.info` at all
     * @param config what the build declared
     */
    fun merge(
        existing: ByteArray?,
        config: JarMetadataConfig,
        provided: List<ProvidedDep> = emptyList(),
    ): Result {
        if (config.isEmpty() && provided.isEmpty()) {
            return Result.Unchanged
        }

        val root = existing?.let { parseRoot(it) } ?: JsonObject()
        val errors = mutableListOf<String>()
        val notes = mutableListOf<String>()

        // The loader rejects a `jar` that is not an object: writing the section is a claim to follow the schema, so a
        // wrong-typed one is an error rather than an absent section. Replacing it is the only way to produce a jar that
        // loads, and it is only honest if it is said out loud.
        val declaredJar = root.get("jar")
        if (declaredJar != null && !declaredJar.isJsonObject && !declaredJar.isJsonNull) {
            notes += "the mcmod.info declares \"jar\" as something other than an object; it is replaced by the " +
                "declaration from this build, because the loader refuses a jar section that is not an object"
        }
        val existingJar = root.get("jar")?.takeIf { it.isJsonObject }?.asJsonObject
        val jar = existingJar?.deepCopy() ?: JsonObject()

        // schemaVersion: the build owns it. A file that declares a newer schema understands keys this build would
        // happily overwrite, so refusing is the only way to avoid downgrading it by accident.
        val declaredSchema = existingJar?.get("schemaVersion")?.let { asInt(it) }
        if (declaredSchema != null && declaredSchema > CURRENT_SCHEMA) {
            errors += "the mcmod.info declares jar schemaVersion $declaredSchema, but this build writes $CURRENT_SCHEMA. " +
                "Upgrade the build, or remove \"schemaVersion\" from the file and let it be filled in."
        }
        if (config.schemaVersion != null && config.schemaVersion!! > CURRENT_SCHEMA) {
            errors += "jarMetadata declares schemaVersion ${config.schemaVersion}, but this build writes $CURRENT_SCHEMA."
        }

        // license: filled in when missing, but two different declarations are two statements and neither wins.
        val declaredLicense = existingJar?.get("license")?.let { asString(it) }
        if (declaredLicense != null && config.license != null && declaredLicense != config.license) {
            errors += "the mcmod.info declares license \"$declaredLicense\" and jarMetadata declares " +
                "\"${config.license}\". Keep one of them."
        }
        val license = config.license ?: declaredLicense
        if (license.isNullOrBlank()) {
            errors += "no license is declared. A jar section requires one: set it with " +
                "jarMetadata { license \"...\" } or add \"license\" to the mcmod.info. Nothing else can supply it."
        }

        mergeValue(jar, "loadOrder", config.loadOrder?.wireName(), "loadOrder", errors) { asString(it) }
        mergeValue(jar, "side", config.side?.wireName(), "side", errors) { asString(it) }
        mergeValue(jar, "coremod", config.coreMod, "coremod", errors) { asString(it) }
        // Written as a JSON boolean, not a string: the loader accepts only a boolean here and refuses the jar
        // otherwise, so "true" would produce a jar that does not start.
        mergeBoolean(jar, "alsoMod", config.alsoMod, errors)
        mergeList(jar, "at", config.accessTransformers, "at", errors)
        val declaredPaths = mergeContainedDeps(jar, provided, notes, errors)

        if (errors.isNotEmpty()) {
            return Result.Failed(errors)
        }

        jar.addProperty("schemaVersion", CURRENT_SCHEMA)
        jar.addProperty("license", license)

        // Whatever was read is replaced by the merged copy. Writing this only when the section was absent used to lose
        // every added key for the one case that matters most: a file that already declares the section.
        root.add("jar", jar)
        return Result.Merged(gson.toJson(root).toByteArray(Charsets.UTF_8), declaredPaths, notes)
    }

    /**
     * Writes `containedDeps` from what the build is about to put in the jar, keeping the author's `kind` for any entry
     * they already described.
     *
     * The split is deliberate. *What* is bundled, and under which coordinates, is the build's to know: it resolved the
     * artifact and it copied the bytes, so it is the only side that can say what identity a version conflict has to be
     * arbitrated on. *How* the loader should treat it is the author's: a jar can legitimately bundle something it does
     * not want discovered as a mod, and no dependency graph can tell.
     *
     * Entries the author declares that the build does not provide are kept rather than dropped - they may be added by
     * another task - and their paths are reported back so the caller can check the finished jar really has them.
     *
     * When the build provides nothing, the key is left exactly as the author wrote it.
     */
    private fun mergeContainedDeps(
        jar: JsonObject,
        provided: List<ProvidedDep>,
        notes: MutableList<String>,
        errors: MutableList<String>,
    ): List<String> {
        val declared = jar.get("containedDeps")?.takeIf { it.isJsonArray }?.asJsonArray
        val authorEntries = declared?.mapNotNull { it.takeIf { entry -> entry.isJsonObject }?.asJsonObject }.orEmpty()

        if (provided.isEmpty()) {
            return authorEntries.mapNotNull { asString(it.get("path")) }
        }

        val remaining = authorEntries.toMutableList()
        val merged = JsonArray()
        for (dep in provided) {
            val existing = remaining.firstOrNull { matchesPath(it, dep.path) }?.also { remaining.remove(it) }
            val entry = JsonObject()
            // id and version come from the resolution: they are what a second jar bundling the same library is compared
            // on, and a hand-written approximation cannot arbitrate anything.
            val id = dep.id ?: asString(existing?.get("id"))
            val version = dep.version ?: asString(existing?.get("version"))
            if (id != null) entry.addProperty("id", id)
            if (version != null) entry.addProperty("version", version)
            entry.addProperty("path", dep.path)
            // ... but the kind is the author's to decide, and the build's derivation is only a fallback.
            val kind = asString(existing?.get("kind")) ?: dep.kind
            entry.addProperty("kind", kind)

            val authorId = asString(existing?.get("id"))
            val authorVersion = asString(existing?.get("version"))
            if (authorId != null && dep.id != null && authorId != dep.id) {
                notes += "the mcmod.info says the bundled entry ${
                    dep.path
                } is \"$authorId\"; writing the resolved identity \"${dep.id}\""
            }
            if (authorVersion != null && dep.version != null && authorVersion != dep.version) {
                notes += "the mcmod.info says the bundled entry ${dep.path} is version $authorVersion; writing the " +
                    "resolved version ${dep.version}"
            }
            merged.add(entry)
        }
        // Author entries the build does not provide stay: another task may add the file, and dropping the declaration
        // would silently un-declare it.
        remaining.forEach { merged.add(it) }

        jar.add("containedDeps", merged)
        if (merged.isEmpty()) {
            // An empty array is not the same statement as no key: the loader treats both as "nothing bundled", but only
            // one of them reads as an answer to "what did you bundle". Keep the key out.
            jar.remove("containedDeps")
            return emptyList()
        }
        return merged.mapNotNull { asString(it.asJsonObject.get("path")) }
    }

    /** Matched the way the loader matches it: the full path, or the file name when the declaration named one. */
    private fun matchesPath(
        entry: JsonObject,
        path: String,
    ): Boolean {
        val declared = asString(entry.get("path")) ?: return false
        val name = path.substringAfterLast('/')
        return declared == path || declared.substringAfterLast('/') == name
    }

    /**
     * Reads the root of a metadata file.
     *
     * The legacy shape is a bare array of mod entries. Wrapping it under `modList` is what makes a `jar` section
     * readable at all, and it is also the shape the loader accepts without special-casing: an object root, an optional
     * `modList`, and jar-level data beside it.
     */
    private fun parseRoot(existing: ByteArray): JsonObject {
        val parsed = JsonParser.parseString(existing.toString(Charsets.UTF_8))
        return when {
            parsed.isJsonArray -> JsonObject().apply { add("modList", parsed as JsonArray) }
            parsed.isJsonObject -> parsed.asJsonObject
            else -> JsonObject()
        }
    }

    private fun mergeValue(
        jar: JsonObject,
        key: String,
        wanted: String?,
        label: String,
        errors: MutableList<String>,
        read: (JsonElement) -> String?,
    ) {
        if (wanted == null) {
            return
        }
        val current = jar.get(key)?.let(read)
        if (current == null) {
            jar.addProperty(key, wanted)
            return
        }
        if (current != wanted) {
            errors += "the mcmod.info declares $label \"$current\" and jarMetadata declares \"$wanted\". Keep one of them."
        }
    }

    private fun mergeBoolean(
        jar: JsonObject,
        key: String,
        wanted: Boolean?,
        errors: MutableList<String>,
    ) {
        if (wanted == null) {
            return
        }
        val current = jar.get(key)?.takeIf { it.isJsonPrimitive }?.asString
        if (current != null && current != wanted.toString()) {
            errors += "the mcmod.info declares $key $current and jarMetadata declares $wanted. Keep one of them."
            return
        }
        // Written even when the values already agree, so the key ends up with the type the schema declares rather than
        // whatever the file happened to hold. The loader refuses a boolean written as a string, and a hand-written
        // file is the only thing that can produce one - which is exactly the case nothing else would catch.
        jar.addProperty(key, wanted)
    }

    private fun mergeList(
        jar: JsonObject,
        key: String,
        wanted: List<String>,
        label: String,
        errors: MutableList<String>,
    ) {
        if (wanted.isEmpty()) {
            return
        }
        val element = jar.get(key)
        val current = element?.let(::asStringList)
        if (current != null && current != wanted) {
            errors += "the mcmod.info declares $label $current and jarMetadata declares $wanted. Keep one of them."
            return
        }
        // Written as an array even when the whitespace-separated string means the same thing: the loader reads both,
        // but only a list can name a path containing a space.
        jar.add(
            key,
            JsonArray().apply {
                wanted.forEach { add(it) }
            },
        )
    }

    /**
     * Reads a string list, accepting the single whitespace-separated string the manifest used to carry.
     *
     * An array element is never split again: a file name containing a space is exactly what the array form exists to
     * express.
     */
    private fun asStringList(element: JsonElement): List<String> {
        if (element.isJsonArray) {
            return element.asJsonArray.mapNotNull { item -> asString(item) }.filter { it.isNotEmpty() }
        }
        val text = asString(element) ?: return emptyList()
        return text.split(Regex("\\s+")).filter { it.isNotEmpty() }
    }

    private fun asString(element: JsonElement?): String? {
        if (element == null || element.isJsonNull || !element.isJsonPrimitive) {
            return null
        }
        return element.asString.takeIf { it.isNotBlank() }
    }

    private fun asInt(element: JsonElement?): Int? {
        if (element == null || element.isJsonNull || !element.isJsonPrimitive) {
            return null
        }
        return try {
            element.asInt
        } catch (e: NumberFormatException) {
            null
        }
    }
}
