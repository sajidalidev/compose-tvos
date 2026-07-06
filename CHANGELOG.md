# Changelog

All notable changes to this project are documented in this file.

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
