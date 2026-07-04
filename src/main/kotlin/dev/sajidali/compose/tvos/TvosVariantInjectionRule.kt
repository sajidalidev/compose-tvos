package dev.sajidali.compose.tvos

import org.gradle.api.artifacts.CacheableRule
import org.gradle.api.artifacts.ComponentMetadataContext
import org.gradle.api.artifacts.ComponentMetadataDetails
import org.gradle.api.artifacts.ComponentMetadataRule
import org.gradle.api.attributes.Attribute
import org.gradle.api.logging.Logger
import org.gradle.api.logging.Logging
import java.io.File
import java.io.Serializable
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject

/**
 * A single successful injection recorded for the end-of-build diagnostics summary (D1,
 * Task 5): the umbrella module that was redirected, the target coordinate its variants
 * point at, and how many variants were mirrored in.
 */
data class InjectionRecord(
    val sourceModule: String,
    val targetCoordinate: String,
    val variantCount: Int
)

/**
 * A redirect-eligible module (artifact/group mapping matched, umbrella-shaped) for which
 * [TvosVariantDiscovery] returned zero variants -- the "silent no-op" case the D1 defect
 * describes. Recorded so the settings plugin's end-of-build summary can warn (or, in
 * `strictMode`, fail) instead of degrading invisibly.
 */
data class EmptyDiscoveryRecord(
    val sourceModule: String,
    val targetCoordinate: String,
    val repositoryUrls: List<String>,
    val offline: Boolean
)

/** Immutable point-in-time read of [TvosVariantInjectionRule]'s diagnostics bookkeeping. */
data class DiagnosticsSnapshot(
    val injections: List<InjectionRecord>,
    val emptyDiscoveries: List<EmptyDiscoveryRecord>
)

/**
 * Single class-based component metadata rule that injects tvOS variants into JetBrains
 * Compose Multiplatform (and configured additional) umbrella modules.
 *
 * Replaces the previous per-mapping closure registrations (one `withModule` rule per
 * artifact-mapping entry, one `all` rule per group-mapping entry) with a single
 * `components.all(TvosVariantInjectionRule::class.java) { it.params(params) }`
 * registration. [Params] is serialized/isolated by Gradle rather than captured by a
 * closure over settings-scope state, which is what makes the rule cacheable
 * ([CacheableRule]) and configuration-cache-friendly.
 *
 * Behavior is intentionally identical to the previous closure-based implementation:
 * artifact-mapping match first, then group-mapping match, an umbrella-module check,
 * version resolution, variant discovery, and `addVariant` with mirrored attributes and a
 * dependency onto the resolved target coordinate.
 */
