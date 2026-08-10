// ═══════════════════════════════════════════════════════════════════════════════
// U03_BilateralToGuidedFilter.patch.kt — U-03 (v6.4) — Guided Filter (halo kill)
// ═══════════════════════════════════════════════════════════════════════════════
//
// ⚠️  DOCUMENTATION-ONLY — Este arquivo NÃO é compilado. Mostra o que o sed em
//     build-archlinux.sh cmd_patch() U-03 injeta no upstream PhotonCamera.
//
// ⭐⭐ PATCH STATUS: REAL — actually effective at runtime. ⭐⭐
//     Fecha U-03 step 1 (HDR bilateral halos no ShadowsHighlightsShader).
//
// O QUE FAZ (4 targets, ~10 seds):
//
//   A) SHADER GLSL  — ShadowsHighlightsShader.kt::GLSL (L25-L207)
//      - Adiciona 3 novos uniforms GLSL no bloco de constantes:
//          uniform float uGuidedFilterRadius;   // window radius px (default 10.0)
//          uniform float uGuidedFilterEps;      // regularization (default 0.01)
//          uniform int   uGuidedFilterEnabled;  // 1=guided (default), 0=legacy bilateral
//      - Adiciona helper shGuidedFilterBaseL(uv, centerL) que implementa o
//        guided filter (He et al. 2010) em single-pass com 5x5 sample grid
//        (25 taps — mesmo custo que o bilateral legacy de 10 pares = 20 taps
//        + 1 center = 21 taps). Span = ±uGuidedFilterRadius pixels.
//      - Modifica shSampleBaseL() para DISPATCH: se uGuidedFilterEnabled==1,
//        chama shGuidedFilterBaseL(); senão executa o path bilateral original
//        (preservado para A/B testing).
//      - Contrato de output PRESERVADO: shSampleBaseL retorna float (local
//        L filtrado) — applyShadowsHighlights() L185-206 não muda.
//
//   B) KOTLIN BIND   — ShadowsHighlightsShader.kt::bindUniforms (L6-L23)
//      - Adiciona `import com.hinnka.mycamera.raw.LeicaConfig` (LeicaConfig
//        está em package `raw`, ShadowsHighlightsShader está em `lut`).
//      - Extende bindUniforms(program, h, s) para também chamar
//        bindGuidedFilterUniforms(program) — callers que usam este overload
//        (LutImageProcessor L1095, VideoLutEffect L890) ganham binding
//        automático sem alteração de caller.
//      - Adiciona novo método bindGuidedFilterUniforms(program) que resolve
//        e binda uGuidedFilterRadius/Eps/Enabled a partir de LeicaConfig.
//        Callers que usam bindUniformLocations(...) (pre-resolvido) devem
//        chamar bindGuidedFilterUniforms(program) explicitamente.
//
//   C) CONSUMERS PRE-RESOLVED (3 sites — adicionam 1 linha cada)
//      - LutRenderer.kt L1439: after bindUniformLocations(...) call,
//        add `ShadowsHighlightsShader.bindGuidedFilterUniforms(locations.programId)`
//      - RawDemosaicProcessor.kt L10338: same pattern, anchor on
//        `shadows = params.shadows` (12-space indent, no trailing comma)
//      - RealtimeVideoRenderer.kt L403: same pattern, anchor on
//        `shadows = params.shadows,` (trailing comma — Kotlin trailing-comma)
//
//   D) LeicaConfig.kt — ToneMappingConfig + 3 accessors
//      - Adiciona 3 campos à ToneMappingConfig data class (L141-L147):
//          guided_filter_enabled: Boolean? = true
//          guided_filter_radius:  Double? = 10.0
//          guided_filter_eps:     Double? = 0.01
//      - Adiciona 3 accessors após toneMappingFilmLikeCurve (L609):
//          toneMappingGuidedFilterEnabled: Boolean (default true)
//          toneMappingGuidedFilterRadius:  Double  (default 10.0)
//          toneMappingGuidedFilterEps:     Double  (default 0.01)
//
// POR QUE:
//   - VLM pixel analysis (Task U-03 explore) identificou halos 2-6px ao redor
//     de edges high-contrast (sky/tree-line, building/sky) — sintoma clássico
//     de bilateral filter com range sigma tight (SH_RANGE_SIGMA=0.105) e
//     radius grande (até 22.5px). shTonalRangeWeight() L94-99 usa kernel
//     bilateral explícito: exp(-delta²/(2σ²)) — quando delta > 2σ, peso cai
//     para ~0, criando transição abrupta → halos.
//   - Guided filter (He et al. 2010, "Guided Image Filtering") é halo-resistant
//     por design: usa variância local (var_I) para computar coeficiente linear
//     `a = var_I / (var_I + eps)` que é BOUNDED [0,1]:
//       * Em edges (var_I >> eps): a → 1 → q ≈ I (passthrough, preserva edge)
//       * Em flats (var_I << eps): a → 0 → q ≈ mean_I (smooth, sem halos)
//     A transição é suave e controlada por eps — não há regime onde o filtro
//     "recorta" abruptamente como o bilateral range kernel faz.
//   - Custo computacional PRESERVADO: 5x5 = 25 taps vs legacy bilateral
//     10 pairs × 2 + 1 center = 21 taps. Diferença <20%, aceitável.
//   - PGTM (DngPhotonLocalToneMapper) usa LLF + BGU (também halo-resistant)
//     — NÃO tocado por U-03. ShadowsHighlightsShader era o ÚNICO bilateral
//     direto na pipeline de tone mapping (confirmed by grep — zero
//     guided_filter matches pre-patch).
//
// LeicaConfig ACCESSORS USADOS:
//   - LeicaConfig.toneMappingGuidedFilterEnabled: Boolean  (default true)
//   - LeicaConfig.toneMappingGuidedFilterRadius:  Double   (default 10.0, clamped 2..16)
//   - LeicaConfig.toneMappingGuidedFilterEps:     Double   (default 0.01, clamped 1e-6..0.1)
//
// RISK / SCOPE NOTES:
//   - Shader é compartilhado entre RAW (RawShaders.kt L165, RawAdjustmentPassShaders
//     L46) e LUT post-edit (LutImageProcessor L3676, LutRenderer L1439 inject via
//     ColorPassProgram.kt L131-L132). U-03 patcheia o shader源头 (ShadowsHighlightsShader
//     .GLSL) — todos consumers ganham guided filter automaticamente.
//   - uGuidedFilterEnabled == 0 preserva legacy bilateral path (A/B testing
//     via config `tone_mapping.guided_filter_enabled: false`).
//   - Performance: 25 taps × (texture lookup + RGB→Lab) ≈ 25 × ~25 ALU = 625
//     ALU ops + 25 texture lookups per pixel. Em full-res 12MP RGBA8 FBO,
//     custo estimado ~3-5ms em Adreno 740 (mesma ordem que o bilateral
//     legacy — aceitável para capture path). Para preview path em 30fps,
//     considerar desativar via config se profiling mostrar frame drops.
//   - Single-pass approximation: skipped o segundo box filter em `a` e `b`
//     (He et al. original usa mean_a = box(a, r), mean_b = box(b, r) — segunda
//     passada). A aproximação q ≈ a*I + b (sem smoothing de a, b) é OK aqui
//     porque o output é usado como MASK no overlay-blend de applyShadowsHighlights,
//     não como output final — artefatos de pixel-level variação em a/b são
//     absorvidos pelo overlay. Para uso como filter direto, implementar
//     multi-pass com FBO ping-pong (fora do escopo deste patch).
//   - PGTM (DngPhotonLocalToneMapper) NÃO tocado — já usa LLF+BGU halo-resistant.
//   - Mertens fusion weights (GlesYuvStacker L1948-1950) NÃO tocados —
//     isso é step 3 do U-03, patch separado.
//   - PGTM curve tunables (DngProfileToneCurve L105-109) NÃO tocados —
//     isso é step 2 do U-03, patch separado.
// ═══════════════════════════════════════════════════════════════════════════════

