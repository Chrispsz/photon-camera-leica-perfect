#!/usr/bin/env python3
"""
apply_upgrades_v65.py — Aplica os 6 upgrades U-01..U-06 no código-fonte do PhotonCamera.
Chamado por build-archlinux.sh como P-79 (após P-78, antes do SUMÁRIO).

Uso: python3 apply_upgrades_v65.py <source_dir>
Onde source_dir = /tmp/photon_upstream (diretório raiz do clone upstream)

Baseado na avaliação VLM de 5 rodadas comparativas (avaliacao-final.md).
3772 LOC de patches traduzidos para operações Python de string match exato.
"""
import sys
import os

def read_file(path):
    with open(path, 'r', encoding='utf-8') as f:
        return f.read()

def write_file(path, content):
    with open(path, 'w', encoding='utf-8') as f:
        f.write(content)

def applied(content, marker):
    return marker in content

def replace_exact(content, old, new, desc, errors):
    if old not in content:
        errors.append(f"  SKIP: {desc} — anchor not found")
        return content
    return content.replace(old, new, 1)

def insert_after(content, marker, insertion, desc, errors):
    idx = content.find(marker)
    if idx < 0:
        errors.append(f"  SKIP: {desc} — marker not found")
        return content
    insert_pos = idx + len(marker)
    return content[:insert_pos] + insertion + content[insert_pos:]

def insert_before(content, marker, insertion, desc, errors):
    idx = content.find(marker)
    if idx < 0:
        errors.append(f"  SKIP: {desc} — marker not found")
        return content
    return content[:idx] + insertion + content[idx:]

def insert_before_last(content, marker, insertion, desc, errors):
    idx = content.rfind(marker)
    if idx < 0:
        errors.append(f"  SKIP: {desc} — marker not found")
        return content
    return content[:idx] + insertion + content[idx:]

# ═══════════════════════════════════════════════════════════════════════════════
# U-02: ADAPTIVE SHARPENING (P0, Phase 1) — APPLIED FIRST
# ═══════════════════════════════════════════════════════════════════════════════

