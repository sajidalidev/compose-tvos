package dev.sajidali.compose.tvos

import org.gradle.api.logging.Logger
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/**
 * Represents a discovered tvOS variant from module metadata.
 */
data class TvosVariant(
    val variantName: String,
    val nativeTarget: String,
    val artifactId: String
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
    private const val CACHE_DIR_NAME = "compose-tvos-redirect-cache"

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

        val tvosSections = json.split(Regex("""(?=\{\s*"name"\s*:)""")).filter { it.contains("tvos_") }

        for (section in tvosSections) {
            val nameMatch = namePattern.find(section) ?: continue
            val variantName = nameMatch.groupValues[1]

            if (!variantName.contains("Api", ignoreCase = true)) continue

            val targetMatch = nativeTargetPattern.find(section) ?: continue
            val nativeTarget = targetMatch.groupValues[1]

            val availableAtMatch = availableAtModulePattern.find(section)
            val moduleArtifactId = if (availableAtMatch != null) {
                availableAtMatch.groupValues[1]
            } else if (baseArtifactId != null) {
                val targetSuffix = nativeTarget.replace("_", "")
                "$baseArtifactId-$targetSuffix"
            } else {
                nativeTarget.replace("_", "")
            }

            variants.add(TvosVariant(variantName, nativeTarget, moduleArtifactId))
        }

        return variants.distinctBy { it.nativeTarget }
    }

    private fun readFromFileCache(cacheDir: File, cacheKey: String): List<TvosVariant>? {
        val cacheFile = getCacheFile(cacheDir, cacheKey)
        if (!cacheFile.exists()) return null

        return try {
            cacheFile.readLines().mapNotNull { line ->
                val parts = line.split("|")
                if (parts.size == 3) TvosVariant(parts[0], parts[1], parts[2]) else null
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun writeToFileCache(cacheDir: File, cacheKey: String, variants: List<TvosVariant>) {
        val cacheFile = getCacheFile(cacheDir, cacheKey)
        try {
            cacheFile.parentFile?.mkdirs()
            cacheFile.writeText(variants.joinToString("\n") { "${it.variantName}|${it.nativeTarget}|${it.artifactId}" })
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