package com.hinnka.mycamera.lut

import com.hinnka.mycamera.raw.LeicaConfig  // U-03: added (LeicaConfig in `raw` package)

// ═══════════════════════════════════════════════════════════════════════════════
// SECTION A — SHADER GLSL: ShadowsHighlightsShader.kt::GLSL (L25-L207)
// ═══════════════════════════════════════════════════════════════════════════════

// ─── ANTES (P-baseline — bilateral range kernel, halo-prone) ────────────────
// L25-L33 (const block):
//        const float SH_RANGE_SIGMA = 0.105;
//        const float SH_RANGE_SIGMA2 = SH_RANGE_SIGMA * SH_RANGE_SIGMA;
//
// L94-L99 (bilateral range kernel):
//        float shTonalRangeWeight(float sampleL, float centerL) {
//            float delta = sampleL - centerL;
//            float bilateral = exp(-(delta * delta) / max(2.0 * SH_RANGE_SIGMA2, SH_LOW_APPROX));
//            float edgeStop = 1.0 - smoothstep(0.12, 0.24, abs(delta));
//            return bilateral * edgeStop;
//        }
//
// L124-L143 (24-tap bilateral sampler — 10 sample pairs + center):
//        float shSampleBaseL(vec2 uv, vec3 centerLab) {
//            float centerL = centerLab.x;
//            float sum = centerL * 0.48;
//            float weightSum = 0.48;
//
//            shAddBaseSamplePair(uv, shPixelOffset(2.5, 1.5), centerL, 0.11, sum, weightSum);
//            shAddBaseSamplePair(uv, shPixelOffset(-1.5, 3.5), centerL, 0.11, sum, weightSum);
//            shAddBaseSamplePair(uv, shPixelOffset(4.5, -2.5), centerL, 0.10, sum, weightSum);
//            shAddBaseSamplePair(uv, shPixelOffset(-4.5, -3.5), centerL, 0.10, sum, weightSum);
//            shAddBaseSamplePair(uv, shPixelOffset(7.5, 4.5), centerL, 0.065, sum, weightSum);
//            shAddBaseSamplePair(uv, shPixelOffset(-6.5, 8.5), centerL, 0.06, sum, weightSum);
//            shAddBaseSamplePair(uv, shPixelOffset(10.5, -7.5), centerL, 0.055, sum, weightSum);
//            shAddBaseSamplePair(uv, shPixelOffset(15.5, 11.5), centerL, 0.035, sum, weightSum);
//            shAddBaseSamplePair(uv, shPixelOffset(-18.5, 5.5), centerL, 0.03, sum, weightSum);
//            shAddBaseSamplePair(uv, shPixelOffset(22.5, -13.5), centerL, 0.026, sum, weightSum);
//
//            return sum / max(weightSum, 0.0001);
//        }

