// ═══════════════════════════════════════════════════════════════════════════════
// U04_DetailPreserveWiring.patch.kt — U-04 (v6.5) — Detail Preserve Wiring
// ═══════════════════════════════════════════════════════════════════════════════
//
// ⚠️  DOCUMENTATION-ONLY — Este arquivo NÃO é compilado. Mostra o que o sed em
//     build-archlinux.sh cmd_patch() U-04 injeta no upstream PhotonCamera.
//
// ⭐⭐ PATCH STATUS: REAL — actually effective at runtime. ⭐⭐
//     Fecha U-04 (oil-painting em foliage/skin/fine textures em RAW + JPEG path).
//
// O QUE FAZ (4 targets, 8 seds):
//
//   A) SHADER  — ChromaDenoiseShaders.kt::PASS_CHROMA_DENOISE
//      - Adiciona `uniform float uDetailPreserve;` após `uOutputStrength` (L51)
//      - Modifica o cálculo de coarseMix (L284-286):
//          ANTES:  coarseMix = uOutputStrength * featheredEdgeSupport(coarseSupport, 2.0)
//          DEPOIS: coarseMix = uOutputStrength * featheredEdgeSupport(coarseSupport, 2.0)
//                                       * (1.0 - uDetailPreserve)
//        Com detail_preserve=0.75, o coarse radius-14 chroma smoothing é reduzido a 25%
//        do valor original — elimina o principal amplificador de oil-painting.
//
//   B) RAW CONSUMER — RawDemosaicProcessor.kt::buildDenoiseProfileParams (L5924)
//      - Troca `val centralPixelWeight = 0.1f * scale` por
//        `val centralPixelWeight = LeicaConfig.noiseReductionDetailPreserve.toFloat() * scale`
//        Com config 0.75, centralPixelWeight sobe de 0.10 → 0.75 (7.5× mais peso no
//        pixel original = menos averaging NLM = preserva micro-detail).
//      - Adiciona binding `uDetailPreserve` no dispatch do chroma denoise (logo após
//        o binding existente de `uOutputStrength` em renderDefaultChromaDenoise).
//
//   C) LUT CONSUMER — LutImageProcessor.kt::buildBitmapDenoiseParams (L2492)
//      - Mesma troca de centralPixelWeight que BLOCK B (mirror p/ JPEG path).
//      - Adiciona binding `uDetailPreserve` no dispatch do bitmap chroma denoise
//        (após `uOutputStrength` em renderBitmapChromaDenoise).
//      - Adiciona `import com.hinnka.mycamera.raw.LeicaConfig` (grep-guarded —
//        no-op se U-02 já o tiver adicionado).
//
//   D) NLM CONFIG — DenoiseProfileNlmConfig.kt::COARSE_GUIDE_WEIGHT (L35)
//      - Troca `const val COARSE_GUIDE_WEIGHT = 8.0f` por getter config-driven:
//          val COARSE_GUIDE_WEIGHT: Float
//              get() = 8.0f * (1.0f - LeicaConfig.noiseReductionDetailPreserve.toFloat() * 0.5f)
//        Com config 0.75, cai de 8.0 → 5.0 (37.5% menos leverage de low-frequency
//        structure no FUSED_ACCU patch weight — menos aggressive contour rejection).
//
// POR QUE:
//   - Stock PhotonCamera NÃO consome o campo JSON `noise_reduction.detail_preserve`
//     (grep em /tmp/photon_src retorna ZERO matches). Acesso já existe em
//     LeicaConfig.noiseReductionDetailPreserve (default 0.96, leica_perfect.json=0.75)
//     mas nada o lê. Resultado: NLM e chroma denoise operam com constantes hardcoded
//     super-agressivas (centralPixelWeight=0.1, COARSE_GUIDE_WEIGHT=8.0, coarseMix
//     sem cap) que aplainam fine texture em foliage/skin/fabric → "oil-painting".
//   - Três amplificadores de oil-painting identificados pela exploração U-01/U-04:
//       1. NLM centralPixelWeight baixo (0.1) → muito averaging entre patches
//          vizinhos → micro-detail perdido.
//       2. Chroma denoise radius-14 mix sem cap → chroma smoothing em larga escala
//          destrói sub-pixel color micro-contrast (foliage/skin).
//       3. COARSE_GUIDE_WEIGHT=8.0 → low-frequency guide rejeita diferenças
//          small-magnitude que ainda contêm detail real.
//   - U-04 ataca os 3 simultaneamente com um único knob (detail_preserve):
//       centralPixelWeight ∝ detail_preserve
//       coarseMix_cap      = (1 - detail_preserve)
//       COARSE_GUIDE_WEIGHT = 8.0 * (1 - 0.5 * detail_preserve)
//
// ARQUIVOS TARGET:
//   - app/src/main/java/com/hinnka/mycamera/lut/ChromaDenoiseShaders.kt (L51, L284-286)
//   - app/src/main/java/com/hinnka/mycamera/raw/RawDemosaicProcessor.kt (L5924, L5750-5753)
//   - app/src/main/java/com/hinnka/mycamera/lut/LutImageProcessor.kt (L2492, L2184-2187, L18)
//   - app/src/main/java/com/hinnka/mycamera/raw/DenoiseProfileNlmConfig.kt (L35)
//
// ÂNCORAS SED (strings exatas a matchear):
//   - ChromaDenoiseShaders.kt L51:    `        uniform float uOutputStrength;`
//   - ChromaDenoiseShaders.kt L285:   `                uOutputStrength * featheredEdgeSupport(coarseSupport, 2.0);`
//   - RawDemosaicProcessor.kt L5924:  `        val centralPixelWeight = 0.1f * scale`
//   - RawDemosaicProcessor.kt L5750:  bloco 3-linhas começando com `        GLES30.glUniform1f(`
//                                     + `            GLES30.glGetUniformLocation(chromaDenoiseProgram, "uOutputStrength"),`
//                                     + `            ChromaDenoiseDefaults.outputStrength(strength)`
//   - LutImageProcessor.kt L2492:     `        val centralPixelWeight = 0.1f * scale`
//   - LutImageProcessor.kt L2184:     bloco 3-linhas começando com `        GLES30.glUniform1f(`
//                                     + `            GLES30.glGetUniformLocation(bitmapChromaDenoiseProgram, "uOutputStrength"),`
//                                     + `            ChromaDenoiseDefaults.outputStrength(strength)`
//   - LutImageProcessor.kt L18:       `import com.hinnka.mycamera.raw.DenoiseProfileNlmConfig`
//   - DenoiseProfileNlmConfig.kt L35: `    const val COARSE_GUIDE_WEIGHT = 8.0f`
//
// LeicaConfig ACCESSORS USADOS (já existentes, criados em P-78 config sync):
//   - LeicaConfig.noiseReductionDetailPreserve: Double  (default 0.96, leica_perfect.json=0.75)
//   - LeicaConfig.noiseReductionChrominance: Double      (default 0.70, leica_perfect.json=0.96)
//
// IMPACTO NUMÉRICO (com config detail_preserve=0.75):
//   ┌────────────────────────────┬──────────────┬──────────────┬───────────┐
//   │ Alvo                       │ Stock        │ U-04        │ Δ         │
//   ├────────────────────────────┼──────────────┼──────────────┼───────────┤
//   │ centralPixelWeight (RAW)   │ 0.100        │ 0.750       │ +650%     │
//   │ centralPixelWeight (JPEG)  │ 0.100        │ 0.750       │ +650%     │
//   │ COARSE_GUIDE_WEIGHT        │ 8.000        │ 5.000       │ −37.5%    │
//   │ coarseMix multiplier       │ 1.000        │ 0.250       │ −75.0%    │
//   │ Effective NLM smoothing    │ 1.000 (ref)  │ ~0.250      │ −75%      │
//   └────────────────────────────┴──────────────┴──────────────┴───────────┘
//
// LIMITAÇÕES:
//   - centralPixelWeight alto (0.75) reduz a magnitude do NLM. Se detail_preserve=1.0,
//     centralPixelWeight=1.0 significa que só o pixel central conta — NLM essencialmente
//     desativado. Isto é INTENCIONAL — detail_preserve é o knob user-facing para esse
//     trade-off. Em leica_perfect.json, detail_preserve=0.75 deixa ~25% de smoothing.
//   - COARSE_GUIDE_WEIGHT baixo (5.0) pode slightly aumentar ghosting em flat regions
//     porque low-contrast structure fica menos effective em reject noise. Mitigado por
//     fine-scale NLM estar unchanged (centralPixelWeight já preserva enough structure).
//   - coarseMix *= (1 - detail_preserve) significa que em detail_preserve=1.0, o radius-14
//     chroma smoothing é totalmente suprimido. Para noisy low-light scenes onde chroma
//     NR é essencial, recommenda-se detail_preserve ≤ 0.85.
//   - Não modifica o medium-mix (radius-4, L267-269) nem o fine-mix (radius-1, L251-252).
//     Apenas o coarse radius-14 é cap-pado — ele é o maior amplificador de oil-painting.
//   - Não modifica uDenoiseMix (FINISH_V2 L253, L271) — overall luma denoise blend.
//     detail_preserve atua APENAS em: (a) centralPixelWeight (NLM patch bias), (b) chroma
//     coarse radius-14 mix, (c) COARSE_GUIDE_WEIGHT (low-freq guide leverage). O luma
//     denoise overall strength permanece controlado por `noise_reduction.luminance` (P-78.4).
//   - Em bitmap path (JPEG capture), LutImageProcessor.kt centralPixelWeight já era
//     hardcoded idêntico ao RAW. U-04 aplica o mesmo fix nos dois paths para
//     consistência — RAW max e JPEG max ambos se beneficiam.
//   - Nenhum novo campo JSON é adicionado — `noise_reduction.detail_preserve` já existe
//     em leica_perfect.json (L44, value=0.75) e em LeicaConfig.noiseReductionDetailPreserve
//     (LeicaConfig.kt L596). U-04 APENAS cria consumers para o accessor pré-existente.
// ═══════════════════════════════════════════════════════════════════════════════

