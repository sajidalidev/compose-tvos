rootProject.name = "demo"

// Consumes the plugin from THIS branch's source tree (composite build), not a published
// coordinate — the demo always tracks the branch-under-development. Real consumers instead
// declare `id("dev.sajidali.compose-tvos") version "1.1.0"` (see README) and resolve the
// plugin from mavenLocal/Central/the Gradle Plugin Portal; this project intentionally omits
// the version because includeBuild substitutes the plugin marker by group:module coordinate
// alone, ignoring any version string.
pluginManagement {
    includeBuild("..")

    repositories {
        mavenLocal()
        gradlePluginPortal()
        mavenCentral()
    }
}

plugins {
    id("dev.sajidali.compose-tvos")
}

composeTvos {
    verbose.set(true)

    // KNOWN BLOCKER (task-10-report.md): even with every version-mapping override below
    // applied, tvosArm64/tvosSimulatorArm64 dependency resolution still fails with an
    // unresolvable Gradle variant AMBIGUITY on org.jetbrains.compose.runtime:runtime:1.12.0-beta01
    // (and, in this dev machine's mavenLocal state, material3/lifecycle-viewmodel-compose too):
    // JetBrains' own dev-repo publish of runtime:1.12.0-beta01 already ships a native
    // 'tvosArm64ApiElements-published' variant, and TvosVariantInjectionRule unconditionally
    // adds a second, identically-attributed 'tvosArm64ApiElements-published-injected' variant
    // regardless of whether the source component already has one -- Gradle then cannot choose
    // between them for ANY consumer, and no consumer-side config (versionMappings included)
    // can fix that. This is a src/main defect (this task does not modify src/main); see the
    // report for the exact reproduction and recommended fix.

    // Verification-discovered version-mapping overrides (see task-10-report.md "Version
    // mapping gaps" section for the full investigation). The remote manifest is stale
    // relative to what is CURRENTLY published under dev.sajidali in mavenLocal; these local
    // overrides (documented, supported per README's "Version conflicts" troubleshooting
    // section, and applied on top of the manifest per composeTvos's own precedence rules)
    // realign the requested/official version each DSL accessor or dependency below actually
    // resolves to with the version dev.sajidali has actually published:
    //   - material3: DSL requests org.jetbrains.compose.material3:material3:1.11.0-alpha07
    //     (material3 tracks its own alpha version line, independent of compose.version);
    //     dev.sajidali only republished material3 at 1.5.0-alpha22 (Task 2/3).
    //   - androidx.lifecycle: the fork's own ui-tvosarm64 POM transitively requests
    //     lifecycle-runtime-compose/lifecycle-viewmodel/lifecycle-viewmodel-savedstate at
    //     2.9.6 (inherited from whatever official ui release these were built against);
    //     dev.sajidali only republished the androidx.lifecycle line at 2.11.0.
    //   - androidx.savedstate: same story, transitively requested at 1.3.6; dev.sajidali
    //     only republished at 1.5.0-alpha01.
    versionMappings.put("org.jetbrains.compose.material3:1.11.0-alpha07", "1.5.0-alpha22")
    versionMappings.put("org.jetbrains.androidx.lifecycle:2.9.6", "2.11.0")
    versionMappings.put("org.jetbrains.androidx.savedstate:1.3.6", "1.5.0-alpha01")
    // navigation-compose / lifecycle-viewmodel-compose below are declared in commonMain at
    // real, officially-published versions (so iOS/Android also resolve them); these two
    // artifact-exact overrides realign them to dev.sajidali's actual published versions
    // (2.10.0-alpha05 / 2.11.0) -- see build.gradle.kts.
    versionMappings.put("org.jetbrains.androidx.navigation:navigation-compose:2.10.0-alpha02", "2.10.0-alpha05")
    versionMappings.put("org.jetbrains.androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0-rc01", "2.11.0")
}

dependencyResolutionManagement {
    repositories {
        mavenLocal()
        google()
        mavenCentral()
        maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
    }
}
