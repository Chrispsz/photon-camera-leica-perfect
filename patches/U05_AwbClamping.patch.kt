// ═══════════════════════════════════════════════════════════════════════════════
// U05_AwbClamping.patch.kt — U-05 (v6.5) — M9-style AWB CCT clamping for default mode
// ═══════════════════════════════════════════════════════════════════════════════
//
// ⚠️  DOCUMENTATION-ONLY — Este arquivo NÃO é compilado. Mostra o que os seds em
//     build-archlinux.sh cmd_patch() U-05 injetam no upstream PhotonCamera.
//
// ⭐⭐ PATCH STATUS: REAL — actually effective at runtime (não é no-op). ⭐⭐
//     Fecha o HARD-LIMIT H8 "custom AWB" do DEFINITIVE_AUDIT_v6.2.md.
//
// O QUE FAZ:
//   1. Adiciona `WhiteBalanceControlPath.CLAMPED_AUTO` enum value ao
//      Camera2Controller (entre MATRIX e UNAVAILABLE).
//   2. Adiciona state field `clampedAwbSmoothedKelvin: Int?` (EMA-smoothed CCT)
//      ao lado de `lastWhiteBalanceResult`, com reset nos 2 spots de camera
//      close/reopen (L1287 e L1707).
//   3. Adiciona parâmetro `bypassClamp: Boolean = false` à função
//      `applyAutoWhiteBalanceSettings` (backward-compatible default).
//   4. Injeta GATE no topo do body de `applyAutoWhiteBalanceSettings`: quando
//      `LeicaConfig.awbClampEnabled && state.awbMode == AUTO &&
//       supportsManualMatrixWhiteBalance()`, chama nova função
//      `applyClampedAutoWhiteBalanceSettings` e return cedo.
//   5. Adiciona nova função `applyClampedAutoWhiteBalanceSettings` que:
//        (a) Lê `lastWhiteBalanceResult` (snapshot HAL do frame anterior).
//        (b) Extrai CCT: prefer `COLOR_CORRECTION_COLOR_TEMPERATURE` (API36+),
//            fallback `estimateKelvinFromRggbGains(gains)` (brute-force 2000-8000K).
//        (c) EMA smoothing: smoothed = (1-α)*prev + α*raw, arredondado.
//            α = LeicaConfig.awbClampSmoothingAlpha (default 0.15).
//            Persiste no field `clampedAwbSmoothedKelvin` (cross-frame state).
//        (d) Clamp: smoothed.coerceIn(min_cct, max_cct) — default [3500, 7000].
//        (e) Converte CCT clamped de volta pra RGGB gains via `kelvinToRggbGains`
//            (Tanner-Helland blackbody, mesmo path do manual CCT).
//        (f) Deriva CCM transform via `buildColorMatrixWhiteBalanceTransform`
//            (usa SENSOR_COLOR_TRANSFORM1/2 + gains).
//        (g) Aplica MANUAL: CONTROL_AWB_MODE=OFF, COLOR_CORRECTION_MODE=
//            TRANSFORM_MATRIX, COLOR_CORRECTION_GAINS=clampedGains,
//            COLOR_CORRECTION_TRANSFORM=transform.
//        (h) Fallback graceful: se snapshot null, gains null, OU transform null,
//            chama `applyAutoWhiteBalanceSettings(bypassClamp=true)` (pure AUTO).
//   6. Adiciona `AwbClampConfig` data class ao LeicaConfig.kt (espelha seção
//      `awb_clamp` do leica_perfect.json).
//   7. Adiciona 4 accessors ao LeicaConfig.kt: awbClampEnabled / awbClampMinCct /
//      awbClampMaxCct / awbClampSmoothingAlpha.
//
// POR QUE:
//   - Definitive audit v6.2 listou HARD-LIMIT H8: "custom AWB" — PhotonCamera
//     delegates entirely to Camera2 HAL via CONTROL_AWB_MODE_AUTO, com ZERO clamp
//     no CCT escolhido. Per-frame WB oscillates (especialmente em mixed lighting).
//   - O perfil M9 CCD dá a impressão de "AWB estável" — mas é PERCEPTUAL masking
//     via LUT amber/crushed-blacks grade, NÃO clamp matemático. (Confirmado em
//     explore-U-05-awb §4.)
//   - U-05 traz REAL stabilization: clamp o CCT do HAL no range [3500, 7000]K
//     (cobre D65 6500K, daylight 5000-5500K, warm LED 3500K, sem permitir
//     tungsten 2700K extremes que causam amber cast perceptível).
//   - EMA smoothing (α=0.15) dampens per-frame oscillation sem lag perceptível
//     (time-constant ~6 frames = 200ms a 30fps).
//   - Aplica CLAMPED_AUTO a todos os paths (preview, capture YUV, RAW metadata):
//     • YUV/JPEG: HAL aplica COLOR_CORRECTION_GAINS internamente → preview e
//       capture recebem gains clamped estabilizados.
//     • RAW: gains registrados no metadata → RawDemosaicProcessor uploads pra
//       uWhiteBalanceGains shader uniform → RAW também estabiliza.
//   - Templates reutilizados do codebase:
//       • HncsProfile.kt L263-270 (temperature.coerceIn MIN/MAX) — clamp pattern.
//       • applyMatrixWhiteBalanceSettings L3538-3567 — MANUAL TRANSFORM_MATRIX path.
//       • buildColorMatrixWhiteBalanceTransform L3569-3598 — CCM derivation.
//       • kelvinToRggbGains L4740-4753 — blackbody → RGGB gains.
//       • estimateKelvinFromRggbGains L4782-4808 — brute-force CCT inverse.
//       • readWhiteBalanceResult L3018-3036 — HAL CCT/Tint/Gains readback.
//
// ARQUIVOS TARGET:
//   - app/src/main/java/com/hinnka/mycamera/camera/Camera2Controller.kt (7 seds)
//   - patches/LeicaConfig.kt (3 seds — injetados no mesmo build step que os
//     accessors existentes, sem quebrar patches P-30..P-57)
//
// ÂNCORAS SED (Camera2Controller.kt):
//   1. `^import com.hinnka.mycamera.raw.ColorSpace as RawColorSpace$` (import inj)
//   2. `^        MATRIX,$` (enum value insert — único no arquivo)
//   3. `^    private var manualWhiteBalanceAnchor: ManualWhiteBalanceAnchor? = null$` (state field)
//   4. `^        lastWhiteBalanceResult = null$` (close reset, 8-space indent)
//   5. `^                lastWhiteBalanceResult = null$` (reopen reset, 16-space indent)
//   6. `^    private fun applyAutoWhiteBalanceSettings($` (signature + 4 N slurps)
//   7. `^        val requestedMode = state.awbMode$` (gate insert before body)
//   8. `^    private fun applyCctWhiteBalanceSettings($` (new helper insert before)
//
// ÂNCORAS SED (LeicaConfig.kt):
//   1. `^        @SerializedName("creative_profiles") val creativeProfiles: CreativeProfileConfig? = null,$` (field add)
//   2. `^    data class VideoConfig($` (AwbClampConfig data class insert before)
//   3. `^    val perChannelTintBlue: Int get() = currentConfig?.colorScience?.perChannelTintBlue ?: 0$` (accessors append)
//
// LeicaConfig ACCESSORS USADOS:
//   - LeicaConfig.awbClampEnabled: Boolean
//   - LeicaConfig.awbClampMinCct: Int
//   - LeicaConfig.awbClampMaxCct: Int
//   - LeicaConfig.awbClampSmoothingAlpha: Float
//
// SED DELIMITER GOTCHA:
//   - Replacement do gate contém `&&` (Kotlin logical AND).
//   - Se usarmos `&` como sed delimiter, `&&` seria interpretado como
//     backreference + literal. PREMATURE TERMINATION.
//   - SOLUÇÃO: usar `|` como delimiter (não conflita com `&&`).
//   - Mesma lição que PerLensAgxConsumer.patch.kt (P-43) documentou pra `||`.
//
// RECURSION SAFETY:
//   - `applyAutoWhiteBalanceSettings` tem gate `if (!bypassClamp && ...)`.
//   - Quando gate dispara, chama `applyClampedAutoWhiteBalanceSettings` e return.
//   - `applyClampedAutoWhiteBalanceSettings` faz fallback chamando
//     `applyAutoWhiteBalanceSettings(bypassClamp=true)` — bypassClamp=true
//     suprime o gate, evitando recursão infinita.
//   - Quando user escolhe AWB preset (INCADESCENT/DAYLIGHT/CLOUDY/etc.), o
//     `state.awbMode != AUTO`, gate não dispara, HAL preset é respeitado.
//
// RACE CONDITION NOTE:
//   - `clampedAwbSmoothedKelvin` é `private var` mutável, mutado apenas dentro
//     de `applyClampedAutoWhiteBalanceSettings` (chamado no CaptureRequest
//     builder thread). Camera2 spec garante que CaptureRequest builders são
//     serialized por camera device. SAFE.
//   - Resetado para null em camera close (L1287) e reopen (L1707) — primeira
//     frame após open inicializa EMA com raw HAL CCT (sem smoothing).
//   - `lastWhiteBalanceResult` é atualizado no CaptureResult handler (L755),
//     que roda no mesmo callback thread. Cross-frame read-after-write é SAFE.
//
// LIMITAÇÕES:
//   - Não ativa clamp em AWB presets (INCADESCENT/DAYLIGHT/etc.) — apenas AUTO.
//     Justificativa: presets são user-explicit choice, clamp seria override.
//   - Não ativa clamp em modo OFF (manual CCT/MATRIX path já tem clamp próprio
//     via `coerceCctAwbTemperature` L2984 + `kelvinToRggbGains` clamp 1-4).
//   - Requer `supportsManualMatrixWhiteBalance()` (TRANSFORM_MATRIX mode). Em
//     devices que não suportam, clamp é SKIP e HAL AUTO é usado (degraded mas
//     functional).
//   - EMA usa α único (sem scene-adaptive). Future v7.0: α dinâmico baseado em
//     |raw - prev| (disable smoothing em scene changes abruptos).
//   - Clamp range fixo [3500, 7000]. Não lê DCP calibrationIlluminant1/2 para
//     derivar range adaptativo. Future v7.0: integração com DcpProfile.
//   - RGB→CCT inverse usa `estimateKelvinFromRggbGains` (brute-force 2000-8000K
//     step 50K) — display-only origin. Para clamp, precisão de 50K é suficiente
//     (menor que perceptual JND de ~100K em daylight). Performance OK: 121
//     iterações por frame, < 1ms.
// ═══════════════════════════════════════════════════════════════════════════════

