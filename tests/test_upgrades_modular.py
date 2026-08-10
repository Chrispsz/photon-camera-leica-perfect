#!/usr/bin/env python3
"""
test_upgrades_modular.py — Verify the modular upgrade pattern works correctly.

Three test groups:
  1. Module structure: u01.py exposes METADATA, apply(), verify() with correct types
  2. Loader discovery: loader.discover() finds u01, topo_sort returns it
  3. Parity: u01.py.apply() produces IDENTICAL output to apply_upgrades_v65.py.apply_u01()
     on a minimal fixture. Catches drift if one is updated without the other.

Exit code: 0 on success, 1 on any failure.
"""
from __future__ import annotations

import os
import shutil
import sys
import tempfile
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(REPO_ROOT))

# ─── Test fixture: minimal .kt files with the anchor strings u01 expects ──────

FIXTURE_DENOISE_SHADERS = """\
package com.hinnka.mycamera.raw

object DenoiseProfileShaders {
    const val FINISH_V2 = "unused"
    const val SEARCH_RADIUS = 5
    private const val PATCH_RADIUS = 1
    private const val IMAGE_LOCAL_X = 8
    private const val IMAGE_LOCAL_Y = 8
    private const val FUSED_TILE_X = IMAGE_LOCAL_X + SEARCH_RADIUS + 2 * PATCH_RADIUS
    private const val FUSED_TILE_Y = IMAGE_LOCAL_Y + SEARCH_RADIUS + 2 * PATCH_RADIUS
    val SHADER = "uniform float uDenoiseMix; px.rgb = mix(original.rgb, px.rgb, clamp(uDenoiseMix, 0.0, 1.0));"
}
"""

FIXTURE_NLM_CONFIG = """\
package com.hinnka.mycamera.raw

object DenoiseProfileNlmConfig {
    val searchOffsets: List<DenoiseProfileOffset> = buildSearchOffsets(
        listOf(1, 2, 3)
    )
}
"""

FIXTURE_LEICA_CONFIG = """\
package com.hinnka.mycamera.raw

object LeicaConfig {
    data class NoiseReductionConfig(
        @SerializedName("chrominance") val chrominance: Double? = 0.94,
        @SerializedName("luminance") val luminance: Double? = 0.98,
        @SerializedName("detail_preserve") val detailPreserve: Double? = 0.96,
        @SerializedName("adaptive") val adaptive: Boolean? = true,
    )

    val noiseReductionAdaptive: Boolean get() = currentConfig?.noiseReduction?.adaptive ?: true
    val currentConfig: NoiseReductionConfig? = null
}
"""


def make_fixture(root: Path) -> None:
    """Create minimal upstream structure under root/app/src/main/java/com/hinnka/mycamera/."""
    java = root / "app/src/main/java/com/hinnka/mycamera/raw"
    java.mkdir(parents=True, exist_ok=True)
    (java / "DenoiseProfileShaders.kt").write_text(FIXTURE_DENOISE_SHADERS)
    (java / "DenoiseProfileNlmConfig.kt").write_text(FIXTURE_NLM_CONFIG)
    (java / "LeicaConfig.kt").write_text(FIXTURE_LEICA_CONFIG)


def color(msg: str, c: str) -> str:
    if not sys.stdout.isatty():
        return msg
    return f"\033[{c}m{msg}\033[0m"


def ok(msg: str) -> None:
    print(f"  {color('✓', '32')} {msg}")


def fail(msg: str) -> None:
    print(f"  {color('✗', '31')} {msg}")


def run_test(name: str, fn) -> bool:
    print(f"── {name} ──")
    try:
        fn()
        return True
    except AssertionError as e:
        fail(f"{name}: {e}")
        return False
    finally:
        print()


# ─── Test 1: Module structure ─────────────────────────────────────────────────

def test_module_structure() -> None:
    from patches.upgrades import u01
    from patches.upgrades.base import UpgradeMetadata

    assert hasattr(u01, "METADATA"), "u01.py missing METADATA"
    assert isinstance(u01.METADATA, UpgradeMetadata), "METADATA is not UpgradeMetadata"
    assert u01.METADATA.id == "U-01", f"METADATA.id = {u01.METADATA.id!r}"
    assert "shadow" in u01.METADATA.name.lower(), f"METADATA.name = {u01.METADATA.name!r}"
    assert len(u01.METADATA.affects) == 4, f"affects = {u01.METADATA.affects}"
    assert callable(u01.apply), "u01.apply is not callable"
    assert callable(u01.verify), "u01.verify is not callable"
    ok("u01.py module structure valid (METADATA + apply + verify)")


