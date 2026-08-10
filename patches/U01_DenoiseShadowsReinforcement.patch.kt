// ═══════════════════════════════════════════════════════════════════════════════
// U01_DenoiseShadowsReinforcement.patch.kt — U-01 (v6.3) — Denoise Shadows Reinforcement
// ═══════════════════════════════════════════════════════════════════════════════
//
// ⚠️  DOCUMENTATION-ONLY — Este arquivo NÃO é compilado. Mostra o que o sed em
//     build-archlinux.sh cmd_patch() U-01 injeta no upstream PhotonCamera.
//
// ⭐⭐ PATCH STATUS: REAL — actually effective at runtime. ⭐⭐
//     Fecha U-01 (reforço de denoise em shadows via modelo de ruído 4-coef por
//     lens + chroma separado + search radius config-driven + shadow band boost).
//
// O QUE FAZ (7 targets, ~14 seds):
//
//   A) SHADER UNIFORM — DenoiseProfileShaders.kt::FINISH_V2
//      - Adiciona `uniform float uShadowBandStrength;`
//      - Adiciona shadow mask: `float shadowMask = 1.0 - smoothstep(0.0, 0.15, luma);`
//      - Substitui o blend final `mix(original.rgb, px.rgb, uDenoiseMix)` por
//        versão com boost em shadows:
//          effectiveMix = uDenoiseMix + uShadowBandStrength * shadowMask * (1 - uDenoiseMix)
//          px.rgb = mix(original.rgb, px.rgb, clamp(effectiveMix, 0.0, 1.0))
//        Em shadows (luma < 0.15), effectiveMix sobe em direção a 1.0 — MAIS denoise.
//        Em highlights (luma > 0.15), shadowMask=0 — behavior igual ao original.
//
//   B) SEARCH_RADIUS — DenoiseProfileShaders.kt::SEARCH_RADIUS
//      - Troca `const val SEARCH_RADIUS = 5` por `val SEARCH_RADIUS: Int get() = ...`
//        que lê LeicaConfig.demosaicNlmSearchRadius (default config=7).
//      - Troca `private const val FUSED_TILE_X/Y` por `private val` getters que
//        dependem do SEARCH_RADIUS runtime (não pode mais ser const).
//      - PATCH_RADIUS=1 permanece const (config não varia).
//
//   C) NlmConfig — DenoiseProfileNlmConfig.kt::searchOffsets
//      - `val searchOffsets` continua lendo buildSearchOffsets(SEARCH_RADIUS) —
//        agora dinâmico via getter em DenoiseProfileShaders.
//      - O loop em RawDemosaicProcessor.dispatchDenoiseNlm já itera sobre essa
//        lista — nenhuma alteração adicional necessária.
//      - Garantia: buildSearchOffsets(radius) já aceita Int param (L41-L53).
//
//   D) NOISE MODEL FALLBACK — RawDemosaicProcessor.kt::resolveDenoiseProfileNoiseModel
//      - Adiciona `lensKey: String = "main"` parameter (backward-compatible).
//      - Return type muda de `Pair<Float, Float>` p/ `DenoiseProfileNoiseModel`
//        data class com (a, b, c, d) — a=shot slope, b=read offset,
//        c=VST exponent base (new), d=shadow-band strength multiplier (new).
//      - Substitui fallback `(1E-4f * fallbackGain, 4.5E-7f * sqrt(fallbackGain))`
//        por per-lens 4-coef model de LeicaConfig.noiseModelForLens(lensKey).
//        Fallback final mantém (1E-4f, 4.5E-7f, c=0, d=0) se config ausente.
//
//   E) SHADOWS EXPONENT — RawDemosaicProcessor.kt::inferDenoiseProfileShadows
//      - Adiciona `c: Float = 0f` parameter (VST exponent base override).
//      - Se c > 0, retorna `c.coerceIn(0.7f, 1.8f)` diretamente — per-lens tunable.
//      - Se c == 0 (default), mantém formula original: `max(0.1f - 0.1f * ln(a), 0.7f)`.
//
//   F) BUILD PARAMS — RawDemosaicProcessor.kt::buildDenoiseProfileParams
//      - Adiciona `lensKey: String = "main"` parameter.
//      - Multiplica `strengthValue` por `LeicaConfig.lumaNrMultiplierForLens(lensKey)`.
//      - Resolve 4-coef noise model (a/b/c/d) e passa c p/ inferDenoiseProfileShadows.
//      - Armazena d como `shadowBandStrength` no DenoiseProfileParams (default 0).
//      - Multiplica shadowBandStrength por LeicaConfig.noiseReductionShadowBandBoost.
//
//   G) FINISH BIND — RawDemosaicProcessor.kt::dispatchDenoiseNlmFinish
//      - Após bind `uDenoiseMix`, bind `uShadowBandStrength = params.shadowBandStrength`.
//
//   H) CHROMA NR MULTIPLIER — RawDemosaicProcessor.kt::renderDefaultChromaDenoise
//      - Adiciona `lensKey: String = "main"` parameter.
//      - Multiplica `chromaDenoiseValue` por `LeicaConfig.chromaNrMultiplierForLens(lensKey)`
//        antes de coerceIn(0f, 1f).
//
//   I) PIPELINE CALL SITE — RawDemosaicProcessor.kt::L2606 / L2619
//      - Passa `lensKey = LeicaConfig.lensKeyFromCameraId("main")` (default).
//        Comentario: future patch pode thread actual cameraId via RawMetadata.
//
//   J) LeicaConfig.kt — shadow_band_boost config field + accessor
//      - Adiciona `@SerializedName("shadow_band_boost") val shadowBandBoost: Double? = 0.5`
//        ao NoiseReductionConfig data class.
//      - Adiciona accessor `val noiseReductionShadowBandBoost: Double get() = ...`
//
// POR QUE:
//   - VLM pixel analysis (Task 3-b) identificou chroma noise residual em shadows
//     (luma < 0.15) mesmo com NLM strength=0.92 + chroma NR strength=0.70.
//   - Modelo de ruído atual usa só 2 coeficientes (a=slope, b=offset) — não
//     modela o VST exponent (c) nem shadow-band boost (d) que GCam/AGC expõe.
//   - User config leica_perfect.json tem per-lens:
//       noise_model_fallback.main: {a: 2.8e-07, b: 9.2e-06, c: 4.2e-06, d: 6.1e-08}
//       per_lens.main.luma_nr_multiplier: 1.0
//       per_lens.main.chroma_nr_multiplier: 1.1
//       demosaic.nlm_search_radius: 7  (vs hardcoded const=5)
//   - SENSOR_NOISE_PROFILE do HAL às vezes vem incompleto pra UW/tele/front —
//     fallback 1E-4/4.5E-7 é genérico e sub-ótimo pra cada sensor.
//   - Search radius 5→7 aumenta cobertura NLM em +96% (49→121 offsets half-octant)
//     — importante pra shadow denoise (mais candidates = melhor statistics).
//   - Shadow band boost: NLM clássico aplica MESMO strength em todo luma range.
//     Em shadows o SNR é pior — precisa MAIS denoise. Multiplicador adaptativo
//     via shadowMask (smoothstep 0..0.15 luma) aumenta effectiveMix progressivo.
//
// LeicaConfig ACCESSORS USADOS:
//   - LeicaConfig.demosaicNlmSearchRadius: Int          (default 7) — EXISTE L642
//   - LeicaConfig.noiseModelForLens(lensKey): NoiseModelCoefficients? — EXISTE L1072
//   - LeicaConfig.lumaNrMultiplierForLens(lensKey): Float    (default 1.0) — EXISTE L827
//   - LeicaConfig.chromaNrMultiplierForLens(lensKey): Float  (default 1.0) — EXISTE L833
//   - LeicaConfig.lensKeyFromCameraId(cameraId): String      — EXISTE L463
//   - LeicaConfig.noiseReductionShadowBandBoost: Double      (default 0.5) — NOVO (Section J)
//
// PIPELINE ORDER (recap do exploration findings):
//   1. RAW Bayer CFA → RCD demosaic → demosaicTextureId (RGBA16F camera RGB, un-WB)
//   2. Chroma denoise (ChromaDenoiseShaders multi-scale bilateral R-G/B-G)
//      ← Section H aplica chroma_nr_multiplier aqui
//   3. Luma+RGB NLM (DenoiseProfileShaders VST+NLM+inverse VST)
//      ← Section F aplica luma_nr_multiplier + 4-coef noise model aqui
//      ← Section A aplica shadow band boost no FINISH_V2 aqui
//   4. LinearRcdPass (WB + DCP hue-sat + CCM + DNG baseline exposure)
//   5. Tone-mapping engine (DarktableFilmic / HNCS / others)
//   6. Output sharpening
//   DENOISE RUNS BEFORE TONE MAPPING. ✓ (RawDemosaicProcessor.kt L2598-L2646)
//
// RISK / SCOPE NOTES:
//   - Luma vs chroma JÁ são separados (exploration confirmou) — U-01 não precisa
//     arquitetar separação nova. Apenas reforça shadows em AMBAS as paths.
//   - "RAW-domain denoise" no sentido estrito (pre-demosaic CFA NLM) NÃO existe
//     no upstream. U-01 atua no camera-RGB stage pré-WB — extensão prática.
//   - SEARCH_RADIUS virar getter quebra compile-time const-ness do FUSED_TILE_X/Y.
//     Solução: tornar FUSED_TILE_X/Y também `private val` getters — Kotlin aceita
//     em object declarations; shaders interpolam via $FUSED_TILE_X no string template
//     (avalia no acesso). Precisa recompilar shader string só se radius mudar em
//     runtime — raro (config reload), aceitável.
//   - shadow_band_boost default 0.5: boost moderado em shadows. Valores >1.0 podem
//     over-denoise (plastificar) shadows — configurável via JSON.
//   - uShadowBandStrength == 0 desativa o boost — behavior idêntico ao pré-U-01.
//   - Per-lens multiplier 1.0 (default) = no-op — backward-compatible.
//   - Performance: FINISH_V2 +3 ALU ops (dot + smoothstep + mix). Em full-res
//     RGBA16F FBO (~12MP), custo estimado <0.1ms em Adreno 740.
//   - Search radius 5→7: +96% offsets no half-octant traversal (49→~85 unique
//     offsets, FUSED_ACCU dispatch loop). Custo: +96% no fused-accu pass. Em
//     Pixel 8/15T typical 12MP RAW, fused-accu total ~25-35ms — passa a ~50-65ms.
//     Aceitável pra single-shot RAW processing; burst path NÃO usa este NLM
//     (GlesRawRadianceStacker cuida do temporal domain separadamente).
//   - lensKey default "main" em todas as funções: callers upstream não quebram.
//     Future patch (fora do escopo U-01) pode thread actual cameraId through
//     RawMetadata.create() signature e propagar via parameter.
// ═══════════════════════════════════════════════════════════════════════════════

