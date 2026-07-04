package dev.sajidali.compose.tvos

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
@Serializable
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
    // v3: cache entries are now serialized as JSON (List<TvosVariant> via kotlinx-serialization)
    // instead of the bespoke pipe/comma-delimited format. Old v1/v2 caches are ignored
    // rather than migrated — a single cold network fetch repopulates v3.
    private const val CACHE_DIR_NAME = "compose-tvos-redirect-cache-v3"

    private val json = Json { ignoreUnknownKeys = true }

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

    internal fun parseModuleMetadata(moduleJson: String, logger: Logger? = null, baseArtifactId: String? = null): List<TvosVariant> {
        return try {
            val root = Json.parseToJsonElement(moduleJson).jsonObject
            val variantsArray = root["variants"]?.jsonArray ?: return emptyList()

            val variants = mutableListOf<TvosVariant>()
            for (variantElement in variantsArray) {
                // Each variant is parsed independently: a single malformed entry (e.g. a
                // non-object array element, or an attribute value that isn't a JSON primitive)
                // must only drop that variant, not the whole document — mirroring the
                // per-section degradation the old regex-based parser had.
                try {
                    val variantObject = variantElement.jsonObject
                    val attributesObject = variantObject["attributes"]?.jsonObject ?: continue

                    val nativeTarget = attributesObject["org.jetbrains.kotlin.native.target"]
                        ?.jsonPrimitive?.content
                        ?: continue
                    if (!nativeTarget.startsWith("tvos_")) continue

                    val variantName = variantObject["name"]?.jsonPrimitive?.content ?: continue

                    // Capture the full attribute map for this variant. The earlier "Api-only"
                    // filter and distinctBy(nativeTarget) produced a single injected variant with
                    // four attributes, which covered kotlin-api requests but silently failed for
                    // consumers whose source-set resolution needed kotlin-metadata / kotlin-runtime
                    // / sources variants (e.g. `appleMain` compileMainKotlinMetadata).
                    // We now mirror every tvOS variant from the fork with its exact attribute set.
                    // Non-string JSON primitives (booleans/numbers) are stringified to their
                    // literal form via JsonPrimitive.content, matching Gradle's own attribute
                    // model where attribute values are always compared/stored as strings here.
                    val attributes = attributesObject.mapValues { (_, value) -> value.jsonPrimitive.content }

                    val moduleArtifactId = variantObject["available-at"]?.jsonObject
                        ?.get("module")?.jsonPrimitive?.content
                    val targetSuffix = nativeTarget.replace("_", "")
                    val artifactId = moduleArtifactId
                        ?: baseArtifactId?.let { "$it-$targetSuffix" }
                        ?: targetSuffix

                    variants.add(TvosVariant(variantName, nativeTarget, artifactId, attributes))
                } catch (e: Exception) {
                    logger?.info("[ComposeTvosRedirect] Skipping malformed variant entry: ${e.message}")
                }
            }

            // Keep every (name, target) pair — api / sources / metadata / runtime all needed.
            variants.distinctBy { it.variantName }
        } catch (e: Exception) {
            logger?.info("[ComposeTvosRedirect] Failed to parse module metadata: ${e.message}")
            emptyList()
        }
    }

    // Cache format (v3): the on-disk cache is a JSON array of TvosVariant, written via
    // kotlinx-serialization. A malformed/corrupt file (or one from an older bespoke
    // pipe/comma-delimited v1/v2 format) is simply ignored and the variants are
    // re-fetched from the network.
    private fun readFromFileCache(cacheDir: File, cacheKey: String): List<TvosVariant>? {
        val cacheFile = getCacheFile(cacheDir, cacheKey)
        if (!cacheFile.exists()) return null

        return try {
            json.decodeFromString<List<TvosVariant>>(cacheFile.readText()).takeIf { it.isNotEmpty() }
        } catch (e: Exception) {
            null
        }
    }

    private fun writeToFileCache(cacheDir: File, cacheKey: String, variants: List<TvosVariant>) {
        val cacheFile = getCacheFile(cacheDir, cacheKey)
        try {
            cacheFile.parentFile?.mkdirs()
            cacheFile.writeText(json.encodeToString<List<TvosVariant>>(variants))
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
        "org.jetbrains.androidx.navigation",
        "org.jetbrains.androidx.lifecycle",
        "org.jetbrains.androidx.savedstate",
        "org.jetbrains.androidx.navigationevent",
        "org.jetbrains.androidx.navigation3",
        "org.jetbrains.compose.annotation-internal",
        "org.jetbrains.compose.collection-internal",
        "org.jetbrains.compose.material3.adaptive"
    )
}

/**
 * Specific artifact mappings for non-standard module names.
 *
 * Every group referenced by an entry below (`androidx.navigation`, `androidx.lifecycle`,
 * `androidx.navigation3`, `androidx.savedstate`) is now also covered by a [ComposeModules.ALL]
 * group rule, so these per-artifact mappings are functionally redundant with the group rules
 * for redirect purposes. They are kept as-is (not removed): the settings plugin's group-rule
 * registration skips any artifact key already present in the artifact-mapping table
 * (`artifactMappings.containsKey(artifactKey)` guard in `ComposeTvosRedirectSettingsPlugin`),
 * so there's no double-handling, and artifact mappings drive the cheaper `withModule` rule
 * instead of the broader `all` rule the group mappings use.
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
 *
 * Resolution falls through to the original requested version when no entry matches —
 * that's the same-version convention. Override exceptions live in the remote manifest
 * loaded by [VersionManifestLoader], not in code.
 */
