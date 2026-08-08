// ═══════════════════════════════════════════════════════════════════════════════
// PerChannelSaturation.patch.kt — P-34 (v6.0)
// ═══════════════════════════════════════════════════════════════════════════════
//
// ⚠️  DOCUMENTATION-ONLY — Este arquivo NÃO é compilado. Mostra o que o sed em
//     build-archlinux.sh cmd_patch() P-34 injeta no upstream PhotonCamera.
//
// O QUE FAZ:
//   Substitui as constantes GLSL `SATURATION_RED/GREEN/BLUE` (injetadas por P-29
//   com valores globais colorScience.saturation_*_pct) por valores PER-LENS:
//     SATURATION_RED = LeicaConfig.saturationRedForLens(currentLensKey)
//     SATURATION_GREEN = LeicaConfig.saturationGreenForLens(currentLensKey)
//     SATURATION_BLUE = LeicaConfig.saturationBlueForLens(currentLensKey)
//
// POR QUE:
//   - VLM analysis mostrou per-channel saturation imbalance:
//       Outdoor: verde +22% (folhagem/neon oversaturated)
//       Indoor: azul +15% (lâmpadas fluorescentes)
//       Skin: vermelho +12% (pele muito vermelha)
//   - v6.0 aplicou saturation_red_pct=-5, green=-10, blue=-7 (global).
//   - Mas cada lens tem características ópticas diferentes:
//       main (OV50E 1.2μm QuadBayer): green worst (-12% needed)
//       UW (S5KJN1 0.64μm): blue worst (-10% needed — daylight UW)
//       tele (S5K3J1 0.8μm): green worst (-11% needed)
//       front (OV32B 0.61μm): red preserve (pele! R=-4 only)
//   - P-34 divide por lens usando os valores beat-GCam (Task 5-b):
//       main:   R=0.93, G=0.88, B=0.92
//       UW:     R=0.95, G=0.90, B=0.94
//       tele:   R=0.94, G=0.89, B=0.91
//       front:  R=0.96, G=0.92, B=0.94 (R preservado pra pele)
//
// ARQUIVO TARGET: app/src/main/java/com/hinnka/mycamera/raw/RawShaders.kt
// ÂNCORA SED: `const float SATURATION_RED = float(LeicaConfig.colorSciencePerChannelRed());`
// INJEÇÃO: substituição in-place (3 seds, um por canal)
//
// LeicaConfig ACCESSORS USADOS:
//   - LeicaConfig.saturationRedForLens(lensKey: String): Float
//   - LeicaConfig.saturationGreenForLens(lensKey: String): Float
//   - LeicaConfig.saturationBlueForLens(lensKey: String): Float
//
// LIMITAÇÕES:
//   - `currentLensKey` precisa estar em scope no shader context. Em GLSL, isso
//     significa que o Kotlin ${} interpolation happens at shader compile time —
//     cada lens gera um shader variant cacheado na GPU shader program.
//   - Em RAW Radiance pipeline (GlesRawRadianceStacker), shaders são compilados
//     1x por lens switch — overhead aceitável (1-3ms no primeiro frame).
//   - P-29 já adicionou os uniforms; P-34 apenas troca o source do valor.
// ═══════════════════════════════════════════════════════════════════════════════

package com.hinnka.mycamera.raw

import com.hinnka.mycamera.raw.LeicaConfig

// ─── ANTES (após P-29, valores globais) ──────────────────────────────────────
val combinedFragmentShader = """
    #version 300 es
    precision highp float;
    // ...
    uniform mat3 uProfileToEngineTransform;
    const float SATURATION_RED = float(LeicaConfig.colorSciencePerChannelRed());
    const float SATURATION_GREEN = float(LeicaConfig.colorSciencePerChannelGreen());
    const float SATURATION_BLUE = float(LeicaConfig.colorSciencePerChannelBlue());
    // ...
    void main() {
        vec4 color = ...;
        color = applyBlackWhiteLevels(color);
        // saturation mix block (added by P-29):
        color.rgb = mix(vec3(dot(color.rgb, vec3(0.299, 0.587, 0.114))), color.rgb, 1.0);
        color.r *= SATURATION_RED;    // -5% global
        color.g *= SATURATION_GREEN;  // -10% global
        color.b *= SATURATION_BLUE;   // -7% global
        // ...
    }
""".trimIndent()

// ─── DEPOIS (após P-34 per-lens) ─────────────────────────────────────────────
val combinedFragmentShader = """
    #version 300 es
    precision highp float;
    // ...
    uniform mat3 uProfileToEngineTransform;
    const float SATURATION_RED = float(LeicaConfig.saturationRedForLens(currentLensKey));
    const float SATURATION_GREEN = float(LeicaConfig.saturationGreenForLens(currentLensKey));
    const float SATURATION_BLUE = float(LeicaConfig.saturationBlueForLens(currentLensKey));
    // ...
    void main() {
        vec4 color = ...;
        color = applyBlackWhiteLevels(color);
        color.rgb = mix(vec3(dot(color.rgb, vec3(0.299, 0.587, 0.114))), color.rgb, 1.0);
        color.r *= SATURATION_RED;    // main: -7%, front: -4% (preserve skin reds)
        color.g *= SATURATION_GREEN;  // main: -12% (worst neon green), front: -8%
        color.b *= SATURATION_BLUE;   // main: -8%, UW: -6% (daylight UW)
        // ...
    }
""".trimIndent()

// ─── SED COMMANDS (executados pelo build-archlinux.sh) ───────────────────────
// sed -i 's|const float SATURATION_RED = float(LeicaConfig.colorSciencePerChannelRed());|const float SATURATION_RED = float(LeicaConfig.saturationRedForLens(currentLensKey));|' "$rsh"
// sed -i 's|const float SATURATION_GREEN = float(LeicaConfig.colorSciencePerChannelGreen());|const float SATURATION_GREEN = float(LeicaConfig.saturationGreenForLens(currentLensKey));|' "$rsh"
// sed -i 's|const float SATURATION_BLUE = float(LeicaConfig.colorSciencePerChannelBlue());|const float SATURATION_BLUE = float(LeicaConfig.saturationBlueForLens(currentLensKey));|' "$rsh"

// ─── REFERENCIADO POR (NÃO QUEBRAR) ──────────────────────────────────────────
//   - P-29 (adiciona GLSL consts + saturation mix block — base pra P-34)
//   - P-3 (RawShaders USM sharpening — outro patch no mesmo arquivo)
//   - LeicaConfig.saturationRedForLens/GreenForLens/BlueForLens
//   - VLM pixel analysis (config/vlm_pixel_analysis.md §Per-Channel Saturation)
//   - beat_gcam_rationale.md §3 (per-lens values derivation)