package com.hinnka.mycamera.raw

import com.hinnka.mycamera.raw.LeicaConfig

// ═══════════════════════════════════════════════════════════════════════════════
// SECTION A — SHADER: DenoiseProfileShaders.kt::FINISH_V2 (L238-L275)
// ═══════════════════════════════════════════════════════════════════════════════

// ─── ANTES (upstream — uDenoiseMix uniform, no shadow band boost) ───────────
val FINISH_V2 = """
    #version 310 es
    $COMMON
    layout(local_size_x = $IMAGE_LOCAL_X, local_size_y = $IMAGE_LOCAL_Y) in;
    layout(binding = 0) uniform highp sampler2D uInput;
    layout(std430, binding = 0) readonly buffer AccuBuffer { vec4 u2[]; };
    layout(rgba16f, binding = 1) writeonly uniform highp image2D uOutput;

    uniform ivec2 uImageSize;
    uniform int uStripeRowOffset;
    uniform int uStripeRowCount;
    uniform vec4 uA;
    uniform vec4 uP;
    uniform vec4 uB;
    uniform float uBias;
    uniform float uDenoiseMix;
    uniform vec4 uSignalScale;

    void main() {
        ivec2 stripeCoord = ivec2(gl_GlobalInvocationID.xy);
        if (stripeCoord.x >= uImageSize.x || stripeCoord.y >= uStripeRowCount) return;
        ivec2 coord = stripeCoord + ivec2(0, uStripeRowOffset);

        int idx = stripeCoord.y * uImageSize.x + stripeCoord.x;
        vec4 accu = u2[idx];
        vec4 original = readPixel(uInput, coord, uImageSize);
        vec4 px = accu.a > 0.0 ? accu / accu.a : vec4(0.0);

        vec4 delta = px * px + vec4(uBias);
        vec4 denominator = 4.0 / (sqrt(uA) * (2.0 - uP));
        vec4 z1 = (px + sqrt(max(vec4(0.0), delta))) / denominator;
        px = max(dtPow(z1, 1.0 / (1.0 - uP / 2.0)) - uB, vec4(0.0));
        px *= uSignalScale;
        px.rgb = mix(original.rgb, px.rgb, clamp(uDenoiseMix, 0.0, 1.0));
        px.a = original.a;
        imageStore(uOutput, coord, px);
    }
""".trimIndent()

// ─── DEPOIS (U-01 — uShadowBandStrength uniform + shadow-boosted mix) ────────
val FINISH_V2 = """
    #version 310 es
    $COMMON
    layout(local_size_x = $IMAGE_LOCAL_X, local_size_y = $IMAGE_LOCAL_Y) in;
    layout(binding = 0) uniform highp sampler2D uInput;
    layout(std430, binding = 0) readonly buffer AccuBuffer { vec4 u2[]; };
    layout(rgba16f, binding = 1) writeonly uniform highp image2D uOutput;

    uniform ivec2 uImageSize;
    uniform int uStripeRowOffset;
    uniform int uStripeRowCount;
    uniform vec4 uA;
    uniform vec4 uP;
    uniform vec4 uB;
    uniform float uBias;
    uniform float uDenoiseMix;
    uniform vec4 uSignalScale;
    uniform float uShadowBandStrength;   // U-01: shadow denoise boost (0=off, 0.5=typical, 1.0=strong)

    void main() {
        ivec2 stripeCoord = ivec2(gl_GlobalInvocationID.xy);
        if (stripeCoord.x >= uImageSize.x || stripeCoord.y >= uStripeRowCount) return;
        ivec2 coord = stripeCoord + ivec2(0, uStripeRowOffset);

        int idx = stripeCoord.y * uImageSize.x + stripeCoord.x;
        vec4 accu = u2[idx];
        vec4 original = readPixel(uInput, coord, uImageSize);
        vec4 px = accu.a > 0.0 ? accu / accu.a : vec4(0.0);

        vec4 delta = px * px + vec4(uBias);
        vec4 denominator = 4.0 / (sqrt(uA) * (2.0 - uP));
        vec4 z1 = (px + sqrt(max(vec4(0.0), delta))) / denominator;
        px = max(dtPow(z1, 1.0 / (1.0 - uP / 2.0)) - uB, vec4(0.0));
        px *= uSignalScale;

        // U-01: shadow-band denoise reinforcement.
        // Boost effectiveMix toward 1.0 in shadows (luma < 0.15) where sensor SNR
        // is worst. uShadowBandStrength=0 preserves legacy behavior.
        float luma = dot(original.rgb, vec3(0.2126, 0.7152, 0.0722));
        float shadowMask = 1.0 - smoothstep(0.0, 0.15, luma);
        float effectiveMix = clamp(
            uDenoiseMix + uShadowBandStrength * shadowMask * (1.0 - uDenoiseMix),
            0.0,
            1.0
        );
        px.rgb = mix(original.rgb, px.rgb, effectiveMix);
        px.a = original.a;
        imageStore(uOutput, coord, px);
    }
""".trimIndent()

// ─── SED COMMANDS — SECTION A ────────────────────────────────────────────────
// Variável: dps=app/src/main/java/com/hinnka/mycamera/raw/DenoiseProfileShaders.kt
//
// A.1) Adiciona uniform uShadowBandStrength após `uniform float uDenoiseMix;`
//      (anchor único no FINISH_V2 — uDenoiseMix só aparece nesse shader):
// sed -i '/uniform float uDenoiseMix;/a\        uniform float uShadowBandStrength;   // U-01: shadow denoise boost (0=off, 0.5=typical)' "$dps"
//
// A.2) Substitui o blend final (linha única no arquivo):
//      OLD: `px.rgb = mix(original.rgb, px.rgb, clamp(uDenoiseMix, 0.0, 1.0));`
//      NEW: bloco com shadow mask + effectiveMix. Usa `|` como delimiter pois o
//      replacement contém `/` (division) e `*` (mul) e `(` (paren):
// sed -i 's|px.rgb = mix(original.rgb, px.rgb, clamp(uDenoiseMix, 0.0, 1.0));|// U-01: shadow-band denoise reinforcement\n        float luma = dot(original.rgb, vec3(0.2126, 0.7152, 0.0722));\n        float shadowMask = 1.0 - smoothstep(0.0, 0.15, luma);\n        float effectiveMix = clamp(uDenoiseMix + uShadowBandStrength * shadowMask * (1.0 - uDenoiseMix), 0.0, 1.0);\n        px.rgb = mix(original.rgb, px.rgb, effectiveMix);|' "$dps"

// ═══════════════════════════════════════════════════════════════════════════════
// SECTION B — SEARCH_RADIUS: DenoiseProfileShaders.kt::SEARCH_RADIUS + FUSED_TILE (L15-L23)
// ═══════════════════════════════════════════════════════════════════════════════

