# Compose tvOS Redirect

A Gradle settings plugin that adds tvOS (`tvosArm64`/`tvosSimulatorArm64`) support to JetBrains
Compose Multiplatform projects, without JetBrains's own artifacts needing to support tvOS yet.

## Quickstart

```kotlin
// settings.gradle.kts
plugins {
    id("dev.sajidali.compose-tvos") version "1.1.0"
}
```

That's it. Add `tvosArm64()`/`tvosSimulatorArm64()` targets to your Kotlin Multiplatform module as
you normally would and `compose.runtime`/`compose.foundation`/`compose.material3`/
`compose.components.resources` (and the other covered groups below) resolve for tvOS too.

## What the plugin actually does

JetBrains Compose Multiplatform does not yet officially ship tvOS `klib`s for most of its modules.
This plugin closes that gap at *dependency-resolution time*, in your build, with no changes to
your `dependencies {}` declarations:

- **tvOS variant injection.** A `ComponentMetadataRule` (`TvosVariantInjectionRule`) attaches a
  `dev.sajidali.*`-published tvOS `available-at` variant onto the official
  `org.jetbrains.*` umbrella module for every covered group (see below), so Gradle's own variant
  resolution picks the tvOS artifact for tvOS targets while iOS/Android/Desktop targets keep
  resolving the official JetBrains artifact untouched.
- **Official-first.** Before injecting or substituting anything, the plugin checks whether the
  *official* artifact you requested already ships a genuine tvOS `klib` at that exact
  `group:artifact:version` (this has started happening upstream for some modules, e.g.
  `org.jetbrains.compose.runtime`). If it does, the plugin leaves it alone — no `dev.sajidali`
  coordinate is ever introduced for that dependency. This applies to both the metadata-rule
  injection path and the separate project-level dependency-substitution path.
- **`org.jetbrains.compose` plugin-marker interception.** `plugins { id("org.jetbrains.compose") }`
  in a project build script is transparently substituted (via
  `pluginManagement.resolutionStrategy.eachPlugin`) to the tvOS-patched
  `dev.sajidali.compose:compose-gradle-plugin` fork, so tvOS Compose Resources packaging works
  with no consumer-side plugin-id change. Version resolution (first non-null wins):
  `composeTvos.composeGradlePluginVersion` → the manifest's `gradlePlugin` field → the requested
  `org.jetbrains.compose` version (same-version convention). Opt out entirely with:
  ```kotlin
  composeTvos {
      interceptComposeGradlePlugin.set(false)
  }
  ```
- **Version-mapping manifest.** Most tvOS-fork artifacts are published at the exact same version
  as the official artifact you requested (the "same-version convention"). Where that isn't true
  — the fork tracks a different alpha/beta line, or hasn't republished a given upstream version —
  the plugin consults a remote JSON manifest (`manifestUrl`, defaults to
  `manifest/compose-tvos-versions.json` in this repo on `main`) for an explicit override. The
  manifest is schema-versioned (`"schema": 2`); a `gradlePlugin` field (schema 2+) additionally
  pins the plugin-marker interception's default version. `composeTvos.versionMappings` entries you
  set yourself always win over the manifest on key collision. Set `manifestUrl` to `""` to disable
  manifest fetching entirely and rely solely on your own `versionMappings`/the same-version
  convention.
- **`strictMode`.** By default, a redirect-eligible module that resolves to zero tvOS variants
  (i.e., the plugin looked, but neither the official artifact nor the `dev.sajidali` fork has a
  tvOS `klib` for the requested version) is reported as an end-of-build `WARNING` block, not a
  build failure — some of these are pre-conflict-resolution candidate versions Gradle never
  actually consumes (see Troubleshooting below), so failing on every one would be noisy. Set
  `composeTvos.strictMode.set(true)` to turn that block into a hard `GradleException` naming every
  affected module instead. Both only ever fire for a project that actually declares a tvOS Kotlin
  target — an iOS/Android/Desktop-only project is never affected.
- **`verbose`.** `composeTvos.verbose.set(true)` logs variant discovery, dependency redirection,
  repository lookups, and manifest loading at `lifecycle` level.
