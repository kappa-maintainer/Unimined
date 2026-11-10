package xyz.wagyourtail.unimined.mods

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import xyz.wagyourtail.unimined.internal.mapping.at.AccessTransformerApplier
import java.nio.file.Path

/**
 * Which entries of a jar are access transformers.
 *
 * The failure this guards is silent by construction: an AT that is not recognized is not an error at build time, it is
 * a file that keeps its source namespace and a loader rule that never matches a class.
 */
class AtEntryMatchTest {

    @Test
    fun `the file name convention is recognized anywhere in the jar`() {
        assertTrue(AccessTransformerApplier.isAtEntry("META-INF/accesstransformer.cfg"))
        assertTrue(AccessTransformerApplier.isAtEntry("accesstransformer.cfg"))
        assertTrue(AccessTransformerApplier.isAtEntry("META-INF/foo_at.cfg"))
        assertTrue(AccessTransformerApplier.isAtEntry("foo_at.cfg"))
        assertTrue(AccessTransformerApplier.isAtEntry("config/nested/foo_at.cfg"))
    }

    @Test
    fun `a plain cfg is not an access transformer on its own`() {
        assertFalse(AccessTransformerApplier.isAtEntry("at.cfg"))
        assertFalse(AccessTransformerApplier.isAtEntry("META-INF/config.cfg"))
        assertFalse(AccessTransformerApplier.isAtEntry("META-INF/mymod_at.json"))
    }

    @Test
    fun `a declared file name is enough`() {
        assertTrue(AccessTransformerApplier.isAtEntry("META-INF/at.cfg", listOf("at.cfg")))
        // ... and the declaration is the only thing that can name one at a non standard location without a hint
        assertTrue(AccessTransformerApplier.isAtEntry("META-INF/at.cfg", listOf("META-INF/at.cfg")))
    }

    @Test
    fun `a declared path with a directory matches the entry path, not just the file name`() {
        assertTrue(AccessTransformerApplier.isAtEntry("META-INF/at/foo.cfg", listOf("at/foo.cfg")))
        assertTrue(AccessTransformerApplier.isAtEntry("META-INF/at/nested/foo.cfg", listOf("at/nested/foo.cfg")))
    }

    @Test
    fun `a declared path does not match a different file of the same name`() {
        assertFalse(AccessTransformerApplier.isAtEntry("META-INF/other/foo.cfg", listOf("at/foo.cfg")))
        assertFalse(AccessTransformerApplier.isAtEntry("META-INF/at/bar.cfg", listOf("at/foo.cfg")))
    }

    @Test
    fun `blank declarations are ignored`() {
        assertFalse(AccessTransformerApplier.isAtEntry("META-INF/foo.cfg", listOf("  ", "/")))
    }

    @Test
    fun `the path overload uses the same rules`() {
        assertTrue(AccessTransformerApplier.isAtEntry(Path.of("META-INF/at/foo.cfg"), listOf("at/foo.cfg")))
        assertFalse(AccessTransformerApplier.isAtEntry(Path.of("META-INF/foo.cfg"), emptyList()))
    }
}