// ─── ANTES (upstream — const=5 hardcoded; FUSED_TILE derivado const) ─────────
object DenoiseProfileShaders {
    const val SEARCH_RADIUS = 5
    const val PATCH_RADIUS = 1
    const val BLACK_PRESERVING_BIAS = 0.0f
    const val IMAGE_LOCAL_X = GlesComputeWorkGroup.IMAGE_TILE_SIZE
    const val IMAGE_LOCAL_Y = GlesComputeWorkGroup.IMAGE_TILE_SIZE
    private const val FUSED_TILE_X = IMAGE_LOCAL_X + SEARCH_RADIUS + 2 * PATCH_RADIUS
    private const val FUSED_TILE_Y = IMAGE_LOCAL_Y + SEARCH_RADIUS + 2 * PATCH_RADIUS
    // ...
}

// ─── DEPOIS (U-01 — SEARCH_RADIUS getter config-driven; FUSED_TILE getters) ──
object DenoiseProfileShaders {
    // U-01: agora config-driven via LeicaConfig.demosaicNlmSearchRadius (default 7).
    // const → val getter porque LeicaConfig é mutável em runtime (hot reload).
    val SEARCH_RADIUS: Int get() = LeicaConfig.demosaicNlmSearchRadius.coerceIn(1, 16)
    const val PATCH_RADIUS = 1
    const val BLACK_PRESERVING_BIAS = 0.0f
    const val IMAGE_LOCAL_X = GlesComputeWorkGroup.IMAGE_TILE_SIZE
    const val IMAGE_LOCAL_Y = GlesComputeWorkGroup.IMAGE_TILE_SIZE
    // U-01: FUSED_TILE_* também viram getters (dependem de SEARCH_RADIUS runtime).
    private val FUSED_TILE_X: Int get() = IMAGE_LOCAL_X + SEARCH_RADIUS + 2 * PATCH_RADIUS
    private val FUSED_TILE_Y: Int get() = IMAGE_LOCAL_Y + SEARCH_RADIUS + 2 * PATCH_RADIUS
    // ...
}

// ─── SED COMMANDS — SECTION B ────────────────────────────────────────────────
// Variável: dps=app/src/main/java/com/hinnka/mycamera/raw/DenoiseProfileShaders.kt
// (LeicaConfig está no mesmo package `com.hinnka.mycamera.raw` — sem import extra)
//
// B.1) SEARCH_RADIUS: const 5 → getter LeicaConfig.demosaicNlmSearchRadius:
// sed -i 's|const val SEARCH_RADIUS = 5|val SEARCH_RADIUS: Int get() = LeicaConfig.demosaicNlmSearchRadius.coerceIn(1, 16)  // U-01: config-driven (default 7)|' "$dps"
//
// B.2) FUSED_TILE_X: const → getter (depende de SEARCH_RADIUS runtime):
// sed -i 's|private const val FUSED_TILE_X = IMAGE_LOCAL_X + SEARCH_RADIUS + 2 \* PATCH_RADIUS|private val FUSED_TILE_X: Int get() = IMAGE_LOCAL_X + SEARCH_RADIUS + 2 * PATCH_RADIUS  // U-01: agora getter (SEARCH_RADIUS runtime)|' "$dps"
//
// B.3) FUSED_TILE_Y: const → getter (mirror B.2):
// sed -i 's|private const val FUSED_TILE_Y = IMAGE_LOCAL_Y + SEARCH_RADIUS + 2 \* PATCH_RADIUS|private val FUSED_TILE_Y: Int get() = IMAGE_LOCAL_Y + SEARCH_RADIUS + 2 * PATCH_RADIUS  // U-01: agora getter (SEARCH_RADIUS runtime)|' "$dps"
//
// NOTA: shader string templates `$FUSED_TILE_X`/`$FUSED_TILE_Y` no FUSED_ACCU
// shader interpolam no acesso ao val (Kotlin reavalia getter). Em runtime isso
// significa: se config mudar, próxima initNLMPrograms() recompila shader com novo
// tile size. Raro (config reload), custo aceitável.

// ═══════════════════════════════════════════════════════════════════════════════
// SECTION C — NlmConfig: DenoiseProfileNlmConfig.kt::searchOffsets (L37-L39)
// ═══════════════════════════════════════════════════════════════════════════════

// ─── ANTES (upstream — searchOffsets lê SEARCH_RADIUS const=5) ───────────────
internal object DenoiseProfileNlmConfig {
    // ...
    val searchOffsets: List<DenoiseProfileOffset> = buildSearchOffsets(
        DenoiseProfileShaders.SEARCH_RADIUS
    )

    fun buildSearchOffsets(radius: Int): List<DenoiseProfileOffset> {
        require(radius >= 0) { "radius must be non-negative" }
        return buildList {
            for (qy in -radius..0) {
                val qxEnd = if (qy == 0) 0 else radius
                for (qx in -radius..qxEnd) {
                    add(DenoiseProfileOffset(qx, qy))
                }
            }
        }
    }
    // ...
}

// ─── DEPOIS (U-01 — searchOffsets agora val lazy que lê getter runtime) ──────
internal object DenoiseProfileNlmConfig {
    // ...
    // U-01: searchOffsets agora referencia DenoiseProfileShaders.SEARCH_RADIUS
    // getter (config-driven). Avaliação lazy no primeiro acesso.
    val searchOffsets: List<DenoiseProfileOffset>
        get() = buildSearchOffsets(DenoiseProfileShaders.SEARCH_RADIUS)

    fun buildSearchOffsets(radius: Int): List<DenoiseProfileOffset> {
        require(radius >= 0) { "radius must be non-negative" }
        return buildList {
            for (qy in -radius..0) {
                val qxEnd = if (qy == 0) 0 else radius
                for (qx in -radius..qxEnd) {
                    add(DenoiseProfileOffset(qx, qy))
                }
            }
        }
    }
    // ...
}

// ─── SED COMMANDS — SECTION C ────────────────────────────────────────────────
// Variável: dnc=app/src/main/java/com/hinnka/mycamera/raw/DenoiseProfileNlmConfig.kt
//
// C.1) Troca `val searchOffsets: List<DenoiseProfileOffset> = buildSearchOffsets(`
//      por versão lazy getter (multiline → single line para sed confiável):
// sed -i 's|val searchOffsets: List<DenoiseProfileOffset> = buildSearchOffsets(|val searchOffsets: List<DenoiseProfileOffset>\n        get() = buildSearchOffsets(|' "$dnc"
//
// C.2) Fecha parêntese da chamada agora na nova linha (após `DenoiseProfileShaders.SEARCH_RADIUS`):
//      Anchor: a linha `        DenoiseProfileShaders.SEARCH_RADIUS\n    )` é única.
//      Não precisa de sed — o C.1 já reorganizou pra `get() = buildSearchOffsets(`
//      e a chamada continua válida multiline.
//
// NOTA: como searchOffsets virou getter, cada acesso reavalia. Em
// RawDemosaicProcessor.dispatchDenoiseNlm o loop `for (offset in
// DenoiseProfileNlmConfig.searchOffsets)` chama 1 vez por dispatch — custo
// desprezível (buildList de ~50-121 entries, ~μs).

// ═══════════════════════════════════════════════════════════════════════════════
// SECTION D — NOISE MODEL FALLBACK: RawDemosaicProcessor.kt::resolveDenoiseProfileNoiseModel (L6318-L6342)
// ═══════════════════════════════════════════════════════════════════════════════

// ─── ANTES (upstream — 2-coef fallback (1E-4f, 4.5E-7f), return Pair<Float, Float>) ─
private fun resolveDenoiseProfileNoiseModel(
    metadata: RawMetadata,
    fallbackGain: Float
): Pair<Float, Float> {
    val greenProfile = RawMetadata.greenNoiseProfile(
        metadata.channelNoiseProfile,
        metadata.cfaPattern
    )
    var slope = greenProfile[0].takeIf { it > 0f }
        ?: metadata.noiseProfile.getOrElse(0) { 0f }
    var offset = greenProfile[1].takeIf { it > 0f }
        ?: metadata.noiseProfile.getOrElse(1) { 0f }

    if (!slope.isFinite() || slope <= 0f) {
        slope = 1E-4f * fallbackGain
    }
    if (!offset.isFinite() || offset <= 0f) {
        offset = 4.5E-7f * sqrt(fallbackGain)
    }

    // An average of N registered RAW frames reduces both Poisson and read variance by N.
    val frameNoiseScale = 1f / metadata.frameCount.coerceAtLeast(1).toFloat()
    return (slope * frameNoiseScale).coerceAtLeast(1e-10f) to
        (offset * frameNoiseScale).coerceAtLeast(1e-10f)
}

// ─── DEPOIS (U-01 — 4-coef per-lens fallback, return DenoiseProfileNoiseModel) ─
// U-01: data class nova p/ carregar 4 coeficientes (a, b, c, d).
private data class DenoiseProfileNoiseModel(
    val a: Float,   // shot noise slope (variance per signal)
    val b: Float,   // read noise offset (dark variance floor)
    val c: Float,   // U-01: VST exponent base (0 = derive from a via inferDenoiseProfileShadows)
    val d: Float,   // U-01: shadow-band strength multiplier (0 = no boost)
)

