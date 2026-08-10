package xyz.wagyourtail.unimined.util

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.writeText

/**
 * Helpers for publishing synthetic components into a local (file-based) Maven repository tree.
 *
 * Used by the Minecraft provider (dev jar + generated sources) and the mapping providers
 * (official client/server mappings), so that projects resolve these artifacts from a regular
 * Maven repository instead of an ivy file-mapping repository. Keeping the project free of
 * ivy repositories lets IDEA use its modern auxiliary-artifact resolver, where sources are
 * discovered through the sourcesElements variant of the Gradle Module Metadata (or the
 * maven `-sources` classifier convention for POM-only components).
 */
object LocalMaven {
    /** `<base>/<group path>/<module>/<version>` */
    fun coordinatesDirectory(
        base: Path,
        group: String,
        module: String,
        version: String,
    ): Path =
        base
            .resolve(group.replace('.', File.separatorChar))
            .resolve(module)
            .resolve(version)
            .createDirectories()

    fun pomFile(
        dir: Path,
        module: String,
        version: String,
    ): Path = dir.resolve("$module-$version.pom")

    fun moduleMetadataFile(
        dir: Path,
        module: String,
        version: String,
    ): Path = dir.resolve("$module-$version.module")

    /**
     * Writes the synthetic Maven POM for a component. When [publishModuleMetadata] is true the
     * POM carries the `published-with-gradle-metadata` marker, so Gradle redirects to the .module
     * sitting next to it (variant-aware resolution, including IDEA's modern artifact view that
     * re-selects the sources variant); when false the marker is omitted and any stale .module is
     * removed, so the component resolves POM-only.
     *
     * The POM-only mode is required whenever an ivy repository is present in the project: IDEA's
     * Legacy auxiliary artifact resolver queries sources with its own ModuleComponentIdentifier
     * class, and a component resolved through .module metadata echoes that class back as the
     * component id, whose hashCode differs from the Gradle-native identifier, so the
     * AuxiliaryConfigurationArtifacts map lookup misses and sources are silently dropped.
     */
    fun writePom(
        dir: Path,
        group: String,
        module: String,
        version: String,
        publishModuleMetadata: Boolean,
    ) {
        dir.createDirectories()
        val pom =
            buildString {
                append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
                append("<project xmlns=\"http://maven.apache.org/POM/4.0.0\" ")
                append("xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" ")
                append("xsi:schemaLocation=\"http://maven.apache.org/POM/4.0.0 ")
                append("https://maven.apache.org/xsd/maven-4.0.0.xsd\">\n")
                if (publishModuleMetadata) {
                    append("  <!-- do_not_remove: published-with-gradle-metadata -->\n")
                }
                append("  <modelVersion>4.0.0</modelVersion>\n")
                append("  <groupId>${xmlEscape(group)}</groupId>\n")
                append("  <artifactId>${xmlEscape(module)}</artifactId>\n")
                append("  <version>${xmlEscape(version)}</version>\n")
                append("  <packaging>jar</packaging>\n")
                append("</project>\n")
            }
        pomFile(dir, module, version).writeText(pom)
        if (!publishModuleMetadata) {
            moduleMetadataFile(dir, module, version).deleteIfExists()
        }
    }

    /**
     * Writes (or refreshes) the synthetic Gradle Module Metadata for a component, declaring the
     * apiElements/runtimeElements binary variants plus a sourcesElements documentation variant
     * when [sourcesFileName] is present. The sources variant is what IDEA's modern resolver
     * re-selects to attach sources to the library.
     */
    fun writeModuleMetadata(
        dir: Path,
        group: String,
        module: String,
        version: String,
        binaryFileNames: List<String>,
        sourcesFileName: String?,
    ) {
        fun variant(
            name: String,
            category: String,
            usage: String,
            docType: String?,
            fileNames: List<String>,
        ): JsonObject {
            val attributes =
                JsonObject().apply {
                    addProperty("org.gradle.category", category)
                    addProperty("org.gradle.dependency.bundling", "external")
                    addProperty("org.gradle.usage", usage)
                    if (category == "library") addProperty("org.gradle.libraryelements", "jar")
                    if (docType != null) addProperty("org.gradle.docstype", docType)
                }
            return JsonObject().apply {
                addProperty("name", name)
                add("attributes", attributes)
                add(
                    "files",
                    JsonArray().apply {
                        for (fileName in fileNames) {
                            add(
                                JsonObject().apply {
                                    addProperty("name", fileName)
                                    addProperty("url", fileName)
                                },
                            )
                        }
                    },
                )
            }
        }

        val variants =
            JsonArray().apply {
                add(variant("apiElements", "library", "java-api", null, binaryFileNames))
                add(variant("runtimeElements", "library", "java-runtime", null, binaryFileNames))
                if (sourcesFileName != null) {
                    add(variant("sourcesElements", "documentation", "java-runtime", "sources", listOf(sourcesFileName)))
                }
            }
        val moduleMetadata =
            JsonObject().apply {
                addProperty("formatVersion", "1.1")
                add(
                    "component",
                    JsonObject().apply {
                        addProperty("group", group)
                        addProperty("module", module)
                        addProperty("version", version)
                        add(
                            "attributes",
                            JsonObject().apply {
                                addProperty("org.gradle.status", "release")
                            },
                        )
                    },
                )
                add(
                    "createdBy",
                    JsonObject().apply {
                        add(
                            "gradle",
                            JsonObject().apply {
                                addProperty("version", "9.6.1")
                            },
                        )
                    },
                )
                add("variants", variants)
            }
        moduleMetadataFile(dir, module, version).writeText(
            GsonBuilder().setPrettyPrinting().create().toJson(moduleMetadata),
        )
    }

    /**
     * Adds (or refreshes) the sourcesElements documentation variant on an already-published
     * .module file, pointing at [sourcesFileName]. Used by the genSources task once the
     * generated sources jar has been copied into the repository tree.
     */
    fun addSourcesVariant(
        dir: Path,
        module: String,
        version: String,
        sourcesFileName: String,
    ) {
        val file = moduleMetadataFile(dir, module, version)
        if (!file.exists()) return
        val root = JsonParser.parseString(file.toFile().readText()).asJsonObject
        val variants = root.getAsJsonArray("variants")
        if (variants.any { it.asJsonObject.get("name")?.asString == "sourcesElements" }) return
        val attributes =
            JsonObject().apply {
                addProperty("org.gradle.category", "documentation")
                addProperty("org.gradle.dependency.bundling", "external")
                addProperty("org.gradle.usage", "java-runtime")
                addProperty("org.gradle.docstype", "sources")
            }
        variants.add(
            JsonObject().apply {
                addProperty("name", "sourcesElements")
                add("attributes", attributes)
                add(
                    "files",
                    JsonArray().apply {
                        add(
                            JsonObject().apply {
                                addProperty("name", sourcesFileName)
                                addProperty("url", sourcesFileName)
                            },
                        )
                    },
                )
            },
        )
        file.writeText(GsonBuilder().setPrettyPrinting().create().toJson(root))
    }

    private fun xmlEscape(value: String): String =
        value
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
}