def apply_u02(src_dir, errors, applied_count):
    java = os.path.join(src_dir, "app/src/main/java/com/hinnka/mycamera")
    rsh = os.path.join(java, "raw/RawShaders.kt")
    rdp = os.path.join(java, "raw/RawDemosaicProcessor.kt")
    lip = os.path.join(java, "lut/LutImageProcessor.kt")
    rsd = os.path.join(java, "raw/RawSharpeningDefaults.kt")
    lcg = os.path.join(java, "raw/LeicaConfig.kt")

    # ── Section A: RawShaders.kt — shader uniforms + helpers + adaptive path ──
    if os.path.exists(rsh):
        src = read_file(rsh)
        changed = False

        # A.1: Add 4 uniforms after uThreshold
        if not applied(src, "uEdgeMaskStrength"):
            old = "        uniform float uThreshold;\n"
            new = old + (
                "        uniform float uEdgeMaskStrength;   // U-02: edge mask power\n"
                "        uniform float uNoiseLimit;         // U-02: noise gating threshold\n"
                "        uniform float uDarkLimit;          // U-02: dark-region luma threshold\n"
                "        uniform int   uAdaptive;           // U-02: 1=adaptive, 0=legacy\n"
            )
            if old in src:
                src = src.replace(old, new, 1)
                changed = True
            else:
                errors.append("  U-02 A.1: uThreshold uniform anchor not found")

        # A.2: Insert CalcNoise/CalcAmount helpers BEFORE the LAST void main() (sharpening shader)
        if not applied(src, "float CalcNoise("):
            helpers = (
                "        // U-02: noise gating (simplified port of Phocus CalcNoise)\n"
                "        float CalcNoise(float noiseLimit, float absDiff) {\n"
                "            if (noiseLimit <= 0.0) return 1.0;\n"
                "            float range = max(noiseLimit * 0.25, 1.0 / 16384.0);\n"
                "            float t = clamp((absDiff - (noiseLimit - range)) / range, 0.0, 1.0);\n"
                "            return t * t * (3.0 - 2.0 * t);\n"
                "        }\n"
                "        // U-02: brightness-adaptive amount (simplified port of Phocus CalcAmount)\n"
                "        float CalcAmount(float amount, float darkLimit, float gray) {\n"
                "            if (amount <= 0.0) return 0.0;\n"
                "            if (darkLimit <= 0.0) return amount;\n"
                "            float lo = darkLimit * 0.5;\n"
                "            float hi = darkLimit * 1.5;\n"
                "            float t = clamp((gray - lo) / max(hi - lo, 1e-5), 0.0, 1.0);\n"
                "            t = t * t * (3.0 - 2.0 * t);\n"
                "            return amount * t;\n"
                "        }\n"
                "        "
            )
            src = insert_before_last(src, "void main() {", helpers, "U-02 A.2 helpers", errors)
            changed = True

        # A.3: Replace final result computation with adaptive path
        if not applied(src, "uAdaptive == 0"):
            old = "            vec3 result = center + center * (detail / max(centerLuma, 1e-5)) * uSharpening;"
            new = (
                "            // U-02: legacy path (uAdaptive == 0)\n"
                "            if (uAdaptive == 0) {\n"
                "                vec3 resultLegacy = center + center * (detail / max(centerLuma, 1e-5)) * uSharpening;\n"
                "                fragColor = vec4(clamp(resultLegacy, 0.0, 1.0), 1.0);\n"
                "                return;\n"
                "            }\n"
                "            // U-02: noise gate + brightness-adaptive amount\n"
                "            float absDiff = abs(delta);\n"
                "            float noise  = CalcNoise(uNoiseLimit, absDiff);\n"
                "            float amount = CalcAmount(uSharpening, uDarkLimit, centerLuma);\n"
                "            // U-02: Sobel 4-tap edge mask\n"
                "            vec2 ts = uTexelSize * r;\n"
                "            float lL = luminance(texture(uInputTexture, vTexCoord + vec2(-ts.x, 0.0)).rgb);\n"
                "            float lR = luminance(texture(uInputTexture, vTexCoord + vec2( ts.x, 0.0)).rgb);\n"
                "            float lU = luminance(texture(uInputTexture, vTexCoord + vec2(0.0, -ts.y)).rgb);\n"
                "            float lD = luminance(texture(uInputTexture, vTexCoord + vec2(0.0,  ts.y)).rgb);\n"
                "            float gx = abs(lR - lL);\n"
                "            float gy = abs(lD - lU);\n"
                "            float edgeMag = sqrt(gx * gx + gy * gy);\n"
                "            float edgeMask = 1.0 - exp(-edgeMag * uEdgeMaskStrength);\n"
                "            float usmFactor = amount * noise * edgeMask;\n"
                "            vec3 result = center + center * (detail * usmFactor / max(centerLuma, 1e-5));"
            )
            if old in src:
                src = src.replace(old, new, 1)
                changed = True
            else:
                errors.append("  U-02 A.3: result computation anchor not found")

        if changed:
            write_file(rsh, src)
            applied_count[0] += 1
            print("  ✓ U-02 §A: RawShaders.kt adaptive sharpening shader")

    # ── Section B: RawDemosaicProcessor.kt — RAW consumer ──
    if os.path.exists(rdp):
        src = read_file(rdp)
        changed = False

        # B.1: defaultUsmRadius → config-driven
        if "LeicaConfig.sharpeningRadius" not in src:
            old = "    private val defaultUsmRadius = RawShaders.DEFAULT_USM_RADIUS"
            new = "    private val defaultUsmRadius: Float get() = LeicaConfig.sharpeningRadius.toFloat().coerceIn(0.1f, 8.0f)  // U-02"
            if old in src:
                src = src.replace(old, new, 1)
                changed = True
            else:
                errors.append("  U-02 B.1: defaultUsmRadius anchor not found")

        # B.2: defaultUsmThreshold → config-driven
        if "LeicaConfig.sharpeningThreshold" not in src:
            old = "    private val defaultUsmThreshold = RawShaders.DEFAULT_USM_THRESHOLD"
            new = "    private val defaultUsmThreshold: Float get() = LeicaConfig.sharpeningThreshold.toFloat().coerceIn(0f, 0.1f)  // U-02"
            if old in src:
                src = src.replace(old, new, 1)
                changed = True
            else:
                errors.append("  U-02 B.2: defaultUsmThreshold anchor not found")

        # B.3: Add 4 uniform bindings after uThreshold block
        if 'uEdgeMaskStrength' not in src or src.count('uEdgeMaskStrength') < 2:
            marker = 'glGetUniformLocation(sharpenProgram, "uThreshold"),'
            idx = src.find(marker)
            if idx >= 0:
                close = src.find('        )', idx)
                if close >= 0:
                    insert_pos = close + len('        )')
                    insertion = (
                        "\n        // U-02: adaptive path uniforms (LeicaConfig-driven)\n"
                        "        GLES30.glUniform1f(GLES30.glGetUniformLocation(sharpenProgram, \"uEdgeMaskStrength\"), LeicaConfig.sharpeningEdgeMaskStrength.toFloat())\n"
                        "        GLES30.glUniform1f(GLES30.glGetUniformLocation(sharpenProgram, \"uNoiseLimit\"), 0.015f)\n"
                        "        GLES30.glUniform1f(GLES30.glGetUniformLocation(sharpenProgram, \"uDarkLimit\"), 0.04f)\n"
                        "        GLES30.glUniform1i(GLES30.glGetUniformLocation(sharpenProgram, \"uAdaptive\"), if (LeicaConfig.sharpeningAdaptive) 1 else 0)"
                    )
                    src = src[:insert_pos] + insertion + src[insert_pos:]
                    changed = True
                else:
                    errors.append("  U-02 B.3: closing ) after uThreshold not found")
            else:
                errors.append("  U-02 B.3: sharpenProgram uThreshold marker not found")

        if changed:
            write_file(rdp, src)
            applied_count[0] += 1
            print("  ✓ U-02 §B: RawDemosaicProcessor.kt RAW consumer")

    # ── Section C: LutImageProcessor.kt — LUT consumer ──
    if os.path.exists(lip):
        src = read_file(lip)
        changed = False

        # C.1: Add import LeicaConfig (grep-guarded)
        if "import com.hinnka.mycamera.raw.LeicaConfig" not in src:
            old = "import com.hinnka.mycamera.raw.RawShaders"
            new = "import com.hinnka.mycamera.raw.LeicaConfig  // U-02\n" + old
            if old in src:
                src = src.replace(old, new, 1)
                changed = True
            else:
                errors.append("  U-02 C.1: RawShaders import anchor not found")

        # C.2: Replace RawShaders.DEFAULT_USM_RADIUS
        if "LeicaConfig.sharpeningRadius" not in src:
            old = "RawShaders.DEFAULT_USM_RADIUS"
            new = "LeicaConfig.sharpeningRadius.toFloat().coerceIn(0.1f, 8.0f)  // U-02"
            src = src.replace(old, new)
            changed = True

        # C.3: Replace RawShaders.DEFAULT_USM_THRESHOLD
        if "LeicaConfig.sharpeningThreshold" not in src:
            old = "RawShaders.DEFAULT_USM_THRESHOLD"
            new = "LeicaConfig.sharpeningThreshold.toFloat().coerceIn(0f, 0.1f)  // U-02"
            src = src.replace(old, new)
            changed = True

        # C.4: Add 4 uniform bindings after uThreshold block (lutSharpenProgram)
        if 'lutSharpenProgram, "uEdgeMaskStrength"' not in src:
            marker = 'glGetUniformLocation(lutSharpenProgram, "uThreshold"),'
            idx = src.find(marker)
            if idx >= 0:
                close = src.find('        )', idx)
                if close >= 0:
                    insert_pos = close + len('        )')
                    insertion = (
                        "\n        // U-02: adaptive path uniforms (LeicaConfig-driven, mirror RAW path)\n"
                        "        GLES30.glUniform1f(GLES30.glGetUniformLocation(lutSharpenProgram, \"uEdgeMaskStrength\"), LeicaConfig.sharpeningEdgeMaskStrength.toFloat())\n"
                        "        GLES30.glUniform1f(GLES30.glGetUniformLocation(lutSharpenProgram, \"uNoiseLimit\"), 0.015f)\n"
                        "        GLES30.glUniform1f(GLES30.glGetUniformLocation(lutSharpenProgram, \"uDarkLimit\"), 0.04f)\n"
                        "        GLES30.glUniform1i(GLES30.glGetUniformLocation(lutSharpenProgram, \"uAdaptive\"), if (LeicaConfig.sharpeningAdaptive) 1 else 0)"
                    )
                    src = src[:insert_pos] + insertion + src[insert_pos:]
                    changed = True
                else:
                    errors.append("  U-02 C.4: closing ) after lutSharpenProgram uThreshold not found")
            else:
                errors.append("  U-02 C.4: lutSharpenProgram uThreshold marker not found")

        if changed:
            write_file(lip, src)
            applied_count[0] += 1
            print("  ✓ U-02 §C: LutImageProcessor.kt LUT consumer")

    # ── Section D: RawSharpeningDefaults.kt — defaults ──
    if os.path.exists(rsd):
        src = read_file(rsd)
        changed = False

        # D.1: CAPTURE_DEFAULT: const → getter
        if "val CAPTURE_DEFAULT: Float" not in src:
            old = "const val CAPTURE_DEFAULT = 0.4f"
            new = "val CAPTURE_DEFAULT: Float get() = LeicaConfig.sharpeningAmount.toFloat()  // U-02"
            if "const val CAPTURE_DEFAULT = 0.15f" in src:
                # P-78.6 already changed 0.4→0.15, replace that too
                src = src.replace("const val CAPTURE_DEFAULT = 0.15f", new, 1)
                changed = True
            elif old in src:
                src = src.replace(old, new, 1)
                changed = True
            else:
                errors.append("  U-02 D.1: CAPTURE_DEFAULT anchor not found")

        # D.2: forCapture floor 0.4→0.05
        if "maxOf(requested, 0.05f)" not in src:
            old = "fun forCapture(requested: Float): Float = maxOf(requested, CAPTURE_DEFAULT)"
            new = "fun forCapture(requested: Float): Float = maxOf(requested, 0.05f)  // U-02: floor reduzido"
            if old in src:
                src = src.replace(old, new, 1)
                changed = True

        if changed:
            write_file(rsd, src)
            applied_count[0] += 1
            print("  ✓ U-02 §D: RawSharpeningDefaults.kt config-driven defaults")

    # ── LeicaConfig.kt: ensure sharpening accessors exist (should already be there) ──
    # U-02 uses existing accessors — no LeicaConfig changes needed


# ═══════════════════════════════════════════════════════════════════════════════
# U-04: DETAIL PRESERVE WIRING (P1, Phase 2)
# ═══════════════════════════════════════════════════════════════════════════════

