// ═══════════════════════════════════════════════════════════════════════════════
// U02_AdaptiveSharpening.patch.kt — U-02 (v6.3) — Adaptive Sharpening
// ═══════════════════════════════════════════════════════════════════════════════
//
// ⚠️  DOCUMENTATION-ONLY — Este arquivo NÃO é compilado. Mostra o que o sed em
//     build-archlinux.sh cmd_patch() U-02 injeta no upstream PhotonCamera.
//
// ⭐⭐ PATCH STATUS: REAL — actually effective at runtime. ⭐⭐
//     Fecha U-02 (oversharpening + falta de edge mask / noise gating).
//
// O QUE FAZ (4 targets, 11 seds):
//
//   A) SHADER  — RawShaders.kt::SHARPEN_FRAGMENT_SHADER
//      - Adiciona 4 novos uniforms: uEdgeMaskStrength, uNoiseLimit, uDarkLimit, uAdaptive
//      - Adiciona 2 helpers portados do Phocus reference (CalcNoise/CalcAmount simplificados)
//      - Substitui o bloco final de `float centerLuma = ...` por versão adaptive:
//          * Sobel 4-tap edge mask (1.0 - exp(-edgeMag * uEdgeMaskStrength))
//          * CalcNoise(uNoiseLimit, |delta|) — 0 em flat noise, 1 em real edges
//          * CalcAmount(uSharpening, uDarkLimit, centerLuma) — smoothstep ramp 0..1
//          * usmFactor = amount × noise × edgeMask × detail  (Phocus-style)
//      - Mantém legacy path quando uAdaptive == 0 (backward-compat com P-3)
//
//   B) RAW CONSUMER — RawDemosaicProcessor.kt::renderSharpenPass
//      - Troca `val defaultUsmRadius`/`defaultUsmThreshold` por getters config-driven
//        (LeicaConfig.sharpeningRadius.toFloat() / sharpeningThreshold.toFloat())
//      - Bind uEdgeMaskStrength, uNoiseLimit, uDarkLimit, uAdaptive
//      - Bind uRadius/uThreshold a partir dos novos getters (não mais RawShaders const)
//
//   C) LUT CONSUMER — LutImageProcessor.kt::renderLutSharpenPass
//      - Adiciona `import com.hinnka.mycamera.raw.LeicaConfig` (LutImageProcessor é
//        package com.hinnka.mycamera.lut, não vê LeicaConfig sem import)
//      - Troca RawShaders.DEFAULT_USM_RADIUS/THRESHOLD por LeicaConfig.sharpening*
//      - Bind uEdgeMaskStrength, uNoiseLimit, uDarkLimit, uAdaptive (idêntico ao RAW)
//
//   D) DEFAULTS — RawSharpeningDefaults.kt
//      - CAPTURE_DEFAULT deixa de ser hardcoded 0.4f — passa a ser
//        LeicaConfig.sharpeningAmount.toFloat() (default config 0.09)
//      - forCapture() floors em 0.05f (em vez de 0.4f) — pequeno mínimo anti-noop
//
// POR QUE:
//   - VLM pixel analysis (Task 3-c) identificou oversharpening 15-40% com halos 2-4px.
//   - Shader original (P-3): USM simples 5x5 + deadband threshold. Sem edge mask,
//     sem noise gating, sem brightness-adaptive — sharpening aplicado uniformemente
//     em flat noise regions (amplifica noise) e em edges (halos).
//   - User config leica_perfect.json tem:
//       sharpening.amount              = 0.09  (vs 0.4 hardcoded)
//       sharpening.radius              = 0.9   (vs 2.0 hardcoded)
//       sharpening.threshold           = 0.003 (vs 0.005 hardcoded)
//       sharpening.edge_mask_strength  = 2.2   (NEW — sem uniform antes de U-02)
//       sharpening.adaptive            = true  (NEW — flag p/ ativar path adaptive)
//   - Mas RawSharpeningDefaults.forCapture() floor em 0.4f — anulava amount=0.09
//     para RAW captures (floor OVERRIDES config). U-02 remove esse floor.
//   - Phocus reference shader (research/phocus_glsl/06_detail_texture/
//     unsharpMask_0x300e6ad.frag) tem CalcNoise + CalcAmount + usmFactor = amount ×
//     noise × diff — portamos versão simplificada desses helpers.
//
// LeicaConfig ACCESSORS USADOS:
//   - LeicaConfig.sharpeningAmount: Double          (default 0.09)
//   - LeicaConfig.sharpeningRadius: Double          (default 0.9)
//   - LeicaConfig.sharpeningThreshold: Double       (default 0.003)
//   - LeicaConfig.sharpeningEdgeMaskStrength: Double (default 2.2)
//   - LeicaConfig.sharpeningAdaptive: Boolean       (default true)
//
// RISK / SCOPE NOTES:
//   - Shader é compartilhado entre RAW (RawDemosaicProcessor) e LUT post-edit
//     (LutImageProcessor). U-02 patcheia AMBOS em lockstep — gallery re-edit path
//     produz mesmo sharpening que capture path.
//   - 3º consumer (GlesRawRadianceStacker L8340-L8386) é alignment-proxy interno,
//     NÃO output sharpening — INTENCIONALMENTE não tocado por U-02.
//   - uAdaptive == 0 preserva legacy path (P-3 behavior) — útil para A/B testing
//     e para fixtures/tests que assumem o shader original.
//   - Performance: +4 texture lookups (Sobel 4-tap) + ~10 ALU ops/pixel. Em
//     full-res RGBA8 FBO (~12MP), custo estimado <0.5ms em Adreno 740.
//   - Phocus reference usa ShaderParams UBO (std140) — nós usamos uniforms soltos
//     por compatibilidade com o existing P-3 uniform binding style.
// ═══════════════════════════════════════════════════════════════════════════════

