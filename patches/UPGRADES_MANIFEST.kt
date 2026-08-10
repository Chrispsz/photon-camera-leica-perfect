// ═══════════════════════════════════════════════════════════════════════════════
// UPGRADES_MANIFEST.kt — v6.5 Avaliação Consolidada
// ═══════════════════════════════════════════════════════════════════════════════
//
// ⚠️  DOCUMENTATION-ONLY — Este arquivo NÃO é compilado. É o manifest dos 6 patches
//     U-01 a U-06 criados com base na avaliação VLM de 5 rodadas comparativas.
//
// CONTEXTO:
//   - 4 rodadas comparativas (PhotonCamera padrão vs Câmera Nativa): Nativa 4 × 0 Photon
//   - 1 rodada isolada (M9 CCD): 75% de sucesso como emulação CCD
//   - Diagnóstico: pipeline base com 3 problemas críticos + modo padrão com 3 problemas
//   - 10 upgrades propostos (U-01 a U-10), 6 implementados como patches (U-01 a U-06)
//
// PATCHES CRIADOS (6 arquivos, 3772 linhas total):
//
//   ┌────────────────────────────────────────────────────────────────────────────┐
//   │ ID  │ Patch File                          │ LOC │ Priority │ Phase │ Status│
//   ├─────┼─────────────────────────────────────┼─────┼──────────┼───────┼───────┤
//   │ U-01│ U01_DenoiseShadowsReinforcement     │ 1058│ P0       │ 2     │ ✅    │
//   │ U-02│ U02_AdaptiveSharpening              │  457│ P0       │ 1     │ ✅    │
//   │ U-03│ U03_BilateralToGuidedFilter         │  591│ P0       │ 2     │ ✅    │
//   │ U-04│ U04_DetailPreserveWiring            │  336│ P1       │ 2     │ ✅    │
//   │ U-05│ U05_AwbClamping                     │  562│ P0       │ 1     │ ✅    │
//   │ U-06│ U06_AeHistogramFeedback             │  768│ P1       │ 1     │ ✅    │
//   └─────┴─────────────────────────────────────┴─────┴──────────┴───────┴───────┘
//
// UPGRADES PENDENTES (4 não implementados — documentação/config only):
//
//   ┌────────────────────────────────────────────────────────────────────────────┐
//   │ ID  │ Título                              │ Priority │ Phase │ Status      │
//   ├─────┼─────────────────────────────────────┼──────────┼───────┼─────────────┤
//   │ U-07│ Reduzir Saturação Artificial        │ P2       │ 1     │ ✅ Config   │
//   │ U-08│ Calibração ICC + LUT 3D             │ P2       │ 3     │ ⏳ Futuro    │
//   │ U-09│ Expandir Perfis Analógicos          │ P2       │ 3     │ ⏳ Futuro    │
//   │ U-10│ Film Grain em Outros Perfis         │ P3       │ 3     │ ⏳ Futuro    │
//   └─────┴─────────────────────────────────────┴──────────┴───────┴─────────────┘
//
// CONFIG TUNING (leica_perfect.json v6.5):
//   - sharpening.amount:           0.09 → 0.054  (-40%, U-02)
//   - sharpening.radius:            0.9 → 0.7     (-22%, U-02)
//   - sharpening.edge_mask_strength: 2.2 → 3.5   (+59%, U-02 adaptive)
//   - noise_reduction.chrominance: 0.94 → 0.96   (+2%, U-01)
//   - noise_reduction.detail_preserve: 0.85 → 0.75 (-12%, U-04 oil-painting)
//   - color.saturation_boost:      1.02 → 1.0    (neutro, U-07)
//   - color.vibrance:              1.01 → 1.04   (seletivo, U-07)
//   - tone_mapping.shadow_lift:    0.05 → 0.03   (-40%, U-03)
//   - tone_mapping.highlight_rolloff: 0.35 → 0.30 (-14%, U-03)
//   - processing.default_exposure_ev: 0.88 → 0.5 (-43%, U-06)
//   - processing.pgtm_pre_tonemap_exposure_boost_ev: 0.8 → 0.4 (-50%, U-06)
//   - processing.usm_radius:       0.9 → 0.7      (U-02 sync)
//   - processing.usm_threshold:    0.004 → 0.005 (U-02 sync)
//
// NOVAS SEÇÕES DE CONFIG:
//   - awb_clamp: { enabled, min_cct=3500, max_cct=7000, smoothing_alpha=0.15 } (U-05)
//   - ae_protection: { enabled, highlight/shadow clip thresholds, histogram_feedback } (U-06)
//
// ARQUIVOS DE CÓDIGO AFETADOS (upstream PhotonCamera):
//   - raw/RawShaders.kt                    — SHARPEN_FRAGMENT_SHADER (U-02)
//   - raw/RawDemosaicProcessor.kt          — renderSharpenPass, buildDenoiseProfileParams, uCentralPixelWeight (U-01, U-02, U-04)
//   - raw/RawSharpeningDefaults.kt         — CAPTURE_DEFAULT (U-02)
//   - raw/DenoiseProfileShaders.kt         — FINISH_V2, SEARCH_RADIUS (U-01)
//   - raw/DenoiseProfileNlmConfig.kt       — COARSE_GUIDE_WEIGHT, searchOffsets (U-01, U-04)
//   - raw/ChromaDenoiseShaders.kt          — coarseMix, uDetailPreserve (U-04)
//   - lut/LutImageProcessor.kt             — renderLutSharpenPass (U-02, U-04)
//   - lut/ShadowsHighlightsShader.kt       — shSampleBaseL bilateral → guided filter (U-03)
//   - camera/Camera2Controller.kt          — applyAutoWhiteBalanceSettings, applyExposureSettings (U-05, U-06)
//   - camera/CameraState.kt                — lastClipLowFraction (U-06)
//   - raw/MeteringSystem.kt                — HighlightCompressionEstimate (U-06 reference)
//   - NEW: raw/AeHistogramProtector.kt     — histogram-based EV correction (U-06)
//   - patches/LeicaConfig.kt               — new accessors (U-01 to U-06)
//
// ORDEM DE APLICAÇÃO RECOMENDADA NO build-archlinux.sh:
//   1. U-02 (sharpening — shader + consumer + defaults)
//   2. U-04 (detail_preserve — depends on U-02's grep-guarded import)
//   3. U-01 (denoise — independent but touches same files as U-04)
//   4. U-03 (guided filter — independent shader replacement)
//   5. U-05 (AWB clamping — Camera2Controller + LeicaConfig)
//   6. U-06 (AE histogram — Camera2Controller + new file + LeicaConfig)
//
// ⚠️  BUG CONHECIDO (U-02) — RESOLVIDO em v6.5.0 (build 4, commit 782622f):
//     O bug original era um problema de SED ({N;s|...|} vs {N;N;s|...|}) que
//     impedia o binding dos uniforms adaptativos (uEdgeMaskStrength/uNoiseLimit/
//     uDarkLimit/uAdaptive). A reescrita dos patches U-01..U-06 em Python3
//     (apply_upgrades_v65.py) ELIMINOU este bug — Python usa replace_exact() /
//     insert_after() com string match exato, não sed multi-linha.
//     VERIFICAÇÃO: apply_upgrades_v65.py linhas 184-187 (RAW) e 240-243 (LUT)
//     confirmam que os 4 uniforms são bindados corretamente via GLES30.glUniform*.
//     Status: OBSOLETO — mantido aqui apenas como histórico.
//
// VALIDAÇÃO:
//   Após aplicar os patches e buildar o APK, refazer as 5 rodadas de avaliação:
//     - Meta modo padrão: Photon vence/empata ≥ 2/4 cenas comparativas
//     - Meta M9 CCD: avaliação sobe de 75% para ≥ 85%
//     - ColorChecker ΔE2000 < 5
//     - SNR em sombras ≥ 40dB
//     - MTF50 preservado sem halos
// ═══════════════════════════════════════════════════════════════════════════════