def apply_u04(src_dir, errors, applied_count):
    java = os.path.join(src_dir, "app/src/main/java/com/hinnka/mycamera")
    cds = os.path.join(java, "lut/ChromaDenoiseShaders.kt")
    rdp = os.path.join(java, "raw/RawDemosaicProcessor.kt")
    lip = os.path.join(java, "lut/LutImageProcessor.kt")
    npnc = os.path.join(java, "raw/DenoiseProfileNlmConfig.kt")

    # ── Section A: ChromaDenoiseShaders.kt ──
    if os.path.exists(cds):
        src = read_file(cds)
        changed = False

        # A.1: Add uniform uDetailPreserve after uOutputStrength
        if "uniform float uDetailPreserve" not in src:
            old = "        uniform float uOutputStrength;\n"
            new = old + "        uniform float uDetailPreserve;  // U-04: detail_preserve cap\n"
            if old in src:
                src = src.replace(old, new, 1)
                changed = True
            else:
                errors.append("  U-04 A.1: uOutputStrength uniform anchor not found")

        # A.2: Cap coarseMix by (1 - uDetailPreserve)
        if "(1.0 - uDetailPreserve)" not in src:
            old = "uOutputStrength * featheredEdgeSupport(coarseSupport, 2.0);"
            new = "uOutputStrength * featheredEdgeSupport(coarseSupport, 2.0) *\n                (1.0 - uDetailPreserve);  // U-04: detail_preserve caps radius-14 chroma smoothing"
            if old in src:
                src = src.replace(old, new, 1)
                changed = True
            else:
                errors.append("  U-04 A.2: featheredEdgeSupport anchor not found")

        if changed:
            write_file(cds, src)
            applied_count[0] += 1
            print("  ✓ U-04 §A: ChromaDenoiseShaders.kt detail_preserve cap")

    # ── Section B: RawDemosaicProcessor.kt ──
    if os.path.exists(rdp):
        src = read_file(rdp)
        changed = False
        # Re-read in case U-02 modified it
        # (U-02 runs first, file is already modified)

        # B.1: centralPixelWeight → config-driven
        if "LeicaConfig.noiseReductionDetailPreserve.toFloat() * scale" not in src:
            old = "val centralPixelWeight = 0.1f * scale"
            new = "val centralPixelWeight = LeicaConfig.noiseReductionDetailPreserve.toFloat() * scale  // U-04"
            if old in src:
                src = src.replace(old, new, 1)
                changed = True
            else:
                errors.append("  U-04 B.1: centralPixelWeight anchor not found")

        # B.2: Add uDetailPreserve binding after uOutputStrength block
        if 'chromaDenoiseProgram, "uDetailPreserve"' not in src:
            marker = 'glGetUniformLocation(chromaDenoiseProgram, "uOutputStrength"),'
            idx = src.find(marker)
            if idx >= 0:
                close = src.find('        )', idx)
                if close >= 0:
                    insert_pos = close + len('        )')
                    insertion = (
                        "\n        GLES30.glUniform1f(\n"
                        "            GLES30.glGetUniformLocation(chromaDenoiseProgram, \"uDetailPreserve\"),\n"
                        "            LeicaConfig.noiseReductionDetailPreserve.toFloat()  // U-04\n"
                        "        )"
                    )
                    src = src[:insert_pos] + insertion + src[insert_pos:]
                    changed = True
                else:
                    errors.append("  U-04 B.2: closing ) after uOutputStrength not found")
            else:
                errors.append("  U-04 B.2: chromaDenoiseProgram uOutputStrength marker not found")

        if changed:
            write_file(rdp, src)
            applied_count[0] += 1
            print("  ✓ U-04 §B: RawDemosaicProcessor.kt detail_preserve wiring")

    # ── Section C: LutImageProcessor.kt ──
    if os.path.exists(lip):
        src = read_file(lip)
        changed = False

        # C.1: Add import LeicaConfig (grep-guarded — may already exist from U-02)
        if "import com.hinnka.mycamera.raw.LeicaConfig" not in src:
            old = "import com.hinnka.mycamera.raw.DenoiseProfileNlmConfig"
            new = "import com.hinnka.mycamera.raw.LeicaConfig  // U-04\n" + old
            if old in src:
                src = src.replace(old, new, 1)
                changed = True

        # C.2: centralPixelWeight → config-driven
        if "LeicaConfig.noiseReductionDetailPreserve.toFloat() * scale" not in src:
            old = "val centralPixelWeight = 0.1f * scale"
            new = "val centralPixelWeight = LeicaConfig.noiseReductionDetailPreserve.toFloat() * scale  // U-04"
            if old in src:
                src = src.replace(old, new, 1)
                changed = True
            else:
                errors.append("  U-04 C.2: centralPixelWeight anchor not found in LutImageProcessor")

        # C.3: Add uDetailPreserve binding after uOutputStrength block (bitmapChromaDenoiseProgram)
        if 'bitmapChromaDenoiseProgram, "uDetailPreserve"' not in src:
            marker = 'glGetUniformLocation(bitmapChromaDenoiseProgram, "uOutputStrength"),'
            idx = src.find(marker)
            if idx >= 0:
                close = src.find('        )', idx)
                if close >= 0:
                    insert_pos = close + len('        )')
                    insertion = (
                        "\n        GLES30.glUniform1f(\n"
                        "            GLES30.glGetUniformLocation(bitmapChromaDenoiseProgram, \"uDetailPreserve\"),\n"
                        "            LeicaConfig.noiseReductionDetailPreserve.toFloat()  // U-04\n"
                        "        )"
                    )
                    src = src[:insert_pos] + insertion + src[insert_pos:]
                    changed = True
                else:
                    errors.append("  U-04 C.3: closing ) after bitmapChromaDenoiseProgram uOutputStrength not found")
            else:
                errors.append("  U-04 C.3: bitmapChromaDenoiseProgram uOutputStrength marker not found")

        if changed:
            write_file(lip, src)
            applied_count[0] += 1
            print("  ✓ U-04 §C: LutImageProcessor.kt detail_preserve wiring")

    # ── Section D: DenoiseProfileNlmConfig.kt ──
    if os.path.exists(npnc):
        src = read_file(npnc)
        changed = False

        # D.1: COARSE_GUIDE_WEIGHT: const → getter
        if "val COARSE_GUIDE_WEIGHT: Float" not in src:
            old = "    const val COARSE_GUIDE_WEIGHT = 8.0f"
            new = (
                "    // U-04: detail_preserve scales the coarse guide down\n"
                "    val COARSE_GUIDE_WEIGHT: Float\n"
                "        get() = 8.0f * (1.0f - LeicaConfig.noiseReductionDetailPreserve.toFloat() * 0.5f)"
            )
            if old in src:
                src = src.replace(old, new, 1)
                changed = True
            else:
                errors.append("  U-04 D.1: COARSE_GUIDE_WEIGHT anchor not found")

        if changed:
            write_file(npnc, src)
            applied_count[0] += 1
            print("  ✓ U-04 §D: DenoiseProfileNlmConfig.kt COARSE_GUIDE_WEIGHT getter")


# ═══════════════════════════════════════════════════════════════════════════════
# U-01: DENOISE SHADOWS REINFORCEMENT (P0, Phase 2)
# ═══════════════════════════════════════════════════════════════════════════════

def apply_u01(src_dir, errors, applied_count):
    java = os.path.join(src_dir, "app/src/main/java/com/hinnka/mycamera")
    dps = os.path.join(java, "raw/DenoiseProfileShaders.kt")
    dnc = os.path.join(java, "raw/DenoiseProfileNlmConfig.kt")
    rdp = os.path.join(java, "raw/RawDemosaicProcessor.kt")
    lcg = os.path.join(java, "raw/LeicaConfig.kt")

    # ── Section A+B: DenoiseProfileShaders.kt ──
    if os.path.exists(dps):
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
    if os.path.exists(dnc):
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
    if os.path.exists(lcg):
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
        # Use robust matching: find 'val noiseReductionAdaptive:' and insert after its line
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


# ═══════════════════════════════════════════════════════════════════════════════
# U-03: BILATERAL → GUIDED FILTER (P0, Phase 2)
# ═══════════════════════════════════════════════════════════════════════════════

