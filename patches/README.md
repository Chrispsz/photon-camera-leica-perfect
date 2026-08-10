# Patches — Photon Camera Leica Perfect v6.5.0

This directory contains all patch logic for the Photon Camera fork. Patches are
organized in a **3-layer architecture** inspired by ReVanced/Morphe:

```
patches/
├── MANIFEST.toml              ← Single source of truth (85 patch entries)
├── README.md                  ← This file (architecture overview)
├── apply_upgrades_v65.py      ← Legacy monolith: U-01..U-06 (1388 LOC)
├── upgrades/                  ← NEW modular pattern (ReVanced-style)
│   ├── __init__.py
│   ├── base.py                ← UpgradeMetadata + Upgrade protocol
│   ├── helpers.py             ← read_file / write_file / replace_exact / ...
│   ├── loader.py              ← auto-discovery + topo sort + apply + verify
│   ├── u01.py                 ← FULLY PORTED (proof of concept)
│   └── README.md              ← How to add a new modular upgrade
├── LeicaConfig.kt             ← Injected Kotlin config (87 KB, ~2000 accessors)
├── LeicaSettingsScreen.kt     ← Injected UI panel
├── LeicaRuntimeState.kt       ← Injected runtime state
├── LeicaStateDumper.kt        ← Injected debug dumper
├── LeicaThermalMonitor.kt     ← Injected thermal gate
├── UPGRADES_MANIFEST.kt       ← Documentation-only manifest of U-01..U-06
└── *.patch.kt                 ← Documentation-only before/after examples
```

## Architecture (3 layers)

### Layer 1 — Build script (the orchestrator)
**File:** `../build-archlinux.sh` (6318 LOC, 79 patches P-01..P-79)

The build script is the single entry point. It:
1. Clones upstream `bjzhou/PhotonCamera` 1.26.1
2. Applies 78 surgical patches (P-01..P-78) as inline `sed` operations
3. P-79 delegates to `apply_upgrades_v65.py` for the 6 v6.5 upgrades (U-01..U-06)
4. Builds the APK via Gradle

Each patch is a `section "P-NN: <title>"` block containing `substep` entries
with `sed -i` commands. Patches are **idempotent** (guarded by `grep -q` checks).

### Layer 2 — Upgrade script (legacy monolith)
**File:** `apply_upgrades_v65.py` (1388 LOC, 6 patches U-01..U-06)

Python script called by P-79. Uses `replace_exact` / `insert_after` / etc.
(more reliable than `sed` for multi-line patterns). Each upgrade is a
`apply_uNN(src_dir, errors, applied_count)` function.

**This file is being migrated to Layer 3.** U-01 is fully ported; U-02..U-06
remain here as legacy.

### Layer 3 — Modular upgrades (ReVanced-style, NEW)
**Directory:** `upgrades/` (this is the future)

Each upgrade is a self-contained Python module (`u01.py`, `u02.py`, etc.) with:
- `METADATA`: `UpgradeMetadata` (id, name, description, depends_on, affects, options)
- `apply(src_dir, errors, applied_count)`: performs the patch
- `verify(src_dir)`: post-apply assertions (returns list of failures)

The `loader.py` auto-discovers all `u*.py` modules, topological-sorts by
`depends_on`, and runs `apply()` + `verify()` in dependency order.

**Why ReVanced-style?**
- **Self-contained**: each patch in its own file → easy to find, review, revert
- **Metadata-driven**: dependencies, affected files, options are declarative
- **Auto-discovery**: adding a new patch = adding a new file (no registration)
- **Verifiable**: `verify()` catches silent patch failures (anchor drift)
- **Testable**: parity test proves modular == legacy output

See `upgrades/README.md` for how to add a new modular upgrade.

## Patch inventory (85 entries)

| Tier | Range | Category | Count |
|------|-------|----------|-------|
| 1 | P-01..P-07 | Core: config, tone mapping, sharpening, NLM, metering | 7 |
| 2 | P-08..P-20 | Multi-frame, HDR, demosaic, DCP | 13 |
| 3 | P-21..P-28 | Mertens, vignette, DNG, JPEG/HEIC, branding, ISP | 8 |
| 4 | P-29..P-36 | Per-lens intelligence (frame count, video, tint, sat, noise) | 8 |
| 5 | P-37..P-43 | Wiring (white/black level, DCP ratio, SR DNG, AgX, gainmap) | 7 |
| 6 | P-44..P-45 | Runtime activation + branding | 2 |
| 7 | P-46..P-48 | UI (settings panel, viewfinder, runtime state) | 3 |
| 8 | P-49..P-53 | Runtime NLM, Live Photo, compilation fixes | 5 |
| 9 | P-54 | Creative profile color science | 1 |
| 10 | P-55..P-56 | Video settings + encoder | 2 |
| 11 | P-57..P-59 | Camera2 params, noise model, thermal | 3 |
| 12 | P-60..P-61 | Build fixes (doNotStrip, LeicaStateDumper) | 2 |
| 13 | P-62..P-65 | Capture modes, LUT picker, one-click max | 4 |
| 14 | P-66..P-74 | Build optimization (ccache, R8, arm64, mode_fast) | 5 |
| 15 | P-75..P-78 | Natural baseline + night denoise evolution | 4 |
| 16 | P-79 | v6.5 UPGRADES dispatcher (calls apply_upgrades_v65.py) | 1 |
| 17 | U-01..U-06 | v6.5 VLM-driven pipeline fixes (modular: U-01) | 6 |
| **Total** | | | **85** |

Full details in `MANIFEST.toml`.

## Quality gate

Every PR/push is validated by `.github/workflows/quality.yml` which runs
`tests/run_all.sh`:

| Check | What it catches |
|-------|-----------------|
| `lint_manifest.py` | MANIFEST.toml schema errors, dangling depends_on, missing files |
| `lint_bash.sh` | Bash syntax errors in all .sh files (`bash -n`) |
| `lint_python.sh` | Python compile errors in all .py files (`py_compile`) |
| `test_manifest_cross_ref.py` | Manifest entries vs actual `section "P-NN:` titles in build script |
| `test_section_sequence.sh` | All P-01..P-79 present (no missing patches) |
| `test_upgrades_modular.py` | Modular u01.py == legacy apply_u01 (parity) + idempotency |

Run locally: `./tests/run_all.sh`

## Adding a new patch

### Option A — Legacy (for patches that fit the sed pattern, P-80+)
1. Add a `section "P-80: <title>"` block in `build-archlinux.sh`
2. Add a `[[patch]]` entry in `MANIFEST.toml`
3. Run `./tests/run_all.sh` to verify

### Option B — Modular (for complex patches, U-07+)
1. Create `patches/upgrades/u07.py` with `METADATA`, `apply()`, `verify()`
2. Add a `[[patch]]` entry in `MANIFEST.toml`
3. Run `./tests/run_all.sh` to verify (loader auto-discovers u07.py)
4. Add a `apply_u07()` call in `apply_upgrades_v65.py` main() OR switch main() to use `loader.run_all()`

See `upgrades/README.md` for a step-by-step guide.