package com.hinnka.mycamera.raw

import com.hinnka.mycamera.raw.LeicaConfig

// ═══════════════════════════════════════════════════════════════════════════════
// SECTION A — SHADER: RawShaders.kt::SHARPEN_FRAGMENT_SHADER (L1679-L1728)
// ═══════════════════════════════════════════════════════════════════════════════

// ─── ANTES (P-3 shader — non-adaptive, no edge mask, no noise gating) ────────
val SHARPEN_FRAGMENT_SHADER = """
    #version 300 es
    precision highp float;

    in vec2 vTexCoord;
    out vec4 fragColor;

    uniform sampler2D uInputTexture;
    uniform vec2 uTexelSize;
    uniform float uSharpening;
    uniform float uRadius;
    uniform float uThreshold;

    float luminance(vec3 color) {
        return dot(color, vec3(0.2126, 0.7152, 0.0722));
    }

    void main() {
        vec3 center = texture(uInputTexture, vTexCoord).rgb;
        if (uSharpening <= 0.0) {
            fragColor = vec4(center, 1.0);
            return;
        }

        float r = max(uRadius, 0.001);
        float sigma = max(r * 0.5, 0.001);
        float twoSigma2 = 2.0 * sigma * sigma;
        vec3 blur = vec3(0.0);
        float weightSum = 0.0;

        for (int y = -2; y <= 2; y++) {
            for (int x = -2; x <= 2; x++) {
                vec2 offset = vec2(float(x), float(y));
                float dist2 = dot(offset, offset);
                float weight = exp(-dist2 / twoSigma2);
                blur += texture(uInputTexture, vTexCoord + offset * uTexelSize * r).rgb * weight;
                weightSum += weight;
            }
        }
        blur /= max(weightSum, 1e-5);

        float centerLuma = luminance(center);
        float blurLuma = luminance(blur);
        float delta = centerLuma - blurLuma;
        float detail = sign(delta) * max(abs(delta) - uThreshold, 0.0);
        vec3 result = center + center * (detail / max(centerLuma, 1e-5)) * uSharpening;

        fragColor = vec4(clamp(result, 0.0, 1.0), 1.0);
    }
""".trimIndent()

