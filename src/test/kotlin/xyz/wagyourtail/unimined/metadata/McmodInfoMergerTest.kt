package xyz.wagyourtail.unimined.metadata

import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import xyz.wagyourtail.unimined.api.mod.JarMetadataConfig
import xyz.wagyourtail.unimined.api.mod.LoadOrder
import xyz.wagyourtail.unimined.api.mod.Side
import xyz.wagyourtail.unimined.internal.minecraft.patch.forge.McmodInfoMerger

/**
 * Covers writing the `jar` section of a `mcmod.info`.
 *
 * Two properties matter more than any individual key: a file the build has nothing to say about must come out byte
 * identical, and running the merge twice must produce the same bytes as running it once. Both are easy to break with a
 * well-meaning reformat and both show up as "the task reruns every time" or "the jar changed for no reason".
 */
class McmodInfoMergerTest {

    private fun config(action: JarMetadataConfig.() -> Unit = {}): JarMetadataConfig = JarMetadataConfig().apply(action)

    private fun merged(
        existing: String?,
        config: JarMetadataConfig,
        provided: List<McmodInfoMerger.ProvidedDep> = emptyList(),
    ): String {
        val result = McmodInfoMerger.merge(existing?.toByteArray(), config, provided)
        assertTrue(result is McmodInfoMerger.Result.Merged, result.toString())
        return (result as McmodInfoMerger.Result.Merged).bytes.toString(Charsets.UTF_8)
    }

    private fun errors(
        existing: String?,
        config: JarMetadataConfig,
        provided: List<McmodInfoMerger.ProvidedDep> = emptyList(),
    ): List<String> {
        val result = McmodInfoMerger.merge(existing?.toByteArray(), config, provided)
        assertTrue(result is McmodInfoMerger.Result.Failed, result.toString())
        return (result as McmodInfoMerger.Result.Failed).errors
    }

    private fun jarOf(json: String) = JsonParser.parseString(json).asJsonObject.getAsJsonObject("jar")

    // ---- nothing declared --------------------------------------------------------------------------------------

    @Test
    fun `an empty configuration leaves the file alone`() {
        val result = McmodInfoMerger.merge("""{"modList": []}""".toByteArray(), JarMetadataConfig())

        assertEquals(McmodInfoMerger.Result.Unchanged, result)
    }

    @Test
    fun `an empty configuration does not create a file`() {
        assertEquals(McmodInfoMerger.Result.Unchanged, McmodInfoMerger.merge(null, JarMetadataConfig()))
    }

    // ---- shapes ------------------------------------------------------------------------------------------------

    @Test
    fun `the legacy array form is wrapped under modList`() {
        val json = merged("""[{"modid": "example", "name": "Example"}]""", config { license("MIT") })

        val root = JsonParser.parseString(json).asJsonObject
        assertEquals("example", root.getAsJsonArray("modList")[0].asJsonObject.get("modid").asString)
        assertEquals("MIT", jarOf(json).get("license").asString)
    }

    @Test
    fun `an existing object keeps its other keys`() {
        val json = merged(
            """{"modList": [{"modid": "example"}], "author": "someone"}""",
            config { license("MIT") },
        )

        val root = JsonParser.parseString(json).asJsonObject
        assertEquals("someone", root.get("author").asString)
        assertEquals("example", root.getAsJsonArray("modList")[0].asJsonObject.get("modid").asString)
    }

    @Test
    fun `a jar with no mcmod info at all gets one`() {
        val json = merged(null, config { license("MIT") })

        assertEquals(1, jarOf(json).get("schemaVersion").asInt)
        assertEquals("MIT", jarOf(json).get("license").asString)
    }

    // ---- schema version and license ----------------------------------------------------------------------------

    @Test
    fun `the build writes its own schema version`() {
        assertEquals(1, jarOf(merged(null, config { license("MIT") })).get("schemaVersion").asInt)
        assertEquals(1, jarOf(merged("""{"jar": {"schemaVersion": 1, "license": "MIT"}}""", config { side("client") })).get("schemaVersion").asInt)
    }