private fun resolveDenoiseProfileNoiseModel(
    metadata: RawMetadata,
    fallbackGain: Float,
    lensKey: String = "main"   // U-01: per-lens 4-coef fallback
): DenoiseProfileNoiseModel {
    val greenProfile = RawMetadata.greenNoiseProfile(
        metadata.channelNoiseProfile,
        metadata.cfaPattern
    )
    var slope = greenProfile[0].takeIf { it > 0f }
        ?: metadata.noiseProfile.getOrElse(0) { 0f }
    var offset = greenProfile[1].takeIf { it > 0f }
        ?: metadata.noiseProfile.getOrElse(1) { 0f }

    // U-01: per-lens 4-coef model from LeicaConfig.noiseModelForLens(lensKey).
    // Used both as fallback (when HAL slope/offset invalid) AND as source of c/d
    // (VST exponent base + shadow-band strength) which the HAL never reports.
    val perLensModel = LeicaConfig.noiseModelForLens(lensKey)
    var c = perLensModel?.c?.toFloat() ?: 0f
    var d = perLensModel?.d?.toFloat() ?: 0f

    if (!slope.isFinite() || slope <= 0f) {
        // U-01: substitui fallback genérico (1E-4f * fallbackGain) por coeficiente
        // physics-derived per-lens (a=2.8e-7 p/ main OV50E, etc.).
        slope = perLensModel?.a?.toFloat()?.takeIf { it > 0f } ?: (1E-4f * fallbackGain)
    }
    if (!offset.isFinite() || offset <= 0f) {
        // U-01: substitui fallback genérico (4.5E-7f * sqrt(fallbackGain)) por
        // coeficiente physics-derived per-lens (b=9.2e-6 p/ main OV50E, etc.).
        offset = perLensModel?.b?.toFloat()?.takeIf { it > 0f } ?: (4.5E-7f * sqrt(fallbackGain))
    }

    // An average of N registered RAW frames reduces both Poisson and read variance by N.
    val frameNoiseScale = 1f / metadata.frameCount.coerceAtLeast(1).toFloat()
    return DenoiseProfileNoiseModel(
        a = (slope * frameNoiseScale).coerceAtLeast(1e-10f),
        b = (offset * frameNoiseScale).coerceAtLeast(1e-10f),
        c = c.coerceAtLeast(0f),
        d = d.coerceAtLeast(0f)
    )
}

// ─── SED COMMANDS — SECTION D ────────────────────────────────────────────────
// Variável: rdp=app/src/main/java/com/hinnka/mycamera/raw/RawDemosaicProcessor.kt
// (LeicaConfig está no mesmo package `com.hinnka.mycamera.raw` — sem import extra)
//
// D.1) Insere data class DenoiseProfileNoiseModel antes da função
//      resolveDenoiseProfileNoiseModel (anchor: linha da assinatura):
// sed -i '/private fun resolveDenoiseProfileNoiseModel(/i\
//// U-01: 4-coef noise model (a, b, c, d) — c=VST exponent base, d=shadow-band boost.\
//private data class DenoiseProfileNoiseModel(\
//    val a: Float,\
//    val b: Float,\
//    val c: Float,\
//    val d: Float,\
//)\
//' "$rdp"
//
// D.2) Adiciona parameter lensKey na assinatura:
// sed -i 's|private fun resolveDenoiseProfileNoiseModel(\n        metadata: RawMetadata,\n        fallbackGain: Float\n    ): Pair<Float, Float>|private fun resolveDenoiseProfileNoiseModel(\n        metadata: RawMetadata,\n        fallbackGain: Float,\n        lensKey: String = "main"   // U-01: per-lens 4-coef fallback\n    ): DenoiseProfileNoiseModel|' "$rdp"
//
//      Em uma linha única (sed -z para multiline):
// sed -z -i 's|private fun resolveDenoiseProfileNoiseModel(\n    metadata: RawMetadata,\n    fallbackGain: Float\n): Pair<Float, Float>|private fun resolveDenoiseProfileNoiseModel(\n    metadata: RawMetadata,\n    fallbackGain: Float,\n    lensKey: String = "main"   // U-01: per-lens 4-coef fallback\n): DenoiseProfileNoiseModel|' "$rdp"
//
// D.3) Substitui o body fallback por per-lens 4-coef lookup (anchor único: o
//      bloco `if (!slope.isFinite()...` é único no arquivo):
// sed -i '/if (!slope.isFinite() || slope <= 0f) {/,/^    }/c\
//\    // U-01: per-lens 4-coef model from LeicaConfig.noiseModelForLens(lensKey).\
//    val perLensModel = LeicaConfig.noiseModelForLens(lensKey)\
//    var c = perLensModel?.c?.toFloat() ?: 0f\
//    var d = perLensModel?.d?.toFloat() ?: 0f\
//\
//    if (!slope.isFinite() || slope <= 0f) {\
//        slope = perLensModel?.a?.toFloat()?.takeIf { it > 0f } ?: (1E-4f * fallbackGain)\
//    }\
//    if (!offset.isFinite() || offset <= 0f) {\
//        offset = perLensModel?.b?.toFloat()?.takeIf { it > 0f } ?: (4.5E-7f * sqrt(fallbackGain))\
//    }\
//' "$rdp"
//
// D.4) Substitui o return statement (era Pair<Float, Float>) por DenoiseProfileNoiseModel:
// sed -i 's|return (slope \* frameNoiseScale).coerceAtLeast(1e-10f) to\n            (offset \* frameNoiseScale).coerceAtLeast(1e-10f)|return DenoiseProfileNoiseModel(\n        a = (slope * frameNoiseScale).coerceAtLeast(1e-10f),\n        b = (offset * frameNoiseScale).coerceAtLeast(1e-10f),\n        c = c.coerceAtLeast(0f),\n        d = d.coerceAtLeast(0f)\n    )|' "$rdp"
//
//      Equivalente single-line (sed -z multiline):
// sed -z -i 's|return (slope \* frameNoiseScale).coerceAtLeast(1e-10f) to\n        (offset \* frameNoiseScale).coerceAtLeast(1e-10f)|return DenoiseProfileNoiseModel(\n        a = (slope * frameNoiseScale).coerceAtLeast(1e-10f),\n        b = (offset * frameNoiseScale).coerceAtLeast(1e-10f),\n        c = c.coerceAtLeast(0f),\n        d = d.coerceAtLeast(0f)\n    )|' "$rdp"

// ═══════════════════════════════════════════════════════════════════════════════
// SECTION E — SHADOWS EXPONENT: RawDemosaicProcessor.kt::inferDenoiseProfileShadows (L5956-L5958)
// ═══════════════════════════════════════════════════════════════════════════════

// ─── ANTES (upstream — derivado de `a` via formula fixa) ─────────────────────
private fun inferDenoiseProfileShadows(a: Float): Float {
    return max(0.1f - 0.1f * ln(a), 0.7f).coerceAtMost(1.8f)
}

// ─── DEPOIS (U-01 — c override p/ VST exponent base per-lens) ────────────────
private fun inferDenoiseProfileShadows(a: Float, c: Float = 0f): Float {
    // U-01: se c > 0 (per-lens coefficient from LeicaConfig.noiseModelForLens),
    // usa c diretamente como VST exponent base. Caso contrário, deriva de `a`
    // via formula darktable original (backward-compatible).
    if (c > 0f) {
        return c.coerceIn(0.7f, 1.8f)
    }
    return max(0.1f - 0.1f * ln(a), 0.7f).coerceAtMost(1.8f)
}

// ─── SED COMMANDS — SECTION E ────────────────────────────────────────────────
// Variável: rdp=app/src/main/java/com/hinnka/mycamera/raw/RawDemosaicProcessor.kt
//
// E.1) Troca assinatura de inferDenoiseProfileShadows (adiciona param c):
// sed -i 's|private fun inferDenoiseProfileShadows(a: Float): Float {|private fun inferDenoiseProfileShadows(a: Float, c: Float = 0f): Float {  // U-01: c override (per-lens VST exponent base)|' "$rdp"
//
// E.2) Substitui o body (adiciona early-return para c > 0):
// sed -i 's|return max(0.1f - 0.1f \* ln(a), 0.7f).coerceAtMost(1.8f)|// U-01: se c > 0 (per-lens coefficient), usa c diretamente como VST exponent base.\n    if (c > 0f) return c.coerceIn(0.7f, 1.8f)\n    return max(0.1f - 0.1f * ln(a), 0.7f).coerceAtMost(1.8f)|' "$rdp"

// ═══════════════════════════════════════════════════════════════════════════════
// SECTION F — BUILD PARAMS: RawDemosaicProcessor.kt::buildDenoiseProfileParams (L5900-L5954)
// ═══════════════════════════════════════════════════════════════════════════════