// ─── DEPOIS (U-03 — guided filter dispatch + bilateral fallback preservado) ─
// A.1) Add 3 uniforms after SH_RANGE_SIGMA2 const:
//        const float SH_RANGE_SIGMA = 0.105;
//        const float SH_RANGE_SIGMA2 = SH_RANGE_SIGMA * SH_RANGE_SIGMA;
//        // U-03: guided filter uniforms (replaces bilateral when enabled)
//        uniform float uGuidedFilterRadius;   // window radius px (default 10.0)
//        uniform float uGuidedFilterEps;      // regularization (default 0.01)
//        uniform int   uGuidedFilterEnabled;  // 1=guided (default), 0=legacy bilateral

// A.2) Add shGuidedFilterBaseL helper before shSampleBaseL declaration:
//        // ─── U-03: Guided filter (He et al. 2010), single-window approximation ──
//        // Replaces the bilateral range kernel in shSampleBaseL. Uses local
//        // mean and variance of L in a 5x5 sample grid (25 taps — same cost as
//        // legacy bilateral 10 pairs + center) to derive linear coefficients
//        // a, b that pass-through edges and smooth flat regions. Halo-resistant
//        // because a is bounded [0,1] — no abrupt weight cutoff like bilateral.
//        float shGuidedFilterBaseL(vec2 uv, float centerL) {
//            float r = clamp(uGuidedFilterRadius, 2.0, 16.0);
//            float stride = max(r * 0.5, 1.0);  // 5 samples per axis: -2s, -s, 0, +s, +2s
//
//            float sumI  = 0.0;
//            float sumII = 0.0;
//            float count = 0.0;
//
//            for (int j = -2; j <= 2; j++) {
//                for (int i = -2; i <= 2; i++) {
//                    vec2 off = vec2(float(i), float(j)) * stride * uTexelSize;
//                    vec2 sampleUv = clamp(uv + off, vec2(0.0), vec2(1.0));
//                    float L = shRgbToLabScaled(sampleToneSource(sampleUv)).x;
//                    sumI  += L;
//                    sumII += L * L;
//                    count += 1.0;
//                }
//            }
//
//            float meanI = sumI / count;
//            float corrI = sumII / count;
//            float varI  = max(corrI - meanI * meanI, 0.0);
//
//            // a → 1 on edges (var_I >> eps): passthrough. a → 0 in flats
//            // (var_I << eps): smooth. eps controls edge sensitivity.
//            float a = varI / (varI + max(uGuidedFilterEps, 1e-6));
//            float b = (1.0 - a) * meanI;
//
//            // q = a * I + b — single-window guided filter output (skips
//            // mean_a/mean_b second box filter; mask role tolerates this).
//            return a * centerL + b;
//        }

// A.3) Modify shSampleBaseL to dispatch (insert after `float centerL = centerLab.x;`):
//        float shSampleBaseL(vec2 uv, vec3 centerLab) {
//            float centerL = centerLab.x;
//            // U-03: dispatch — guided filter (default) or legacy bilateral (A/B fallback)
//            if (uGuidedFilterEnabled == 1) {
//                return shGuidedFilterBaseL(uv, centerL);
//            }
//            // ─── Legacy bilateral path (preserved for A/B testing) ───────
//            float sum = centerL * 0.48;
//            float weightSum = 0.48;
//            shAddBaseSamplePair(uv, shPixelOffset(2.5, 1.5), centerL, 0.11, sum, weightSum);
//            ... (rest of bilateral sampler unchanged) ...
//            return sum / max(weightSum, 0.0001);
//        }

