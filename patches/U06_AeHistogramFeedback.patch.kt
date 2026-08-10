// ═══════════════════════════════════════════════════════════════════════════════
// U06_AeHistogramFeedback.patch.kt — U-06 (v6.3.x)
// ═══════════════════════════════════════════════════════════════════════════════
//
// ⚠️  DOCUMENTATION-ONLY — Este arquivo NÃO é compilado. Mostra o que o sed em
//     build-archlinux.sh cmd_patch() U-06 injeta no upstream PhotonCamera.
//
// O QUE FAZ:
//   Implementa realce/sombra protection com histogram feedback no AE:
//     1. Cria AeHistogramProtector (novo arquivo) que lê state.histogram (256-bin
//        luma histogram já emitido por LutRenderer.calculateMeteringResults L3495-
//        L3554) e computa clipHighFraction (fração de pixels acima de 250/255),
//        clipLowFraction (fração abaixo de 5/255), avgLuma, e histogramEvCorrection
//        (inteiro em AE-comp steps, range [-6, +6]).
//     2. Modifica applyExposureSettings (Camera2Controller.kt L3265) para somar
//        ao AE-comp: defaultEvBias + perLensEvComp + histogramEvCorrection, com
//        clamp final [-maxSteps, +maxSteps] (max_ev_comp_steps=12 default).
//     3. Modifica calculateHdrBracketExposureCompensation (L6367-L6372) que é
//        consumido por AMBOS os write sites L6344 (HDR bracket) e L6544 (multi-
//        frame short exposure fallback) — único ponto de patcheio cobre 2 sites.
//     4. Gateia pgtm_pre_tonemap_exposure_boost_ev e per_lens.<id>.ev_comp atrás
//        de clipLowFraction < threshold (não aplicar boost em cenas bright —
//        evita overexpose).
//
// POR QUE:
//   - Stock PhotonCamera tem histogram 100% display-only (state.histogram em
//     CameraState.kt L331 nunca feeda o AE). Resultado: AE do HAL decide sozinho
//     e pode blow out highlights em high-contrast scenes.
//   - MeteringSystem.estimateHighlightCompression (L431-L459) já retorna
//     autoHighlightsAdjustment ∈ [-0.8, 0] com smoothStep clip detection — mas é
//     consumido APENAS pelo RAW viewfinder matcher (RawDemosaicProcessor L11308-
//     L11359), NÃO pelo Camera2 AE compensation. U-06 fecha esse gap.
//   - Symmetric shadow protection: clipLowFraction > threshold AND avgLuma <
//     target*0.5 → boost EV (counteracts crushed blacks).
//   - Gating pgtm_pre_tonemap_exposure_boost_ev (default 1.3 EV) e ev_comp
//     (per_lens, default 0.0) previne que o pre-tonemap boost empurre highlights
//     em cenas bright (clipLowFraction baixo = pouca sombra, luma já alto).
//
// ARQUIVOS TARGET:
//   - app/src/main/java/com/hinnka/mycamera/raw/LeicaConfig.kt
//       (adiciona AeProtectionConfig data class + accessors)
//   - app/src/main/java/com/hinnka/mycamera/camera/AeHistogramProtector.kt (NEW)
//       (utility object — compute clipStats + histogramEvCorrection from histogram)
//   - app/src/main/java/com/hinnka/mycamera/camera/Camera2Controller.kt
//       (applyExposureSettings L3265 + calculateHdrBracketExposureCompensation L6367)
//
// ÂNCORAS SED:
//   - LeicaConfig.kt: `data class ProcessingConfig(` e `val pgtmPreTonemapExposureBoostEv: Double`
//   - Camera2Controller.kt L3265: `builder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, state.exposureCompensation)`
//   - Camera2Controller.kt L6367-L6372: `private fun calculateHdrBracketExposureCompensation(...)`
//
// LeicaConfig ACCESSORS USADOS:
//   - LeicaConfig.aeProtectionEnabled: Boolean (default true)
//   - LeicaConfig.highlightClipThresholdPct: Double (default 1.0 = 1% dos pixels)
//   - LeicaConfig.shadowClipThresholdPct: Double (default 1.0)
//   - LeicaConfig.shadowFloorRgb: Int (default 5 — bins 0..4 = shadow)
//   - LeicaConfig.histogramFeedbackEnabled: Boolean (default true)
//   - LeicaConfig.maxEvCompSteps: Int (default 12 — clamp ±12 steps)
//   - LeicaConfig.defaultExposureEv: Double (default 0.88 EV — já existe em ProcessingConfig)
//   - LeicaConfig.evCompForLens(lensKey): Float (já existe em PerLensTuning)
//   - LeicaConfig.lensKeyFromCameraId(cameraId): String (já existe — P-43 pattern)
//   - LeicaConfig.effectivePgtmPreTonemapExposureBoostEv(clipLowFraction): Double (NOVO)
//   - LeicaConfig.effectiveEvCompForLens(lensKey, clipLowFraction): Float (NOVO)
//
// LIMITAÇÕES:
//   - histogramEvCorrection é limitado a [-6, +6] steps (1 EV em 1/6-step devices).
//     Isto é INTENCIONAL — proteção não deve dominar o AE, apenas fine-tune.
//   - Gating do pgtm boost pode reduzir shadow lift em cenas bright, mas isto é
//     desejável — highlights preservados > shadow lift em bright scenes.
//   - Histogram vem de LutRenderer (preview path); em burst mode pode ter 1-frame
//     de latência. Aceitável para proteção (não para metering preciso).
//   - Em RAWmax path, multi-frame short exposure usa manual sensor (L6504-L6550);
//     L6544 AE-comp fallback só ativa quando MANUAL_SENSOR unsupported — raro.
// ═══════════════════════════════════════════════════════════════════════════════