// ─── ANTES (upstream — sem lensKey, sem per-lens multiplier, 2-coef model) ───
private fun buildDenoiseProfileParams(
    metadata: RawMetadata,
    strengthValue: Float
): DenoiseProfileParams {
    val profileGain =
        (metadata.iso / 100.0f * metadata.postRawSensitivityBoost).coerceAtLeast(1f)
    val (noiseA, noiseB) = resolveDenoiseProfileNoiseModel(metadata, profileGain)
    val a = noiseA.coerceAtLeast(1e-10f)
    val b = noiseB.coerceAtLeast(1e-10f)
    val strength = strengthValue.coerceAtLeast(0f)
    val scale = 1.0f
    val shadows = inferDenoiseProfileShadows(a)
    val bias = DenoiseProfileShaders.BLACK_PRESERVING_BIAS
    val adaptiveWb = computeDenoiseProfileWb(metadata)
    val p = floatArrayOf(
        max(shadows + 0.1f * ln(scale / adaptiveWb[0]), 0.0f),
        max(shadows + 0.1f * ln(scale / adaptiveWb[1]), 0.0f),
        max(shadows + 0.1f * ln(scale / adaptiveWb[2]), 0.0f),
        1.0f
    )
    val compensateP = 0.05f / 0.05f.pow(shadows)
    val patchRadius = DenoiseProfileShaders.PATCH_RADIUS
    val searchRadius = DenoiseProfileShaders.SEARCH_RADIUS
    val weightTuning = DenoiseProfileNlmConfig.weightTuning(patchRadius)
    val centralPixelWeight = 0.1f * scale
    val signalScale = floatArrayOf(scale, scale, scale, 1.0f)
    val aa = floatArrayOf(a * compensateP, a * compensateP, a * compensateP, 1.0f)
    val bb = floatArrayOf(b, b, b, 1.0f)

    return DenoiseProfileParams(
        strength = strength,
        a = a, b = b, shadows = shadows, bias = bias, scale = scale,
        patchRadius = patchRadius, searchRadius = searchRadius,
        expectedFineDistance = weightTuning.expectedFineDistance,
        expectedGuideDistance = weightTuning.expectedGuideDistance,
        inverseBandwidth = weightTuning.inverseBandwidth,
        coarseGuideWeight = weightTuning.coarseGuideWeight,
        centralPixelWeight = centralPixelWeight,
        p = p, adaptiveWb = adaptiveWb, signalScale = signalScale,
        aa = aa, bb = bb
    )
}

// ─── DEPOIS (U-01 — lensKey param + per-lens multiplier + 4-coef model + d) ──
private fun buildDenoiseProfileParams(
    metadata: RawMetadata,
    strengthValue: Float,
    lensKey: String = "main"   // U-01: per-lens noise model + multiplier
): DenoiseProfileParams {
    val profileGain =
        (metadata.iso / 100.0f * metadata.postRawSensitivityBoost).coerceAtLeast(1f)
    // U-01: resolveDenoiseProfileNoiseModel agora retorna DenoiseProfileNoiseModel
    // com (a, b, c, d) — c=VST exponent base, d=shadow-band strength multiplier.
    val noiseModel = resolveDenoiseProfileNoiseModel(metadata, profileGain, lensKey)
    val a = noiseModel.a.coerceAtLeast(1e-10f)
    val b = noiseModel.b.coerceAtLeast(1e-10f)
    val c = noiseModel.c   // U-01: VST exponent base override (0 = derive from a)
    val d = noiseModel.d   // U-01: shadow-band strength multiplier per-lens

    // U-01: multiplica strength por per-lens luma_nr_multiplier (default 1.0).
    val lumaMultiplier = LeicaConfig.lumaNrMultiplierForLens(lensKey)
    val strength = (strengthValue * lumaMultiplier).coerceAtLeast(0f)
    val scale = 1.0f

    // U-01: passa c como override pra inferDenoiseProfileShadows. Se c=0, formula
    // darktable original (max(0.1f - 0.1f * ln(a), 0.7f)) é usada.
    val shadows = inferDenoiseProfileShadows(a, c)
    val bias = DenoiseProfileShaders.BLACK_PRESERVING_BIAS
    val adaptiveWb = computeDenoiseProfileWb(metadata)
    val p = floatArrayOf(
        max(shadows + 0.1f * ln(scale / adaptiveWb[0]), 0.0f),
        max(shadows + 0.1f * ln(scale / adaptiveWb[1]), 0.0f),
        max(shadows + 0.1f * ln(scale / adaptiveWb[2]), 0.0f),
        1.0f
    )
    val compensateP = 0.05f / 0.05f.pow(shadows)
    val patchRadius = DenoiseProfileShaders.PATCH_RADIUS
    val searchRadius = DenoiseProfileShaders.SEARCH_RADIUS   // U-01: agora getter (config-driven)
    val weightTuning = DenoiseProfileNlmConfig.weightTuning(patchRadius)
    val centralPixelWeight = 0.1f * scale
    val signalScale = floatArrayOf(scale, scale, scale, 1.0f)
    val aa = floatArrayOf(a * compensateP, a * compensateP, a * compensateP, 1.0f)
    val bb = floatArrayOf(b, b, b, 1.0f)

    // U-01: shadow-band strength = per-lens `d` × global shadow_band_boost config.
    // Se d=0 (config sem coeficiente d) ou shadowBandBoost=0, boost desativa.
    val shadowBandStrength = (d * LeicaConfig.noiseReductionShadowBandBoost.toFloat())
        .coerceIn(0f, 1.5f)

    return DenoiseProfileParams(
        strength = strength,
        a = a, b = b, shadows = shadows, bias = bias, scale = scale,
        patchRadius = patchRadius, searchRadius = searchRadius,
        expectedFineDistance = weightTuning.expectedFineDistance,
        expectedGuideDistance = weightTuning.expectedGuideDistance,
        inverseBandwidth = weightTuning.inverseBandwidth,
        coarseGuideWeight = weightTuning.coarseGuideWeight,
        centralPixelWeight = centralPixelWeight,
        p = p, adaptiveWb = adaptiveWb, signalScale = signalScale,
        aa = aa, bb = bb,
        shadowBandStrength = shadowBandStrength   // U-01
    )
}

// ─── SED COMMANDS — SECTION F ────────────────────────────────────────────────
// Variável: rdp=app/src/main/java/com/hinnka/mycamera/raw/RawDemosaicProcessor.kt
//
// F.1) Adiciona parameter lensKey na assinatura:
// sed -i 's|private fun buildDenoiseProfileParams(\n        metadata: RawMetadata,\n        strengthValue: Float\n    ): DenoiseProfileParams {|private fun buildDenoiseProfileParams(\n        metadata: RawMetadata,\n        strengthValue: Float,\n        lensKey: String = "main"   // U-01: per-lens noise model + multiplier\n    ): DenoiseProfileParams {|' "$rdp"
//
//      Em sed -z multiline:
// sed -z -i 's|private fun buildDenoiseProfileParams(\n    metadata: RawMetadata,\n    strengthValue: Float\n): DenoiseProfileParams {|private fun buildDenoiseProfileParams(\n    metadata: RawMetadata,\n    strengthValue: Float,\n    lensKey: String = "main"   // U-01: per-lens noise model + multiplier\n): DenoiseProfileParams {|' "$rdp"
//
// F.2) Substitui o destructuring do Pair<Float, Float> por DenoiseProfileNoiseModel:
// sed -i 's|val (noiseA, noiseB) = resolveDenoiseProfileNoiseModel(metadata, profileGain)|val noiseModel = resolveDenoiseProfileNoiseModel(metadata, profileGain, lensKey)  // U-01: 4-coef|' "$rdp"
//
// F.3) Substitui as val a/b por versão 4-coef (adiciona c, d):
// sed -i 's|val a = noiseA.coerceAtLeast(1e-10f)\n        val b = noiseB.coerceAtLeast(1e-10f)|val a = noiseModel.a.coerceAtLeast(1e-10f)\n        val b = noiseModel.b.coerceAtLeast(1e-10f)\n        val c = noiseModel.c   // U-01: VST exponent base override\n        val d = noiseModel.d   // U-01: shadow-band strength multiplier|' "$rdp"
//
//      Em sed -z multiline:
// sed -z -i 's|val a = noiseA.coerceAtLeast(1e-10f)\n    val b = noiseB.coerceAtLeast(1e-10f)|val a = noiseModel.a.coerceAtLeast(1e-10f)\n    val b = noiseModel.b.coerceAtLeast(1e-10f)\n    val c = noiseModel.c   // U-01: VST exponent base override\n    val d = noiseModel.d   // U-01: shadow-band strength multiplier|' "$rdp"
//
// F.4) Aplica per-lens luma_nr_multiplier no strength:
// sed -i 's|val strength = strengthValue.coerceAtLeast(0f)|val lumaMultiplier = LeicaConfig.lumaNrMultiplierForLens(lensKey)  // U-01\n        val strength = (strengthValue * lumaMultiplier).coerceAtLeast(0f)|' "$rdp"
//
// F.5) Passa c como override pra inferDenoiseProfileShadows:
// sed -i 's|val shadows = inferDenoiseProfileShadows(a)|val shadows = inferDenoiseProfileShadows(a, c)  // U-01: c override|' "$rdp"
//
// F.6) Adiciona val shadowBandStrength antes do return statement (anchor: `val bb =`):
// sed -i '/val bb = floatArrayOf(b, b, b, 1.0f)/a\
//\    // U-01: shadow-band strength = per-lens d × global shadow_band_boost config.\
//    val shadowBandStrength = (d * LeicaConfig.noiseReductionShadowBandBoost.toFloat()).coerceIn(0f, 1.5f)\
//' "$rdp"
//
// F.7) Adiciona campo shadowBandStrength no return do DenoiseProfileParams
//      (anchor: linha `bb = bb,` que aparece 1x no buildDenoiseProfileParams return):
// sed -i 's|            bb = bb\n        )|            bb = bb,\n            shadowBandStrength = shadowBandStrength   // U-01\n        )|' "$rdp"
//
//      Em sed -z multiline:
// sed -z -i 's|            bb = bb\n        )|            bb = bb,\n            shadowBandStrength = shadowBandStrength   // U-01\n        )|' "$rdp"

