// Kotlin/Compose version line: the dev.sajidali fork core (COMPOSE 1.12.0-beta01) was
// compiled with Kotlin/Native compiler 2.3.20 -- verified directly from klib manifests, e.g.:
//   unzip -p ~/.m2/repository/dev/sajidali/compose/ui/ui-tvosarm64/1.12.0-beta01/ui-tvosarm64-1.12.0-beta01.klib default/manifest
//     -> compiler_version=2.3.20, abi_version=2.3.0
// (the task brief's guessed "2.2.20" does not match; the fork's klibs require the KGP that
// emits this exact compiler_version/abi_version pair, so 2.3.20 is used here instead.)
plugins {
    kotlin("multiplatform") version "2.3.20"
    kotlin("plugin.compose") version "2.3.20"
    id("org.jetbrains.compose") version "1.12.0-beta01"
}

// `id("org.jetbrains.compose") version "1.12.0-beta01"` above is transparently intercepted
// by the composeTvos settings plugin's pluginManagement.resolutionStrategy.eachPlugin and
// substituted (useModule) to dev.sajidali.compose:compose-gradle-plugin:1.12.0-beta01 -- the
// same version, published in mavenLocal by Task 9a -- via the same-version convention (no
// manifest `gradlePlugin` override needed).

repositories {
    mavenLocal()
    google()
    mavenCentral()
    maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
}

kotlin {
    val tvosArm64 = tvosArm64()
    val tvosSimulatorArm64 = tvosSimulatorArm64()

    // Control target: untouched by the redirect plugin (no tvOS Kotlin target on this
    // consumer's classpath maps to it) -- proves iOS keeps resolving official JetBrains
    // artifacts only. NO tvosX64 anywhere, per brief.
    val iosArm64 = iosArm64()

    listOf(tvosArm64, tvosSimulatorArm64, iosArm64).forEach { target ->
        target.binaries.framework {
            baseName = "DemoKit"
            isStatic = true
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.components.resources)
            // Requested at the latest REAL, officially-published versions (verified against
            // Maven Central's maven-metadata.xml at investigation time) so iosArm64 -- which
            // never gets redirected -- can resolve them too; composeTvos.versionMappings in
            // settings.gradle.kts maps these to dev.sajidali's actual published tvOS-fork
            // versions (2.10.0-alpha05 / 2.11.0). The brief's originally suggested literal
            // versions (2.10.0-alpha05 / 2.11.0) do not exist upstream for ANY platform, so
            // requesting them directly would fail official resolution (including iOS) outright.
            implementation("org.jetbrains.androidx.navigation:navigation-compose:2.10.0-alpha02")
            implementation("org.jetbrains.androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0-rc01")
        }
    }
}

compose.resources {
    packageOfResClass = "demo.generated.resources"
}
