// ═══════════════════════════════════════════════════════════════════════════════
// PerLensDcpRatio.patch.kt — P-39 (v6.1)
// ═══════════════════════════════════════════════════════════════════════════════
//
// ⚠️  DOCUMENTATION-ONLY — Este arquivo NÃO é compilado. Mostra o que o sed em
//     build-archlinux.sh cmd_patch() P-39 injeta no upstream PhotonCamera.
//
// O QUE FAZ:
//   Substitui as chamadas `LeicaConfig.dcpRatioWarm` e `LeicaConfig.dcpRatioCool`
//   (valores globais, aplicados por P-20) por versões PER-LENS:
//     `LeicaConfig.ccmRatioWarmForLens(cameraId ?: "main")`
//     `LeicaConfig.ccmRatioCoolForLens(cameraId ?: "main")`
//   Defensive seds também tratam literais `0.5f` e `1.6f` caso P-20 não casou.
//
// POR QUE:
//   - Task 5-a gap L5: per_lens.ccm_ratio_warm/cool defined no JSON mas SEM
//     accessor nem wiring — JSON era doc-only.
//   - Task 5-c adicionou accessors ccmRatioWarmForLens/ccmRatioCoolForLens.
//   - P-39 faz o wiring: substitui global ratio por per-lens ratio.
//   - Beat-GCam (Task 5-b): cada lens tem características ópticas diferentes,
//     requerendo DCP warm/cool ratios diferentes:
//       main (OV50E):    warm=0.52, cool=1.62 (Leica M8 standard)
//       UW (S5KJN1):     warm=0.50, cool=1.60 (slightly cooler — wide angle)
//       tele (S5K3J1):   warm=0.54, cool=1.64 (warmer — portraits)
//       front (OV32B):   warm=0.48, cool=1.58 (coolest — selfies under diverse lighting)
//
// ARQUIVO TARGET: app/src/main/java/com/hinnka/mycamera/raw/DcpProfile.kt
// ÂNCORA SED: `LeicaConfig.dcpRatioWarm` / `LeicaConfig.dcpRatioCool` (injetados por P-20)
// INJEÇÃO: substituição in-place (3 seds: 2 pra P-20 outputs, 2 defensive pra literais)
//
// LeicaConfig ACCESSORS USADOS:
//   - LeicaConfig.ccmRatioWarmForLens(lensKey: String): Float
//   - LeicaConfig.ccmRatioCoolForLens(lensKey: String): Float
//   - LeicaConfig.lensKeyFromCameraId(cameraId: String): String
//
// LIMITAÇÕES:
//   - calculateInterpolationWeight (onde ratioWarm/Cool são usados) é DEAD CODE
//     atualmente: computeInterpolatedCameraToXyz é private com NO callers.
//     Wiring está pronto pra uso futuro, ZERO runtime cost no momento.
//     Gap G2 (Task 6-c definitive audit) — documentado pra v7.0.
//   - Per-lens lookup assume `cameraId` em scope no call site. DcpProfile.kt
//     resolveRenderPlan tem cameraId threading já (via patches P-19/P-30/P-33).
//   - Valores per-lens são tiny adjustments (±0.04) — impacto visual sutil mas
//     mensurável em cenas com illuminantes mistos (tungsten + daylight).
// ═══════════════════════════════════════════════════════════════════════════════

package com.hinnka.mycamera.raw

import com.hinnka.mycamera.raw.LeicaConfig

// ─── ANTES (após P-20, valores globais) ──────────────────────────────────────
fun calculateInterpolationWeight(
    illuminant1: ...,
    illuminant2: ...,
    wbGains: FloatArray
): Float {
    val ratioWarm = LeicaConfig.dcpRatioWarm   // global 0.52
    val ratioCool = LeicaConfig.dcpRatioCool   // global 1.62
    // ... interpolation math ...
}

// ─── DEPOIS (após P-39 per-lens) ─────────────────────────────────────────────
fun calculateInterpolationWeight(
    illuminant1: ...,
    illuminant2: ...,
    wbGains: FloatArray,
    lensKey: String = "main"  // P-39 adiciona param (backward-compatible default)
): Float {
    val ratioWarm = LeicaConfig.ccmRatioWarmForLens(lensKey)  // main=0.52, UW=0.50, tele=0.54, front=0.48
    val ratioCool = LeicaConfig.ccmRatioCoolForLens(lensKey)  // main=1.62, UW=1.60, tele=1.64, front=1.58
    // ... interpolation math ...
}

// ─── SED COMMANDS (executados pelo build-archlinux.sh) ───────────────────────
// Substitui global por per-lens (caso P-20 já aplicou):
//   sed -i 's|LeicaConfig.dcpRatioWarm|LeicaConfig.ccmRatioWarmForLens(cameraId ?: "main")|g' "$dcp"
//   sed -i 's|LeicaConfig.dcpRatioCool|LeicaConfig.ccmRatioCoolForLens(cameraId ?: "main")|g' "$dcp"
//
// Defensive (caso P-20 não casou — literais originais 0.5f/1.6f):
//   sed -i 's|val ratioWarm = 0.5f|val ratioWarm = LeicaConfig.ccmRatioWarmForLens(cameraId ?: "main")|' "$dcp"
//   sed -i 's|val ratioCool = 1.6f|val ratioCool = LeicaConfig.ccmRatioCoolForLens(cameraId ?: "main")|' "$dcp"

// ─── REFERENCIADO POR (NÃO QUEBRAR) ──────────────────────────────────────────
//   - P-19 (DcpProfile force Leica DCP/LUT/frame)
//   - P-20 (DcpProfile illuminant ratios global — P-39 substitui por per-lens)
//   - P-30 (DcpProfile CCM tint shift — outro patch no mesmo arquivo)
//   - P-33 (ColorTintShift per-lens — outro patch no mesmo arquivo)
//   - LeicaConfig.ccmRatioWarmForLens() / ccmRatioCoolForLens()
//   - LeicaConfig.PerLensTuning data class (ccm_ratio_warm/cool fields)
//   - DEFINITIVE_AUDIT_v6.2.md gap G2 (dead code caveat documented)
//   - beat_gcam_rationale.md §ccm (per-lens values derivation)
//
// ⚠️  GAP G2 STATUS: calculateInterpolationWeight é DEAD CODE (computeInterpolatedCameraToXyz
//     private sem callers). Wiring está em place mas com ZERO runtime effect.
//     Documentado pra v7.0 — não é bug, é limitação arquitetural upstream.
