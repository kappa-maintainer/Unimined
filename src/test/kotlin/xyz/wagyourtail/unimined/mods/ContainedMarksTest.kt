package xyz.wagyourtail.unimined.mods

import groovy.lang.Closure
import org.gradle.api.InvalidUserDataException
import org.gradle.api.Project
import org.gradle.api.artifacts.Dependency
import org.gradle.api.artifacts.DependencyArtifact
import org.gradle.api.artifacts.ExternalModuleDependency
import org.gradle.api.internal.artifacts.dependencies.DefaultDependencyArtifact
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import xyz.wagyourtail.unimined.api.mod.ContainedModule
import xyz.wagyourtail.unimined.UniminedExtensionImpl
import xyz.wagyourtail.unimined.api.unimined

/**
 * Covers the declaration side: what a build script is allowed to mark, and what it is not.
 *
 * Everything here is about rejecting a declaration that would later produce a jar nobody can explain, so the tests are
 * mostly about the errors rather than the happy path.
 */
class ContainedMarksTest {

    private lateinit var project: Project

    @BeforeEach
    fun setUp() {
        // The extension without the rest of the plugin: this covers the declaration side only, and applying the real
        // plugin needs a plugin repository ProjectBuilder does not have.
        project = ProjectBuilder.builder().build()
        project.extensions.create("unimined", UniminedExtensionImpl::class.java, project)
        project.unimined.containedMarks.installGroovySugar()
    }

    private fun declare(
        notation: String,
        by: String = "dependencies {}",
    ) = project.unimined.containedMarks.declare(project.dependencies.create(notation), notation, by)

    @Test
    fun `a declaration records the module and the version`() {
        val mark = declare("com.example:mod:1.0")

        assertEquals(ContainedModule("com.example", "mod", null), mark.module)
        assertEquals("1.0", mark.version)
        assertEquals("com.example:mod:1.0", mark.notation)
    }

    @Test
    fun `a classifier is part of the identity`() {
        val mark = declare("com.example:mod:1.0:dev")

        assertEquals(ContainedModule("com.example", "mod", "dev"), mark.module)
        assertEquals("com.example:mod:dev", mark.module?.id)
    }

    @Test
    fun `declaring the same module twice keeps the first`() {
        val first = declare("com.example:mod:1.0", "dependencies {}")
        val second = declare("com.example:mod:2.0", "mods { main }")

        assertEquals(first, second)
        assertEquals(1, project.unimined.containedMarks.declared().size)
    }

    @Test
    fun `declaring the same module under another classifier is allowed`() {
        declare("com.example:mod:1.0")
        declare("com.example:mod:1.0:dev")

        assertEquals(
            listOf("com.example:mod", "com.example:mod:dev"),
            project.unimined.containedMarks.declared().map { it.module?.id },
        )
    }

    /**
     * A project dependency's jar task produces a dev-namespace jar, so bundling it would ship names that only resolve
     * in a development environment. Refusing here is much cheaper than explaining the NoClassDefFoundError later.
     */
    @Test
    fun `a project dependency is refused`() {
        val other = ProjectBuilder.builder().withParent(project).withName("other").build()

        val error = assertThrows<InvalidUserDataException> {
            project.unimined.containedMarks.declare(project.dependencies.project(mapOf("path" to other.path)), ":other", "dependencies {}")
        }
        assertTrue(error.message!!.contains("project dependency"), error.message!!)
        assertTrue(error.message!!.contains("published artifact"), error.message!!)
    }

    /**
     * Two artifacts for one module only arise from an explicit classifier list, and the jar cannot say which of them it
     * contains - so the declaration is refused instead of silently bundling one of them.
     */
    @Test
    fun `a declaration with several artifacts is refused`() {
        val delegate = project.dependencies.create("com.example:mod:1.0") as ExternalModuleDependency
        val twoArtifacts = object : ExternalModuleDependency by delegate {
            override fun getArtifacts(): MutableSet<DependencyArtifact> =
                mutableSetOf(
                    DefaultDependencyArtifact("mod", "jar", null, null, null),
                    DefaultDependencyArtifact("mod", "jar", "extra", null, null),
                )
        }

        val error = assertThrows<InvalidUserDataException> {
            project.unimined.containedMarks.declare(twoArtifacts, "com.example:mod:1.0", "dependencies {}")
        }
        assertTrue(error.message!!.contains("2 artifacts"), error.message!!)
    }

    /**
     * A `files(...)` dependency has no coordinates, so there is no identity to argue about: it is bundled as one entry
     * named after the file.
     */
    @Test
    fun `a file dependency has no module`() {
        val mark = project.unimined.containedMarks.declare(
            project.dependencies.create(project.files("libs/handmade.jar")),
            "[/abs/libs/handmade.jar]",
            "mods { main }",
        )

        assertNull(mark.module)
        assertNull(mark.version)
    }

    @Test
    fun `an empty declaration list reports itself as empty`() {
        assertTrue(project.unimined.containedMarks.isEmpty())
        declare("com.example:mod:1.0")
        assertTrue(!project.unimined.containedMarks.isEmpty())
    }

    /**
     * The Groovy spelling: a build script declares a bundled dependency inside `dependencies {}`, where the marking
     * call has to be reachable without an import. That works because the plugin installs a closure with this name in the
     * project's extra properties, and `dependencies {}` delegates unknown calls to the script.
     */
    @Test
    fun `the groovy spelling declares and returns the dependency`() {
        @Suppress("UNCHECKED_CAST")
        val contained = project.extensions.extraProperties.get("contained") as Closure<*>

        val returned = contained.call("com.example:mod:1.0")

        assertTrue(returned is Dependency, returned.toString())
        assertEquals("com.example:mod:1.0", project.unimined.containedMarks.declared().single().notation)

        // Returning the same dependency object is what lets the call sit inside a configuration declaration.
        project.configurations.create("modImplementation").dependencies.add(returned as Dependency)
        assertEquals(1, project.configurations.getByName("modImplementation").dependencies.size)
    }
}