    /** A file written for a newer loader may use keys this build would overwrite, so it is refused, not downgraded. */
    @Test
    fun `a newer schema version in the file is refused`() {
        val messages = errors("""{"jar": {"schemaVersion": 2, "license": "MIT"}}""", config { side("client") })

        assertEquals(1, messages.size)
        assertTrue(messages.single().contains("schemaVersion 2"), messages.single())
    }

    @Test
    fun `a newer schema version in the configuration is refused`() {
        val messages = errors(null, config { schemaVersion(2); license("MIT") })

        assertTrue(messages.single().contains("jarMetadata declares schemaVersion 2"), messages.single())
    }

    @Test
    fun `a section without a license is refused and says how to supply one`() {
        val messages = errors(null, config { side("client") })

        assertTrue(messages.single().contains("license"), messages.single())
        assertTrue(messages.single().contains("jarMetadata { license"), messages.single())
    }

    @Test
    fun `the license already in the file is reused`() {
        val json = merged("""{"jar": {"schemaVersion": 1, "license": "EPL-2.0"}}""", config { side("client") })

        assertEquals("EPL-2.0", jarOf(json).get("license").asString)
    }

    // ---- conflicts ---------------------------------------------------------------------------------------------

    @Test
    fun `two different licenses are a conflict`() {
        val messages = errors("""{"jar": {"schemaVersion": 1, "license": "EPL-2.0"}}""", config { license("MIT") })

        assertTrue(messages.single().contains("EPL-2.0"), messages.single())
        assertTrue(messages.single().contains("MIT"), messages.single())
    }

    @Test
    fun `two different sides are a conflict`() {
        val messages = errors(
            """{"jar": {"schemaVersion": 1, "license": "MIT", "side": "server"}}""",
            config { side("client") },
        )

        assertTrue(messages.single().contains("server"), messages.single())
        assertTrue(messages.single().contains("client"), messages.single())
    }

    @Test
    fun `an agreeing declaration is not a conflict`() {
        val json = merged(
            """{"jar": {"schemaVersion": 1, "license": "MIT", "side": "client"}}""",
            config { side("client") },
        )

        assertEquals("client", jarOf(json).get("side").asString)
    }

    @Test
    fun `two different access transformer lists are a conflict`() {
        val messages = errors(
            """{"jar": {"schemaVersion": 1, "license": "MIT", "at": ["a.cfg"]}}""",
            config { at("b.cfg") },
        )

        assertTrue(messages.single().contains("a.cfg"), messages.single())
        assertTrue(messages.single().contains("b.cfg"), messages.single())
    }

    /**
     * The manifest shape is one whitespace-separated string. Reading it that way keeps a jar that still carries
     * `FMLAT: a.cfg b.cfg` comparable to a list of two paths instead of reporting a conflict nobody has - and the
     * produced file gets the array form, because only that one can express a path containing a space.
     */
    @Test
    fun `the manifest string shape is read as a list and normalised`() {
        val json = merged(
            """{"jar": {"schemaVersion": 1, "license": "MIT", "at": "a.cfg b.cfg"}}""",
            config { at("a.cfg", "b.cfg") },
        )

        assertEquals(listOf("a.cfg", "b.cfg"), accessTransformersOf(json))
        assertTrue(jarOf(json).get("at").isJsonArray, "the array form is written")
    }

    /**
     * The same for a boolean written as a string by an older tool: the values agree, but the type is one the loader
     * refuses, so agreeing is not a reason to leave it.
     */
    @Test
    fun `a boolean written as a string is normalised`() {
        val json = merged(
            """{"jar": {"schemaVersion": 1, "license": "MIT", "alsoMod": "true"}}""",
            config { alsoMod(true) },
        )

        assertTrue(jarOf(json).get("alsoMod").asJsonPrimitive.isBoolean, jarOf(json).get("alsoMod").toString())
        assertTrue(jarOf(json).get("alsoMod").asBoolean)
    }

    /** The loader now rejects a `jar` that is not an object; replacing it is only honest if it is reported. */
    @Test
    fun `a jar section of the wrong type is replaced and reported`() {
        val result = McmodInfoMerger.merge(
            """{"jar": "not a section"}""".toByteArray(),
            config { license("MIT") },
        ) as McmodInfoMerger.Result.Merged

        assertTrue(jarOf(result.bytes.toString(Charsets.UTF_8)).get("schemaVersion").asInt == 1)
        assertTrue(result.notes.single().contains("other than an object"), result.notes.toString())
    }

