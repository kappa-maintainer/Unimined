package xyz.wagyourtail.unimined.mods

import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import xyz.wagyourtail.unimined.internal.mods.McmodInfoStripper

/**
 * Covers removing the bundled-dependency declaration from the copy a development run gets.
 *
 * The tests are mostly about what must *not* change: the same reasoning that makes the removal right also makes it
 * wrong to reformat an untouched file or to drop the `jar` section along with the key.
 */
class McmodInfoStripperTest {

    private fun strip(text: String) = McmodInfoStripper.stripContainedDeps(text.toByteArray())

    private fun stripped(text: String): String {
        val result = strip(text)
        assertTrue(result is McmodInfoStripper.Result.Stripped, result.toString())
        return (result as McmodInfoStripper.Result.Stripped).bytes.toString(Charsets.UTF_8)
    }

    @Test
    fun `the declaration is removed and the rest of the jar section stays`() {
        val json =
            stripped(
                """
                {
                  "jar": {
                    "schemaVersion": 1,
                    "license": "MIT",
                    "containedDeps": [
                      {"id": "com.google.guava:guava", "version": "31.1-jre", "path": "META-INF/libraries/guava-31.1-jre.jar", "kind": "LIB"}
                    ]
                  }
                }
                """.trimIndent(),
            )

        val jar = JsonParser.parseString(json).asJsonObject.getAsJsonObject("jar")
        assertFalse(jar.has("containedDeps"))
        assertEquals(1, jar.get("schemaVersion").asInt)
        assertEquals("MIT", jar.get("license").asString)
    }

    /**
     * The section itself is the signal that the jar was written against the current environment, and the LWJGL2 filter
     * reads it. Removing the whole section instead of one key would quietly change that.
     */
    @Test
    fun `the jar section is kept even when it becomes sparse`() {
        val json =
            stripped(
                """
                {"jar": {"schemaVersion": 1, "license": "MIT", "containedDeps": [{"path": "a.jar"}]}}
                """.trimIndent(),
            )

        val root = JsonParser.parseString(json).asJsonObject
        assertTrue(root.has("jar"), json)
        assertTrue(root.getAsJsonObject("jar").has("schemaVersion"), json)
    }

    @Test
    fun `the mod list and its unknown fields are left alone`() {
        val json =
            stripped(
                """
                {
                  "modList": [{"modid": "example", "name": "Example", "somethingCustom": {"a": 1}}],
                  "jar": {"schemaVersion": 1, "license": "MIT", "containedDeps": [{"path": "x.jar"}]},
                  "futureKey": "kept"
                }
                """.trimIndent(),
            )

        val root = JsonParser.parseString(json).asJsonObject
        assertEquals("example", root.getAsJsonArray("modList")[0].asJsonObject.get("modid").asString)
        assertEquals(1, root.getAsJsonArray("modList")[0].asJsonObject.getAsJsonObject("somethingCustom").get("a").asInt)
        assertEquals("kept", root.get("futureKey").asString)
    }

    @Test
    fun `other jar keys are preserved, including ones this tool does not understand`() {
        val json =
            stripped(
                """
                {"jar": {"schemaVersion": 1, "license": "MIT", "side": "client",
                         "at": ["a.cfg", "b c.cfg"], "coremod": "com.example.Plugin",
                         "containedDeps": [{"path": "x.jar"}]}}
                """.trimIndent(),
            )

        val jar = JsonParser.parseString(json).asJsonObject.getAsJsonObject("jar")
        assertEquals("client", jar.get("side").asString)
        assertEquals(2, jar.getAsJsonArray("at").size())
        assertEquals("com.example.Plugin", jar.get("coremod").asString)
    }

    @Test
    fun `a jar with no declaration is reported unchanged so its bytes stay identical`() {
        val text = """{"jar": {"schemaVersion": 1, "license": "MIT"}}"""

        assertEquals(McmodInfoStripper.Result.Unchanged, strip(text))
    }

    /** The legacy array form has no jar-level data at all, so a match inside it is somebody's mod entry. */
    @Test
    fun `the legacy array form is untouched`() {
        val text = """[{"modid": "example", "description": "mentions \"containedDeps\" in passing"}]"""

        assertEquals(McmodInfoStripper.Result.Unchanged, strip(text))
    }

    @Test
    fun `an object without a jar section is untouched`() {
        val text = """{"modList": [{"modid": "example"}], "containedDeps": "not ours"}"""

        assertEquals(McmodInfoStripper.Result.Unchanged, strip(text))
    }

    /**
     * A file that cannot be parsed is copied through rather than dropped or half-written: the jar has to stay loadable,
     * and a broken metadata file is its author's to fix.
     */
    @Test
    fun `an unreadable file is reported instead of rewritten`() {
        val result = strip("""{"jar": {"containedDeps": [ }""")

        assertTrue(result is McmodInfoStripper.Result.Unreadable, result.toString())
    }

    @Test
    fun `the removed entries are described for the log line`() {
        val result = strip(
            """
            {"jar": {"schemaVersion": 1, "license": "MIT", "containedDeps": [
              {"id": "com.google.guava:guava", "version": "31.1-jre", "path": "META-INF/libraries/guava.jar", "kind": "LIB"},
              {"path": "META-INF/libraries/plain.jar"}
            ]}}
            """.trimIndent(),
        )

        val removed = (result as McmodInfoStripper.Result.Stripped).removed
        assertEquals(2, removed.size)
        assertEquals("com.google.guava:guava", removed[0].id)
        assertEquals("META-INF/libraries/guava.jar", removed[0].path)
        // A declaration that names no id/version/kind is still named by its path, not printed as an empty bracket.
        assertEquals("META-INF/libraries/plain.jar", removed[1].toString())
        assertTrue(removed[0].toString().contains("31.1-jre"), removed[0].toString())
        assertTrue(removed[0].toString().contains("LIB"), removed[0].toString())
    }

    @Test
    fun `an empty declaration list is still a declaration and is removed`() {
        val json = stripped("""{"jar": {"schemaVersion": 1, "license": "MIT", "containedDeps": []}}""")

        assertFalse(JsonParser.parseString(json).asJsonObject.getAsJsonObject("jar").has("containedDeps"))
    }
}
