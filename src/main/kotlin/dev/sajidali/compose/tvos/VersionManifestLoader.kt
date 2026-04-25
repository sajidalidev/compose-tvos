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
 */
@Serializable
internal data class VersionManifest(
    val schema: Int = 1,
    val mappings: Map<String, String> = emptyMap()
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

    private const val CACHE_DIR_NAME = "compose-tvos-redirect-cache-v2/version-manifest"
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
        logger: Logger? = null
    ): Map<String, String> {
        if (manifestUrl.isNullOrBlank()) return emptyMap()

        val cacheFile = cacheFileFor(cacheDir, manifestUrl)
        val cacheFresh = !refreshDependencies &&
            cacheFile.exists() &&
            (System.currentTimeMillis() - cacheFile.lastModified()) < CACHE_TTL_MS

        if (cacheFresh) {
            parse(cacheFile.readText(), logger)?.let { return it }
        }

        val fetched = fetch(manifestUrl, logger)
        if (fetched != null) {
            writeCache(cacheFile, fetched)
            parse(fetched, logger)?.let { return it }
        }

        // Network failed — try whatever we have on disk, even if stale.
        if (cacheFile.exists()) {
            parse(cacheFile.readText(), logger)?.let {
                logger?.info("[ComposeTvosRedirect] Using cached version manifest (network unavailable)")
                return it
            }
        }

        logger?.warn(
            "[ComposeTvosRedirect] Could not load version manifest from $manifestUrl; " +
                "falling back to same-version convention. Override with composeTvos.versionMappings " +
                "if a fork uses a different version than upstream."
        )
        return emptyMap()
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

    internal fun parse(text: String, logger: Logger?): Map<String, String>? = try {
        json.decodeFromString(VersionManifest.serializer(), text).mappings
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
