"""
patches/upgrades — Modular upgrade patches for Photon Camera Leica Perfect fork.

This package implements the ReVanced/Morphe-style modular patch architecture:
each upgrade (U-NN) is a self-contained Python module with:
  - METADATA: id, name, description, depends_on, affects, version, options
  - apply(src_dir, errors, applied_count): performs the patch (string ops)
  - verify(src_dir): post-apply assertions (returns list of failures)

The loader (loader.py) auto-discovers u*.py modules, topological-sorts by
depends_on, and runs apply() + verify() in dependency order.

CURRENT STATE (v6.5.0):
  - u01.py: FULLY PORTED from apply_upgrades_v65.py (proof of concept)
  - u02..u06: still live in apply_upgrades_v65.py (legacy path)
  - apply_upgrades_v65.py remains the production entry point (called by P-79)
  - loader.py is ready but NOT yet wired into apply_upgrades_v65.py main()

MIGRATION PATH (v6.5.1+):
  1. Port u02.py..u06.py one at a time (each is ~120-310 LOC)
  2. After each port, run tests/test_upgrades_modular.py to verify parity
  3. Once all 6 are ported, switch apply_upgrades_v65.py main() to:
       from patches.upgrades.loader import run_all
       run_all(src_dir)
  4. apply_upgrades_v65.py becomes a thin shim, then can be deleted

NEW UPGRADES (U-07+):
  Add a new file patches/upgrades/u07.py with METADATA + apply + verify.
  The loader will discover it automatically. No other changes needed.
"""
