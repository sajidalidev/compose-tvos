---
layout: page
title: Library authors
nav_order: 5
permalink: /library-authors.html
---

# Adding tvOS to your own KMP library

This plugin was verified against two real, independent third-party Kotlin Multiplatform
libraries — [Koin](https://insert-koin.io/)'s `koin-compose` module and
[Coil](https://coil-kt.github.io/coil/)'s `coil-compose-core` module — confirming resolution,
compilation, and zero `dev.sajidali` leakage into non-tvOS targets in both cases. The recipe
below is exactly what was applied.

## 1. Add the tvOS targets

In your library module's `build.gradle.kts`:

```kotlin
kotlin {
    tvosArm64()
    tvosSimulatorArm64()
}
```

## 2. Apply the plugin in `settings.gradle.kts`, in the right order

Statement order matters. Get it wrong and Gradle rejects the script before the plugin ever
loads:

```kotlin
// settings.gradle.kts

// 1. pluginManagement {} must be the first statement in the file.
pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
        // whatever else your project already declares
    }
}

// 2. The plugins {} block applying the plugin comes after pluginManagement {}.
plugins {
    id("dev.sajidali.compose-tvos") version "1.1.0"
}

// 3. The composeTvos {} extension block comes after the plugins {} block that applies it.
composeTvos {
    verbose.set(true)
}
```

`enableFeaturePreview(...)`, if you use it, can go on either side.

## 3. Check version lines before touching them

Don't reflexively bump a dependency's version to match the fork's exact published line. Check
whether the *official* artifact already ships a tvOS `klib` at the version your library already
uses first — [official-first resolution](how-it-works.md#official-first-resolution) means the
plugin will already resolve it correctly with no mapping needed, and bumping to a version that
doesn't exist upstream at all can break resolution for *every* platform, not just tvOS.

## 4. Verify resolution and compilation

```bash
./gradlew :your-module:dependencies --configuration tvosArm64CompileKlibraries
./gradlew :your-module:compileKotlinTvosArm64
```

The first command's output should show `dev.sajidali.*` coordinates (or genuine official tvOS
variants, for official-first cases) throughout your tvOS dependency graph, with no unresolved
modules. The second should compile clean against those `klib`s.

As a control, confirm iOS (or another non-tvOS target) is completely unaffected:

```bash
./gradlew :your-module:dependencies --configuration iosArm64CompileKlibraries
```

This configuration's output should contain zero `dev.sajidali` coordinates — the plugin only
ever acts on tvOS-targeting configurations.

## What consumers of your library then need

Applying this plugin in your own library's `settings.gradle.kts` only affects *your* build: it
lets your library's own tvOS compilation succeed by redirecting your build's dependency graph.
It does not rewrite your library's own published metadata — your published artifact's `.module`
file still declares its dependencies against the official `org.jetbrains.*` coordinates you wrote
in `dependencies {}`.

That means anyone who depends on your library from their own tvOS Kotlin Multiplatform project
still needs to apply `dev.sajidali.compose-tvos` in *their own* `settings.gradle.kts` too, so
that their build's dependency graph — which now transitively includes the same
`org.jetbrains.*` Compose/AndroidX coordinates your library depends on — gets redirected the
same way. A publish-time metadata rewrite (so a library could depend on `dev.sajidali.*`
coordinates directly, with no consumer-side plugin needed) is a possible future direction but is
not part of this plugin today.
