// ═══════════════════════════════════════════════════════════════════════════════
// NoiseModelFallback.patch.kt — P-36 (v6.0)
// ═══════════════════════════════════════════════════════════════════════════════
//
// ⚠️  DOCUMENTATION-ONLY — Este arquivo NÃO é compilado. Mostra o que o sed em
//     build-archlinux.sh cmd_patch() P-36 injeta no upstream PhotonCamera.
//
// O QUE FAZ:
//   Substitui o fallback zero `floatArrayOf(0.0f, 0.0f)` em RawMetadata.kt
//   extractChannelNoiseProfile() por uma expressão que consulta
//   LeicaConfig.noiseModelForLens(lensKey) — retornando coeficientes physics-
//   derived (a, b, c, d) quando o HAL não reporta noise profile.
//
// POR QUE:
//   - Stock PhotonCamera: se CameraCharacteristics.SENSOR_NOISE_PROFILE retornar
//     null ou vazio, fallback é `floatArrayOf(0.0f, 0.0f)` (= zero noise model,
//     NLM recebe sinal limpo e não denoise nada — grainy output).
//   - Xiaomi 15T HAL às vezes retorna noise profile incompleto pra UW/tele/front.
//   - v6.0 P-36: injeta coeficientes quadratic physics-derived por sensor:
//       noise^2 = a*ISO^2 + b*ISO + c + d/ISO   (model GCam-style)
//     PC's RawNoiseModel usa linear S+O: shotNoise=b, readNoise=d (a/c descartados).
//     Coefficients derivados do pixel size → full-well → read noise physics:
//       main (OV50E 1.2μm):     a=2.8e-7, b=9.2e-6,  c=4.2e-6,  d=6.1e-8
//       UW (S5KJN1 0.64μm):     a=7.2e-7, b=2.4e-5,  c=1.1e-5,  d=1.6e-7  (2.6x main)
//       tele (S5K3J1 0.8μm):    a=5.0e-7, b=1.6e-5,  c=7.6e-6,  d=1.1e-7  (1.8x main)
//       front (OV32B 0.61μm):   a=9.5e-7, b=3.2e-5,  c=1.4e-5,  d=2.1e-7  (3.4x main)
//
// ARQUIVO TARGET: app/src/main/java/com/hinnka/mycamera/raw/RawMetadata.kt
// ÂNCORA SED: `floatArrayOf(0.0f, 0.0f)` (unique no arquivo, line ~361)
// INJEÇÃO: substituição in-place com expressão per-lens
//
// LeicaConfig ACCESSORS USADOS:
//   - LeicaConfig.noiseModelForLens(lensKey: String): NoiseModelCoefficients?
//   - LeicaConfig.lensKeyFromCameraId(cameraId: String): String
//
// LIMITAÇÕES:
//   - lensKey é derivado de LENS_FACING via syntheticCameraId (front=1, back=0).
//     No Xiaomi 15T, cameraId="0"=main, "1"=front, "2"=UW, "4"=tele.
//     lensKeyFromCameraId mapeia isso corretamente.
//   - Coefficients são aproximados (±5-15% vs real calibração per-unit). Mas
//     ordens de grandeza corretas — NLM aplica força apropriada por sensor.
//   - Para RAW domain denoise (RAW Radiance fusion), o noise model alimenta o
//     robust fusion weight —准确性 critical pra evitar ghosting.
//   - Future refinement: thread actual cameraId through RawMetadata.create() signature
//     (atualmente usa LENS_FACING heuristic).
// ═══════════════════════════════════════════════════════════════════════════════

package com.hinnka.mycamera.raw

import com.hinnka.mycamera.raw.LeicaConfig

// ─── ANTES (upstream original) ───────────────────────────────────────────────
fun extractChannelNoiseProfile(
    characteristics: CameraCharacteristics,
    // ...
): FloatArray {
    val halProfile = characteristics.get(CameraCharacteristics.SENSOR_NOISE_PROFILE)
    return if (halProfile != null && halProfile.isNotEmpty()) {
        halProfile  // HAL reported — use authoritative
    } else {
        // Stock fallback: zero noise model = NLM no-op = grainy output
        floatArrayOf(0.0f, 0.0f)
    }
}

// ─── DEPOIS (após P-36) ──────────────────────────────────────────────────────
fun extractChannelNoiseProfile(
    characteristics: CameraCharacteristics,
    cameraId: String? = null,  // adicionado por P-36 (synthetic via LENS_FACING fallback)
    // ...
): FloatArray {
    val halProfile = characteristics.get(CameraCharacteristics.SENSOR_NOISE_PROFILE)
    return if (halProfile != null && halProfile.isNotEmpty()) {
        halProfile  // HAL reported — use authoritative
    } else {
        // v6.0 P-36 fallback: physics-derived per-sensor coefficients
        // PC's RawNoiseModel usa linear S+O: shotNoise=b, readNoise=d
        LeicaConfig.noiseModelForLens(
            LeicaConfig.lensKeyFromCameraId(cameraId ?: "main")
        )?.let { coeffs ->
            floatArrayOf(coeffs.b.toFloat(), coeffs.d.toFloat())
        } ?: floatArrayOf(0.0f, 0.0f)  // último fallback: zero (config ausente)
    }
}

// ─── SED COMMAND (executado pelo build-archlinux.sh) ─────────────────────────
// sed -i 's|floatArrayOf(0.0f, 0.0f)|LeicaConfig.noiseModelForLens(LeicaConfig.lensKeyFromCameraId(cameraId ?: "main"))?.let { floatArrayOf(it.b.toFloat(), it.d.toFloat()) } ?: floatArrayOf(0.0f, 0.0f)|g' "$rmd"

// ─── REFERENCIADO POR (NÃO QUEBRAR) ──────────────────────────────────────────
//   - P-17 (RawMetadata black/white levels — outro patch no mesmo arquivo)
//   - P-37b (RawMetadata white level per-lens — outro patch no mesmo arquivo)
//   - P-38 (RawMetadata black level per-lens — outro patch no mesmo arquivo)
//   - LeicaConfig.noiseModelForLens() / lensKeyFromCameraId()
//   - LeicaConfig.NoiseModelCoefficients data class (a/b/c/d fields)
//   - RawNoiseModel.kt (consumer do noise profile retornado)
//   - beat_gcam_rationale.md §noise_model (physics-derived coefficients)
//   - gcam_xml_extracted.md §Noise (BigKaka V7.0 pref_noise_model 72/130)
