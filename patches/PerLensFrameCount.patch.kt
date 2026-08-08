// ═══════════════════════════════════════════════════════════════════════════════
// PerLensFrameCount.patch.kt — P-31 (v6.0)
// ═══════════════════════════════════════════════════════════════════════════════
//
// ⚠️  DOCUMENTATION-ONLY — Este arquivo NÃO é compilado. Mostra o que o sed em
//     build-archlinux.sh cmd_patch() P-31 injeta no upstream PhotonCamera.
//
// O QUE FAZ:
//   Adiciona função `frameCountForCamera(cameraId: String): Int` ao companion
//   object de MultiFrameConfig.kt. Essa função retorna o frame count per-lens
//   (main=15, UW=9, tele=7, front=11) lendo do LeicaConfig.frameCountForLens().
//
// POR QUE:
//   - GCam XML (BigKaka V7.0 AGC9.6.19) tinha: main=11, UW=7, tele=5, front=7.
//   - v6.0 BEAT GCam derivando da física do sensor + RAW Radiance + gyro:
//       main=15 (+36% vs GCam — OV50E 1.2μm QuadBayer aguenta +frames)
//       UW=9   (+29% vs GCam — S5KJN1 0.64μm small pixel, mais NR)
//       tele=7 (+40% vs GCam — S5K3J1 com OIS+gyro, mais estável)
//       front=11 (+57% vs GCam — OV32B 0.61μm, selfie estável)
//   - SNR math: 11→15 frames = +1.34 dB = +0.45 EV shadow improvement
//               7→11 frames (front) = +1.96 dB = +0.65 EV shadow improvement
//
// ARQUIVO TARGET: app/src/main/java/com/hinnka/mycamera/camera/MultiFrameConfig.kt
// ÂNCORA SED: `val SHORT_FRAME_EXPOSURE_DIVISOR` (line ~14)
// INJEÇÃO: append após a constante (mantém compatibilidade com callers existentes)
//
// LeicaConfig ACCESSORS USADOS:
//   - LeicaConfig.frameCountForLens(lensKey: String): Int
//   - LeicaConfig.lensKeyFromCameraId(cameraId: String): String
//
// LIMITAÇÕES:
//   - Não substitui DEFAULT_FRAME_COUNT — callers que usam default continuam 15
//     (já patcheado em P-8). Apenas callers que chamam frameCountForCamera()
//     recebem per-lens.
//   - Camera2Controller.kt precisa passar cameraId explícito (já faz em burst plan).
// ═══════════════════════════════════════════════════════════════════════════════

package com.hinnka.mycamera.camera

import com.hinnka.mycamera.raw.LeicaConfig

// ─── ANTES (upstream original) ───────────────────────────────────────────────
object MultiFrameConfig {
    const val MIN_FRAME_COUNT = 3
    const val MAX_FRAME_COUNT = 20
    const val DEFAULT_FRAME_COUNT = 7
    const val MIN_OUTPUT_SCALE = 1f
    const val MAX_OUTPUT_SCALE = 2f
    const val DEFAULT_SUPER_RESOLUTION_SCALE = 1f
    const val SHORT_FRAME_COUNT = 1
    const val SHORT_FRAME_EXPOSURE_DIVISOR = 3.0
    // ... resto do companion
}

// ─── DEPOIS (após P-31 sed append) ───────────────────────────────────────────
object MultiFrameConfig {
    val MIN_FRAME_COUNT get() = 3  // P-8 race-safe
    val MAX_FRAME_COUNT get() = 20  // P-8 race-safe
    val DEFAULT_FRAME_COUNT get() = LeicaConfig.multiFrameCount  // P-8 (15)
    val MIN_OUTPUT_SCALE get() = 1f
    val MAX_OUTPUT_SCALE get() = 2f
    val DEFAULT_SUPER_RESOLUTION_SCALE get() = LeicaConfig.multiFrameSuperResolutionScale  // P-8
    const val SHORT_FRAME_COUNT = 1
    val SHORT_FRAME_EXPOSURE_DIVISOR get() = LeicaConfig.multiFrameShortFrameExposureDivisor  // P-8

    /**
     * frameCountForCamera — retorna frame count per-lens (v6.0 BEAT GCam).
     * main=15, UW=9, tele=7, front=11. Fallback pra DEFAULT_FRAME_COUNT se lensKey
     * não encontrado em per_lens config.
     *
     * @param cameraId Camera2 ID string ("0"=main, "1"=front, "2"=UW, "4"=tele)
     * @return frame count inteiro (clampado 3..20)
     */
    fun frameCountForCamera(cameraId: String): Int =
        LeicaConfig.frameCountForLens(LeicaConfig.lensKeyFromCameraId(cameraId))
}

// ─── SED COMMAND (executado pelo build-archlinux.sh) ─────────────────────────
// sed -i '/^val SHORT_FRAME_EXPOSURE_DIVISOR/a\
//\
///**\
// * frameCountForCamera — retorna frame count per-lens (v6.0 BEAT GCam).\
// * main=15, UW=9, tele=7, front=11. Fallback pra DEFAULT_FRAME_COUNT.\
// */\
//fun frameCountForCamera(cameraId: String): Int = LeicaConfig.frameCountForLens(LeicaConfig.lensKeyFromCameraId(cameraId))' \
//   "$APP_JAVA/camera/MultiFrameConfig.kt"

// ─── REFERENCIADO POR (NÃO QUEBRAR) ──────────────────────────────────────────
//   - Camera2Controller.kt continueCaptureAfterFocusPreparation() L6856+ (burst plan)
//   - P-8 (DEFAULT_FRAME_COUNT race-safe)
//   - LeicaConfig.frameCountForLens() / lensKeyFromCameraId()
//   - effectiveFrameCountForLens() em LeicaConfig (combina com capture_mode multiplier)
