package xyz.wagyourtail.unimined.mods

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import xyz.wagyourtail.unimined.api.mod.ContainedKind
import xyz.wagyourtail.unimined.api.mod.ContainedMark
import xyz.wagyourtail.unimined.api.mod.ContainedModule
import xyz.wagyourtail.unimined.internal.mods.ContainedPlanner
import xyz.wagyourtail.unimined.internal.mods.ContainedPlanner.Role
import java.io.File

/**
 * Covers the decision of *what* a jar bundles: which resolved file a declaration names, which kind it becomes, and the
 * three ways this can silently produce a broken jar.
 *
 * Nothing here needs a build: the planner takes declarations and resolved artifacts, so the interesting cases are
 * reachable without resolving anything.
 */
class ContainedPlannerTest {

    private fun module(
        group: String,
        name: String,
        classifier: String? = null,
    ) = ContainedModule(group, name, classifier)

    private fun mark(
        group: String,
        name: String,
        version: String? = "1.0",
        classifier: String? = null,
        declaredBy: String = "dependencies {}",
    ) = ContainedMark(
        notation = "$group:$name:$version",
        module = module(group, name, classifier),
        version = version,
        declaredBy = declaredBy,
    )

    private fun entry(
        group: String,
        name: String,
        file: String,
        role: Role,
        version: String = "1.0",
        classifier: String? = null,
    ) = ContainedPlanner.Entry(module(group, name, classifier), version, File(file), role)

    private fun plan(
        marks: List<ContainedMark>,
        entries: List<ContainedPlanner.Entry>,
        fallback: Map<String, List<File>> = emptyMap(),
        devClassPath: Set<File> = emptySet(),
    ) = ContainedPlanner.plan(marks, entries, { mark -> fallback[mark.notation].orEmpty() }, devClassPath)

    @Test
    fun `a mod dependency is bundled as a mod`() {
        val result = plan(
            listOf(mark("com.example", "coolmod")),
            listOf(
                entry("com.example", "coolmod", "cache/coolmod-1.0.jar", Role.REMAPPED),
                // The remapped output is on the class path too; the bundled copy must be the original, and the
                // original is what a remapped entry carries.
                entry("com.example", "coolmod", "cache/coolmod-1.0-mapped-mcp.jar", Role.PLAIN),
            ),
        )

        assertTrue(result.errors.isEmpty(), result.errors.toString())
        assertEquals(1, result.jars.size)
        assertEquals(ContainedKind.MOD, result.jars.single().kind)
        assertEquals("cache/coolmod-1.0.jar", result.jars.single().file.invariantSeparatorsPath)
        assertEquals("com.example:coolmod", result.jars.single().id)
        assertEquals("1.0", result.jars.single().version)
    }

    @Test
    fun `a library dependency is bundled as a library`() {
        val result = plan(
            listOf(mark("org.openjdk.nashorn", "nashorn-core", "15.4")),
            listOf(entry("org.openjdk.nashorn", "nashorn-core", "cache/nashorn-core-15.4.jar", Role.PLAIN, "15.4")),
        )

        assertTrue(result.errors.isEmpty(), result.errors.toString())
        assertEquals(ContainedKind.LIB, result.jars.single().kind)
        assertTrue(result.warnings.isEmpty(), result.warnings.toString())
    }

    @Test
    fun `a classifier is part of the identity`() {
        val result = plan(
            listOf(mark("com.example", "library", "1.0", classifier = "dev")),
            listOf(
                entry("com.example", "library", "cache/library-1.0.jar", Role.PLAIN),
                entry("com.example", "library", "cache/library-1.0-dev.jar", Role.PLAIN, classifier = "dev"),
            ),
        )

        assertTrue(result.errors.isEmpty(), result.errors.toString())
        assertEquals("com.example:library:dev", result.jars.single().id)
        assertEquals("cache/library-1.0-dev.jar", result.jars.single().file.invariantSeparatorsPath)
    }

    /**
     * The one case where the produced jar would look right and fail at runtime: a jar published with a `dev` classifier
     * is in dev names, so unpacking it in production would look for classes that only exist in a development
     * environment.
     */
    @Test
    fun `a dev namespace only dependency is refused`() {
        val result = plan(
            listOf(mark("com.example", "devmod")),
            listOf(entry("com.example", "devmod", "cache/devmod-1.0.jar", Role.DEV_NAMESPACE)),
        )

        assertTrue(result.jars.isEmpty())
        assertEquals(1, result.errors.size)
        assertTrue(result.errors.single().contains("dev namespace"), result.errors.single())
        assertTrue(result.errors.single().contains("modImplementation"), result.errors.single())
    }

    /**
     * The counterpart of the dev-namespace refusal, and the reason it is not enough on its own: an artifact can carry
     * dev names while sitting in a remap configuration, where everything else is in production names. Packing it would
     * put mcp names into a published jar, which is the one failure a consumer cannot diagnose.
     */
    @Test
    fun `a dev classified artifact in a remap configuration is refused`() {
        val result = plan(
            listOf(mark("com.example", "mod", "1.0", classifier = "dev")),
            listOf(entry("com.example", "mod", "cache/mod-1.0-dev.jar", Role.DEV_CLASSIFIER, classifier = "dev")),
        )

        assertTrue(result.jars.isEmpty())
        assertEquals(1, result.errors.size)
        assertTrue(result.errors.single().contains("dev classifier"), result.errors.single())
        assertTrue(result.errors.single().contains("mcp names"), result.errors.single())
    }