    /** Reads `at` in either spelling, the way the loader does. */
    private fun accessTransformersOf(json: String): List<String> {
        val element = jarOf(json).get("at")
        if (element.isJsonArray) {
            return element.asJsonArray.map { it.asString }
        }
        return element.asString.split(Regex("\\s+")).filter { it.isNotEmpty() }
    }

    // ---- declared values ---------------------------------------------------------------------------------------

    @Test
    fun `every declared key is written`() {
        val json = merged(
            null,
            config {
                license("MIT")
                side("client")
                loadOrder("first")
                coreMod("com.example.Plugin")
                alsoMod(true)
                at("accesstransformer.cfg", "extra at.cfg")
            },
        )

        val jar = jarOf(json)
        assertEquals("client", jar.get("side").asString)
        assertEquals("first", jar.get("loadOrder").asString)
        assertEquals("com.example.Plugin", jar.get("coremod").asString)
        assertTrue(jar.get("alsoMod").asBoolean)
        assertEquals("accesstransformer.cfg", jar.getAsJsonArray("at")[0].asString)
        // A path with a space is the reason the array form exists; it must not be split.
        assertEquals("extra at.cfg", jar.getAsJsonArray("at")[1].asString)
    }

    @Test
    fun `undeclared keys are not invented`() {
        val jar = jarOf(merged(null, config { license("MIT") }))

        assertFalse(jar.has("side"))
        assertFalse(jar.has("loadOrder"))
        assertFalse(jar.has("coremod"))
        assertFalse(jar.has("alsoMod"))
        assertFalse(jar.has("at"))
    }

    /**
     * The loader's boolean reader refuses anything that is not a JSON boolean, so writing `"true"` produces a jar that
     * does not start. Asserting the primitive's type, not its text: `asString` on a string and on a boolean both answer
     * `true`, which is how this was written as a string once already.
     */
    @Test
    fun `alsoMod is written as a JSON boolean`() {
        val jar = jarOf(merged(null, config { license("MIT"); alsoMod(true) }))

        assertTrue(jar.get("alsoMod").asJsonPrimitive.isBoolean, jar.get("alsoMod").toString())
        assertTrue(jar.get("alsoMod").asBoolean)
    }

    @Test
    fun `alsoMod false is written rather than omitted`() {
        val jar = jarOf(merged(null, config { license("MIT"); alsoMod(false) }))

        assertTrue(jar.get("alsoMod").asJsonPrimitive.isBoolean, jar.get("alsoMod").toString())
        assertFalse(jar.get("alsoMod").asBoolean)
    }

    // ---- preservation ------------------------------------------------------------------------------------------

    @Test
    fun `unknown keys inside the jar section survive`() {
        val json = merged(
            """{"jar": {"schemaVersion": 1, "license": "MIT", "somethingNew": {"a": 1}}}""",
            config { side("client") },
        )

        assertEquals(1, jarOf(json).getAsJsonObject("somethingNew").get("a").asInt)
    }

    @Test
    fun `unknown keys on mod entries survive`() {
        val json = merged(
            """{"modList": [{"modid": "example", "customField": [1, 2]}]}""",
            config { license("MIT") },
        )

        assertEquals(2, JsonParser.parseString(json).asJsonObject.getAsJsonArray("modList")[0]
            .asJsonObject.getAsJsonArray("customField").size())
    }

    @Test
    fun `the modList is never created when the file had none`() {
        val json = merged(null, config { license("MIT") })

        assertNull(JsonParser.parseString(json).asJsonObject.get("modList"))
    }

    // ---- containedDeps -----------------------------------------------------------------------------------------

    private fun dep(
        path: String,
        id: String? = "com.example:lib",
        version: String? = "1.0",
        kind: String = "LIB",
    ) = McmodInfoMerger.ProvidedDep(path, id, version, kind)

    private fun containedDeps(json: String): List<Map<String, String>> =
        jarOf(json).getAsJsonArray("containedDeps").map { element ->
            element.asJsonObject.entrySet().associate { (key, value) -> key to value.asString }
        }