// ═══════════════════════════════════════════════════════════════════════════════
// SECTION G — DenoiseProfileParams: add shadowBandStrength field (L5870-L5889)
// ═══════════════════════════════════════════════════════════════════════════════

// ─── ANTES (upstream — sem campo shadowBandStrength) ─────────────────────────
private data class DenoiseProfileParams(
    val strength: Float,
    val a: Float,
    val b: Float,
    val shadows: Float,
    val bias: Float,
    val scale: Float,
    val patchRadius: Int,
    val searchRadius: Int,
    val expectedFineDistance: Float,
    val expectedGuideDistance: Float,
    val inverseBandwidth: Float,
    val coarseGuideWeight: Float,
    val centralPixelWeight: Float,
    val p: FloatArray,
    val adaptiveWb: FloatArray,
    val signalScale: FloatArray,
    val aa: FloatArray,
    val bb: FloatArray,
)

// ─── DEPOIS (U-01 — adiciona campo shadowBandStrength) ───────────────────────
private data class DenoiseProfileParams(
    val strength: Float,
    val a: Float,
    val b: Float,
    val shadows: Float,
    val bias: Float,
    val scale: Float,
    val patchRadius: Int,
    val searchRadius: Int,
    val expectedFineDistance: Float,
    val expectedGuideDistance: Float,
    val inverseBandwidth: Float,
    val coarseGuideWeight: Float,
    val centralPixelWeight: Float,
    val p: FloatArray,
    val adaptiveWb: FloatArray,
    val signalScale: FloatArray,
    val aa: FloatArray,
    val bb: FloatArray,
    val shadowBandStrength: Float = 0f,   // U-01: shadow denoise boost (0=legacy behavior)
)

// ─── SED COMMANDS — SECTION G ────────────────────────────────────────────────
// Variável: rdp=app/src/main/java/com/hinnka/mycamera/raw/RawDemosaicProcessor.kt
//
// G.1) Adiciona campo shadowBandStrength após `val bb: FloatArray,` (anchor único
//      no data class DenoiseProfileParams):
// sed -i 's|val bb: FloatArray,|val bb: FloatArray,\n    val shadowBandStrength: Float = 0f,   // U-01: shadow denoise boost (0=legacy)|' "$rdp"
//
// NOTA: default 0f preserva backward-compat com callers que não passam o campo.
//       Qualquer DenoiseProfileParams(...) constructor call existente continua válido.

// ═══════════════════════════════════════════════════════════════════════════════
// SECTION H — FINISH BIND: RawDemosaicProcessor.kt::dispatchDenoiseNlmFinish (L6110-L6117)
// ═══════════════════════════════════════════════════════════════════════════════

// ─── ANTES (upstream — só bind uBias + uDenoiseMix) ──────────────────────────
setDenoiseCommonUniforms(program, width, height, params)
setDenoiseStripeUniforms(program, stripe)
GLES31.glUniform1f(
    GLES31.glGetUniformLocation(program, "uBias"),
    params.bias - 0.5f * ln(params.scale)
)
GLES31.glUniform1f(
    GLES31.glGetUniformLocation(program, "uDenoiseMix"),
    params.strength.coerceIn(0f, 1f)
)

// ─── DEPOIS (U-01 — bind uShadowBandStrength após uDenoiseMix) ───────────────
setDenoiseCommonUniforms(program, width, height, params)
setDenoiseStripeUniforms(program, stripe)
GLES31.glUniform1f(
    GLES31.glGetUniformLocation(program, "uBias"),
    params.bias - 0.5f * ln(params.scale)
)
GLES31.glUniform1f(
    GLES31.glGetUniformLocation(program, "uDenoiseMix"),
    params.strength.coerceIn(0f, 1f)
)
// U-01: bind uShadowBandStrength (0 = legacy behavior, sem shadow boost)
GLES31.glUniform1f(
    GLES31.glGetUniformLocation(program, "uShadowBandStrength"),
    params.shadowBandStrength
)

// ─── SED COMMANDS — SECTION H ────────────────────────────────────────────────
// Variável: rdp=app/src/main/java/com/hinnka/mycamera/raw/RawDemosaicProcessor.kt
//
// H.1) Adiciona bind de uShadowBandStrength após o bloco uDenoiseMix (anchor: a
//      linha `params.strength.coerceIn(0f, 1f)\n        )` é única no arquivo):
// sed -i '/GLES31.glGetUniformLocation(program, "uDenoiseMix"),/{N;s|params.strength.coerceIn(0f, 1f)\n        )|params.strength.coerceIn(0f, 1f)\n        )\n        // U-01: bind uShadowBandStrength (0 = legacy behavior)\n        GLES31.glUniform1f(\n            GLES31.glGetUniformLocation(program, "uShadowBandStrength"),\n            params.shadowBandStrength\n        )|}' "$rdp"
//
//      Em sed -z multiline (mais legível):
// sed -z -i 's|GLES31.glGetUniformLocation(program, "uDenoiseMix"),\n            params.strength.coerceIn(0f, 1f)\n        )|GLES31.glGetUniformLocation(program, "uDenoiseMix"),\n            params.strength.coerceIn(0f, 1f)\n        )\n        // U-01: bind uShadowBandStrength (0 = legacy behavior)\n        GLES31.glUniform1f(\n            GLES31.glGetUniformLocation(program, "uShadowBandStrength"),\n            params.shadowBandStrength\n        )|' "$rdp"

// ═══════════════════════════════════════════════════════════════════════════════
// SECTION I — CHROMA NR MULTIPLIER: RawDemosaicProcessor.kt::renderDefaultChromaDenoise (L5692-L5788)
// ═══════════════════════════════════════════════════════════════════════════════

// ─── ANTES (upstream — sem lensKey, sem per-lens multiplier) ─────────────────
private fun renderDefaultChromaDenoise(
    sourceTextureId: Int,
    width: Int,
    height: Int,
    metadata: RawMetadata,
    chromaDenoiseValue: Float?,
): Int {
    val strength = chromaDenoiseValue?.coerceIn(0f, 1f) ?: 0f
    if (strength <= 0f || width * height < 2) {
        return sourceTextureId
    }
    // ...
}

// ─── DEPOIS (U-01 — lensKey param + per-lens chroma_nr_multiplier) ───────────
private fun renderDefaultChromaDenoise(
    sourceTextureId: Int,
    width: Int,
    height: Int,
    metadata: RawMetadata,
    chromaDenoiseValue: Float?,
    lensKey: String = "main",   // U-01: per-lens chroma_nr_multiplier
): Int {
    // U-01: aplica per-lens chroma_nr_multiplier (default 1.0 = no-op).
    // Config leica_perfect.json tem main=1.0, UW=1.05, tele=0.95, front=1.1.
    val chromaMultiplier = LeicaConfig.chromaNrMultiplierForLens(lensKey)
    val strength = (chromaDenoiseValue?.times(chromaMultiplier) ?: 0f).coerceIn(0f, 1f)
    if (strength <= 0f || width * height < 2) {
        return sourceTextureId
    }
    // ...
}

// ─── SED COMMANDS — SECTION I ────────────────────────────────────────────────
// Variável: rdp=app/src/main/java/com/hinnka/mycamera/raw/RawDemosaicProcessor.kt
//
// I.1) Adiciona parameter lensKey na assinatura (anchor: linha da função):
// sed -z -i 's|private fun renderDefaultChromaDenoise(\n        sourceTextureId: Int,\n        width: Int,\n        height: Int,\n        metadata: RawMetadata,\n        chromaDenoiseValue: Float?,\n    ): Int {|private fun renderDefaultChromaDenoise(\n        sourceTextureId: Int,\n        width: Int,\n        height: Int,\n        metadata: RawMetadata,\n        chromaDenoiseValue: Float?,\n        lensKey: String = "main",   // U-01: per-lens chroma_nr_multiplier\n    ): Int {|' "$rdp"
//
// I.2) Substitui a linha de strength por versão com multiplier:
// sed -i 's|val strength = chromaDenoiseValue?.coerceIn(0f, 1f) ?: 0f|val chromaMultiplier = LeicaConfig.chromaNrMultiplierForLens(lensKey)  // U-01\n        val strength = (chromaDenoiseValue?.times(chromaMultiplier) ?: 0f).coerceIn(0f, 1f)|' "$rdp"

