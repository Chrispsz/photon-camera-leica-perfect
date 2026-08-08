// ═══════════════════════════════════════════════════════════════════════════════
// WhiteLevelWiring.patch.kt — P-37 (v6.1) — split em P-37a + P-37b
// ═══════════════════════════════════════════════════════════════════════════════
//
// ⚠️  DOCUMENTATION-ONLY — Este arquivo NÃO é compilado. Mostra o que o sed em
//     build-archlinux.sh cmd_patch() P-37a + P-37b injeta no upstream PhotonCamera.
//
// O QUE FAZ:
//   P-37a: Adiciona função `resolveWhiteLevelForLens(lensKey, defaultWhiteLevel)`
//          ao companion object de RawWhiteLevelCorrection.kt.
//   P-37b: Substitui o fallback hardcoded `?: 1023f` em RawMetadata.kt:197 por
//          uma chamada `LeicaConfig.whiteLevelForLens(lensKey)` — retornando
//          16383 pra main (14-bit), 1023 pra UW/tele/front (10-bit).
//
// POR QUE:
//   - Task 5-a gap B2: sensors.main_white_level/etc fields defined no JSON mas
//     SEM accessor nem wiring — JSON era doc-only.
//   - Stock PhotonCamera: white level default 1023 (10-bit) pra todos os sensors.
//     Mas OV50E main do Xiaomi 15T tem ADC 14-bit — white level real é 16383.
//   - Usar 1023 em main: perde 6 dB de dynamic range (= 1 EV), highlights clipam
//     1 stop antes do necessário.
//   - P-37b fix: main usa 16383 (TRUE 14-bit MAX), UW/tele/front usam 1023.
//
// ARQUIVOS TARGET:
//   - app/src/main/java/com/hinnka/mycamera/raw/RawWhiteLevelCorrection.kt (P-37a)
//   - app/src/main/java/com/hinnka/mycamera/raw/RawMetadata.kt (P-37b)
//
// ÂNCORAS SED:
//   P-37a: `fun isOverrideMode(mode: String?): Boolean {` (unique em RawWhiteLevelCorrection.kt)
//   P-37b: `?: 1023f // 默认 10-bit` (unique em RawMetadata.kt, line ~197)
//
// LeicaConfig ACCESSORS USADOS:
//   - LeicaConfig.whiteLevelForLens(lensKey: String): Int
//   - LeicaConfig.lensKeyFromCameraId(cameraId: String): String
//
// RACE CONDITION NOTE:
//   - whiteLevelForLens é race-safe (`fun` com `?: default` fallback).
//   - Mas P-37b só ativa quando HAL não reporta white level (raro). Quando HAL
//     reporta, valor authoritative é usado — P-37b fallback só fires em HAL failure.
//   - O caso mais impactante: OV50E main HAL failure → P-37b retorna 16383
//     (correto), em vez de 1023 (stock — errado pra 14-bit).
//
// LIMITAÇÕES:
//   - P-37b usa synthetic cameraId derivado de LENS_FACING (back=main, front=front).
//     Não distingue UW/tele do main (todos LENS_FACING_BACK=0). Pra UW/tele, HAL
//     tipicamente reports correct white level (1023), então fallback raramente fires.
//   - P-37a função NÃO é chamada em nenhum site ainda (wiring está pronto pra
//     uso futuro, mas P-37b é o patch que realmente tem runtime effect).
// ═══════════════════════════════════════════════════════════════════════════════

package com.hinnka.mycamera.raw

import com.hinnka.mycamera.raw.LeicaConfig

// ─── P-37a: RawWhiteLevelCorrection.kt — ANTES ───────────────────────────────
object RawWhiteLevelCorrection {
    const val MODE_RAW10 = "raw10"
    const val MODE_RAW12 = "raw12"
    const val MODE_RAW14 = "raw14"
    const val MODE_SENSOR = "sensor"

    fun isOverrideMode(mode: String?): Boolean {
        // ...
    }

    fun resolveWhiteLevel(defaultWhiteLevel: Int, mode: String?, customWhiteLevel: Int): Int {
        // ...
    }
}

