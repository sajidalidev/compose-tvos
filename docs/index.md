---
layout: home
title: Home
nav_order: 1
permalink: /
---

# compose-tvos

A Gradle **settings plugin** that adds tvOS (`tvosArm64`/`tvosSimulatorArm64`) support to
JetBrains Compose Multiplatform projects — without JetBrains's own artifacts needing to support
tvOS yet.

- [How it works](how-it-works.md) — the architecture: variant injection, official-first
  resolution, plugin-marker interception, the version manifest.
- [Supported versions](supported-versions.md) — the full version matrix and what "covered
  groups" means.
- [App embedding](app-embedding.md) — building and running your KMP app on a real tvOS device
  or simulator, including the Compose Resources bundle layout.
- [Library authors](library-authors.md) — adding tvOS to your own Kotlin Multiplatform library.
- [Troubleshooting](troubleshooting.md) — WARN blocks, `strictMode`, caches, offline behavior.

## Quickstart

```kotlin
// settings.gradle.kts
plugins {
    id("dev.sajidali.compose-tvos") version "1.1.0"
}
```

That is the entire required setup: the plugin resolves from the
[Gradle Plugin Portal](https://plugins.gradle.org/plugin/dev.sajidali.compose-tvos), and once
applied it takes care of the rest (including adding `mavenCentral()` to plugin resolution for
the tvOS-patched Compose Gradle plugin it substitutes). If your build declares its own
`pluginManagement.repositories` block, keep `gradlePluginPortal()` and `mavenCentral()` in it.

That's it — no other settings are required. Add `tvosArm64()`/`tvosSimulatorArm64()` targets to
your Kotlin Multiplatform module as you normally would:

```kotlin
// build.gradle.kts
kotlin {
    tvosArm64()
    tvosSimulatorArm64()
}
```

...and your existing Compose dependency declarations resolve for tvOS too, with no changes:

```kotlin
kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.components.resources)
        }
    }
}
```

See the full list of redirected dependency groups in
[Supported versions](supported-versions.md).

## Requirements

- Gradle 8.0+
- **Kotlin 2.3.20+ for consumer projects targeting tvOS.** The fork's published `klib`s were
  compiled with Kotlin/Native compiler `2.3.20` (verified directly from klib manifests:
  `compiler_version=2.3.20`, `abi_version=2.3.0`) — an older Kotlin version's compiler is not
  ABI-compatible with these artifacts on tvOS targets. Non-tvOS targets in the same project are
  unaffected by this constraint, since they never touch a `dev.sajidali` artifact.
- JetBrains Compose Multiplatform 1.6+
- Your repositories should include Maven Central (and, for the very latest fork publishes ahead
  of a Central sync, `mavenLocal()`):

  ```kotlin
  // settings.gradle.kts
  dependencyResolutionManagement {
      repositories {
          google()
          mavenCentral()
      }
  }
  ```

## Demo

A working, canonical tvOS Compose Multiplatform consumer of this plugin lives in
[`demo/`](https://github.com/sajidalidev/compose-tvos/tree/main/demo) in the repository:
`compose.runtime`/`foundation`/`material3`/`components.resources`, `navigation-compose`,
`lifecycle-viewmodel-compose`, tvOS + iOS targets, and Compose Resources.