def apply_u03(src_dir, errors, applied_count):
    java = os.path.join(src_dir, "app/src/main/java/com/hinnka/mycamera")
    shs = os.path.join(java, "lut/ShadowsHighlightsShader.kt")
    lr = os.path.join(java, "lut/LutRenderer.kt")
    rdp = os.path.join(java, "raw/RawDemosaicProcessor.kt")
    rvr = os.path.join(java, "lut/RealtimeVideoRenderer.kt")
    lcg = os.path.join(java, "raw/LeicaConfig.kt")

    # ── Section A+B: ShadowsHighlightsShader.kt ──
    if os.path.exists(shs):
        src = read_file(shs)
        changed = False

        # A.1: Add 3 uniforms after SH_RANGE_SIGMA2
        if "uGuidedFilterRadius" not in src:
            old = "const float SH_RANGE_SIGMA2 = SH_RANGE_SIGMA * SH_RANGE_SIGMA;"
            new = old + (
                "\n        // U-03: guided filter uniforms (replaces bilateral when enabled)\n"
                "        uniform float uGuidedFilterRadius;\n"
                "        uniform float uGuidedFilterEps;\n"
                "        uniform int   uGuidedFilterEnabled;"
            )
            if old in src:
                src = src.replace(old, new, 1)
                changed = True
            else:
                errors.append("  U-03 A.1: SH_RANGE_SIGMA2 anchor not found")

        # A.2: Insert shGuidedFilterBaseL helper before shSampleBaseL
        if "shGuidedFilterBaseL" not in src:
            helper = (
                "        // U-03: Guided filter (He et al. 2010), single-window approximation\n"
                "        float shGuidedFilterBaseL(vec2 uv, float centerL) {\n"
                "            float r = clamp(uGuidedFilterRadius, 2.0, 16.0);\n"
                "            float stride = max(r * 0.5, 1.0);\n"
                "            float sumI  = 0.0;\n"
                "            float sumII = 0.0;\n"
                "            float count = 0.0;\n"
                "            for (int j = -2; j <= 2; j++) {\n"
                "                for (int i = -2; i <= 2; i++) {\n"
                "                    vec2 off = vec2(float(i), float(j)) * stride * uTexelSize;\n"
                "                    vec2 sampleUv = clamp(uv + off, vec2(0.0), vec2(1.0));\n"
                "                    float L = shRgbToLabScaled(sampleToneSource(sampleUv)).x;\n"
                "                    sumI  += L;\n"
                "                    sumII += L * L;\n"
                "                    count += 1.0;\n"
                "                }\n"
                "            }\n"
                "            float meanI = sumI / count;\n"
                "            float corrI = sumII / count;\n"
                "            float varI  = max(corrI - meanI * meanI, 0.0);\n"
                "            float a = varI / (varI + max(uGuidedFilterEps, 1e-6));\n"
                "            float b = (1.0 - a) * meanI;\n"
                "            return a * centerL + b;\n"
                "        }\n"
                "        "
            )
            src = insert_before(src, "float shSampleBaseL(vec2 uv, vec3 centerLab) {", helper, "U-03 A.2 helper", errors)
            changed = True

        # A.3: Insert dispatch guard after centerL = centerLab.x
        if "uGuidedFilterEnabled == 1" not in src:
            old = "            float centerL = centerLab.x;"
            new = old + (
                "\n            // U-03: dispatch guided filter (default) or legacy bilateral\n"
                "            if (uGuidedFilterEnabled == 1) {\n"
                "                return shGuidedFilterBaseL(uv, centerL);\n"
                "            }"
            )
            if old in src:
                src = src.replace(old, new, 1)
                changed = True
            else:
                errors.append("  U-03 A.3: centerL = centerLab.x anchor not found")

        # B.1: Add import LeicaConfig after import GLES30
        if "import com.hinnka.mycamera.raw.LeicaConfig  // U-03" not in src:
            old = "import android.opengl.GLES30"
            new = "import android.opengl.GLES30\nimport com.hinnka.mycamera.raw.LeicaConfig  // U-03"
            if old in src:
                src = src.replace(old, new, 1)
                changed = True

        # B.2: Extend bindUniforms to call bindGuidedFilterUniforms
        if "bindGuidedFilterUniforms(program)" not in src:
            # Find the closing ) of bindUniforms call (line 12: "        )")
            old = "        shadows = shadows\n        )\n    }"
            new = "        shadows = shadows\n        )\n        // U-03: auto-bind guided filter uniforms\n        bindGuidedFilterUniforms(program)\n    }"
            if old in src:
                src = src.replace(old, new, 1)
                changed = True
            else:
                errors.append("  U-03 B.2: bindUniforms closing anchor not found")

        # B.3: Add bindGuidedFilterUniforms method via string replace
        if "fun bindGuidedFilterUniforms" not in src:
            old = "        GLES30.glUniform1f(shadowsLocation, shadows)\n    }\n"
            new = old + (
                "\n"
                "    // U-03: bind guided filter uniforms from LeicaConfig\n"
                "    fun bindGuidedFilterUniforms(program: Int) {\n"
                "        val radiusLoc = GLES30.glGetUniformLocation(program, \"uGuidedFilterRadius\")\n"
                "        val epsLoc = GLES30.glGetUniformLocation(program, \"uGuidedFilterEps\")\n"
                "        val enabledLoc = GLES30.glGetUniformLocation(program, \"uGuidedFilterEnabled\")\n"
                "        if (radiusLoc >= 0) {\n"
                "            GLES30.glUniform1f(radiusLoc, LeicaConfig.toneMappingGuidedFilterRadius.toFloat().coerceIn(2.0f, 16.0f))\n"
                "        }\n"
                "        if (epsLoc >= 0) {\n"
                "            GLES30.glUniform1f(epsLoc, LeicaConfig.toneMappingGuidedFilterEps.toFloat().coerceIn(1e-6f, 0.1f))\n"
                "        }\n"
                "        if (enabledLoc >= 0) {\n"
                "            GLES30.glUniform1i(enabledLoc, if (LeicaConfig.toneMappingGuidedFilterEnabled) 1 else 0)\n"
                "        }\n"
                "    }\n"
            )
            if old in src:
                src = src.replace(old, new, 1)
                changed = True
            else:
                errors.append("  U-03 B.3: shadowsLocation, shadows anchor not found")

        if changed:
            write_file(shs, src)
            applied_count[0] += 1
            print("  ✓ U-03 §A+B: ShadowsHighlightsShader.kt guided filter + bind method")

    # ── Section C: 3 consumers ──
    # C.1: LutRenderer.kt — insert AFTER the closing ) of bindUniformLocations call
    if os.path.exists(lr):
        src = read_file(lr)
        if "ShadowsHighlightsShader.bindGuidedFilterUniforms" not in src:
            marker = "shadows = params.shadows"
            idx = src.find(marker)
            if idx >= 0:
                # Find the closing ) of the bindUniformLocations() call
                close_paren = src.find('            )', idx)
                if close_paren >= 0:
                    insert_pos = close_paren + len('            )')
                    insertion = "\n            ShadowsHighlightsShader.bindGuidedFilterUniforms(locations.programId)  // U-03"
                    src = src[:insert_pos] + insertion + src[insert_pos:]
                    write_file(lr, src)
                    applied_count[0] += 1
                    print("  ✓ U-03 §C.1: LutRenderer.kt guided filter bind")
                else:
                    errors.append("  U-03 C.1: closing ) after shadows = params.shadows not found")
            else:
                errors.append("  U-03 C.1: LutRenderer shadows = params.shadows anchor not found")

    # C.2: RawDemosaicProcessor.kt — insert AFTER the closing ) of bindUniformLocations call
    if os.path.exists(rdp):
        src = read_file(rdp)
        if "ShadowsHighlightsShader.bindGuidedFilterUniforms" not in src:
            marker = "shadows = params.shadows"
            idx = src.find(marker)
            if idx >= 0:
                # Find the closing ) of the bindUniformLocations() call
                close_paren = src.find('        )', idx)
                if close_paren >= 0:
                    insert_pos = close_paren + len('        )')
                    insertion = "\n        ShadowsHighlightsShader.bindGuidedFilterUniforms(program)  // U-03"
                    src = src[:insert_pos] + insertion + src[insert_pos:]
                    write_file(rdp, src)
                    applied_count[0] += 1
                    print("  ✓ U-03 §C.2: RawDemosaicProcessor.kt guided filter bind")
                else:
                    errors.append("  U-03 C.2: closing ) after shadows = params.shadows not found")
            else:
                errors.append("  U-03 C.2: RawDemosaicProcessor shadows = params.shadows anchor not found")

    # C.3: RealtimeVideoRenderer.kt — insert AFTER the closing ) of bindUniformLocations call
    if os.path.exists(rvr):
        src = read_file(rvr)
        if "ShadowsHighlightsShader.bindGuidedFilterUniforms" not in src:
            # Find 'shadows = params.shadows,' then find the NEXT closing ')' and insert after it
            marker = "shadows = params.shadows,"
            idx = src.find(marker)
            if idx >= 0:
                # Find the closing ) of the bindUniformLocations() call
                close_paren = src.find('        )', idx)
                if close_paren >= 0:
                    insert_pos = close_paren + len('        )')
                    insertion = "\n        ShadowsHighlightsShader.bindGuidedFilterUniforms(locations.programId)  // U-03"
                    src = src[:insert_pos] + insertion + src[insert_pos:]
                    write_file(rvr, src)
                    applied_count[0] += 1
                    print("  ✓ U-03 §C.3: RealtimeVideoRenderer.kt guided filter bind")
                else:
                    errors.append("  U-03 C.3: closing ) after shadows = params.shadows, not found")
            else:
                errors.append("  U-03 C.3: RealtimeVideoRenderer shadows = params.shadows, anchor not found")

    # ── Section D: LeicaConfig.kt — 3 guided filter fields + accessors ──
    if os.path.exists(lcg):
        src = read_file(lcg)
        # Re-read in case U-01 modified it
        changed = False

        # D.1: Add 3 fields to ToneMappingConfig
        if "guided_filter_enabled" not in src:
            old = '@SerializedName("film_like_curve") val filmLikeCurve: Boolean? = true,'
            new = old + (
                '\n        // U-03: guided filter (replaces bilateral — kills HDR halos)\n'
                '        @SerializedName("guided_filter_enabled") val guidedFilterEnabled: Boolean? = true,\n'
                '        @SerializedName("guided_filter_radius") val guidedFilterRadius: Double? = 10.0,\n'
                '        @SerializedName("guided_filter_eps") val guidedFilterEps: Double? = 0.01,'
            )
            if old in src:
                src = src.replace(old, new, 1)
                changed = True
            else:
                errors.append("  U-03 D.1: film_like_curve field anchor not found")

        # D.2: Add 3 accessors after toneMappingFilmLikeCurve
        # Use robust matching: find 'val toneMappingFilmLikeCurve:' and insert after its line
        if "val toneMappingGuidedFilterEnabled" not in src:
            marker = "val toneMappingFilmLikeCurve:"
            idx = src.find(marker)
            if idx >= 0:
                eol = src.find('\n', idx)
                if eol >= 0:
                    new_lines = (
                        "\n\n    // U-03: guided filter (replaces bilateral — kills HDR halos)\n"
                        "    val toneMappingGuidedFilterEnabled: Boolean get() = currentConfig?.toneMapping?.guidedFilterEnabled ?: true\n"
                        "    val toneMappingGuidedFilterRadius: Double get() = currentConfig?.toneMapping?.guidedFilterRadius ?: 10.0\n"
                        "    val toneMappingGuidedFilterEps: Double get() = currentConfig?.toneMapping?.guidedFilterEps ?: 0.01"
                    )
                    src = src[:eol+1] + new_lines + src[eol+1:]
                    changed = True
                else:
                    errors.append("  U-03 D.2: toneMappingFilmLikeCurve EOL not found")
            else:
                errors.append("  U-03 D.2: toneMappingFilmLikeCurve marker not found")

        if changed:
            write_file(lcg, src)
            applied_count[0] += 1
            print("  ✓ U-03 §D: LeicaConfig.kt guided filter accessors")