// ─── P-37a: RawWhiteLevelCorrection.kt — DEPOIS ──────────────────────────────
object RawWhiteLevelCorrection {
    const val MODE_RAW10 = "raw10"
    const val MODE_RAW12 = "raw12"
    const val MODE_RAW14 = "raw14"
    const val MODE_SENSOR = "sensor"

    fun isOverrideMode(mode: String?): Boolean {
        // ...
    }

    fun resolveWhiteLevel(defaultWhiteLevel: Int, mode: String?, customWhiteLevel: Int): Int {
        // ...
    }

    /**
     * resolveWhiteLevelForLens — retorna white level per-sensor (v6.1).
     * main=16383 (14-bit OV50E), UW/tele/front=1023 (10-bit S5KJN1/S5K3J1/OV32B).
     * Fallback pro defaultWhiteLevel se config ausente.
     *
     * @param lensKey "main"/"uw"/"tele"/"front"
     * @param defaultWhiteLevel fallback se LeicaConfig retornar 0 ou negativo
     */
    fun resolveWhiteLevelForLens(lensKey: String, defaultWhiteLevel: Int): Int {
        val configured = LeicaConfig.whiteLevelForLens(lensKey)
        return if (configured > 0) configured else defaultWhiteLevel
    }
}

// ─── P-37b: RawMetadata.kt — ANTES ───────────────────────────────────────────
fun extractWhiteLevel(characteristics: CameraCharacteristics): Float {
    val halLevel = characteristics.get(CameraCharacteristics.SENSOR_WHITE_LEVEL)
    return halLevel?.toFloat() ?: 1023f  // 默认 10-bit (default fallback)
}

// ─── P-37b: RawMetadata.kt — DEPOIS ──────────────────────────────────────────
fun extractWhiteLevel(
    characteristics: CameraCharacteristics,
    cameraId: String? = null  // synthetic via LENS_FACING se null
): Float {
    val halLevel = characteristics.get(CameraCharacteristics.SENSOR_WHITE_LEVEL)
    return halLevel?.toFloat()
        ?: LeicaConfig.whiteLevelForLens(LeicaConfig.lensKeyFromCameraId(cameraId ?: "main")).toFloat()
        // P-37b per-lens override: main=16383 (14-bit), UW/tele/front=1023 (10-bit)
}

// ─── SED COMMANDS (executados pelo build-archlinux.sh) ───────────────────────
// P-37a (append função em RawWhiteLevelCorrection.kt):
//   sed -i '/^fun isOverrideMode(mode: String?): Boolean {$/a\
//\
///**\
// * resolveWhiteLevelForLens — retorna white level per-sensor (v6.1).\
// * main=16383 (14-bit), UW/tele/front=1023 (10-bit).\
// */\
//fun resolveWhiteLevelForLens(lensKey: String, defaultWhiteLevel: Int): Int {\
//    val configured = LeicaConfig.whiteLevelForLens(lensKey)\
//    return if (configured > 0) configured else defaultWhiteLevel\
//}' "$rwlc"
//
// P-37b (substitui fallback em RawMetadata.kt):
//   sed -i 's|?: 1023f // 默认 10-bit|?: LeicaConfig.whiteLevelForLens(LeicaConfig.lensKeyFromCameraId(cameraId ?: "main")).toFloat() // P-37b per-lens override|' "$rmd"

// ─── REFERENCIADO POR (NÃO QUEBRAR) ──────────────────────────────────────────
//   - P-17 (RawMetadata black/white levels — P-37b sobrescreve white level fallback)
//   - P-38 (BlackLevelWiring — sibling patch no mesmo arquivo)
//   - P-36 (NoiseModelFallback — outro patch no mesmo RawMetadata.kt)
//   - LeicaConfig.whiteLevelForLens() / lensKeyFromCameraId()
//   - LeicaConfig.SensorsConfig data class (main_white_level=16383, etc)
//   - max_capability_audit.md §BITS MAX (main 14-bit confirmation)
//   - RawWhiteLevelCorrection.kt MODE_RAW14 (consumidor potencial de P-37a)
