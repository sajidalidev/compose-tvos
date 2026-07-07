# Compose tvOS Redirect — Roadmap

## Current state (branch `production-hardening-1.1.0`)

Phases 1–4 of the production-hardening effort are complete on this branch (9+ commits,
`8ba9713..2823433`). What actually shipped, in order:

- **Manifest + version resolution rewritten**: the version-override manifest is now fetched as
  real JSON (kotlinx-serialization) into a versioned on-disk cache (`compose-tvos-redirect-cache-v3`,
  respecting `GRADLE_USER_HOME`), with deterministic six-tier version-pattern resolution and a
  documented specificity/lexicographic tie-break rule.
- **Class-based, cacheable `@CacheableRule` metadata rule** replacing the earlier ad hoc rule
  wiring — configuration-cache friendly, with `--offline` support (cache-only, degrades to empty
  rather than failing) and HTTP redirect-following.
- **End-of-build diagnostics** (`strictMode`, the lifecycle summary line, the WARN block, and
  conflict-resolution-loser suppression so the WARN block doesn't drown in pre-conflict-resolution
  candidate-version noise).
- **Redirect coverage expanded from 8 to 15 groups**: `ui`, `foundation`, `runtime`, `material`,
  `material3`, `animation`, `components`, `annotation-internal`, `collection-internal`,
  `material3.adaptive`, `navigation`, `lifecycle`, `savedstate`, `navigationevent`, `navigation3`.
- **`material3.adaptive` now published**: the fork builds a real tvOS Kotlin/Native target for
  `androidx.window:window-core` and republishes it alongside `adaptive`/`adaptive-layout`/
  `adaptive-navigation`/`adaptive-navigation3`/`material3-adaptive-navigation-suite` — the
  previous window-core gap is closed, no manifest mapping or consumer-side config needed
  (same-version convention; `window-core` arrives transitively through the fork's own module
  metadata).
- **Official-first resolution**: both the metadata-rule injection path and the project-level
  dependency-substitution path check whether the *official* JetBrains artifact already ships a
  genuine tvOS `klib` before ever introducing a `dev.sajidali` coordinate — this is what unblocked
  `org.jetbrains.compose.runtime` (which started shipping real tvOS variants upstream) without
  creating a duplicate-variant Gradle ambiguity.
- **`org.jetbrains.compose` plugin-marker interception**: substitutes the tvOS-patched
  `dev.sajidali.compose:compose-gradle-plugin` fork transparently, with an opt-out
  (`interceptComposeGradlePlugin.set(false)`) and preserved Gradle Plugin Portal fallback for
  consumers who declare no `pluginManagement.repositories` of their own.
  The standalone `dev.sajidali.compose-tvos-project` plugin id is retired (deprecated on the
  Portal, no longer published).
- **GradleTestKit functional test harness** covering end-to-end plugin behavior against a real
  Gradle invocation, with a content-derived `-ft-<hash>` plugin version so republished test bytes
  can't be masked by TestKit's own module cache.
- **`demo/`** rewritten as the canonical tvOS Compose Multiplatform E2E consumer: real
  `compose.runtime`/`foundation`/`material3`/`components.resources`, `navigation-compose`,
  `lifecycle-viewmodel-compose`, tvOS + iOS targets. Verified end-to-end against the published
  `dev.sajidali` fork (task-10c): compile + link for `tvosArm64`/`tvosSimulatorArm64`, zero
  `dev.sajidali` leakage into `iosArm64`, Compose Resources packaging exercised through to a
  linked framework.
- Fork repo (`compose-multiplatform-core`, branch `tvos-publishing`): the coordinate-root rewrite,
  the `compose.platforms` build wiring, closure-audit script
  (`scripts/audit-tvos-closure.py`), and a full, clean (`exit 0`, zero FAIL/UNKNOWN) publish of
  every covered group, including `material3.adaptive` and `window-core`, into `mavenLocal`.

## Shipped: Maven Central publication (2026-07-06/07)