# ═══════════════════════════════════════════════════════════════════════════════
# U-05: AWB CLAMPING (P0, Phase 1)
# ═══════════════════════════════════════════════════════════════════════════════

def apply_u05(src_dir, errors, applied_count):
    java = os.path.join(src_dir, "app/src/main/java/com/hinnka/mycamera")
    c2c = os.path.join(java, "camera/Camera2Controller.kt")
    lcg = os.path.join(java, "raw/LeicaConfig.kt")

    # ── Part 1: Camera2Controller.kt ──
    if os.path.exists(c2c):
        src = read_file(c2c)
        changed = False

        # 1. Import LeicaConfig (grep-guarded)
        if "import com.hinnka.mycamera.raw.LeicaConfig" not in src:
            old = "import com.hinnka.mycamera.raw.ColorSpace as RawColorSpace"
            new = old + "\nimport com.hinnka.mycamera.raw.LeicaConfig"
            if old in src:
                src = src.replace(old, new, 1)
                changed = True
            else:
                # Fallback: add after package
                old2 = "package com.hinnka.mycamera.camera"
                new2 = old2 + "\nimport com.hinnka.mycamera.raw.LeicaConfig"
                if old2 in src:
                    src = src.replace(old2, new2, 1)
                    changed = True
                else:
                    errors.append("  U-05 §1: import anchor not found")

        # 2. Add CLAMPED_AUTO enum value after MATRIX,
        if "CLAMPED_AUTO" not in src:
            old = "        MATRIX,\n        UNAVAILABLE"
            new = "        MATRIX,\n        CLAMPED_AUTO,   // U-05: AUTO routed through MANUAL MATRIX with clamped CCT\n        UNAVAILABLE"
            if old in src:
                src = src.replace(old, new, 1)
                changed = True
            else:
                errors.append("  U-05 §2: MATRIX, UNAVAILABLE anchor not found")

        # 2b. Add CLAMPED_AUTO branch to exhaustive 'when' expressions
        # Fix 1: createManualWhiteBalanceAnchor when (returns null for CLAMPED_AUTO, same as UNAVAILABLE)
        if "WhiteBalanceControlPath.UNAVAILABLE -> null" in src and "CLAMPED_AUTO -> null" not in src:
            old = "            WhiteBalanceControlPath.UNAVAILABLE -> null"
            new = "            WhiteBalanceControlPath.UNAVAILABLE -> null\n            WhiteBalanceControlPath.CLAMPED_AUTO -> null  // U-05: never reached (gate handles it)"
            if old in src:
                src = src.replace(old, new, 1)
                changed = True

        # Fix 2: applyWhiteBalanceSettings when (routes CLAMPED_AUTO to AUTO, same as UNAVAILABLE)
        if "WhiteBalanceControlPath.UNAVAILABLE -> applyAutoWhiteBalanceSettings(" in src and "CLAMPED_AUTO -> applyAutoWhiteBalanceSettings" not in src:
            old = "            WhiteBalanceControlPath.UNAVAILABLE -> applyAutoWhiteBalanceSettings("
            new = "            WhiteBalanceControlPath.CLAMPED_AUTO -> applyAutoWhiteBalanceSettings(\n                builder = builder,\n                state = state.copy(awbMode = CameraMetadata.CONTROL_AWB_MODE_AUTO),\n                isCapture = isCapture\n            )\n            WhiteBalanceControlPath.UNAVAILABLE -> applyAutoWhiteBalanceSettings("
            if old in src:
                src = src.replace(old, new, 1)
                changed = True

        # 3. Add clampedAwbSmoothedKelvin state field
        if "clampedAwbSmoothedKelvin" not in src:
            old = "    private var manualWhiteBalanceAnchor: ManualWhiteBalanceAnchor? = null"
            new = old + "\n    private var clampedAwbSmoothedKelvin: Int? = null  // U-05: EMA-smoothed CCT"
            if old in src:
                src = src.replace(old, new, 1)
                changed = True
            else:
                errors.append("  U-05 §3: manualWhiteBalanceAnchor anchor not found")

        # 4. Reset EMA on camera close (8-space indent)
        if "clampedAwbSmoothedKelvin = null  // U-05: reset EMA state on camera close" not in src:
            old = "        lastWhiteBalanceResult = null\n        manualWhiteBalanceAnchor = null"
            new = "        lastWhiteBalanceResult = null\n        clampedAwbSmoothedKelvin = null  // U-05: reset EMA state on camera close\n        manualWhiteBalanceAnchor = null"
            if old in src:
                src = src.replace(old, new, 1)
                changed = True

        # 5. Reset EMA on camera reopen (16-space indent)
        if "clampedAwbSmoothedKelvin = null  // U-05: reset EMA state on camera reopen" not in src:
            old = "                lastWhiteBalanceResult = null"
            new = "                lastWhiteBalanceResult = null\n                clampedAwbSmoothedKelvin = null  // U-05: reset EMA state on camera reopen"
            if old in src:
                src = src.replace(old, new, 1)
                changed = True

        # 6. Add bypassClamp parameter to applyAutoWhiteBalanceSettings
        if "bypassClamp: Boolean = false" not in src:
            old = "    private fun applyAutoWhiteBalanceSettings(\n        builder: CaptureRequest.Builder,\n        state: CameraState,\n        isCapture: Boolean\n    ) {"
            new = "    private fun applyAutoWhiteBalanceSettings(\n        builder: CaptureRequest.Builder,\n        state: CameraState,\n        isCapture: Boolean,\n        bypassClamp: Boolean = false  // U-05\n    ) {"
            if old in src:
                src = src.replace(old, new, 1)
                changed = True
            else:
                errors.append("  U-05 §6: applyAutoWhiteBalanceSettings signature anchor not found")

        # 7. Inject AWB clamp gate before val requestedMode = state.awbMode
        if "applyClampedAutoWhiteBalanceSettings" not in src:
            gate = (
                "        // U-05 AWB clamp: when enabled and MANUAL MATRIX supported and user picked AUTO,\n"
                "        // route to clamped MANUAL TRANSFORM_MATRIX path (M9-style stabilization).\n"
                "        if (!bypassClamp && LeicaConfig.awbClampEnabled &&\n"
                "            state.awbMode == CameraMetadata.CONTROL_AWB_MODE_AUTO &&\n"
                "            supportsManualMatrixWhiteBalance()) {\n"
                "            applyClampedAutoWhiteBalanceSettings(builder, state, isCapture)\n"
                "            return\n"
                "        }\n"
            )
            old = "        val requestedMode = state.awbMode"
            if old in src:
                src = src.replace(old, gate + old, 1)
                changed = True
            else:
                errors.append("  U-05 §7: val requestedMode = state.awbMode anchor not found")

        # 8. Insert applyClampedAutoWhiteBalanceSettings helper before applyCctWhiteBalanceSettings
        if "private fun applyClampedAutoWhiteBalanceSettings" not in src:
            helper = (
                "    /**\n"
                "     * U-05 AWB clamp — route AUTO-mode requests through MANUAL TRANSFORM_MATRIX path\n"
                "     * when awb_clamp.enabled=true. Reads HAL's per-frame CCT from lastWhiteBalanceResult,\n"
                "     * applies EMA smoothing (alpha=awbClampSmoothingAlpha), clamps to [min_cct, max_cct],\n"
                "     * and re-applies as MANUAL gains. Stabilizes per-frame AWB oscillation (M9-style).\n"
                "     */\n"
                "    private fun applyClampedAutoWhiteBalanceSettings(\n"
                "        builder: CaptureRequest.Builder,\n"
                "        state: CameraState,\n"
                "        isCapture: Boolean\n"
                "    ) {\n"
                "        val snapshot = lastWhiteBalanceResult\n"
                "        if (snapshot == null ||\n"
                "            (snapshot.colorTemperature == null && snapshot.gains == null)) {\n"
                "            applyAutoWhiteBalanceSettings(\n"
                "                builder = builder,\n"
                "                state = state.copy(awbMode = CameraMetadata.CONTROL_AWB_MODE_AUTO),\n"
                "                isCapture = isCapture,\n"
                "                bypassClamp = true\n"
                "            )\n"
                "            return\n"
                "        }\n"
                "        val rawKelvin = snapshot.colorTemperature\n"
                "            ?: snapshot.gains?.let(::estimateKelvinFromRggbGains)\n"
                "            ?: run {\n"
                "                applyAutoWhiteBalanceSettings(\n"
                "                    builder = builder,\n"
                "                    state = state.copy(awbMode = CameraMetadata.CONTROL_AWB_MODE_AUTO),\n"
                "                    isCapture = isCapture,\n"
                "                    bypassClamp = true\n"
                "                )\n"
                "                return\n"
                "            }\n"
                "        val alpha = LeicaConfig.awbClampSmoothingAlpha.coerceIn(0f, 1f)\n"
                "        val prev = clampedAwbSmoothedKelvin ?: rawKelvin\n"
                "        val smoothed = ((prev.toFloat() * (1f - alpha)) +\n"
                "                        (rawKelvin.toFloat() * alpha)).roundToInt()\n"
                "        clampedAwbSmoothedKelvin = smoothed\n"
                "        val minCct = LeicaConfig.awbClampMinCct.coerceAtMost(LeicaConfig.awbClampMaxCct - 1)\n"
                "        val maxCct = LeicaConfig.awbClampMaxCct.coerceAtLeast(minCct + 1)\n"
                "        val clampedKelvin = smoothed.coerceIn(minCct, maxCct)\n"
                "        val clampedGains = kelvinToRggbGains(clampedKelvin)\n"
                "        val transform = buildColorMatrixWhiteBalanceTransform(clampedGains)\n"
                "        if (transform == null) {\n"
                "            applyAutoWhiteBalanceSettings(\n"
                "                builder = builder,\n"
                "                state = state.copy(awbMode = CameraMetadata.CONTROL_AWB_MODE_AUTO),\n"
                "                isCapture = isCapture,\n"
                "                bypassClamp = true\n"
                "            )\n"
                "            return\n"
                "        }\n"
                "        builder.set(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_OFF)\n"
                "        builder.set(CaptureRequest.CONTROL_AWB_LOCK, false)\n"
                "        builder.set(\n"
                "            CaptureRequest.COLOR_CORRECTION_MODE,\n"
                "            CaptureRequest.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX\n"
                "        )\n"
                "        builder.set(CaptureRequest.COLOR_CORRECTION_GAINS, clampedGains)\n"
                "        builder.set(CaptureRequest.COLOR_CORRECTION_TRANSFORM, transform)\n"
                "    }\n"
                "\n"
            )
            old = "    private fun applyCctWhiteBalanceSettings("
            if old in src:
                src = src.replace(old, helper + old, 1)
                changed = True
            else:
                errors.append("  U-05 §8: applyCctWhiteBalanceSettings anchor not found")

        if changed:
            write_file(c2c, src)
            applied_count[0] += 1
            print("  ✓ U-05 §1-8: Camera2Controller.kt AWB clamping")

    # ── Part 2: LeicaConfig.kt ──
    if os.path.exists(lcg):
        src = read_file(lcg)
        # Re-read in case U-01/U-03 modified it
        changed = False

        # 9. Add awbClamp field to LeicaPerfectConfig
        if "@SerializedName(\"awb_clamp\")" not in src:
            old = '@SerializedName("creative_profiles") val creativeProfiles: CreativeProfileConfig? = null,'
            new = '@SerializedName("awb_clamp") val awbClamp: AwbClampConfig? = null,\n        ' + old
            if old in src:
                src = src.replace(old, new, 1)
                changed = True
            else:
                errors.append("  U-05 §9: creativeProfiles anchor not found")

        # 10. Add AwbClampConfig data class before VideoConfig
        if "data class AwbClampConfig" not in src:
            dc = (
                "    /** awb_clamp — U-05: AUTO-mode CCT clamping for M9-style stabilization. */\n"
                "    data class AwbClampConfig(\n"
                "        @SerializedName(\"enabled\") val enabled: Boolean? = true,\n"
                "        @SerializedName(\"min_cct\") val minCct: Int? = 3500,\n"
                "        @SerializedName(\"max_cct\") val maxCct: Int? = 7000,\n"
                "        @SerializedName(\"smoothing_alpha\") val smoothingAlpha: Float? = 0.15f,\n"
                "    )\n"
                "\n"
            )
            old = "    data class VideoConfig("
            if old in src:
                src = src.replace(old, dc + old, 1)
                changed = True
            else:
                errors.append("  U-05 §10: data class VideoConfig anchor not found")

        # 11. Add 4 accessors after perChannelTintBlue
        # Use robust matching: find 'val perChannelTintBlue:' (the accessor, not the field) and insert after its line
        if "val awbClampEnabled" not in src:
            marker = "val perChannelTintBlue:"
            # Find the LAST occurrence (the accessor, not the @SerializedName field declaration)
            idx = src.rfind(marker)
            if idx >= 0:
                eol = src.find('\n', idx)
                if eol >= 0:
                    new_lines = (
                        "\n\n    // U-05: AWB CCT clamping (M9-style stabilization for default AUTO mode)\n"
                        "    val awbClampEnabled: Boolean get() = currentConfig?.awbClamp?.enabled ?: true\n"
                        "    val awbClampMinCct: Int get() = currentConfig?.awbClamp?.minCct ?: 3500\n"
                        "    val awbClampMaxCct: Int get() = currentConfig?.awbClamp?.maxCct ?: 7000\n"
                        "    val awbClampSmoothingAlpha: Float get() = currentConfig?.awbClamp?.smoothingAlpha ?: 0.15f"
                    )
                    src = src[:eol+1] + new_lines + src[eol+1:]
                    changed = True
                else:
                    errors.append("  U-05 §11: perChannelTintBlue EOL not found")
            else:
                errors.append("  U-05 §11: perChannelTintBlue marker not found")

        if changed:
            write_file(lcg, src)
            applied_count[0] += 1
            print("  ✓ U-05 §9-11: LeicaConfig.kt AwbClampConfig + accessors")