// ─── DEPOIS (U-02 — adaptive edge mask + noise gate + brightness-adaptive) ───
val SHARPEN_FRAGMENT_SHADER = """
    #version 300 es
    precision highp float;

    in vec2 vTexCoord;
    out vec4 fragColor;

    uniform sampler2D uInputTexture;
    uniform vec2 uTexelSize;
    uniform float uSharpening;
    uniform float uRadius;
    uniform float uThreshold;
    uniform float uEdgeMaskStrength;   // U-02: edge mask power (0=off, 2.2=typical)
    uniform float uNoiseLimit;         // U-02: noise gating threshold (luma units, 0-1)
    uniform float uDarkLimit;          // U-02: dark-region luma threshold (0-1)
    uniform int   uAdaptive;           // U-02: 1=adaptive path, 0=legacy P-3 path

    float luminance(vec3 color) {
        return dot(color, vec3(0.2126, 0.7152, 0.0722));
    }

    // ── U-02: noise gating (simplified port of Phocus CalcNoise) ─────────────
    // Returns 0..1: 0 when |diff| well below noiseLimit (just noise), 1 when
    // |diff| well above noiseLimit (real edge detail). Smoothstep ramp.
    float CalcNoise(float noiseLimit, float absDiff) {
        if (noiseLimit <= 0.0) return 1.0;
        float range = max(noiseLimit * 0.25, 1.0 / 16384.0);
        float t = clamp((absDiff - (noiseLimit - range)) / range, 0.0, 1.0);
        return t * t * (3.0 - 2.0 * t);  // smoothstep
    }

    // ── U-02: brightness-adaptive amount (simplified port of Phocus CalcAmount)
    // Reduces sharpening in dark regions (where sensor noise lives). Smoothstep
    // ramp from 0 at darkLimit/2 to full at darkLimit*1.5 — preserves midtone
    // sharpness, softens shadows.
    float CalcAmount(float amount, float darkLimit, float gray) {
        if (amount <= 0.0) return 0.0;
        if (darkLimit <= 0.0) return amount;
        float lo = darkLimit * 0.5;
        float hi = darkLimit * 1.5;
        float t = clamp((gray - lo) / max(hi - lo, 1e-5), 0.0, 1.0);
        t = t * t * (3.0 - 2.0 * t);  // smoothstep
        return amount * t;
    }

    void main() {
        vec3 center = texture(uInputTexture, vTexCoord).rgb;
        if (uSharpening <= 0.0) {
            fragColor = vec4(center, 1.0);
            return;
        }

        float r = max(uRadius, 0.001);
        float sigma = max(r * 0.5, 0.001);
        float twoSigma2 = 2.0 * sigma * sigma;
        vec3 blur = vec3(0.0);
        float weightSum = 0.0;

        for (int y = -2; y <= 2; y++) {
            for (int x = -2; x <= 2; x++) {
                vec2 offset = vec2(float(x), float(y));
                float dist2 = dot(offset, offset);
                float weight = exp(-dist2 / twoSigma2);
                blur += texture(uInputTexture, vTexCoord + offset * uTexelSize * r).rgb * weight;
                weightSum += weight;
            }
        }
        blur /= max(weightSum, 1e-5);

        float centerLuma = luminance(center);
        float blurLuma = luminance(blur);
        float delta = centerLuma - blurLuma;
        float detail = sign(delta) * max(abs(delta) - uThreshold, 0.0);

        // ── U-02: legacy path (preserves P-3 behavior when uAdaptive == 0) ───
        if (uAdaptive == 0) {
            vec3 resultLegacy = center + center * (detail / max(centerLuma, 1e-5)) * uSharpening;
            fragColor = vec4(clamp(resultLegacy, 0.0, 1.0), 1.0);
            return;
        }

        // ── U-02: noise gating + brightness-adaptive amount ─────────────────
        float absDiff = abs(delta);
        float noise  = CalcNoise(uNoiseLimit, absDiff);
        float amount = CalcAmount(uSharpening, uDarkLimit, centerLuma);

        // ── U-02: Sobel 4-tap edge mask — suppress sharpening in flat regions
        vec2 ts = uTexelSize * r;
        float lL = luminance(texture(uInputTexture, vTexCoord + vec2(-ts.x, 0.0)).rgb);
        float lR = luminance(texture(uInputTexture, vTexCoord + vec2( ts.x, 0.0)).rgb);
        float lU = luminance(texture(uInputTexture, vTexCoord + vec2(0.0, -ts.y)).rgb);
        float lD = luminance(texture(uInputTexture, vTexCoord + vec2(0.0,  ts.y)).rgb);
        float gx = abs(lR - lL);
        float gy = abs(lD - lU);
        float edgeMag = sqrt(gx * gx + gy * gy);
        // Edge mask: 0 in flat regions, 1 at strong edges. uEdgeMaskStrength
        // controls how aggressively flat regions are suppressed (default 2.2).
        float edgeMask = 1.0 - exp(-edgeMag * uEdgeMaskStrength);

        // ── U-02: composite usmFactor = amount × noise × edgeMask × detail ──
        float usmFactor = amount * noise * edgeMask;
        vec3 result = center + center * (detail * usmFactor / max(centerLuma, 1e-5));

        fragColor = vec4(clamp(result, 0.0, 1.0), 1.0);
    }
""".trimIndent()