package com.hinnka.mycamera.raw

import com.hinnka.mycamera.raw.LeicaConfig

// ═══════════════════════════════════════════════════════════════════════════════
// BLOCK A — SHADER: ChromaDenoiseShaders.kt (L51 + L284-286)
// ═══════════════════════════════════════════════════════════════════════════════

// ─── ANTES (L51 — bloco de uniforms do PASS_CHROMA_DENOISE) ────────────────────
//        uniform float uH;
//        uniform float uOutputStrength;
//        uniform float uEdgeGuidanceRelaxation;
//        uniform int uCameraRgbInput;

// ─── DEPOIS (U-04 — adiciona uDetailPreserve entre uOutputStrength e uEdgeGuidanceRelaxation)
//        uniform float uH;
//        uniform float uOutputStrength;
//        uniform float uDetailPreserve;  // U-04: detail_preserve cap on coarse radius mix
//        uniform float uEdgeGuidanceRelaxation;
//        uniform int uCameraRgbInput;

// ─── ANTES (L284-286 — coarse radius-14 mix no final de main()) ────────────────
//            float coarseMix =
//                uOutputStrength * featheredEdgeSupport(coarseSupport, 2.0);
//            vec2 coarseChroma = mix(mediumChroma, coarseCandidate, coarseMix);

