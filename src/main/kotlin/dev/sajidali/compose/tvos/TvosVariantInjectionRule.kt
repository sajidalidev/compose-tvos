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
 *
 * Task 10e: also used for two OTHER "official-first" successes that likewise must never
 * contribute to the WARN/strictMode path: (1) [TvosVariantInjectionRule.execute]'s
 * fork-discovery-EMPTY branch, when the official artifact turns out to already cover the
 * target itself (`skippedNativeTargets` then lists every officially-covered target, since the
 * fork provided nothing to compare per-variant against); and (2)
 * [ComposeTvosRedirectPlugin]'s project-level `dependencySubstitution` skip, recorded directly
 * via [TvosDiagnosticsBookkeeping.recordSkippedAlreadySupported] from that independent code
 * path -- see [TvosDiagnosticsBookkeeping]'s KDoc for why the bookkeeping itself lives there
 * rather than in this rule's companion object.
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
        val sourceModule = "$group:$moduleName:${id.version}"
        val targetCoordinate = "$targetGroup:$targetArtifact:$version"

        // Task 10b (Phase 4 blocker fix), reordered ahead of the empty-fork-discovery check by
        // Task 10e: check whether the OFFICIAL module -- this component's own
        // group:module:version, not the redirect target -- already ships native tvOS variant(s)
        // itself (e.g. org.jetbrains.compose.runtime already publishes tvosArm64 upstream).
        // Re-uses the exact same discoverVariants/parseModuleMetadata path, repository list, and
        // disk/memory caching as the target lookup below, keyed by the OFFICIAL coordinate --
        // naturally distinct from the target coordinate's own cache key, since the redirect
        // target always carries a different (dev.sajidali-mapped) group. A failed/empty
        // official-metadata fetch (offline, network error, or a parse failure on well-formed
        // metadata) degrades to an empty set here, same as discoverVariants's existing
        // never-throw contract.
        //
        // Task 10e note: this fetch now ALSO runs on the empty-fork-discovery path below (it
        // previously only ran once the fork side was confirmed non-empty) -- one extra
        // getVariants call for that path, deliberately accepted: it is memoized (in-memory +
        // disk, see getVariants's own KDoc), so it costs a genuine network/disk round trip only
        // the FIRST time a given official coordinate is seen, not per-component-metadata-rule
        // invocation.
        //
        // Task 11 (dangling-metadata fix): an official variant's advertised availability is no
        // longer trusted at face value -- verifyOfficialSupport additionally probes each
        // available-at redirect target's existence (one extra cached probe per distinct
        // available-at module, see its own KDoc) before counting a native target as officially
        // supported, so upstream metadata that advertises a tvOS variant whose target module
        // was never actually published no longer causes this rule to skip injection for it.
        val officialVariants = getVariants(group, moduleName, id.version)
        val supportResult = TvosVariantDiscovery.verifyOfficialSupport(
            officialVariants, params.repositoryUrls, File(params.cacheDirPath),
            if (params.verbose) logger else null, params.offline
        )
        val alreadySupportedTargets = supportResult.supportedNativeTargets

        val variants = getVariants(targetGroup, targetArtifact, version)
        if (variants.isEmpty()) {
            // Task 10e: fork discovery came back empty. Before treating this as a genuine gap,
            // check whether the official artifact already covers tvOS itself -- if so, nothing
            // is actually broken (no injection was ever needed for this coordinate) and this
            // must NOT be recorded as an [EmptyDiscoveryRecord] (see
            // TvosDiagnosticsBookkeeping.recordEmptyForkDiscovery for the precedence decision).
            // Finding 2 (review of task-20-report.md): no poisoning here either -- the fork has
            // nothing to inject as a replacement, so de-tuning the dangling official variant's
            // attributes would only trade a recognizable "phantom coordinate" resolution failure
            // for an unhelpful "no matching variant" one, with no replacement to show for it.
            TvosDiagnosticsBookkeeping.recordEmptyForkDiscovery(
                sourceModule, targetCoordinate, alreadySupportedTargets, params.repositoryUrls, params.offline
            )
            if (params.verbose && alreadySupportedTargets.isNotEmpty()) {
                logger.lifecycle(
                    "[ComposeTvosRedirect] Fork discovery found nothing for $sourceModule, but the " +
                        "official artifact already provides tvOS variant(s) for: " +
                        "${alreadySupportedTargets.toList()}; no injection needed."
                )
            }
            return
        }

        // That fallback (empty officialVariants -> nothing already supported -> inject
        // everything discovered, i.e. the previous, unconditional behavior) is deliberately
        // chosen over failing closed: injection is only reachable when the TARGET dev.sajidali
        // metadata was already fetchable, and a wrongly-SKIPPED injection breaks tvOS builds
        // outright, whereas a wrongly-ADDED injection only breaks the (rare)
        // already-officially-supported case -- exactly the case this whole check exists to
        // avoid in the common path.
        val injectable = variants.filter { it.nativeTarget !in alreadySupportedTargets }

        // Task 11 follow-up: a dangling official variant is still present, UNMODIFIED, in this
        // component's own metadata -- merely excluding its native target from
        // alreadySupportedTargets (so injectable above includes it) reproduces the exact
        // "cannot choose between ..." variant-ambiguity Gradle error Task 10b's whole pre-check
        // exists to avoid, since our injected variant would carry an IDENTICAL, mirrored
        // attribute set. See poisonDanglingOfficialVariants's own KDoc for why de-tuning the
        // dangling variant's own attributes -- rather than removing it (no public API for that)
        // or overriding its available-at target (also no public API for that) -- is how this is
        // resolved.
        //
        // Finding 2 (review of task-20-report.md): poisoning must only run for a dangling
        // target that `injectable` will actually replace -- gated here, AFTER `injectable` is
        // computed, rather than unconditionally on every confirmed-dangling target. If the fork
        // has no variant for that same native target either, poisoning the official variant
        // buys nothing (there is no injected replacement to leave as the sole candidate) and
        // only trades the recognizable "phantom org.jetbrains coordinate" resolution failure for
        // an unhelpful "no matching variant" one.
        val replacedDanglingTargets = supportResult.danglingNativeTargets.filterTo(mutableSetOf()) { target ->
            injectable.any { it.nativeTarget == target }
        }
        if (replacedDanglingTargets.isNotEmpty()) {
            poisonDanglingOfficialVariants(metadata, officialVariants, replacedDanglingTargets)
        }

        if (injectable.size < variants.size) {
            val skippedTargets = variants.filter { it.nativeTarget in alreadySupportedTargets }.map { it.nativeTarget }
            TvosDiagnosticsBookkeeping.recordSkippedAlreadySupported(
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

        TvosDiagnosticsBookkeeping.recordInjection(InjectionRecord(sourceModule, targetCoordinate, injectable.size))

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
                        // (<gradleUserHome>/compose-tvos-redirect-cache-v4/, `~/.gradle` by
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

    /**
     * Task 11 (dangling-metadata fix): de-tunes each ORIGINAL official [officialVariants] entry
     * whose native target is confirmed dangling ([danglingNativeTargets]) so it can never again
     * be selected as a candidate for a real consumer request, clearing the way for the freshly
     * [injectTvosVariants]-added variant (same native target, pointing at the fork) to be the
     * SOLE match.
     *
     * Why this exists at all: once a dangling native target is excluded from
     * `alreadySupportedTargets`, [execute] injects a normal variant for it exactly as if the
     * official artifact had never mentioned that target -- but the official artifact's ORIGINAL
     * variant (the one whose `available-at` redirect is broken) is still sitting there,
     * unmodified, in this component's metadata. Since [injectTvosVariants] mirrors the fork
     * variant's attributes 1:1 (the same mechanism Task 10b relies on for the genuinely-official
     * case), the newly injected variant and the original dangling one end up with IDENTICAL
     * attribute sets -- Gradle then cannot choose between them and fails the whole component
     * with a "cannot choose between ..." variant-ambiguity error (confirmed red: the dangling
     * functional-test scenario failed with exactly this error before this fix was added).
     *
     * Why de-tuning attributes rather than something more surgical: [ComponentMetadataDetails]'s
     * public API has no way to remove a variant outright, and [org.gradle.api.artifacts.VariantMetadata]
     * has no way to override an EXISTING variant's `available-at` redirect target (its
     * `withDependencies`/`withFiles` mutate a variant's OWN local dependency/file list, which an
     * available-at variant doesn't have -- its entire identity is delegated to the target
     * module). Overwriting the variant's own `org.jetbrains.kotlin.native.target` attribute value
     * to a value no real consumer request will ever carry is the one lever both APIs do expose:
     * [ComponentMetadataDetails.withVariant] can still target an already-declared (including
     * available-at-backed) variant by name, and [org.gradle.api.artifacts.VariantMetadata.attributes]
     * lets an existing attribute's value be overwritten (not just added to), same as any other
     * `AttributeContainer`. Once that attribute no longer reads `tvos_arm64` (etc.), the variant
     * simply stops matching any real `tvosArm64CompileKlibraries`-shaped request, so Gradle's
     * variant selection is left with exactly one candidate: the injected one.
     *
     * Finding 2 (review of task-20-report.md): [danglingNativeTargets] as passed by [execute] is
     * NOT the raw `supportResult.danglingNativeTargets` set -- it is pre-filtered down to only
     * the targets `injectable` will actually replace. A dangling target the fork has no variant
     * for either must never reach this function: there would be no injected replacement left
     * behind, so de-tuning the official variant would only turn a recognizable
     * "target module not found" resolution failure into an unhelpful "no matching variant" one.
     */
    private fun poisonDanglingOfficialVariants(
        metadata: ComponentMetadataDetails,
        officialVariants: List<TvosVariant>,
        danglingNativeTargets: Set<String>
    ) {
        officialVariants.filter { it.nativeTarget in danglingNativeTargets }.forEach { variant ->
            metadata.withVariant(variant.variantName) { variantMetadata ->
                variantMetadata.attributes { attrs ->
                    attrs.attribute(
                        Attribute.of("org.jetbrains.kotlin.native.target", String::class.java),
                        "${variant.nativeTarget}-dangling-unavailable"
                    )
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

        // Diagnostics bookkeeping (Task 5 / defect D1) moved to [TvosDiagnosticsBookkeeping]
        // in Task 10e -- see that object's KDoc for why: ComposeTvosRedirectPlugin's own
        // "official-first" success path now needs to record into the exact same
        // skippedAlreadySupported map, so the bookkeeping itself no longer lives on this rule's
        // companion object specifically.
    }
}