// ─── SED COMMANDS — SECTION A (executados pelo build-archlinux.sh) ───────────
// Variável de ambiente: rsh=app/src/main/java/com/hinnka/mycamera/raw/RawShaders.kt
//
// A.1) Adiciona 4 novos uniforms após `uniform float uThreshold;` (linha única no
//      arquivo — anchor único ao SHARPEN_FRAGMENT_SHADER):
// sed -i '/uniform float uThreshold;/a\        uniform float uEdgeMaskStrength;   // U-02: edge mask power (0=off, 2.2=typical)\n        uniform float uNoiseLimit;         // U-02: noise gating threshold (luma 0-1)\n        uniform float uDarkLimit;          // U-02: dark-region luma threshold (0-1)\n        uniform int   uAdaptive;           // U-02: 1=adaptive, 0=legacy P-3' "$rsh"
//
// A.2) Adiciona helpers CalcNoise/CalcAmount antes de `void main() {` (anchor
//      único — `void main()` só aparece no SHARPEN_FRAGMENT_SHADER neste arquivo):
// sed -i '/void main() {/i\
//\        // U-02: noise gating (simplified port of Phocus CalcNoise)\        float CalcNoise(float noiseLimit, float absDiff) {\            if (noiseLimit <= 0.0) return 1.0;\            float range = max(noiseLimit * 0.25, 1.0 / 16384.0);\            float t = clamp((absDiff - (noiseLimit - range)) / range, 0.0, 1.0);\            return t * t * (3.0 - 2.0 * t);\
//\        }\
//\        // U-02: brightness-adaptive amount (simplified port of Phocus CalcAmount)\
//        float CalcAmount(float amount, float darkLimit, float gray) {\
//            if (amount <= 0.0) return 0.0;\
//            if (darkLimit <= 0.0) return amount;\
//            float lo = darkLimit * 0.5;\
//            float hi = darkLimit * 1.5;\
//            float t = clamp((gray - lo) / max(hi - lo, 1e-5), 0.0, 1.0);\
//            t = t * t * (3.0 - 2.0 * t);\
//            return amount * t;\
//        }\
//' "$rsh"
//
// A.3) Substitui o bloco final de computação (3 linhas únicas no arquivo):
//      OLD: `vec3 result = center + center * (detail / max(centerLuma, 1e-5)) * uSharpening;`
//      NEW: bloco adaptive completo (noise gate + Sobel edge mask + composite factor).
//      Usa `|` como delimiter pois o replacement contém `/` (division) e `*` (mul):
// sed -i 's|vec3 result = center + center \* (detail / max(centerLuma, 1e-5)) \* uSharpening;|// U-02: legacy path (uAdaptive == 0)\n        if (uAdaptive == 0) {\n            vec3 resultLegacy = center + center * (detail / max(centerLuma, 1e-5)) * uSharpening;\n            fragColor = vec4(clamp(resultLegacy, 0.0, 1.0), 1.0);\n            return;\n        }\n        // U-02: noise gate + brightness-adaptive amount\n        float absDiff = abs(delta);\n        float noise  = CalcNoise(uNoiseLimit, absDiff);\n        float amount = CalcAmount(uSharpening, uDarkLimit, centerLuma);\n        // U-02: Sobel 4-tap edge mask\n        vec2 ts = uTexelSize * r;\n        float lL = luminance(texture(uInputTexture, vTexCoord + vec2(-ts.x, 0.0)).rgb);\n        float lR = luminance(texture(uInputTexture, vTexCoord + vec2( ts.x, 0.0)).rgb);\n        float lU = luminance(texture(uInputTexture, vTexCoord + vec2(0.0, -ts.y)).rgb);\n        float lD = luminance(texture(uInputTexture, vTexCoord + vec2(0.0,  ts.y)).rgb);\n        float gx = abs(lR - lL);\n        float gy = abs(lD - lU);\n        float edgeMag = sqrt(gx * gx + gy * gy);\n        float edgeMask = 1.0 - exp(-edgeMag * uEdgeMaskStrength);\n        float usmFactor = amount * noise * edgeMask;\n        vec3 result = center + center * (detail * usmFactor / max(centerLuma, 1e-5));|' "$rsh"
//
// NOTA: A.1-A.3 cobrem toda a transformação do shader. O build script pode também
//       optar por substituir o bloco inteiro via `c` (change) com range address.
//       Equivalente compacto (alternativa válida):
// sed -i '/float centerLuma = luminance(center);/,/vec3 result = center + center/c\
//        float centerLuma = luminance(center);\
//        ...NOVO BLOCO...\
//        vec3 result = center + center * (detail * usmFactor / max(centerLuma, 1e-5));' "$rsh"