# ─── Test 2: Loader discovery ─────────────────────────────────────────────────

def test_loader_discovery() -> None:
    from patches.upgrades.loader import discover, topo_sort

    discovered = discover()
    assert len(discovered) >= 1, f"discovered {len(discovered)} modules, expected ≥1"

    ids = [d.metadata.id for d in discovered]
    assert "U-01" in ids, f"U-01 not discovered: {ids}"

    ordered = topo_sort(discovered)
    assert len(ordered) == len(discovered), "topo_sort lost entries"
    ok(f"loader discovered {len(discovered)} module(s): {ids}")


# ─── Test 3: Parity (u01.py modular == apply_u01 legacy) ──────────────────────

def test_parity() -> None:
    """Run both apply paths on identical fixtures, diff the results."""
    import importlib.util

    # Import legacy apply_u01 from apply_upgrades_v65.py (it's not a package)
    spec = importlib.util.spec_from_file_location(
        "apply_upgrades_v65", REPO_ROOT / "patches/apply_upgrades_v65.py"
    )
    legacy = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(legacy)

    from patches.upgrades import u01 as modular_u01

    # Create two identical fixtures
    with tempfile.TemporaryDirectory() as tmp:
        fixture_a = Path(tmp) / "legacy"
        fixture_b = Path(tmp) / "modular"
        make_fixture(fixture_a)
        make_fixture(fixture_b)

        # Apply legacy
        legacy_errors: list[str] = []
        legacy_count = [0]
        legacy.apply_u01(str(fixture_a), legacy_errors, legacy_count)

        # Apply modular
        mod_errors: list[str] = []
        mod_count = [0]
        modular_u01.apply(str(fixture_b), mod_errors, mod_count)

        # Compare applied counts
        assert legacy_count[0] == mod_count[0], (
            f"applied_count mismatch: legacy={legacy_count[0]} modular={mod_count[0]}"
        )
        ok(f"applied_count parity: legacy={legacy_count[0]} modular={mod_count[0]}")

        # Compare error lists (sort because order may differ)
        assert sorted(legacy_errors) == sorted(mod_errors), (
            f"errors mismatch:\n  legacy={legacy_errors}\n  modular={mod_errors}"
        )
        ok(f"errors parity: legacy={len(legacy_errors)} modular={len(mod_errors)}")

        # Compare file contents byte-for-byte
        for relpath in [
            "app/src/main/java/com/hinnka/mycamera/raw/DenoiseProfileShaders.kt",
            "app/src/main/java/com/hinnka/mycamera/raw/DenoiseProfileNlmConfig.kt",
            "app/src/main/java/com/hinnka/mycamera/raw/LeicaConfig.kt",
        ]:
            a = (fixture_a / relpath).read_text()
            b = (fixture_b / relpath).read_text()
            assert a == b, f"file content mismatch for {relpath}:\n--- legacy ---\n{a}\n--- modular ---\n{b}"
        ok("File contents identical (legacy vs modular)")

        # Verify both pass verify()
        # (legacy doesn't have verify, only modular does — so just check modular)
        mod_failures = modular_u01.verify(str(fixture_b))
        assert not mod_failures, f"modular verify() failed after apply: {mod_failures}"
        ok("modular verify() passes after apply()")

    # Test idempotency: running apply twice should be a no-op
    with tempfile.TemporaryDirectory() as tmp:
        fixture = Path(tmp) / "idem"
        make_fixture(fixture)
        errors1, count1 = [], [0]
        modular_u01.apply(str(fixture), errors1, count1)
        errors2, count2 = [], [0]
        modular_u01.apply(str(fixture), errors2, count2)
        assert count2[0] == 0, f"second apply should be no-op, but applied {count2[0]} patches"
        ok(f"Idempotency: second apply is no-op (count1={count1[0]}, count2={count2[0]})")


def main() -> int:
    print(color("═══════════════════════════════════════════════════════════════", "1"))
    print(color("  test_upgrades_modular — modular pattern verification", "1"))
    print(color("═══════════════════════════════════════════════════════════════", "1"))
    print()

    results = [
        run_test("Module structure", test_module_structure),
        run_test("Loader discovery", test_loader_discovery),
        run_test("Parity (legacy vs modular)", test_parity),
    ]

    passed = sum(results)
    failed = len(results) - passed

    print(color("═══════════════════════════════════════════════════════════════", "1"))
    print(color(f"  Passed: {passed}/{len(results)}", "32" if failed == 0 else "31"))
    if failed:
        print(color(f"  Failed: {failed}", "31"))
    print(color("═══════════════════════════════════════════════════════════════", "1"))

    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
