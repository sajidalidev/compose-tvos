package dev.sajidali.compose.tvos

import org.gradle.api.logging.Logger
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/**
 * Represents a discovered tvOS variant from module metadata.
 *
 * @param attributes Full Gradle attribute map (org.gradle.category, org.gradle.usage,
 * org.gradle.jvm.environment, org.gradle.libraryelements, org.gradle.docstype,
 * org.jetbrains.kotlin.platform.type, org.jetbrains.kotlin.native.target, etc.) parsed
 * from the source variant. Mirrored onto the injected variant so Kotlin's metadata
 * transformer can match it for all source-set compile paths (api, metadata, runtime,
 * sources) — not just the api request. Empty map means the cache pre-dates this field
 * and a degraded-but-functional default set is used at injection time.
 */
data class TvosVariant(
    val variantName: String,
    val nativeTarget: String,
    val artifactId: String,
    val attributes: Map<String, String> = emptyMap()
)

/**
 * Pattern-based tvOS artifact detection.
 */
object TvosTargets {
    private val TVOS_SUFFIX_PATTERN = Regex("-(tvos[A-Za-z0-9_]+)$", RegexOption.IGNORE_CASE)

    fun matchesTvosSuffix(moduleName: String): Boolean =
        TVOS_SUFFIX_PATTERN.containsMatchIn(moduleName)

    fun extractTvosSuffix(moduleName: String): String? {
        val match = TVOS_SUFFIX_PATTERN.find(moduleName) ?: return null
        return "-${match.groupValues[1]}"
    }
}

/**
 * Discovers tvOS variants from target module's Gradle Module Metadata.
 */
object TvosVariantDiscovery {
    private val variantCache = ConcurrentHashMap<String, List<TvosVariant>>()
    // v2: cache entries now include the full attribute map per variant. Old v1 caches
    // (3-field pipe-separated lines) are ignored rather than migrated — a single cold
    // network fetch repopulates v2.
    private const val CACHE_DIR_NAME = "compose-tvos-redirect-cache-v2"

    fun discoverVariants(
        repositoryUrls: List<String>,
        groupId: String,
        artifactId: String,
        version: String,
        cacheDir: File?,
        logger: Logger? = null
    ): List<TvosVariant> {
        val cacheKey = "$groupId:$artifactId:$version"

        variantCache[cacheKey]?.let { return it }

        if (!version.contains("SNAPSHOT") && cacheDir != null) {
            readFromFileCache(cacheDir, cacheKey)?.let {
                variantCache[cacheKey] = it
                return it
            }
        }

        var variants: List<TvosVariant> = emptyList()
        for (repoUrl in repositoryUrls) {
            variants = fetchAndParseMetadata(repoUrl, groupId, artifactId, version, logger)
            if (variants.isNotEmpty()) break
        }

        if (variants.isNotEmpty()) {
            variantCache[cacheKey] = variants
            if (!version.contains("SNAPSHOT") && cacheDir != null) {
                writeToFileCache(cacheDir, cacheKey, variants)
            }
        }

        return variants
    }

    private fun fetchAndParseMetadata(
        repositoryUrl: String,
        groupId: String,
        artifactId: String,
        version: String,
        logger: Logger?
    ): List<TvosVariant> {
        val baseUrl = repositoryUrl.trimEnd('/')
        val groupPath = groupId.replace('.', '/')
        val modulePath = "$groupPath/$artifactId/$version/$artifactId-$version.module"

        return try {
            if (baseUrl.startsWith("file:")) {
                fetchFromFileUrl(baseUrl, modulePath, artifactId, logger)
            } else {
                fetchFromHttpUrl("$baseUrl/$modulePath", artifactId, logger)
            }
        } catch (e: Exception) {
            logger?.info("[ComposeTvosRedirect] Failed to fetch metadata: ${e.message}")
            emptyList()
        }
    }