// ═══════════════════════════════════════════════════════════════════════════════
// SECTION B — RAW CONSUMER: RawDemosaicProcessor.kt (L998-L999, L10578-L10585)
// ═══════════════════════════════════════════════════════════════════════════════

// ─── ANTES (P-3 — hardcoded constants, only uSharpening/uRadius/uThreshold bound)
private val defaultUsmRadius = RawShaders.DEFAULT_USM_RADIUS        // 2.0f
private val defaultUsmThreshold = RawShaders.DEFAULT_USM_THRESHOLD  // 0.005f

private fun renderSharpenPass(metadata, sharpeningValue, inputTextureId) {
    // ...
    GLES30.glUniform1f(glu("uSharpening"), sharpeningValue.coerceIn(0f, 1f))
    GLES30.glUniform1f(glu("uRadius"),     defaultUsmRadius)
    GLES30.glUniform1f(glu("uThreshold"),  defaultUsmThreshold)
    // ...
}

// ─── DEPOIS (U-02 — config-driven + 4 new uniform bindings)
private val defaultUsmRadius: Float        // U-02: agora getter, lê config
    get() = LeicaConfig.sharpeningRadius.toFloat().coerceIn(0.1f, 8.0f)
private val defaultUsmThreshold: Float     // U-02: agora getter, lê config
    get() = LeicaConfig.sharpeningThreshold.toFloat().coerceIn(0f, 0.1f)

private fun renderSharpenPass(metadata, sharpeningValue, inputTextureId) {
    // ...
    GLES30.glUniform1f(glu("uSharpening"),  sharpeningValue.coerceIn(0f, 1f))
    GLES30.glUniform1f(glu("uRadius"),      defaultUsmRadius)
    GLES30.glUniform1f(glu("uThreshold"),   defaultUsmThreshold)
    // U-02: 4 novos uniforms (adaptive path)
    GLES30.glUniform1f(glu("uEdgeMaskStrength"), LeicaConfig.sharpeningEdgeMaskStrength.toFloat())
    GLES30.glUniform1f(glu("uNoiseLimit"),        0.015f)  // U-02: ~1.5% luma noise floor
    GLES30.glUniform1f(glu("uDarkLimit"),         0.04f)   // U-02: shadows <4% luma get reduced sharpening
    GLES30.glUniform1i(glu("uAdaptive"),          if (LeicaConfig.sharpeningAdaptive) 1 else 0)
    // ...
}