package com.hinnka.mycamera.camera

import com.hinnka.mycamera.raw.LeicaConfig

// ═══════════════════════════════════════════════════════════════════════════════
// PARTE 1 — Camera2Controller.kt
// ═══════════════════════════════════════════════════════════════════════════════

// ─── ANTES (upstream original) ───────────────────────────────────────────────

// (L30 imports)
import com.hinnka.mycamera.raw.ColorSpace as RawColorSpace
import com.hinnka.mycamera.raw.DngSdkColorSpec

// (L189-193 enum)
private enum class WhiteBalanceControlPath {
    CCT,
    MATRIX,
    UNAVAILABLE
}

// (L289-290 state fields)
private var lastWhiteBalanceResult: WhiteBalanceResultSnapshot? = null
private var manualWhiteBalanceAnchor: ManualWhiteBalanceAnchor? = null

// (L1287-1288 close reset)
        lastWhiteBalanceResult = null
        manualWhiteBalanceAnchor = null

// (L1707 reopen reset)
                lastWhiteBalanceResult = null

// (L3488-3511 applyAutoWhiteBalanceSettings)
    private fun applyAutoWhiteBalanceSettings(
        builder: CaptureRequest.Builder,
        state: CameraState,
        isCapture: Boolean
    ) {
        val requestedMode = state.awbMode
        val awbMode = if (availableAwbModes.isEmpty() || requestedMode in availableAwbModes) {
            requestedMode
        } else {
            CameraMetadata.CONTROL_AWB_MODE_AUTO
        }
        builder.set(CaptureRequest.CONTROL_AWB_MODE, awbMode)
        builder.set(CaptureRequest.CONTROL_AWB_LOCK, false)

        // 自动白平衡：拍照优先高质量色彩校正，预览维持快速路径
        if (isManualPostProcessingSupported) {
            val colorCorrectionMode = if (isCapture && state.captureMode == CaptureMode.PHOTO) {
                CaptureRequest.COLOR_CORRECTION_MODE_HIGH_QUALITY
            } else {
                CaptureRequest.COLOR_CORRECTION_MODE_FAST
            }
            builder.set(CaptureRequest.COLOR_CORRECTION_MODE, colorCorrectionMode)
        }
    }