- **Offline behavior.** With `--offline`, the plugin never opens a network connection: the version
  manifest and per-artifact variant-discovery results are served from their on-disk caches only
  (fresh or stale — stale-while-offline is preferred over failing the build), and a coordinate
  with no cached entry degrades to an empty result (treated the same as "no tvOS variant found"),
  never a hard failure. `--refresh-dependencies` forces a fresh manifest fetch (variant-discovery
  caching is unaffected by that flag).
- **Caches live under `GRADLE_USER_HOME`.** Both the variant-discovery cache
  (`<gradleUserHome>/compose-tvos-redirect-cache-v3/`) and the version-manifest cache
  (`<gradleUserHome>/compose-tvos-redirect-cache-v3/version-manifest/`) are resolved from
  whichever `GRADLE_USER_HOME` (or `--gradle-user-home`) the invoking Gradle actually used — never
  hardcoded to `~/.gradle` — so isolated/CI/TestKit invocations get isolated caches automatically.

## Covered groups

The plugin redirects these 15 `org.jetbrains.*` groups to their `dev.sajidali.*` tvOS-fork twin
whenever a tvOS Kotlin target requests them:

- `org.jetbrains.compose.ui`
- `org.jetbrains.compose.foundation`
- `org.jetbrains.compose.runtime`
- `org.jetbrains.compose.material`
- `org.jetbrains.compose.material3`
- `org.jetbrains.compose.animation`
- `org.jetbrains.compose.components`
- `org.jetbrains.compose.annotation-internal`
- `org.jetbrains.compose.collection-internal`
- `org.jetbrains.compose.material3.adaptive` — **covered by the plugin's redirect logic, but not
  currently published by the fork.** Upstream `androidx.window:window-core` (a transitive
  dependency of `material3-adaptive`) ships no Kotlin/Native target at all, so the fork excludes
  `material3-adaptive`/`material3-adaptive-navigation-suite` from this release rather than
  porting around that gap. A project requesting `material3-adaptive` on tvOS gets the normal
  "no tvOS variant found" warning/`strictMode` failure, not a silent success. Revisit once
  upstream ships a tvOS target for `androidx.window:window-core`.
- `org.jetbrains.androidx.navigation` (including the `navigation-compose` artifact)
- `org.jetbrains.androidx.lifecycle` (including `lifecycle-viewmodel-compose`)
- `org.jetbrains.androidx.savedstate` (including `savedstate-compose`)
- `org.jetbrains.androidx.navigationevent`
- `org.jetbrains.androidx.navigation3` (including `navigation3-ui`)

Add further groups/artifacts of your own with `composeTvos.additionalGroups`/
`additionalArtifacts` (see Configuration below) — useful for third-party KMP libraries that
publish their own tvOS-less umbrella artifacts the same way JetBrains does.

## Supported version matrix

The fork republishes upstream Compose Multiplatform/AndroidX libraries under `dev.sajidali.*`
with tvOS `klib`s added. Versions currently published (verify against the manifest / your own
`versionMappings` for anything not covered by the same-version convention):

| Component | Published `dev.sajidali` version |
|---|---|
| `compose.{ui,foundation,runtime,animation,material,components}` | `1.12.0-beta01` |
| `compose.material3` (own alpha line, independent of the COMPOSE version) | `1.5.0-alpha22` |
| `androidx.lifecycle.*` | `2.11.0` |
| `androidx.navigation.*` (incl. `navigation-compose`) | `2.10.0-alpha05` |
| `androidx.navigation3.*` (`navigation3-ui`) | `1.2.0-alpha04` |
| `androidx.navigationevent.*` (`navigationevent-compose`) | `1.1.1` |
| `androidx.savedstate.*` (incl. `savedstate-compose`) | `1.5.0-alpha01` |
| `compose-gradle-plugin` (the `org.jetbrains.compose` fork used by plugin-marker interception) | `1.12.0-beta01` |
| `compose.material3.adaptive` / `material3-adaptive-navigation-suite` | not published (see above) |

## Requirements

- Gradle 8.0+
- **Kotlin 2.3.20+ for consumer projects targeting tvOS.** The fork's published `klib`s were
  compiled with Kotlin/Native compiler `2.3.20` (verified directly from klib manifests:
  `compiler_version=2.3.20`, `abi_version=2.3.0`) — an older Kotlin version's compiler will not be
  ABI-compatible with these artifacts on tvOS targets. Non-tvOS targets in the same project are
  unaffected by this constraint (they never touch a `dev.sajidali` artifact).
