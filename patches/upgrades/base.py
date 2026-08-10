"""
base.py — Defines the Upgrade protocol and METADATA schema for modular patches.

Inspired by ReVanced's BytecodePatch / ResourcePatch pattern:
  - Each patch is a self-contained module
  - METADATA declares name, description, dependencies, affected files
  - apply() does the work
  - verify() asserts the work was done correctly

Unlike ReVanced (which patches compiled bytecode via fingerprints), we patch
Kotlin/GLSL source via string operations (replace_exact, insert_after, etc.)
because Photon Camera is built from source on each CI run.
"""
from __future__ import annotations

from dataclasses import dataclass, field
from typing import Protocol, runtime_checkable


@dataclass(frozen=True)
class UpgradeMetadata:
    """Declarative metadata for a modular upgrade patch.

    Mirrors ReVanced's PatchAnnotation: name, description, dependencies,
    compatible packages → here we track affected source files instead.
    """
    id: str                          # e.g. "U-01"
    name: str                        # short human-readable name
    description: str                 # 1-3 sentence summary
    version: str                     # semver introduced, e.g. "6.5.0"
    depends_on: tuple[str, ...]      # upgrade IDs that must run first
    affects: tuple[str, ...]         # .kt basenames touched
    options: dict[str, str] = field(default_factory=dict)  # typed config knobs


@runtime_checkable
class Upgrade(Protocol):
    """Protocol every modular upgrade must satisfy.

    A module is recognized as an upgrade if it exposes:
      - METADATA: UpgradeMetadata instance
      - apply(src_dir: str, errors: list[str], applied_count: list[int]) -> None
      - verify(src_dir: str) -> list[str]  (returns list of failure messages)
    """
    METADATA: UpgradeMetadata

    def apply(self, src_dir: str, errors: list[str], applied_count: list[int]) -> None: ...
    def verify(self, src_dir: str) -> list[str]: ...


# Application order (from UPGRADES_MANIFEST.kt):
# U-02 → U-04 → U-01 → U-03 → U-05 → U-06
# This is the ORDER in which upgrades must be applied. The loader uses
# depends_on for topological sorting, but this canonical order is the fallback
# when there are no explicit dependencies.
CANONICAL_ORDER: tuple[str, ...] = ("U-02", "U-04", "U-01", "U-03", "U-05", "U-06")
