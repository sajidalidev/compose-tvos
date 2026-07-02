package dev.sajidali.compose.tvos.fixtures

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.File

/**
 * A Kotlin/Native target as it appears in Gradle Module Metadata.
 *
 * @param konanName value of `org.jetbrains.kotlin.native.target` (e.g. `tvos_arm64`)
 * @param variantPrefix variant-name prefix used by KMP publishing (e.g. `tvosArm64`)
 * @param moduleSuffix suffix of the published platform module (e.g. `tvosarm64`)
 */
data class FixtureNativeTarget(
    val konanName: String,
    val variantPrefix: String,
    val moduleSuffix: String
) {
    companion object {
        val TVOS_ARM64 = FixtureNativeTarget("tvos_arm64", "tvosArm64", "tvosarm64")
        val TVOS_SIMULATOR_ARM64 =
            FixtureNativeTarget("tvos_simulator_arm64", "tvosSimulatorArm64", "tvossimulatorarm64")
        val IOS_ARM64 = FixtureNativeTarget("ios_arm64", "iosArm64", "iosarm64")
    }
}

/**
 * Generates a `file://` Maven repository containing realistic Gradle Module Metadata.
 *
 * The generated `.module` files mirror the structure of the real artifacts published by
 * the user's Compose fork (reference: `~/.m2/repository/org/jetbrains/compose/ui/ui/1.11.0/
 * ui-1.11.0.module`): an umbrella module whose per-target variants point at platform
 * modules via `available-at`, and platform modules carrying the actual (dummy) files.
 *
 * Only Gradle Module Metadata is generated — no `.pom` files. Consumers must declare the
 * repository with `metadataSources { gradleMetadata() }`.
 *
 * Artifacts referenced by the metadata are created as empty files; tests assert
 * dependency-graph resolution, never compilation.
 */
class FixtureRepo(val rootDir: File) {

    /** `file:` URL of the repository root, suitable for a Maven repository declaration. */
    val url: String get() = rootDir.toURI().toString()

    private val json = Json { prettyPrint = true }

    /**
     * Publishes an umbrella module (e.g. `org.jetbrains.compose.ui:ui`) plus one platform
     * module per requested target (e.g. `ui-tvosarm64`).
     *
     * The umbrella contains a common `metadataApiElements` variant and, per target, the
     * Api / Metadata / Sources variant triple with `available-at` redirects — exactly the
     * shape the plugin's `TvosVariantDiscovery` parses and mirrors into injected variants.
     */
    fun publishUmbrellaWithPlatforms(
        group: String,
        artifact: String,
        version: String,
        targets: List<FixtureNativeTarget>
    ) {
        val umbrellaVariants = buildJsonArray {
            add(metadataApiElementsVariant("$artifact-$version.jar"))
            targets.forEach { target ->
                val platformModule = "$artifact-${target.moduleSuffix}"
                add(availableAtVariant("${target.variantPrefix}ApiElements-published", apiAttributes(target), group, platformModule, version))
                add(availableAtVariant("${target.variantPrefix}MetadataElements-published", metadataAttributes(target), group, platformModule, version))
                add(availableAtVariant("${target.variantPrefix}SourcesElements-published", sourcesAttributes(target), group, platformModule, version))
            }
        }
        writeModule(group, artifact, version, umbrellaVariants)
        writeArtifactFile(group, artifact, version, "$artifact-$version.jar")

        targets.forEach { target -> publishPlatformModule(group, artifact, version, target) }
    }

    /** Publishes a platform module (e.g. `ui-tvosarm64`) with dummy klib/jar artifacts. */
    private fun publishPlatformModule(
        group: String,
        baseArtifact: String,
        version: String,
        target: FixtureNativeTarget
    ) {
        val artifact = "$baseArtifact-${target.moduleSuffix}"
        val klib = "$artifact-$version.klib"
        val metadataJar = "$artifact-$version-metadata.jar"
        val sourcesJar = "$artifact-$version-sources.jar"

        val variants = buildJsonArray {
            add(fileVariant("${target.variantPrefix}ApiElements-published", apiAttributes(target), klib))
            add(fileVariant("${target.variantPrefix}MetadataElements-published", metadataAttributes(target), metadataJar))
            add(fileVariant("${target.variantPrefix}SourcesElements-published", sourcesAttributes(target), sourcesJar))
        }
        writeModule(group, artifact, version, variants)
        listOf(klib, metadataJar, sourcesJar).forEach { writeArtifactFile(group, artifact, version, it) }
    }