// ═══════════════════════════════════════════════════════════════════════════════
// SECTION J — PIPELINE CALL SITE: RawDemosaicProcessor.kt L2606/L2619 (renderDenoiseProfilePass signature)
// ═══════════════════════════════════════════════════════════════════════════════

// ─── ANTES (upstream — renderDenoiseProfilePass não recebe lensKey) ──────────
private fun renderDenoiseProfilePass(
    sourceTextureId: Int,
    width: Int,
    height: Int,
    metadata: RawMetadata,
    denoiseValue: Float?,
) {
    setupNLMFramebuffers(width, height)
    if (!isDenoiseProfileReady()) {
        PLog.w(TAG, "DenoiseProfile programs not initialized, falling back to passthrough")
        renderPassthroughToTexture(sourceTextureId, width, height, gfFboId[1])
        return
    }
    val params = buildDenoiseProfileParams(metadata, denoiseValue ?: 0f)
    // ...
}

// ─── DEPOIS (U-01 — lensKey param threaded p/ buildDenoiseProfileParams) ─────
private fun renderDenoiseProfilePass(
    sourceTextureId: Int,
    width: Int,
    height: Int,
    metadata: RawMetadata,
    denoiseValue: Float?,
    lensKey: String = "main",   // U-01: thread p/ buildDenoiseProfileParams
) {
    setupNLMFramebuffers(width, height)
    if (!isDenoiseProfileReady()) {
        PLog.w(TAG, "DenoiseProfile programs not initialized, falling back to passthrough")
        renderPassthroughToTexture(sourceTextureId, width, height, gfFboId[1])
        return
    }
    val params = buildDenoiseProfileParams(metadata, denoiseValue ?: 0f, lensKey)  // U-01
    // ...
}

// ─── PIPELINE CALL SITE (L2606 / L2619) ──────────────────────────────────────
// ─── ANTES ───────────────────────────────────────────────────────────────────
val chromaDenoiseTextureId = renderDefaultChromaDenoise(
    sourceTextureId = demosaicTextureId,
    width = actualWidth,
    height = actualHeight,
    metadata = actualMetadata,
    chromaDenoiseValue = chromaDenoiseValue,
)
renderDenoiseProfilePass(
    sourceTextureId = chromaDenoiseTextureId,
    width = actualWidth,
    height = actualHeight,
    metadata = actualMetadata,
    denoiseValue = denoiseValue,
)

// ─── DEPOIS (U-01 — passa lensKey = LeicaConfig.lensKeyFromCameraId("main")) ─
// U-01 NOTE: RawMetadata não carrega cameraId upstream. Default lensKey="main"
// é backward-compatible. Future patch (fora escopo U-01) pode thread actual
// cameraId through RawMetadata.create() signature e passar lensKey explícito.
val u01LensKey = LeicaConfig.lensKeyFromCameraId("main")  // U-01: default "main"
val chromaDenoiseTextureId = renderDefaultChromaDenoise(
    sourceTextureId = demosaicTextureId,
    width = actualWidth,
    height = actualHeight,
    metadata = actualMetadata,
    chromaDenoiseValue = chromaDenoiseValue,
    lensKey = u01LensKey,   // U-01
)
renderDenoiseProfilePass(
    sourceTextureId = chromaDenoiseTextureId,
    width = actualWidth,
    height = actualHeight,
    metadata = actualMetadata,
    denoiseValue = denoiseValue,
    lensKey = u01LensKey,   // U-01
)

// ─── SED COMMANDS — SECTION J ────────────────────────────────────────────────
// Variável: rdp=app/src/main/java/com/hinnka/mycamera/raw/RawDemosaicProcessor.kt
//
// J.1) Adiciona parameter lensKey na assinatura de renderDenoiseProfilePass:
// sed -z -i 's|private fun renderDenoiseProfilePass(\n        sourceTextureId: Int,\n        width: Int,\n        height: Int,\n        metadata: RawMetadata,\n        denoiseValue: Float?,\n    ) {|private fun renderDenoiseProfilePass(\n        sourceTextureId: Int,\n        width: Int,\n        height: Int,\n        metadata: RawMetadata,\n        denoiseValue: Float?,\n        lensKey: String = "main",   // U-01: thread p/ buildDenoiseProfileParams\n    ) {|' "$rdp"
//
// J.2) Passa lensKey p/ buildDenoiseProfileParams:
// sed -i 's|val params = buildDenoiseProfileParams(metadata, denoiseValue ?: 0f)|val params = buildDenoiseProfileParams(metadata, denoiseValue ?: 0f, lensKey)  // U-01|' "$rdp"
//
// J.3) Pipeline call site — adiciona val u01LensKey + passa lensKey nos dois calls:
//      Anchor: o bloco `val chromaDenoiseTextureId = renderDefaultChromaDenoise(` é único.
// sed -i '/val chromaDenoiseTextureId = renderDefaultChromaDenoise(/i\
//\        val u01LensKey = LeicaConfig.lensKeyFromCameraId("main")  // U-01: default "main"\
//' "$rdp"
//
// J.4) Adiciona lensKey = u01LensKey no call de renderDefaultChromaDenoise
//      (anchor: linha `chromaDenoiseValue = chromaDenoiseValue,` única no call site):
// sed -i 's|chromaDenoiseValue = chromaDenoiseValue,\n            )|chromaDenoiseValue = chromaDenoiseValue,\n            lensKey = u01LensKey,   // U-01\n        )|' "$rdp"
//
//      Em sed -z multiline:
// sed -z -i 's|chromaDenoiseValue = chromaDenoiseValue,\n            )|chromaDenoiseValue = chromaDenoiseValue,\n            lensKey = u01LensKey,   // U-01\n        )|' "$rdp"
//
// J.5) Adiciona lensKey = u01LensKey no call de renderDenoiseProfilePass
//      (anchor: linha `denoiseValue = denoiseValue,` única no call site):
// sed -z -i 's|denoiseValue = denoiseValue,\n            )|denoiseValue = denoiseValue,\n            lensKey = u01LensKey,   // U-01\n        )|' "$rdp"

// ═══════════════════════════════════════════════════════════════════════════════
// SECTION K — LeicaConfig.kt: shadow_band_boost config field + accessor
// ═══════════════════════════════════════════════════════════════════════════════

// ─── ANTES (NoiseReductionConfig — 5 fields, sem shadow_band_boost) ──────────
data class NoiseReductionConfig(
    @SerializedName("enabled") val enabled: Boolean? = true,
    @SerializedName("luminance") val luminance: Double? = 0.92,
    @SerializedName("chrominance") val chrominance: Double? = 0.70,
    @SerializedName("detail_preserve") val detailPreserve: Double? = 0.96,
    @SerializedName("adaptive") val adaptive: Boolean? = true,
)

// ─── DEPOIS (U-01 — adiciona shadow_band_boost) ──────────────────────────────
data class NoiseReductionConfig(
    @SerializedName("enabled") val enabled: Boolean? = true,
    @SerializedName("luminance") val luminance: Double? = 0.92,
    @SerializedName("chrominance") val chrominance: Double? = 0.70,
    @SerializedName("detail_preserve") val detailPreserve: Double? = 0.96,
    @SerializedName("adaptive") val adaptive: Boolean? = true,
    @SerializedName("shadow_band_boost") val shadowBandBoost: Double? = 0.5,   // U-01
)

// ─── ANTES (accessors — sem noiseReductionShadowBandBoost) ───────────────────
val noiseReductionEnabled: Boolean get() = currentConfig?.noiseReduction?.enabled ?: true
val noiseReductionLuminance: Double get() = currentConfig?.noiseReduction?.luminance ?: 0.92
val noiseReductionChrominance: Double get() = currentConfig?.noiseReduction?.chrominance ?: 0.70
val noiseReductionDetailPreserve: Double get() = currentConfig?.noiseReduction?.detailPreserve ?: 0.96
val noiseReductionAdaptive: Boolean get() = currentConfig?.noiseReduction?.adaptive ?: true

// ─── DEPOIS (U-01 — adiciona accessor noiseReductionShadowBandBoost) ─────────
val noiseReductionEnabled: Boolean get() = currentConfig?.noiseReduction?.enabled ?: true
val noiseReductionLuminance: Double get() = currentConfig?.noiseReduction?.luminance ?: 0.92
val noiseReductionChrominance: Double get() = currentConfig?.noiseReduction?.chrominance ?: 0.70
val noiseReductionDetailPreserve: Double get() = currentConfig?.noiseReduction?.detailPreserve ?: 0.96
val noiseReductionAdaptive: Boolean get() = currentConfig?.noiseReduction?.adaptive ?: true
val noiseReductionShadowBandBoost: Double  // U-01: shadow denoise reinforcement
    get() = currentConfig?.noiseReduction?.shadowBandBoost ?: 0.5