object ComposeVersions {

    /**
     * Resolves the redirect target version for `groupId:artifactId@originalVersion`.
     *
     * Mapping keys are matched in six priority tiers, most specific tier first:
     *  1. Exact artifact match (`group:artifact:versionPattern`)
     *  2. Exact group match (`group:versionPattern`)
     *  3. Group pattern match (`group.*:versionPattern`)
     *  4. Global pattern (`*:versionPattern`)
     *  5. [targetVersionOverride], if set
     *  6. [originalVersion] itself (same-version convention)
     *
     * Within a single tier, more than one entry can match the same (groupId, originalVersion)
     * pair — e.g. two overlapping wildcard version patterns, or (in tier 3) two group patterns
     * that both cover groupId. Ties are broken deterministically by VERSION PATTERN specificity,
     * independent of the `versionMappings` map's iteration/insertion order:
     *  1. An exact version pattern (`versionPattern == originalVersion`, no wildcard) always
     *     beats a wildcard version pattern.
     *  2. Among wildcard version patterns (`X.*`), the longest literal prefix wins
     *     (e.g. `1.10.2.*` beats `1.10.*` beats `1.*`).
     *  3. `*` (match-anything) is the least specific version pattern.
     *  4. Any remaining tie (identical version-pattern specificity, e.g. two overlapping group
     *     patterns paired with the same version pattern text in tier 3) is broken by ascending
     *     lexicographic order of the raw map key. This ordering is arbitrary but stable, so
     *     resolution never depends on `Map` implementation or insertion order.
     */
    fun resolveVersion(
        groupId: String,
        artifactId: String,
        originalVersion: String,
        versionMappings: Map<String, String>,
        targetVersionOverride: String?
    ): String {
        // Priority 1: Exact artifact match (group:artifact:version)
        bestMatch(versionMappings) { parts ->
            parts.size == 3 &&
                parts[0] == groupId && parts[1] == artifactId &&
                matchesVersionPattern(originalVersion, parts[2])
        }?.let { return it }

        // Priority 2: Exact group match (group:version)
        bestMatch(versionMappings) { parts ->
            parts.size == 2 &&
                !parts[0].endsWith(".*") && parts[0] != "*" && parts[0] == groupId &&
                matchesVersionPattern(originalVersion, parts[1])
        }?.let { return it }

        // Priority 3: Group pattern match (group.*:version)
        bestMatch(versionMappings) { parts ->
            parts.size == 2 &&
                parts[0].endsWith(".*") && matchesGroupPattern(groupId, parts[0]) &&
                matchesVersionPattern(originalVersion, parts[1])
        }?.let { return it }

        // Priority 4: Global pattern (*:version)
        bestMatch(versionMappings) { parts ->
            parts.size == 2 && parts[0] == "*" && matchesVersionPattern(originalVersion, parts[1])
        }?.let { return it }

        // Priority 5: Override
        targetVersionOverride?.let { return it }

        // Priority 6: Original
        return originalVersion
    }

    /**
     * Scans [versionMappings] for every entry whose split key satisfies [matches] and returns
     * the target version of the most specific one, per the version-pattern specificity/lexicographic
     * tie-break rules documented on [resolveVersion]. Returns `null` when no entry matches.
     */
    private inline fun bestMatch(
        versionMappings: Map<String, String>,
        matches: (parts: List<String>) -> Boolean
    ): String? {
        var bestKey: String? = null
        var bestVersion: String? = null
        var bestSpecificity = Int.MIN_VALUE
        for ((key, targetVersion) in versionMappings) {
            val parts = key.split(":")
            if (!matches(parts)) continue
            val specificity = versionPatternSpecificity(parts.last())
            val currentBestKey = bestKey
            val isBetter = currentBestKey == null ||
                specificity > bestSpecificity ||
                (specificity == bestSpecificity && key < currentBestKey)
            if (isBetter) {
                bestKey = key
                bestVersion = targetVersion
                bestSpecificity = specificity
            }
        }
        return bestVersion
    }

    /**
     * Higher is more specific. Only meaningful for patterns that already satisfied
     * [matchesVersionPattern]: an exact literal (no wildcard) is maximally specific, `X.*`
     * wildcards rank by prefix length, and `*` is least specific.
     */
    private fun versionPatternSpecificity(pattern: String): Int = when {
        pattern == "*" -> -1
        pattern.endsWith(".*") -> pattern.length - 2
        else -> Int.MAX_VALUE
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

    // Verified against the fork's coordinateRoot rewrite (JetBrainsPublication.mavenGroupFor,
    // see .superpowers/sdd/task-8a-report.md): the fork parameterizes only the ROOT segment
    // ("org.jetbrains" -> coordinateRoot="dev.sajidali") of both JETBRAINS_COMPOSE_GROUP_PREFIX
    // ("$coordinateRoot.compose.") and JETBRAINS_FORK_GROUP_PREFIX ("$coordinateRoot.androidx.");
    // everything after the root is untouched. A prefix-only string replace therefore reproduces
    // the fork's rewrite exactly for every group in ComposeModules.ALL, including the new
    // D14 groups, e.g. "org.jetbrains.androidx.lifecycle" -> "dev.sajidali.androidx.lifecycle"
    // and "org.jetbrains.compose.annotation-internal" -> "dev.sajidali.compose.annotation-internal".
    fun mapGroupId(sourceGroupId: String): String =
        sourceGroupId.replace(SOURCE_PREFIX, TARGET_PREFIX)
}
