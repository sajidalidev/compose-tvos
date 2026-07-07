package dev.sajidali.compose.tvos

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.gradle.api.logging.Logger
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Wire format for the remote version override manifest.
 *
 * The mapping keys use the same scope syntax as user-supplied [ComposeVersions.normalizeMappings]
 * input — `groupId:versionPattern`, `groupId.*:versionPattern`, `groupId:artifactId:versionPattern`,
 * or `*:versionPattern`. Versions accept `1.10.*` wildcards.
 *
 * `gradlePlugin` (schema 2+) optionally pins the version of the substituted
 * `dev.sajidali.compose:compose-gradle-plugin` artifact used by the `org.jetbrains.compose`
 * plugin-marker interception (see [ComposeTvosRedirectSettingsPlugin]). `ignoreUnknownKeys`
 * on the shared [Json] instance means schema-1 manifests (missing this field entirely) still
 * parse fine -- `gradlePlugin` simply stays null -- so both schema 1 and schema 2 documents
 * are accepted on read; `schema` itself is otherwise informational and never validated here.
 */
@Serializable
internal data class VersionManifest(
    val schema: Int = 1,
    val mappings: Map<String, String> = emptyMap(),
    val gradlePlugin: String? = null
)

/**
 * Fetches and caches the remote version override manifest.
 *
 * The manifest is the moving piece of version mapping that used to live as a hardcoded
 * map in source. Convention (same-version-as-requested) covers most cases; the manifest
 * only needs to list exceptions where the fork is published at a different version than
 * upstream. Failures degrade silently — convention keeps the build working offline.
 */
internal object VersionManifestLoader {

    // Review of task-20-report.md, Finding 1: kept in lockstep with
    // TvosVariantDiscovery.CACHE_DIR_NAME's v3 -> v4 bump so both caches live under the same
    // parent directory version, even though this loader's own manifest schema wasn't the
    // trigger for that bump.
    private const val CACHE_DIR_NAME = "compose-tvos-redirect-cache-v4/version-manifest"
    private const val FETCH_TIMEOUT_MS = 5_000
    private const val CACHE_TTL_MS = 24L * 60 * 60 * 1000

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    fun load(
        manifestUrl: String?,
        cacheDir: File,
        refreshDependencies: Boolean,
        logger: Logger? = null,
        offline: Boolean = false
    ): Map<String, String> =
        loadManifest(manifestUrl, cacheDir, refreshDependencies, logger, offline)?.mappings ?: emptyMap()

    /**
     * Same fetch/cache/degrade behavior as [load], but returns the full parsed
     * [VersionManifest] (mappings + [VersionManifest.gradlePlugin]) instead of just the
     * mappings, so a single fetch/cache round-trip per build can serve both consumers
     * (the settings plugin reads both from one call).
     */
    fun loadManifest(
        manifestUrl: String?,
        cacheDir: File,
        refreshDependencies: Boolean,
        logger: Logger? = null,
        offline: Boolean = false
    ): VersionManifest? {
        if (manifestUrl.isNullOrBlank()) return null

        val cacheFile = cacheFileFor(cacheDir, manifestUrl)
        val cacheFresh = !refreshDependencies &&
            cacheFile.exists() &&
            (System.currentTimeMillis() - cacheFile.lastModified()) < CACHE_TTL_MS

        if (cacheFresh) {
            parseManifest(cacheFile.readText(), logger)?.let { return it }
        }

        if (offline) {
            // Never fetch while offline: fresh-or-stale cache is used if present at all,
            // otherwise degrade to the same-version convention (empty mappings).
            if (cacheFile.exists()) {
                parseManifest(cacheFile.readText(), logger)?.let {
                    logger?.info("[ComposeTvosRedirect] Offline: using cached version manifest (network skipped)")
                    return it
                }
            }
            logger?.info(
                "[ComposeTvosRedirect] Offline: no cached version manifest available; " +
                    "falling back to same-version convention"
            )
            return null
        }

        val fetched = fetch(manifestUrl, logger)
        if (fetched != null) {
            writeCache(cacheFile, fetched)
            parseManifest(fetched, logger)?.let { return it }
        }

        // Network failed — try whatever we have on disk, even if stale.
        if (cacheFile.exists()) {
            parseManifest(cacheFile.readText(), logger)?.let {
                logger?.info("[ComposeTvosRedirect] Using cached version manifest (network unavailable)")
                return it
            }
        }

        logger?.warn(
            "[ComposeTvosRedirect] Could not load version manifest from $manifestUrl; " +
                "falling back to same-version convention. Override with composeTvos.versionMappings " +
                "if a fork uses a different version than upstream."
        )
        return null
    }

    private fun fetch(manifestUrl: String, logger: Logger?): String? = try {
        val connection = URL(manifestUrl).openConnection() as HttpURLConnection
        connection.connectTimeout = FETCH_TIMEOUT_MS
        connection.readTimeout = FETCH_TIMEOUT_MS
        connection.requestMethod = "GET"
        if (connection.responseCode == 200) {
            connection.inputStream.bufferedReader().readText()
        } else {
            logger?.info("[ComposeTvosRedirect] Manifest fetch returned HTTP ${connection.responseCode}")
            null
        }
    } catch (e: Exception) {
        logger?.info("[ComposeTvosRedirect] Manifest fetch failed: ${e.message}")
        null
    }

    internal fun parse(text: String, logger: Logger?): Map<String, String>? =
        parseManifest(text, logger)?.mappings

    internal fun parseManifest(text: String, logger: Logger?): VersionManifest? = try {
        json.decodeFromString(VersionManifest.serializer(), text)
    } catch (e: Exception) {
        logger?.warn("[ComposeTvosRedirect] Manifest parse failed: ${e.message}")
        null
    }

    private fun writeCache(cacheFile: File, content: String) {
        try {
            cacheFile.parentFile?.mkdirs()
            cacheFile.writeText(content)
        } catch (_: Exception) {
            // best-effort cache; ignore
        }
    }

    private fun cacheFileFor(cacheDir: File, url: String): File {
        val digest = MessageDigest.getInstance("SHA-256").digest(url.toByteArray())
        val hex = digest.joinToString("") { "%02x".format(it) }
        return File(cacheDir, "$CACHE_DIR_NAME/$hex.json")
    }
}