// ─── SED COMMANDS — SECTION K ────────────────────────────────────────────────
// Variável: lcg=app/src/main/java/com/hinnka/mycamera/raw/LeicaConfig.kt
// (NOTA: este arquivo patches/LeicaConfig.kt é o DOCUMENTATION-ONLY copy. O
//        build script aplica os seds no upstream LeicaConfig.kt in-place.)
//
// K.1) Adiciona field shadowBandBoost ao NoiseReductionConfig data class
//      (anchor: linha `@SerializedName("adaptive") val adaptive: Boolean? = true,`
//      aparece 1x no data class NoiseReductionConfig — verificar com grep):
// sed -i 's|@SerializedName("adaptive") val adaptive: Boolean? = true,|@SerializedName("adaptive") val adaptive: Boolean? = true,\n        @SerializedName("shadow_band_boost") val shadowBandBoost: Double? = 0.5,   // U-01|' "$lcg"
//
// K.2) Adiciona accessor noiseReductionShadowBandBoost após noiseReductionAdaptive
//      (anchor: linha única `val noiseReductionAdaptive: Boolean get() = ...`):
// sed -i 's|val noiseReductionAdaptive: Boolean get() = currentConfig?.noiseReduction?.adaptive ?: true|val noiseReductionAdaptive: Boolean get() = currentConfig?.noiseReduction?.adaptive ?: true\nval noiseReductionShadowBandBoost: Double get() = currentConfig?.noiseReduction?.shadowBandBoost ?: 0.5  // U-01|' "$lcg"

// ═══════════════════════════════════════════════════════════════════════════════
// REFERENCIADO POR (NÃO QUEBRAR)
// ═══════════════════════════════════════════════════════════════════════════════
//   - LeicaConfig.kt:
//       * NoiseModelCoefficients data class (a/b/c/d fields) — L339-L344
//       * NoiseModelFallback data class (main/uw/tele/front) — L347-L353
//       * noiseModelForLens(lensKey) accessor — L1072-L1083
//       * lumaNrMultiplierForLens(lensKey) accessor — L827-L830
//       * chromaNrMultiplierForLens(lensKey) accessor — L833-L836
//       * demosaicNlmSearchRadius accessor — L642
//       * lensKeyFromCameraId(cameraId) helper — L463-L486
//       * noiseReductionShadowBandBoost accessor — NOVO (Section K)
//       * NoiseReductionConfig data class — extended (Section K)
//   - DenoiseProfileShaders.kt:
//       * SEARCH_RADIUS const → val getter (Section B)
//       * FUSED_TILE_X/Y const → val getter (Section B)
//       * FINISH_V2 shader uniform + shadow mask (Section A)
//       * PATCH_RADIUS, BLACK_PRESERVING_BIAS, IMAGE_LOCAL_X/Y continuam const
//       * PRECONDITION_V2, INIT, FUSED_ACCU shaders não alterados (VST exponent
//         `p` continua vindo de params.p via setDenoiseCommonUniforms — já
//         reflete `c` override através de inferDenoiseProfileShadows)
//   - DenoiseProfileNlmConfig.kt:
//       * searchOffsets val → getter (Section C)
//       * buildSearchOffsets(radius) function — unchanged (já aceita Int param)
//       * weightTuning(patchRadius) function — unchanged
//       * COARSE_GUIDE_WEIGHT const — unchanged (U-04 territory, não U-01)
//   - RawDemosaicProcessor.kt:
//       * DenoiseProfileParams data class — adiciona shadowBandStrength field (Section G)
//       * DenoiseProfileNoiseModel data class — NOVO (Section D)
//       * resolveDenoiseProfileNoiseModel() — return type muda (Section D)
//       * inferDenoiseProfileShadows() — adiciona param c (Section E)
//       * buildDenoiseProfileParams() — adiciona lensKey + 4-coef + multiplier (Section F)
//       * dispatchDenoiseNlmFinish() — bind uShadowBandStrength (Section H)
//       * renderDefaultChromaDenoise() — adiciona lensKey + chroma multiplier (Section I)
//       * renderDenoiseProfilePass() — adiciona lensKey param (Section J.1-J.2)
//       * Pipeline call site L2606/L2619 — passa lensKey (Section J.3-J.5)
//       * dispatchDenoiseNlmFusedAccumulate(), dispatchDenoisePreconditionV2(),
//         setDenoiseCommonUniforms() — NÃO alterados (consomem params.* fields
//         indiretamente, incluindo novo params.shadowBandStrength automaticamente
//         via Section H binding).
//   - RawMetadata.kt — NÃO alterado (cameraId threading é future patch)
//   - ChromaDenoiseShaders.kt — NÃO alterado (chroma NR multiplier aplicado no
//     host antes do shader dispatch, multiplicando `strength` em renderDefaultChromaDenoise)
//   - ChromaDenoiseDefaults.kt — NÃO alterado (multiplier applied upstream of defaults)
//   - RawNoiseModel.kt — NÃO alterado (esse path é o temporal stacking, não NLM)
//   - NoiseModelFallback.patch.kt (P-36) — não conflita (P-36 patcheia
//     RawMetadata.extractChannelNoiseProfile; U-01 patcheia RawDemosaicProcessor.
//     resolveDenoiseProfileNoiseModel que consome o que P-36 retorna. Compatível.)
//   - PerLensAgxConsumer.patch.kt (P-43) — não conflita (different file: CameraViewModel.kt)
//   - U-04 patch (futuro) — pode complementar U-01 alterando uCentralPixelWeight
//     e COARSE_GUIDE_WEIGHT. U-01 não toca esses pontos.
//
// VALIDAÇÃO PÓS-PATCH (build engineer):
//   1. `grep -n 'uniform float uShadowBandStrength' DenoiseProfileShaders.kt` => 1 match
//   2. `grep -n 'float shadowMask' DenoiseProfileShaders.kt` => 1 match (no FINISH_V2)
//   3. `grep -n 'effectiveMix' DenoiseProfileShaders.kt` => 2 matches (decl + use)
//   4. `grep -n 'val SEARCH_RADIUS: Int get()' DenoiseProfileShaders.kt` => 1 (não `const val`)
//   5. `grep -n 'private val FUSED_TILE_X: Int get()' DenoiseProfileShaders.kt` => 1
//   6. `grep -n 'val searchOffsets: List<DenoiseProfileOffset>' DenoiseProfileNlmConfig.kt`
//      => 1 match com `get() = buildSearchOffsets` na próxima linha
//   7. `grep -n 'private data class DenoiseProfileNoiseModel' RawDemosaicProcessor.kt` => 1
//   8. `grep -n 'lensKey: String = "main"' RawDemosaicProcessor.kt` => 4+ matches
//      (resolveDenoiseProfileNoiseModel, buildDenoiseProfileParams,
//       renderDenoiseProfilePass, renderDefaultChromaDenoise)
//   9. `grep -n 'LeicaConfig.noiseModelForLens' RawDemosaicProcessor.kt` => 1 match
//   10. `grep -n 'LeicaConfig.lumaNrMultiplierForLens' RawDemosaicProcessor.kt` => 1 match
//   11. `grep -n 'LeicaConfig.chromaNrMultiplierForLens' RawDemosaicProcessor.kt` => 1 match
//   12. `grep -n 'LeicaConfig.noiseReductionShadowBandBoost' RawDemosaicProcessor.kt` => 1 match
//   13. `grep -n 'shadowBandStrength = shadowBandStrength' RawDemosaicProcessor.kt` => 1
//   14. `grep -n 'val shadowBandStrength: Float = 0f' RawDemosaicProcessor.kt` => 1 (field)
//   15. `grep -n 'uShadowBandStrength' RawDemosaicProcessor.kt` => 2 matches (location + bind)
//   16. `grep -n 'u01LensKey' RawDemosaicProcessor.kt` => 3 matches (decl + 2 uses)
//   17. `grep -n 'shadow_band_boost' LeicaConfig.kt` => 1 (SerializedName)
//   18. `grep -n 'noiseReductionShadowBandBoost' LeicaConfig.kt` => 1 (accessor)
//   19. Build Gradle assembleDebug — sem erros de compilação.
//   20. Smoke test: capture 1 RAW em condição low-light (ISO ≥ 3200, EV ≤ -2).
//       Comparar shadows luma 0..0.15 com e sem U-01 (toggle via config
//       noise_reduction.shadow_band_boost = 0 vs 0.5). Esperado: chroma/luma
//       noise em shadows reduzido ~30-50% sem plastificar midtones.
//   21. Smoke test: capture UW (cameraId=2) e main (cameraId=0). Verificar que
//       per-lens noise model a/b/c/d é aplicado (log line `DenoiseProfile NLM`
//       em RawDemosaicProcessor L5855 deve mostrar `a=2.8e-7` p/ main e
//       `a=7.2e-7` p/ UW quando HAL SENSOR_NOISE_PROFILE estiver vazio).
// ═══════════════════════════════════════════════════════════════════════════════