    // -- variant builders --------------------------------------------------------------

    private fun metadataApiElementsVariant(fileName: String): JsonObject = buildJsonObject {
        put("name", "metadataApiElements")
        putJsonObject("attributes") {
            put("org.gradle.category", "library")
            put("org.gradle.jvm.environment", "non-jvm")
            put("org.gradle.usage", "kotlin-metadata")
            put("org.jetbrains.kotlin.platform.type", "common")
        }
        put("files", fileEntry(fileName))
    }

    private fun availableAtVariant(
        name: String,
        attributes: Map<String, String>,
        group: String,
        module: String,
        version: String
    ): JsonObject = buildJsonObject {
        put("name", name)
        putJsonObject("attributes") { attributes.forEach { (k, v) -> put(k, v) } }
        putJsonObject("available-at") {
            put("url", "../../$module/$version/$module-$version.module")
            put("group", group)
            put("module", module)
            put("version", version)
        }
    }

    private fun fileVariant(name: String, attributes: Map<String, String>, fileName: String): JsonObject =
        buildJsonObject {
            put("name", name)
            putJsonObject("attributes") { attributes.forEach { (k, v) -> put(k, v) } }
            put("files", fileEntry(fileName))
        }

    private fun fileEntry(fileName: String) = buildJsonArray {
        add(buildJsonObject {
            put("name", fileName)
            put("url", fileName)
            put("size", 0)
        })
    }

    // -- attribute sets (mirroring the reference fork metadata) ------------------------

    private fun apiAttributes(target: FixtureNativeTarget) = mapOf(
        "org.gradle.category" to "library",
        "org.gradle.jvm.environment" to "non-jvm",
        "org.gradle.usage" to "kotlin-api",
        "org.jetbrains.kotlin.native.target" to target.konanName,
        "org.jetbrains.kotlin.platform.type" to "native"
    )

    private fun metadataAttributes(target: FixtureNativeTarget) = mapOf(
        "org.gradle.category" to "library",
        "org.gradle.jvm.environment" to "non-jvm",
        "org.gradle.usage" to "kotlin-metadata",
        "org.jetbrains.kotlin.native.target" to target.konanName,
        "org.jetbrains.kotlin.platform.type" to "native"
    )

    private fun sourcesAttributes(target: FixtureNativeTarget) = mapOf(
        "org.gradle.category" to "documentation",
        "org.gradle.dependency.bundling" to "external",
        "org.gradle.docstype" to "sources",
        "org.gradle.jvm.environment" to "non-jvm",
        "org.gradle.usage" to "kotlin-runtime",
        "org.jetbrains.kotlin.native.target" to target.konanName,
        "org.jetbrains.kotlin.platform.type" to "native"
    )

    // -- file output -------------------------------------------------------------------

    private fun writeModule(
        group: String,
        artifact: String,
        version: String,
        variants: JsonArray
    ) {
        val content = buildJsonObject {
            put("formatVersion", "1.1")
            putJsonObject("component") {
                put("group", group)
                put("module", artifact)
                put("version", version)
                putJsonObject("attributes") { put("org.gradle.status", "release") }
            }
            putJsonObject("createdBy") {
                putJsonObject("gradle") { put("version", "8.13") }
            }
            put("variants", variants)
        }
        moduleDir(group, artifact, version)
            .resolve("$artifact-$version.module")
            .writeText(json.encodeToString(JsonObject.serializer(), content))
    }

    private fun writeArtifactFile(group: String, artifact: String, version: String, fileName: String) {
        moduleDir(group, artifact, version).resolve(fileName).writeBytes(ByteArray(0))
    }

    private fun moduleDir(group: String, artifact: String, version: String): File =
        File(rootDir, "${group.replace('.', '/')}/$artifact/$version").apply { mkdirs() }
}
