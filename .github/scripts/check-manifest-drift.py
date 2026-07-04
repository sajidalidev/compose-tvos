#!/usr/bin/env python3
"""Checks that every dev.sajidali coordinate implied by manifest/compose-tvos-versions.json
still resolves on Maven Central.

Run weekly (+ on manual dispatch, see .github/workflows/check-manifest.yml) to catch
manifest-vs-Central drift. This guards against the fork publishing a NEW version of an
artifact without the manifest being updated to match (or vice versa).

NOTE: this script -- and the workflow that runs it -- will fail until the Phase 5 Central
publication of the dev.sajidali.* fork artifacts has actually gone live (see
.superpowers/sdd/task-11-prep-report.md section 7). That is expected pre-publication.

For every mapping key, the checked (dev.sajidali) coordinate is derived by:
  - rewriting only the LEADING "org.jetbrains" segment of the group to "dev.sajidali"
    (mirrors TvosArtifactMapping.mapGroupId's root-only rewrite: everything after the root
    segment is left untouched), and
  - using the artifact named in the key if present ("group:artifact:versionPattern"),
    otherwise a REPRESENTATIVE_ARTIFACT for that group (below).
A "group:versionPattern" manifest key applies to every artifact under that group (there is no
single Central coordinate to check), so REPRESENTATIVE_ARTIFACT names ONE real, already-verified
published artifact per such group as a drift smoke test -- not an exhaustive per-artifact audit.
The mapping VALUE (not the key's requested-version pattern) is the version actually checked,
since that's the version the fork claims to publish.
"""
import json
import sys
import urllib.error
import urllib.request

MANIFEST_PATH = "manifest/compose-tvos-versions.json"
SOURCE_PREFIX = "org.jetbrains"
TARGET_PREFIX = "dev.sajidali"
CENTRAL_BASE = "https://repo1.maven.org/maven2"

# Sourced from the real published tree (.superpowers/sdd/task-8c-report.md /
# task-11-prep-report.md); extend this table if new group-only manifest entries are added.
REPRESENTATIVE_ARTIFACT = {
    "org.jetbrains.compose.material3": "material3",
    "org.jetbrains.androidx.lifecycle": "lifecycle-common",
    "org.jetbrains.androidx.savedstate": "savedstate",
}


def map_group(group: str) -> str:
    if group.startswith(SOURCE_PREFIX):
        return TARGET_PREFIX + group[len(SOURCE_PREFIX):]
    return group


def parse_key(key: str):
    """Returns (group, artifact_or_None) from a manifest key, stripping the trailing
    versionPattern segment. Keys are 'group:versionPattern', 'group:artifact:versionPattern',
    or wildcard-group variants like 'group.*:versionPattern' (the '.*' suffix is stripped);
    '*:versionPattern' (a bare wildcard covering every group) is skipped -- not a single
    Central coordinate."""
    parts = key.split(":")
    if parts[0] == "*":
        return "*", None
    group = parts[0]
    if group.endswith(".*"):
        group = group[:-2]
    if len(parts) == 3:
        return group, parts[1]
    return group, None


def head_ok(url: str) -> bool:
    req = urllib.request.Request(url, method="HEAD")
    try:
        with urllib.request.urlopen(req, timeout=30) as resp:
            return 200 <= resp.status < 300
    except (urllib.error.HTTPError, urllib.error.URLError):
        return False


def check_coordinate(group: str, artifact: str, version: str) -> list:
    group_path = group.replace(".", "/")
    base = f"{CENTRAL_BASE}/{group_path}/{artifact}/{version}/{artifact}-{version}"
    missing = []
    for ext in (".pom", ".module"):
        url = base + ext
        if not head_ok(url):
            missing.append(url)
    return missing


def main() -> int:
    with open(MANIFEST_PATH) as f:
        manifest = json.load(f)

    mappings = manifest.get("mappings", {})
    gradle_plugin_version = manifest.get("gradlePlugin")

    all_missing = {}

    for key, target_version in mappings.items():
        group, artifact = parse_key(key)
        if group == "*":
            print(f"SKIP  {key!r}: bare wildcard group, no single Central coordinate to check")
            continue
        mapped_group = map_group(group)
        resolved_artifact = artifact or REPRESENTATIVE_ARTIFACT.get(group)
        if resolved_artifact is None:
            print(
                f"SKIP  {key!r}: group-only entry with no REPRESENTATIVE_ARTIFACT table entry "
                f"for {group!r} -- add one to check-manifest-drift.py"
            )
            continue
        coordinate = f"{mapped_group}:{resolved_artifact}:{target_version}"
        missing = check_coordinate(mapped_group, resolved_artifact, target_version)
        if missing:
            all_missing[coordinate] = missing
        else:
            print(f"OK    {coordinate}")

    if gradle_plugin_version:
        coordinate = f"dev.sajidali.compose:compose-gradle-plugin:{gradle_plugin_version}"
        missing = check_coordinate("dev.sajidali.compose", "compose-gradle-plugin", gradle_plugin_version)
        if missing:
            all_missing[coordinate] = missing
        else:
            print(f"OK    {coordinate}")

    if all_missing:
        print("\nMANIFEST-VS-CENTRAL DRIFT DETECTED -- missing coordinates:", file=sys.stderr)
        for coordinate, urls in all_missing.items():
            print(f"  {coordinate}", file=sys.stderr)
            for url in urls:
                print(f"    missing: {url}", file=sys.stderr)
        return 1

    print("\nAll manifest-derived dev.sajidali coordinates resolve on Maven Central.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
