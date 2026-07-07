---
layout: page
title: Troubleshooting
nav_order: 6
permalink: /troubleshooting.html
---

# Troubleshooting

## `[ComposeTvos] WARNING: tvOS variant discovery found nothing for N redirect-eligible module(s)`

This means the plugin looked for a tvOS `klib` (official first, then the `dev.sajidali` fork)
for one or more modules and found neither, for the *exact* `group:artifact:version` it was asked
about. Two common causes:

1. **A real gap** — the fork hasn't published that version/group yet (check the
   [version matrix](supported-versions.md) and the manifest), or the version needs an entry in
   `composeTvos.versionMappings`.
2. **A pre-conflict-resolution candidate version.** `ComponentMetadataRule`s fire on every
   version node Gradle's resolution engine touches while resolving a graph, not just the winning
   version after conflict resolution — so a losing candidate version (one nothing ends up
   actually depending on) can legitimately show up in this list even though your build's
   resolved graph never touches it. If your build otherwise succeeds and the modules named don't
   ring a bell, this is very likely why.

Set `composeTvos.strictMode.set(true)` temporarily and inspect the resulting exception message
together with `./gradlew :dependencies --configuration <tvosConfigurationName>` to see whether
the named module survives conflict resolution.

## `strictMode`

By default, the WARN block above is only a warning, not a build failure. Set
`composeTvos.strictMode.set(true)` if you'd rather the build fail loudly than warn — useful in
CI once you've confirmed your resolved graph is clean. Both the WARN block and `strictMode`'s
`GradleException` only ever fire for a project that actually declares a tvOS Kotlin target.

## Clearing the cache

Both the variant-discovery cache and the version-manifest cache live under `GRADLE_USER_HOME`,
resolved from whichever `GRADLE_USER_HOME` (or `--gradle-user-home`) the invoking Gradle actually
used — never hardcoded to `~/.gradle` — so isolated/CI/TestKit invocations get isolated caches
automatically:

- Variant-discovery cache: `<gradleUserHome>/compose-tvos-redirect-cache-v3/`
- Version-manifest cache: `<gradleUserHome>/compose-tvos-redirect-cache-v3/version-manifest/`

If a fork publish changes shape (new tvOS variants, a version bump) and the plugin still reports
stale results, clear the on-disk cache:

```bash
rm -rf "$(./gradlew properties -q --property gradleUserHomeDir | awk '{print $2}')/compose-tvos-redirect-cache-v3"
```

or simply delete `~/.gradle/compose-tvos-redirect-cache-v3` if you use the default
`GRADLE_USER_HOME`.

`--refresh-dependencies` forces a fresh version-manifest fetch without clearing the
variant-discovery cache.

## Offline behavior

With `--offline`, the plugin never opens a network connection: the version manifest and
per-artifact variant-discovery results are served from their on-disk caches only (fresh or
stale — stale-while-offline is preferred over failing the build), and a coordinate with no
cached entry degrades to an empty result (treated the same as "no tvOS variant found"), never a
hard failure.

## Known exclusions

There are no outstanding gaps in the covered dependency groups as of this writing — the
`compose.material3.adaptive` family and `androidx.window:window-core` (formerly excluded) are
now published by the fork and covered by the same-version convention with no extra
configuration. See [Supported versions](supported-versions.md) for the full matrix.

The one target that remains unsupported is `tvosX64` (the legacy Intel tvOS simulator) — the
fork does not build it, and this plugin does not redirect it. Use `tvosSimulatorArm64` for
simulator builds instead.

## Plugin does not resolve

Version 1.1.0+ resolves from the
[Gradle Plugin Portal](https://plugins.gradle.org/plugin/dev.sajidali.compose-tvos) with no
`pluginManagement` configuration (it is also on Maven Central). If resolution fails, check for
a `pluginManagement.repositories` block of your own that omits `gradlePluginPortal()` — once
you declare that block, Gradle stops adding the Portal implicitly, so keep both
`gradlePluginPortal()` and `mavenCentral()` in it. Do not pin version `1.0.0`: it predates the
published artifact ecosystem and configures nothing (upgrade to `1.1.0`).

## Migrating from a pre-plugin "shadow" repository

If your project previously obtained tvOS Compose by publishing patched `org.jetbrains.*`
artifacts into `mavenLocal()` or a vendored/local Maven directory (the "shadow" approach used
before this plugin existed), **remove those `org/jetbrains/**` entries before adopting the
plugin** (keep any third-party artifacts, e.g. your own koin/coil tvOS builds — only the
Compose shadow must go).

Why: the plugin deliberately prefers official artifacts whenever they already provide a tvOS
variant ("official-first"). It cannot distinguish a genuine upstream tvOS publish from your own
older shadow publish sitting in a repository you declared — so the stale shadow wins, no
injection happens for those modules, and your dependency graph silently mixes two Compose
lineages. The typical symptom is not a resolution error but a **link-time failure** with
duplicate IR symbols (e.g. `IrPropertySymbolImpl is already bound`) once modules from both
lineages meet in one binary.

## Artifacts not found

Ensure your repositories include Maven Central (and, for the very latest fork publishes ahead of
a Central sync, `mavenLocal()`):

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}
```
