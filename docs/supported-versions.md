---
layout: page
title: Supported versions
nav_order: 3
permalink: /supported-versions.html
---

# Supported versions

## Covered groups

The plugin redirects these `org.jetbrains.*` groups to their `dev.sajidali.*` tvOS-fork twin
whenever a tvOS Kotlin target requests them ("covered" means: the plugin will look for a tvOS
`klib` for any artifact under these groups, official-first, falling back to the fork):

- `org.jetbrains.compose.ui`
- `org.jetbrains.compose.foundation`
- `org.jetbrains.compose.runtime`
- `org.jetbrains.compose.material`
- `org.jetbrains.compose.material3`
- `org.jetbrains.compose.animation`
- `org.jetbrains.compose.components`
- `org.jetbrains.compose.annotation-internal`
- `org.jetbrains.compose.collection-internal`
- `org.jetbrains.compose.material3.adaptive` (`adaptive`, `adaptive-layout`,
  `adaptive-navigation`, `adaptive-navigation3`) and `material3-adaptive-navigation-suite` —
  published by the fork, including `androidx.window:window-core`, which the fork now builds a
  real tvOS Kotlin/Native target for. No extra configuration needed: the same-version convention
  covers `adaptive`, and `window-core` arrives transitively through the fork's own module
  metadata.
- `org.jetbrains.androidx.navigation` (including the `navigation-compose` artifact)
- `org.jetbrains.androidx.lifecycle` (including `lifecycle-viewmodel-compose`)
- `org.jetbrains.androidx.savedstate` (including `savedstate-compose`)
- `org.jetbrains.androidx.navigationevent`
- `org.jetbrains.androidx.navigation3` (including `navigation3-ui`)

That's 15 groups in total. Add further groups/artifacts of your own with
`composeTvos.additionalGroups`/`additionalArtifacts` (see the
[configuration reference](how-it-works.md#configuration-reference)) — useful for third-party KMP
libraries that publish their own tvOS-less umbrella artifacts the same way JetBrains does. See
[Library authors](library-authors.md) if you maintain one of those libraries yourself.

## Version matrix

The fork republishes upstream Compose Multiplatform/AndroidX libraries under `dev.sajidali.*`
with tvOS `klib`s added. Versions currently published (verify against the
[manifest](https://github.com/sajidalidev/compose-tvos/blob/main/manifest/compose-tvos-versions.json)
or your own `versionMappings` for anything not covered by the same-version convention):

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
| `compose.material3.adaptive` (`adaptive`, `adaptive-layout`, `adaptive-navigation`, `adaptive-navigation3`) | `1.3.0-beta02` |
| `material3-adaptive-navigation-suite` | `1.5.0-alpha22` |
| `androidx.window.window-core` (transitive dependency of `material3.adaptive`) | `1.6.0-alpha02` |

## How older Compose lines map via the manifest

Most groups above follow the **same-version convention**: if you request
`org.jetbrains.compose.foundation:1.12.0-beta01`, the plugin looks for
`dev.sajidali.compose.foundation:foundation-tvosarm64:1.12.0-beta01` — same version string, no
configuration needed.

`compose.material3` and `androidx.navigation` (and a few older `androidx.lifecycle`/
`androidx.savedstate` version lines) are exceptions: the fork tracks its own version line for
these, or hasn't republished every historical upstream version. Those exceptions are resolved
through the version-mapping manifest, whose `mappings` object keys can be:

- `*:versionPattern` — matches any group
- `group.*:versionPattern` — matches a group prefix
- `group:versionPattern` — an exact group at a version pattern (e.g. `2.9.*`, `2.11.0-beta01`)
- `group:artifact:versionPattern` — an exact group and artifact

For example, the manifest currently maps:

```json
{
  "mappings": {
    "org.jetbrains.compose.material3:1.11.0-alpha07": "1.5.0-alpha22",
    "org.jetbrains.androidx.navigation:navigation-compose:2.10.0-alpha02": "2.10.0-alpha05",
    "org.jetbrains.androidx.lifecycle:2.9.*": "2.11.0",
    "org.jetbrains.androidx.lifecycle:2.11.0-beta01": "2.11.0",
    "org.jetbrains.androidx.savedstate:1.3.*": "1.5.0-alpha01",
    "org.jetbrains.androidx.savedstate:1.4.*": "1.5.0-alpha01"
  },
  "gradlePlugin": "1.12.0-beta01"
}
```

If you're requesting an official version not covered by any mapping and not covered by the
same-version convention, add your own entry to `composeTvos.versionMappings` — it always wins
over the manifest on key collision — or check whether the official artifact already ships a
genuine tvOS `klib` at that version (in which case the plugin needs no mapping at all — see
[official-first resolution](how-it-works.md#official-first-resolution)).

## Not (yet) supported

- `tvosX64` (the legacy Intel tvOS simulator target) is not built by the fork and not redirected
  by this plugin — only `tvosArm64` and `tvosSimulatorArm64` are covered.
- Everything else under the covered groups above is currently supported, including
  `compose.material3.adaptive` and `androidx.window:window-core` — there are no outstanding
  exclusions in the covered set as of this writing.