    @Test
    fun `bundled dependencies are declared with their resolved identity`() {
        val json = merged(
            null,
            config { license("MIT") },
            listOf(dep("META-INF/libraries/lib-1.0.jar")),
        )

        assertEquals(
            listOf(
                mapOf(
                    "id" to "com.example:lib",
                    "version" to "1.0",
                    "path" to "META-INF/libraries/lib-1.0.jar",
                    "kind" to "LIB",
                ),
            ),
            containedDeps(json),
        )
    }

    @Test
    fun `a dependency without coordinates omits the identity so the loader falls back to the file name`() {
        val json = merged(
            null,
            config { license("MIT") },
            listOf(dep("META-INF/libraries/handmade.jar", id = null, version = null)),
        )

        val entry = containedDeps(json).single()
        assertFalse(entry.containsKey("id"), entry.toString())
        assertFalse(entry.containsKey("version"), entry.toString())
        assertEquals("handmade.jar", entry["path"]!!.substringAfterLast('/'))
    }

    /**
     * Decision: the build knows *what* is bundled, the author knows *how* it should be treated. A dependency graph
     * cannot tell that a jar bundles something it does not want discovered as a mod.
     */
    @Test
    fun `the author's kind wins over the derived one`() {
        val json = merged(
            """{"jar": {"schemaVersion": 1, "license": "MIT",
                     "containedDeps": [{"path": "META-INF/libraries/lib-1.0.jar", "kind": "MOD"}]}}""",
            config { side("both") },
            listOf(dep("META-INF/libraries/lib-1.0.jar", kind = "LIB")),
        )

        assertEquals("MOD", containedDeps(json).single()["kind"])
    }

    /** ... but the coordinates are the resolution's, because they are what version arbitration compares. */
    @Test
    fun `a hand written identity is replaced by the resolved one and reported`() {
        val result = McmodInfoMerger.merge(
            """{"jar": {"schemaVersion": 1, "license": "MIT",
                     "containedDeps": [{"path": "META-INF/libraries/lib.jar", "id": "lib", "version": "0"}]}}"""
                .toByteArray(),
            config { side("both") },
            listOf(dep("META-INF/libraries/lib.jar", id = "com.example:lib", version = "1.0")),
        ) as McmodInfoMerger.Result.Merged

        val entry = containedDeps(result.bytes.toString(Charsets.UTF_8)).single()
        assertEquals("com.example:lib", entry["id"])
        assertEquals("1.0", entry["version"])
        assertEquals(2, result.notes.size)
        assertTrue(result.notes.any { it.contains("com.example:lib") }, result.notes.toString())
    }

    /** Matched by file name too, the way the loader resolves a declaration that only names the file. */
    @Test
    fun `an author entry named by file name is matched`() {
        val json = merged(
            """{"jar": {"schemaVersion": 1, "license": "MIT", "containedDeps": [{"path": "lib-1.0.jar", "kind": "MOD"}]}}""",
            config { side("both") },
            listOf(dep("META-INF/libraries/lib-1.0.jar")),
        )

        val entries = containedDeps(json)
        assertEquals(1, entries.size, entries.toString())
        assertEquals("MOD", entries.single()["kind"])
        assertEquals("META-INF/libraries/lib-1.0.jar", entries.single()["path"])
    }

    /**
     * A declaration the build does not provide may be satisfied by another task, so it is kept - and reported, so the
     * caller can check the finished jar really has the file.
     */
    @Test
    fun `an author entry the build does not provide is kept and reported back`() {
        val result = McmodInfoMerger.merge(
            """{"jar": {"schemaVersion": 1, "license": "MIT", "containedDeps": [{"path": "META-INF/libraries/by-task.jar"}]}}"""
                .toByteArray(),
            config { side("both") },
            listOf(dep("META-INF/libraries/lib-1.0.jar")),
        ) as McmodInfoMerger.Result.Merged

        assertEquals(
            listOf("META-INF/libraries/lib-1.0.jar", "META-INF/libraries/by-task.jar"),
            result.containedDepPaths,
        )
        assertEquals(2, containedDeps(result.bytes.toString(Charsets.UTF_8)).size)
    }

