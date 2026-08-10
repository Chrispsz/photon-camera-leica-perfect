# patches/upgrades/ — Modular Upgrade Patches

ReVanced/Morphe-style modular patch architecture for Photon Camera.

## Structure

```
upgrades/
├── __init__.py    ← Package marker + migration status
├── base.py        ← UpgradeMetadata dataclass + Upgrade protocol
├── helpers.py     ← read_file / write_file / replace_exact / insert_after / ...
├── loader.py      ← Auto-discovery + topo sort + apply + verify
├── u01.py         ← U-01: Denoise Shadows Reinforcement (FULLY PORTED)
└── (u02.py..u06.py — TODO: port from apply_upgrades_v65.py)
```

## How it works

1. Each `u*.py` file is an upgrade module with `METADATA` + `apply()` + `verify()`
2. `loader.discover()` scans this directory for `u*.py` files
3. `loader.topo_sort()` orders them by `METADATA.depends_on`
4. `loader.run_all(src_dir)` applies each in order, then runs `verify()`
5. `verify()` returns a list of failures (empty = all assertions pass)

## How to add a new upgrade (U-07+)

### Step 1: Create the module

Create `patches/upgrades/u07.py`:

```python
"""u07.py — U-07: <short description>"""
from __future__ import annotations
from .base import UpgradeMetadata
from .helpers import read_file, write_file, applied, java_path, file_exists

METADATA = UpgradeMetadata(
    id="U-07",
    name="<short name>",
    description="<1-3 sentence summary>",
    version="6.5.1",
    depends_on=("U-02",),  # upgrades that must run first (empty tuple if none)
    affects=("<File1>.kt", "<File2>.kt"),  # .kt basenames touched
    options={
        "option_name": "type, default, description",
    },
)

def apply(src_dir: str, errors: list[str], applied_count: list[int]) -> None:
    """Apply the patch. Must be IDEMPOTENT (safe to run multiple times)."""
    path = java_path(src_dir, "raw/SomeFile.kt")
    if not file_exists(src_dir, "raw/SomeFile.kt"):
        errors.append("  U-07: SomeFile.kt not found")
        return
    src = read_file(path)
    if applied(src, "U-07 marker"):  # idempotency guard
        return
    # ... string operations ...
    write_file(path, src)
    applied_count[0] += 1
    print("  ✓ U-07: SomeFile.kt <what changed>")

def verify(src_dir: str) -> list[str]:
    """Post-apply assertions. Return list of failure messages (empty = pass)."""
    failures = []
    path = java_path(src_dir, "raw/SomeFile.kt")
    if not file_exists(src_dir, "raw/SomeFile.kt"):
        failures.append("U-07: SomeFile.kt not found")
        return failures
    src = read_file(path)
    if "U-07 marker" not in src:
        failures.append("U-07: marker not injected")
    return failures
```

### Step 2: Register in MANIFEST.toml

Add to `patches/MANIFEST.toml`:
```toml
[[patch]]
id = "U-07"
title = "<short description>"
category = "V6_5_UPGRADES"  # or a new category
version = "6.5.1"
file = "patches/upgrades/u07.py"
line = <line of METADATA>
affects = ["<File1>.kt", "<File2>.kt"]
depends_on = ["U-02"]
status = "active"
```

### Step 3: Wire into production (optional — only if switching from legacy)

If `apply_upgrades_v65.py` should call the modular version instead of a legacy
function, edit its `main()` to call `loader.run_all()`:

```python
# In apply_upgrades_v65.py main():
from patches.upgrades.loader import run_all
run_all(src_dir)
```

Until all 6 upgrades are ported, the hybrid approach works: keep `apply_u02`..
`apply_u06` as legacy calls, and the loader handles U-01 + U-07+.

### Step 4: Test

```bash
./tests/run_all.sh
```

This runs `test_upgrades_modular.py` which verifies:
- Module has valid METADATA + apply + verify
- Loader discovers the new module
- Parity (if porting from legacy — output must match)
- Idempotency (running apply twice = no-op)

## Migration status (v6.5.0)

| Upgrade | Status | LOC | Module | Legacy |
|---------|--------|-----|--------|--------|
| U-01 | ✅ Ported | 121 | `u01.py` | (removed from production path) |
| U-02 | ⏳ Legacy | 239 | — | `apply_upgrades_v65.py::apply_u02` |
| U-03 | ⏳ Legacy | 243 | — | `apply_upgrades_v65.py::apply_u03` |
| U-04 | ⏳ Legacy | 158 | — | `apply_upgrades_v65.py::apply_u04` |
| U-05 | ⏳ Legacy | 255 | — | `apply_upgrades_v65.py::apply_u05` |
| U-06 | ⏳ Legacy | 314 | — | `apply_upgrades_v65.py::apply_u06` |

**To port U-02..U-06:** copy the `apply_uNN` function from `apply_upgrades_v65.py`
into `uNN.py`, wrap it as `apply()` (same signature), add `METADATA` + `verify()`,
run `./tests/run_all.sh` to verify parity. ~30 min per upgrade.

## Why ReVanced-style?

| ReVanced | Our equivalent |
|----------|----------------|
| `BytecodePatch` class | `Upgrade` protocol (METADATA + apply + verify) |
| `MethodFingerprint` (locates code in DEX) | Anchor strings in `replace_exact()` / `insert_after()` |
| `PatchLoader` (classpath scan) | `loader.discover()` (directory scan) |
| `@Patch` annotation | `METADATA` dataclass |
| `dependsOn` list | `METADATA.depends_on` tuple |
| `compatiblePackage` | `METADATA.affects` (file basenames) |
| Options (typed) | `METADATA.options` dict |
| Patches enabled/disabled by name | (future) `--skip U-03` CLI flag |

The key difference: ReVanced patches **compiled bytecode** (DEX/Smali); we patch
**source code** (Kotlin/GLSL) because Photon Camera is built from source on each
CI run. The modular + metadata + auto-discovery pattern is the same.