// ─── SED COMMANDS — SECTION A (executados pelo build-archlinux.sh) ───────────
// Variável: shs=app/src/main/java/com/hinnka/mycamera/lut/ShadowsHighlightsShader.kt
//
// A.1) Add 3 uniforms after SH_RANGE_SIGMA2 const (anchor único no arquivo):
// sed -i 's|        const float SH_RANGE_SIGMA2 = SH_RANGE_SIGMA \* SH_RANGE_SIGMA;|        const float SH_RANGE_SIGMA2 = SH_RANGE_SIGMA * SH_RANGE_SIGMA;\n        // U-03: guided filter uniforms (replaces bilateral when enabled)\n        uniform float uGuidedFilterRadius;   // window radius px (default 10.0)\n        uniform float uGuidedFilterEps;      // regularization (default 0.01)\n        uniform int   uGuidedFilterEnabled;  // 1=guided (default), 0=legacy bilateral|' "$shs"
//
// A.2) Insert shGuidedFilterBaseL helper BEFORE shSampleBaseL declaration.
//      Anchor: `float shSampleBaseL(vec2 uv, vec3 centerLab) {` (único no arquivo).
//      Usa `|` como delimiter pois o replacement contém `/` (division) e `*` (mul):
// sed -i '/float shSampleBaseL(vec2 uv, vec3 centerLab) {/i\
//\        // U-03: Guided filter (He et al. 2010), single-window approximation\
//        float shGuidedFilterBaseL(vec2 uv, float centerL) {\
//            float r = clamp(uGuidedFilterRadius, 2.0, 16.0);\
//            float stride = max(r * 0.5, 1.0);\
//            float sumI  = 0.0;\
//            float sumII = 0.0;\
//            float count = 0.0;\
//            for (int j = -2; j <= 2; j++) {\
//                for (int i = -2; i <= 2; i++) {\
//                    vec2 off = vec2(float(i), float(j)) * stride * uTexelSize;\
//                    vec2 sampleUv = clamp(uv + off, vec2(0.0), vec2(1.0));\
//                    float L = shRgbToLabScaled(sampleToneSource(sampleUv)).x;\
//                    sumI  += L;\
//                    sumII += L * L;\
//                    count += 1.0;\
//                }\
//            }\
//            float meanI = sumI / count;\
//            float corrI = sumII / count;\
//            float varI  = max(corrI - meanI * meanI, 0.0);\
//            float a = varI / (varI + max(uGuidedFilterEps, 1e-6));\
//            float b = (1.0 - a) * meanI;\
//            return a * centerL + b;\
//        }\
//' "$shs"
//
// A.3) Insert dispatch guard after `float centerL = centerLab.x;` (anchor único):
// sed -i 's|            float centerL = centerLab.x;|            float centerL = centerLab.x;\n            // U-03: dispatch guided filter (default) or legacy bilateral (A/B fallback)\n            if (uGuidedFilterEnabled == 1) {\n                return shGuidedFilterBaseL(uv, centerL);\n            }|' "$shs"
//
// NOTA: A.1-A.3 cobrem toda a transformação do shader. O bilateral original
//       (shAddBaseSamplePair calls em L129-L140) fica PRESERVADO como fallback
//       quando uGuidedFilterEnabled == 0 — útil para A/B testing via config
//       `tone_mapping.guided_filter_enabled: false`.

// ═══════════════════════════════════════════════════════════════════════════════
// SECTION B — KOTLIN BIND: ShadowsHighlightsShader.kt (L1-L23)
// ═══════════════════════════════════════════════════════════════════════════════

// ─── ANTES (P-baseline — bindUniforms only binds uHighlights/uShadows) ──────
// L1-L3:
// package com.hinnka.mycamera.lut
//
// import android.opengl.GLES30
//
// L6-L23:
// fun bindUniforms(program: Int, highlights: Float, shadows: Float) {
//     bindUniformLocations(
//         highlightsLocation = GLES30.glGetUniformLocation(program, "uHighlights"),
//         shadowsLocation = GLES30.glGetUniformLocation(program, "uShadows"),
//         highlights = highlights,
//         shadows = shadows
//     )
// }
//
// fun bindUniformLocations(
//     highlightsLocation: Int,
//     shadowsLocation: Int,
//     highlights: Float,
//     shadows: Float
// ) {
//     GLES30.glUniform1f(highlightsLocation, highlights)
//     GLES30.glUniform1f(shadowsLocation, shadows)
// }

// ─── DEPOIS (U-03 — import LeicaConfig + auto-bind guided filter in overload) ─
// L1-L4:
// package com.hinnka.mycamera.lut
//
// import android.opengl.GLES30
// import com.hinnka.mycamera.raw.LeicaConfig  // U-03: guided filter config
//
// L6-L45 (bindUniforms extended + new bindGuidedFilterUniforms method):
// fun bindUniforms(program: Int, highlights: Float, shadows: Float) {
//     bindUniformLocations(
//         highlightsLocation = GLES30.glGetUniformLocation(program, "uHighlights"),
//         shadowsLocation = GLES30.glGetUniformLocation(program, "uShadows"),
//         highlights = highlights,
//         shadows = shadows
//     )
//     // U-03: auto-bind guided filter uniforms for callers using this overload
//     // (LutImageProcessor L1095, VideoLutEffect L890 — no caller change needed).
//     bindGuidedFilterUniforms(program)
// }
//
// fun bindUniformLocations(
//     highlightsLocation: Int,
//     shadowsLocation: Int,
//     highlights: Float,
//     shadows: Float
// ) {
//     GLES30.glUniform1f(highlightsLocation, highlights)
//     GLES30.glUniform1f(shadowsLocation, shadows)
// }
//
// // U-03: bind guided filter uniforms from LeicaConfig. Call this once per frame
// // from consumers using the pre-resolved bindUniformLocations() overload.
// fun bindGuidedFilterUniforms(program: Int) {
//     val radiusLoc = GLES30.glGetUniformLocation(program, "uGuidedFilterRadius")
//     val epsLoc = GLES30.glGetUniformLocation(program, "uGuidedFilterEps")
//     val enabledLoc = GLES30.glGetUniformLocation(program, "uGuidedFilterEnabled")
//     if (radiusLoc >= 0) {
//         GLES30.glUniform1f(radiusLoc, LeicaConfig.toneMappingGuidedFilterRadius.toFloat().coerceIn(2.0f, 16.0f))
//     }
//     if (epsLoc >= 0) {
//         GLES30.glUniform1f(epsLoc, LeicaConfig.toneMappingGuidedFilterEps.toFloat().coerceIn(1e-6f, 0.1f))
//     }
//     if (enabledLoc >= 0) {
//         GLES30.glUniform1i(enabledLoc, if (LeicaConfig.toneMappingGuidedFilterEnabled) 1 else 0)
//     }
// }

