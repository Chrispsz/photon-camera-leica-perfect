// ═══════════════════════════════════════════════════════════════════════════════
// BlackLevelWiring.patch.kt — P-38 (v6.1)
// ═══════════════════════════════════════════════════════════════════════════════
//
// ⚠️  DOCUMENTATION-ONLY — Este arquivo NÃO é compilado. Mostra o que o sed em
//     build-archlinux.sh cmd_patch() P-38 injeta no upstream PhotonCamera.
//
// O QUE FAZ:
//   Substitui o fallback hardcoded `floatArrayOf(64f, 64f, 64f, 64f)` em
//   RawMetadata.kt:222 (extractBlackLevel) por uma expressão que consulta
//   LeicaConfig.blackLevelForLens(lensKey) — retornando 1024 pra main (14-bit
//   pedestal) e 64 pra UW/tele/front (10-bit pedestal).
//
// POR QUE:
//   - Sibling do P-37 (white level). Mesma Task 5-a gap B2.
//   - Stock PhotonCamera: black level default 64 pra todos os sensors (10-bit).
//     Mas OV50E main 14-bit tem pedestal óptico 1024 (= 2^10, reservado pelos
//     bottom 10 bits do RAW16 container de 14-bit dynamic range).
//   - Usar 64 em main: leaves 960 LSBs of dynamic range "below black" — shadows
//     aparecem lifted/lavadas porque pipeline subtrai apenas 64 em vez de 1024.
//   - P-38 fix: main usa 1024, UW/tele/front usam 64.
//
// ARQUIVO TARGET: app/src/main/java/com/hinnka/mycamera/raw/RawMetadata.kt
// ÂNCORA SED: `floatArrayOf(64f, 64f, 64f, 64f)` (unique no arquivo, line ~222)
// INJEÇÃO: substituição in-place com expressão per-lens
//
// LeicaConfig ACCESSORS USADOS:
//   - LeicaConfig.blackLevelForLens(lensKey: String): Int
//   - LeicaConfig.lensKeyFromCameraId(cameraId: String): String
//
// RACE CONDITION NOTE:
//   - blackLevelForLens é race-safe (fun com ?: fallback).
//   - P-38 só ativa quando HAL não reporta black level (raro). Em HAL failure
//     do main, P-38 retorna 1024 (correto 14-bit) em vez de 64 (errado).
//
// LIMITAÇÕES:
//   - Mesma limitação que P-37b: synthetic cameraId via LENS_FACING não distingue
//     UW/tele. Mas UW/tele HALs tipicamente reportam black level corretamente.
//   - floatArrayOf retorna 4 floats (uma por CFA channel R/Gr/Gb/B). Todos
//     recebem o mesmo valor (assumimos black level uniforme — true pra QuadBayer).
// ═══════════════════════════════════════════════════════════════════════════════

package com.hinnka.mycamera.raw

import com.hinnka.mycamera.raw.LeicaConfig

// ─── ANTES (upstream original) ───────────────────────────────────────────────
fun extractBlackLevel(characteristics: CameraCharacteristics): FloatArray {
    val halLevels = characteristics.get(CameraCharacteristics.SENSOR_BLACK_LEVEL)
    return if (halLevels != null && halLevels.size >= 4) {
        floatArrayOf(halLevels[0], halLevels[1], halLevels[2], halLevels[3])
    } else {
        // Stock fallback: 64 pra todos os 4 channels (10-bit default)
        floatArrayOf(64f, 64f, 64f, 64f)
    }
}

// ─── DEPOIS (após P-38) ──────────────────────────────────────────────────────
fun extractBlackLevel(
    characteristics: CameraCharacteristics,
    cameraId: String? = null  // synthetic via LENS_FACING se null
): FloatArray {
    val halLevels = characteristics.get(CameraCharacteristics.SENSOR_BLACK_LEVEL)
    return if (halLevels != null && halLevels.size >= 4) {
        floatArrayOf(halLevels[0], halLevels[1], halLevels[2], halLevels[3])
    } else {
        // v6.1 P-38 fallback: per-sensor black level (main=1024, UW/tele/front=64)
        val bl = LeicaConfig.blackLevelForLens(
            LeicaConfig.lensKeyFromCameraId(cameraId ?: "main")
        ).toFloat()
        floatArrayOf(bl, bl, bl, bl)
    }
}

// ─── SED COMMAND (executado pelo build-archlinux.sh) ─────────────────────────
// sed -i 's|floatArrayOf(64f, 64f, 64f, 64f)|floatArrayOf(bl, bl, bl, bl).also { val bl = LeicaConfig.blackLevelForLens(LeicaConfig.lensKeyFromCameraId(cameraId ?: "main")).toFloat() }|g' "$rmd"

// ─── REFERENCIADO POR (NÃO QUEBRAR) ──────────────────────────────────────────
//   - P-17 (RawMetadata black/white levels — P-38 sobrescreve black level fallback)
//   - P-37 (WhiteLevelWiring — sibling patch)
//   - P-36 (NoiseModelFallback — outro patch no mesmo RawMetadata.kt)
//   - LeicaConfig.blackLevelForLens() / lensKeyFromCameraId()
//   - LeicaConfig.SensorsConfig data class (main_black_level=1024, etc)
//   - max_capability_audit.md §BITS MAX (main 14-bit pedestal 1024)
//   - RawShaders.kt applyBlackWhiteLevels() (consumer do black level)