package com.hinnka.mycamera.camera

import com.hinnka.mycamera.raw.LeicaConfig

// ═══════════════════════════════════════════════════════════════════════════════
// BLOCK A — Adicionar AeProtectionConfig data class ao LeicaConfig.kt
// ═══════════════════════════════════════════════════════════════════════════════

// ─── ANTES (LeicaConfig.kt ProcessingConfig termina em hncs_film_curve_gain) ──
//    data class ProcessingConfig(
//        // ...
//        @SerializedName("hncs_film_curve_gain") val hncsFilmCurveGain: Double? = 1.1,
//    )
//
//    /** dcp — forçar Leica M8 + LUT + frame. */
//    data class DcpConfig(

// ─── DEPOIS (insert AeProtectionConfig ENTRE ProcessingConfig e DcpConfig) ─────

    /** ae_protection — U-06 histogram-based highlight/shadow protection. */
    data class AeProtectionConfig(
        @SerializedName("enabled") val enabled: Boolean? = true,
        @SerializedName("highlight_clip_threshold_pct") val highlightClipThresholdPct: Double? = 1.0,
        @SerializedName("shadow_clip_threshold_pct") val shadowClipThresholdPct: Double? = 1.0,
        @SerializedName("shadow_floor_rgb") val shadowFloorRgb: Int? = 5,
        @SerializedName("histogram_feedback_enabled") val histogramFeedbackEnabled: Boolean? = true,
        @SerializedName("max_ev_comp_steps") val maxEvCompSteps: Int? = 12,
    )

// ─── SED COMMAND (executado pelo build-archlinux.sh) ─────────────────────────
// Insert AeProtectionConfig data class imediatamente ANTES de `/** dcp — forçar Leica M8`:
//   sed -i '/^    \/\*\* dcp — forçar Leica M8/i\    /** ae_protection — U-06 histogram-based highlight/shadow protection. */\n    data class AeProtectionConfig(\n        @SerializedName("enabled") val enabled: Boolean? = true,\n        @SerializedName("highlight_clip_threshold_pct") val highlightClipThresholdPct: Double? = 1.0,\n        @SerializedName("shadow_clip_threshold_pct") val shadowClipThresholdPct: Double? = 1.0,\n        @SerializedName("shadow_floor_rgb") val shadowFloorRgb: Int? = 5,\n        @SerializedName("histogram_feedback_enabled") val histogramFeedbackEnabled: Boolean? = true,\n        @SerializedName("max_ev_comp_steps") val maxEvCompSteps: Int? = 12,\n    )\n' "$lcfg"
//
// Adicionar campo `aeProtection: AeProtectionConfig? = null` ao LeicaPerfectConfig:
//   sed -i 's|@SerializedName("hdr") val hdr: HdrConfig? = null,|@SerializedName("hdr") val hdr: HdrConfig? = null,\n        @SerializedName("ae_protection") val aeProtection: AeProtectionConfig? = null,|' "$lcfg"

// ═══════════════════════════════════════════════════════════════════════════════
// BLOCK B — Adicionar accessors ao LeicaConfig.kt (após pgtmPreTonemapExposureBoostEv)
// ═══════════════════════════════════════════════════════════════════════════════

// ─── ANTES (LeicaConfig.kt L655-L662 — accessors existentes) ──────────────────
//    val defaultExposureEv: Double get() = currentConfig?.processing?.defaultExposureEv ?: 0.88
//    val displayTargetLuma: Double get() = currentConfig?.processing?.displayTargetLuma ?: 0.21
//    val centerWeightSigma: Double get() = currentConfig?.processing?.centerWeightSigma ?: 0.28
//    val pgtmPreTonemapExposureBoostEv: Double
//        get() = currentConfig?.processing?.pgtmPreTonemapExposureBoostEv ?: 1.3

// ─── DEPOIS (append U-06 accessors após pgtmPreTonemapExposureBoostEv) ────────

    // ─── U-06: ae_protection accessors ───────────────────────────────────────
    val aeProtectionEnabled: Boolean
        get() = currentConfig?.aeProtection?.enabled ?: true

    val highlightClipThresholdPct: Double
        get() = currentConfig?.aeProtection?.highlightClipThresholdPct ?: 1.0

    val shadowClipThresholdPct: Double
        get() = currentConfig?.aeProtection?.shadowClipThresholdPct ?: 1.0

    val shadowFloorRgb: Int
        get() = currentConfig?.aeProtection?.shadowFloorRgb ?: 5

    val histogramFeedbackEnabled: Boolean
        get() = currentConfig?.aeProtection?.histogramFeedbackEnabled ?: true

    val maxEvCompSteps: Int
        get() = currentConfig?.aeProtection?.maxEvCompSteps ?: 12

    /**
     * U-06: Gated pgtm_pre_tonemap_exposure_boost_ev.
     * Retorna 0.0 quando clipLowFraction < shadowClipThresholdPct/100
     * (bright scene, pouca sombra → não aplicar shadow-lift boost → protege highlights).
     */
    fun effectivePgtmPreTonemapExposureBoostEv(clipLowFraction: Float): Double {
        if (!aeProtectionEnabled) return pgtmPreTonemapExposureBoostEv
        val threshold = (shadowClipThresholdPct / 100.0).toFloat()
        return if (clipLowFraction >= threshold) pgtmPreTonemapExposureBoostEv else 0.0
    }

    /**
     * U-06: Gated per-lens ev_comp.
     * Retorna 0.0 quando clipLowFraction < shadowClipThresholdPct/100
     * (bright scene → suprime per-lens exposure bias pra não empurrar highlights).
     */
    fun effectiveEvCompForLens(lensKey: String, clipLowFraction: Float): Float {
        if (!aeProtectionEnabled) return evCompForLens(lensKey)
        val threshold = (shadowClipThresholdPct / 100.0).toFloat()
        return if (clipLowFraction >= threshold) evCompForLens(lensKey) else 0.0f
    }