// ─── DEPOIS (U-04 — cap coarseMix por (1 - uDetailPreserve) para suprimir oil-painting)
//            float coarseMix =
//                uOutputStrength * featheredEdgeSupport(coarseSupport, 2.0) *
//                (1.0 - uDetailPreserve);  // U-04: detail_preserve caps radius-14 chroma smoothing
//            vec2 coarseChroma = mix(mediumChroma, coarseCandidate, coarseMix);

// ─── SED COMMANDS — SECTION A ─────────────────────────────────────────────────
// Variável: cds=app/src/main/java/com/hinnka/mycamera/lut/ChromaDenoiseShaders.kt
//
// A.1) Adiciona uniform uDetailPreserve após uOutputStrength (única ocorrência no arquivo):
// sed -i '/^        uniform float uOutputStrength;$/a\        uniform float uDetailPreserve;  // U-04: detail_preserve cap on coarse radius mix' "$cds"
//
// A.2) Modifica o cálculo de coarseMix — adiciona fator (1.0 - uDetailPreserve):
//      Padrão exato da linha (16 spaces indent, dentro do raw string trimIndent):
//      `                uOutputStrength * featheredEdgeSupport(coarseSupport, 2.0);`
// sed -i 's|uOutputStrength \* featheredEdgeSupport(coarseSupport, 2.0);|uOutputStrength * featheredEdgeSupport(coarseSupport, 2.0) *\n                (1.0 - uDetailPreserve);  // U-04: detail_preserve caps radius-14 chroma smoothing|' "$cds"

// ═══════════════════════════════════════════════════════════════════════════════
// BLOCK B — RAW CONSUMER: RawDemosaicProcessor.kt (L5924 + L5750-5753)
// ═══════════════════════════════════════════════════════════════════════════════

