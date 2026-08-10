#!/usr/bin/env python3
"""
lint_manifest.py — Validates patches/MANIFEST.toml schema and cross-references.

Checks:
  1. TOML parses without error
  2. Every [[patch]] has all required fields
  3. IDs are unique and match pattern (P-NN | U-NN)
  4. depends_on references resolve to existing patch IDs
  5. file paths exist on disk (relative to repo root)
  6. line numbers are positive integers
  7. affects is a list of strings
  8. status is one of: active | deprecated | documentation-only
  9. category is one of the 21 known categories

Exit code: 0 on success, 1 on any failure.
"""
from __future__ import annotations

import re
import sys
import os
from pathlib import Path

try:
    import tomllib  # Python 3.11+
except ImportError:
    print("ERROR: requires Python 3.11+ (tomllib builtin). Found:", sys.version)
    sys.exit(2)

REPO_ROOT = Path(__file__).resolve().parent.parent
MANIFEST = REPO_ROOT / "patches" / "MANIFEST.toml"

REQUIRED_FIELDS = ("id", "title", "category", "version", "file", "line", "affects", "depends_on", "status")
VALID_STATUS = {"active", "deprecated", "documentation-only", "dispatcher"}
ID_PATTERN = re.compile(r"^(P|U)-\d{2}$")

VALID_CATEGORIES = {
    "CORE_TONE_MAPPING", "MULTI_FRAME_HDR", "METRICS_EXPORT", "PER_LENS",
    "WIRING", "RUNTIME_ACTIVATION", "UI", "RUNTIME_NLM", "COLOR_SCIENCE",
    "VIDEO", "CAMERA2_PARAMS", "BUILD_FIXES", "CAPTURE_MODES", "UI_LUT",
    "PHOTO_QUALITY", "COMPOSITION", "RAW_ON_DEMAND", "BUILD_OPTIMIZATION",
    "NATURAL_BASELINE", "NIGHT_DENOISE", "V6_5_UPGRADES",
}


def color(msg: str, c: str) -> str:
    if not sys.stdout.isatty():
        return msg
    return f"\033[{c}m{msg}\033[0m"


def ok(msg: str) -> None:
    print(f"  {color('✓', '32')} {msg}")


def fail(msg: str) -> None:
    print(f"  {color('✗', '31')} {msg}")


def warn(msg: str) -> None:
    print(f"  {color('!', '33')} {msg}")


def main() -> int:
    if not MANIFEST.exists():
        fail(f"MANIFEST.toml not found at {MANIFEST}")
        return 1

    errors: list[str] = []
    warnings: list[str] = []

    # ── 1. Parse TOML ──────────────────────────────────────────────────────
    try:
        with MANIFEST.open("rb") as f:
            data = tomllib.load(f)
    except tomllib.TOMLDecodeError as e:
        fail(f"TOML parse error: {e}")
        return 1

    patches = data.get("patch", [])
    if not patches:
        fail("MANIFEST.toml has no [[patch]] entries")
        return 1

    ok(f"TOML parses — {len(patches)} [[patch]] entries found")

    # ── 2. Required fields + per-entry validation ──────────────────────────
    seen_ids: set[str] = set()
    id_to_entry: dict[str, dict] = {}

    for i, p in enumerate(patches):
        ctx = f"entry #{i}"
        missing = [f for f in REQUIRED_FIELDS if f not in p]
        if missing:
            errors.append(f"{ctx}: missing fields {missing}")
            continue

        pid = p["id"]
        ctx = f"{pid}"

        # 3. ID uniqueness + pattern
        if pid in seen_ids:
            errors.append(f"{ctx}: duplicate id")
        seen_ids.add(pid)
        id_to_entry[pid] = p

        if not ID_PATTERN.match(pid):
            errors.append(f"{ctx}: id does not match pattern P-NN or U-NN")

        # 4. depends_on is list of strings
        deps = p["depends_on"]
        if not isinstance(deps, list) or not all(isinstance(d, str) for d in deps):
            errors.append(f"{ctx}: depends_on must be list of strings, got {type(deps).__name__}")

        # 5. affects is list of strings
        affects = p["affects"]
        if not isinstance(affects, list) or not all(isinstance(a, str) for a in affects):
            errors.append(f"{ctx}: affects must be list of strings")

        # 6. line is positive int
        line = p["line"]
        if not isinstance(line, int) or line < 1:
            errors.append(f"{ctx}: line must be positive integer, got {line!r}")

        # 7. status valid
        if p["status"] not in VALID_STATUS:
            errors.append(f"{ctx}: status {p['status']!r} not in {sorted(VALID_STATUS)}")

        # 8. category valid
        if p["category"] not in VALID_CATEGORIES:
            errors.append(f"{ctx}: category {p['category']!r} not in known categories")

        # 9. title non-empty
        if not isinstance(p["title"], str) or not p["title"].strip():
            errors.append(f"{ctx}: title must be non-empty string")

        # 10. version looks like semver-ish
        if not isinstance(p["version"], str) or not re.match(r"^\d+\.\d+\.\d+", p["version"]):
            warnings.append(f"{ctx}: version {p['version']!r} does not look like semver")

    ok(f"Per-entry schema validated for {len(patches)} patches")

    # ── 3. depends_on references resolve ───────────────────────────────────
    for pid, p in id_to_entry.items():
        for dep in p["depends_on"]:
            if dep not in seen_ids:
                errors.append(f"{pid}: depends_on {dep!r} does not exist in manifest")

    ok("depends_on cross-references resolved")

    # ── 4. file paths exist ────────────────────────────────────────────────
    for pid, p in id_to_entry.items():
        fpath = REPO_ROOT / p["file"]
        if not fpath.exists():
            errors.append(f"{pid}: file {p['file']!r} not found at {fpath}")

    ok("file paths verified on disk")

    # ── 5. ID sequence sanity (P-1..P-78, U-01..U-06) ──────────────────────
    p_ids = sorted([pid for pid in seen_ids if pid.startswith("P-")],
                   key=lambda x: int(x.split("-")[1]))
    u_ids = sorted([pid for pid in seen_ids if pid.startswith("U-")],
                   key=lambda x: int(x.split("-")[1]))

    expected_p = [f"P-{n:02d}" for n in range(1, 80)]  # P-01..P-79 (P-79 = dispatcher)
    missing_p = set(expected_p) - set(p_ids)
    extra_p = set(p_ids) - set(expected_p)
    if missing_p:
        errors.append(f"P- sequence gaps: {sorted(missing_p)}")
    if extra_p:
        warnings.append(f"P- sequence extras (unexpected): {sorted(extra_p)}")

    expected_u = [f"U-{n:02d}" for n in range(1, 7)]
    missing_u = set(expected_u) - set(u_ids)
    if missing_u:
        errors.append(f"U- sequence gaps: {sorted(missing_u)}")

    ok(f"ID sequence: {len(p_ids)} P-patches + {len(u_ids)} U-patches = {len(p_ids) + len(u_ids)} total")

    # ── Summary ────────────────────────────────────────────────────────────
    print()
    if errors:
        print(color(f"FAIL: {len(errors)} error(s)", "31"))
        for e in errors:
            fail(e)
        return 1

    if warnings:
        print(color(f"WARN: {len(warnings)} warning(s)", "33"))
        for w in warnings:
            warn(w)

    print(color(f"OK: MANIFEST.toml valid — {len(patches)} patches, 0 errors", "32"))
    return 0


if __name__ == "__main__":
    sys.exit(main())