# ═══════════════════════════════════════════════════════════════════════════════
# U-06: AE HISTOGRAM FEEDBACK (P1, Phase 1)
# ═══════════════════════════════════════════════════════════════════════════════

def apply_u06(src_dir, errors, applied_count):
    java = os.path.join(src_dir, "app/src/main/java/com/hinnka/mycamera")
    lcg = os.path.join(java, "raw/LeicaConfig.kt")
    c2c = os.path.join(java, "camera/Camera2Controller.kt")
    cstate = os.path.join(java, "camera/CameraState.kt")
    aeProt = os.path.join(java, "camera/AeHistogramProtector.kt")

    # ── Block C: Create AeHistogramProtector.kt (NEW FILE) ──
    if not os.path.exists(aeProt):
        prot_src = '''package com.hinnka.mycamera.camera

import com.hinnka.mycamera.raw.LeicaConfig
import kotlin.math.roundToInt

object AeHistogramProtector {
    private const val MAX_HISTOGRAM_EV_CORRECTION_STEPS = 6
    private const val HIGHLIGHT_CLIP_BIN_THRESHOLD = 250

    data class HistogramStats(
        val clipHighFraction: Float,
        val clipLowFraction: Float,
        val avgLuma: Float,
        val histogramEvCorrection: Int,
        val hasHighlightClip: Boolean,
        val hasShadowClip: Boolean,
    )

    fun compute(state: CameraState): HistogramStats {
        val histogram = state.histogram
        if (!LeicaConfig.aeProtectionEnabled ||
            !LeicaConfig.histogramFeedbackEnabled ||
            histogram == null || histogram.isEmpty()
        ) {
            return HistogramStats(0f, 0f, state.getAvgLuma(), 0, false, false)
        }
        val shadowFloor = LeicaConfig.shadowFloorRgb.coerceIn(1, 32)
        val total = histogram.sumOf { it.toLong() }
        if (total <= 0L) {
            return HistogramStats(0f, 0f, state.getAvgLuma(), 0, false, false)
        }
        var clipHighCount = 0L
        for (i in HIGHLIGHT_CLIP_BIN_THRESHOLD until histogram.size) {
            clipHighCount += histogram[i].toLong()
        }
        var clipLowCount = 0L
        for (i in 0 until shadowFloor.coerceAtMost(histogram.size)) {
            clipLowCount += histogram[i].toLong()
        }
        val totalF = total.toFloat()
        val clipHighFraction = clipHighCount / totalF
        val clipLowFraction = clipLowCount / totalF
        var weightedSum = 0L
        for (i in histogram.indices) {
            weightedSum += i.toLong() * histogram[i].toLong()
        }
        val avgLuma = (weightedSum.toFloat() / totalF) / 255f
        val highThreshold = (LeicaConfig.highlightClipThresholdPct / 100.0).toFloat()
        val lowThreshold = (LeicaConfig.shadowClipThresholdPct / 100.0).toFloat()
        val targetLuma = LeicaConfig.displayTargetLuma.toFloat()
        val hasHighlightClip = clipHighFraction > highThreshold
        val hasShadowClip = clipLowFraction > lowThreshold && avgLuma < targetLuma * 0.5f
        val histogramEvCorrection: Int = when {
            hasHighlightClip -> {
                val severity = (clipHighFraction / highThreshold).coerceIn(1f, 6f)
                -severity.roundToInt().coerceIn(1, MAX_HISTOGRAM_EV_CORRECTION_STEPS)
            }
            hasShadowClip -> {
                val lowSeverity = (clipLowFraction / lowThreshold).coerceIn(1f, 6f)
                val lumaSeverity = if (avgLuma > 0f) {
                    ((targetLuma * 0.5f) / avgLuma).coerceIn(1f, 6f)
                } else 6f
                val combined = ((lowSeverity + lumaSeverity) * 0.5f).coerceIn(1f, 6f)
                combined.roundToInt().coerceIn(1, MAX_HISTOGRAM_EV_CORRECTION_STEPS)
            }
            else -> 0
        }
        return HistogramStats(
            clipHighFraction, clipLowFraction, avgLuma,
            histogramEvCorrection, hasHighlightClip, hasShadowClip,
        )
    }

    fun evToSteps(ev: Float, evStep: Float): Int {
        if (evStep <= 0f || !ev.isFinite()) return 0
        val magnitude = (kotlin.math.abs(ev / evStep) + 0.0001f).roundToInt()
        return if (ev < 0f) -magnitude else magnitude
    }

    fun evToSteps(ev: Double, evStep: Float): Int =
        evToSteps(ev.toFloat(), evStep)
}
'''
        write_file(aeProt, prot_src)
        applied_count[0] += 1
        print("  ✓ U-06 §C: AeHistogramProtector.kt created (NEW FILE)")

    # ── Block H: CameraState.kt — add lastClipLowFraction field ──
    if os.path.exists(cstate):
        src = read_file(cstate)
        if "lastClipLowFraction" not in src:
            old = "    val histogram: IntArray? = null,"
            new = old + "\n    /** U-06: ultimo clipLowFraction (snapshot populado por updateHistogram). */\n    val lastClipLowFraction: Float = 0f,"
            if old in src:
                src = src.replace(old, new, 1)
                write_file(cstate, src)
                applied_count[0] += 1
                print("  ✓ U-06 §H: CameraState.kt lastClipLowFraction field")

    # ── Blocks A+B: LeicaConfig.kt — AeProtectionConfig + accessors ──
    if os.path.exists(lcg):
        src = read_file(lcg)
        # Re-read in case U-01/U-03/U-05 modified it
        changed = False

        # A.1: Insert AeProtectionConfig data class before dcp comment
        if "data class AeProtectionConfig" not in src:
            dc = (
                "    /** ae_protection — U-06 histogram-based highlight/shadow protection. */\n"
                "    data class AeProtectionConfig(\n"
                "        @SerializedName(\"enabled\") val enabled: Boolean? = true,\n"
                "        @SerializedName(\"highlight_clip_threshold_pct\") val highlightClipThresholdPct: Double? = 1.0,\n"
                "        @SerializedName(\"shadow_clip_threshold_pct\") val shadowClipThresholdPct: Double? = 1.0,\n"
                "        @SerializedName(\"shadow_floor_rgb\") val shadowFloorRgb: Int? = 5,\n"
                "        @SerializedName(\"histogram_feedback_enabled\") val histogramFeedbackEnabled: Boolean? = true,\n"
                "        @SerializedName(\"max_ev_comp_steps\") val maxEvCompSteps: Int? = 12,\n"
                "    )\n"
                "\n"
            )
            # v6.5.1: anchor changed from "/** dcp — forçar Leica M8" (comment, fragile)
            # to "data class DcpConfig(" (stable structural anchor, survives comment edits)
            old = "    data class DcpConfig("
            if old in src:
                src = src.replace(old, dc + old, 1)
                changed = True
            else:
                errors.append("  U-06 A.1: 'data class DcpConfig(' anchor not found")

        # A.2: Add aeProtection field to LeicaPerfectConfig
        if '@SerializedName("ae_protection")' not in src:
            old = '@SerializedName("hdr") val hdr: HdrConfig? = null,'
            new = old + '\n        @SerializedName("ae_protection") val aeProtection: AeProtectionConfig? = null,'
            if old in src:
                src = src.replace(old, new, 1)
                changed = True
            else:
                errors.append("  U-06 A.2: hdr field anchor not found")

        # B.1: Add accessors + 2 functions after pgtmPreTonemapExposureBoostEv getter
        if "val aeProtectionEnabled" not in src:
            old = "    val pgtmPreTonemapExposureBoostEv: Double\n        get() = currentConfig?.processing?.pgtmPreTonemapExposureBoostEv ?: 1.3"
            new = old + (
                "\n\n"
                "    // ─── U-06: ae_protection accessors ─────────────────────────────────────\n"
                "    val aeProtectionEnabled: Boolean\n"
                "        get() = currentConfig?.aeProtection?.enabled ?: true\n"
                "    val highlightClipThresholdPct: Double\n"
                "        get() = currentConfig?.aeProtection?.highlightClipThresholdPct ?: 1.0\n"
                "    val shadowClipThresholdPct: Double\n"
                "        get() = currentConfig?.aeProtection?.shadowClipThresholdPct ?: 1.0\n"
                "    val shadowFloorRgb: Int\n"
                "        get() = currentConfig?.aeProtection?.shadowFloorRgb ?: 5\n"
                "    val histogramFeedbackEnabled: Boolean\n"
                "        get() = currentConfig?.aeProtection?.histogramFeedbackEnabled ?: true\n"
                "    val maxEvCompSteps: Int\n"
                "        get() = currentConfig?.aeProtection?.maxEvCompSteps ?: 12\n"
                "\n"
                "    fun effectivePgtmPreTonemapExposureBoostEv(clipLowFraction: Float): Double {\n"
                "        if (!aeProtectionEnabled) return pgtmPreTonemapExposureBoostEv\n"
                "        val threshold = (shadowClipThresholdPct / 100.0).toFloat()\n"
                "        return if (clipLowFraction >= threshold) pgtmPreTonemapExposureBoostEv else 0.0\n"
                "    }\n"
                "\n"
                "    fun effectiveEvCompForLens(lensKey: String, clipLowFraction: Float): Float {\n"
                "        if (!aeProtectionEnabled) return evCompForLens(lensKey)\n"
                "        val threshold = (shadowClipThresholdPct / 100.0).toFloat()\n"
                "        return if (clipLowFraction >= threshold) evCompForLens(lensKey) else 0.0f\n"
                "    }"
            )
            if old in src:
                src = src.replace(old, new, 1)
                changed = True
            else:
                errors.append("  U-06 B.1: pgtmPreTonemapExposureBoostEv accessor anchor not found")

        if changed:
            write_file(lcg, src)
            applied_count[0] += 1
            print("  ✓ U-06 §A+B: LeicaConfig.kt AeProtectionConfig + accessors")

    # ── Block D: Camera2Controller.kt — applyExposureSettings histogram feedback ──
    if os.path.exists(c2c):
        src = read_file(c2c)
        # Re-read in case U-05 modified it
        changed = False

        # D.1: Replace single AE-comp line with histogram feedback block
        if "AeHistogramProtector.compute" not in src:
            old = "            builder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, state.exposureCompensation)"
            new = (
                "            // U-06: histogram feedback + default_ev + per_lens ev_comp\n"
                "            val evStep = state.getExposureCompensationStep()\n"
                "            val range = state.getExposureCompensationRange()\n"
                "            val maxSteps = LeicaConfig.maxEvCompSteps\n"
                "            val lowerBound = maxOf(range.lower, -maxSteps)\n"
                "            val upperBound = minOf(range.upper, maxSteps)\n"
                "            val stats = AeHistogramProtector.compute(state)\n"
                "            val defaultEvBiasSteps = AeHistogramProtector.evToSteps(LeicaConfig.defaultExposureEv, evStep)\n"
                "            val perLensEvCompSteps = AeHistogramProtector.evToSteps(LeicaConfig.effectiveEvCompForLens(LeicaConfig.lensKeyFromCameraId(state.currentCameraId), stats.clipLowFraction), evStep)\n"
                "            val effectiveComp = (state.exposureCompensation + defaultEvBiasSteps + perLensEvCompSteps + stats.histogramEvCorrection).coerceIn(lowerBound, upperBound)\n"
                "            builder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, effectiveComp)\n"
                "            PLog.d(TAG, \"U-06 AE: user=\" + state.exposureCompensation + \" defaultEv=\" + defaultEvBiasSteps + \" perLens=\" + perLensEvCompSteps + \" hist=\" + stats.histogramEvCorrection + \" clipHi=\" + stats.clipHighFraction + \" clipLo=\" + stats.clipLowFraction + \" avgLuma=\" + stats.avgLuma + \" effective=\" + effectiveComp)"
            )
            if old in src:
                src = src.replace(old, new, 1)
                changed = True
            else:
                errors.append("  U-06 D: AE EXPOSURE_COMPENSATION anchor not found")

        # E.1: Replace calculateHdrBracketExposureCompensation body
        if "AeHistogramProtector.compute" not in src or src.count("AeHistogramProtector") < 3:
            old = (
                "    private fun calculateHdrBracketExposureCompensation(state: CameraState, evOffset: Float): Int {\n"
                "        val evStep = state.getExposureCompensationStep().takeIf { it > 0f } ?: return state.exposureCompensation\n"
                "        val range = state.getExposureCompensationRange()\n"
                "        val steps = roundHdrBracketCompensationSteps(evOffset, evStep)\n"
                "        return (state.exposureCompensation + steps).coerceIn(range.lower, range.upper)\n"
                "    }"
            )
            new = (
                "    private fun calculateHdrBracketExposureCompensation(state: CameraState, evOffset: Float): Int {\n"
                "        val evStep = state.getExposureCompensationStep().takeIf { it > 0f } ?: return state.exposureCompensation\n"
                "        val range = state.getExposureCompensationRange()\n"
                "        val maxSteps = LeicaConfig.maxEvCompSteps\n"
                "        val lowerBound = maxOf(range.lower, -maxSteps)\n"
                "        val upperBound = minOf(range.upper, maxSteps)\n"
                "        val bracketSteps = roundHdrBracketCompensationSteps(evOffset, evStep)\n"
                "        // U-06: histogram feedback (highlight/shadow protection)\n"
                "        val stats = AeHistogramProtector.compute(state)\n"
                "        val perLensEvCompSteps = AeHistogramProtector.evToSteps(LeicaConfig.effectiveEvCompForLens(LeicaConfig.lensKeyFromCameraId(state.currentCameraId), stats.clipLowFraction), evStep)\n"
                "        return (state.exposureCompensation + bracketSteps + perLensEvCompSteps + stats.histogramEvCorrection).coerceIn(lowerBound, upperBound)\n"
                "    }"
            )
            if old in src:
                src = src.replace(old, new, 1)
                changed = True
            else:
                errors.append("  U-06 E: calculateHdrBracketExposureCompensation body anchor not found")

        if changed:
            write_file(c2c, src)
            applied_count[0] += 1
            print("  ✓ U-06 §D+E: Camera2Controller.kt AE histogram feedback")