    /**
     * Declaring a bundled dependency is itself a reason to write the section, so "nothing configured" is not the same
     * as "nothing to do" - and the file that comes out of it still needs the licence the loader requires.
     */
    @Test
    fun `bundling alone is enough to make the section necessary`() {
        val result = McmodInfoMerger.merge(null, JarMetadataConfig(), listOf(dep("META-INF/libraries/lib.jar")))

        assertTrue(result !is McmodInfoMerger.Result.Unchanged, result.toString())
    }

    /**
     * The case that made a whole class of keys disappear: a file that already declares the section. Reading it into a
     * copy and merging into that copy is easy to write and silently drops everything new.
     */
    @Test
    fun `a key is added to a section that already exists`() {
        val json = merged(
            """{"jar": {"schemaVersion": 1, "license": "MIT"}}""",
            config { side("client"); loadOrder("last") },
        )

        assertEquals("client", jarOf(json).get("side").asString)
        assertEquals("last", jarOf(json).get("loadOrder").asString)
        assertEquals("MIT", jarOf(json).get("license").asString)
    }

    /** ... and that means a licence has to come from somewhere, which is the loader's rule, not this build's. */
    @Test
    fun `bundling without a licence is refused`() {
        val messages = errors(null, JarMetadataConfig(), listOf(dep("META-INF/libraries/lib.jar")))

        assertTrue(messages.single().contains("license"), messages.single())
    }

    @Test
    fun `no bundled dependencies leaves the author's declaration untouched`() {
        val json = merged(
            """{"jar": {"schemaVersion": 1, "license": "MIT", "containedDeps": [{"path": "a.jar", "kind": "MOD"}]}}""",
            config { side("both") },
        )

        assertEquals("MOD", containedDeps(json).single()["kind"])
        assertEquals("a.jar", containedDeps(json).single()["path"])
    }

    // ---- idempotency -------------------------------------------------------------------------------------------

    /**
     * The remap task re-reads the same source jar on every run, so the output has to be a function of the input. If it
     * were not, the jar would differ on each build and the task would never be up to date.
     */
    @Test
    fun `merging twice produces the same bytes`() {
        val cfg = config {
            license("MIT")
            side("both")
            at("a.cfg")
        }
        val once = merged("""[{"modid": "example"}]""", cfg)
        val twice = merged(once, cfg)

        assertEquals(once, twice)
    }

    @Test
    fun `the output is stable across separate configurations with the same values`() {
        val first = merged("""{"modList": []}""", config { license("MIT"); side("client") })
        val second = merged("""{"modList": []}""", config { license("MIT"); side("client") })

        assertEquals(first, second)
    }

    // ---- configuration surface ---------------------------------------------------------------------------------

    @Test
    fun `the string setters parse the wire names`() {
        val cfg = config {
            side("CLIENT")
            loadOrder("LAST")
        }

        assertEquals(Side.CLIENT, cfg.side)
        assertEquals(LoadOrder.LAST, cfg.loadOrder)
    }

    @Test
    fun `an unknown side is rejected at declaration time`() {
        val error = org.junit.jupiter.api.assertThrows<IllegalArgumentException> { config { side("clientside") } }

        assertTrue(error.message!!.contains("both, client, server"), error.message!!)
    }

    @Test
    fun `an unknown load order is rejected at declaration time`() {
        val error = org.junit.jupiter.api.assertThrows<IllegalArgumentException> { config { loadOrder("middle") } }

        assertTrue(error.message!!.contains("first, normal, last"), error.message!!)
    }

    @Test
    fun `a configuration that declares anything is not empty`() {
        assertTrue(JarMetadataConfig().isEmpty())
        assertFalse(config { side("both") }.isEmpty())
        assertFalse(config { at("a.cfg") }.isEmpty())
        // Access transformer validation on its own is not a declaration.
        assertTrue(config { accessTransformerValidation(false) }.isEmpty())
    }

    @Test
    fun `the fingerprint changes when a declared value changes`() {
        val a = config { license("MIT") }.fingerprint()
        val b = config { license("Apache-2.0") }.fingerprint()

        assertTrue(a != b, "fingerprints must differ so the task reruns")
    }
}