// ─── ANTES (L5924 — buildDenoiseProfileParams, dentro do bloco val centralPixelWeight) ─
//        val weightTuning = DenoiseProfileNlmConfig.weightTuning(patchRadius)
//        val centralPixelWeight = 0.1f * scale
//        val signalScale = floatArrayOf(

// ─── DEPOIS (U-04 — config-driven via LeicaConfig.noiseReductionDetailPreserve) ─
//        val weightTuning = DenoiseProfileNlmConfig.weightTuning(patchRadius)
//        val centralPixelWeight = LeicaConfig.noiseReductionDetailPreserve.toFloat() * scale  // U-04: was 0.1f
//        val signalScale = floatArrayOf(

// ─── ANTES (L5750-5753 — renderDefaultChromaDenoise, bloco uOutputStrength) ───
//        GLES30.glUniform1f(
//            GLES30.glGetUniformLocation(chromaDenoiseProgram, "uOutputStrength"),
//            ChromaDenoiseDefaults.outputStrength(strength)
//        )
//        GLES30.glUniform1f(
//            GLES30.glGetUniformLocation(chromaDenoiseProgram, "uEdgeGuidanceRelaxation"),
//            edgeGuidanceRelaxation
//        )

// ─── DEPOIS (U-04 — insere binding uDetailPreserve entre uOutputStrength e uEdgeGuidanceRelaxation)
//        GLES30.glUniform1f(
//            GLES30.glGetUniformLocation(chromaDenoiseProgram, "uOutputStrength"),
//            ChromaDenoiseDefaults.outputStrength(strength)
//        )
//        GLES30.glUniform1f(
//            GLES30.glGetUniformLocation(chromaDenoiseProgram, "uDetailPreserve"),
//            LeicaConfig.noiseReductionDetailPreserve.toFloat()  // U-04: detail_preserve → shader cap
//        )
//        GLES30.glUniform1f(
//            GLES30.glGetUniformLocation(chromaDenoiseProgram, "uEdgeGuidanceRelaxation"),
//            edgeGuidanceRelaxation
//        )

// ─── SED COMMANDS — SECTION B ─────────────────────────────────────────────────
// Variável: rdp=app/src/main/java/com/hinnka/mycamera/raw/RawDemosaicProcessor.kt
// (LeicaConfig está no mesmo package `com.hinnka.mycamera.raw` — sem import extra)
//
// B.1) Troca hardcoded `0.1f * scale` por LeicaConfig-driven em buildDenoiseProfileParams:
// sed -i 's|val centralPixelWeight = 0.1f \* scale|val centralPixelWeight = LeicaConfig.noiseReductionDetailPreserve.toFloat() * scale  // U-04: was 0.1f|' "$rdp"
//
// B.2) Adiciona binding uDetailPreserve após o bloco uOutputStrength em renderDefaultChromaDenoise.
//      Anchor: bloco 4-linhas `glUniform1f( + chromaDenoiseProgram, "uOutputStrength" + ChromaDenoiseDefaults.outputStrength(strength) + )`.
//      Estratégia: append o novo bloco 4-linhas após o `)` que fecha o uOutputStrength.
//      Nota: precisa de 3 Ns (1 implicit read + 2 Ns explícitos) para incluir a linha `)` no pattern space
//      antes do `s|...|`. Pattern copiado do U-02 mas corrigido — U-02 usava 2 Ns e a substitution
//      não fazia match na linha `)` (o `defaultUsmThreshold\n        )` nunca aparecia no pattern space).
// sed -i '/GLES30\.glUniform1f($/{N;/glGetUniformLocation(chromaDenoiseProgram, "uOutputStrength"),/{N;N;s|ChromaDenoiseDefaults\.outputStrength(strength)\n        )|ChromaDenoiseDefaults.outputStrength(strength)\n        )\n        GLES30.glUniform1f(\n            GLES30.glGetUniformLocation(chromaDenoiseProgram, "uDetailPreserve"),\n            LeicaConfig.noiseReductionDetailPreserve.toFloat()  // U-04: detail_preserve → shader cap\n        )|}}' "$rdp"

// ═══════════════════════════════════════════════════════════════════════════════
// BLOCK C — LUT CONSUMER: LutImageProcessor.kt (L2492 + L2184-2187 + L18)
// ═══════════════════════════════════════════════════════════════════════════════

