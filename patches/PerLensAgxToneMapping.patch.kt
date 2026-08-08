// ═══════════════════════════════════════════════════════════════════════════════
// PerLensAgxToneMapping.patch.kt — P-41 (v6.1)
// ═══════════════════════════════════════════════════════════════════════════════
//
// ⚠️  DOCUMENTATION-ONLY — Este arquivo NÃO é compilado. Mostra o que o sed em
//     build-archlinux.sh cmd_patch() P-41 injeta no upstream PhotonCamera.
//
// O QUE FAZ:
//   Adiciona 4 novas companion FUNCTIONS a RawToneMappingParameters.kt após o
//   `val DEFAULT = RawToneMappingParameters()`:
//     1. agxToeForLens(lensKey): Double       — per-lens toe power
//     2. agxShoulderForLens(lensKey): Double  — per-lens shoulder power
//     3. agxBlackRelativeExposureForLens(lensKey): Float  — per-lens black exposure
//     4. forLens(lensKey): RawToneMappingParameters       — factory per-lens
//
//   ADDITIVE — DEFAULT e globals do P-2 são preservados. OPT-IN: consumers
//   precisam explicitamente chamar forLens(lensKey) ao invés de DEFAULT.
//   P-43 (CameraViewModel) faz esse opt-in no runtime chokepoint.
//
// POR QUE:
//   - Task 5-b: 6 NEW per-lens continuous params (gamma_contrast/shoulder/
//     shadow_lift + ccm_ratio_warm/cool) substituindo 2 GCam preset integers
//     por 24 continuous values.
//   - Task 5-c: accessors gammaContrastForLens/gammaShoulderForLens/
//     gammaShadowLiftForLens adicionados ao LeicaConfig.
//   - P-41: factory function que combina esses 3 accessors num RawToneMappingParameters
//     object pronto pra usar no shader.
//   - Per-lens AgX values (beat-GCam):
//       main (OV50E):    contrast=1.10, shoulder=0.35, shadow_lift=0.10 (Leica authentic)
//       UW (S5KJN1):     contrast=1.15, shoulder=0.32, shadow_lift=0.12 (punchier wide)
//       tele (S5K3J1):   contrast=1.18, shoulder=0.38, shadow_lift=0.08 (portrait punchy)
//       front (OV32B):   contrast=1.08, shoulder=0.30, shadow_lift=0.15 (soft selfies)
//
// ARQUIVO TARGET: app/src/main/java/com/hinnka/mycamera/raw/RawToneMappingParameters.kt
// ÂNCORA SED: `val DEFAULT = RawToneMappingParameters()` (unique, line ~95)
// INJEÇÃO: append após o val DEFAULT (preserva DEFAULT intacto)
//
// LeicaConfig ACCESSORS USADOS:
//   - LeicaConfig.gammaContrastForLens(lensKey): Double
//   - LeicaConfig.gammaShoulderForLens(lensKey): Double
//   - LeicaConfig.gammaShadowLiftForLens(lensKey): Double
//   - LeicaConfig.agxBlackRelativeExposure: Float (global — floor pra evitar log(0))
//   - LeicaConfig.agxWhiteRelativeExposure: Float (derived de effectiveToneContrast)
//
// LIMITAÇÕES:
//   - P-41 é OPT-IN — por si só não muda runtime. Consumers precisam chamar
//     forLens(lensKey) ao invés de DEFAULT.
//   - P-43 (PerLensAgxConsumer) é o patch que realmente ativa isso no runtime
//     (CameraViewModel.resolveCaptureRawToneMappingParameters).
//   - Sem P-43, runtime behavior é inalterado (DEFAULT ainda é usado em todos
//     os 9 call sites em RawDemosaicProcessor.kt).
//   - forLens() retorna uma NOVA instância cada chamada — não há caching. Para
//     hot path, consumers devem cachear a instância por lensKey (não feito em P-43
//     porque resolveCaptureRawToneMappingParameters só é chamado 1x por capture).
// ═══════════════════════════════════════════════════════════════════════════════

package com.hinnka.mycamera.raw

import com.hinnka.mycamera.raw.LeicaConfig

// ─── ANTES (após P-2, DEFAULT com globals) ───────────────────────────────────
data class RawToneMappingParameters(
    val toePower: Double = DEFAULT_TOE_POWER,
    val shoulderPower: Double = DEFAULT_SHOULDER_POWER,
    val blackRelativeExposure: Float = DEFAULT_BLACK_RELATIVE_EXPOSURE,
    val whiteRelativeExposure: Float = DEFAULT_WHITE_RELATIVE_EXPOSURE,
) {
    companion object {
        // P-2 race-safe conversions
        val DEFAULT_TOE_POWER get() = LeicaConfig.pgtmToePower
        val DEFAULT_SHOULDER_POWER get() = LeicaConfig.pgtmShoulderPower
        val DEFAULT_BLACK_RELATIVE_EXPOSURE get() = LeicaConfig.agxBlackRelativeExposure
        val DEFAULT_WHITE_RELATIVE_EXPOSURE get() = LeicaConfig.agxWhiteRelativeExposure

        val DEFAULT = RawToneMappingParameters()
    }
}

