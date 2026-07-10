# Changelog

All notable changes to this project are documented in this file.

## [Unreleased]

## [1.3.0] - 2026-07-10

### Added
- `io.insert-koin` and `io.coil-kt.coil3` are now default redirect groups with zero consumer
  config, following the same explicit-group-target pattern added for `androidx.tv` in 1.2.0:
  `io.insert-koin` -> `dev.sajidali.koin` and `io.coil-kt.coil3` -> `dev.sajidali.coil3`, merged
  into the default group mappings ahead of a consumer's own `additionalGroups` (which can still
  override either). Pairs with the fork's `dev.sajidali.koin` (Koin, `4.2.0`, tvOS-only modules)
  and `dev.sajidali.coil3` (Coil, `3.5.0`) artifacts, both following the same-version convention
  so no manifest change is needed.

## [1.2.0] - 2026-07-08

### Added
- `androidx.tv:tv-material` is now a default redirect group with zero consumer config: it's
  covered by a new explicit group target (`androidx.tv` -> `dev.sajidali.androidx.tv`) merged
  into the default group mappings, distinct from the `org.jetbrains`-only `ComposeModules.ALL` /
  `mapGroupId` path since `androidx.tv` is a Google coordinate whose fork target is a prepend,
  not a prefix replace. A consumer's own `additionalGroups` can still override it.

## [1.1.1] - 2026-07-07

### Added
- Ecosystem: the `dev.sajidali` fork now publishes `compose.material3.adaptive` (`adaptive`,
  `adaptive-layout`, `adaptive-navigation`, `adaptive-navigation3`, `1.3.0-beta02`),
  `material3-adaptive-navigation-suite` (`1.5.0-alpha22`), and `androidx.window:window-core`
  (`1.6.0-alpha02`, now built with a real tvOS Kotlin/Native target). No plugin code change was
  needed — the same-version convention covers `adaptive`, and `window-core` is picked up
  transitively via the fork's own module metadata.

### Fixed
- Official-first now verifies advertised platform artifacts exist; dangling upstream metadata
  (e.g. `org.jetbrains.androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0-beta01`'s tvOS
  variants on Maven Central, whose advertised platform module 404s on every repository) no
  longer breaks resolution — the fork serves those targets instead. Previously, both
  official-first mechanisms (the metadata-rule variant injection and the project-level
  dependency substitution) trusted an official umbrella's advertised `available-at` tvOS
  variant at face value; if the target module it pointed at didn't actually exist, injection and
  substitution were both skipped and resolution failed on the phantom coordinate instead of
  falling through to the fork.
- The variant-discovery and version-manifest disk caches now live under a new
  `compose-tvos-redirect-cache-v4` directory (bumped from `v3`; old caches are ignored
  harmlessly, exactly like the earlier v1/v2 → v3 bump). A cache file written by a 1.1.0 plugin
  predates the `available-at` group/version fields above and would otherwise silently decode
  them as `null`, defeating the dangling-metadata existence check just fixed for anyone with a
  warm 1.1.0 cache.
- The dangling-metadata fix no longer de-tunes an official umbrella's advertised variant when
  the fork has no replacement to offer for that same native target: doing so previously turned a
  recognizable "target module not found" resolution failure into an unhelpful "no matching
  variant" one, with nothing gained in exchange.

## [1.1.0] - 2026-07-06

### Added
- GradleTestKit functional test suite covering end-to-end plugin behavior.
- Offline support for the remote version-mapping manifest.
- End-of-build diagnostics summary.
- WARN-level logging when tvOS artifacts are missing (only surfaced for projects that declare tvOS targets).
- `strictMode` option.
- Deterministic tie-breaking for version-mapping resolution.
- Redirect coverage expanded from 8 to 15 groups: lifecycle, savedstate, navigationevent, navigation3, annotation-internal, collection-internal, material3.adaptive.
- `org.jetbrains.compose` plugin-marker interception: `plugins { id("org.jetbrains.compose") }` in consumer project build scripts is now transparently substituted (via `pluginManagement.resolutionStrategy.eachPlugin`) to the tvOS-patched `dev.sajidali.compose:compose-gradle-plugin` fork, giving tvOS resource packaging support with no consumer-side plugin-id change. Version resolution: `composeGradlePluginVersion` extension property -> manifest `gradlePlugin` field -> requested version (same-version convention). Opt out with `interceptComposeGradlePlugin.set(false)`. When a consumer declares no `pluginManagement.repositories` of their own, Gradle Plugin Portal is added first so the interception never silently disables portal fallback for the consumer's other plugins.
- "Official-first" tvOS resolution: both the metadata-rule variant injection and the project-level dependency substitution now check whether the requested official JetBrains artifact already ships a genuine tvOS `klib` before ever introducing a `dev.sajidali` coordinate — needed as JetBrains itself starts shipping real tvOS variants for some artifacts (e.g. `org.jetbrains.compose.runtime`); without it, unconditional injection produced a duplicate, identically-attributed Gradle variant and an unresolvable ambiguity error.

### Changed
- Gradle Module Metadata is now parsed with a real JSON parser.
- The variant disk cache is now JSON-based (cache directory v3; old caches are ignored harmlessly).
- All metadata rules are consolidated into a single cacheable, class-based rule (configuration-cache friendly).
- Caches now respect `GRADLE_USER_HOME`.
- HTTP fetches follow redirects and honor `--offline`.
- Empty tvOS variant discovery (an artifact confirmed, via a successful fetch, to have no tvOS variant at a given version) is now cached as a genuine success outcome distinct from a transient fetch failure, so a build no longer re-issues a network request every time for an artifact already known to lack tvOS support.
- The end-of-build diagnostics summary now shares one bookkeeping model between the metadata-rule injection path and the dependency-substitution path, so both official-first mechanisms are reflected in the same summary/WARN block instead of only one of them.
- The diagnostics WARN block (and `strictMode` failure message) no longer lists conflict-resolution-loser candidate versions — module versions a metadata rule was asked about while Gradle explored the dependency graph, but that were never the version conflict resolution actually kept — as if they were real gaps; a module with no working version anywhere still warns.

### Removed
- The standalone `dev.sajidali.compose-tvos-project` plugin id is no longer published. It was a no-op without the settings plugin. The 1.0.0 marker remains on the Gradle Plugin Portal but is deprecated — apply only `dev.sajidali.compose-tvos` in `settings.gradle.kts`.

## [1.0.0]

- Initial release.