- JetBrains Compose Multiplatform 1.6+

## Configuration

The plugin works out of the box with sensible defaults. Full optional configuration surface:

```kotlin
// settings.gradle.kts
plugins {
    id("dev.sajidali.compose-tvos") version "1.1.0"
}

composeTvos {
    // Enable verbose logging (variant discovery, redirection, manifest loading).
    verbose.set(true)

    // Fail the build (instead of warning) when a tvOS project has a redirect-eligible module
    // with zero discoverable tvOS variants.
    strictMode.set(true)

    // Override target version for all redirected artifacts.
    targetVersion.set("1.12.0-beta01")

    // Add/override version mappings; always wins over the remote manifest on key collision.
    versionMappings.put("org.jetbrains.compose.material3:1.11.0-alpha07", "1.5.0-alpha22")

    // Point at a different (or empty, to disable) version-mapping manifest.
    manifestUrl.set("https://raw.githubusercontent.com/sajidalidev/compose-tvos/main/manifest/compose-tvos-versions.json")

    // Add additional library groups to redirect.
    additionalGroups.put("io.insert-koin", "dev.sajidali.koin")

    // Add specific artifact mappings.
    additionalArtifacts.put(
        "io.coil-kt.coil3:coil-compose",
        "dev.sajidali.coil3:coil-compose"
    )

    // Opt out of the org.jetbrains.compose plugin-marker interception (resolve the official,
    // unpatched Gradle plugin instead — tvOS Compose Resources packaging will not work).
    interceptComposeGradlePlugin.set(false)

    // Override the version of the substituted compose-gradle-plugin fork (only relevant when
    // interceptComposeGradlePlugin is true).
    composeGradlePluginVersion.set("1.12.0-beta01")
}
```

## Demo

[`demo/`](demo/) is a working, canonical tvOS Compose Multiplatform consumer of this plugin:
`compose.runtime`/`foundation`/`material3`/`components.resources`, `navigation-compose`,
`lifecycle-viewmodel-compose`, tvOS + iOS targets, and Compose Resources. It builds this plugin
as a composite build (`includeBuild("..")`) so it always tracks the branch under development
rather than a published coordinate — see the comments at the top of `demo/settings.gradle.kts`
for how a real consumer's setup differs (a plain version-pinned `plugins {}` block, no
`includeBuild`).

## Troubleshooting

### `[ComposeTvos] WARNING: tvOS variant discovery found nothing for N redirect-eligible module(s)`

This means the plugin looked for a tvOS `klib` (official first, then the `dev.sajidali` fork) for
one or more modules and found neither, for the *exact* `group:artifact:version` it was asked
about. Two common causes:

1. **A real gap** — the fork hasn't published that version/group yet (check the version matrix
   above and the manifest), or the version needs an entry in `composeTvos.versionMappings`.
2. **A pre-conflict-resolution candidate version.** `ComponentMetadataRule`s fire on every version
   node Gradle's resolution engine touches while resolving a graph, not just the winning version
   after conflict resolution — so a losing candidate version (one nothing ends up actually
   depending on) can legitimately show up in this list even though your build's resolved graph
   never touches it. If your build otherwise succeeds and the modules named don't ring a bell,
   this is very likely why. Set `composeTvos.strictMode.set(true)` temporarily and inspect the
   resulting exception message together with `./gradlew :dependencies --configuration
   <tvosConfigurationName>` to see whether the named module survives conflict resolution.

Use `composeTvos.strictMode.set(true)` if you'd rather the build fail loudly than warn — useful in
CI once you've confirmed your resolved graph is clean.

### Clearing the cache

If a fork publish changes shape (new tvOS variants, a version bump) and the plugin still reports
stale results, clear the on-disk caches under `GRADLE_USER_HOME`:

```bash
rm -rf "$(./gradlew properties -q --property gradleUserHomeDir | awk '{print $2}')/compose-tvos-redirect-cache-v3"
```

or simply delete `~/.gradle/compose-tvos-redirect-cache-v3` if you use the default
`GRADLE_USER_HOME`. `--refresh-dependencies` forces a fresh version-manifest fetch without
clearing the variant-discovery cache.

### Artifacts not found

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

## License

```
Copyright 2025 Sajid Ali

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
```