// ─── DEPOIS (após P-41 append) ───────────────────────────────────────────────
data class RawToneMappingParameters(
    val toePower: Double = DEFAULT_TOE_POWER,
    val shoulderPower: Double = DEFAULT_SHOULDER_POWER,
    val blackRelativeExposure: Float = DEFAULT_BLACK_RELATIVE_EXPOSURE,
    val whiteRelativeExposure: Float = DEFAULT_WHITE_RELATIVE_EXPOSURE,
) {
    companion object {
        // P-2 race-safe conversions
        val DEFAULT_TOE_POWER get() = LeicaConfig.pgtmToePower
        val DEFAULT_SHOULDER_POWER get() = LeicaConfig.pgtmShoulderPower
        val DEFAULT_BLACK_RELATIVE_EXPOSURE get() = LeicaConfig.agxBlackRelativeExposure
        val DEFAULT_WHITE_RELATIVE_EXPOSURE get() = LeicaConfig.agxWhiteRelativeExposure

        val DEFAULT = RawToneMappingParameters()

        // v6.1 — Per-lens AgX variants (P-41). Consumers chamam forLens(lensKey)
        // ao invés de DEFAULT para obter tuning per-lens.
        // P-43 (CameraViewModel) ativa este opt-in no runtime chokepoint.

        fun agxToeForLens(lensKey: String): Double = LeicaConfig.gammaShadowLiftForLens(lensKey)
        // main=0.10, UW=0.12, tele=0.08, front=0.15

        fun agxShoulderForLens(lensKey: String): Double = LeicaConfig.gammaShoulderForLens(lensKey)
        // main=0.35, UW=0.32, tele=0.38, front=0.30

        fun agxBlackRelativeExposureForLens(lensKey: String): Float = LeicaConfig.agxBlackRelativeExposure
        // global floor (0.002) — evita log(0)

        /**
         * forLens — factory que retorna RawToneMappingParameters com valores per-lens.
         * Substitui DEFAULT quando o caller quer per-lens tuning.
         *
         * @param lensKey "main"/"uw"/"tele"/"front"
         * @return RawToneMappingParameters com toe/shoulder/black/white per-lens
         */
        fun forLens(lensKey: String): RawToneMappingParameters = RawToneMappingParameters(
            toePower = agxToeForLens(lensKey),
            shoulderPower = agxShoulderForLens(lensKey),
            blackRelativeExposure = agxBlackRelativeExposureForLens(lensKey),
            whiteRelativeExposure = LeicaConfig.agxWhiteRelativeExposure,
        )
    }
}

// ─── SED COMMAND (executado pelo build-archlinux.sh) ─────────────────────────
// sed -i '/^    val DEFAULT = RawToneMappingParameters()$/a\
//\
//    // v6.1 — Per-lens AgX variants (P-41). Consumers chamam forLens(lensKey) ao invés de DEFAULT.\
//    fun agxToeForLens(lensKey: String): Double = LeicaConfig.gammaShadowLiftForLens(lensKey)\
//    fun agxShoulderForLens(lensKey: String): Double = LeicaConfig.gammaShoulderForLens(lensKey)\
//    fun agxBlackRelativeExposureForLens(lensKey: String): Float = LeicaConfig.agxBlackRelativeExposure\
//    /** forLens — factory que retorna RawToneMappingParameters com valores per-lens. */\
//    fun forLens(lensKey: String): RawToneMappingParameters = RawToneMappingParameters(\
//        toePower = agxToeForLens(lensKey),\
//        shoulderPower = agxShoulderForLens(lensKey),\
//        blackRelativeExposure = agxBlackRelativeExposureForLens(lensKey),\
//        whiteRelativeExposure = LeicaConfig.agxWhiteRelativeExposure,\
//    )' "$rtmp"

// ─── REFERENCIADO POR (NÃO QUEBRAR) ──────────────────────────────────────────
//   - P-2 (RawToneMappingParameters AgX defaults — base pra P-41)
//   - P-43 (PerLensAgxConsumer — ativa forLens() no CameraViewModel chokepoint)
//   - LeicaConfig.gammaContrastForLens/gammaShoulderForLens/gammaShadowLiftForLens
//   - LeicaConfig.agxWhiteRelativeExposure / agxBlackRelativeExposure
//   - LeicaConfig.PerLensTuning data class (gamma_contrast/shoulder/shadow_lift)
//   - RawDemosaicProcessor.kt 9 DEFAULT references (NOT patched — function-default-args)
//   - beat_gcam_rationale.md §gamma (per-lens AgX values derivation)
//
// ⚠️  GAP G1 STATUS: P-41 alone is OPT-IN (zero runtime effect). G1 is CLOSED only
//     when P-43 (PerLensAgxConsumer) is also applied. P-41 + P-43 together = per-lens
//     AgX active at runtime.