@CacheableRule
abstract class TvosVariantInjectionRule @Inject constructor(
    private val params: Params
) : ComponentMetadataRule {

    /**
     * Serializable parameter bundle for [TvosVariantInjectionRule]. Every field must stay
     * serializable/isolatable by Gradle: only primitives, strings, and simple
     * collections/pairs of those.
     */
    data class Params(
        val groupMappings: Map<String, String>,
        val artifactMappings: Map<String, Pair<String, String>>,
        val versionMappings: Map<String, String>,
        val targetVersionOverride: String?,
        val repositoryUrls: List<String>,
        val cacheDirPath: String,
        val offline: Boolean,
        val verbose: Boolean
    ) : Serializable

    override fun execute(context: ComponentMetadataContext) {
        val metadata = context.details
        val id = metadata.id
        val group = id.group
        val moduleName = id.module.name
        val artifactKey = "$group:$moduleName"

        val artifactMatch = params.artifactMappings[artifactKey]
        val (targetGroup, targetArtifact) = if (artifactMatch != null) {
            artifactMatch
        } else {
            val mappedGroup = params.groupMappings[group] ?: return
            mappedGroup to moduleName
        }

        if (!TvosArtifactMapping.isUmbrellaModule(moduleName)) return

        val version = ComposeVersions.resolveVersion(
            group, moduleName, id.version, params.versionMappings, params.targetVersionOverride
        )
        val variants = getVariants(targetGroup, targetArtifact, version)
        val sourceModule = "$group:$moduleName:${id.version}"
        val targetCoordinate = "$targetGroup:$targetArtifact:$version"
        if (variants.isEmpty()) {
            emptyDiscoveries.putIfAbsent(
                sourceModule,
                EmptyDiscoveryRecord(sourceModule, targetCoordinate, params.repositoryUrls, params.offline)
            )
            return
        }
        injections.putIfAbsent(sourceModule, InjectionRecord(sourceModule, targetCoordinate, variants.size))

        if (params.verbose) {
            logger.lifecycle("[ComposeTvosRedirect] Injecting ${variants.size} tvOS variants into: $group:$moduleName:${id.version}")
        }
        injectTvosVariants(metadata, targetGroup, version, variants)
    }

    private fun getVariants(targetGroup: String, targetArtifact: String, version: String): List<TvosVariant> {
        val key = "$targetGroup:$targetArtifact:$version"
        return variantCache.getOrPut(key) {
            TvosVariantDiscovery.discoverVariants(
                params.repositoryUrls, targetGroup, targetArtifact, version, File(params.cacheDirPath),
                if (params.verbose) logger else null,
                offline = params.offline
            )
        }
    }

    private fun injectTvosVariants(
        metadata: ComponentMetadataDetails,
        targetGroup: String,
        version: String,
        variants: List<TvosVariant>
    ) {
        variants.forEach { variant ->
            metadata.addVariant("${variant.variantName}-injected") { variantMetadata ->
                variantMetadata.attributes { attrs ->
                    val attributesToApply = if (variant.attributes.isNotEmpty()) {
                        variant.attributes
                    } else {
                        // Fallback for cache entries predating attribute capture. This
                        // covers the common kotlin-api request path but does NOT cover
                        // metadata / sources / runtime variant lookups — if a consumer
                        // reports unresolved references in a shared source set (e.g.
                        // `appleMain`) after upgrading, deleting the cache directory
                        // (<gradleUserHome>/compose-tvos-redirect-cache-v3/, `~/.gradle` by
                        // default) forces re-discovery
                        // with the full attribute set.
                        mapOf(
                            "org.gradle.category" to "library",
                            "org.gradle.usage" to "kotlin-api",
                            "org.gradle.jvm.environment" to "non-jvm",
                            "org.jetbrains.kotlin.platform.type" to "native",
                            "org.jetbrains.kotlin.native.target" to variant.nativeTarget
                        )
                    }
                    attributesToApply.forEach { (key, value) ->
                        attrs.attribute(Attribute.of(key, String::class.java), value)
                    }
                }
                variantMetadata.withDependencies { deps ->
                    deps.add("$targetGroup:${variant.artifactId}:$version")
                }
            }
        }
    }

    companion object {
        private val logger: Logger = Logging.getLogger(TvosVariantInjectionRule::class.java)

        // Rule instances are created per-component by Gradle, so this cache must live at
        // the companion-object level (shared across all instances) rather than as an
        // instance field to actually memoize across components, mirroring the previous
        // settings-plugin-local `variantCache`.
        private val variantCache = ConcurrentHashMap<String, List<TvosVariant>>()

        // -- diagnostics bookkeeping (Task 5 / defect D1) ---------------------------------
        // Rules execute concurrently across components (and, for a multi-project build,
        // potentially across projects), hence ConcurrentHashMap; keyed by "group:module:
        // version" so repeat resolution of the same coordinate (warm caches, multiple
        // resolvable configurations hitting the same umbrella) dedups to one entry rather
        // than accumulating duplicates.
        //
        // Lifetime: like `variantCache` above, these maps live for the lifetime of the
        // classloader (a warm Gradle daemon spans many builds), so they must be reset once
        // per build -- see `resetDiagnostics()`, called from the settings plugin at
        // `settingsEvaluated` -- or a summary would accumulate stale entries from earlier
        // builds in the same daemon. On a configuration-cache-REUSED build, `settingsEvaluated`
        // itself does not re-run (the whole configuration phase is skipped and the task graph
        // is replayed from the cache), so neither the reset nor the summary/warn/strictMode
        // reporting runs for that build -- an accepted config-cache-reuse limitation, see
        // TvosDiagnosticsService's KDoc.
        private val injections = ConcurrentHashMap<String, InjectionRecord>()
        private val emptyDiscoveries = ConcurrentHashMap<String, EmptyDiscoveryRecord>()

        /** Clears diagnostics bookkeeping; called once per build before the rule can fire. */
        fun resetDiagnostics() {
            injections.clear()
            emptyDiscoveries.clear()
        }

        /** Point-in-time read of the diagnostics bookkeeping accumulated so far this build. */
        fun diagnosticsSnapshot(): DiagnosticsSnapshot =
            DiagnosticsSnapshot(injections.values.toList(), emptyDiscoveries.values.toList())
    }
}
