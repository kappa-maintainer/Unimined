package xyz.wagyourtail.unimined.api.mod

import groovy.lang.Closure
import org.gradle.api.InvalidUserDataException
import org.gradle.api.Project
import org.gradle.api.artifacts.Dependency
import org.gradle.api.artifacts.ExternalModuleDependency
import org.gradle.api.artifacts.ProjectDependency
import org.jetbrains.annotations.ApiStatus
import xyz.wagyourtail.unimined.api.unimined
import java.io.File

/**
 * The dependencies a mod project wants bundled inside its jar, as declared.
 *
 * Kept on the extension rather than in a configuration: a bundled dependency is almost always *also* a regular
 * dependency of the project (that is what makes the code compile against it), so this list exists only to say "and put
 * a copy of it in the jar". It is deliberately not on any class path, so marking a dependency cannot change what is
 * compiled or run — only what the produced jar contains.
 *
 * Every declaration is project-wide, and every minecraft configuration in the project bundles all of them: a project
 * that builds one jar per loader needs the same library in both, and "which loader does this belong to" is a question
 * the declaration cannot answer anyway.
 *
 * ## Why marks are not a configuration
 *
 * A configuration would resolve on its own graph. That is the one thing this must not do: the jar has to contain the
 * *same version* the project compiled against, and an independent resolution can pick a different one when a BOM or a
 * constraint raises the version on the real class path. Instead a mark is matched against the resolution that already
 * happened (see [ModsConfig.getBundledJars]), and only resolved on its own when it is on no class path at all.
 */
@ApiStatus.Internal
class ContainedMarks(private val project: Project) {

    private data class Declaration(
        val mark: ContainedMark,
        val dependency: Dependency,
    )

    private val declarations = mutableListOf<Declaration>()

    /** @return the declared marks, in declaration order */
    fun declared(): List<ContainedMark> = declarations.map { it.mark }

    /** @return whether anything was declared, without building the list */
    fun isEmpty(): Boolean = declarations.isEmpty()

    /**
     * The tasks that produce the declared files, for declarations made from a file collection.
     *
     * A `contained(files(otherTask))` declaration only names a path, so nothing would order the copy after the task
     * that writes it. Reading the build dependencies of the declaration is the only place that information survives -
     * resolving the configuration at execution time is too late for Gradle to add the edge.
     *
     * @return the tasks to depend on, empty for declarations that come from a repository
     */
    fun buildDependencies(): Set<org.gradle.api.Task> =
        declarations.mapNotNull { it.dependency as? org.gradle.api.artifacts.FileCollectionDependency }
            .flatMap { it.files.buildDependencies.getDependencies(null) }
            .toSet()

    /**
     * Installs [ContainedDependencyClosure] under the name `contained` for build scripts.
     *
     * Done by the plugin, not here, because a project that only uses the Kotlin spelling has no reason to gain a
     * dynamic method it never calls.
     */
    fun installGroovySugar() {
        project.extensions.extraProperties.set("contained", ContainedDependencyClosure(project))
    }

    /**
     * Records that [dependency] should be copied into the produced jar.
     *
     * Declaring the same module twice is not an error: the first declaration wins, because two declarations of one
     * module differ only in the version they name and the jar can only contain one.
     */
    fun declare(
        dependency: Dependency,
        notation: String,
        declaredBy: String,
    ): ContainedMark {
        if (dependency is ProjectDependency) {
            throw InvalidUserDataException(
                "contained($notation) is a project dependency, which is not supported: the jar task of " +
                    "another project produces a dev-namespace jar, so bundling it would ship class names that only " +
                    "resolve in a development environment. Bundle the published artifact of that project instead.",
            )
        }
        val module = moduleOf(dependency, notation)
        if (module != null) {
            val existing = declarations.firstOrNull { it.mark.module == module }
            if (existing != null) {
                project.logger.info(
                    "[Unimined/Contained ${project.path}] $module is already bundled (declared by " +
                        "${existing.mark.declaredBy}), ignoring the declaration from $declaredBy",
                )
                return existing.mark
            }
        }
        val mark = ContainedMark(
            notation = notation,
            module = module,
            version = (dependency as? ExternalModuleDependency)?.version,
            declaredBy = declaredBy,
        )
        declarations.add(Declaration(mark, dependency))
        return mark
    }