// ─── SED COMMANDS — SECTION B ────────────────────────────────────────────────
// Variável: shs=app/src/main/java/com/hinnka/mycamera/lut/ShadowsHighlightsShader.kt
//
// B.1) Add import LeicaConfig after `import android.opengl.GLES30` (anchor único):
// sed -i 's|^import android\.opengl\.GLES30$|import android.opengl.GLES30\nimport com.hinnka.mycamera.raw.LeicaConfig  // U-03: guided filter config|' "$shs"
//
// B.2) Extend bindUniforms(program, ...) to also call bindGuidedFilterUniforms.
//      Anchor: `        )` (8-space indent closing paren of the bindUniformLocations
//      CALL inside bindUniforms) — único no arquivo (bindUniformLocations signature
//      closes with `    ) {` at 4-space, not `        )` at 8-space).
// sed -i 's|^        \)$|        )\n        // U-03: auto-bind guided filter uniforms for callers using this overload\n        bindGuidedFilterUniforms(program)|' "$shs"
//
// B.3) Add bindGuidedFilterUniforms method AFTER bindUniformLocations closing brace.
//      Uses Python heredoc for robustness (avoids fragile multi-line sed `a\`
//      escaping — same pattern as U06_AeHistogramFeedback.patch.kt Block E).
//      Anchor: `        GLES30.glUniform1f(shadowsLocation, shadows)\n    }\n`
//      (última linha de bindUniformLocations + closing brace at 4-space) — único
//      no arquivo (assertion fail-fast if upstream shifts).
// python3 - "$shs" <<'PYEOF'
// import sys
// path = sys.argv[1]
// with open(path, 'r') as f:
//     src = f.read()
// anchor = "        GLES30.glUniform1f(shadowsLocation, shadows)\n    }\n"
// new_block = """        GLES30.glUniform1f(shadowsLocation, shadows)
//     }
//
//     // U-03: bind guided filter uniforms from LeicaConfig. Call this once per frame
//     // from consumers using the pre-resolved bindUniformLocations() overload.
//     fun bindGuidedFilterUniforms(program: Int) {
//         val radiusLoc = GLES30.glGetUniformLocation(program, "uGuidedFilterRadius")
//         val epsLoc = GLES30.glGetUniformLocation(program, "uGuidedFilterEps")
//         val enabledLoc = GLES30.glGetUniformLocation(program, "uGuidedFilterEnabled")
//         if (radiusLoc >= 0) {
//             GLES30.glUniform1f(radiusLoc, LeicaConfig.toneMappingGuidedFilterRadius.toFloat().coerceIn(2.0f, 16.0f))
//         }
//         if (epsLoc >= 0) {
//             GLES30.glUniform1f(epsLoc, LeicaConfig.toneMappingGuidedFilterEps.toFloat().coerceIn(1e-6f, 0.1f))
//         }
//         if (enabledLoc >= 0) {
//             GLES30.glUniform1i(enabledLoc, if (LeicaConfig.toneMappingGuidedFilterEnabled) 1 else 0)
//         }
//     }
// """
// assert src.count(anchor) == 1, f"B.3 anchor count != 1: {src.count(anchor)}"
// src = src.replace(anchor, new_block, 1)
// with open(path, 'w') as f:
//     f.write(src)
// PYEOF

// ═══════════════════════════════════════════════════════════════════════════════
// SECTION C — CONSUMERS PRE-RESOLVED (3 sites — 1-liner each)
// ═══════════════════════════════════════════════════════════════════════════════

// ─── ANTES (P-baseline — pre-resolved locations, no guided filter binding) ──
// C.1) LutRenderer.kt L1439-L1444 (16-space indent, inside if(recipeEnabled)):
//            ShadowsHighlightsShader.bindUniformLocations(
//                highlightsLocation = locations.uHighlightsLocation,
//                shadowsLocation = locations.uShadowsLocation,
//                highlights = params.highlights,
//                shadows = params.shadows
//            )
//
// C.2) RawDemosaicProcessor.kt L10338-L10343 (8-space indent, local vars):
//        ShadowsHighlightsShader.bindUniformLocations(
//            highlightsLocation = highlightsLocation,
//            shadowsLocation = shadowsLocation,
//            highlights = params.highlights,
//            shadows = params.shadows
//        )
//
// C.3) RealtimeVideoRenderer.kt L403-L408 (8-space indent, trailing comma):
//        ShadowsHighlightsShader.bindUniformLocations(
//            highlightsLocation = locations.uHighlightsLocation,
//            shadowsLocation = locations.uShadowsLocation,
//            highlights = params.highlights,
//            shadows = params.shadows,
//        )

