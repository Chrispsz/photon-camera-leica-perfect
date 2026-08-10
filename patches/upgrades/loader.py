"""
loader.py — Auto-discovers modular upgrade patches and runs them in dependency order.

ReVanced-style pattern: each u*.py module in this directory is auto-discovered.
The loader reads METADATA from each, topological-sorts by depends_on, and runs
apply() + verify() in order.

USAGE (future, when all 6 upgrades are ported to modules):
    from patches.upgrades.loader import run_all
    run_all(src_dir)

CURRENT STATE:
  - Only u01.py is ported (proof of concept)
  - u02..u06 still live in apply_upgrades_v65.py
  - This loader is ready but NOT yet wired into production
  - tests/test_upgrades_modular.py verifies the loader works correctly
"""
from __future__ import annotations

import importlib
import pkgutil
from typing import NamedTuple

from .base import UpgradeMetadata, CANONICAL_ORDER


class DiscoveredUpgrade(NamedTuple):
    metadata: UpgradeMetadata
    module: object  # the imported module object


def discover() -> list[DiscoveredUpgrade]:
    """Auto-discover all u*.py modules in this package.

    Returns a list of (metadata, module) pairs. Modules without METADATA or
    without apply/verify callables are skipped (with a warning to stderr).
    """
    import os
    import sys

    # __package__ is the string 'patches.upgrades' (set by Python for submodules)
    pkg_name = __package__ or "patches.upgrades"
    pkg_dir = os.path.dirname(__file__)  # directory containing this loader.py
    discovered: list[DiscoveredUpgrade] = []

    for finder, name, ispkg in pkgutil.iter_modules([pkg_dir]):
        # Only consider u*.py modules (u01, u02, ..., u99)
        if not name.startswith("u") or not name[1:].isdigit():
            continue
        full_name = f"{pkg_name}.{name}"
        mod = importlib.import_module(full_name)
        if not hasattr(mod, "METADATA") or not isinstance(mod.METADATA, UpgradeMetadata):
            print(f"  WARN: {full_name} has no METADATA — skipping", flush=True)
            continue
        if not callable(getattr(mod, "apply", None)) or not callable(getattr(mod, "verify", None)):
            print(f"  WARN: {full_name} missing apply()/verify() — skipping", flush=True)
            continue
        discovered.append(DiscoveredUpgrade(mod.METADATA, mod))

    return discovered


def topo_sort(upgrades: list[DiscoveredUpgrade]) -> list[DiscoveredUpgrade]:
    """Sort upgrades by depends_on (topological sort).

    Falls back to CANONICAL_ORDER for upgrades with no explicit dependencies.
    Raises ValueError if a dependency cycle is detected.
    """
    by_id = {u.metadata.id: u for u in upgrades}
    sorted_list: list[DiscoveredUpgrade] = []
    visited: set[str] = set()
    visiting: set[str] = set()  # cycle detection

    def visit(uid: str) -> None:
        if uid in visited:
            return
        if uid in visiting:
            raise ValueError(f"dependency cycle at {uid}")
        if uid not in by_id:
            # Dependency is a legacy upgrade (P-NN) or not-yet-ported module — skip
            return
        visiting.add(uid)
        for dep in by_id[uid].metadata.depends_on:
            visit(dep)
        visiting.discard(uid)
        visited.add(uid)
        sorted_list.append(by_id[uid])

    # First, sort by canonical order (stable), then topo-sort
    canonical_idx = {uid: i for i, uid in enumerate(CANONICAL_ORDER)}
    ordered_ids = sorted(
        (u.metadata.id for u in upgrades),
        key=lambda uid: canonical_idx.get(uid, 999),
    )
    for uid in ordered_ids:
        visit(uid)

    return sorted_list


def run_all(src_dir: str) -> tuple[int, list[str]]:
    """Discover, sort, and apply all modular upgrades.

    Returns (applied_count, errors). verify() failures are appended to errors
    but do not stop the run (same semantics as apply_upgrades_v65.py).
    """
    print("═══════════════════════════════════════════════════════════════")
    print("  Modular upgrade loader (patches.upgrades.loader)")
    print("═══════════════════════════════════════════════════════════════")

    discovered = discover()
    if not discovered:
        print("  No modular upgrades found (expected if migration not started)")
        return 0, []

    print(f"  Discovered {len(discovered)} modular upgrade(s):")
    for d in discovered:
        print(f"    • {d.metadata.id} ({d.metadata.name}) — affects {len(d.metadata.affects)} file(s)")

    try:
        ordered = topo_sort(discovered)
    except ValueError as e:
        print(f"  ERROR: {e}")
        return 0, [str(e)]

    print(f"  Application order: {' → '.join(d.metadata.id for d in ordered)}")
    print()

    errors: list[str] = []
    applied_count = [0]

    for d in ordered:
        print(f"── {d.metadata.id}: {d.metadata.name} ──")
        d.module.apply(src_dir, errors, applied_count)
        verify_failures = d.module.verify(src_dir)
        if verify_failures:
            for f in verify_failures:
                errors.append(f"  {d.metadata.id} VERIFY FAIL: {f}")
        print()

    print("═══════════════════════════════════════════════════════════════")
    print(f"  Sub-patches applied: {applied_count[0]}")
    if errors:
        print(f"  Warnings: {len(errors)}")
        for e in errors:
            print(e)
    else:
        print("  All patches applied + verified (zero warnings)")
    print("═══════════════════════════════════════════════════════════════")

    return applied_count[0], errors


if __name__ == "__main__":
    import sys
    if len(sys.argv) < 2:
        print("Usage: python3 -m patches.upgrades.loader <source_dir>", file=sys.stderr)
        sys.exit(2)
    count, errs = run_all(sys.argv[1])
    sys.exit(0 if not errs else 0)  # same as apply_upgrades_v65.py: don't fail build on warnings
