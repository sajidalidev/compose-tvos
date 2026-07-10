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
 * @param availableAtGroup Task 11 (dangling-metadata fix): the `available-at` object's own
 * `group` field, when this variant is a redirect rather than an inline variant the umbrella
 * carries itself. `null` for an inline variant (no `available-at` at all) -- deliberately
 * NOT assumed to equal the umbrella's own group, even though that happens to hold for the
 * confirmed real-world dangling case; captured verbatim from whatever the metadata says.
 * `null` also for cache entries predating this field.
 * @param availableAtVersion The `available-at` object's own `version` field, same caveats as
 * [availableAtGroup]. Together with [availableAtGroup] and [artifactId] (which already carries
 * the `available-at` `module` field when present), this is enough to probe whether the
 * advertised redirect target genuinely exists -- see [TvosVariantDiscovery.verifyOfficialSupport].
 */
@Serializable
data class TvosVariant(
    val variantName: String,
    val nativeTarget: String,
    val artifactId: String,
    val attributes: Map<String, String> = emptyMap(),
    val availableAtGroup: String? = null,
    val availableAtVersion: String? = null
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

    // Task 11 (dangling-metadata fix): memoizes targetModuleExists's per-coordinate answer,
    // exactly parallel to variantCache above -- a distinct map since an existence probe answers
    // a different question (does the .module file exist at all?) than a parsed variant list.
    private val existenceCache = ConcurrentHashMap<String, Boolean>()
    // v3: cache entries are now serialized as JSON (List<TvosVariant> via kotlinx-serialization)
    // instead of the bespoke pipe/comma-delimited format. Old v1/v2 caches are ignored
    // rather than migrated — a single cold network fetch repopulates v3.
    // v4 (review of task-20-report.md, Finding 1): TvosVariant gained the nullable
    // availableAtGroup/availableAtVersion fields (Task 11, dangling-metadata fix). A v3 cache
    // file written by a pre-Task-11 (1.1.0) plugin decodes those two fields as null (thanks to
    // Json { ignoreUnknownKeys = true }, deserialization doesn't fail) — which
    // verifyOfficialSupport then reads as "no available-at redirect", i.e. an inline variant
    // "supported by definition", silently skipping the existence check the Task 11 fix exists
    // to run. Bumping the cache directory ensures a warm 1.1.0 cache is never read by this
    // version — old v3 (and earlier) caches are ignored rather than migrated, exactly like the
    // v1/v2 -> v3 bump above; a single cold network fetch repopulates v4.
    private const val CACHE_DIR_NAME = "compose-tvos-redirect-cache-v4"

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Fetching here is done via plain `HttpURLConnection`/`File` reads rather than Gradle's
     * injected `RepositoryResourceAccessor`. `RepositoryResourceAccessor` binds resource
     * fetching to the single repository that declared the component-metadata rule requesting
     * it, which fits same-repo lookups (e.g. fetching a sibling POM from the repo that's
     * already resolving the component). Here the rule fires on `org.jetbrains` components but
     * must probe a configurable list of *independent* repository URLs for the unrelated
     * `dev.sajidali` TARGET coordinate's module metadata — a cross-repository, multi-URL
     * search `RepositoryResourceAccessor` isn't built for — so a direct, offline-aware,
     * redirect-bounded `HttpURLConnection`/file path is used instead.
     *
     * @param offline When true, only the in-memory/disk caches are consulted — no network
     * connection is ever opened. A SNAPSHOT version (which never populates the disk cache,
     * see below) with no in-memory entry therefore resolves to an empty list while offline,
     * mirroring `--offline`'s "do not touch the network" contract; the miss is logged at
     * info level rather than failing the build (same degrade-gracefully convention as the
     * network-failure path below).
     */
    fun discoverVariants(
        repositoryUrls: List<String>,
        groupId: String,
        artifactId: String,
        version: String,
        cacheDir: File?,
        logger: Logger? = null,
        offline: Boolean = false
    ): List<TvosVariant> {
        val cacheKey = "$groupId:$artifactId:$version"

        variantCache[cacheKey]?.let { return it }

        if (!version.contains("SNAPSHOT") && cacheDir != null) {
            readFromFileCache(cacheDir, cacheKey)?.let {
                variantCache[cacheKey] = it
                return it
            }
        }

        if (offline) {
            logger?.info("[ComposeTvosRedirect] Offline: no cached tvOS variants for $cacheKey; skipping network fetch")
            return emptyList()
        }

        // Task 10b follow-up (Fix 2, review of task-10b-report.md): a repository returning and
        // parsing a .module successfully -- even with ZERO tvOS variants inside it, the majority
        // case for an official JetBrains artifact -- is a genuine, cacheable answer, distinct
        // from a fetch FAILURE (network error, 404 on every repository, or a parse exception on
        // malformed content), which must remain retryable on a later call/build rather than
        // silently freezing a transient failure into "permanently empty". `fetchAndParseMetadata`
        // therefore returns a `FetchOutcome` rather than a bare (ambiguous) `List<TvosVariant>`.
        // The loop stops at the first SUCCESSFUL parse (whether empty or not): once one
        // repository has genuinely answered for this coordinate, trying further mirror
        // repositories for the identical group:artifact:version cannot change that answer, and
        // this also means the common "official artifact has no tvOS variants" case now costs a
        // single repository round-trip instead of one per configured repository.
        for (repoUrl in repositoryUrls) {
            val outcome = fetchAndParseMetadata(repoUrl, groupId, artifactId, version, logger)
            if (outcome is FetchOutcome.Success) {
                variantCache[cacheKey] = outcome.variants
                if (!version.contains("SNAPSHOT") && cacheDir != null) {
                    writeToFileCache(cacheDir, cacheKey, outcome.variants)
                }
                return outcome.variants
            }
        }

        return emptyList()
    }

    /**
     * The outcome of attempting to fetch and parse a single repository's `.module` file for one
     * `group:artifact:version` coordinate. [Success] (with a possibly-empty [Success.variants])
     * is cacheable -- a repository was actually reached and its content actually parsed.
     * [Failure] covers everything that should be retried later instead of memoized: the
     * coordinate not existing on this repository (404 / missing file), a network/IO error, or a
     * parse exception on content that was fetched but could not be understood as valid module
     * metadata.
     */
    private sealed class FetchOutcome {
        data class Success(val variants: List<TvosVariant>) : FetchOutcome()
        data object Failure : FetchOutcome()
    }

    private fun fetchAndParseMetadata(
        repositoryUrl: String,
        groupId: String,
        artifactId: String,
        version: String,
        logger: Logger?
    ): FetchOutcome {
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
            FetchOutcome.Failure
        }
    }

    private fun fetchFromFileUrl(baseUrl: String, modulePath: String, artifactId: String, logger: Logger?): FetchOutcome {
        val basePath = baseUrl.removePrefix("file:").trimStart('/')
        val absolutePath = if (baseUrl.startsWith("file:///") || baseUrl.startsWith("file:/") && !baseUrl.startsWith("file://")) {
            "/$basePath"
        } else {
            basePath
        }
        val moduleFile = File(absolutePath, modulePath)

        return if (moduleFile.exists() && moduleFile.isFile) {
            parseModuleMetadataOutcome(moduleFile.readText(), logger, artifactId)
        } else {
            FetchOutcome.Failure
        }
    }

    // Redirects are followed manually (rather than relying on
    // HttpURLConnection.instanceFollowRedirects, which is already true by default) because
    // the JDK deliberately refuses to auto-follow a redirect that changes protocol
    // (http <-> https) — a real scenario for repositories migrating to https. JVM/system
    // http(s).proxyHost/proxyPort properties are honored automatically by
    // HttpURLConnection; no extra wiring is required for that.
    private const val MAX_REDIRECTS = 3

    private fun fetchFromHttpUrl(moduleUrl: String, artifactId: String, logger: Logger?): FetchOutcome {
        var currentUrl = moduleUrl
        var hops = 0
        while (true) {
            val connection = URL(currentUrl).openConnection() as HttpURLConnection
            connection.connectTimeout = 5_000
            connection.readTimeout = 5_000
            connection.requestMethod = "GET"
            connection.instanceFollowRedirects = false

            val responseCode = connection.responseCode
            if (responseCode == HttpURLConnection.HTTP_OK) {
                return parseModuleMetadataOutcome(connection.inputStream.bufferedReader().readText(), logger, artifactId)
            }

            val isRedirect = responseCode == HttpURLConnection.HTTP_MOVED_PERM ||
                responseCode == HttpURLConnection.HTTP_MOVED_TEMP ||
                responseCode == HttpURLConnection.HTTP_SEE_OTHER ||
                responseCode == 307 || responseCode == 308
            if (!isRedirect || hops >= MAX_REDIRECTS) {
                // Any other non-200 terminal status (404, 5xx, or a redirect with no more
                // hops left) is treated as a miss, same as before.
                return FetchOutcome.Failure
            }

            val location = connection.getHeaderField("Location") ?: return FetchOutcome.Failure
            currentUrl = URL(URL(currentUrl), location).toString()
            hops++
        }
    }

    /**
     * Public, always-succeeds entry point (never throws, degrades to `emptyList()`) — used
     * directly by unit tests and by [parseModuleMetadataOutcome] below, which additionally
     * distinguishes a genuine parse exception (returned as [FetchOutcome.Failure], so the
     * caller in [discoverVariants] does not cache it) from a clean parse that simply found no
     * (or zero tvOS) variants (returned as [FetchOutcome.Success], which IS cached — see Fix 2,
     * task-10b-report.md).
     */
    internal fun parseModuleMetadata(moduleJson: String, logger: Logger? = null, baseArtifactId: String? = null): List<TvosVariant> =
        try {
            parseVariantsOrThrow(moduleJson, logger, baseArtifactId)
        } catch (e: Exception) {
            logger?.info("[ComposeTvosRedirect] Failed to parse module metadata: ${e.message}")
            emptyList()
        }

    private fun parseModuleMetadataOutcome(moduleJson: String, logger: Logger?, baseArtifactId: String?): FetchOutcome =
        try {
            FetchOutcome.Success(parseVariantsOrThrow(moduleJson, logger, baseArtifactId))
        } catch (e: Exception) {
            logger?.info("[ComposeTvosRedirect] Failed to parse module metadata: ${e.message}")
            FetchOutcome.Failure
        }

    /**
     * Does the actual JSON-to-[TvosVariant] parsing, letting a genuine top-level parse failure
     * (malformed JSON, or a `variants`/`attributes` shape that isn't the expected JSON type)
     * propagate as an exception to the caller -- [parseModuleMetadata] and
     * [parseModuleMetadataOutcome] each decide separately how to degrade that. A single
     * malformed VARIANT entry inside an otherwise well-formed document is still handled here,
     * per-entry, exactly as before: it is skipped rather than failing the whole document.
     */
    private fun parseVariantsOrThrow(moduleJson: String, logger: Logger?, baseArtifactId: String?): List<TvosVariant> {
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

                val availableAtObject = variantObject["available-at"]?.jsonObject
                val moduleArtifactId = availableAtObject?.get("module")?.jsonPrimitive?.content
                // Task 11: capture the available-at redirect's own group/version verbatim --
                // both null when this is an inline variant (no available-at at all).
                val availableAtGroup = availableAtObject?.get("group")?.jsonPrimitive?.content
                val availableAtVersion = availableAtObject?.get("version")?.jsonPrimitive?.content
                val targetSuffix = nativeTarget.replace("_", "")
                val artifactId = moduleArtifactId
                    ?: baseArtifactId?.let { "$it-$targetSuffix" }
                    ?: targetSuffix

                variants.add(
                    TvosVariant(variantName, nativeTarget, artifactId, attributes, availableAtGroup, availableAtVersion)
                )
            } catch (e: Exception) {
                logger?.info("[ComposeTvosRedirect] Skipping malformed variant entry: ${e.message}")
            }
        }

        // Keep every (name, target) pair — api / sources / metadata / runtime all needed.
        return variants.distinctBy { it.variantName }
    }

    // Cache format (v3): the on-disk cache is a JSON array of TvosVariant, written via
    // kotlinx-serialization. A malformed/corrupt file (or one from an older bespoke
    // pipe/comma-delimited v1/v2 format) is simply ignored and the variants are
    // re-fetched from the network.
    private fun readFromFileCache(cacheDir: File, cacheKey: String): List<TvosVariant>? {
        val cacheFile = getCacheFile(cacheDir, cacheKey)
        if (!cacheFile.exists()) return null

        return try {
            // Fix 2 (task-10b-report.md review): an existing file that decodes to an EMPTY list
            // is a valid, successfully-cached "officially has no tvOS variants" result, not a
            // miss -- only a decode exception (corrupt/unreadable file) falls through to `null`
            // (a miss, triggering re-fetch). The previous `takeIf { it.isNotEmpty() }` guard
            // treated a legitimately-cached empty result the same as a corrupt file, defeating
            // the whole point of caching the (majority) empty-official-artifact case.
            json.decodeFromString<List<TvosVariant>>(cacheFile.readText())
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
        existenceCache.clear()
    }

    /**
     * Extracts the set of `org.jetbrains.kotlin.native.target` values present in [variants] --
     * used by [dev.sajidali.compose.tvos.TvosVariantInjectionRule] (Task 10b, Phase 4 blocker
     * fix) to determine which tvOS native targets an OFFICIAL module already ships upstream
     * (e.g. `org.jetbrains.compose.runtime` already publishes a real `tvosArm64` variant), so
     * injection can skip re-adding a colliding variant for those targets rather than producing
     * an unresolvable Gradle variant-ambiguity error. [variants] is expected to come from the
     * same [discoverVariants]/[parseModuleMetadata] path used everywhere else -- already
     * filtered to tvOS-only variants -- so no further filtering is needed here.
     */
    fun alreadySupportedNativeTargets(variants: List<TvosVariant>): Set<String> =
        variants.map { it.nativeTarget }.toSet()

    /**
     * Task 10b follow-up (§5 second-defect, review of task-10b-report.md): true if [variants]
     * (assumed already tvOS-filtered, discovered against the OFFICIAL `group:baseModule:version`
     * coordinate) already ships a native variant for the exact tvOS platform-module suffix
     * [tvosSuffix] names (e.g. `-tvosarm64`, as returned by [TvosTargets.extractTvosSuffix]).
     * Comparison mirrors [parseVariantsOrThrow]'s own `nativeTarget.replace("_", "")` module-name
     * derivation (`tvos_arm64` -> `tvosarm64`), case-insensitively, so it stays symmetric with
     * how a real platform-module suffix is derived from a native target elsewhere in this file.
     *
     * Used by [dev.sajidali.compose.tvos.ComposeTvosRedirectPlugin]'s project-level dependency
     * substitution to skip substituting a tvOS-suffixed coordinate to the fork when the official
     * umbrella already provides that exact native target itself -- the same official-first
     * pre-check [TvosVariantInjectionRule] performs before injecting (Task 10b), applied to the
     * separate `dependencySubstitution.all` mechanism that has its own, independent blind spot.
     */
    fun isNativeTargetOfficiallySupported(variants: List<TvosVariant>, tvosSuffix: String): Boolean =
        isNativeTargetInSet(alreadySupportedNativeTargets(variants), tvosSuffix)

    /**
     * Task 11: the same suffix-vs-native-target comparison [isNativeTargetOfficiallySupported]
     * does, but against an already-computed set of native targets (e.g. the existence-verified
     * set [verifyOfficialSupport] returns) rather than re-deriving an unverified one from a raw
     * variant list. Factored out so callers that already ran the existence check don't have to
     * re-derive [alreadySupportedNativeTargets] (which would silently drop the verification).
     */
    fun isNativeTargetInSet(nativeTargets: Set<String>, tvosSuffix: String): Boolean {
        val normalizedSuffix = tvosSuffix.removePrefix("-").lowercase()
        return nativeTargets.any { it.replace("_", "").lowercase() == normalizedSuffix }
    }

    /**
     * Task 11 (dangling-metadata fix) result: which native targets among an OFFICIAL umbrella's
     * discovered tvOS [TvosVariant]s are genuinely officially supported once their advertised
     * `available-at` redirect target has been existence-verified, and which advertise a target
     * module confirmed NOT to exist anywhere ("dangling metadata" -- see [targetModuleExists]).
     */
    data class OfficialSupportResult(
        val supportedNativeTargets: Set<String>,
        val danglingNativeTargets: Set<String>
    )

    /**
     * Task 11 (dangling-metadata fix): like [alreadySupportedNativeTargets], but does not
     * simply trust that an OFFICIAL umbrella's advertised `available-at` variant is real. For
     * every variant that redirects to a target module (as opposed to an INLINE variant the
     * umbrella carries itself, i.e. no `available-at` at all), the target module's existence is
     * verified via [targetModuleExists] before its native target counts as officially supported.
     *
     * This exists precisely because upstream JetBrains metadata can genuinely dangle: the
     * confirmed real-world case (task-11-report.md) is
     * `org.jetbrains.androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0-beta01`'s umbrella
     * `.module` on Maven Central, which advertises `tvosArm64`/`tvosSimulatorArm64` variants
     * whose `available-at` target (e.g. `lifecycle-viewmodel-compose-tvosarm64`) 404s on every
     * configured repository. Trusting that advertisement at face value made the pre-1.1.1
     * official-first mechanism skip injection/substitution for a target the official artifact
     * does not actually ship, so consumer resolution failed outright on the phantom coordinate
     * instead of falling through to the fork, which genuinely publishes it.
     *
     * An inline variant (no `available-at`) counts as supported by definition -- there is no
     * separate target module whose existence could even be probed; the umbrella itself already
     * carries the real artifact.
     *
     * Fallback semantics -- deliberately ASYMMETRIC with [TvosVariantInjectionRule]'s own
     * empty-fork-discovery fallback (which injects EVERYTHING discovered when the OFFICIAL
     * fetch itself failed/came back empty):
     *  - An existence probe FAILURE (network error, distinct from a confirmed 404/absent --
     *    see [targetModuleExists]'s `Unknown` outcome) treats the target as SUPPORTED. This
     *    preserves pre-1.1.1 behavior: a transient network outage must not flip an otherwise
     *    healthy official resolution over to the fork.
     *  - Only a confirmed 404/absent on EVERY configured repository flips a target to NOT
     *    supported, so injection/substitution proceeds for it.
     * The two fallbacks point in opposite directions because they guard against opposite failure
     * modes. The empty-fork-discovery fallback exists to avoid silently leaving a genuine tvOS
     * gap unfilled -- a wrongly-SKIPPED injection breaks tvOS builds outright there, since
     * nothing else will ever provide that variant. Here, the opposite mistake is the one to
     * avoid: a wrongly-added injection/substitution would override an official resolution Gradle
     * would otherwise already have handled correctly, merely because a repository hiccuped
     * during the one extra probe this fix adds -- comparatively low-stakes, since the official
     * artifact was never actually confirmed broken.
     */
    fun verifyOfficialSupport(
        variants: List<TvosVariant>,
        repositoryUrls: List<String>,
        cacheDir: File?,
        logger: Logger? = null,
        offline: Boolean = false
    ): OfficialSupportResult {
        val supported = mutableSetOf<String>()
        val dangling = mutableSetOf<String>()
        for (variant in variants) {
            val availableAtGroup = variant.availableAtGroup
            val availableAtVersion = variant.availableAtVersion
            if (availableAtGroup == null || availableAtVersion == null) {
                supported.add(variant.nativeTarget)
                continue
            }
            val exists = targetModuleExists(
                repositoryUrls, availableAtGroup, variant.artifactId, availableAtVersion, cacheDir, logger, offline
            )
            if (exists) {
                supported.add(variant.nativeTarget)
            } else {
                dangling.add(variant.nativeTarget)
                logger?.lifecycle(
                    "[ComposeTvosRedirect] Official metadata advertises " +
                        "$availableAtGroup:${variant.artifactId}:$availableAtVersion but artifact missing -- " +
                        "treating as unsupported, fork will serve"
                )
            }
        }
        return OfficialSupportResult(supported, dangling)
    }

    /**
     * Task 11: does `groupId:artifactId:version`'s `.module` file -- the OFFICIAL umbrella's
     * `available-at` TARGET, not the umbrella itself -- actually exist on any configured
     * repository? Shares the exact repository-URL list, memory+disk caching (SNAPSHOT versions
     * excluded from the disk cache, same as [discoverVariants]), and offline semantics as the
     * rest of this object: an existence result is exactly as cacheable as a parsed variant list,
     * since it is likewise a genuine, repository-confirmed answer -- not a guess.
     *
     * Returns `true` (treated as existing/supported) when: the module was confirmed to exist on
     * some configured repository, OR every probe attempt was inconclusive (a network error or
     * other non-404 failure -- see [ProbeOutcome.Unknown] -- rather than a genuine miss), OR
     * offline with nothing already cached (mirrors [discoverVariants]'s own offline degrade).
     * Returns `false` ONLY when every configured repository gave a confirmed 404/absent answer.
     * See [verifyOfficialSupport]'s KDoc for why this fallback direction is deliberately the
     * opposite of the empty-fork-discovery fallback elsewhere in this plugin.
     */
    fun targetModuleExists(
        repositoryUrls: List<String>,
        groupId: String,
        artifactId: String,
        version: String,
        cacheDir: File?,
        logger: Logger? = null,
        offline: Boolean = false
    ): Boolean {
        val cacheKey = "$groupId:$artifactId:$version"

        existenceCache[cacheKey]?.let { return it }

        if (!version.contains("SNAPSHOT") && cacheDir != null) {
            readExistenceFromFileCache(cacheDir, cacheKey)?.let {
                existenceCache[cacheKey] = it
                return it
            }
        }

        if (offline) {
            logger?.info("[ComposeTvosRedirect] Offline: no cached existence probe for $cacheKey; assuming supported")
            return true
        }

        // Mirrors discoverVariants's own repository-list looping, but existence needs a
        // different aggregation: a confirmed 404 on one repository does not end the search
        // (another mirror might still have it), whereas ANY confirmed existence ends it
        // immediately, and any inconclusive (network-error) probe must not be allowed to freeze
        // a false "confirmed absent" verdict into the cache.
        var sawUnknown = false
        for (repoUrl in repositoryUrls) {
            when (probeModuleExists(repoUrl, groupId, artifactId, version, logger)) {
                ProbeOutcome.Exists -> {
                    cacheExistence(cacheDir, cacheKey, true, version)
                    return true
                }
                ProbeOutcome.ConfirmedAbsent -> { /* keep checking remaining repositories */ }
                ProbeOutcome.Unknown -> sawUnknown = true
            }
        }

        if (sawUnknown) {
            // At least one repository could not give a definitive answer: never cache this
            // (mirrors FetchOutcome.Failure never being cached) so a transient outage remains
            // retryable, and fall back to "supported" per this function's documented contract.
            return true
        }

        // Every configured repository gave a confirmed 404/absent answer.
        cacheExistence(cacheDir, cacheKey, false, version)
        return false
    }

    private fun cacheExistence(cacheDir: File?, cacheKey: String, exists: Boolean, version: String) {
        existenceCache[cacheKey] = exists
        if (!version.contains("SNAPSHOT") && cacheDir != null) {
            writeExistenceToFileCache(cacheDir, cacheKey, exists)
        }
    }

    /**
     * Per-repository existence-probe outcome. Distinct from [FetchOutcome]: existence only
     * needs the HTTP/file-presence answer, not a successful variant parse, and specifically
     * distinguishes a confirmed 404/missing-file ([ConfirmedAbsent]) from every other failure
     * mode ([Unknown]: network error, timeout, exhausted redirect, or any other non-200/404
     * status) -- a distinction [FetchOutcome.Failure] deliberately does not make, since nothing
     * upstream of this fix ever needed it.
     */
    private sealed class ProbeOutcome {
        data object Exists : ProbeOutcome()
        data object ConfirmedAbsent : ProbeOutcome()
        data object Unknown : ProbeOutcome()
    }

    private fun probeModuleExists(
        repositoryUrl: String,
        groupId: String,
        artifactId: String,
        version: String,
        logger: Logger?
    ): ProbeOutcome {
        val baseUrl = repositoryUrl.trimEnd('/')
        val groupPath = groupId.replace('.', '/')
        val modulePath = "$groupPath/$artifactId/$version/$artifactId-$version.module"

        return try {
            if (baseUrl.startsWith("file:")) {
                probeFileUrl(baseUrl, modulePath)
            } else {
                probeHttpUrl("$baseUrl/$modulePath")
            }
        } catch (e: Exception) {
            logger?.info("[ComposeTvosRedirect] Existence probe failed for $groupId:$artifactId:$version: ${e.message}")
            ProbeOutcome.Unknown
        }
    }

    private fun probeFileUrl(baseUrl: String, modulePath: String): ProbeOutcome {
        val basePath = baseUrl.removePrefix("file:").trimStart('/')
        val absolutePath = if (baseUrl.startsWith("file:///") || baseUrl.startsWith("file:/") && !baseUrl.startsWith("file://")) {
            "/$basePath"
        } else {
            basePath
        }
        val moduleFile = File(absolutePath, modulePath)
        return if (moduleFile.exists() && moduleFile.isFile) ProbeOutcome.Exists else ProbeOutcome.ConfirmedAbsent
    }

    private fun probeHttpUrl(moduleUrl: String): ProbeOutcome {
        var currentUrl = moduleUrl
        var hops = 0
        while (true) {
            val connection = URL(currentUrl).openConnection() as HttpURLConnection
            connection.connectTimeout = 5_000
            connection.readTimeout = 5_000
            connection.requestMethod = "GET"
            connection.instanceFollowRedirects = false

            val responseCode = connection.responseCode
            if (responseCode == HttpURLConnection.HTTP_OK) {
                connection.inputStream.close()
                return ProbeOutcome.Exists
            }
            if (responseCode == HttpURLConnection.HTTP_NOT_FOUND) {
                return ProbeOutcome.ConfirmedAbsent
            }

            val isRedirect = responseCode == HttpURLConnection.HTTP_MOVED_PERM ||
                responseCode == HttpURLConnection.HTTP_MOVED_TEMP ||
                responseCode == HttpURLConnection.HTTP_SEE_OTHER ||
                responseCode == 307 || responseCode == 308
            if (!isRedirect || hops >= MAX_REDIRECTS) {
                // Any other non-200/404 terminal status (5xx, or a redirect with no more hops
                // left) is ambiguous, not a confirmed absence.
                return ProbeOutcome.Unknown
            }

            val location = connection.getHeaderField("Location") ?: return ProbeOutcome.Unknown
            currentUrl = URL(URL(currentUrl), location).toString()
            hops++
        }
    }

    private fun readExistenceFromFileCache(cacheDir: File, cacheKey: String): Boolean? {
        val cacheFile = getExistenceCacheFile(cacheDir, cacheKey)
        if (!cacheFile.exists()) return null

        return try {
            when (cacheFile.readText().trim()) {
                "true" -> true
                "false" -> false
                else -> null
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun writeExistenceToFileCache(cacheDir: File, cacheKey: String, exists: Boolean) {
        val cacheFile = getExistenceCacheFile(cacheDir, cacheKey)
        try {
            cacheFile.parentFile?.mkdirs()
            cacheFile.writeText(exists.toString())
        } catch (_: Exception) { }
    }

    private fun getExistenceCacheFile(cacheDir: File, cacheKey: String): File {
        val safeKey = cacheKey.replace(":", "_").replace(".", "_")
        return File(cacheDir, "$CACHE_DIR_NAME/$safeKey.exists")
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

    /**
     * Default group redirects whose target is NOT derived via [TvosArtifactMapping.mapGroupId]'s
     * `org.jetbrains` -> `dev.sajidali` prefix replace, because the source group isn't rooted at
     * `org.jetbrains` in the first place. `androidx.tv` is a Google coordinate, so its fork
     * target is a prepend (`androidx.tv` -> `dev.sajidali.androidx.tv`), not a replace -- these
     * entries are merged into the final group-mapping table as explicit, literal targets.
     *
     * `io.insert-koin` (Koin) and `io.coil-kt.coil3` (Coil) are third-party coordinates with the
     * same shape: neither is rooted at `org.jetbrains`, so each gets its own explicit, literal
     * fork target (`io.insert-koin` -> `dev.sajidali.koin`, `io.coil-kt.coil3` -> `dev.sajidali.coil3`).
     */
    val EXPLICIT_GROUP_TARGETS = mapOf(
        "androidx.tv" to "dev.sajidali.androidx.tv",
        "io.insert-koin" to "dev.sajidali.koin",
        "io.coil-kt.coil3" to "dev.sajidali.coil3"
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