// ─── DEPOIS (U-03 — 1-liner added after each bindUniformLocations call) ─────
// C.1) LutRenderer.kt — add after bindUniformLocations(...) call:
//            ShadowsHighlightsShader.bindUniformLocations(
//                highlightsLocation = locations.uHighlightsLocation,
//                shadowsLocation = locations.uShadowsLocation,
//                highlights = params.highlights,
//                shadows = params.shadows
//            )
//            ShadowsHighlightsShader.bindGuidedFilterUniforms(locations.programId)  // U-03: guided filter
//
// C.2) RawDemosaicProcessor.kt — add after bindUniformLocations(...) call:
//        ShadowsHighlightsShader.bindUniformLocations(
//            highlightsLocation = highlightsLocation,
//            shadowsLocation = shadowsLocation,
//            highlights = params.highlights,
//            shadows = params.shadows
//        )
//        ShadowsHighlightsShader.bindGuidedFilterUniforms(program)  // U-03: guided filter
//
// C.3) RealtimeVideoRenderer.kt — add after bindUniformLocations(...) call:
//        ShadowsHighlightsShader.bindUniformLocations(
//            highlightsLocation = locations.uHighlightsLocation,
//            shadowsLocation = locations.uShadowsLocation,
//            highlights = params.highlights,
//            shadows = params.shadows,
//        )
//        ShadowsHighlightsShader.bindGuidedFilterUniforms(locations.programId)  // U-03: guided filter

// ─── SED COMMANDS — SECTION C ────────────────────────────────────────────────
//
// C.1) LutRenderer.kt — anchor: 16-space indent `shadows = params.shadows` (no comma).
//      Único no arquivo (LutRenderer usa 16-space por estar dentro de if(recipeEnabled)).
// sed -i 's|^                shadows = params\.shadows$|                shadows = params.shadows\n            ShadowsHighlightsShader.bindGuidedFilterUniforms(locations.programId)  // U-03: guided filter|' "$lr"
//
// C.2) RawDemosaicProcessor.kt — anchor: 12-space indent `shadows = params.shadows` (no comma).
//      Único no arquivo (RawDemosaicProcessor usa local vars highlightsLocation/shadowsLocation,
//      distinguishable from LutRenderer's 16-space `locations.uHighlightsLocation`).
// sed -i 's|^            shadows = params\.shadows$|            shadows = params.shadows\n        ShadowsHighlightsShader.bindGuidedFilterUniforms(program)  // U-03: guided filter|' "$rdp"
//
// C.3) RealtimeVideoRenderer.kt — anchor: 12-space indent `shadows = params.shadows,` (trailing comma).
//      Único no arquivo (trailing comma é específico do Kotlin style deste arquivo).
// sed -i 's|^            shadows = params\.shadows,$|            shadows = params.shadows,\n        ShadowsHighlightsShader.bindGuidedFilterUniforms(locations.programId)  // U-03: guided filter|' "$rvr"

// ═══════════════════════════════════════════════════════════════════════════════
// SECTION D — LeicaConfig.kt: ToneMappingConfig + 3 accessors
// ═══════════════════════════════════════════════════════════════════════════════

// ─── ANTES (P-baseline — ToneMappingConfig has 5 fields, no guided_filter) ─
// L141-L147:
//    /** tone_mapping — AgX + filmic shoulders. */
//    data class ToneMappingConfig(
//        @SerializedName("enabled") val enabled: Boolean? = true,
//        @SerializedName("contrast") val contrast: Double? = 1.10,
//        @SerializedName("highlight_rolloff") val highlightRolloff: Double? = 0.35,
//        @SerializedName("shadow_lift") val shadowLift: Double? = 0.10,
//        @SerializedName("film_like_curve") val filmLikeCurve: Boolean? = true,
//    )
//
// L605-L609 (accessors):
//    val toneMappingEnabled: Boolean get() = currentConfig?.toneMapping?.enabled ?: true
//    val toneMappingContrast: Double get() = currentConfig?.toneMapping?.contrast ?: 1.10
//    val toneMappingHighlightRolloff: Double get() = currentConfig?.toneMapping?.highlightRolloff ?: 0.35
//    val toneMappingShadowLift: Double get() = currentConfig?.toneMapping?.shadowLift ?: 0.10
//    val toneMappingFilmLikeCurve: Boolean get() = currentConfig?.toneMapping?.filmLikeCurve ?: true