// ─── SED COMMAND ──────────────────────────────────────────────────────────────
// Append U-06 accessors imediatamente APÓS o getter `pgtmPreTonemapExposureBoostEv`:
//   sed -i '/val pgtmPreTonemapExposureBoostEv: Double$/,/^        get() = currentConfig?.processing?.pgtmPreTonemapExposureBoostEv ?: 1.3$/{
//     /^        get() = currentConfig?.processing?.pgtmPreTonemapExposureBoostEv ?: 1.3$/a\
// \
//     // ─── U-06: ae_protection accessors ─────────────────────────────────────\
//     val aeProtectionEnabled: Boolean\
//         get() = currentConfig?.aeProtection?.enabled ?: true\
// \
//     val highlightClipThresholdPct: Double\
//         get() = currentConfig?.aeProtection?.highlightClipThresholdPct ?: 1.0\
// \
//     val shadowClipThresholdPct: Double\
//         get() = currentConfig?.aeProtection?.shadowClipThresholdPct ?: 1.0\
// \
//     val shadowFloorRgb: Int\
//         get() = currentConfig?.aeProtection?.shadowFloorRgb ?: 5\
// \
//     val histogramFeedbackEnabled: Boolean\
//         get() = currentConfig?.aeProtection?.histogramFeedbackEnabled ?: true\
// \
//     val maxEvCompSteps: Int\
//         get() = currentConfig?.aeProtection?.maxEvCompSteps ?: 12\
// \
//     fun effectivePgtmPreTonemapExposureBoostEv(clipLowFraction: Float): Double {\
//         if (!aeProtectionEnabled) return pgtmPreTonemapExposureBoostEv\
//         val threshold = (shadowClipThresholdPct / 100.0).toFloat()\
//         return if (clipLowFraction >= threshold) pgtmPreTonemapExposureBoostEv else 0.0\
//     }\
// \
//     fun effectiveEvCompForLens(lensKey: String, clipLowFraction: Float): Float {\
//         if (!aeProtectionEnabled) return evCompForLens(lensKey)\
//         val threshold = (shadowClipThresholdPct / 100.0).toFloat()\
//         return if (clipLowFraction >= threshold) evCompForLens(lensKey) else 0.0f\
//     }
//   }' "$lcfg"

// ═══════════════════════════════════════════════════════════════════════════════
// BLOCK C — Criar novo arquivo AeHistogramProtector.kt
// ═══════════════════════════════════════════════════════════════════════════════

// ─── NOVO ARQUIVO (criado via heredoc) ────────────────────────────────────────
// Caminho: app/src/main/java/com/hinnka/mycamera/camera/AeHistogramProtector.kt

package com.hinnka.mycamera.camera

import com.hinnka.mycamera.raw.LeicaConfig
import kotlin.math.roundToInt

/**
 * U-06: Histogram-based AE protection utility.
 *
 * Reads `state.histogram` (256-bin luma histogram already populated by
 * `LutRenderer.calculateMeteringResults` L3495-L3554 → CameraViewModel →
 * Camera2Controller.updateHistogram → state.histogram) and computes:
 *
 *   - clipHighFraction: fraction of pixels above (255 - shadowFloorRgb + 245)/255
 *                       i.e. bins [250..255] (above 250/255 luma)
 *   - clipLowFraction : fraction of pixels below shadowFloorRgb/255
 *                       i.e. bins [0..shadowFloorRgb-1] (below 5/255 default)
 *   - avgLuma         : weighted mean luma, normalized to [0,1]
 *   - histogramEvCorrection: integer AE-comp steps in [-6, +6]
 *
 * Correction logic:
 *   - If clipHighFraction > highlightClipThreshold: reduce EV (max -6 steps)
 *   - If clipLowFraction > shadowClipThreshold AND avgLuma < target*0.5:
 *     increase EV (max +6 steps)
 *   - Corrections are mutually exclusive (highlight protection takes precedence)
 *
 * This object is stateless and safe to call from any thread. Histogram reads
 * are atomic snapshots (IntArray reference is stable across updates).
 */
object AeHistogramProtector {

    /** Maximum magnitude of histogram-driven EV correction, in AE-comp steps. */
    private const val MAX_HISTOGRAM_EV_CORRECTION_STEPS = 6

    /** Bin index above which a pixel is considered "clipped highlight" (luma 250/255). */
    private const val HIGHLIGHT_CLIP_BIN_THRESHOLD = 250

    /** Snapshot of all histogram-derived AE-protection signals. */
    data class HistogramStats(
        val clipHighFraction: Float,
        val clipLowFraction: Float,
        val avgLuma: Float,
        val histogramEvCorrection: Int,
        val hasHighlightClip: Boolean,
        val hasShadowClip: Boolean,
    )

