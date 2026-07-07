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

## Gradle Plugin Portal publication is pending

The plugin is currently resolvable via Maven Central. Publication to the Gradle Plugin Portal
(where `plugins { id(...) version ... }` resolves by default when you declare no
`pluginManagement.repositories` of your own) is still pending. In the meantime, declare an
explicit `pluginManagement` block with `mavenCentral()` in your `settings.gradle.kts` (see the
[quickstart](index.md#quickstart)) — once the Portal publish lands, this plugin will also
resolve from Gradle Plugin Portal directly, and the explicit `mavenCentral()` entry can be kept
or dropped as you prefer.

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
