"""
helpers.py — Shared string-operation utilities for upgrade patches.

These are the SAME helpers used by apply_upgrades_v65.py (copied here so
modular upgrades don't depend on the legacy monolith). Keep both copies in
sync until apply_upgrades_v65.py is fully deprecated.
"""
from __future__ import annotations

import os


def read_file(path: str) -> str:
    with open(path, 'r', encoding='utf-8') as f:
        return f.read()


def write_file(path: str, content: str) -> None:
    with open(path, 'w', encoding='utf-8') as f:
        f.write(content)


def applied(content: str, marker: str) -> bool:
    """Return True if the patch marker is already present (idempotency guard)."""
    return marker in content


def replace_exact(content: str, old: str, new: str, desc: str, errors: list[str]) -> str:
    """Replace first occurrence of `old` with `new`. Appends error if anchor missing."""
    if old not in content:
        errors.append(f"  SKIP: {desc} — anchor not found")
        return content
    return content.replace(old, new, 1)


def insert_after(content: str, marker: str, insertion: str, desc: str, errors: list[str]) -> str:
    """Insert `insertion` immediately after the first occurrence of `marker`."""
    idx = content.find(marker)
    if idx < 0:
        errors.append(f"  SKIP: {desc} — marker not found")
        return content
    insert_pos = idx + len(marker)
    return content[:insert_pos] + insertion + content[insert_pos:]


def insert_before(content: str, marker: str, insertion: str, desc: str, errors: list[str]) -> str:
    """Insert `insertion` immediately before the first occurrence of `marker`."""
    idx = content.find(marker)
    if idx < 0:
        errors.append(f"  SKIP: {desc} — marker not found")
        return content
    return content[:idx] + insertion + content[idx:]


def insert_before_last(content: str, marker: str, insertion: str, desc: str, errors: list[str]) -> str:
    """Insert `insertion` immediately before the LAST occurrence of `marker`."""
    idx = content.rfind(marker)
    if idx < 0:
        errors.append(f"  SKIP: {desc} — marker not found")
        return content
    return content[:idx] + insertion + content[idx:]


def file_exists(src_dir: str, *parts: str) -> bool:
    """Check if a file exists under src_dir/app/src/main/java/com/hinnka/mycamera/..."""
    return os.path.exists(os.path.join(src_dir, "app/src/main/java/com/hinnka/mycamera", *parts))


def java_path(src_dir: str, *parts: str) -> str:
    """Return absolute path under src_dir/app/src/main/java/com/hinnka/mycamera/."""
    return os.path.join(src_dir, "app/src/main/java/com/hinnka/mycamera", *parts)