The entire ecosystem is published under `dev.sajidali.*` on Maven Central and verified from
public repositories on a clean machine: core fork libraries, `components-resources`,
`compose-gradle-plugin`, the `dev.sajidali.compose-tvos` plugin v1.1.0 (with its plugin
marker), and — as of 2026-07-07 — the `material3.adaptive` family plus `window-core`. The
live version manifest is served from this repo's `main` branch. A demo app was run on an
Apple TV simulator with Compose resources rendering (see the docs site's app-embedding page).

## Near-term

- ~~**Gradle Plugin Portal listing**~~ — approved and live (2026-07-07):
  https://plugins.gradle.org/plugin/dev.sajidali.compose-tvos — the quickstart is now a single
  `plugins {}` line with zero `pluginManagement` configuration.
- **Release cadence policy**: publish one fork line per Compose *stable* release; map
  intermediate requested versions onto it via the remote manifest. Rationale: Central Portal
  monthly publishing quotas (a full core publish is roughly one month's budget) — a quota
  increase request to Sonatype is worth filing regardless.
- Merge `tvos-publishing` → `tvos-main` in both fork repos.

**CI (workflows authored, go live with the repo's `main` branch)**:
- Plugin repo `.github/workflows/ci.yml`: ubuntu (build + unit + functional tests + manifest
  JSON-schema validation), macos (demo E2E against Central — doubles as an upstream-regression
  canary).
- `check-manifest.yml`: scheduled job asserting every manifest mapping's target actually resolves
  on Central.
- `release.yml`: tag-triggered publish.
- Fork repo: publish runbook documented as a skill (AOSP-scale build → manual dispatch), with the
  closure audit as a mandatory gate before any publish is considered complete.

**Should-fix-later** (parked as minors through Phase 1–4 review, not release-blocking, batch
before/around the 1.1.0 tag):
- Success-path diagnostics lifecycle line (`"Injected tvOS variants into N module(s)..."`) is
  deliberately *not* gated on tvOS-target detection (an actual injection is treated as real
  signal, never spurious noise, per `DiagnosticsSummary`'s own KDoc) — this is an intentional,
  documented deviation from the original review suggestion, not an oversight; revisit only if it
  turns out to be noisy for non-tvOS multi-module builds where exactly one module happens to
  declare a tvOS target.
- No dedicated functional-test fixture for the HTTP redirect-following logic in
  `TvosVariantDiscovery.fetchFromHttpUrl` (read-verified, not test-covered).
- Tier-3 group-pattern version-mapping tie-break is version-pattern-specificity only — a
  less-specific `group.*` pattern can still win over a more-specific one on lexicographic key
  ordering when their version patterns tie. In-spec and documented, but an authoring gotcha for
  anyone writing overlapping `additionalGroups`/manifest entries.
- The functional-test `-ft-<hash>` plugin-version hash covers `src/main` only; `build.gradle.kts`/
  `libs.versions.toml` changes don't bust the test coordinate.
- Diagnostics WARN block cannot reach absolute zero on a graph with any losing candidate versions
  that never get their own injection/skip record anywhere (task-10c/10d gate (e)): a module that
  legitimately never resolves at any version still (correctly) warns; this is not fixable without
  restructuring `TvosVariantInjectionRule` to defer reporting until after conflict resolution,
  which the current `ComponentMetadataRule` API doesn't offer a hook for.
- `-Pcompose.platforms` in the fork is additive, not exclusive: every `dev.sajidali` umbrella
  module still carries variants for every default JetBrains target (android/desktop/ios/js/macos/
  wasmJs) in addition to tvOS. Harmless for tvOS consumers (this plugin only ever redirects tvOS
  configurations) but means `dev.sajidali` artifacts are not "tvOS-only" — documented, not
  currently worth the build-logic change to restrict (see `stage-central-bundle.sh`'s header for
  the explicit policy decision).

## Later

**Library-author publish-time rewrite** (formerly "Option B" in this file's earlier draft):
instead of (or in addition to) this plugin's build-time redirect, a companion *publishing* plugin
that lets third-party KMP library authors rewrite their own tvOS variant metadata to depend on
`dev.sajidali.compose.*` directly at publish time — so `io.coil-kt:coil-compose-tvosarm64` (for
example) could depend on `dev.sajidali.compose.ui:ui-tvosarm64` instead of a
never-published-for-tvOS `org.jetbrains.compose.ui:ui-tvosarm64`. This is real ecosystem-growth
work (reaching out to library maintainers, a documented publish-time integration guide) and is
explicitly out of scope until this plugin's consumer-side story is fully shipped and stable on
Central.
