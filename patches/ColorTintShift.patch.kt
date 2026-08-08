// ═══════════════════════════════════════════════════════════════════════════════
// ColorTintShift.patch.kt — P-33 (v6.0)
// ═══════════════════════════════════════════════════════════════════════════════
//
// ⚠️  DOCUMENTATION-ONLY — Este arquivo NÃO é compilado. Mostra o que o sed em
//     build-archlinux.sh cmd_patch() P-33 injeta no upstream PhotonCamera.
//
// O QUE FAZ:
//   Substitui a chamada global `LeicaConfig.colorTintShift` (aplicada em P-30)
//   por `LeicaConfig.tintShiftForLens(cameraId ?: "main")` — aplicando tint
//   shift PER-LENS ao invés de um valor global único.
//
// POR QUE:
//   - VLM pixel analysis (Task 3-c) identificou magenta tint push +6 a +16
//     dependendo da cena. Mas a severidade varia por lens:
//       main (OV50E):   pior caso — magenta +16 em outdoor daylight
//       UW (S5KJN1):    magenta +12 em vegetação
//       tele (S5K3J1):  magenta +11 em retrato
//       front (OV32B):  magenta +8 (menos, mas com warmth de pele)
//   - v6.0 aplicou tint_shift global -12 (corrigiu a média, mas super-corrigiu front).
//   - v6.0 P-33 divide por lens: -14 main, -12 UW, -11 tele, -8 front.
//   - Cada valor derivado da VLM analysis da cena onde aquele lens brilha.
//
// ARQUIVO TARGET: app/src/main/java/com/hinnka/mycamera/raw/DcpProfile.kt
// ÂNCORA SED: `LeicaConfig.colorTintShift` (injetado por P-30, único no arquivo)
// INJEÇÃO: substituição in-place (sed global flag)
//
// LeicaConfig ACCESSORS USADOS:
//   - LeicaConfig.tintShiftForLens(lensKey: String): Int
//   - LeicaConfig.lensKeyFromCameraId(cameraId: String): String
//
// LIMITAÇÕES:
//   - `cameraId` precisa estar em scope no call site. DcpProfile.kt::resolveRenderPlan
//     tem acesso via parâmetro threading (já adicionado em patches anteriores).
//   - A função applyTintShift(matrix, tintShift) é adicionada por P-30 (helper).
//   - tint_shift negativo = mais verde; positivo = mais magenta.
//   - O valor é aplicado à CCM (Color Correction Matrix) modificando green row
//     por (1 + tintShift/100) e red/blue rows por (1 - tintShift/100).
// ═══════════════════════════════════════════════════════════════════════════════

package com.hinnka.mycamera.raw

import com.hinnka.mycamera.raw.LeicaConfig

// ─── ANTES (após P-30, que aplicou tint global -12) ──────────────────────────
fun resolveRenderPlan(...) {
    // ...
    val selectedMatrix = ... // CCM base
    colorCorrectionMatrix = applyTintShift(selectedMatrix, LeicaConfig.colorTintShift)
    // Aplica -12 global (supercorrige front, subcorrige main)
}

// ─── DEPOIS (após P-33 per-lens) ─────────────────────────────────────────────
fun resolveRenderPlan(cameraId: String? = null, ...) {
    // ...
    val selectedMatrix = ... // CCM base
    val lensKey = LeicaConfig.lensKeyFromCameraId(cameraId ?: "main")
    colorCorrectionMatrix = applyTintShift(selectedMatrix, LeicaConfig.tintShiftForLens(lensKey))
    // main=-14, UW=-12, tele=-11, front=-8 (valores VLM-derived per lens)
}

// ─── Helper applyTintShift (adicionado por P-30, mantido por P-33) ──────────
/**
 * applyTintShift — aplica tint shift à CCM 3x3.
 * tintShift > 0 = empurra magenta (red/blue up, green down)
 * tintShift < 0 = empurra verde (red/blue down, green up)
 *
 * Math: green row × (1 + tint/100), red/blue rows × (1 - tint/100)
 * Exemplo: tint=-14 → green × 0.86, red/blue × 1.14 (mais verde)
 */
fun applyTintShift(matrix: FloatArray, tintShift: Int): FloatArray {
    if (tintShift == 0) return matrix
    val factor = tintShift / 100.0f
    // matrix layout: [r_r, r_g, r_b, g_r, g_g, g_b, b_r, b_g, b_b]
    return floatArrayOf(
        matrix[0] * (1 - factor), matrix[1] * (1 - factor), matrix[2] * (1 - factor),  // red row
        matrix[3] * (1 + factor), matrix[4] * (1 + factor), matrix[5] * (1 + factor),  // green row
        matrix[6] * (1 - factor), matrix[7] * (1 - factor), matrix[8] * (1 - factor),  // blue row
    )
}

// ─── SED COMMAND (executado pelo build-archlinux.sh) ─────────────────────────
// Usa `|` delimiter pra evitar escaping `/` em divisões:
// sed -i 's|LeicaConfig.colorTintShift|LeicaConfig.tintShiftForLens(cameraId ?: "main")|g' "$dcp"

// ─── REFERENCIADO POR (NÃO QUEBRAR) ──────────────────────────────────────────
//   - P-30 (aplica tint shift global — P-33 substitui por per-lens)
//   - P-19/P-20 (DcpProfile force Leica DCP + illuminant ratios)
//   - LeicaConfig.tintShiftForLens() / lensKeyFromCameraId()
//   - VLM pixel analysis (config/vlm_pixel_analysis.md §Aggregate Tuning Deltas)
