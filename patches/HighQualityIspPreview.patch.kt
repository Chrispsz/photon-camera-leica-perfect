// ═══════════════════════════════════════════════════════════════════════════════
// HighQualityIspPreview.patch.kt — P-35 (v6.0)
// ═══════════════════════════════════════════════════════════════════════════════
//
// ⚠️  DOCUMENTATION-ONLY — Este arquivo NÃO é compilado. Mostra o que o sed em
//     build-archlinux.sh cmd_patch() P-35 injeta no upstream PhotonCamera.
//
// O QUE FAZ:
//   Estende P-28 (que força HIGH_QUALITY ISP para stills) aplicando o mesmo
//   guard no `applyImageQualitySettings` — que controla EDGE_MODE e
//   NOISE_REDUCTION_MODE pra AMBOS stills E preview streams.
//
// POR QUE:
//   - Stock PhotonCamera em burst mode (isBurst=true) desabilita HQ ISP:
//       applyImageQualitySettings() sets EDGE_MODE=FAST(1) e NOISE_REDUCTION_MODE=FAST(1)
//     (Camera2Controller.kt L3877-L3928, line L3881 e L3900).
//   - Rationale stock: burst mode tem muitos frames, ISP FAST reduz latência.
//   - v6.0 RAWmax: RAW Radiance fusion alinha no RAW domain pre-demosaic — ISP
//     NR é redundante (NLM no domínio RAW já faz melhor). Mas EDGE_MODE FAST
//     ainda degrada preview quality, e ainda aplica no single-frame fallback.
//   - P-35: força HQ ISP também no preview (isBurst path), já que:
//       (1) Preview latency não é sensível a 1-2ms de ISP HQ
//       (2) RAWmax faz stacking no RAW domain — ISP NR é additive, não conflita
//       (3) EDGE_MODE HQ melhoria visível no preview (sharper corners)
//
// ARQUIVO TARGET: app/src/main/java/com/hinnka/mycamera/camera/Camera2Controller.kt
// ÂNCORA SED: `private fun applyImageQualitySettings(builder: CaptureRequest.Builder, isBurst: Boolean) {`
// INJEÇÃO: append guard no início da função (similar a P-28)
//
// LeicaConfig ACCESSORS USADOS:
//   - LeicaConfig.forceHighQualityIsp: Boolean (default true)
//
// LIMITAÇÕES:
//   - ISP HQ em preview pode causar 1-3ms de latency extra em devices low-end.
//     Xiaomi 15T Dimensity 8300-Ultra aguenta sem problema.
//   - Em burst mode com 15+ frames, ISP HQ é wasteful (RAW stacking substitui).
//     Mas o impacto é menor que o ganho de consistency preview→stills.
//   - Em video recording, ISP HQ é ESSENCIAL (RAW domain não se aplica a YUV video).
// ═══════════════════════════════════════════════════════════════════════════════

package com.hinnka.mycamera.camera

import com.hinnka.mycamera.raw.LeicaConfig

// ─── ANTES (upstream original) ───────────────────────────────────────────────
private fun applyImageQualitySettings(builder: CaptureRequest.Builder, isBurst: Boolean) {
    // Stock: burst mode downgrade pra FAST (latency)
    if (isBurst) {
        builder.set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_FAST)
        builder.set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_FAST)
    } else {
        builder.set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_HIGH_QUALITY)
        builder.set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY)
    }
    // ...
}

// ─── DEPOIS (após P-28 + P-35) ───────────────────────────────────────────────
// P-28 já patcheou applyFastStillPostProcessingSettings (stills path).
// P-35 patcheia applyImageQualitySettings (preview + stills path):

private fun applyImageQualitySettings(builder: CaptureRequest.Builder, isBurst: Boolean) {
    // P-35: força HQ ISP em todos os paths quando LeicaConfig.forceHighQualityIsp
    if (LeicaConfig.forceHighQualityIsp) {
        builder.set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_HIGH_QUALITY)
        builder.set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY)
        return  // early return — pula o branch isBurst abaixo
    }
    // Stock path (fallback quando forceHighQualityIsp = false)
    if (isBurst) {
        builder.set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_FAST)
        builder.set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_FAST)
    } else {
        builder.set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_HIGH_QUALITY)
        builder.set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY)
    }
    // ...
}

// ─── SED COMMAND (executado pelo build-archlinux.sh) ─────────────────────────
// sed -i '/^    private fun applyImageQualitySettings(builder: CaptureRequest.Builder, isBurst: Boolean) {$/a\        if (LeicaConfig.forceHighQualityIsp) { builder.set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_HIGH_QUALITY); builder.set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY); return }' "$c2c"

// ─── REFERENCIADO POR (NÃO QUEBRAR) ──────────────────────────────────────────
//   - P-28 (força HQ ISP em stills — base pra P-35)
//   - LeicaConfig.forceHighQualityIsp (default true via advanced.force_high_quality_isp)
//   - Camera2Controller.applyFastStillPostProcessingSettings (P-28 patch site)
//   - Camera2Controller.applyHighQualityStillPostProcessingSettings (chamado por P-28)
//   - max_capability_audit.md §PROCESSING (ISP HQ é um max arquitetural)