// ─── DEPOIS (U-03 — 3 new fields + 3 new accessors) ─────────────────────────
// L141-L153 (ToneMappingConfig extended):
//    /** tone_mapping — AgX + filmic shoulders + U-03 guided filter. */
//    data class ToneMappingConfig(
//        @SerializedName("enabled") val enabled: Boolean? = true,
//        @SerializedName("contrast") val contrast: Double? = 1.10,
//        @SerializedName("highlight_rolloff") val highlightRolloff: Double? = 0.35,
//        @SerializedName("shadow_lift") val shadowLift: Double? = 0.10,
//        @SerializedName("film_like_curve") val filmLikeCurve: Boolean? = true,
//        // U-03: guided filter (replaces bilateral in ShadowsHighlightsShader — kills HDR halos)
//        @SerializedName("guided_filter_enabled") val guidedFilterEnabled: Boolean? = true,
//        @SerializedName("guided_filter_radius") val guidedFilterRadius: Double? = 10.0,
//        @SerializedName("guided_filter_eps") val guidedFilterEps: Double? = 0.01,
//    )
//
// L605-L614 (3 new accessors after toneMappingFilmLikeCurve):
//    val toneMappingEnabled: Boolean get() = currentConfig?.toneMapping?.enabled ?: true
//    val toneMappingContrast: Double get() = currentConfig?.toneMapping?.contrast ?: 1.10
//    val toneMappingHighlightRolloff: Double get() = currentConfig?.toneMapping?.highlightRolloff ?: 0.35
//    val toneMappingShadowLift: Double get() = currentConfig?.toneMapping?.shadowLift ?: 0.10
//    val toneMappingFilmLikeCurve: Boolean get() = currentConfig?.toneMapping?.filmLikeCurve ?: true
//
//    // U-03: guided filter (replaces bilateral in ShadowsHighlightsShader — kills HDR halos)
//    val toneMappingGuidedFilterEnabled: Boolean get() = currentConfig?.toneMapping?.guidedFilterEnabled ?: true
//    val toneMappingGuidedFilterRadius: Double get() = currentConfig?.toneMapping?.guidedFilterRadius ?: 10.0
//    val toneMappingGuidedFilterEps: Double get() = currentConfig?.toneMapping?.guidedFilterEps ?: 0.01

// ─── SED COMMANDS — SECTION D ────────────────────────────────────────────────
// Variável: lcfg=config/LeicaConfig.kt (ou path real do LeicaConfig.kt no repo)
//
// D.1) Add 3 fields to ToneMappingConfig (anchor: `film_like_curve` field, único no arquivo):
// sed -i 's|@SerializedName("film_like_curve") val filmLikeCurve: Boolean? = true,|@SerializedName("film_like_curve") val filmLikeCurve: Boolean? = true,\n        // U-03: guided filter (replaces bilateral in ShadowsHighlightsShader — kills HDR halos)\n        @SerializedName("guided_filter_enabled") val guidedFilterEnabled: Boolean? = true,\n        @SerializedName("guided_filter_radius") val guidedFilterRadius: Double? = 10.0,\n        @SerializedName("guided_filter_eps") val guidedFilterEps: Double? = 0.01,|' "$lcfg"
//
// D.2) Add 3 accessors after toneMappingFilmLikeCurve (anchor único no arquivo):
// sed -i 's|val toneMappingFilmLikeCurve: Boolean get() = currentConfig?.toneMapping?.filmLikeCurve ?: true|val toneMappingFilmLikeCurve: Boolean get() = currentConfig?.toneMapping?.filmLikeCurve ?: true\n\n    // U-03: guided filter (replaces bilateral in ShadowsHighlightsShader — kills HDR halos)\n    val toneMappingGuidedFilterEnabled: Boolean get() = currentConfig?.toneMapping?.guidedFilterEnabled ?: true\n    val toneMappingGuidedFilterRadius: Double get() = currentConfig?.toneMapping?.guidedFilterRadius ?: 10.0\n    val toneMappingGuidedFilterEps: Double get() = currentConfig?.toneMapping?.guidedFilterEps ?: 0.01|' "$lcfg"

// ═══════════════════════════════════════════════════════════════════════════════
// CONFIG JSON SCHEMA (config/leica_perfect.json — tone_mapping section)
// ═══════════════════════════════════════════════════════════════════════════════
//
// Atualizar config/leica_perfect.json L55-L62 para incluir 3 novos campos:
//
//   "tone_mapping": {
//     "_comment_v65": "v6.5 U-03+U-08: shadow_lift 0.05→0.03, highlight_rolloff 0.35→0.30",
//     "_comment_u03": "U-03: guided_filter replaces bilateral — kills HDR halos. Disable for A/B test.",
//     "enabled": true,
//     "contrast": 1.1,
//     "highlight_rolloff": 0.30,
//     "shadow_lift": 0.03,
//     "film_like_curve": true,
//     "guided_filter_enabled": true,
//     "guided_filter_radius": 10.0,
//     "guided_filter_eps": 0.01
//   },
//
// Defaults:
//   guided_filter_enabled = true     (kill halos by default)
//   guided_filter_radius  = 10.0     (px, range 2..16 — guided filter window radius)
//   guided_filter_eps     = 0.01     (regularization — larger = smoother, smaller = sharper edges)
//
// A/B testing: set guided_filter_enabled=false to revert to legacy bilateral
// (preserved in shader as fallback path when uGuidedFilterEnabled == 0).