    private fun fetchFromFileUrl(baseUrl: String, modulePath: String, artifactId: String, logger: Logger?): List<TvosVariant> {
        val basePath = baseUrl.removePrefix("file:").trimStart('/')
        val absolutePath = if (baseUrl.startsWith("file:///") || baseUrl.startsWith("file:/") && !baseUrl.startsWith("file://")) {
            "/$basePath"
        } else {
            basePath
        }
        val moduleFile = File(absolutePath, modulePath)

        return if (moduleFile.exists() && moduleFile.isFile) {
            parseModuleMetadata(moduleFile.readText(), logger, artifactId)
        } else {
            emptyList()
        }
    }

    private fun fetchFromHttpUrl(moduleUrl: String, artifactId: String, logger: Logger?): List<TvosVariant> {
        val connection = URL(moduleUrl).openConnection() as HttpURLConnection
        connection.connectTimeout = 5_000
        connection.readTimeout = 5_000
        connection.requestMethod = "GET"

        return if (connection.responseCode == 200) {
            parseModuleMetadata(connection.inputStream.bufferedReader().readText(), logger, artifactId)
        } else {
            emptyList()
        }
    }

    internal fun parseModuleMetadata(json: String, logger: Logger? = null, baseArtifactId: String? = null): List<TvosVariant> {
        val variants = mutableListOf<TvosVariant>()

        val nativeTargetPattern = Regex(""""org\.jetbrains\.kotlin\.native\.target"\s*:\s*"(tvos_[^"]+)"""")
        val availableAtModulePattern = Regex(""""available-at"\s*:\s*\{[^}]*"module"\s*:\s*"([^"]+)"""", RegexOption.DOT_MATCHES_ALL)
        val namePattern = Regex(""""name"\s*:\s*"([^"]+)"""")
        val attributesBlockPattern = Regex(""""attributes"\s*:\s*\{([^}]*)\}""", RegexOption.DOT_MATCHES_ALL)
        val attributePairPattern = Regex(""""([^"]+)"\s*:\s*"([^"]+)"""")

        val tvosSections = json.split(Regex("""(?=\{\s*"name"\s*:)""")).filter { it.contains("tvos_") }

        for (section in tvosSections) {
            val nameMatch = namePattern.find(section) ?: continue
            val variantName = nameMatch.groupValues[1]

            val targetMatch = nativeTargetPattern.find(section) ?: continue
            val nativeTarget = targetMatch.groupValues[1]

            // Capture the full attribute block for this variant. The earlier "Api-only"
            // filter and distinctBy(nativeTarget) produced a single injected variant with
            // four attributes, which covered kotlin-api requests but silently failed for
            // consumers whose source-set resolution needed kotlin-metadata / kotlin-runtime
            // / sources variants (e.g. `appleMain` compileMainKotlinMetadata).
            // We now mirror every tvOS variant from the fork with its exact attribute set.
            val attributes = attributesBlockPattern.find(section)
                ?.let { blockMatch ->
                    attributePairPattern.findAll(blockMatch.groupValues[1])
                        .associate { it.groupValues[1] to it.groupValues[2] }
                }
                ?: emptyMap()

            val availableAtMatch = availableAtModulePattern.find(section)
            val moduleArtifactId = if (availableAtMatch != null) {
                availableAtMatch.groupValues[1]
            } else if (baseArtifactId != null) {
                val targetSuffix = nativeTarget.replace("_", "")
                "$baseArtifactId-$targetSuffix"
            } else {
                nativeTarget.replace("_", "")
            }

            variants.add(TvosVariant(variantName, nativeTarget, moduleArtifactId, attributes))
        }

        // Keep every (name, target) pair — api / sources / metadata / runtime all needed.
        return variants.distinctBy { it.variantName }
    }

    // Cache format (v2): two lines per variant.
    //   header: variantName|nativeTarget|artifactId
    //   attrs:  attrs:key1=value1,key2=value2,...
    // Attribute keys/values are Gradle attribute names and enum-like strings (no commas/
    // pipes/equals), so naive delimiting is safe. A malformed file is simply ignored and
    // the variants are re-fetched from the network.
    private fun readFromFileCache(cacheDir: File, cacheKey: String): List<TvosVariant>? {
        val cacheFile = getCacheFile(cacheDir, cacheKey)
        if (!cacheFile.exists()) return null

        return try {
            val lines = cacheFile.readLines()
            val result = mutableListOf<TvosVariant>()
            var i = 0
            while (i < lines.size) {
                val header = lines[i].split("|")
                if (header.size != 3) { i++; continue }
                val attrsLine = lines.getOrNull(i + 1).orEmpty()
                val attributes = if (attrsLine.startsWith("attrs:")) {
                    attrsLine.removePrefix("attrs:")
                        .split(",")
                        .mapNotNull {
                            val eq = it.indexOf('=')
                            if (eq > 0) it.substring(0, eq) to it.substring(eq + 1) else null
                        }
                        .toMap()
                } else {
                    emptyMap()
                }
                result.add(TvosVariant(header[0], header[1], header[2], attributes))
                i += 2
            }
            result.takeIf { it.isNotEmpty() }
        } catch (e: Exception) {
            null
        }
    }

    private fun writeToFileCache(cacheDir: File, cacheKey: String, variants: List<TvosVariant>) {
        val cacheFile = getCacheFile(cacheDir, cacheKey)
        try {
            cacheFile.parentFile?.mkdirs()
            val text = buildString {
                variants.forEach { v ->
                    append(v.variantName).append('|')
                        .append(v.nativeTarget).append('|')
                        .append(v.artifactId).append('\n')
                    append("attrs:")
                        .append(v.attributes.entries.joinToString(",") { "${it.key}=${it.value}" })
                        .append('\n')
                }
            }
            cacheFile.writeText(text)
        } catch (_: Exception) { }
    }

    private fun getCacheFile(cacheDir: File, cacheKey: String): File {
        val safeKey = cacheKey.replace(":", "_").replace(".", "_")
        return File(cacheDir, "$CACHE_DIR_NAME/$safeKey.cache")
    }

    fun clearCache() {
        variantCache.clear()
    }
}

/**
 * Compose Multiplatform group IDs that should be redirected for tvOS.
 */
object ComposeModules {
    val ALL = setOf(
        "org.jetbrains.compose.ui",
        "org.jetbrains.compose.foundation",
        "org.jetbrains.compose.runtime",
        "org.jetbrains.compose.material",
        "org.jetbrains.compose.material3",
        "org.jetbrains.compose.animation",
        "org.jetbrains.compose.components",
        "org.jetbrains.androidx.navigation"
    )
}

/**
 * Specific artifact mappings for non-standard module names.
 */
object ComposeArtifacts {
    private const val JETBRAINS_PREFIX = "org.jetbrains"
    private const val TARGET_PREFIX = "dev.sajidali"

    val ALL = setOf(
        "org.jetbrains.androidx.navigation:navigation-compose",
        "org.jetbrains.androidx.lifecycle:lifecycle-viewmodel-compose",
        "org.jetbrains.androidx.lifecycle:lifecycle-viewmodel-navigation3",
        "org.jetbrains.androidx.navigation3:navigation3-ui",
        "org.jetbrains.androidx.savedstate:savedstate-compose"
    )

    fun getTargetMapping(groupId: String, artifactId: String): Pair<String, String>? {
        val source = "$groupId:$artifactId"
        if (source !in ALL) return null
        val target = source.replace(JETBRAINS_PREFIX, TARGET_PREFIX)
        val parts = target.split(":")
        return if (parts.size == 2) Pair(parts[0], parts[1]) else null
    }
}

/**
 * Version pattern matching and mapping.
 */
object ComposeVersions {
    val ALL: Map<String, String> = mapOf(
        "org.jetbrains.compose.*:1.10.*" to "1.10.0",
        "org.jetbrains.compose.material3:1.10.*" to "1.10.0-alpha05",
        "org.jetbrains.androidx.lifecycle:2.9.*" to "2.10.0-alpha06",
        "org.jetbrains.androidx.navigation:2.9.*" to "2.9.1",
        "org.jetbrains.androidx.navigation3:1.0.*" to "1.0.0-alpha06",
        "org.jetbrains.androidx.savedstate:1.4.*" to "1.4.0",
    )

    fun resolveVersion(
        groupId: String,
        artifactId: String,
        originalVersion: String,
        versionMappings: Map<String, String>,
        targetVersionOverride: String?
    ): String {
        // Priority 1: Exact artifact match (group:artifact:version)
        for ((key, targetVersion) in versionMappings) {
            val parts = key.split(":")
            if (parts.size == 3) {
                val (mappingGroup, mappingArtifact, versionPattern) = parts
                if (mappingGroup == groupId && mappingArtifact == artifactId &&
                    matchesVersionPattern(originalVersion, versionPattern)) {
                    return targetVersion
                }
            }
        }

        // Priority 2: Exact group match (group:version)
        for ((key, targetVersion) in versionMappings) {
            val parts = key.split(":")
            if (parts.size == 2) {
                val (scope, versionPattern) = parts
                if (!scope.endsWith(".*") && scope != "*" && scope == groupId &&
                    matchesVersionPattern(originalVersion, versionPattern)) {
                    return targetVersion
                }
            }
        }

        // Priority 3: Group pattern match (group.*:version)
        for ((key, targetVersion) in versionMappings) {
            val parts = key.split(":")
            if (parts.size == 2) {
                val (scope, versionPattern) = parts
                if (scope.endsWith(".*") && matchesGroupPattern(groupId, scope) &&
                    matchesVersionPattern(originalVersion, versionPattern)) {
                    return targetVersion
                }
            }
        }

        // Priority 4: Global pattern (*:version)
        for ((key, targetVersion) in versionMappings) {
            val parts = key.split(":")
            if (parts.size == 2 && parts[0] == "*" &&
                matchesVersionPattern(originalVersion, parts[1])) {
                return targetVersion
            }
        }

        // Priority 5: Override
        targetVersionOverride?.let { return it }

        // Priority 6: Original
        return originalVersion
    }

    private fun matchesVersionPattern(version: String, pattern: String): Boolean {
        if (pattern == "*" || pattern == version) return true
        if (pattern.endsWith(".*")) {
            return version.startsWith("${pattern.dropLast(2)}.")
        }
        return false
    }

    private fun matchesGroupPattern(groupId: String, pattern: String): Boolean {
        if (pattern == "*" || pattern == groupId) return true
        if (pattern.endsWith(".*")) {
            return groupId.startsWith("${pattern.dropLast(2)}.")
        }
        return false
    }

    fun normalizeMappings(mappings: Map<String, String>): Map<String, String> =
        mappings.mapKeys { (key, _) -> if (!key.contains(":")) "*:$key" else key }
}

/**
 * Utility functions for tvOS artifact detection and mapping.
 */
object TvosArtifactMapping {
    private const val SOURCE_PREFIX = "org.jetbrains"
    private const val TARGET_PREFIX = "dev.sajidali"

    fun isTvosArtifact(moduleName: String): Boolean =
        TvosTargets.matchesTvosSuffix(moduleName)

    fun isUmbrellaModule(moduleName: String): Boolean {
        val platformPatterns = listOf(
            Regex("-(ios|tvos|macos|watchos|linux|mingw|android|jvm|js|wasm)[A-Za-z0-9_]*$", RegexOption.IGNORE_CASE),
            Regex("-(desktop|native)$", RegexOption.IGNORE_CASE)
        )
        return platformPatterns.none { it.containsMatchIn(moduleName) }
    }

    fun isComposeGroup(groupId: String): Boolean = groupId in ComposeModules.ALL

    fun mapGroupId(sourceGroupId: String): String =
        sourceGroupId.replace(SOURCE_PREFIX, TARGET_PREFIX)
}
