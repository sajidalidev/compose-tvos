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

/**
 * Task 10b (Phase 4 blocker fix): a discovered tvOS variant that was deliberately NOT
 * injected because the OFFICIAL module (fetched via the same discovery path, keyed by its
 * own `group:module:version` rather than the redirect target coordinate) already ships a
 * native variant for that target -- e.g. `org.jetbrains.compose.runtime` already publishes
 * `tvosArm64` upstream. Recorded for verbose diagnostics only: deliberately NOT an
 * [EmptyDiscoveryRecord] (discovery succeeded; the module is simply already covered), so it
 * never contributes to the end-of-build WARN/strictMode path.
 */
data class SkippedAlreadySupportedRecord(
    val sourceModule: String,
    val targetCoordinate: String,
    val skippedNativeTargets: List<String>
)

/** Immutable point-in-time read of [TvosVariantInjectionRule]'s diagnostics bookkeeping. */
data class DiagnosticsSnapshot(
    val injections: List<InjectionRecord>,
    val emptyDiscoveries: List<EmptyDiscoveryRecord>,
    val skippedAlreadySupported: List<SkippedAlreadySupportedRecord> = emptyList()
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

        // Task 10b (Phase 4 blocker fix): before injecting, check whether the OFFICIAL module
        // -- this component's own group:module:version, not the redirect target -- already
        // ships native tvOS variant(s) for some of the targets just discovered (e.g.
        // org.jetbrains.compose.runtime already publishes tvosArm64 upstream). Re-uses the
        // exact same discoverVariants/parseModuleMetadata path, repository list, and
        // disk/memory caching as the target lookup above, keyed by the OFFICIAL coordinate --
        // naturally distinct from the target coordinate's own cache key, since the redirect
        // target always carries a different (dev.sajidali-mapped) group. A failed/empty
        // official-metadata fetch (offline, network error, or a parse failure on well-formed
        // metadata) degrades to an empty set here, same as discoverVariants's existing
        // never-throw contract -- so the fallback is simply "inject everything discovered",
        // i.e. the previous, unconditional behavior. That fallback is deliberately chosen over
        // failing closed: injection is only reachable when the TARGET dev.sajidali metadata
        // was already fetchable, and a wrongly-SKIPPED injection breaks tvOS builds outright,
        // whereas a wrongly-ADDED injection only breaks the (rare) already-officially-supported
        // case -- exactly the case this whole check exists to avoid in the common path.
        val officialVariants = getVariants(group, moduleName, id.version)
        val alreadySupportedTargets = TvosVariantDiscovery.alreadySupportedNativeTargets(officialVariants)
        val injectable = variants.filter { it.nativeTarget !in alreadySupportedTargets }

        if (injectable.size < variants.size) {
            val skippedTargets = variants.filter { it.nativeTarget in alreadySupportedTargets }.map { it.nativeTarget }
            skippedAlreadySupported.putIfAbsent(
                sourceModule,
                SkippedAlreadySupportedRecord(sourceModule, targetCoordinate, skippedTargets)
            )
            if (params.verbose) {
                // Fix 4 (review of task-10b-report.md): skippedTargets carries one entry per
                // skipped VARIANT (api/metadata/sources all share the same nativeTarget), so the
                // raw list logs the same target repeated 2-3x (e.g. "[tvos_arm64, tvos_arm64,
                // tvos_arm64]"). The count in the message still reflects the true variant count;
                // only the displayed list of targets is deduped for readability.
                logger.lifecycle(
                    "[ComposeTvosRedirect] Skipping ${skippedTargets.size} tvOS variant(s) already " +
                        "supported by the official artifact: $group:$moduleName:${id.version} (${skippedTargets.distinct()})"
                )
            }
        }

        if (injectable.isEmpty()) return

        injections.putIfAbsent(sourceModule, InjectionRecord(sourceModule, targetCoordinate, injectable.size))

        if (params.verbose) {
            logger.lifecycle("[ComposeTvosRedirect] Injecting ${injectable.size} tvOS variants into: $group:$moduleName:${id.version}")
        }
        injectTvosVariants(metadata, targetGroup, version, injectable)
    }

    /**
     * Fetches (and memoizes) the tvOS variants for `groupId:artifactId:version`, via
     * [TvosVariantDiscovery.discoverVariants]. Shared by both callers in [execute]: the
     * redirect TARGET coordinate (dev.sajidali-mapped) and, since Task 10b, the OFFICIAL
     * coordinate (this component's own identity) used for the already-supported pre-check --
     * these are always different cache keys, since the mapped target group never equals the
     * original (JetBrains) group.
     */
    private fun getVariants(groupId: String, artifactId: String, version: String): List<TvosVariant> {
        val key = "$groupId:$artifactId:$version"
        return variantCache.getOrPut(key) {
            TvosVariantDiscovery.discoverVariants(
                params.repositoryUrls, groupId, artifactId, version, File(params.cacheDirPath),
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

        // Task 10b (Phase 4 blocker fix): modules for which one or more discovered tvOS
        // variants were skipped because the official artifact already ships that native
        // target. Verbose-only bookkeeping (see the KDoc on SkippedAlreadySupportedRecord) --
        // deliberately kept separate from emptyDiscoveries so it never feeds the WARN/
        // strictMode path.
        private val skippedAlreadySupported = ConcurrentHashMap<String, SkippedAlreadySupportedRecord>()

        /** Clears diagnostics bookkeeping; called once per build before the rule can fire. */
        fun resetDiagnostics() {
            injections.clear()
            emptyDiscoveries.clear()
            skippedAlreadySupported.clear()
        }

        /** Point-in-time read of the diagnostics bookkeeping accumulated so far this build. */
        fun diagnosticsSnapshot(): DiagnosticsSnapshot =
            DiagnosticsSnapshot(injections.values.toList(), emptyDiscoveries.values.toList(), skippedAlreadySupported.values.toList())
    }
}
