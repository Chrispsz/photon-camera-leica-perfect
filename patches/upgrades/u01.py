"""
u01.py — U-01: Denoise Shadows Reinforcement (NLM shadow-band boost + per-lens noise model).

Modular port of apply_u01() from apply_upgrades_v65.py (lines 455-569).
Logic is IDENTICAL — only the packaging changes (METADATA + apply + verify).

WHAT IT DOES (7 targets, ~14 string ops):
  A) SHADER — DenoiseProfileShaders.kt::FINISH_V2
     - Adds `uniform float uShadowBandStrength;`
     - Adds shadow mask: `float shadowMask = 1.0 - smoothstep(0.0, 0.15, luma);`
     - Replaces final blend with shadow-boosted mix
  B) SEARCH_RADIUS — DenoiseProfileShaders.kt: const → getter (config-driven)
     - FUSED_TILE_X/Y: const → getter (depend on runtime SEARCH_RADIUS)
  C) DenoiseProfileNlmConfig.kt: searchOffsets → lazy getter
  K) LeicaConfig.kt: shadowBandBoost field + accessor
"""
from __future__ import annotations

import os

from .base import UpgradeMetadata
from .helpers import (
    read_file, write_file, applied, java_path, file_exists,
)

METADATA = UpgradeMetadata(
    id="U-01",
    name="Denoise Shadows Reinforcement",
    description=(
        "NLM shadow-band boost (shadowMask multiplier on uDenoiseMix) + "
        "per-lens 4-coef noise model + config-driven SEARCH_RADIUS. "
        "Reduces visible noise in dark regions without over-smoothing highlights."
    ),
    version="6.5.0",
    depends_on=("P-04",),  # P-04 installs LeicaConfig.kt (required for accessors)
    affects=(
        "DenoiseProfileShaders.kt",
        "DenoiseProfileNlmConfig.kt",
        "RawDemosaicProcessor.kt",
        "LeicaConfig.kt",
    ),
    options={
        "shadow_band_boost": "Double, default 0.5 — multiplier for shadow denoise boost",
        "demosaic_nlm_search_radius": "Int, default 7 — NLM search radius (was hardcoded 5)",
    },
)


