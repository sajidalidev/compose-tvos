---
layout: page
title: App embedding
nav_order: 4
permalink: /app-embedding.html
---

# Building and running your tvOS app

Building your KMP module produces a Kotlin framework per tvOS target (e.g.
`:linkDebugFrameworkTvosSimulatorArm64` → `<module>.framework`), which exposes your
`UIViewController`-returning entry point (e.g. `ComposeUIViewController { App() }`) to Swift/
Objective-C.

## Static vs. dynamic framework linking

- A **static framework** (`isStatic = true`) links directly into the app executable with no
  separate embedding step. `otool -L` on the resulting executable shows no dependency on the
  framework's own dylib — its object code is fully linked into the app binary.
- A **dynamic framework** needs the usual `Frameworks/` embedding step in your app bundle/Xcode
  project.

## Xcode vs. hand-assembled bundles

Most consumers link their framework into an Xcode project the normal way (add the `.framework`
as a dependency, embed if dynamic). If you need to build a `.app` bundle without an Xcode
project (for example, CI smoke tests or custom tooling), you can compile directly against the
framework:

```bash
swiftc -parse-as-library -target arm64-apple-tvos<sdk-version>-simulator \
  -sdk "$(xcrun --sdk appletvsimulator --show-sdk-path)" \
  -F <path-to>/debugFramework \
  -framework <YourFrameworkName> \
  -o YourApp.app/YourApp \
  YourEntryPoint.swift
```

A few things worth knowing if you go this route:

- Match the `-target` SDK version to the simulator runtime you're compiling against exactly, or
  the linker emits a page of "was built for newer 'tvOS-simulator' version than being linked"
  warnings for Compose's own Objective-C interop shims.
- If your entry-point file uses Swift's `@main` attribute, it can't be named `main.swift` (that
  combination is a hard compiler error: `'main' attribute cannot be used in a module that
  contains top-level code`) — name it something else and pass `-parse-as-library` to `swiftc`.
- `Info.plist` needs `UIDeviceFamily` set to `[3]` (tvOS) and an (even empty) `UILaunchScreen`
  dictionary — modern tvOS/iOS crashes on launch for bundles with no storyboard and no
  `UILaunchScreen` key at all.

## Compose Resources bundle layout

This is the part most likely to trip you up, and it isn't obvious from the assembled directory
name alone.

Gradle assembles resources per target under
`build/generated/compose/resourceGenerator/assembledResources/<target>Main/composeResources/`
(camelCase, no hyphen) — and the tvOS resource reader looks for them at runtime nested **one
level inside** a hyphenated `compose-resources/` wrapper directory at the app bundle root. The
assembled `composeResources/` tree itself is copied in as-is — not renamed, not flattened.

For example, an app bundled as `Demo.app` with a `demo.generated.resources` package must end up
with:

```
Demo.app/compose-resources/composeResources/demo.generated.resources/drawable/logo.xml
Demo.app/compose-resources/composeResources/demo.generated.resources/values/strings.commonMain.cvr
```

i.e. copy the assembled `composeResources/` directory as-is *into* a `compose-resources/`
directory you create at the bundle root:

```bash
mkdir -p Demo.app/compose-resources
cp -R build/generated/compose/resourceGenerator/assembledResources/tvosSimulatorArm64Main/composeResources \
      Demo.app/compose-resources/
```

Hand-assembled bundles (e.g. CI smoke tests or tooling that builds a `.app` without an Xcode
project) must replicate this layout explicitly; a naive copy/rename produces a black screen or a
missing-resource exception. Xcode projects that run the Compose Resources Gradle task as a build
phase handle this nesting for you automatically.

## Simulator run notes

```bash
xcrun simctl install booted YourApp.app
xcrun simctl launch booted <your-bundle-id>
xcrun simctl io booted screenshot output.png
```

If you attach with `xcrun simctl launch --console-pty` to read console output and then kill that
session, the app is killed along with it (`--console-pty` ties the launched process's lifetime to
the console connection). Use a plain `simctl launch` (no console attachment) for anything you
intend to leave running or screenshot afterward, and `log show`/`log stream` separately if you
need console output.

This full pipeline — static-framework linking, hand-assembled bundle, Compose Resources nesting,
install, and launch — has been verified end-to-end on an Apple TV simulator: Material3 styling,
a Compose Resources drawable and string resource, and `lifecycle-viewmodel-compose` state all
render correctly with no crash, no missing-resource exception, and no black screen.