    /**
     * Resolves a declaration that no class path contains.
     *
     * Non-transitive on purpose: the entry is one jar, and whatever that jar depends on is either declared by this
     * project as well or is the bundled jar's own business - a bundled mod carries its own declarations with it.
     *
     * @return the files the declaration names, or an empty list when it does not resolve
     */
    fun resolve(mark: ContainedMark): List<File> {
        val dependency = declarations.firstOrNull { it.mark == mark }?.dependency ?: return emptyList()
        val detached = project.configurations.detachedConfiguration(dependency).apply { isTransitive = false }
        return try {
            detached.resolve().filter { !it.isDirectory }.sortedBy { it.name }
        } catch (e: Exception) {
            project.logger.info(
                "[Unimined/Contained ${project.path}] {} (declared by {}) did not resolve: {}",
                mark.notation, mark.declaredBy, e.message,
            )
            emptyList()
        }
    }

    /**
     * The module a declaration names, or `null` when it has no coordinates.
     *
     * @throws InvalidUserDataException when the module cannot be identified, since bundling it would produce an entry
     *         nobody can arbitrate the version of, or one that silently duplicates another file name
     */
    private fun moduleOf(
        dependency: Dependency,
        notation: String,
    ): ContainedModule? {
        if (dependency !is ExternalModuleDependency) {
            // A files(...) dependency: no coordinates, no version. Its identity is the file, which the bundled-jar
            // computation derives once the files are known.
            return null
        }
        val group = dependency.group
            ?: throw InvalidUserDataException("contained($notation) has no group, so it has no identity to bundle under")
        if (dependency.artifacts.size > 1) {
            throw InvalidUserDataException(
                "contained($notation) declares ${dependency.artifacts.size} artifacts, so it does not name one entry: " +
                    "declare each artifact separately with an explicit classifier.",
            )
        }
        val classifier = dependency.artifacts.singleOrNull()?.classifier
        return ContainedModule(group, dependency.name, classifier)
    }
}

/**
 * Marks a dependency as bundled inside the produced jar, returning it so it can be declared in the same expression.
 *
 * ```groovy
 * dependencies {
 *     modImplementation contained("com.cleanroommc:scalar:1.0.0")          // bundled as a mod
 *     implementation   contained("org.openjdk.nashorn:nashorn-core:15.4")   // bundled as a library
 * }
 * ```
 *
 * The kind follows from the configuration the dependency is declared in, so there is nothing else to say: a mod
 * dependency is bundled as a mod, anything else as a library.
 *
 * A dependency that is bundled but declared in no configuration is bundled too, and warned about: it will exist in the
 * produced jar and not in a development run, which is only what you want for something reached by reflection.
 */
fun Project.contained(notation: Any): Dependency {
    val dependency = if (notation is Dependency) notation else dependencies.create(notation)
    unimined.containedMarks.declare(dependency, notation.toString(), "dependencies {}")
    return dependency
}

/**
 * The Groovy spelling of [Project.contained], without the import.
 *
 * `dependencies {}` configures the `DependencyHandler` with `DELEGATE_FIRST`, so a method it does not have is looked up
 * on the script - and a callable in the project's extra properties is what makes `implementation contained("g:a:v")`
 * resolve. Kotlin scripts need the extension function instead, since they have no dynamic dispatch to fall back on.
 */
@ApiStatus.Internal
class ContainedDependencyClosure(private val project: Project) : Closure<Dependency>(project) {

    @Suppress("unused") // called by Groovy's dynamic dispatch
    fun doCall(notation: Any): Dependency = project.contained(notation)
}