// ─── ANTES (L18 — bloco de imports do package com.hinnka.mycamera.raw) ────────
// import com.hinnka.mycamera.raw.DenoiseProfileNlmConfig
// import com.hinnka.mycamera.raw.DenoiseProfileShaders
// import com.hinnka.mycamera.raw.HncsFilmCurveMode
// ...

// ─── DEPOIS (U-04 — adiciona import LeicaConfig, grep-guarded contra duplicatas)
// import com.hinnka.mycamera.raw.LeicaConfig  // U-04: noise_reduction.detail_preserve (no-op if U-02 already added)
// import com.hinnka.mycamera.raw.DenoiseProfileNlmConfig
// import com.hinnka.mycamera.raw.DenoiseProfileShaders
// import com.hinnka.mycamera.raw.HncsFilmCurveMode
// ...

// ─── ANTES (L2492 — buildBitmapDenoiseParams, mesmo hardcoded que RAW) ────────
//        val weightTuning = DenoiseProfileNlmConfig.weightTuning(patchRadius)
//        val centralPixelWeight = 0.1f * scale
//        return BitmapDenoiseParams(

// ─── DEPOIS (U-04 — mirror do BLOCK B.1 para JPEG path) ───────────────────────
//        val weightTuning = DenoiseProfileNlmConfig.weightTuning(patchRadius)
//        val centralPixelWeight = LeicaConfig.noiseReductionDetailPreserve.toFloat() * scale  // U-04: was 0.1f
//        return BitmapDenoiseParams(

// ─── ANTES (L2184-2187 — renderBitmapChromaDenoise, bloco uOutputStrength) ───
//        GLES30.glUniform1f(
//            GLES30.glGetUniformLocation(bitmapChromaDenoiseProgram, "uOutputStrength"),
//            ChromaDenoiseDefaults.outputStrength(strength)
//        )
//        GLES30.glUniform1f(
//            GLES30.glGetUniformLocation(
//                bitmapChromaDenoiseProgram,
//                "uEdgeGuidanceRelaxation"
//            ),
//            edgeGuidanceRelaxation
//        )

// ─── DEPOIS (U-04 — insere binding uDetailPreserve após uOutputStrength em bitmap path)
//        GLES30.glUniform1f(
//            GLES30.glGetUniformLocation(bitmapChromaDenoiseProgram, "uOutputStrength"),
//            ChromaDenoiseDefaults.outputStrength(strength)
//        )
//        GLES30.glUniform1f(
//            GLES30.glGetUniformLocation(bitmapChromaDenoiseProgram, "uDetailPreserve"),
//            LeicaConfig.noiseReductionDetailPreserve.toFloat()  // U-04: detail_preserve → shader cap
//        )
//        GLES30.glUniform1f(
//            GLES30.glGetUniformLocation(
//                bitmapChromaDenoiseProgram,
//                "uEdgeGuidanceRelaxation"
//            ),
//            edgeGuidanceRelaxation
//        )

// ─── SED COMMANDS — SECTION C ─────────────────────────────────────────────────
// Variável: lip=app/src/main/java/com/hinnka/mycamera/lut/LutImageProcessor.kt
//
// C.1) Adiciona import LeicaConfig antes do primeiro import do package raw (grep-guarded):
// grep -q '^import com\.hinnka\.mycamera\.raw\.LeicaConfig' "$lip" || sed -i '/^import com\.hinnka\.mycamera\.raw\.DenoiseProfileNlmConfig$/i import com.hinnka.mycamera.raw.LeicaConfig  // U-04: noise_reduction.detail_preserve (no-op if U-02 already added)' "$lip"
//
// C.2) Troca hardcoded `0.1f * scale` por LeicaConfig-driven em buildBitmapDenoiseParams:
// sed -i 's|val centralPixelWeight = 0.1f \* scale|val centralPixelWeight = LeicaConfig.noiseReductionDetailPreserve.toFloat() * scale  // U-04: was 0.1f|' "$lip"
//
// C.3) Adiciona binding uDetailPreserve após o bloco uOutputStrength em renderBitmapChromaDenoise.
//      Anchor: `ChromaDenoiseDefaults.outputStrength(strength)\n        )` precedido por
//      `bitmapChromaDenoiseProgram, "uOutputStrength"`. Append 4 linhas novas.
//      Nota: 3 Ns (igual a B.2 — ver comentário lá sobre o bug do U-02).
// sed -i '/GLES30\.glUniform1f($/{N;/glGetUniformLocation(bitmapChromaDenoiseProgram, "uOutputStrength"),/{N;N;s|ChromaDenoiseDefaults\.outputStrength(strength)\n        )|ChromaDenoiseDefaults.outputStrength(strength)\n        )\n        GLES30.glUniform1f(\n            GLES30.glGetUniformLocation(bitmapChromaDenoiseProgram, "uDetailPreserve"),\n            LeicaConfig.noiseReductionDetailPreserve.toFloat()  // U-04: detail_preserve → shader cap\n        )|}}' "$lip"