    /**
     * When the same module is available both ways, the production copy is what gets bundled - a project that also
     * declares the dev artifact for other reasons does not lose the ability to bundle.
     */
    @Test
    fun `a production copy outranks a dev classified one`() {
        val result = plan(
            listOf(mark("com.example", "mod")),
            listOf(
                entry("com.example", "mod", "cache/mod-1.0-dev.jar", Role.DEV_CLASSIFIER, classifier = "dev"),
                entry("com.example", "mod", "cache/mod-1.0.jar", Role.REMAPPED),
            ),
        )

        assertTrue(result.errors.isEmpty(), result.errors.toString())
        assertEquals("cache/mod-1.0.jar", result.jars.single().file.invariantSeparatorsPath)
        assertEquals(ContainedKind.MOD, result.jars.single().kind)
    }

    @Test
    fun `a dev classified module is refused even when it is also a plain dependency`() {
        val result = plan(
            listOf(mark("com.example", "mod", "1.0", classifier = "dev")),
            listOf(
                entry("com.example", "mod", "cache/mod-1.0-dev.jar", Role.PLAIN, classifier = "dev"),
                entry("com.example", "mod", "remote/mod-1.0-dev.jar", Role.DEV_CLASSIFIER, classifier = "dev"),
            ),
        )

        assertTrue(result.jars.isEmpty())
        assertEquals(1, result.errors.size)
    }

    @Test
    fun `a remapped copy settles a module that is also reachable in dev names`() {
        val result = plan(
            listOf(mark("com.example", "mod")),
            listOf(
                entry("com.example", "mod", "cache/mod-1.0-dev.jar", Role.DEV_NAMESPACE),
                entry("com.example", "mod", "cache/mod-1.0.jar", Role.REMAPPED),
            ),
        )

        assertTrue(result.errors.isEmpty(), result.errors.toString())
        assertEquals(ContainedKind.MOD, result.jars.single().kind)
        assertEquals("cache/mod-1.0.jar", result.jars.single().file.invariantSeparatorsPath)
    }

    @Test
    fun `a declaration on no class path is resolved from itself and warned about`() {
        val jar = File("libs/handmade.jar")
        val result = plan(
            listOf(mark("com.example", "handmade")),
            emptyList(),
            mapOf("com.example:handmade:1.0" to listOf(jar)),
        )

        assertTrue(result.errors.isEmpty(), result.errors.toString())
        assertEquals(ContainedKind.LIB, result.jars.single().kind)
        // No identity is invented: the loader derives one from the file name, which is the same reading a legacy
        // declaration gets, and a fabricated id would make this jar never match the real library in another jar.
        assertEquals(null, result.jars.single().id)
        assertEquals(null, result.jars.single().version)
        assertEquals("handmade.jar", result.jars.single().fileName)
        assertEquals(1, result.warnings.size)
        assertTrue(result.warnings.single().contains("not on the dev class path"), result.warnings.single())
    }

    /**
     * A file declaration has no identity to match a module against, so the only question worth asking is whether the
     * file is on the dev class path. A project that compiles against it must not be told it will not see it.
     */
    @Test
    fun `a file declaration that is on the dev class path is not warned about`() {
        val jar = File("libs/handmade.jar").absoluteFile
        val mark = ContainedMark("[libs/handmade.jar]", null, null, "dependencies {}")
        val result = plan(
            listOf(mark),
            emptyList(),
            mapOf("[libs/handmade.jar]" to listOf(jar)),
            devClassPath = setOf(jar.normalize()),
        )

        assertTrue(result.errors.isEmpty(), result.errors.toString())
        assertTrue(result.warnings.isEmpty(), result.warnings.toString())
        assertEquals(ContainedKind.LIB, result.jars.single().kind)
    }

    @Test
    fun `a file declaration that is not on the dev class path is warned about`() {
        val jar = File("libs/handmade.jar").absoluteFile
        val mark = ContainedMark("[libs/handmade.jar]", null, null, "dependencies {}")
        val result = plan(
            listOf(mark),
            emptyList(),
            mapOf("[libs/handmade.jar]" to listOf(jar)),
            devClassPath = emptySet(),
        )

        assertEquals(1, result.warnings.size)
        assertTrue(result.warnings.single().contains("not on the dev class path"), result.warnings.single())
    }

    @Test
    fun `a declaration that does not resolve is an error`() {
        val result = plan(listOf(mark("com.example", "missing")), emptyList())

        assertTrue(result.jars.isEmpty())
        assertEquals(1, result.errors.size)
        assertTrue(result.errors.single().contains("could not be resolved"), result.errors.single())
    }