// ─── SED COMMANDS — SECTION B ────────────────────────────────────────────────
// Variável: rdp=app/src/main/java/com/hinnka/mycamera/raw/RawDemosaicProcessor.kt
// (LeicaConfig está no mesmo package `com.hinnka.mycamera.raw` — sem import extra)
//
// B.1) Transforma `val defaultUsmRadius` em getter config-driven:
// sed -i 's|private val defaultUsmRadius = RawShaders.DEFAULT_USM_RADIUS|private val defaultUsmRadius: Float get() = LeicaConfig.sharpeningRadius.toFloat().coerceIn(0.1f, 8.0f)  // U-02: config-driven|' "$rdp"
//
// B.2) Transforma `val defaultUsmThreshold` em getter config-driven:
// sed -i 's|private val defaultUsmThreshold = RawShaders.DEFAULT_USM_THRESHOLD|private val defaultUsmThreshold: Float get() = LeicaConfig.sharpeningThreshold.toFloat().coerceIn(0f, 0.1f)  // U-02: config-driven|' "$rdp"
//
// B.3) Adiciona 4 bindings após o bloco `uThreshold` em renderSharpenPass.
//      Anchor: linha exata `defaultUsmThreshold\n        )` no final do uniform block:
// sed -i '/glUniform1f($/N;/glGetUniformLocation(sharpenProgram, "uThreshold"),/{N;s|defaultUsmThreshold\n        )|defaultUsmThreshold\n        )\n        // U-02: adaptive path uniforms (LeicaConfig-driven)\n        GLES30.glUniform1f(GLES30.glGetUniformLocation(sharpenProgram, "uEdgeMaskStrength"), LeicaConfig.sharpeningEdgeMaskStrength.toFloat())\n        GLES30.glUniform1f(GLES30.glGetUniformLocation(sharpenProgram, "uNoiseLimit"), 0.015f)\n        GLES30.glUniform1f(GLES30.glGetUniformLocation(sharpenProgram, "uDarkLimit"), 0.04f)\n        GLES30.glUniform1i(GLES30.glGetUniformLocation(sharpenProgram, "uAdaptive"), if (LeicaConfig.sharpeningAdaptive) 1 else 0)|}' "$rdp"
//
// Alternativa B.3 (mais legível, 4 seds independentes):
// sed -i '/GLES30.glUniform1f($/,/defaultUsmThreshold$/{/defaultUsmThreshold$/a\
//        )\
//        // U-02: adaptive path uniforms (LeicaConfig-driven)\
//        GLES30.glUniform1f(GLES30.glGetUniformLocation(sharpenProgram, "uEdgeMaskStrength"), LeicaConfig.sharpeningEdgeMaskStrength.toFloat())\
//        GLES30.glUniform1f(GLES30.glGetUniformLocation(sharpenProgram, "uNoiseLimit"), 0.015f)\
//        GLES30.glUniform1f(GLES30.glGetUniformLocation(sharpenProgram, "uDarkLimit"), 0.04f)\
//        GLES30.glUniform1i(GLES30.glGetUniformLocation(sharpenProgram, "uAdaptive"), if (LeicaConfig.sharpeningAdaptive) 1 else 0)
// }' "$rdp"

// ═══════════════════════════════════════════════════════════════════════════════
// SECTION C — LUT CONSUMER: LutImageProcessor.kt (L25 import, L2396-L2403)
// ═══════════════════════════════════════════════════════════════════════════════

// ─── ANTES (P-3 — RawShaders constants hardcoded, no edge mask binding)
import com.hinnka.mycamera.raw.RawShaders

private fun renderLutSharpenPass(sourceTextureId, width, height, sharpening): Boolean {
    // ...
    GLES30.glUniform1f(glu("uSharpening"), sharpening)
    GLES30.glUniform1f(glu("uRadius"),     RawShaders.DEFAULT_USM_RADIUS)     // 2.0f
    GLES30.glUniform1f(glu("uThreshold"),  RawShaders.DEFAULT_USM_THRESHOLD)  // 0.005f
    // ...
}

// ─── DEPOIS (U-02 — LeicaConfig-driven + 4 new uniform bindings)
import com.hinnka.mycamera.raw.LeicaConfig       // U-02: added
import com.hinnka.mycamera.raw.RawShaders

