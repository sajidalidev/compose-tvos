# Compose tvOS

A Gradle plugin that adds tvOS support to JetBrains Compose Multiplatform projects.

## Overview

JetBrains Compose Multiplatform doesn't officially support tvOS. This plugin enables tvOS builds by injecting tvOS variants from alternative artifacts into the official JetBrains Compose modules.

**How it works:**
- Your iOS, Android, and Desktop targets continue using official JetBrains artifacts
- Only tvOS targets are redirected to `dev.sajidali.*` artifacts that include tvOS support
- No changes needed to your dependency declarations

## Installation

Add the plugin to your `settings.gradle.kts`:

```kotlin
plugins {
    id("dev.sajidali.compose-tvos") version "1.1.0"
}
```

That's it! The plugin automatically configures tvOS variant injection for all Compose Multiplatform dependencies.

## Requirements

- Gradle 8.0+
- Kotlin 2.0+
- JetBrains Compose Multiplatform 1.6+

## Configuration

The plugin works out of the box with sensible defaults. Optional configuration:

```kotlin
// settings.gradle.kts
plugins {
    id("dev.sajidali.compose-tvos") version "1.1.0"
}

composeTvos {
    // Enable verbose logging
    verbose.set(true)

    // Override target version for all redirected artifacts
    targetVersion.set("1.10.0")

    // Add custom version mappings
    versionMappings.put("org.jetbrains.compose.*:1.10.*", "1.10.0")

    // Add additional library groups to redirect
    additionalGroups.put("io.insert-koin", "dev.sajidali.koin")

    // Add specific artifact mappings
    additionalArtifacts.put(
        "io.coil-kt.coil3:coil-compose",
        "dev.sajidali.coil3:coil-compose"
    )
}
```

## Supported Modules

The plugin automatically handles these JetBrains Compose modules:

- `org.jetbrains.compose.ui`
- `org.jetbrains.compose.foundation`
- `org.jetbrains.compose.runtime`
- `org.jetbrains.compose.material`
- `org.jetbrains.compose.material3`
- `org.jetbrains.compose.animation`
- `org.jetbrains.compose.components`
- `org.jetbrains.androidx.navigation`

And these specific artifacts:

- `org.jetbrains.androidx.navigation:navigation-compose`
- `org.jetbrains.androidx.lifecycle:lifecycle-viewmodel-compose`
- `org.jetbrains.androidx.savedstate:savedstate-compose`

## Adding tvOS Target

In your shared module's `build.gradle.kts`:

```kotlin
kotlin {
    // Your existing targets
    androidTarget()
    iosArm64()
    iosSimulatorArm64()

    // Add tvOS targets
    tvosArm64()
    tvosSimulatorArm64()
    tvosX64()

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(compose.runtime)
                implementation(compose.foundation)
                implementation(compose.material3)
                // These automatically get tvOS variants injected
            }
        }
    }
}
```

## Version Mappings

The plugin includes default version mappings to ensure compatibility:

| Source Version Pattern | Target Version |
|------------------------|----------------|
| `org.jetbrains.compose.*:1.10.*` | `1.10.0` |
| `org.jetbrains.compose.material3:1.10.*` | `1.10.0-alpha05` |
| `org.jetbrains.androidx.lifecycle:2.9.*` | `2.10.0-alpha06` |
| `org.jetbrains.androidx.navigation:2.9.*` | `2.9.1` |

Override these with `versionMappings` if needed.

## Troubleshooting

### Artifacts not found

Ensure your repositories include Maven Central:

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}
```

### Version conflicts

Use `versionMappings` to align versions:

```kotlin
composeTvos {
    versionMappings.put("org.jetbrains.compose.*:1.10.*", "1.10.0")
}
```

### Verbose logging

Enable verbose mode to see what the plugin is doing:

```kotlin
composeTvos {
    verbose.set(true)
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