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

// task-10c: this dev machine's mavenLocal carries a stale, complete org.jetbrains.compose.*
// publish at 1.11.0 left over from an earlier phase (predating the dev.sajidali-fork-plus-
// injection design; see task-10-report.md §4.1/§7.3). material3:1.11.0-alpha07's own POM
// transitively requests org.jetbrains.compose.{ui,foundation-layout,animation,...} at 1.11.0,
// and because that STALE local 1.11.0 publish also genuinely carries real tvOS klibs, the
// official-first check (correctly) leaves it unsubstituted -- while runtime/foundation's OWN
// direct request for the SAME modules at 1.12.0-beta01 (no official tvOS variant at that
// version) correctly redirects to the dev.sajidali fork. Two different module coordinates
// (org.jetbrains.compose.ui:ui-tvosarm64 vs dev.sajidali.compose.ui:ui-tvosarm64) for the
// same underlying Kotlin classes then both end up in the same tvOS link graph, and the
// Kotlin/Native linker fails with a duplicate-symbol error ("IrPropertySymbolImpl is already
// bound") for classes both klibs define (e.g. AbsoluteAlignment).
//
// This is NOT the components-resources 1.11.0/1.12.0-beta01 ABI risk flagged going into this
// task (that one never reproduced) and is not expected to reproduce on a clean machine: on a
// clean mavenLocal, material3:1.11.0-alpha07 genuinely has no official tvOS variant (verified
// directly against the live JetBrains dev repo -- 23 variants, none tvOS), so it would redirect
// via composeTvos.versionMappings (settings.gradle.kts) to the dev.sajidali fork uniformly,
// with no org.jetbrains.compose.ui:1.11.0 edge ever entering the graph.
//
// The fix is scoped narrowly, twice over:
//  1. Only the exact modules observed on BOTH sides of the duplicate (requested at 1.11.0 via
//     material3's transitive graph AND at 1.12.0-beta01 via runtime/foundation's direct graph)
//     are forced. org.jetbrains.compose.material3 itself is excluded (tracks its own alpha
//     line, handled by versionMappings). org.jetbrains.compose.collection-internal /
//     annotation-internal are excluded because dev.sajidali never republishes either at ANY
//     version at all (verified: no dev/sajidali/compose/{collection,annotation}-internal
//     directory exists in mavenLocal), and the official artifact already ships its own genuine
//     tvosArm64/tvosSimulatorArm64 klibs locally, so forcing them would only break their
//     already-working official-first resolution.
//     org.jetbrains.compose.material:material-ripple is excluded for a DIFFERENT reason (task-
//     10d correction -- the claim previously here, "no dev.sajidali publish at 1.12.0-beta01,
//     confirmed 404 on the live JetBrains dev repo", was WRONG on both counts: dev.sajidali DOES
//     publish material-ripple:1.12.0-beta01 -- present in mavenLocal -- and the OFFICIAL
//     org.jetbrains.compose.material:material-ripple:1.12.0-beta01 coordinate genuinely exists
//     on the live JetBrains dev repo too (confirmed directly via curl against its
//     maven-metadata.xml and .module file), though that live artifact ships no tvOS variant at
//     all. The real, verified reason material-ripple is excluded: unlike ui/foundation-layout/
//     animation, it is requested ONLY via material3's transitive graph at 1.11.0, with no
//     second, direct edge anywhere in this demo's graph requesting it at 1.12.0-beta01 -- so
//     there is no actual duplicate-symbol collision here for forcing to fix. Re-verified
//     directly (task-10d): temporarily adding "material-ripple" to this set and re-running
//     gates (a)-(c) resolves and links cleanly with no duplicate-klib error, confirming forcing
//     it would be harmless but pointless (not resolution-breaking, as previously claimed) --
//     left out simply because there is nothing here for it to fix.
//  2. Only configurations whose name contains "tvos" are touched, so iosArm64 (gate (d)'s
//     "zero dev.sajidali, untouched" control target) never sees this override at all.
//
// TEMPORARY dev-machine-pollution guard -- remove once Phase 5's clean-machine verification
// passes without it (no-op on clean mavenLocal by design).
val duplicateKlibRiskModules = setOf(
    "ui", "ui-uikit", "ui-backhandler", "ui-text", "ui-util", "ui-graphics", "ui-unit", "ui-geometry",
    "animation", "animation-core", "foundation-layout"
)
configurations.matching { it.name.contains("tvos", ignoreCase = true) }.configureEach {
    resolutionStrategy.eachDependency {
        if (requested.group.startsWith("org.jetbrains.compose.") && requested.name in duplicateKlibRiskModules) {
            useVersion("1.12.0-beta01")
            because("align the compose.ui/foundation-layout/animation family to a single " +
                "version to avoid linking both an official (mavenLocal-pollution) and a " +
                "dev.sajidali klib for the same class, tvOS targets only")
        }
    }
}