private fun renderLutSharpenPass(sourceTextureId, width, height, sharpening): Boolean {
    // ...
    GLES30.glUniform1f(glu("uSharpening"), sharpening)
    GLES30.glUniform1f(glu("uRadius"),     LeicaConfig.sharpeningRadius.toFloat().coerceIn(0.1f, 8.0f))      // U-02
    GLES30.glUniform1f(glu("uThreshold"),  LeicaConfig.sharpeningThreshold.toFloat().coerceIn(0f, 0.1f))    // U-02
    // U-02: 4 novos uniforms (mirror RAW path — mesma config, mesmo behavior)
    GLES30.glUniform1f(glu("uEdgeMaskStrength"), LeicaConfig.sharpeningEdgeMaskStrength.toFloat())
    GLES30.glUniform1f(glu("uNoiseLimit"),        0.015f)
    GLES30.glUniform1f(glu("uDarkLimit"),         0.04f)
    GLES30.glUniform1i(glu("uAdaptive"),          if (LeicaConfig.sharpeningAdaptive) 1 else 0)
    // ...
}

// ─── SED COMMANDS — SECTION C ────────────────────────────────────────────────
// Variável: lip=app/src/main/java/com/hinnka/mycamera/lut/LutImageProcessor.kt
//
// C.1) Adiciona import LeicaConfig antes do import RawShaders (grep-guarded):
// sed -i '/^import com\.hinnka\.mycamera\.raw\.RawShaders$/i import com.hinnka.mycamera.raw.LeicaConfig  // U-02' "$lip"
//
// C.2) Troca RawShaders.DEFAULT_USM_RADIUS por LeicaConfig.sharpeningRadius:
// sed -i 's|RawShaders.DEFAULT_USM_RADIUS|LeicaConfig.sharpeningRadius.toFloat().coerceIn(0.1f, 8.0f)  // U-02|' "$lip"
//
// C.3) Troca RawShaders.DEFAULT_USM_THRESHOLD por LeicaConfig.sharpeningThreshold:
// sed -i 's|RawShaders.DEFAULT_USM_THRESHOLD|LeicaConfig.sharpeningThreshold.toFloat().coerceIn(0f, 0.1f)  // U-02|' "$lip"
//
// C.4) Adiciona 4 novos uniform bindings após o bloco uThreshold (mirror B.3).
//      Anchor: linha `RawShaders.DEFAULT_USM_THRESHOLD` agora substituída — o
//      sed C.3 deixa o trailing `)` na linha seguinte. Append após essa linha:
// sed -i '/LeicaConfig.sharpeningThreshold.toFloat().coerceIn(0f, 0.1f)  \/\/ U-02$/{N;s|)$|)\n        // U-02: adaptive path uniforms (LeicaConfig-driven, mirror RAW path)\n        GLES30.glUniform1f(GLES30.glGetUniformLocation(lutSharpenProgram, "uEdgeMaskStrength"), LeicaConfig.sharpeningEdgeMaskStrength.toFloat())\n        GLES30.glUniform1f(GLES30.glGetUniformLocation(lutSharpenProgram, "uNoiseLimit"), 0.015f)\n        GLES30.glUniform1f(GLES30.glGetUniformLocation(lutSharpenProgram, "uDarkLimit"), 0.04f)\n        GLES30.glUniform1i(GLES30.glGetUniformLocation(lutSharpenProgram, "uAdaptive"), if (LeicaConfig.sharpeningAdaptive) 1 else 0)|}' "$lip"

// ═══════════════════════════════════════════════════════════════════════════════
// SECTION D — DEFAULTS: RawSharpeningDefaults.kt (L3-L7, 7 LOC total)
// ═══════════════════════════════════════════════════════════════════════════════

// ─── ANTES (P-3 — CAPTURE_DEFAULT=0.4f força floor em RAW captures)
package com.hinnka.mycamera.raw

object RawSharpeningDefaults {
    const val CAPTURE_DEFAULT = 0.4f

    fun forCapture(requested: Float): Float = maxOf(requested, CAPTURE_DEFAULT)
}

// ─── DEPOIS (U-02 — CAPTURE_DEFAULT config-driven, floor reduzido p/ 0.05f)
package com.hinnka.mycamera.raw

object RawSharpeningDefaults {
    // U-02: agora reflete LeicaConfig.sharpeningAmount (default 0.09 em vez de 0.4).
    // const → val getter porque LeicaConfig é mutável em runtime (hot reload).
    val CAPTURE_DEFAULT: Float get() = LeicaConfig.sharpeningAmount.toFloat()