    @Test
    fun `a declaration that resolves to two files is an error`() {
        val result = plan(
            listOf(mark("com.example", "mod")),
            listOf(
                entry("com.example", "mod", "cache/mod-1.0.jar", Role.REMAPPED),
                entry("com.example", "mod", "cache/mod-1.0-extra.jar", Role.REMAPPED),
            ),
        )

        assertTrue(result.jars.isEmpty())
        assertEquals(1, result.errors.size)
        assertTrue(result.errors.single().contains("2 files"), result.errors.single())
    }

    @Test
    fun `two modules sharing one file name are an error`() {
        val result = plan(
            listOf(mark("com.example", "first"), mark("com.example", "second")),
            listOf(
                entry("com.example", "first", "cache/library-1.0.jar", Role.PLAIN),
                entry("com.example", "second", "other/library-1.0.jar", Role.PLAIN),
            ),
        )

        assertTrue(result.jars.isEmpty())
        assertEquals(1, result.errors.size)
        assertTrue(result.errors.single().contains("library-1.0.jar"), result.errors.single())
    }

    @Test
    fun `a version disagreement warns and bundles the resolved version`() {
        val result = plan(
            listOf(mark("com.google.guava", "guava", "31.1-jre")),
            listOf(entry("com.google.guava", "guava", "cache/guava-33.0-jre.jar", Role.PLAIN, "33.0-jre")),
        )

        assertTrue(result.errors.isEmpty(), result.errors.toString())
        assertEquals("33.0-jre", result.jars.single().version)
        assertEquals(1, result.warnings.size)
        assertTrue(result.warnings.single().contains("31.1-jre"), result.warnings.single())
        assertTrue(result.warnings.single().contains("33.0-jre"), result.warnings.single())
    }

    @Test
    fun `a non jar is refused instead of copied`() {
        val result = plan(
            listOf(mark("com.example", "mod")),
            listOf(entry("com.example", "mod", "cache/mod-1.0.zip", Role.REMAPPED)),
        )

        assertTrue(result.jars.isEmpty())
        assertEquals(1, result.errors.size)
        assertTrue(result.errors.single().contains("not a jar"), result.errors.single())
    }

    /**
     * The same artifact can legitimately appear under two classifiers (a `dev` build and a plain one). Those are
     * different identities, so each is bundled under its own name; the failure mode this guards against is the one
     * where they are treated as one module and the jar cannot say which file it contains.
     */
    @Test
    fun `two classifiers of one module are two entries`() {
        val result = plan(
            listOf(
                mark("com.example", "library", "1.0"),
                mark("com.example", "library", "1.0", classifier = "dev"),
            ),
            listOf(
                entry("com.example", "library", "cache/library-1.0.jar", Role.PLAIN),
                entry("com.example", "library", "cache/library-1.0-dev.jar", Role.DEV_NAMESPACE, classifier = "dev"),
            ),
        )

        // The two are separate identities, so the plain copy is bundled and the dev-classified one is refused on its
        // own - not silently mixed up with the other, which is what a name-based identity would have done.
        assertEquals(1, result.jars.size)
        assertEquals("com.example:library", result.jars.single().id)
        assertEquals("cache/library-1.0.jar", result.jars.single().file.invariantSeparatorsPath)
        assertEquals(1, result.errors.size)
        assertTrue(result.errors.single().contains("dev namespace"), result.errors.single())
    }

    @Test
    fun `the same module declared twice is bundled once`() {
        val result = plan(
            listOf(mark("com.example", "mod"), mark("com.example", "mod")),
            listOf(entry("com.example", "mod", "cache/mod-1.0.jar", Role.REMAPPED)),
        )

        assertTrue(result.errors.isEmpty(), result.errors.toString())
        assertEquals(1, result.jars.size)
    }

    @Test
    fun `declaration order is preserved`() {
        val result = plan(
            listOf(mark("b", "second"), mark("a", "first")),
            listOf(
                entry("b", "second", "cache/second-1.0.jar", Role.PLAIN),
                entry("a", "first", "cache/first-1.0.jar", Role.PLAIN),
            ),
        )

        assertEquals(listOf("b:second", "a:first"), result.jars.map { it.id })
    }

    /**
     * A `files(...)` declaration has no coordinates, so it can never match a class path entry by identity. It is
     * resolved from itself, and the file name becomes its id - which is exactly the reading the loader gives a
     * declaration that names no id.
     */
    @Test
    fun `a file dependency is bundled under its file name`() {
        val fileMark = ContainedMark("[/abs/libs/x.jar]", null, null, "mods { main }")
        val result = plan(listOf(fileMark), emptyList(), mapOf("[/abs/libs/x.jar]" to listOf(File("libs/x.jar"))))

        assertTrue(result.errors.isEmpty(), result.errors.toString())
        assertEquals(null, result.jars.single().id)
        assertEquals(null, result.jars.single().version)
        assertTrue(result.jars.single().hasIdentity.not())
    }

    @Test
    fun `an empty declaration list bundles nothing`() {
        val result = plan(emptyList(), listOf(entry("com.example", "unused", "cache/x-1.0.jar", Role.PLAIN)))

        assertTrue(result.jars.isEmpty())
        assertTrue(result.errors.isEmpty())
        assertTrue(result.warnings.isEmpty())
    }
}