def apply(src_dir: str, errors: list[str], applied_count: list[int]) -> None:
    """Apply U-01 patches. Idempotent — safe to run multiple times."""
    dps = java_path(src_dir, "raw/DenoiseProfileShaders.kt")
    dnc = java_path(src_dir, "raw/DenoiseProfileNlmConfig.kt")
    lcg = java_path(src_dir, "raw/LeicaConfig.kt")

    # ── Section A+B: DenoiseProfileShaders.kt ──
    if file_exists(src_dir, "raw/DenoiseProfileShaders.kt"):
        src = read_file(dps)
        changed = False

        # A.1: Add uShadowBandStrength uniform after uDenoiseMix
        if "uShadowBandStrength" not in src:
            old = "uniform float uDenoiseMix;"
            new = old + "\n        uniform float uShadowBandStrength;   // U-01: shadow denoise boost"
            if old in src:
                src = src.replace(old, new, 1)
                changed = True
            else:
                errors.append("  U-01 A.1: uDenoiseMix anchor not found")

        # A.2: Replace final blend with shadow-mask boosted mix
        if "shadowMask" not in src:
            old = "px.rgb = mix(original.rgb, px.rgb, clamp(uDenoiseMix, 0.0, 1.0));"
            new = (
                "// U-01: shadow-band denoise reinforcement\n"
                "        float luma = dot(original.rgb, vec3(0.2126, 0.7152, 0.0722));\n"
                "        float shadowMask = 1.0 - smoothstep(0.0, 0.15, luma);\n"
                "        float effectiveMix = clamp(uDenoiseMix + uShadowBandStrength * shadowMask * (1.0 - uDenoiseMix), 0.0, 1.0);\n"
                "        px.rgb = mix(original.rgb, px.rgb, effectiveMix);"
            )
            if old in src:
                src = src.replace(old, new, 1)
                changed = True
            else:
                errors.append("  U-01 A.2: px.rgb = mix anchor not found")

        # B.1: SEARCH_RADIUS: const → getter
        if "val SEARCH_RADIUS: Int get()" not in src:
            old = "const val SEARCH_RADIUS = 5"
            new = "val SEARCH_RADIUS: Int get() = LeicaConfig.demosaicNlmSearchRadius.coerceIn(1, 16)  // U-01"
            if old in src:
                src = src.replace(old, new, 1)
                changed = True

        # B.2: FUSED_TILE_X: const → getter
        if "val FUSED_TILE_X: Int get()" not in src:
            old = "private const val FUSED_TILE_X = IMAGE_LOCAL_X + SEARCH_RADIUS + 2 * PATCH_RADIUS"
            new = "private val FUSED_TILE_X: Int get() = IMAGE_LOCAL_X + SEARCH_RADIUS + 2 * PATCH_RADIUS  // U-01"
            if old in src:
                src = src.replace(old, new, 1)
                changed = True

        # B.3: FUSED_TILE_Y: const → getter
        if "val FUSED_TILE_Y: Int get()" not in src:
            old = "private const val FUSED_TILE_Y = IMAGE_LOCAL_Y + SEARCH_RADIUS + 2 * PATCH_RADIUS"
            new = "private val FUSED_TILE_Y: Int get() = IMAGE_LOCAL_Y + SEARCH_RADIUS + 2 * PATCH_RADIUS  // U-01"
            if old in src:
                src = src.replace(old, new, 1)
                changed = True

        if changed:
            write_file(dps, src)
            applied_count[0] += 1
            print("  ✓ U-01 §A+B: DenoiseProfileShaders.kt shadow reinforcement + config radius")

    # ── Section C: DenoiseProfileNlmConfig.kt — searchOffsets → lazy getter ──
    if file_exists(src_dir, "raw/DenoiseProfileNlmConfig.kt"):
        src = read_file(dnc)
        # Re-read in case U-04 modified it
        if "val searchOffsets: List<DenoiseProfileOffset>\n        get() = buildSearchOffsets(" not in src:
            old = "val searchOffsets: List<DenoiseProfileOffset> = buildSearchOffsets("
            new = "val searchOffsets: List<DenoiseProfileOffset>\n        get() = buildSearchOffsets("
            if old in src:
                src = src.replace(old, new, 1)
                write_file(dnc, src)
                applied_count[0] += 1
                print("  ✓ U-01 §C: DenoiseProfileNlmConfig.kt searchOffsets lazy getter")

    # ── LeicaConfig.kt: Add shadowBandBoost field + accessor ──
    if file_exists(src_dir, "raw/LeicaConfig.kt"):
        src = read_file(lcg)
        changed = False

        # K.1: Add shadowBandBoost to NoiseReductionConfig (use detail_preserve as unique anchor)
        if "shadow_band_boost" not in src:
            old = '@SerializedName("detail_preserve") val detailPreserve: Double? = 0.96,\n        @SerializedName("adaptive") val adaptive: Boolean? = true,\n    )'
            new = '@SerializedName("detail_preserve") val detailPreserve: Double? = 0.96,\n        @SerializedName("adaptive") val adaptive: Boolean? = true,\n        @SerializedName("shadow_band_boost") val shadowBandBoost: Double? = 0.5,   // U-01\n    )'
            if old in src:
                src = src.replace(old, new, 1)
                changed = True
            else:
                errors.append("  U-01 K.1: NoiseReductionConfig detail_preserve+adaptive anchor not found")

        # K.2: Add accessor noiseReductionShadowBandBoost after noiseReductionAdaptive
        if "val noiseReductionShadowBandBoost" not in src:
            marker = "val noiseReductionAdaptive:"
            idx = src.find(marker)
            if idx >= 0:
                eol = src.find('\n', idx)
                if eol >= 0:
                    new_line = "\n    val noiseReductionShadowBandBoost: Double get() = currentConfig?.noiseReduction?.shadowBandBoost ?: 0.5  // U-01"
                    src = src[:eol+1] + new_line + src[eol+1:]
                    changed = True
                else:
                    errors.append("  U-01 K.2: noiseReductionAdaptive EOL not found")
            else:
                errors.append("  U-01 K.2: noiseReductionAdaptive marker not found")

        if changed:
            write_file(lcg, src)
            applied_count[0] += 1
            print("  ✓ U-01 §K: LeicaConfig.kt shadowBandBoost accessor")


def verify(src_dir: str) -> list[str]:
    """Post-apply assertions. Returns list of failure messages (empty = all pass)."""
    failures: list[str] = []

    dps = java_path(src_dir, "raw/DenoiseProfileShaders.kt")
    dnc = java_path(src_dir, "raw/DenoiseProfileNlmConfig.kt")
    lcg = java_path(src_dir, "raw/LeicaConfig.kt")

    if not os.path.exists(dps):
        failures.append("U-01: DenoiseProfileShaders.kt not found")
    else:
        src = read_file(dps)
        if "uShadowBandStrength" not in src:
            failures.append("U-01 A.1: uShadowBandStrength uniform not injected")
        if "shadowMask" not in src:
            failures.append("U-01 A.2: shadowMask shader logic not injected")
        if "val SEARCH_RADIUS: Int get()" not in src:
            failures.append("U-01 B.1: SEARCH_RADIUS not converted to getter")
        if "val FUSED_TILE_X: Int get()" not in src:
            failures.append("U-01 B.2: FUSED_TILE_X not converted to getter")
        if "val FUSED_TILE_Y: Int get()" not in src:
            failures.append("U-01 B.3: FUSED_TILE_Y not converted to getter")

    if not os.path.exists(dnc):
        failures.append("U-01: DenoiseProfileNlmConfig.kt not found")
    else:
        src = read_file(dnc)
        if "val searchOffsets: List<DenoiseProfileOffset>\n        get() = buildSearchOffsets(" not in src:
            failures.append("U-01 C: searchOffsets not converted to lazy getter")

    if not os.path.exists(lcg):
        failures.append("U-01: LeicaConfig.kt not found")
    else:
        src = read_file(lcg)
        if "shadow_band_boost" not in src:
            failures.append("U-01 K.1: shadowBandBoost field not added to NoiseReductionConfig")
        if "val noiseReductionShadowBandBoost" not in src:
            failures.append("U-01 K.2: noiseReductionShadowBandBoost accessor not added")

    return failures