// (L3513 start of applyCctWhiteBalanceSettings — NO helper before it)
    private fun applyCctWhiteBalanceSettings(

// ─── DEPOIS (após U-05 — 8 seds no Camera2Controller.kt) ─────────────────────

// (L30 imports — LeicaConfig added grep-guarded)
import com.hinnka.mycamera.raw.ColorSpace as RawColorSpace
import com.hinnka.mycamera.raw.DngSdkColorSpec
import com.hinnka.mycamera.raw.LeicaConfig  // U-05

// (L189-194 enum — CLAMPED_AUTO added)
private enum class WhiteBalanceControlPath {
    CCT,
    MATRIX,
        CLAMPED_AUTO,   // U-05: AUTO routed through MANUAL MATRIX with clamped CCT
    UNAVAILABLE
}

// (L289-291 state fields — clampedAwbSmoothedKelvin added)
private var lastWhiteBalanceResult: WhiteBalanceResultSnapshot? = null
private var manualWhiteBalanceAnchor: ManualWhiteBalanceAnchor? = null
    private var clampedAwbSmoothedKelvin: Int? = null  // U-05: EMA-smoothed CCT for CLAMPED_AUTO path

// (L1287-1289 close reset — clampedAwbSmoothedKelvin reset added)
        lastWhiteBalanceResult = null
        clampedAwbSmoothedKelvin = null  // U-05: reset EMA state on camera close
        manualWhiteBalanceAnchor = null

// (L1707-1708 reopen reset — clampedAwbSmoothedKelvin reset added)
                lastWhiteBalanceResult = null
                clampedAwbSmoothedKelvin = null  // U-05: reset EMA state on camera reopen

// (L3488-3520 applyAutoWhiteBalanceSettings — gate + bypassClamp param)
    private fun applyAutoWhiteBalanceSettings(
        builder: CaptureRequest.Builder,
        state: CameraState,
        isCapture: Boolean,
        bypassClamp: Boolean = false  // U-05: when true, skip AWB clamp gate (fallback path)
    ) {
        // U-05 AWB clamp: when enabled and MANUAL MATRIX supported and user picked AUTO,
        // route to clamped MANUAL TRANSFORM_MATRIX path (M9-style stabilization).
        if (!bypassClamp && LeicaConfig.awbClampEnabled &&
            state.awbMode == CameraMetadata.CONTROL_AWB_MODE_AUTO &&
            supportsManualMatrixWhiteBalance()) {
            applyClampedAutoWhiteBalanceSettings(builder, state, isCapture)
            return
        }
        val requestedMode = state.awbMode
        val awbMode = if (availableAwbModes.isEmpty() || requestedMode in availableAwbModes) {
            requestedMode
        } else {
            CameraMetadata.CONTROL_AWB_MODE_AUTO
        }
        builder.set(CaptureRequest.CONTROL_AWB_MODE, awbMode)
        builder.set(CaptureRequest.CONTROL_AWB_LOCK, false)

        // 自动白平衡：拍照优先高质量色彩校正，预览维持快速路径
        if (isManualPostProcessingSupported) {
            val colorCorrectionMode = if (isCapture && state.captureMode == CaptureMode.PHOTO) {
                CaptureRequest.COLOR_CORRECTION_MODE_HIGH_QUALITY
            } else {
                CaptureRequest.COLOR_CORRECTION_MODE_FAST
            }
            builder.set(CaptureRequest.COLOR_CORRECTION_MODE, colorCorrectionMode)
        }
    }

    /**
     * U-05 AWB clamp — route AUTO-mode requests through MANUAL TRANSFORM_MATRIX path
     * when awb_clamp.enabled=true. Reads HAL's per-frame CCT from lastWhiteBalanceResult,
     * applies EMA smoothing (alpha=awbClampSmoothingAlpha), clamps to [min_cct, max_cct],
     * and re-applies as MANUAL gains. Stabilizes per-frame AWB oscillation (M9-style).
     *
     * Fallback: when snapshot null, gains null, OR CCM transform null, calls
     * applyAutoWhiteBalanceSettings(bypassClamp=true) for pure AUTO (no recursion).
     *
     * @param builder CaptureRequest builder (preview or still capture)
     * @param state   Current CameraState (awbMode must be AUTO when this is called)
     * @param isCapture true for still-capture requests, false for preview/repeated
     */
    private fun applyClampedAutoWhiteBalanceSettings(
        builder: CaptureRequest.Builder,
        state: CameraState,
        isCapture: Boolean
    ) {
        val snapshot = lastWhiteBalanceResult
        // First frame or HAL not reporting WB yet: fall back to pure AUTO
        if (snapshot == null ||
            (snapshot.colorTemperature == null && snapshot.gains == null)) {
            applyAutoWhiteBalanceSettings(
                builder = builder,
                state = state.copy(awbMode = CameraMetadata.CONTROL_AWB_MODE_AUTO),
                isCapture = isCapture,
                bypassClamp = true
            )
            return
        }
        // Derive raw CCT from HAL: prefer COLOR_CORRECTION_COLOR_TEMPERATURE (API36+),
        // else estimate from gains via brute-force blackbody match (50K resolution).
        val rawKelvin = snapshot.colorTemperature
            ?: snapshot.gains?.let(::estimateKelvinFromRggbGains)
            ?: run {
                applyAutoWhiteBalanceSettings(
                    builder = builder,
                    state = state.copy(awbMode = CameraMetadata.CONTROL_AWB_MODE_AUTO),
                    isCapture = isCapture,
                    bypassClamp = true
                )
                return
            }
        // EMA smoothing across frames (dampen HAL's per-frame oscillation).
        // First frame after open: initialize smoothed state to raw (no smoothing).
        val alpha = LeicaConfig.awbClampSmoothingAlpha.coerceIn(0f, 1f)
        val prev = clampedAwbSmoothedKelvin ?: rawKelvin
        val smoothed = ((prev.toFloat() * (1f - alpha)) +
                        (rawKelvin.toFloat() * alpha)).roundToInt()
        clampedAwbSmoothedKelvin = smoothed
        // Clamp to user-configured range (default 3500-7000K covers daylight D65 +
        // warm LED without permitting extreme tungsten 2700K amber cast).
        val minCct = LeicaConfig.awbClampMinCct.coerceAtMost(LeicaConfig.awbClampMaxCct - 1)
        val maxCct = LeicaConfig.awbClampMaxCct.coerceAtLeast(minCct + 1)
        val clampedKelvin = smoothed.coerceIn(minCct, maxCct)
        // Convert clamped CCT back to RGGB gains via Tanner-Helland blackbody
        // approximation (same path as manual CCT mode).
        val clampedGains = kelvinToRggbGains(clampedKelvin)
        // Derive CCM transform from sensor color matrices + clamped gains.
        // If null (e.g., SENSOR_COLOR_TRANSFORM1/2 missing), fall back to AUTO.
        val transform = buildColorMatrixWhiteBalanceTransform(clampedGains)
        if (transform == null) {
            applyAutoWhiteBalanceSettings(
                builder = builder,
                state = state.copy(awbMode = CameraMetadata.CONTROL_AWB_MODE_AUTO),
                isCapture = isCapture,
                bypassClamp = true
            )
            return
        }
        // Apply as MANUAL TRANSFORM_MATRIX (real stabilization — feeds clamped
        // WB gains back to HAL for both YUV/JPEG processing and RAW metadata).
        builder.set(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_OFF)
        builder.set(CaptureRequest.CONTROL_AWB_LOCK, false)
        builder.set(
            CaptureRequest.COLOR_CORRECTION_MODE,
            CaptureRequest.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX
        )
        builder.set(CaptureRequest.COLOR_CORRECTION_GAINS, clampedGains)
        builder.set(CaptureRequest.COLOR_CORRECTION_TRANSFORM, transform)
    }

    private fun applyCctWhiteBalanceSettings(

// ═══════════════════════════════════════════════════════════════════════════════
// PARTE 2 — LeicaConfig.kt
// ═══════════════════════════════════════════════════════════════════════════════

// ─── ANTES (upstream LeicaConfig.kt — sem awb_clamp) ─────────────────────────

// (L78 LeicaPerfectConfig — campo creative_profiles é o último)
        @SerializedName("creative_profiles") val creativeProfiles: CreativeProfileConfig? = null,
    )

// (L322 sem AwbClampConfig data class — VideoConfig vem direto após ColorScienceConfig)
    /** video — MAX HEVC 250Mbps B-frames=2 log HDR10. */
    data class VideoConfig(

// (L893 accessors — perChannelTintBlue é o último de color_science)
    val perChannelTintBlue: Int get() = currentConfig?.colorScience?.perChannelTintBlue ?: 0

// ─── DEPOIS (após U-05 — 3 seds no LeicaConfig.kt) ───────────────────────────

// (L78 LeicaPerfectConfig — campo awbClamp adicionado antes de creative_profiles)
        @SerializedName("awb_clamp") val awbClamp: AwbClampConfig? = null,
        @SerializedName("creative_profiles") val creativeProfiles: CreativeProfileConfig? = null,
    )

// (L322 AwbClampConfig data class adicionada antes de VideoConfig)
    /** awb_clamp — U-05: AUTO-mode CCT clamping for M9-style stabilization. */
    data class AwbClampConfig(
        @SerializedName("enabled") val enabled: Boolean? = true,
        @SerializedName("min_cct") val minCct: Int? = 3500,
        @SerializedName("max_cct") val maxCct: Int? = 7000,
        @SerializedName("smoothing_alpha") val smoothingAlpha: Float? = 0.15f,
    )

    /** video — MAX HEVC 250Mbps B-frames=2 log HDR10. */
    data class VideoConfig(

// (L893 accessors — 4 novos awbClamp* accessors adicionados após perChannelTintBlue)
    val perChannelTintBlue: Int get() = currentConfig?.colorScience?.perChannelTintBlue ?: 0

    // U-05: AWB CCT clamping (M9-style stabilization for default AUTO mode)
    val awbClampEnabled: Boolean get() = currentConfig?.awbClamp?.enabled ?: true
    val awbClampMinCct: Int get() = currentConfig?.awbClamp?.minCct ?: 3500
    val awbClampMaxCct: Int get() = currentConfig?.awbClamp?.maxCct ?: 7000
    val awbClampSmoothingAlpha: Float get() = currentConfig?.awbClamp?.smoothingAlpha ?: 0.15f

// ═══════════════════════════════════════════════════════════════════════════════
// SED COMMANDS (executados pelo build-archlinux.sh cmd_patch() U-05)
// ═══════════════════════════════════════════════════════════════════════════════
//
// ─── PARTE 1: Camera2Controller.kt ($c2c) ─────────────────────────────────────
//
// 1. Import LeicaConfig (grep-guarded — não duplica se já injetado por outro patch):
//    grep -q '^import com.hinnka.mycamera.raw.LeicaConfig$' "$c2c" || \
//      sed -i '/^import com.hinnka.mycamera.raw.ColorSpace as RawColorSpace$/a import com.hinnka.mycamera.raw.LeicaConfig' "$c2c"
//
// 2. Add WhiteBalanceControlPath.CLAMPED_AUTO enum value (after MATRIX, before UNAVAILABLE):
//    sed -i '/^        MATRIX,$/a\
//\        CLAMPED_AUTO,   // U-05: AUTO routed through MANUAL MATRIX with clamped CCT' "$c2c"
//
// 3. Add clampedAwbSmoothedKelvin state field (after manualWhiteBalanceAnchor):
//    sed -i '/^    private var manualWhiteBalanceAnchor: ManualWhiteBalanceAnchor? = null$/a\
//\    private var clampedAwbSmoothedKelvin: Int? = null  // U-05: EMA-smoothed CCT for CLAMPED_AUTO path' "$c2c"
//
// 4. Reset EMA state on camera close (8-space indent, L1287 anchor):
//    sed -i '/^        lastWhiteBalanceResult = null$/a\
//\        clampedAwbSmoothedKelvin = null  // U-05: reset EMA state on camera close' "$c2c"
//
// 5. Reset EMA state on camera reopen (16-space indent, L1707 anchor):
//    sed -i '/^                lastWhiteBalanceResult = null$/a\
//\                clampedAwbSmoothedKelvin = null  // U-05: reset EMA state on camera reopen' "$c2c"
//
// 6. Add bypassClamp parameter to applyAutoWhiteBalanceSettings (multi-line N+s):
//    sed -i '/^    private fun applyAutoWhiteBalanceSettings($/{N;N;N;N;s|        isCapture: Boolean\n    ) {|        isCapture: Boolean,\n        bypassClamp: Boolean = false  // U-05: when true, skip AWB clamp gate\n    ) {|}' "$c2c"
//
// 7. Inject AWB clamp gate at top of applyAutoWhiteBalanceSettings body (before val requestedMode):
//    sed -i '/^        val requestedMode = state.awbMode$/i\
//\        // U-05 AWB clamp: when enabled and MANUAL MATRIX supported and user picked AUTO,\
//\        // route to clamped MANUAL TRANSFORM_MATRIX path (M9-style stabilization).\
//\        if (!bypassClamp \&\& LeicaConfig.awbClampEnabled \&\&\
//\            state.awbMode == CameraMetadata.CONTROL_AWB_MODE_AUTO \&\&\
//\            supportsManualMatrixWhiteBalance()) {\
//\            applyClampedAutoWhiteBalanceSettings(builder, state, isCapture)\
//\            return\
//\        }' "$c2c"
//
// 8. Insert applyClampedAutoWhiteBalanceSettings helper before applyCctWhiteBalanceSettings:
//    sed -i '/^    private fun applyCctWhiteBalanceSettings($/i\
//\    /**\
//\     * U-05 AWB clamp — route AUTO-mode requests through MANUAL TRANSFORM_MATRIX path\
//\     * when awb_clamp.enabled=true. Reads HAL\'s per-frame CCT from lastWhiteBalanceResult,\
//\     * applies EMA smoothing (alpha=awbClampSmoothingAlpha), clamps to [min_cct, max_cct],\
//\     * and re-applies as MANUAL gains. Stabilizes per-frame AWB oscillation (M9-style).\
//\     *\
//\     * Fallback: when snapshot null, gains null, OR CCM transform null, calls\
//\     * applyAutoWhiteBalanceSettings(bypassClamp=true) for pure AUTO (no recursion).\
//\     */\
//\    private fun applyClampedAutoWhiteBalanceSettings(\
//\        builder: CaptureRequest.Builder,\
//\        state: CameraState,\
//\        isCapture: Boolean\
//\    ) {\
//\        val snapshot = lastWhiteBalanceResult\
//\        if (snapshot == null ||\
//\            (snapshot.colorTemperature == null \&\& snapshot.gains == null)) {\
//\            applyAutoWhiteBalanceSettings(\
//\                builder = builder,\
//\                state = state.copy(awbMode = CameraMetadata.CONTROL_AWB_MODE_AUTO),\
//\                isCapture = isCapture,\
//\                bypassClamp = true\
//\            )\
//\            return\
//\        }\
//\        val rawKelvin = snapshot.colorTemperature\
//\            ?: snapshot.gains?.let(::estimateKelvinFromRggbGains)\
//\            ?: run {\
//\                applyAutoWhiteBalanceSettings(\
//\                    builder = builder,\
//\                    state = state.copy(awbMode = CameraMetadata.CONTROL_AWB_MODE_AUTO),\
//\                    isCapture = isCapture,\
//\                    bypassClamp = true\
//\                )\
//\                return\
//\            }\
//\        val alpha = LeicaConfig.awbClampSmoothingAlpha.coerceIn(0f, 1f)\
//\        val prev = clampedAwbSmoothedKelvin ?: rawKelvin\
//\        val smoothed = ((prev.toFloat() * (1f - alpha)) +\
//\                        (rawKelvin.toFloat() * alpha)).roundToInt()\
//\        clampedAwbSmoothedKelvin = smoothed\
//\        val minCct = LeicaConfig.awbClampMinCct.coerceAtMost(LeicaConfig.awbClampMaxCct - 1)\
//\        val maxCct = LeicaConfig.awbClampMaxCct.coerceAtLeast(minCct + 1)\
//\        val clampedKelvin = smoothed.coerceIn(minCct, maxCct)\
//\        val clampedGains = kelvinToRggbGains(clampedKelvin)\
//\        val transform = buildColorMatrixWhiteBalanceTransform(clampedGains)\
//\        if (transform == null) {\
//\            applyAutoWhiteBalanceSettings(\
//\                builder = builder,\
//\                state = state.copy(awbMode = CameraMetadata.CONTROL_AWB_MODE_AUTO),\
//\                isCapture = isCapture,\
//\                bypassClamp = true\
//\            )\
//\            return\
//\        }\
//\        builder.set(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_OFF)\
//\        builder.set(CaptureRequest.CONTROL_AWB_LOCK, false)\
//\        builder.set(\
//\            CaptureRequest.COLOR_CORRECTION_MODE,\
//\            CaptureRequest.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX\
//\        )\
//\        builder.set(CaptureRequest.COLOR_CORRECTION_GAINS, clampedGains)\
//\        builder.set(CaptureRequest.COLOR_CORRECTION_TRANSFORM, transform)\
//\    }\
//\' "$c2c"
//
// ─── PARTE 2: LeicaConfig.kt ($lcfg) ──────────────────────────────────────────
//
// 9. Add awbClamp field to LeicaPerfectConfig (before creative_profiles):
//    sed -i '/^        @SerializedName("creative_profiles") val creativeProfiles: CreativeProfileConfig? = null,$/i\
//\        @SerializedName("awb_clamp") val awbClamp: AwbClampConfig? = null,' "$lcfg"
//
// 10. Add AwbClampConfig data class (before VideoConfig):
//    sed -i '/^    data class VideoConfig($/i\
//\    /** awb_clamp — U-05: AUTO-mode CCT clamping for M9-style stabilization. */\
//\    data class AwbClampConfig(\
//\        @SerializedName("enabled") val enabled: Boolean? = true,\
//\        @SerializedName("min_cct") val minCct: Int? = 3500,\
//\        @SerializedName("max_cct") val maxCct: Int? = 7000,\
//\        @SerializedName("smoothing_alpha") val smoothingAlpha: Float? = 0.15f,\
//\    )\
//\
//\' "$lcfg"
//
// 11. Add 4 awbClamp* accessors (after perChannelTintBlue):
//    sed -i '/^    val perChannelTintBlue: Int get() = currentConfig?.colorScience?.perChannelTintBlue ?: 0$/a\
//\
//\    // U-05: AWB CCT clamping (M9-style stabilization for default AUTO mode)\
//\    val awbClampEnabled: Boolean get() = currentConfig?.awbClamp?.enabled ?: true\
//\    val awbClampMinCct: Int get() = currentConfig?.awbClamp?.minCct ?: 3500\
//\    val awbClampMaxCct: Int get() = currentConfig?.awbClamp?.maxCct ?: 7000\
//\    val awbClampSmoothingAlpha: Float get() = currentConfig?.awbClamp?.smoothingAlpha ?: 0.15f' "$lcfg"

// ─── REFERENCIADO POR (NÃO QUEBRAR) ──────────────────────────────────────────
//   - Camera2Controller.kt::applyWhiteBalanceSettings (L3466 dispatcher — calls
//     applyAutoWhiteBalanceSettings when awbMode != OFF; U-05 gate transparent)
//   - Camera2Controller.kt::applyCctWhiteBalanceSettings (L3513 — manual CCT path,
//     has own clamp via coerceCctAwbTemperature L2984; U-05 does NOT touch)
//   - Camera2Controller.kt::applyMatrixWhiteBalanceSettings (L3538 — manual MATRIX
//     path; U-05 reuses buildColorMatrixWhiteBalanceTransform + kelvinToRggbGains)
//   - Camera2Controller.kt::readWhiteBalanceResult (L3018 — populates
//     lastWhiteBalanceResult; U-05 consumes it)
//   - Camera2Controller.kt::estimateKelvinFromRggbGains (L4782 — brute-force CCT
//     inverse; U-05 uses for pre-API36 fallback)
//   - Camera2Controller.kt::kelvinToRggbGains (L4740 — Tanner-Helland blackbody;
//     U-05 uses to convert clamped CCT back to gains)
//   - Camera2Controller.kt::buildColorMatrixWhiteBalanceTransform (L3569 — derives
//     CCM from SENSOR_COLOR_TRANSFORM1/2 + gains; U-05 reuses)
//   - Camera2Controller.kt::supportsManualMatrixWhiteBalance (L2998 — device gate;
//     U-05 uses as runtime support check)
//   - Camera2Controller.kt per-frame capture-result handler (L753-786 — updates
//     lastWhiteBalanceResult; U-05 reads cross-frame)
//   - Camera2Controller.kt camera close (L1287) + reopen (L1707) reset spots
//   - HncsProfile.kt L263-270 (clamp pattern template — temperature.coerceIn)
//   - LeicaConfig.kt::LeicaPerfectConfig (L52-79 — gains awbClamp field)
//   - LeicaConfig.kt::ColorScienceConfig (L310-321 — neighbor data class)
//   - config/leica_perfect.json (awb_clamp section L115-124 — enabled/min_cct/
//     max_cct/smoothing_alpha/outdoor_daylight_range/indoor_tungsten_range/
//     indoor_led_warm_range/indoor_led_neutral_range — only first 4 consumed by
//     U-05; the 4 range arrays reserved for future v7.0 scene-adaptive α)
//   - DEFINITIVE_AUDIT_v6.2.md HARD-LIMIT H8 "custom AWB" (CLOSED by U-05)
//   - explore-U-05-awb Stage Summary §6 (injection point B — HAL FEEDBACK clamp)
//
// ⭐  H8 STATUS: CLOSED by U-05. AWB CCT clamping agora ativo no runtime.
//    Default AUTO mode receives EMA-smoothed + clamped MANUAL TRANSFORM_MATRIX
//    path instead of pure HAL AUTO. M9-style stabilization agora é REAL
//    (mathematical clamp) em vez de PERCEPTUAL (LUT amber masking).
