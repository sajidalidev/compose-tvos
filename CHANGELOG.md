# Changelog

All notable changes to this project are documented in this file.

## [1.1.0] - Unreleased

### Added
- GradleTestKit functional test suite covering end-to-end plugin behavior.
- Offline support for the remote version-mapping manifest.
- End-of-build diagnostics summary.
- WARN-level logging when tvOS artifacts are missing (only surfaced for projects that declare tvOS targets).
- `strictMode` option.
- Deterministic tie-breaking for version-mapping resolution.
- Redirect coverage expanded from 8 to 15 groups: lifecycle, savedstate, navigationevent, navigation3, annotation-internal, collection-internal, material3.adaptive.

### Changed
- Gradle Module Metadata is now parsed with a real JSON parser.
- The variant disk cache is now JSON-based (cache directory v3; old caches are ignored harmlessly).
- All metadata rules are consolidated into a single cacheable, class-based rule (configuration-cache friendly).
- Caches now respect `GRADLE_USER_HOME`.
- HTTP fetches follow redirects and honor `--offline`.

### Removed
- The standalone `dev.sajidali.compose-tvos-project` plugin id is no longer published. It was a no-op without the settings plugin. The 1.0.0 marker remains on the Gradle Plugin Portal but is deprecated — apply only `dev.sajidali.compose-tvos` in `settings.gradle.kts`.

## [1.0.0]

- Initial release.