    // U-02: floor reduzido de 0.4f para 0.05f — mínimo anti-noop apenas.
    // Antes: maxOf(requested, 0.4f) — anulava user's amount=0.09 pra RAW captures.
    // Depois: maxOf(requested, 0.05f) — user's amount=0.09 passa direto.
    fun forCapture(requested: Float): Float = maxOf(requested, 0.05f)
}

// ─── SED COMMANDS — SECTION D ────────────────────────────────────────────────
// Variável: rsd=app/src/main/java/com/hinnka/mycamera/raw/RawSharpeningDefaults.kt
// (LeicaConfig está no mesmo package `com.hinnka.mycamera.raw` — sem import extra)
//
// D.1) CAPTURE_DEFAULT: const 0.4f → getter LeicaConfig.sharpeningAmount:
// sed -i 's|const val CAPTURE_DEFAULT = 0.4f|val CAPTURE_DEFAULT: Float get() = LeicaConfig.sharpeningAmount.toFloat()  // U-02: config-driven (default 0.09)|' "$rsd"
//
// D.2) forCapture: floor 0.4f → 0.05f (permite user amount=0.09 passar):
// sed -i 's|fun forCapture(requested: Float): Float = maxOf(requested, CAPTURE_DEFAULT)|fun forCapture(requested: Float): Float = maxOf(requested, 0.05f)  // U-02: floor reduzido (era CAPTURE_DEFAULT=0.4)|' "$rsd"

// ═══════════════════════════════════════════════════════════════════════════════
// REFERENCIADO POR (NÃO QUEBRAR)
// ═══════════════════════════════════════════════════════════════════════════════
//   - LeicaConfig.sharpening{Amount,Radius,Threshold,EdgeMaskStrength,Adaptive}
//   - LeicaConfig.kt §ACCESSORS (L586-L591) — accessors já existem
//   - RawShaders.kt L15-L17: DEFAULT_USM_RADIUS/THRESHOLD continuam intactos
//     (consumers agora lêem LeicaConfig ao invés das constants, mas constants
//     são preservadas como fallback)
//   - RawDemosaicProcessor.kt L998-L999 (defaultUsm*), L10578-L10585 (bind)
//   - LutImageProcessor.kt L25 (import), L2396-L2403 (bind)
//   - RawSharpeningDefaults.kt L3-L7 (CAPTURE_DEFAULT + forCapture)
//   - CameraViewModel.kt L4849-L4853, L5406-L5410, L5851-L5855 (chamam forCapture)
//     => automaticamente se beneficiam do floor reduzido — sem alteração necessária
//   - GalleryManager.kt L243, L2199, L3059, L3506, L4905, L5074 (idem)
//   - Phocus reference: research/phocus_glsl/06_detail_texture/unsharpMask_0x300e6ad.frag
//     (CalcNoise/CalcAmount portados de lá, versão simplificada)
//   - VLM pixel analysis: config/vlm_pixel_analysis.md §Sharpening (oversharpening
//     15-40%, halos 2-4px)
//
// VALIDAÇÃO PÓS-PATCH (build engineer):
//   1. `grep -n 'uniform float uEdgeMaskStrength' RawShaders.kt` => 1 match
//   2. `grep -n 'CalcNoise\|CalcAmount' RawShaders.kt` => 4+ matches (decl + call)
//   3. `grep -n 'LeicaConfig.sharpeningRadius' RawDemosaicProcessor.kt` => 1 match
//   4. `grep -n 'LeicaConfig.sharpeningRadius' LutImageProcessor.kt` => 1 match
//   5. `grep -n 'import com.hinnka.mycamera.raw.LeicaConfig' LutImageProcessor.kt` => 1
//   6. `grep -n 'val CAPTURE_DEFAULT' RawSharpeningDefaults.kt` => 1 match (não `const val`)
//   7. `grep -n 'maxOf(requested, 0.05f)' RawSharpeningDefaults.kt` => 1 match
//   8. Build Gradle assembly + smoke test: capture 1 RAW + 1 LUT, comparar halos.
// ═══════════════════════════════════════════════════════════════════════════════
