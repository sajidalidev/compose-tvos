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

    // Task 10b/10b-review fixed the runtime/foundation/ui-line variant-injection and
    // dependency-substitution ambiguity: TvosVariantInjectionRule and
    // ComposeTvosRedirectPlugin's dependencySubstitution now both check whether the OFFICIAL
    // artifact already ships a genuine native tvOS variant before touching a coordinate ("official-
    // first"), so compose.runtime/compose.foundation/compose.ui and, on this machine,
    // material3/androidx.lifecycle/androidx.savedstate (whichever already publish real tvOS
    // klibs upstream at the exact requested version) resolve straight through to their OWN
    // official coordinates -- no versionMappings entry, no dev.sajidali involvement at all.
    //
    // Reassessed (task-10c) against task-10-report.md's original 5-entry list: 3 entries were
    // dropped because they no longer do anything useful --
    //   - androidx.lifecycle 2.9.6->2.11.0 / androidx.savedstate 1.3.6->1.5.0-alpha01: both were
    //     pinned to a transitive version Gradle no longer selects (the graph now resolves
    //     androidx.lifecycle/androidx.savedstate to 2.11.0-rc01/1.4.0), AND -- verified directly
    //     against the live JetBrains dev-repo module metadata -- both groups genuinely publish
    //     real tvOS klib variants upstream at THOSE versions, so the official-first check would
    //     resolve them without a mapping at any version. Neither entry is needed on any machine.
    //
    // 2 entries remain, confirmed still required by direct live-repo checks (not just this
    // machine's mavenLocal state) -- these are the actual Phase 5 manifest deliverable:
    //   - org.jetbrains.compose.material3:material3:1.11.0-alpha07 has NO tvOS variant on the
    //     real JetBrains dev repo (23 variants, none tvOS); dev.sajidali only republished
    //     material3 at 1.5.0-alpha22, a different version line entirely (material3 tracks its
    //     own alpha line, independent of compose.version). Without this mapping, resolution
    //     fails outright on a clean machine (this dev machine's mavenLocal happens to carry a
    //     stale, superseded org.jetbrains.compose.material3:1.11.0-alpha07 publish from an
    //     earlier phase that also has a tvOS variant, masking the gap locally -- see
    //     task-10-report.md §4.1 -- but that is machine-specific pollution, not a fact a clean
    //     consumer machine can rely on).
    //   - org.jetbrains.androidx.navigation:navigation-compose has NO tvOS variant upstream at
    //     ANY version (confirmed live, 23 variants none tvOS); dev.sajidali only republished it
    //     at 2.10.0-alpha05, while the latest real official release (used in commonMain so
    //     iOS/Android also resolve it) is 2.10.0-alpha02. Removing this mapping reproduces
    //     "Could not resolve org.jetbrains.androidx.navigation:navigation-compose:2.10.0-alpha02"
    //     immediately (verified during this reassessment).
    versionMappings.put("org.jetbrains.compose.material3:1.11.0-alpha07", "1.5.0-alpha22")
    versionMappings.put("org.jetbrains.androidx.navigation:navigation-compose:2.10.0-alpha02", "2.10.0-alpha05")
}

dependencyResolutionManagement {
    repositories {
        mavenLocal()
        google()
        mavenCentral()
        maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
    }
}
