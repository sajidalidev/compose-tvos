#!/usr/bin/env python3
"""Fast, network-free structural validation of manifest/compose-tvos-versions.json.

Run on every CI build (see .github/workflows/ci.yml) -- distinct from
.github/scripts/check-manifest-drift.py's (weekly, network-dependent) live Maven Central
resolution check.

Checks:
  - the file is valid JSON
  - a "schema" field is present
  - a "mappings" field is present and is a JSON object
  - no key in "mappings" is empty/blank
"""
import json
import sys

MANIFEST_PATH = "manifest/compose-tvos-versions.json"


def main() -> int:
    with open(MANIFEST_PATH) as f:
        try:
            manifest = json.load(f)
        except json.JSONDecodeError as e:
            print(f"FAIL: {MANIFEST_PATH} is not valid JSON: {e}", file=sys.stderr)
            return 1

    errors = []
    if "schema" not in manifest:
        errors.append("missing required 'schema' field")

    mappings = manifest.get("mappings")
    if "mappings" not in manifest:
        errors.append("missing required 'mappings' field")
    elif not isinstance(mappings, dict):
        errors.append("'mappings' must be a JSON object")
    else:
        for key in mappings:
            if not key or not key.strip():
                errors.append("'mappings' contains an empty key")

    if errors:
        print(f"FAIL: {MANIFEST_PATH} failed validation:", file=sys.stderr)
        for e in errors:
            print(f"  - {e}", file=sys.stderr)
        return 1

    print(
        f"OK: {MANIFEST_PATH} is valid "
        f"(schema={manifest['schema']}, {len(mappings)} mapping(s))"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