// ═══════════════════════════════════════════════════════════════════════════════
// BLOCK D — NLM CONFIG: DenoiseProfileNlmConfig.kt (L35)
// ═══════════════════════════════════════════════════════════════════════════════

// ─── ANTES (L35 — const val COARSE_GUIDE_WEIGHT hardcoded) ────────────────────
//     // Coarse guide differences describe structure rather than independent RGB noise. Give them
//     // enough leverage to reject low-contrast contours without turning the decision into a hard
//     // threshold.
//     const val COARSE_GUIDE_WEIGHT = 8.0f

// ─── DEPOIS (U-04 — getter config-driven, preserva comment original) ──────────
//     // Coarse guide differences describe structure rather than independent RGB noise. Give them
//     // enough leverage to reject low-contrast contours without turning the decision into a hard
//     // threshold.
//     // U-04: detail_preserve scales the coarse guide down — higher detail_preserve = less
//     // low-frequency structure influence = more micro-detail preserved (less oil-painting).
//     val COARSE_GUIDE_WEIGHT: Float
//         get() = 8.0f * (1.0f - LeicaConfig.noiseReductionDetailPreserve.toFloat() * 0.5f)

// ─── SED COMMANDS — SECTION D ─────────────────────────────────────────────────
// Variável: npnc=app/src/main/java/com/hinnka/mycamera/raw/DenoiseProfileNlmConfig.kt
// (LeicaConfig está no mesmo package `com.hinnka.mycamera.raw` — sem import extra)
//
// D.1) Troca `const val COARSE_GUIDE_WEIGHT = 8.0f` por getter config-driven (multi-linha):
// sed -i 's|    const val COARSE_GUIDE_WEIGHT = 8.0f|    // U-04: detail_preserve scales the coarse guide down — higher detail_preserve = less\n    // low-frequency structure influence = more micro-detail preserved (less oil-painting).\n    val COARSE_GUIDE_WEIGHT: Float\n        get() = 8.0f * (1.0f - LeicaConfig.noiseReductionDetailPreserve.toFloat() * 0.5f)|' "$npnc"

// ═══════════════════════════════════════════════════════════════════════════════
// VALIDAÇÃO PÓS-PATCH (sanity checks opcionais para build-archlinux.sh)
// ═══════════════════════════════════════════════════════════════════════════════
//
// Após aplicar os 8 seds, verificar que cada âncora foi efetivamente substituída:
//
//   grep -c 'uniform float uDetailPreserve;' "$cds"                       # expect: 1
//   grep -c '(1.0 - uDetailPreserve)' "$cds"                              # expect: 1
//   grep -c 'LeicaConfig.noiseReductionDetailPreserve' "$rdp"             # expect: 2 (centralPixelWeight + uDetailPreserve bind)
//   grep -c 'LeicaConfig.noiseReductionDetailPreserve' "$lip"             # expect: 2 (centralPixelWeight + uDetailPreserve bind; import NÃO conta)
//   grep -c '^import com\.hinnka\.mycamera\.raw\.LeicaConfig' "$lip"      # expect: 1 (grep-guarded contra duplicata)
//   grep -c 'LeicaConfig.noiseReductionDetailPreserve' "$npnc"            # expect: 1 (COARSE_GUIDE_WEIGHT getter)
//   grep -c 'const val COARSE_GUIDE_WEIGHT' "$npnc"                       # expect: 0 (const removido)
//   grep -c 'val COARSE_GUIDE_WEIGHT: Float' "$npnc"                      # expect: 1 (novo getter)
//
// Compile-time sanity: Kotlin compila getter `val X: Float get() = ...` em qualquer
// contexto que `const val X = ...` era aceito — exceto anotações @CompileTimeOnly.
// Nenhum uso de COARSE_GUIDE_WEIGHT em /tmp/photon_src requer const-context (único
// uso é L69 `coarseGuideWeight = COARSE_GUIDE_WEIGHT` dentro de função `weightTuning`).
// ═══════════════════════════════════════════════════════════════════════════════