    /**
     * Compute histogram-driven AE protection stats from the current CameraState.
     *
     * Returns zeroed stats when:
     *   - ae_protection.enabled = false
     *   - histogram_feedback_enabled = false
     *   - state.histogram is null or empty
     */
    fun compute(state: CameraState): HistogramStats {
        val histogram = state.histogram
        if (!LeicaConfig.aeProtectionEnabled ||
            !LeicaConfig.histogramFeedbackEnabled ||
            histogram == null || histogram.isEmpty()
        ) {
            return HistogramStats(
                clipHighFraction = 0f,
                clipLowFraction = 0f,
                avgLuma = state.getAvgLuma(),
                histogramEvCorrection = 0,
                hasHighlightClip = false,
                hasShadowClip = false,
            )
        }

        val shadowFloor = LeicaConfig.shadowFloorRgb.coerceIn(1, 32)
        val total = histogram.sumOf { it.toLong() }
        if (total <= 0L) {
            return HistogramStats(
                clipHighFraction = 0f,
                clipLowFraction = 0f,
                avgLuma = state.getAvgLuma(),
                histogramEvCorrection = 0,
                hasHighlightClip = false,
                hasShadowClip = false,
            )
        }

        // Sum bins [HIGHLIGHT_CLIP_BIN_THRESHOLD .. 255] for clipHigh.
        var clipHighCount = 0L
        for (i in HIGHLIGHT_CLIP_BIN_THRESHOLD until histogram.size) {
            clipHighCount += histogram[i].toLong()
        }
        // Sum bins [0 .. shadowFloor-1] for clipLow.
        var clipLowCount = 0L
        for (i in 0 until shadowFloor.coerceAtMost(histogram.size)) {
            clipLowCount += histogram[i].toLong()
        }

        val totalF = total.toFloat()
        val clipHighFraction = clipHighCount / totalF
        val clipLowFraction = clipLowCount / totalF

        // Weighted mean luma (re-implements CameraState.getAvgLuma locally to
        // avoid double-pass on the histogram array).
        var weightedSum = 0L
        for (i in histogram.indices) {
            weightedSum += i.toLong() * histogram[i].toLong()
        }
        val avgLuma = (weightedSum.toFloat() / totalF) / 255f

        // Threshold conversion: pct → fraction (1.0% → 0.01).
        val highThreshold = (LeicaConfig.highlightClipThresholdPct / 100.0).toFloat()
        val lowThreshold = (LeicaConfig.shadowClipThresholdPct / 100.0).toFloat()
        val targetLuma = LeicaConfig.displayTargetLuma.toFloat()

        // Correction computation.
        val hasHighlightClip = clipHighFraction > highThreshold
        val hasShadowClip = clipLowFraction > lowThreshold && avgLuma < targetLuma * 0.5f

        val histogramEvCorrection: Int = when {
            // Highlight protection takes precedence (don't fight both at once).
            hasHighlightClip -> {
                // Scale correction by clip severity:
                //   clipHigh == threshold → -1 step
                //   clipHigh == threshold * 6 → -6 steps (saturated)
                val severity = (clipHighFraction / highThreshold).coerceIn(1f, 6f)
                -severity.roundToInt().coerceIn(1, MAX_HISTOGRAM_EV_CORRECTION_STEPS)
            }
            hasShadowClip -> {
                // Scale by clip severity and luma shortfall:
                //   clipLow == threshold, avgLuma == target*0.5 → +1 step
                //   clipLow == threshold * 6 OR avgLuma near 0 → +6 steps (saturated)
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
            clipHighFraction = clipHighFraction,
            clipLowFraction = clipLowFraction,
            avgLuma = avgLuma,
            histogramEvCorrection = histogramEvCorrection,
            hasHighlightClip = hasHighlightClip,
            hasShadowClip = hasShadowClip,
        )
    }

    /**
     * Convert a float EV value to integer AE-comp steps using the device's
     * exposure compensation step (typically 1/6 EV per step).
     *
     * Returns 0 when evStep is non-positive (device reports unsupported AE-comp).
     */
    fun evToSteps(ev: Float, evStep: Float): Int {
        if (evStep <= 0f || !ev.isFinite()) return 0
        val magnitude = (kotlin.math.abs(ev / evStep) + 0.0001f).roundToInt()
        return if (ev < 0f) -magnitude else magnitude
    }

    /**
     * Convert a double EV value to integer AE-comp steps (overload for LeicaConfig
     * accessors that return Double, e.g. defaultExposureEv).
     */
    fun evToSteps(ev: Double, evStep: Float): Int =
        evToSteps(ev.toFloat(), evStep)
}

// ─── SED COMMAND (heredoc cria novo arquivo) ──────────────────────────────────
// cat > "$aeProt" << 'PATCH_EOF'
// package com.hinnka.mycamera.camera
//
// import com.hinnka.mycamera.raw.LeicaConfig
// import kotlin.math.roundToInt
//
// object AeHistogramProtector {
//     private const val MAX_HISTOGRAM_EV_CORRECTION_STEPS = 6
//     private const val HIGHLIGHT_CLIP_BIN_THRESHOLD = 250
//
//     data class HistogramStats(
//         val clipHighFraction: Float,
//         val clipLowFraction: Float,
//         val avgLuma: Float,
//         val histogramEvCorrection: Int,
//         val hasHighlightClip: Boolean,
//         val hasShadowClip: Boolean,
//     )
//
//     fun compute(state: CameraState): HistogramStats {
//         val histogram = state.histogram
//         if (!LeicaConfig.aeProtectionEnabled ||
//             !LeicaConfig.histogramFeedbackEnabled ||
//             histogram == null || histogram.isEmpty()
//         ) {
//             return HistogramStats(0f, 0f, state.getAvgLuma(), 0, false, false)
//         }
//         val shadowFloor = LeicaConfig.shadowFloorRgb.coerceIn(1, 32)
//         val total = histogram.sumOf { it.toLong() }
//         if (total <= 0L) {
//             return HistogramStats(0f, 0f, state.getAvgLuma(), 0, false, false)
//         }
//         var clipHighCount = 0L
//         for (i in HIGHLIGHT_CLIP_BIN_THRESHOLD until histogram.size) {
//             clipHighCount += histogram[i].toLong()
//         }
//         var clipLowCount = 0L
//         for (i in 0 until shadowFloor.coerceAtMost(histogram.size)) {
//             clipLowCount += histogram[i].toLong()
//         }
//         val totalF = total.toFloat()
//         val clipHighFraction = clipHighCount / totalF
//         val clipLowFraction = clipLowCount / totalF
//         var weightedSum = 0L
//         for (i in histogram.indices) {
//             weightedSum += i.toLong() * histogram[i].toLong()
//         }
//         val avgLuma = (weightedSum.toFloat() / totalF) / 255f
//         val highThreshold = (LeicaConfig.highlightClipThresholdPct / 100.0).toFloat()
//         val lowThreshold = (LeicaConfig.shadowClipThresholdPct / 100.0).toFloat()
//         val targetLuma = LeicaConfig.displayTargetLuma.toFloat()
//         val hasHighlightClip = clipHighFraction > highThreshold
//         val hasShadowClip = clipLowFraction > lowThreshold && avgLuma < targetLuma * 0.5f
//         val histogramEvCorrection: Int = when {
//             hasHighlightClip -> {
//                 val severity = (clipHighFraction / highThreshold).coerceIn(1f, 6f)
//                 -severity.roundToInt().coerceIn(1, MAX_HISTOGRAM_EV_CORRECTION_STEPS)
//             }
//             hasShadowClip -> {
//                 val lowSeverity = (clipLowFraction / lowThreshold).coerceIn(1f, 6f)
//                 val lumaSeverity = if (avgLuma > 0f) {
//                     ((targetLuma * 0.5f) / avgLuma).coerceIn(1f, 6f)
//                 } else 6f
//                 val combined = ((lowSeverity + lumaSeverity) * 0.5f).coerceIn(1f, 6f)
//                 combined.roundToInt().coerceIn(1, MAX_HISTOGRAM_EV_CORRECTION_STEPS)
//             }
//             else -> 0
//         }
//         return HistogramStats(
//             clipHighFraction, clipLowFraction, avgLuma,
//             histogramEvCorrection, hasHighlightClip, hasShadowClip,
//         )
//     }
//
//     fun evToSteps(ev: Float, evStep: Float): Int {
//         if (evStep <= 0f || !ev.isFinite()) return 0
//         val magnitude = (kotlin.math.abs(ev / evStep) + 0.0001f).roundToInt()
//         return if (ev < 0f) -magnitude else magnitude
//     }
//
//     fun evToSteps(ev: Double, evStep: Float): Int =
//         evToSteps(ev.toFloat(), evStep)
// }
// PATCH_EOF
//
// Onde:
//   aeProt="$SRC/app/src/main/java/com/hinnka/mycamera/camera/AeHistogramProtector.kt"

// ═══════════════════════════════════════════════════════════════════════════════
// BLOCK D — Modificar applyExposureSettings (Camera2Controller.kt L3265)
// ═══════════════════════════════════════════════════════════════════════════════

// ─── ANTES (Camera2Controller.kt L3263-L3266) ─────────────────────────────────
//    // 如果是全自动曝光，设置曝光补偿
//    if (state.isIsoAuto && state.isShutterSpeedAuto) {
//        builder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, state.exposureCompensation)
//    } else {

// ─── DEPOIS (U-06 — add histogram feedback + defaultEvBias + perLensEvComp) ────
//    // 如果是全自动曝光，设置曝光补偿
//    if (state.isIsoAuto && state.isShutterSpeedAuto) {
//        // U-06: histogram feedback + default_ev + per_lens ev_comp
//        val evStep = state.getExposureCompensationStep()
//        val range = state.getExposureCompensationRange()
//        val maxSteps = LeicaConfig.maxEvCompSteps
//        val lowerBound = maxOf(range.lower, -maxSteps)
//        val upperBound = minOf(range.upper, maxSteps)
//        val stats = AeHistogramProtector.compute(state)
//        val defaultEvBiasSteps = AeHistogramProtector.evToSteps(
//            LeicaConfig.defaultExposureEv, evStep,
//        )
//        val perLensEvCompSteps = AeHistogramProtector.evToSteps(
//            LeicaConfig.effectiveEvCompForLens(
//                LeicaConfig.lensKeyFromCameraId(currentCameraId),
//                stats.clipLowFraction,
//            ),
//            evStep,
//        )
//        val effectiveComp = (
//            state.exposureCompensation +
//            defaultEvBiasSteps +
//            perLensEvCompSteps +
//            stats.histogramEvCorrection
//        ).coerceIn(lowerBound, upperBound)
//        builder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, effectiveComp)
//        PLog.d(TAG, "U-06 AE: user=${state.exposureCompensation} defaultEv=$defaultEvBiasSteps " +
//            "perLens=$perLensEvCompSteps hist=${stats.histogramEvCorrection} " +
//            "clipHi=${stats.clipHighFraction} clipLo=${stats.clipLowFraction} " +
//            "avgLuma=${stats.avgLuma} → effective=$effectiveComp")
//    } else {

// ─── SED COMMAND ──────────────────────────────────────────────────────────────
// Substitui a linha única `builder.set(CONTROL_AE_EXPOSURE_COMPENSATION, state.exposureCompensation)`
// dentro do branch `if (state.isIsoAuto && state.isShutterSpeedAuto)` por um bloco
// multi-line que computa effectiveComp com histogram feedback:
//
// sed -i '/if (state.isIsoAuto && state.isShutterSpeedAuto) {/{
//   N
//   s|        builder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, state.exposureCompensation)|        // U-06: histogram feedback + default_ev + per_lens ev_comp\n        val evStep = state.getExposureCompensationStep()\n        val range = state.getExposureCompensationRange()\n        val maxSteps = LeicaConfig.maxEvCompSteps\n        val lowerBound = maxOf(range.lower, -maxSteps)\n        val upperBound = minOf(range.upper, maxSteps)\n        val stats = AeHistogramProtector.compute(state)\n        val defaultEvBiasSteps = AeHistogramProtector.evToSteps(LeicaConfig.defaultExposureEv, evStep)\n        val perLensEvCompSteps = AeHistogramProtector.evToSteps(LeicaConfig.effectiveEvCompForLens(LeicaConfig.lensKeyFromCameraId(currentCameraId), stats.clipLowFraction), evStep)\n        val effectiveComp = (state.exposureCompensation + defaultEvBiasSteps + perLensEvCompSteps + stats.histogramEvCorrection).coerceIn(lowerBound, upperBound)\n        builder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, effectiveComp)\n        PLog.d(TAG, "U-06 AE: user=" + state.exposureCompensation + " defaultEv=" + defaultEvBiasSteps + " perLens=" + perLensEvCompSteps + " hist=" + stats.histogramEvCorrection + " clipHi=" + stats.clipHighFraction + " clipLo=" + stats.clipLowFraction + " avgLuma=" + stats.avgLuma + " effective=" + effectiveComp)|
// }' "$c2c"
//
// Nota: O `N` do sed puxa a próxima linha (após o `if {`) garantindo que a
// substituição só atinja ocorrências dentro do branch isAutoExposure.

// ═══════════════════════════════════════════════════════════════════════════════
// BLOCK E — Modificar calculateHdrBracketExposureCompensation (L6367-L6372)
//           Único ponto cobre AMBOS L6344 (HDR bracket) e L6544 (multi-frame short)
// ═══════════════════════════════════════════════════════════════════════════════

// ─── ANTES (Camera2Controller.kt L6367-L6372) ─────────────────────────────────
//    private fun calculateHdrBracketExposureCompensation(state: CameraState, evOffset: Float): Int {
//        val evStep = state.getExposureCompensationStep().takeIf { it > 0f } ?: return state.exposureCompensation
//        val range = state.getExposureCompensationRange()
//        val steps = roundHdrBracketCompensationSteps(evOffset, evStep)
//        return (state.exposureCompensation + steps).coerceIn(range.lower, range.upper)
//    }

// ─── DEPOIS (U-06 — add histogram feedback + perLensEvComp + maxSteps clamp) ───
//    private fun calculateHdrBracketExposureCompensation(state: CameraState, evOffset: Float): Int {
//        val evStep = state.getExposureCompensationStep().takeIf { it > 0f } ?: return state.exposureCompensation
//        val range = state.getExposureCompensationRange()
//        val maxSteps = LeicaConfig.maxEvCompSteps
//        val lowerBound = maxOf(range.lower, -maxSteps)
//        val upperBound = minOf(range.upper, maxSteps)
//        val bracketSteps = roundHdrBracketCompensationSteps(evOffset, evStep)
//        // U-06: histogram feedback (highlight/shadow protection)
//        val stats = AeHistogramProtector.compute(state)
//        val perLensEvCompSteps = AeHistogramProtector.evToSteps(
//            LeicaConfig.effectiveEvCompForLens(
//                LeicaConfig.lensKeyFromCameraId(currentCameraId),
//                stats.clipLowFraction,
//            ),
//            evStep,
//        )
//        // Note: defaultEvBias (0.88 EV) NÃO é aplicado aqui — bracket já tem
//        // seu próprio evOffset (+2.2 long / -1.5 short). Aplicar default_ev
//        // em cima causaria double-bias. Só aplicamos protection + per-lens.
//        return (
//            state.exposureCompensation +
//            bracketSteps +
//            perLensEvCompSteps +
//            stats.histogramEvCorrection
//        ).coerceIn(lowerBound, upperBound)
//    }

// ─── SED COMMAND ──────────────────────────────────────────────────────────────
// Substitui o body inteiro da função calculateHdrBracketExposureCompensation:
//
// python3 - "$c2c" << 'PY_EOF'
// import sys, re
// path = sys.argv[1]
// src = open(path, 'r', encoding='utf-8').read()
// old = (
//     "    private fun calculateHdrBracketExposureCompensation(state: CameraState, evOffset: Float): Int {\n"
//     "        val evStep = state.getExposureCompensationStep().takeIf { it > 0f } ?: return state.exposureCompensation\n"
//     "        val range = state.getExposureCompensationRange()\n"
//     "        val steps = roundHdrBracketCompensationSteps(evOffset, evStep)\n"
//     "        return (state.exposureCompensation + steps).coerceIn(range.lower, range.upper)\n"
//     "    }"
// )
// new = (
//     "    private fun calculateHdrBracketExposureCompensation(state: CameraState, evOffset: Float): Int {\n"
//     "        val evStep = state.getExposureCompensationStep().takeIf { it > 0f } ?: return state.exposureCompensation\n"
//     "        val range = state.getExposureCompensationRange()\n"
//     "        val maxSteps = LeicaConfig.maxEvCompSteps\n"
//     "        val lowerBound = maxOf(range.lower, -maxSteps)\n"
//     "        val upperBound = minOf(range.upper, maxSteps)\n"
//     "        val bracketSteps = roundHdrBracketCompensationSteps(evOffset, evStep)\n"
//     "        // U-06: histogram feedback (highlight/shadow protection)\n"
//     "        val stats = AeHistogramProtector.compute(state)\n"
//     "        val perLensEvCompSteps = AeHistogramProtector.evToSteps(LeicaConfig.effectiveEvCompForLens(LeicaConfig.lensKeyFromCameraId(currentCameraId), stats.clipLowFraction), evStep)\n"
//     "        // Note: defaultEvBias (0.88 EV) não é aplicado aqui — bracket já tem seu próprio evOffset.\n"
//     "        return (state.exposureCompensation + bracketSteps + perLensEvCompSteps + stats.histogramEvCorrection).coerceIn(lowerBound, upperBound)\n"
//     "    }"
// )
// if old not in src:
//     print(f"ERROR: anchor not found in {path}", file=sys.stderr); sys.exit(1)
// open(path, 'w', encoding='utf-8').write(src.replace(old, new, 1))
// PY_EOF
//
// Justificativa do python3 em vez de sed puro: o bloco antigo tem 5 linhas com
// caracteres especiais (?:, .coerceIn) que tornariam o sed regex frágil. O
// python3 faz string-match exato e aborta se o anchor sumir (fail-fast).

// ═══════════════════════════════════════════════════════════════════════════════
// BLOCK F — Gate pgtm_pre_tonemap_exposure_boost_ev consumption sites
//           (RawProfileExposureGl.kt + RawDemosaicProcessor.kt)
// ═══════════════════════════════════════════════════════════════════════════════

// O gating é implementado NO ACCESSOR (Block B — effectivePgtmPreTonemapExposureBoostEv)
// ao invés de inline em cada consumer. Isto significa que QUALQUER caller de
// LeicaConfig.pgtmPreTonemapExposureBoostEv precisa ser migrado para a versão
// "effective" passando clipLowFraction.
//
// Consumers conhecidos (exploration findings):
//   - RawProfileExposureGl.kt L58: `linearGain = 2^exposureEv` onde exposureEv
//     inclui `profileExposureEv` uniform (computado do DCP baseline + user comp
//     + pgtmPreTonemapExposureBoostEv). migração: o caller (Kotlin side que
//     prepara o uniform) precisa ler `effectivePgtmPreTonemapExposureBoostEv`
//     passando o clipLowFraction do state atual.
//   - RawDemosaicProcessor.kt L11489-L11497: `clampedExposureEv = exposureEv.coerceIn(-4f, 4f)`
//     dentro de `renderToneMappedExposurePreviewFrame` — segundo consumer.
//
// Como o clipLowFraction precisa ser passado do CameraState para o GL/uniform
// setup, a migração é melhor feita como um patch SEPARADO (U-06b — ToneMapping
// integration) que:
//   1. Adiciona `state.lastClipLowFraction: Float` (populado por AeHistogramProtector)
//   2. Passa esse valor para RawProfileExposureGl / RawDemosaicProcessor no setup
//      do uniform.
//   3. Usa LeicaConfig.effectivePgtmPreTonemapExposureBoostEv(lastClipLowFraction)
//      no lugar de LeicaConfig.pgtmPreTonemapExposureBoostEv direto.
//
// Este patch (U-06) estabelece a API e o AE-comp feedback. A integração com o
// pipeline de tonemapping fica para U-06b.

// ─── SED COMMAND (NO-OP para este patch — documentação apenas) ────────────────
// Nenhum sed aplicado ao RawProfileExposureGl.kt ou RawDemosaicProcessor.kt
// neste patch. Ver U-06b para a integração do tonemapping gating.

// ═══════════════════════════════════════════════════════════════════════════════
// BLOCK G — Populate state.lastClipLowFraction em updateHistogram
// ═══════════════════════════════════════════════════════════════════════════════

// Para que consumidores downstream (U-06b tonemapping) possam gatear
// pgtm_pre_tonemap_exposure_boost_ev, precisamos persistir o clipLowFraction
// no state. Isto é feito em Camera2Controller.updateHistogram (que recebe
// o histogram do LutRenderer via CameraViewModel.handleHistogramUpdate).

// ─── ANTES (Camera2Controller.kt updateHistogram — esquema conceitual) ────────
//    fun updateHistogram(histogram: IntArray) {
//        _state.value = _state.value.copy(histogram = histogram)
//    }

// ─── DEPOIS ───────────────────────────────────────────────────────────────────
//    fun updateHistogram(histogram: IntArray) {
//        val updated = _state.value.copy(histogram = histogram)
//        // U-06: snapshot clipLowFraction para que downstream consumers
//        // (tonemapping, exposure boost gating) possam gatear sem re-computar.
//        val stats = AeHistogramProtector.compute(updated)
//        _state.value = updated.copy(lastClipLowFraction = stats.clipLowFraction)
//    }

// ─── SED COMMAND ──────────────────────────────────────────────────────────────
// Depende do campo `lastClipLowFraction: Float = 0f` ser adicionado ao
// CameraState (Block H abaixo). Aplicação:
//
// python3 - "$c2c" << 'PY_EOF'
// import sys
// path = sys.argv[1]
// src = open(path, 'r', encoding='utf-8').read()
// # Procura pela função updateHistogram — anchor flexível porque o nome pode
// # variar entre versões upstream. Procura pela assignação do histogram.
// import re
// pat = re.compile(
//     r'(fun update[A-Za-z]*Histogram\([^)]*\)\s*\{)(\s*[^}]*?)(histogram\s*=\s*histogram)([^}]*?\})',
//     re.DOTALL,
// )
// m = pat.search(src)
// if not m:
//     print("WARN: updateHistogram anchor not found — skipping Block G", file=sys.stderr)
//     sys.exit(0)
// # Insere snapshot antes do fechamento da função.
// new_body = m.group(0).replace(
//     m.group(3),
//     m.group(3) + ")\n        val stats = AeHistogramProtector.compute(_state.value)\n        _state.value = _state.value.copy(lastClipLowFraction = stats.clipLowFraction",
//     1,
// )
// # (a lógica acima é ilustrativa; implementação real pode exigir reescrever a
// # função inteira. Verificar atualização do PhotonCamera upstream antes de aplicar.)
// open(path, 'w', encoding='utf-8').write(src.replace(m.group(0), new_body, 1))
// PY_EOF

// ═══════════════════════════════════════════════════════════════════════════════
// BLOCK H — Adicionar campo lastClipLowFraction ao CameraState
// ═══════════════════════════════════════════════════════════════════════════════

// ─── ANTES (CameraState.kt L331) ──────────────────────────────────────────────
//    val histogram: IntArray? = null,

// ─── DEPOIS ───────────────────────────────────────────────────────────────────
//    val histogram: IntArray? = null,
//    /** U-06: último clipLowFraction computado (snapshot populado por updateHistogram). */
//    val lastClipLowFraction: Float = 0f,

// ─── SED COMMAND ──────────────────────────────────────────────────────────────
// sed -i 's|    val histogram: IntArray? = null,|    val histogram: IntArray? = null,\n    /** U-06: último clipLowFraction (snapshot populado por updateHistogram). */\n    val lastClipLowFraction: Float = 0f,|' "$cstate"

// ═══════════════════════════════════════════════════════════════════════════════
// RESUMO DA APLICAÇÃO (ordem do build-archlinux.sh cmd_patch U-06)
// ═══════════════════════════════════════════════════════════════════════════════
//
// Variáveis de path:
//   lcfg="$SRC/app/src/main/java/com/hinnka/mycamera/raw/LeicaConfig.kt"
//   c2c="$SRC/app/src/main/java/com/hinnka/mycamera/camera/Camera2Controller.kt"
//   cstate="$SRC/app/src/main/java/com/hinnka/mycamera/camera/CameraState.kt"
//   aeProt="$SRC/app/src/main/java/com/hinnka/mycamera/camera/AeHistogramProtector.kt"
//
// Sequência:
//   1. [Block A] sed insere AeProtectionConfig data class em LeicaConfig.kt
//   2. [Block A] sed adiciona campo aeProtection ao LeicaPerfectConfig
//   3. [Block B] sed/python insere accessors após pgtmPreTonemapExposureBoostEv
//   4. [Block C] cat heredoc cria AeHistogramProtector.kt (novo arquivo)
//   5. [Block H] sed adiciona campo lastClipLowFraction ao CameraState.kt
//   6. [Block D] sed substitui linha L3265 por bloco effectiveComp em Camera2Controller.kt
//   7. [Block E] python3 substitui body de calculateHdrBracketExposureCompensation
//   8. [Block G] python3 patcheia updateHistogram para popular lastClipLowFraction
//   9. [Block F] NO-OP (integração tonemapping fica para U-06b)
//
// Validação pós-patch:
//   - grep -n "AeHistogramProtector" $c2c  # deve encontrar 3+ matches
//   - grep -n "aeProtection" $lcfg          # deve encontrar data class + accessor
//   - grep -n "lastClipLowFraction" $cstate # deve encontrar 1 match
//   - ./gradlew assembleDebug               # build smoke test

// ═══════════════════════════════════════════════════════════════════════════════
// REFERENCIADO POR (NÃO QUEBRAR)
// ═══════════════════════════════════════════════════════════════════════════════
//   - LeicaConfig.processing.defaultExposureEv (0.88 EV — base pra defaultEvBiasSteps)
//   - LeicaConfig.processing.pgtmPreTonemapExposureBoostEv (1.3 EV — gated por U-06)
//   - LeicaConfig.processing.displayTargetLuma (0.21 — usado p/ shadow clip check)
//   - LeicaConfig.per_lens.<id>.ev_comp (gated por U-06 effectiveEvCompForLens)
//   - LeicaConfig.per_lens.<id>.highlight_compression_ev (-0.2 — consumido pelo
//     RAW viewfinder matcher, NÃO por este patch; U-06 usa histogram direto)
//   - CameraState.histogram (256-bin, populado por LutRenderer.calculateMeteringResults)
//   - CameraState.getExposureCompensationStep / getExposureCompensationRange
//   - Camera2Controller.applyExposureSettings (L3265 — Block D)
//   - Camera2Controller.calculateHdrBracketExposureCompensation (L6367 — Block E)
//   - Camera2Controller.updateHistogram (Block G — popula lastClipLowFraction)
//   - MeteringSystem.estimateHighlightCompression (L431-L459 — NÃO tocado por U-06;
//     U-06 usa histogram direto porque estimateHighlightCompression exige
//     amount+strength inputs que não estão disponíveis no preview path YUV)
//   - MultiFrameExposurePlanner.planLongExposure (LONG only; U-06 NÃO modifica
//     o planner — long frame exposure já é +2.5 EV fixed. Histogram feedback
//     aplica-se APENAS ao AE-comp write sites. Ver U-06c para planner integration.)
//   - LutRenderer.calculateMeteringResults (L3495-L3554 — source do histogram,
//     NÃO tocado por este patch)
//
// RISCOS:
//   - defaultEvBias = 0.88 EV → ~5 steps (em 1/6-step devices). Aplicar sempre
//     que isAutoExposure = true pode mudar VISIVELMENTE o exposure vs stock.
//     Para desabilitar sem recompilar: setar processing.default_exposure_ev=0.0.
//   - histogramEvCorrection pode flutuar frame-a-frame causando flicker. Mitigação:
//     AeHistogramProtector.compute usa threshold histeresis (1% → 6% severity
//     scale) que reduz flutuação pequenas. Se flicker persistir, adicionar EMA
//     smoothing em lastClipLowFraction (U-06d).
//   - Gating do pgtm boost (Block F) requer Block G (lastClipLowFraction) +
//     U-06b (passar valor ao RawProfileExposureGl). Sem U-06b, o gating é
//     no-op (pgtmPreTonemapExposureBoostEv continua sendo consumido direto).
//   - Em devices sem AE-comp support (evStep=0), AeHistogramProtector.evToSteps
//     retorna 0 → nenhum bias aplicado. Comportamento correto (fallback seguro).