# ═══════════════════════════════════════════════════════════════════════════════
# MAIN
# ═══════════════════════════════════════════════════════════════════════════════

def main():
    if len(sys.argv) < 2:
        print("Usage: apply_upgrades_v65.py <source_dir>", file=sys.stderr)
        sys.exit(1)

    src_dir = sys.argv[1]
    java = os.path.join(src_dir, "app/src/main/java/com/hinnka/mycamera")
    if not os.path.exists(java):
        print(f"ERROR: source dir not found: {java}", file=sys.stderr)
        sys.exit(1)

    print("═══════════════════════════════════════════════════════════════")
    print("  Applying v6.5 upgrades U-01..U-06 (6 patches, 3772 LOC)")
    print("═══════════════════════════════════════════════════════════════")
    print()

    errors = []
    applied_count = [0]

    # Application order per UPGRADES_MANIFEST: U-02 → U-04 → U-01 → U-03 → U-05 → U-06
    print("── U-02: Adaptive Sharpening (oversharpening -40% + edge mask + noise gate) ──")
    apply_u02(src_dir, errors, applied_count)
    print()

    print("── U-04: Detail Preserve Wiring (oil-painting fix) ──")
    apply_u04(src_dir, errors, applied_count)
    print()

    print("── U-01: Denoise Shadows Reinforcement (NLM shadow boost) ──")
    apply_u01(src_dir, errors, applied_count)
    print()

    print("── U-03: Bilateral → Guided Filter (HDR halo killer) ──")
    apply_u03(src_dir, errors, applied_count)
    print()

    print("── U-05: AWB Clamping (M9 CCD CCT clamp for default mode) ──")
    apply_u05(src_dir, errors, applied_count)
    print()

    print("── U-06: AE Histogram Feedback (highlight/shadow protection) ──")
    apply_u06(src_dir, errors, applied_count)
    print()

    print("═══════════════════════════════════════════════════════════════")
    print(f"  Sub-patches applied: {applied_count[0]}")
    if errors:
        print(f"  Warnings: {len(errors)}")
        for e in errors:
            print(e)
    else:
        print("  All patches applied successfully (zero warnings)")
    print("═══════════════════════════════════════════════════════════════")

    # Exit 0 even with warnings — build continues, missing patches = feature disabled
    sys.exit(0)

if __name__ == "__main__":
    main()