// ═══════════════════════════════════════════════════════════════════════════════
// REFERENCIADO POR (NÃO QUEBRAR)
// ═══════════════════════════════════════════════════════════════════════════════
//   - LeicaConfig.toneMappingGuidedFilter{Enabled,Radius,Eps}
//   - LeicaConfig.kt §ACCESSORS (L605-L614 após patch) — 3 novos accessors
//   - LeicaConfig.kt §ToneMappingConfig (L141-L153 após patch) — 3 novos fields
//   - ShadowsHighlightsShader.kt L1-L4 (import LeicaConfig adicionado por U-03)
//   - ShadowsHighlightsShader.kt L25-L33 (3 uniforms adicionados por U-03)
//   - ShadowsHighlightsShader.kt L124-L143 (shSampleBaseL dispatch + guided helper)
//   - ShadowsHighlightsShader.kt L6-L45 (bindUniforms extended + bindGuidedFilterUniforms)
//   - RawShaders.kt L165 (${ShadowsHighlightsShader.GLSL} inject — automático)
//   - RawAdjustmentPassShaders.kt L46 (${ShadowsHighlightsShader.GLSL} inject — automático)
//   - LutImageProcessor.kt L1095 (bindUniforms overload — auto-binds via U-03)
//   - LutImageProcessor.kt L3676 (${ShadowsHighlightsShader.GLSL} inject — automático)
//   - LutRenderer.kt L1439 (bindUniformLocations + explicit bindGuidedFilterUniforms)
//   - LutRenderer.kt ColorPassProgram.kt L131-L132 (locations.uHighlights/uShadows
//     Location pre-resolved — não precisa de programa extra para guided filter
//     porque bindGuidedFilterUniforms resolve via glGetUniformLocation em runtime)
//   - RawDemosaicProcessor.kt L10338 (bindUniformLocations + explicit bindGuidedFilterUniforms)
//   - RealtimeVideoRenderer.kt L403 (bindUniformLocations + explicit bindGuidedFilterUniforms)
//   - VideoLutEffect.kt L890 (bindUniforms overload — auto-binds via U-03)
//   - PreviewShadowsHighlightsShader.kt (preview variant — NÃO tocada por U-03;
//     preview path mantém bilateral legacy para preview performance. Se
//     necessário, aplicar U-03 separadamente à preview variant.)
//   - DngPhotonLocalToneMapper.kt (PGTM — NÃO tocado, já usa LLF+BGU halo-resistant)
//   - DngPhotonLocalToneMapGpuShaders.kt (PGTM GPU — NÃO tocado)
//   - GlesYuvStacker.kt L1948-1950 (Mertens weights — NÃO tocado, U-03 step 3 separado)
//   - DngProfileToneCurve.kt L105-109 (PGTM curve consts — NÃO tocado, U-03 step 2 separado)
//
// VALIDAÇÃO PÓS-PATCH (build engineer):
//   1. `grep -n 'uniform float uGuidedFilterRadius' ShadowsHighlightsShader.kt` => 1 match
//   2. `grep -n 'uniform float uGuidedFilterEps' ShadowsHighlightsShader.kt` => 1 match
//   3. `grep -n 'uniform int   uGuidedFilterEnabled' ShadowsHighlightsShader.kt` => 1 match
//   4. `grep -n 'shGuidedFilterBaseL' ShadowsHighlightsShader.kt` => 2 matches (decl + call)
//   5. `grep -n 'uGuidedFilterEnabled == 1' ShadowsHighlightsShader.kt` => 1 match (dispatch)
//   6. `grep -n 'import com.hinnka.mycamera.raw.LeicaConfig' ShadowsHighlightsShader.kt` => 1 match
//   7. `grep -n 'fun bindGuidedFilterUniforms' ShadowsHighlightsShader.kt` => 1 match
//   8. `grep -n 'bindGuidedFilterUniforms' LutRenderer.kt` => 1 match (caller added)
//   9. `grep -n 'bindGuidedFilterUniforms' RawDemosaicProcessor.kt` => 1 match (caller added)
//  10. `grep -n 'bindGuidedFilterUniforms' RealtimeVideoRenderer.kt` => 1 match (caller added)
//  11. `grep -n 'toneMappingGuidedFilterEnabled' LeicaConfig.kt` => 1 match (accessor)
//  12. `grep -n 'guided_filter_enabled' LeicaConfig.kt` => 1 match (data class field)
//  13. `grep -n 'guided_filter_enabled' config/leica_perfect.json` => 1 match (config schema)
//  14. Build Gradle assembly + smoke test: capture 1 high-contrast scene
//      (tree-line against sky), comparar halos com guided_filter_enabled=true
//      vs false. Confirmar halos 2-6px eliminados quando true.
//  15. A/B test: alternar `guided_filter_enabled` no JSON em runtime (hot-reload)
//      e confirmar toggle entre guided filter (sem halos) e bilateral (com halos).
// ═══════════════════════════════════════════════════════════════════════════════
