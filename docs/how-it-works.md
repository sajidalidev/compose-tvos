---
layout: page
title: How it works
nav_order: 2
permalink: /how-it-works.html
---

# How it works

JetBrains Compose Multiplatform does not yet officially ship tvOS `klib`s for most of its
modules. This plugin closes that gap at *dependency-resolution time*, in your build, with no
changes to your `dependencies {}` declarations.

## tvOS variant injection

A `ComponentMetadataRule` (`TvosVariantInjectionRule`) attaches a `dev.sajidali.*`-published
tvOS `available-at` variant onto the official `org.jetbrains.*` umbrella module for every
[covered group](supported-versions.md), so Gradle's own variant resolution picks the tvOS
artifact for tvOS targets while iOS/Android/Desktop targets keep resolving the official
JetBrains artifact untouched.

## Official-first resolution

Before injecting or substituting anything, the plugin checks whether the *official* artifact
you requested already ships a genuine tvOS `klib` at that exact `group:artifact:version` — this
has started happening upstream for some modules, e.g. `org.jetbrains.compose.runtime`. If it
does, the plugin leaves it alone: no `dev.sajidali` coordinate is ever introduced for that
dependency. This applies both to the metadata-rule injection path and to the separate
project-level dependency-substitution path.

This is what lets the plugin keep working transparently as JetBrains ships more native tvOS
support upstream over time — a module that gains an official tvOS `klib` simply stops being
redirected, with no consumer-side change needed.

## `org.jetbrains.compose` plugin-marker interception

`plugins { id("org.jetbrains.compose") }` in a project build script is transparently substituted
(via `pluginManagement.resolutionStrategy.eachPlugin`) to the tvOS-patched
`dev.sajidali.compose:compose-gradle-plugin` fork, so tvOS Compose Resources packaging works
with no consumer-side plugin-id change.

Version resolution (first non-null wins):

1. `composeTvos.composeGradlePluginVersion`
2. the version-manifest's `gradlePlugin` field
3. the requested `org.jetbrains.compose` version (the same-version convention, below)

Opt out entirely with:

```kotlin
composeTvos {
    interceptComposeGradlePlugin.set(false)
}
```

Opting out means the official, unpatched Gradle plugin is resolved instead — tvOS Compose
Resources packaging will not work.

## Version-mapping manifest and the same-version convention

Most tvOS-fork artifacts are published at the exact same version as the official artifact you
requested — the "same-version convention". Where that isn't true (the fork tracks a different
alpha/beta line, or hasn't republished a given upstream version yet), the plugin consults a
remote JSON manifest (`manifestUrl`, defaults to `manifest/compose-tvos-versions.json` in the
plugin's repository on `main`) for an explicit override.

The manifest is schema-versioned (`"schema": 2`). Its `mappings` object supports several key
shapes, in increasing order of specificity:

- `*:versionPattern` — matches any group
- `group.*:versionPattern` — matches a group prefix
- `group:versionPattern` — matches an exact group at a version pattern (e.g. `1.10.*`)
- `group:artifact:versionPattern` — matches an exact group and artifact

A `gradlePlugin` field (schema 2+) additionally pins the plugin-marker interception's default
version (see above).

`composeTvos.versionMappings` entries you set yourself always win over the manifest on key
collision. Set `manifestUrl` to `""` to disable manifest fetching entirely and rely solely on
your own `versionMappings`/the same-version convention.

## Diagnostics: `strictMode` and `verbose`

By default, a redirect-eligible module that resolves to zero tvOS variants (i.e. the plugin
looked, but neither the official artifact nor the `dev.sajidali` fork has a tvOS `klib` for the
requested version) is reported as an end-of-build `WARNING` block, not a build failure — some of
these are pre-conflict-resolution candidate versions Gradle never actually consumes (see
[Troubleshooting](troubleshooting.md)), so failing on every one would be noisy.

Set `composeTvos.strictMode.set(true)` to turn that block into a hard `GradleException` naming
every affected module instead. Both only ever fire for a project that actually declares a tvOS
Kotlin target — an iOS/Android/Desktop-only project is never affected.

`composeTvos.verbose.set(true)` logs variant discovery, dependency redirection, repository
lookups, and manifest loading at `lifecycle` level.

## Configuration reference

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
