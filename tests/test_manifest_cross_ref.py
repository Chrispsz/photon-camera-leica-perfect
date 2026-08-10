#!/usr/bin/env python3
"""
test_manifest_cross_ref.py — Cross-reference MANIFEST.toml entries against
the actual `section "P-NN:` / `substep "P-NN:` titles in build-archlinux.sh
and the `def apply_u0N(` functions in apply_upgrades_v65.py.

Ensures the manifest stays in sync with the code as patches are added.

Exit code: 0 on success, 1 on any mismatch.
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

try:
    import tomllib
except ImportError:
    print("ERROR: requires Python 3.11+ (tomllib builtin)")
    sys.exit(2)

REPO_ROOT = Path(__file__).resolve().parent.parent
MANIFEST = REPO_ROOT / "patches" / "MANIFEST.toml"
BUILD_SH = REPO_ROOT / "build-archlinux.sh"
UPGRADES_PY = REPO_ROOT / "patches" / "apply_upgrades_v65.py"

# Matches: section "P-1: ..." OR substep "P-1: ..." (1-2 digit, optional sub-letter a-z)
# Captures the base P-NN id (sub-letter like P-32a is normalized to P-32).
SECTION_RE = re.compile(r'^\s*(?:section|substep)\s+"((?:P|U)-\d{1,2})([a-z]?):\s*([^"]+)"', re.MULTILINE)
# Matches: def apply_u01(
DEF_RE = re.compile(r'^def\s+(apply_u\d{2})\s*\(', re.MULTILINE)


def color(msg: str, c: str) -> str:
    if not sys.stdout.isatty():
        return msg
    return f"\033[{c}m{msg}\033[0m"


def ok(msg: str) -> None:
    print(f"  {color('✓', '32')} {msg}")


def fail(msg: str) -> None:
    print(f"  {color('✗', '31')} {msg}")


def main() -> int:
    errors: list[str] = []

    # ── 1. Load manifest IDs ───────────────────────────────────────────────
    with MANIFEST.open("rb") as f:
        data = tomllib.load(f)
    manifest_ids = {p["id"] for p in data.get("patch", [])}

    # ── 2. Parse build-archlinux.sh section titles ─────────────────────────
    build_src = BUILD_SH.read_text()
    sections = SECTION_RE.findall(build_src)

    # Normalize IDs to 2-digit (P-1 → P-01) for comparison; sub-letters (P-32a → P-32)
    def normalize_id(pid: str) -> str:
        prefix, num = pid.split("-")
        return f"{prefix}-{int(num):02d}"

    code_ids: set[str] = set()
    for pid, _subletter, _title in sections:
        code_ids.add(normalize_id(pid))

    # ── 3. Parse apply_upgrades_v65.py def names ───────────────────────────
    upg_src = UPGRADES_PY.read_text()
    defs = DEF_RE.findall(upg_src)
    for d in defs:
        # apply_u01 → U-01
        num = d.split("_u")[1]
        code_ids.add(f"U-{num}")

    ok(f"build-archlinux.sh: {len(sections)} section/substep titles")
    ok(f"apply_upgrades_v65.py: {len(defs)} apply_u* functions")
    ok(f"MANIFEST.toml: {len(manifest_ids)} [[patch]] entries")

    # ── 4. Compare ─────────────────────────────────────────────────────────
    in_code_not_manifest = code_ids - manifest_ids
    in_manifest_not_code = manifest_ids - code_ids

    if in_code_not_manifest:
        for pid in sorted(in_code_not_manifest):
            errors.append(f"{pid}: found in code but MISSING from MANIFEST.toml")

    if in_manifest_not_code:
        for pid in sorted(in_manifest_not_code):
            errors.append(f"{pid}: declared in MANIFEST.toml but NOT found in code (stale entry?)")

    print()
    if errors:
        print(color(f"FAIL: {len(errors)} mismatch(es)", "31"))
        for e in errors:
            fail(e)
        return 1

    print(color(f"OK: manifest and code in sync — {len(manifest_ids)} patches matched", "32"))
    return 0


if __name__ == "__main__":
    sys.exit(main())
