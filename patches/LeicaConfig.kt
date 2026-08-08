package com.hinnka.mycamera.raw

import android.hardware.camera2.CameraCharacteristics
import android.util.Log
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.hinnka.mycamera.camera.Camera2Controller

/**
 * ═══════════════════════════════════════════════════════════════════════════════
 * LeicaConfig — Configurador central do fork Leica Perfect (PhotonCamera v6.2.0)
 * ═══════════════════════════════════════════════════════════════════════════════
 *
 * DEFINITIVE QUALITY — MAX BITS/LUT/RAW + Beat-GCam + 26 Creative Profiles
 *
 * Mantém TODO o estado de tuning do fork num único object companion:
 *   - Schema JSON versionado (config/leica_perfect.json)
 *   - Data classes para TODAS as 22 seções do JSON
 *   - Accessors race-safe (val get() = currentConfig?.X ?: default)
 *   - Helpers per-lens (lensKeyFromCameraId, *ForLens)
 *   - Suporte a 26 creative profiles (v6.2)
 *   - Suporte a 3 capture modes adaptativos (v6.2 — mode_max/balanced/fast)
 *
 * Histórico de versões do schema JSON:
 *   - v2 (PhotonCamera v3.1): 7 seções básicas — sharpening/noise/color/tone/lens/output/advanced
 *   - v3 (fork v4.0): +5 seções — multi_frame/hdr/demosaic/processing/dcp
 *   - v4 (fork v4.1): +1 seção sensors (per-sensor black/white level/CFA)
 *   - v5 (fork v6.0): +4 seções — per_lens/color_science/video/noise_model_fallback
 *   - v6.0 (fork v6.1): +5 campos PerLensConfig (gamma_contrast/shoulder/shadow_lift,
 *                          ccm_ratio_warm/cool) + 1 campo AdvancedTuning (export_super_res_dng)
 *                          + 13 accessors v6.1 (gamma*ForLens, ccm*ForLens, *LevelForLens,
 *                          usesDirect*Control, exportSuperResDng)
 *   - v6.1 (fork v6.2): +1 seção creative_profiles (26 perfis) + 13 accessors v6.2
 *                          (activeCreativeProfile, activeLutId, activeDcpId, activeFrameId,
 *                          effectiveToneContrast, effectiveSaturationBoost, effectiveWarmthShiftK,
 *                          creativeProfileById, creativeProfilesForScene, etc.) +
 *                          creative-profile-aware forcedDcpId/forcedBaselineLutId/forcedFrameId
 *
 * CONFIG_SCHEMA_VERSION mantido em 5 (adições v6.0/v6.1/v6.2 são campos opcionais —
 * JSON antigo sem as seções novas continua carregando sem erro).
 */
object LeicaConfig {

    private const val TAG = "LeicaConfig"
    const val CONFIG_SCHEMA_VERSION = 5
    const val ACTIVE_VERSION = "6.2.0"

    // ═══════════════════════════════════════════════════════════════════════════
    // DATA CLASSES — espelham 1:1 as seções do leica_perfect.json
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Container raiz — uma instância dessas é desserializada do JSON.
     * Cada campo é nullable para que JSONs antigos/parciais continuem funcionando.
     */
    data class LeicaPerfectConfig(
        @SerializedName("_meta") val meta: MetaConfig? = null,
        @SerializedName("capture_modes") val captureModes: CaptureModeConfig? = null,
        @SerializedName("sharpening") val sharpening: SharpeningConfig? = null,
        @SerializedName("noise_reduction") val noiseReduction: NoiseReductionConfig? = null,
        @SerializedName("color") val color: ColorConfig? = null,
        @SerializedName("tone_mapping") val toneMapping: ToneMappingConfig? = null,
        @SerializedName("lens_correction") val lensCorrection: LensCorrectionConfig? = null,
        @SerializedName("output") val output: OutputConfig? = null,
        @SerializedName("multi_frame") val multiFrame: MultiFrameConfig? = null,
        @SerializedName("hdr") val hdr: HdrConfig? = null,
        @SerializedName("demosaic") val demosaic: DemosaicConfig? = null,
        @SerializedName("processing") val processing: ProcessingConfig? = null,
        @SerializedName("dcp") val dcp: DcpConfig? = null,
        @SerializedName("advanced") val advanced: AdvancedTuning? = null,
        @SerializedName("sensors") val sensors: SensorsConfig? = null,
        @SerializedName("per_lens") val perLens: PerLensConfig? = null,
        @SerializedName("color_science") val colorScience: ColorScienceConfig? = null,
        @SerializedName("video") val video: VideoConfig? = null,
        @SerializedName("noise_model_fallback") val noiseModelFallback: NoiseModelFallback? = null,
        @SerializedName("creative_profiles") val creativeProfiles: CreativeProfileConfig? = null,
    )

    /** _meta — versão/schema/dispositivo. */
    data class MetaConfig(
        @SerializedName("version") val version: String? = "6.2.0",
        @SerializedName("name") val name: String? = "Xiaomi 15T Leica Perfect",
        @SerializedName("description") val description: String? = "",
        @SerializedName("device") val device: String? = "Xiaomi 15T (NON-Pro)",
        @SerializedName("codename") val codename: String? = "dizi",
        @SerializedName("chipset") val chipset: String? = "MediaTek Dimensity 8300-Ultra",
        @SerializedName("upstream") val upstream: String? = "bjzhou/PhotonCamera v1.26.1",
        @SerializedName("schema_version") val schemaVersion: Int? = 5,
        @SerializedName("device_target") val deviceTarget: String? = "",
    )

    /**
     * capture_modes — v6.2 INTELLIGENT ADAPTIVE.
     * Controla trade-off qualidade vs velocidade/termico.
     */
    data class CaptureModeConfig(
        @SerializedName("active_capture_mode") val activeCaptureMode: String? = "mode_fast",
        @SerializedName("modes") val modes: Map<String, CaptureModeSettings>? = null,
    )

    /** Um capture mode individual (mode_max / mode_balanced). */
    data class CaptureModeSettings(
        @SerializedName("frame_count_multiplier") val frameCountMultiplier: Float? = 1.0f,
        @SerializedName("super_resolution_scale") val superResolutionScale: Float? = 1.0f,
        @SerializedName("nlm_search_radius") val nlmSearchRadius: Int? = 5,
        @SerializedName("force_rawmax") val forceRawmax: Boolean? = true,
        @SerializedName("export_super_res_dng") val exportSuperResDng: Boolean? = false,
        @SerializedName("video_bitrate_mbps") val videoBitrateMbps: Int? = 120,
        @SerializedName("thermal_throttle_at_c") val thermalThrottleAtC: Int? = 50,
    )

    /** sharpening — unsharp mask global. */
    data class SharpeningConfig(
        @SerializedName("enabled") val enabled: Boolean? = true,
        @SerializedName("amount") val amount: Double? = 0.09,
        @SerializedName("radius") val radius: Double? = 0.9,
        @SerializedName("threshold") val threshold: Double? = 0.003,
        @SerializedName("adaptive") val adaptive: Boolean? = true,
        @SerializedName("edge_mask_strength") val edgeMaskStrength: Double? = 2.2,
    )

    /** noise_reduction — controle NLM luma/chroma. */
    data class NoiseReductionConfig(
        @SerializedName("enabled") val enabled: Boolean? = true,
        @SerializedName("luminance") val luminance: Double? = 0.92,
        @SerializedName("chrominance") val chrominance: Double? = 0.70,
        @SerializedName("detail_preserve") val detailPreserve: Double? = 0.96,
        @SerializedName("adaptive") val adaptive: Boolean? = true,
    )

    /** color — boost global. */
    data class ColorConfig(
        @SerializedName("saturation_boost") val saturationBoost: Double? = 1.02,
        @SerializedName("vibrance") val vibrance: Double? = 1.01,
        @SerializedName("warmth") val warmth: Double? = 1.00,
        @SerializedName("leica_look") val leicaLook: Boolean? = true,
        @SerializedName("color_profile") val colorProfile: String? = "summilux",
    )

    /** tone_mapping — AgX + filmic shoulders. */
    data class ToneMappingConfig(
        @SerializedName("enabled") val enabled: Boolean? = true,
        @SerializedName("contrast") val contrast: Double? = 1.10,
        @SerializedName("highlight_rolloff") val highlightRolloff: Double? = 0.35,
        @SerializedName("shadow_lift") val shadowLift: Double? = 0.10,
        @SerializedName("film_like_curve") val filmLikeCurve: Boolean? = true,
    )

    /** lens_correction — toggles de correções ópticas. */
    data class LensCorrectionConfig(
        @SerializedName("enabled") val enabled: Boolean? = true,
        @SerializedName("distortion_correction") val distortionCorrection: Boolean? = true,
        @SerializedName("vignette_correction") val vignetteCorrection: Boolean? = true,
        @SerializedName("chromatic_aberration") val chromaticAberration: Boolean? = true,
    )

    /** output — formato/qualidade do arquivo final.
     *  v6.4.0: force_no_raw/force_no_dng/force_heic_q100/force_ultrahdr_q100 added. */
    data class OutputConfig(
        @SerializedName("format") val format: String? = "jpeg",
        @SerializedName("quality") val quality: Int? = 100,
        @SerializedName("max_resolution") val maxResolution: Int? = 0,
        @SerializedName("preserve_exif") val preserveExif: Boolean? = true,
        @SerializedName("add_watermark") val addWatermark: Boolean? = false,
        @SerializedName("force_no_raw") val forceNoRaw: Boolean = false,
        @SerializedName("force_no_dng") val forceNoDng: Boolean = false,
        @SerializedName("force_heic_q100") val forceHeicQ100: Boolean = false,
        @SerializedName("force_ultrahdr_q100") val forceUltraHdrQ100: Boolean = false,
    )

    /** multi_frame — burst count + super res scale. */
    data class MultiFrameConfig(
        @SerializedName("enabled") val enabled: Boolean? = true,
        @SerializedName("frame_count") val frameCount: Int? = 15,
        @SerializedName("super_resolution_scale") val superResolutionScale: Float? = 2.0f,
        @SerializedName("long_frame_exposure_ev") val longFrameExposureEv: Double? = 2.8,
        @SerializedName("short_frame_exposure_divisor") val shortFrameExposureDivisor: Double? = 2.5,
        @SerializedName("force_rawmax") val forceRawmax: Boolean? = true,
    )

    /** hdr — bracket EV spacing. */
    data class HdrConfig(
        @SerializedName("yuv_long_ev") val yuvLongEv: Float? = 2.5f,
        @SerializedName("yuv_short_ev") val yuvShortEv: Float? = -2.0f,
    )

    /** demosaic — NLM + highlight reconstruction. */
    data class DemosaicConfig(
        @SerializedName("highlight_reconstruction_threshold") val highlightReconstructionThreshold: Double? = 0.95,
        @SerializedName("nlm_search_radius") val nlmSearchRadius: Int? = 7,
        @SerializedName("nlm_patch_radius") val nlmPatchRadius: Int? = 1,
    )

    /** processing — PGTM/filmic/USM finos. */
    data class ProcessingConfig(
        @SerializedName("usm_radius") val usmRadius: Double? = 0.9,
        @SerializedName("usm_threshold") val usmThreshold: Double? = 0.004,
        @SerializedName("pgtm_toe_power") val pgtmToePower: Double? = 1.6,
        @SerializedName("pgtm_mid_power") val pgtmMidPower: Double? = 1.35,
        @SerializedName("pgtm_shoulder_power") val pgtmShoulderPower: Double? = 1.3,
        @SerializedName("pgtm_balance") val pgtmBalance: Double? = 0.95,
        @SerializedName("filmic_grey_source") val filmicGreySource: Double? = 0.1845,
        @SerializedName("filmic_default_contrast") val filmicDefaultContrast: Double? = 1.6,
        @SerializedName("filmic_default_dynamic_range") val filmicDefaultDynamicRange: Double? = 12.5,
        @SerializedName("default_exposure_ev") val defaultExposureEv: Double? = 0.88,
        @SerializedName("display_target_luma") val displayTargetLuma: Double? = 0.21,
        @SerializedName("center_weight_sigma") val centerWeightSigma: Double? = 0.28,
        @SerializedName("pgtm_pre_tonemap_exposure_boost_ev") val pgtmPreTonemapExposureBoostEv: Double? = 1.3,
        @SerializedName("pgtm_target_dynamic_range") val pgtmTargetDynamicRange: Double? = 120.0,
        @SerializedName("hncs_film_curve_gain") val hncsFilmCurveGain: Double? = 1.1,
    )

    /** dcp — forçar Leica M8 + LUT + frame. */
    data class DcpConfig(
        @SerializedName("force_dcp_id") val forceDcpId: String? = "builtin_dcp_Leica M8 Camera Standard",
        @SerializedName("force_baseline_lut_id") val forceBaselineLutId: String? = "Leica_M9_STD",
        @SerializedName("dcp_ratio_warm") val dcpRatioWarm: Float? = 0.52f,
        @SerializedName("dcp_ratio_cool") val dcpRatioCool: Float? = 1.62f,
        @SerializedName("force_heic_export") val forceHeicExport: Boolean? = true,
        @SerializedName("force_frame_id") val forceFrameId: String? = "leica",
    )

    /** advanced — Mertens/vignette/DNG export/gainmap/branding/ISP. */
    data class AdvancedTuning(
        @SerializedName("mertens_contrast_weight") val mertensContrastWeight: Float? = 1.5f,
        @SerializedName("mertens_saturation_weight") val mertensSaturationWeight: Float? = 1.05f,
        @SerializedName("mertens_exposure_weight") val mertensExposureWeight: Float? = 1.0f,
        @SerializedName("vignette_grid_max_h") val vignetteGridMaxH: Int? = 128,
        @SerializedName("vignette_grid_max_v") val vignetteGridMaxV: Int? = 96,
        @SerializedName("export_dng_with_raw") val exportDngWithRaw: Boolean? = true,
        @SerializedName("export_super_res_dng") val exportSuperResDng: Boolean? = true,
        @SerializedName("gainmap_jpeg_quality") val gainmapJpegQuality: Int? = 100,
        @SerializedName("ultra_hdr_quality") val ultraHdrQuality: Int? = 100,
        @SerializedName("heic_quality") val heicQuality: Int? = 100,
        @SerializedName("software_branding") val softwareBranding: String? = "LeicaCamera",
        @SerializedName("force_high_quality_isp") val forceHighQualityIsp: Boolean? = true,
    )

    /**
     * sensors — por-sensor black/white level + CFA mode + MTK RAW BPP.
     * v6.1 MAX: main 14-bit (black=1024, white=16383), UW/tele/front 10-bit (black=64, white=1023).
     */
    data class SensorsConfig(
        @SerializedName("main_black_level") val mainBlackLevel: Int? = 1024,
        @SerializedName("main_white_level") val mainWhiteLevel: Int? = 16383,
        @SerializedName("main_cfa_mode") val mainCfaMode: String? = "4x4_RGGB",
        @SerializedName("uw_black_level") val uwBlackLevel: Int? = 64,
        @SerializedName("uw_white_level") val uwWhiteLevel: Int? = 1023,
        @SerializedName("uw_cfa_mode") val uwCfaMode: String? = "2x2_GBRG",
        @SerializedName("tele_black_level") val teleBlackLevel: Int? = 64,
        @SerializedName("tele_white_level") val teleWhiteLevel: Int? = 1023,
        @SerializedName("tele_cfa_mode") val teleCfaMode: String? = "2x2_BGGR",
        @SerializedName("front_black_level") val frontBlackLevel: Int? = 64,
        @SerializedName("front_white_level") val frontWhiteLevel: Int? = 1023,
        @SerializedName("front_cfa_mode") val frontCfaMode: String? = "2x2_BGGR",
        @SerializedName("mtk_raw_bpp") val mtkRawBpp: Int? = 14,
    )

    /**
     * per_lens — 4 lens entries (main/uw/tele/front) com tuning BEAT GCam.
     * v6.1 adicionou gamma_contrast/shoulder/shadow_lift + ccm_ratio_warm/cool
     * (5 campos novos) substituindo 2 GCam preset integers por 24 continuous values.
     */
    data class PerLensConfig(
        @SerializedName("main") val main: PerLensTuning? = null,
        @SerializedName("uw") val uw: PerLensTuning? = null,
        @SerializedName("tele") val tele: PerLensTuning? = null,
        @SerializedName("front") val front: PerLensTuning? = null,
    )

    /**
     * PerLensTuning — uma entrada per-lens.
     * Sentinel -1 em gamma_preset/awb_mode/noise_model_preset/ccm_preset = "use direct control"
     * (ignora lookup de preset e usa gamma_contrast/ccm_ratio_warm/etc direto).
     */
    data class PerLensTuning(
        @SerializedName("frame_count") val frameCount: Int? = -1,
        @SerializedName("ev_comp") val evComp: Float? = 0.0f,
        @SerializedName("gamma_preset") val gammaPreset: Int? = -1,
        @SerializedName("awb_mode") val awbMode: Int? = -1,
        @SerializedName("noise_model_preset") val noiseModelPreset: Int? = -1,
        @SerializedName("ccm_preset") val ccmPreset: Int? = -1,
        // v6.1 NEW — control direto AgX/DCP (5 campos)
        @SerializedName("gamma_contrast") val gammaContrast: Float? = -1f,
        @SerializedName("gamma_shoulder") val gammaShoulder: Float? = -1f,
        @SerializedName("gamma_shadow_lift") val gammaShadowLift: Float? = -1f,
        @SerializedName("ccm_ratio_warm") val ccmRatioWarm: Float? = -1f,
        @SerializedName("ccm_ratio_cool") val ccmRatioCool: Float? = -1f,
        // Tuning basico
        @SerializedName("sharpening_multiplier") val sharpeningMultiplier: Float? = 1.0f,
        @SerializedName("luma_nr_multiplier") val lumaNrMultiplier: Float? = 1.0f,
        @SerializedName("chroma_nr_multiplier") val chromaNrMultiplier: Float? = 1.0f,
        @SerializedName("tint_shift") val tintShift: Int? = 0,
        @SerializedName("highlight_compression_ev") val highlightCompressionEv: Float? = 0.0f,
        @SerializedName("saturation_red") val saturationRed: Float? = 1.0f,
        @SerializedName("saturation_green") val saturationGreen: Float? = 1.0f,
        @SerializedName("saturation_blue") val saturationBlue: Float? = 1.0f,
        // Front-only
        @SerializedName("skin_warmth_k") val skinWarmthK: Int? = 0,
        @SerializedName("beauty_filter") val beautyFilter: Boolean? = false,
        // v6.3.8 NEW — Camera2 direct params (P-57)
        @SerializedName("edge_mode") val edgeMode: Int? = -1,
        @SerializedName("camera2_noise_reduction_mode") val camera2NoiseReductionMode: Int? = -1,
        @SerializedName("shading_mode") val shadingMode: Int? = -1,
        @SerializedName("hot_pixel_mode") val hotPixelMode: Int? = -1,
        @SerializedName("tonemap_mode") val tonemapMode: Int? = -1,
    )

    /** color_science — tints e saturações per-channel. */
    data class ColorScienceConfig(
        @SerializedName("tint_shift") val tintShift: Int? = -12,
        @SerializedName("saturation_red_pct") val saturationRedPct: Int? = -5,
        @SerializedName("saturation_green_pct") val saturationGreenPct: Int? = -10,
        @SerializedName("saturation_blue_pct") val saturationBluePct: Int? = -7,
        @SerializedName("highlight_compression_ev") val highlightCompressionEv: Float? = -0.15f,
        @SerializedName("skin_tone_protection") val skinToneProtection: Boolean? = true,
        @SerializedName("per_channel_tint_red") val perChannelTintRed: Int? = 0,
        @SerializedName("per_channel_tint_green") val perChannelTintGreen: Int? = 0,
        @SerializedName("per_channel_tint_blue") val perChannelTintBlue: Int? = 0,
    )

    /** video — MAX HEVC 250Mbps B-frames=2 log HDR10. */
    data class VideoConfig(
        @SerializedName("codec") val codec: String? = "hevc",
        @SerializedName("bitrate_mbps") val bitrateMbps: Int? = 250,
        @SerializedName("max_b_frames") val maxBFrames: Int? = 2,
        @SerializedName("color_profile") val colorProfile: String? = "log",
        @SerializedName("default_resolution") val defaultResolution: String? = "2160p",
        @SerializedName("default_fps") val defaultFps: Int? = 30,
        @SerializedName("hdr_video") val hdrVideo: Boolean? = true,
        @SerializedName("audio_codec") val audioCodec: String? = "aac",
        @SerializedName("audio_bitrate_kbps") val audioBitrateKbps: Int? = 256,
        @SerializedName("audio_sample_rate") val audioSampleRate: Int? = 48000,
        @SerializedName("i_frame_interval_sec") val iFrameIntervalSec: Int? = 1,
        @SerializedName("rate_control") val rateControl: String? = "vbr",
    )

    /** NoiseModelCoefficients — modelo quadrático noise^2 = a*ISO^2 + b*ISO + c + d/ISO. */
    data class NoiseModelCoefficients(
        @SerializedName("a") val a: Double? = 0.0,
        @SerializedName("b") val b: Double? = 0.0,
        @SerializedName("c") val c: Double? = 0.0,
        @SerializedName("d") val d: Double? = 0.0,
    )

    /** noise_model_fallback — coeficientes physics-derived por sensor. */
    data class NoiseModelFallback(
        @SerializedName("enabled") val enabled: Boolean? = true,
        @SerializedName("main") val main: NoiseModelCoefficients? = null,
        @SerializedName("uw") val uw: NoiseModelCoefficients? = null,
        @SerializedName("tele") val tele: NoiseModelCoefficients? = null,
        @SerializedName("front") val front: NoiseModelCoefficients? = null,
    )

    /**
     * CreativeProfile — um perfil criativo individual (LUT + DCP + frame + tone).
     * 26 perfis total (Leica 2 + Hasselblad 2 + Fuji 7 + Kodak 2 + Ricoh 5 + Pentax 4 + Lumix 3 + B&W 1).
     */
    data class CreativeProfile(
        @SerializedName("lut_id") val lutId: String? = "leica_m9",
        @SerializedName("dcp_id") val dcpId: String? = "builtin_dcp_Leica M8 Camera Standard",
        @SerializedName("frame_id") val frameId: String? = "03_no_frame",
        @SerializedName("tone_contrast_boost") val toneContrastBoost: Float? = 0.0f,
        @SerializedName("tone_warmth_shift_k") val toneWarmthShiftK: Int? = 0,
        @SerializedName("saturation_multiplier") val saturationMultiplier: Float? = 1.0f,
        @SerializedName("recommended_for") val recommendedFor: List<String>? = emptyList(),
        @SerializedName("best_lens") val bestLens: String? = "main",
    )

    /**
     * CreativeProfileConfig — container dos 26 creative profiles.
     * active_profile controla qual perfil está ativo no runtime (via accessors v6.2).
     */
    data class CreativeProfileConfig(
        @SerializedName("active_profile") val activeProfile: String? = "leica_authentic",
        @SerializedName("profiles") val profiles: Map<String, CreativeProfile>? = emptyMap(),
    )

    // ═══════════════════════════════════════════════════════════════════════════
    // ESTADO INTERNO
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * currentConfig — instância carregada do JSON (via load()).
     * Mutável para permitir reload em runtime; accessors são race-safe via `?.`.
     */
    @Volatile
    private var currentConfig: LeicaPerfectConfig? = null

    /**
     * configSnapshot — read-only public accessor for currentConfig.
     * Used by LeicaStateDumper to dump the entire config state to logcat.
     * v6.3.8-fix4: added because currentConfig is private (encapsulation preserved).
     */
    val configSnapshot: LeicaPerfectConfig?
        get() = currentConfig

    @Volatile
    private var lastLoadMs: Long = 0L

    @Volatile
    private var lastLensKeyCache: String = "main"

    /** Carrega config de JSON string (normalmente chamado em app init). */
    fun load(json: String) {
        try {
            currentConfig = gson.fromJson(json, LeicaPerfectConfig::class.java)
            lastLoadMs = System.currentTimeMillis()
            Log.i(TAG, "LeicaConfig loaded — schema v${currentConfig?.meta?.schemaVersion ?: "?"} " +
                "(${currentConfig?.let { countSections(it) } ?: 0} sections, " +
                "${currentConfig?.creativeProfiles?.profiles?.size ?: 0} creative profiles)")
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to load LeicaConfig — using defaults", t)
            currentConfig = null
        }
    }

    /** Carrega de arquivo (wrapper conveniente para File/InputStream). */
    fun loadFromFile(path: String) {
        try {
            val json = java.io.File(path).readText()
            load(json)
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to read LeicaConfig file: $path", t)
        }
    }

    /** Conta seções não-null pra logging. */
    private fun countSections(c: LeicaPerfectConfig): Int {
        var n = 0
        if (c.meta != null) n++
        if (c.captureModes != null) n++
        if (c.sharpening != null) n++
        if (c.noiseReduction != null) n++
        if (c.color != null) n++
        if (c.toneMapping != null) n++
        if (c.lensCorrection != null) n++
        if (c.output != null) n++
        if (c.multiFrame != null) n++
        if (c.hdr != null) n++
        if (c.demosaic != null) n++
        if (c.processing != null) n++
        if (c.dcp != null) n++
        if (c.advanced != null) n++
        if (c.sensors != null) n++
        if (c.perLens != null) n++
        if (c.colorScience != null) n++
        if (c.video != null) n++
        if (c.noiseModelFallback != null) n++
        if (c.creativeProfiles != null) n++
        return n
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // HELPERS PER-LENS
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * lensKeyFromCameraId — mapeia Camera2 ID string pra chave "main"/"uw"/"tele"/"front".
     * Xiaomi 15T (dizi): 0=main(OV50E), 2=UW(S5KJN1), 4=tele(S5K3J1), 1=front(OV32B).
     * Fallback case-insensitive substring para otros dispositivos.
     */
    fun lensKeyFromCameraId(cameraId: String): String {
        // Cache rápido pra última chave (evita re-parsing no hot path)
        if (cameraId == lastLensKeyCache) return lastLensKeyCache

        val key = when (cameraId.lowercase()) {
            "0" -> "main"
            "2" -> "uw"
            "4" -> "tele"
            "1" -> "front"
            else -> {
                // Substring fallback
                val lc = cameraId.lowercase()
                when {
                    "front" in lc -> "front"
                    "tele" in lc -> "tele"
                    "wide" in lc || "uw" in lc -> "uw"
                    "main" in lc -> "main"
                    else -> "main" // default conservador
                }
            }
        }
        lastLensKeyCache = key
        return key
    }

    /**
     * lensKeyFromCharacteristics — deriva lensKey do LENS_FACING.
     * Mais robusto que cameraId (funciona em qualquer dispositivo).
     * Limitação: LENS_FACING não distingue UW/tele do main (todos BACK=0).
     * Pra UW/tele precisa do focal length — chamador deve passar explicitamente se disponível.
     */
    fun lensKeyFromCharacteristics(c: CameraCharacteristics?): String {
        if (c == null) return "main"
        val facing = c.get(CameraCharacteristics.LENS_FACING)
        return when (facing) {
            CameraCharacteristics.LENS_FACING_FRONT -> "front"
            CameraCharacteristics.LENS_FACING_BACK -> {
                // Tenta usar focal length pra distinguir UW/tele
                val focalLengths = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                if (focalLengths != null && focalLengths.isNotEmpty()) {
                    val fl = focalLengths[0]
                    when {
                        fl < 3.0f -> "uw"     // <3mm típico UW
                        fl > 7.0f -> "tele"   // >7mm típico tele
                        else -> "main"
                    }
                } else "main"
            }
            else -> "main"
        }
    }

    /** Acesso direto a PerLensTuning por chave — null se não existir. */
    fun perLensForKey(lensKey: String): PerLensTuning? {
        val cfg = currentConfig ?: return null
        return when (lensKey.lowercase()) {
            "main" -> cfg.perLens?.main
            "uw" -> cfg.perLens?.uw
            "tele" -> cfg.perLens?.tele
            "front" -> cfg.perLens?.front
            else -> cfg.perLens?.main
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // ACCESSORS — _meta + capture_modes (v6.2 ADAPTIVE)
    // ═══════════════════════════════════════════════════════════════════════════

    /** Versão do config (string "6.2.0"). */
    val configVersion: String get() = currentConfig?.meta?.version ?: "6.2.0"

    /** Schema version (int — 5). */
    val configSchemaVersion: Int get() = currentConfig?.meta?.schemaVersion ?: 5

    /** Nome do dispositivo alvo. */
    val deviceTarget: String get() = currentConfig?.meta?.deviceTarget
        ?: "Xiaomi 15T (dizi) — OV50E + S5KJN1 + S5K3J1 + OV32B"

    /** Capture mode ativo (mode_max / mode_fast).
     *  v6.3.4: checa LeicaRuntimeState.captureModeOverride primeiro (setado pelo menu).
     *  v6.4.0: default mudou de mode_balanced (removido) pra mode_fast (disparo rapido inteligente).
     *  Só cai pro JSON se override for null. */
    val activeCaptureMode: String
        get() = LeicaRuntimeState.captureModeOverride
            ?: currentConfig?.captureModes?.activeCaptureMode
            ?: "mode_fast"

    /** Settings do capture mode ativo (null = usar defaults globais). */
    val captureModeSettings: CaptureModeSettings?
        get() = currentConfig?.captureModes?.modes?.get(activeCaptureMode)

    /** Multiplicador de frame count do modo ativo (1.0 / 0.6 / 0.33). */
    val captureModeFrameCountMultiplier: Float
        get() = captureModeSettings?.frameCountMultiplier ?: 1.0f

    /** Super resolution scale do modo ativo (2.0 / 1.0 / 1.0). */
    val captureModeSuperResolutionScale: Float
        get() = captureModeSettings?.superResolutionScale ?: 1.0f

    /** NLM search radius do modo ativo (7 / 5 / 4). */
    val captureModeNlmSearchRadius: Int
        get() = captureModeSettings?.nlmSearchRadius ?: 5

    /** Force RAWmax do modo ativo. */
    val captureModeForceRawmax: Boolean
        get() = captureModeSettings?.forceRawmax ?: true

    /** Export super res DNG do modo ativo. */
    val captureModeExportSuperResDng: Boolean
        get() = captureModeSettings?.exportSuperResDng ?: false

    /** Video bitrate do modo ativo (250 / 120 / 80 Mbps). */
    val captureModeVideoBitrateMbps: Int
        get() = captureModeSettings?.videoBitrateMbps ?: 120

    /** Thermal throttle point do modo ativo (45 / 50 / 55°C). */
    val captureModeThermalThrottleAtC: Int
        get() = captureModeSettings?.thermalThrottleAtC ?: 50

    // ═══════════════════════════════════════════════════════════════════════════
    // ACCESSORS — sharpening / noise_reduction / color / tone_mapping
    // ═══════════════════════════════════════════════════════════════════════════

    val sharpeningEnabled: Boolean get() = currentConfig?.sharpening?.enabled ?: true
    val sharpeningAmount: Double get() = currentConfig?.sharpening?.amount ?: 0.09
    val sharpeningRadius: Double get() = currentConfig?.sharpening?.radius ?: 0.9
    val sharpeningThreshold: Double get() = currentConfig?.sharpening?.threshold ?: 0.003
    val sharpeningAdaptive: Boolean get() = currentConfig?.sharpening?.adaptive ?: true
    val sharpeningEdgeMaskStrength: Double get() = currentConfig?.sharpening?.edgeMaskStrength ?: 2.2

    val noiseReductionEnabled: Boolean get() = currentConfig?.noiseReduction?.enabled ?: true
    val noiseReductionLuminance: Double get() = currentConfig?.noiseReduction?.luminance ?: 0.92
    val noiseReductionChrominance: Double get() = currentConfig?.noiseReduction?.chrominance ?: 0.70
    val noiseReductionDetailPreserve: Double get() = currentConfig?.noiseReduction?.detailPreserve ?: 0.96
    val noiseReductionAdaptive: Boolean get() = currentConfig?.noiseReduction?.adaptive ?: true

    val colorSaturationBoost: Double get() = currentConfig?.color?.saturationBoost ?: 1.02
    val colorVibrance: Double get() = currentConfig?.color?.vibrance ?: 1.01
    val colorWarmth: Double get() = currentConfig?.color?.warmth ?: 1.00
    val colorLeicaLook: Boolean get() = currentConfig?.color?.leicaLook ?: true
    val colorProfile: String get() = currentConfig?.color?.colorProfile ?: "summilux"

    val toneMappingEnabled: Boolean get() = currentConfig?.toneMapping?.enabled ?: true
    val toneMappingContrast: Double get() = currentConfig?.toneMapping?.contrast ?: 1.10
    val toneMappingHighlightRolloff: Double get() = currentConfig?.toneMapping?.highlightRolloff ?: 0.35
    val toneMappingShadowLift: Double get() = currentConfig?.toneMapping?.shadowLift ?: 0.10
    val toneMappingFilmLikeCurve: Boolean get() = currentConfig?.toneMapping?.filmLikeCurve ?: true

    // ═══════════════════════════════════════════════════════════════════════════
    // ACCESSORS — lens_correction / output
    // ═══════════════════════════════════════════════════════════════════════════

    val lensCorrectionEnabled: Boolean get() = currentConfig?.lensCorrection?.enabled ?: true
    val lensDistortionCorrection: Boolean get() = currentConfig?.lensCorrection?.distortionCorrection ?: true
    val lensVignetteCorrection: Boolean get() = currentConfig?.lensCorrection?.vignetteCorrection ?: true
    val lensChromaticAberration: Boolean get() = currentConfig?.lensCorrection?.chromaticAberration ?: true

    val outputFormat: String get() = currentConfig?.output?.format ?: "jpeg"
    val outputQuality: Int get() = currentConfig?.output?.quality ?: 100
    val outputMaxResolution: Int get() = currentConfig?.output?.maxResolution ?: 0
    val outputPreserveExif: Boolean get() = currentConfig?.output?.preserveExif ?: true
    val outputAddWatermark: Boolean get() = currentConfig?.output?.addWatermark ?: false

    // ═══════════════════════════════════════════════════════════════════════════
    // ACCESSORS — multi_frame / hdr / demosaic
    // ═══════════════════════════════════════════════════════════════════════════

    val multiFrameEnabled: Boolean get() = currentConfig?.multiFrame?.enabled ?: true
    val multiFrameCount: Int get() = currentConfig?.multiFrame?.frameCount ?: 15
    val multiFrameSuperResolutionScale: Float get() = currentConfig?.multiFrame?.superResolutionScale ?: 2.0f
    val multiFrameLongFrameExposureEv: Double get() = currentConfig?.multiFrame?.longFrameExposureEv ?: 2.8
    val multiFrameShortFrameExposureDivisor: Double get() = currentConfig?.multiFrame?.shortFrameExposureDivisor ?: 2.5
    val multiFrameForceRawmax: Boolean get() = currentConfig?.multiFrame?.forceRawmax ?: true

    val yuvLongEv: Float get() = currentConfig?.hdr?.yuvLongEv ?: 2.5f
    val yuvShortEv: Float get() = currentConfig?.hdr?.yuvShortEv ?: -2.0f

    val demosaicHighlightReconstructionThreshold: Double
        get() = currentConfig?.demosaic?.highlightReconstructionThreshold ?: 0.95
    val demosaicNlmSearchRadius: Int get() = currentConfig?.demosaic?.nlmSearchRadius ?: 7
    val demosaicNlmPatchRadius: Int get() = currentConfig?.demosaic?.nlmPatchRadius ?: 1

    // ═══════════════════════════════════════════════════════════════════════════
    // ACCESSORS — processing (PGTM/Filmic/USM)
    // ═══════════════════════════════════════════════════════════════════════════

    val usmRadius: Double get() = currentConfig?.processing?.usmRadius ?: 0.9
    val usmThreshold: Double get() = currentConfig?.processing?.usmThreshold ?: 0.004
    val pgtmToePower: Double get() = currentConfig?.processing?.pgtmToePower ?: 1.6
    val pgtmMidPower: Double get() = currentConfig?.processing?.pgtmMidPower ?: 1.35
    val pgtmShoulderPower: Double get() = currentConfig?.processing?.pgtmShoulderPower ?: 1.3
    val pgtmBalance: Double get() = currentConfig?.processing?.pgtmBalance ?: 0.95
    val filmicGreySource: Double get() = currentConfig?.processing?.filmicGreySource ?: 0.1845
    val filmicDefaultContrast: Double get() = currentConfig?.processing?.filmicDefaultContrast ?: 1.6
    val filmicDefaultDynamicRange: Double get() = currentConfig?.processing?.filmicDefaultDynamicRange ?: 12.5
    val defaultExposureEv: Double get() = currentConfig?.processing?.defaultExposureEv ?: 0.88
    val displayTargetLuma: Double get() = currentConfig?.processing?.displayTargetLuma ?: 0.21
    val centerWeightSigma: Double get() = currentConfig?.processing?.centerWeightSigma ?: 0.28
    val pgtmPreTonemapExposureBoostEv: Double
        get() = currentConfig?.processing?.pgtmPreTonemapExposureBoostEv ?: 1.3
    val pgtmTargetDynamicRange: Double get() = currentConfig?.processing?.pgtmTargetDynamicRange ?: 120.0
    val hncsFilmCurveGain: Double get() = currentConfig?.processing?.hncsFilmCurveGain ?: 1.1

    // ═══════════════════════════════════════════════════════════════════════════
    // ACCESSORS — dcp (creative-profile-aware via v6.2 overrides)
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * forcedDcpId — ID do DCP forçado.
     * v6.2: quando creative profile != baseline (leica_authentic), retorna activeDcpId do perfil ativo.
     * Caso contrário usa o valor global do JSON.
     */
    val forcedDcpId: String
        get() = if (!isActiveProfileBaseline) activeDcpId
        else currentConfig?.dcp?.forceDcpId ?: "builtin_dcp_Leica M8 Camera Standard"

    /**
     * runtimeLutOverride — LUT ID override set at runtime pelo mod menu (P-64).
     * Quando non-null, takes precedence over activeLutId (fixes "stuck on m9 CCD" bug v6.4.0).
     * Set via LeicaRuntimeState.setRuntimeLutOverride(id).
     */
    @Volatile
    var runtimeLutOverride: String? = null

    /**
     * forcedBaselineLutId — ID do LUT baseline forçado.
     * v6.2: creative-profile-aware (retorna activeLutId quando não-baseline).
     * v6.4.0: runtimeLutOverride (setado pelo mod menu) takes precedence over everything.
     *         Fixes "stuck on m9 CCD" bug — quando usuario escolhe outro LUT no menu,
     *         o override era ignorado. Agora é respeitado.
     */
    val forcedBaselineLutId: String
        get() = runtimeLutOverride
            ?: if (!isActiveProfileBaseline) activeLutId
            else currentConfig?.dcp?.forceBaselineLutId ?: "Leica_M9_STD"

    /**
     * forcedFrameId — ID do frame (moldura) forçado.
     * v6.2: creative-profile-aware (retorna activeFrameId quando não-baseline).
     */
    val forcedFrameId: String
        get() = if (!isActiveProfileBaseline) activeFrameId
        else currentConfig?.dcp?.forceFrameId ?: "leica"

    val dcpRatioWarm: Float get() = currentConfig?.dcp?.dcpRatioWarm ?: 0.52f
    val dcpRatioCool: Float get() = currentConfig?.dcp?.dcpRatioCool ?: 1.62f
    val forceHeicExport: Boolean get() = currentConfig?.dcp?.forceHeicExport ?: true

    // ═══════════════════════════════════════════════════════════════════════════
    // ACCESSORS — advanced (Mertens/vignette/DNG/gainmap/ISP)
    // ═══════════════════════════════════════════════════════════════════════════

    val mertensContrastWeight: Float get() = currentConfig?.advanced?.mertensContrastWeight ?: 1.5f
    val mertensSaturationWeight: Float get() = currentConfig?.advanced?.mertensSaturationWeight ?: 1.05f
    val mertensExposureWeight: Float get() = currentConfig?.advanced?.mertensExposureWeight ?: 1.0f
    val vignetteGridMaxH: Int get() = currentConfig?.advanced?.vignetteGridMaxH ?: 128
    val vignetteGridMaxV: Int get() = currentConfig?.advanced?.vignetteGridMaxV ?: 96

    /** forceNoRaw — v6.4.0: desativa export DNG quando true (JSON output.force_no_raw). */
    val forceNoRaw: Boolean get() = currentConfig?.output?.forceNoRaw ?: false

    /** forceNoDng — v6.4.0: alias pra clareza semantica. */
    val forceNoDng: Boolean get() = currentConfig?.output?.forceNoDng ?: false

    /** forceHeicQ100 — v6.4.0: força HEIC Q100 quando true. */
    val forceHeicQ100: Boolean get() = currentConfig?.output?.forceHeicQ100 ?: false

    /** forceUltraHdrQ100 — v6.4.0: força UltraHDR Q100 quando true. */
    val forceUltraHdrQ100: Boolean get() = currentConfig?.output?.forceUltraHdrQ100 ?: false

    /**
     * exportDngWithRawExport — v6.4.0: honra forceNoRaw/forceNoDng (desativa DNG quando user pediu JPEG one-click).
     */
    val exportDngWithRawExport: Boolean
        get() = if (forceNoRaw || forceNoDng) false
        else currentConfig?.advanced?.exportDngWithRaw ?: true

    /**
     * exportSuperResDng — força export de DNG 16-bit uncompressed a 2.0x super res.
     * v6.1 (closes Task 5-a gap R2 sibling).
     * v6.4.0: honra forceNoRaw/forceNoDng (desativa quando user pediu JPEG one-click).
     */
    val exportSuperResDng: Boolean
        get() = if (forceNoRaw || forceNoDng) false
        else currentConfig?.advanced?.exportSuperResDng ?: true

    val gainmapJpegQuality: Int get() = currentConfig?.advanced?.gainmapJpegQuality ?: 100
    val ultraHdrQuality: Int get() = currentConfig?.advanced?.ultraHdrQuality ?: 100
    val heicQuality: Int get() = currentConfig?.advanced?.heicQuality ?: 100
    val softwareBranding: String get() = currentConfig?.advanced?.softwareBranding ?: "LeicaCamera"
    val forceHighQualityIsp: Boolean get() = currentConfig?.advanced?.forceHighQualityIsp ?: true

    // ═══════════════════════════════════════════════════════════════════════════
    // ACCESSORS — sensors (MAX BITS per-sensor v6.1)
    // ═══════════════════════════════════════════════════════════════════════════

    val mainBlackLevel: Int get() = currentConfig?.sensors?.mainBlackLevel ?: 1024
    val mainWhiteLevel: Int get() = currentConfig?.sensors?.mainWhiteLevel ?: 16383
    val mainCfaMode: String get() = currentConfig?.sensors?.mainCfaMode ?: "4x4_RGGB"
    val uwBlackLevel: Int get() = currentConfig?.sensors?.uwBlackLevel ?: 64
    val uwWhiteLevel: Int get() = currentConfig?.sensors?.uwWhiteLevel ?: 1023
    val uwCfaMode: String get() = currentConfig?.sensors?.uwCfaMode ?: "2x2_GBRG"
    val teleBlackLevel: Int get() = currentConfig?.sensors?.teleBlackLevel ?: 64
    val teleWhiteLevel: Int get() = currentConfig?.sensors?.teleWhiteLevel ?: 1023
    val teleCfaMode: String get() = currentConfig?.sensors?.teleCfaMode ?: "2x2_BGGR"
    val frontBlackLevel: Int get() = currentConfig?.sensors?.frontBlackLevel ?: 64
    val frontWhiteLevel: Int get() = currentConfig?.sensors?.frontWhiteLevel ?: 1023
    val frontCfaMode: String get() = currentConfig?.sensors?.frontCfaMode ?: "2x2_BGGR"
    val mtkRawBpp: Int get() = currentConfig?.sensors?.mtkRawBpp ?: 14

    // ═══════════════════════════════════════════════════════════════════════════
    // ACCESSORS — per_lens (BEAT GCam per-lens tuning)
    // ═══════════════════════════════════════════════════════════════════════════

    /** Per-lens raw accessors (nullable pra caller decidir fallback). */
    val perLensMain: PerLensTuning? get() = currentConfig?.perLens?.main
    val perLensUw: PerLensTuning? get() = currentConfig?.perLens?.uw
    val perLensTele: PerLensTuning? get() = currentConfig?.perLens?.tele
    val perLensFront: PerLensTuning? get() = currentConfig?.perLens?.front

    /** frame count pra lensKey — fallback pra global multiFrameCount. */
    fun frameCountForLens(lensKey: String): Int {
        val pl = perLensForKey(lensKey) ?: return multiFrameCount
        val raw = pl.frameCount ?: return multiFrameCount
        return if (raw > 0) raw else multiFrameCount
    }

    /** EV compensation per-lens. */
    fun evCompForLens(lensKey: String): Float {
        val pl = perLensForKey(lensKey) ?: return 0.0f
        return pl.evComp ?: 0.0f
    }

    /** Gamma preset per-lens (sentinel -1 = usar control direto). */
    fun gammaPresetForLens(lensKey: String): Int {
        val pl = perLensForKey(lensKey) ?: return -1
        return pl.gammaPreset ?: -1
    }

    /** AWB mode per-lens (sentinel -1 = usar control direto). */
    fun awbModeForLens(lensKey: String): Int {
        val pl = perLensForKey(lensKey) ?: return -1
        return pl.awbMode ?: -1
    }

    /** Noise model preset per-lens (sentinel -1 = usar direct control via NoiseModelFallback). */
    fun noiseModelPresetForLens(lensKey: String): Int {
        val pl = perLensForKey(lensKey) ?: return -1
        return pl.noiseModelPreset ?: -1
    }

    /** CCM preset per-lens (sentinel -1 = usar direct control via ccmRatioWarm/Cool). */
    fun ccmPresetForLens(lensKey: String): Int {
        val pl = perLensForKey(lensKey) ?: return -1
        return pl.ccmPreset ?: -1
    }

    /** Sharpening multiplier per-lens (1.0 = global default). */
    fun sharpeningMultiplierForLens(lensKey: String): Float {
        val pl = perLensForKey(lensKey) ?: return 1.0f
        return pl.sharpeningMultiplier ?: 1.0f
    }

    /** Luma NR multiplier per-lens. */
    fun lumaNrMultiplierForLens(lensKey: String): Float {
        val pl = perLensForKey(lensKey) ?: return 1.0f
        return pl.lumaNrMultiplier ?: 1.0f
    }

    /** Chroma NR multiplier per-lens. */
    fun chromaNrMultiplierForLens(lensKey: String): Float {
        val pl = perLensForKey(lensKey) ?: return 1.0f
        return pl.chromaNrMultiplier ?: 1.0f
    }

    /** Tint shift per-lens (negativo = mais verde, positivo = mais magenta). */
    fun tintShiftForLens(lensKey: String): Int {
        val pl = perLensForKey(lensKey) ?: return 0
        return pl.tintShift ?: 0
    }

    /** Highlight compression EV per-lens (negativo = mais compressão). */
    fun highlightCompressionEvForLens(lensKey: String): Float {
        val pl = perLensForKey(lensKey) ?: return 0.0f
        return pl.highlightCompressionEv ?: 0.0f
    }

    /** Saturação vermelho per-lens. */
    fun saturationRedForLens(lensKey: String): Float {
        val pl = perLensForKey(lensKey) ?: return 1.0f
        return pl.saturationRed ?: 1.0f
    }

    /** Saturação verde per-lens. */
    fun saturationGreenForLens(lensKey: String): Float {
        val pl = perLensForKey(lensKey) ?: return 1.0f
        return pl.saturationGreen ?: 1.0f
    }

    /** Saturação azul per-lens. */
    fun saturationBlueForLens(lensKey: String): Float {
        val pl = perLensForKey(lensKey) ?: return 1.0f
        return pl.saturationBlue ?: 1.0f
    }

    /** Skin warmth (Kelvin shift) — front-only. */
    fun skinWarmthKForFront(): Int {
        val pl = perLensFront ?: return 0
        return pl.skinWarmthK ?: 0
    }

    /** Beauty filter toggle — front-only (default OFF). */
    fun beautyFilterForFront(): Boolean {
        val pl = perLensFront ?: return false
        return pl.beautyFilter ?: false
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // ACCESSORS — color_science (per-channel tint/saturation)
    // ═══════════════════════════════════════════════════════════════════════════

    val colorTintShift: Int get() = currentConfig?.colorScience?.tintShift ?: -12
    val saturationRedPct: Int get() = currentConfig?.colorScience?.saturationRedPct ?: -5
    val saturationGreenPct: Int get() = currentConfig?.colorScience?.saturationGreenPct ?: -10
    val saturationBluePct: Int get() = currentConfig?.colorScience?.saturationBluePct ?: -7
    val colorScienceHighlightCompressionEv: Float
        get() = currentConfig?.colorScience?.highlightCompressionEv ?: -0.15f
    val skinToneProtection: Boolean get() = currentConfig?.colorScience?.skinToneProtection ?: true
    val perChannelTintRed: Int get() = currentConfig?.colorScience?.perChannelTintRed ?: 0
    val perChannelTintGreen: Int get() = currentConfig?.colorScience?.perChannelTintGreen ?: 0
    val perChannelTintBlue: Int get() = currentConfig?.colorScience?.perChannelTintBlue ?: 0

    // ═══════════════════════════════════════════════════════════════════════════
    // ACCESSORS — video (MAX HEVC 250Mbps B-frames=2 log HDR10)
    // ═══════════════════════════════════════════════════════════════════════════

    val videoCodec: String get() = currentConfig?.video?.codec ?: "hevc"
    val videoBitrateMbps: Int get() = currentConfig?.video?.bitrateMbps ?: 250
    val videoMaxBFrames: Int get() = currentConfig?.video?.maxBFrames ?: 2
    val videoColorProfile: String get() = currentConfig?.video?.colorProfile ?: "log"
    val videoDefaultResolution: String get() = currentConfig?.video?.defaultResolution ?: "2160p"
    val videoDefaultFps: Int get() = currentConfig?.video?.defaultFps ?: 30
    val videoHdr: Boolean get() = currentConfig?.video?.hdrVideo ?: true
    val videoAudioCodec: String get() = currentConfig?.video?.audioCodec ?: "aac"
    val videoAudioBitrateKbps: Int get() = currentConfig?.video?.audioBitrateKbps ?: 256
    val videoAudioSampleRate: Int get() = currentConfig?.video?.audioSampleRate ?: 48000
    val videoIFrameIntervalSec: Int get() = currentConfig?.video?.iFrameIntervalSec ?: 1
    val videoRateControl: String get() = currentConfig?.video?.rateControl ?: "vbr"

    // ═══════════════════════════════════════════════════════════════════════════
    // v6.3.7 NEW: Enum-name helpers — map LeicaConfig values to PhotonCamera enum names
    // Used by P-55 in UserPreferencesRepository.kt to force video settings from JSON config.
    // Without these, the app uses hardcoded defaults (FHD_1080P/FPS_30/P1/H264/OFF)
    // and ignores LeicaConfig video values entirely.
    // ═══════════════════════════════════════════════════════════════════════════

    /** Maps videoDefaultResolution ("2160p"/"1080p"/"720p") to VideoResolutionPreset enum name. */
    val videoDefaultResolutionEnum: String
        get() = when (videoDefaultResolution.lowercase()) {
            "2160p", "4k", "uhd" -> "UHD_2160P"
            "1080p", "fhd" -> "FHD_1080P"
            "720p", "hd" -> "HD_720P"
            else -> "UHD_2160P"
        }

    /** Maps videoDefaultFps (Int) to VideoFpsPreset enum name (FPS_24/FPS_25/FPS_30/FPS_50/FPS_60). */
    val videoDefaultFpsEnum: String
        get() = when (videoDefaultFps) {
            24 -> "FPS_24"
            25 -> "FPS_25"
            30 -> "FPS_30"
            50 -> "FPS_50"
            60 -> "FPS_60"
            else -> "FPS_30"
        }

    /** Maps videoCodec ("hevc"/"h264") to VideoCodec enum name (H265/H264). */
    val videoDefaultCodecEnum: String
        get() = if (videoCodec.equals("hevc", ignoreCase = true)) "H265" else "H264"

    /** Maps effectiveVideoBitrateMbps (Int) to VideoBitratePreset enum name (P1=30/P2=60/P3=90/P4=120/P5=250). */
    val videoDefaultBitrateEnum: String
        get() = when {
            effectiveVideoBitrateMbps >= 250 -> "P5"
            effectiveVideoBitrateMbps >= 120 -> "P4"
            effectiveVideoBitrateMbps >= 90 -> "P3"
            effectiveVideoBitrateMbps >= 60 -> "P2"
            else -> "P1"
        }

    /** Maps videoColorProfile ("log"/"off") to VideoLogProfile enum name (LLOG_BT2020/OFF). */
    val videoDefaultLogProfileEnum: String
        get() = if (videoColorProfile.equals("log", ignoreCase = true)) "LLOG_BT2020" else "OFF"

    // ═══════════════════════════════════════════════════════════════════════════
    // v6.3.8 NEW — Video encoder completeness (P-56)
    // ═══════════════════════════════════════════════════════════════════════════

    /** Maps videoRateControl ("vbr"/"cbr"/"cqp") to MediaCodecInfo.EncoderCapabilities constant. */
    val videoBitrateMode: Int
        get() = when (videoRateControl.lowercase()) {
            "cbr" -> mediaCodecInfoConst("BITRATE_MODE_CBR", 2)
            "cqp", "cq" -> mediaCodecInfoConst("BITRATE_MODE_CQ", 0)
            else -> mediaCodecInfoConst("BITRATE_MODE_VBR", 1)
        }

    /** Maps videoAudioCodec ("aac"/"opus") to MediaFormat mime type. */
    val videoAudioMimeType: String
        get() = when (videoAudioCodec.lowercase()) {
            "opus" -> android.media.MediaFormat.MIMETYPE_AUDIO_OPUS
            else   -> android.media.MediaFormat.MIMETYPE_AUDIO_AAC
        }

    /** True if videoHdr is enabled AND codec is HEVC (HDR10 requires HEVC). */
    val videoHdr10Enabled: Boolean
        get() = videoHdr && videoCodec.equals("hevc", ignoreCase = true)

    /**
     * videoHdr10StaticInfo — CTA-861.3 HDR10 static metadata block (24 bytes).
     * BT.2020 primaries + D65 white point + 1000 cd/m² peak + 400 nits CLL.
     * Returns null when videoHdr is disabled or codec is not HEVC.
     */
    val videoHdr10StaticInfo: java.nio.ByteBuffer?
        get() {
            if (!videoHdr10Enabled) return null
            val buf = java.nio.ByteBuffer.allocate(24)
            buf.order(java.nio.ByteOrder.LITTLE_ENDIAN)
            // BT.2020 / DCI-P3 D65 primaries in 0.00002 cd/m² units (unsigned 16-bit).
            // .toShort() yields the correct bit pattern for values > 32767 (signed wraparound).
            // v6.3.8-fix1: fixes Kotlin compile error (Int→Short type mismatch on 35400/39850).
            buf.putShort(35400.toShort()).putShort(14600.toShort())   // R_x, R_y
            buf.putShort(8500.toShort() ).putShort(39850.toShort())   // G_x, G_y
            buf.putShort(6550.toShort() ).putShort(2300.toShort())    // B_x, B_y
            buf.putShort(15635.toShort()).putShort(16450.toShort())   // W_x, W_y
            buf.putShort(1000.toShort())                              // max display mastering luminance
            buf.putShort(1.toShort())                                 // min display mastering luminance
            buf.putShort(400.toShort())                               // max content light level (CLL)
            buf.putShort(180.toShort())                               // max frame-average light level (FALL)
            buf.flip()
            return buf
        }

    /** Helper: look up MediaCodecInfo.EncoderCapabilities constant by name (defensive). */
    private fun mediaCodecInfoConst(name: String, default: Int): Int = try {
        val cls = Class.forName("android.media.MediaCodecInfo\$EncoderCapabilities")
        val field = cls.getDeclaredField(name)
        field.getInt(null)
    } catch (_: Throwable) {
        default
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // v6.3.8 NEW — Camera2 direct params per-lens (P-57)
    // Sentinel -1 = use app default (don't override)
    // Values are Camera2 constants: EDGE_MODE_*, NOISE_REDUCTION_MODE_*, SHADING_MODE_*,
    // HOT_PIXEL_MODE_*, TONEMAP_MODE_*
    // ═══════════════════════════════════════════════════════════════════════════

    /** EDGE_MODE per-lens (-1 = use app default level). */
    fun edgeModeForLens(lensKey: String): Int {
        val tuning = perLensForKey(lensKey) ?: return -1
        return tuning.edgeMode ?: -1
    }

    /** Camera2 hardware NOISE_REDUCTION_MODE per-lens (-1 = use app default level). */
    fun camera2NoiseReductionModeForLens(lensKey: String): Int {
        val tuning = perLensForKey(lensKey) ?: return -1
        return tuning.camera2NoiseReductionMode ?: -1
    }

    /** SHADING_MODE per-lens (-1 = use app default). */
    fun shadingModeForLens(lensKey: String): Int {
        val tuning = perLensForKey(lensKey) ?: return -1
        return tuning.shadingMode ?: -1
    }

    /** HOT_PIXEL_MODE per-lens (-1 = use app default). */
    fun hotPixelModeForLens(lensKey: String): Int {
        val tuning = perLensForKey(lensKey) ?: return -1
        return tuning.hotPixelMode ?: -1
    }

    /** TONEMAP_MODE per-lens — String ("SYSTEM_DEFAULT"/"SRGB"/"FAST"/"HQ"). null = use app default. */
    fun tonemapModeForLens(lensKey: String): String? {
        val tuning = perLensForKey(lensKey) ?: return null
        return tuning.tonemapMode?.let { mode ->
            when {
                mode == -1 -> null
                mode == 0 -> "SYSTEM_DEFAULT"
                mode == 1 -> "SRGB"
                mode == 2 -> "FAST"
                mode == 3 -> "HQ"
                else -> null
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // ACCESSORS — noise_model_fallback (physics-derived quadratic coefficients)
    // ═══════════════════════════════════════════════════════════════════════════

    val noiseModelFallbackEnabled: Boolean get() = currentConfig?.noiseModelFallback?.enabled ?: true

    /**
     * noiseModelForLens — retorna coeficientes quadráticos (a/b/c/d) pra lensKey.
     * Usado pelo patch P-36 quando HAL não reporta noise profile.
     * PC's RawNoiseModel usa linear S+O: shotNoise=b, readNoise=d (a/c descartados).
     * Retorna null se fallback desabilitado ou config ausente.
     */
    fun noiseModelForLens(lensKey: String): NoiseModelCoefficients? {
        val cfg = currentConfig ?: return null
        val nmf = cfg.noiseModelFallback ?: return null
        if (nmf.enabled != true) return null
        return when (lensKey.lowercase()) {
            "main" -> nmf.main
            "uw" -> nmf.uw
            "tele" -> nmf.tele
            "front" -> nmf.front
            else -> nmf.main
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // v6.1 NEW ACCESSORS — Per-lens AgX/DCP direct control + black/white level
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * gammaContrastForLens — contraste AgX per-lens.
     * Fallback pro global toneMappingContrast quando sentinel -1.
     */
    fun gammaContrastForLens(lensKey: String): Double {
        val pl = perLensForKey(lensKey)
        val v = pl?.gammaContrast ?: -1f
        return if (v >= 0f) v.toDouble() else toneMappingContrast
    }

    /** gammaShoulderForLens — shoulder AgX per-lens (fallback pro global). */
    fun gammaShoulderForLens(lensKey: String): Double {
        val pl = perLensForKey(lensKey)
        val v = pl?.gammaShoulder ?: -1f
        return if (v >= 0f) v.toDouble() else toneMappingHighlightRolloff
    }

    /** gammaShadowLiftForLens — shadow lift AgX per-lens (fallback pro global). */
    fun gammaShadowLiftForLens(lensKey: String): Double {
        val pl = perLensForKey(lensKey)
        val v = pl?.gammaShadowLift ?: -1f
        return if (v >= 0f) v.toDouble() else toneMappingShadowLift
    }

    /** ccmRatioWarmForLens — ratio DCP warm per-lens (fallback pro global dcpRatioWarm). */
    fun ccmRatioWarmForLens(lensKey: String): Float {
        val pl = perLensForKey(lensKey)
        val v = pl?.ccmRatioWarm ?: -1f
        return if (v >= 0f) v else dcpRatioWarm
    }

    /** ccmRatioCoolForLens — ratio DCP cool per-lens (fallback pro global dcpRatioCool). */
    fun ccmRatioCoolForLens(lensKey: String): Float {
        val pl = perLensForKey(lensKey)
        val v = pl?.ccmRatioCool ?: -1f
        return if (v >= 0f) v else dcpRatioCool
    }

    /** blackLevelForLens — pedestal óptico per-sensor (v6.1 fecha Task 5-a gap B2). */
    fun blackLevelForLens(lensKey: String): Int {
        val cfg = currentConfig ?: return 64
        val s = cfg.sensors ?: return 64
        return when (lensKey.lowercase()) {
            "main" -> s.mainBlackLevel ?: 1024
            "uw" -> s.uwBlackLevel ?: 64
            "tele" -> s.teleBlackLevel ?: 64
            "front" -> s.frontBlackLevel ?: 64
            else -> s.mainBlackLevel ?: 1024
        }
    }

    /** whiteLevelForLens — full-well max per-sensor (v6.1 fecha Task 5-a gap B2). */
    fun whiteLevelForLens(lensKey: String): Int {
        val cfg = currentConfig ?: return 1023
        val s = cfg.sensors ?: return 1023
        return when (lensKey.lowercase()) {
            "main" -> s.mainWhiteLevel ?: 16383
            "uw" -> s.uwWhiteLevel ?: 1023
            "tele" -> s.teleWhiteLevel ?: 1023
            "front" -> s.frontWhiteLevel ?: 1023
            else -> s.mainWhiteLevel ?: 16383
        }
    }

    /** cfaModeForLens — layout CFA per-sensor (v6.1 fecha Task 5-a gaps B3/B4). */
    fun cfaModeForLens(lensKey: String): String {
        val cfg = currentConfig ?: return "4x4_RGGB"
        val s = cfg.sensors ?: return "4x4_RGGB"
        return when (lensKey.lowercase()) {
            "main" -> s.mainCfaMode ?: "4x4_RGGB"
            "uw" -> s.uwCfaMode ?: "2x2_GBRG"
            "tele" -> s.teleCfaMode ?: "2x2_BGGR"
            "front" -> s.frontCfaMode ?: "2x2_BGGR"
            else -> s.mainCfaMode ?: "4x4_RGGB"
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // v6.1 Sentinel -1 helpers — pra callers decidirem entre preset lookup e direct control
    // ═══════════════════════════════════════════════════════════════════════════

    /** usesDirectGammaControl — true quando gamma_preset == -1 (usar gammaContrast/Shoulder/ShadowLift). */
    fun usesDirectGammaControl(lensKey: String): Boolean = gammaPresetForLens(lensKey) == -1

    /** usesDirectCcmControl — true quando ccm_preset == -1 (usar ccmRatioWarm/Cool). */
    fun usesDirectCcmControl(lensKey: String): Boolean = ccmPresetForLens(lensKey) == -1

    /** usesDirectAwbControl — true quando awb_mode == -1 (usar control contínuo). */
    fun usesDirectAwbControl(lensKey: String): Boolean = awbModeForLens(lensKey) == -1

    /** usesDirectNoiseModelControl — true quando noise_model_preset == -1 (usar coeficientes a/b/c/d). */
    fun usesDirectNoiseModelControl(lensKey: String): Boolean = noiseModelPresetForLens(lensKey) == -1

    // ═══════════════════════════════════════════════════════════════════════════
    // v6.2 NEW ACCESSORS — Creative Profiles (LUT/DCP/frame/tone)
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * activeCreativeProfile — resolve o perfil criativo ativo (CreativeProfile object).
     * v6.3.4: usa activeCreativeProfileId (que checa LeicaRuntimeState override primeiro).
     * Null se creative_profiles section ausente OU ID não está no map.
     */
    val activeCreativeProfile: CreativeProfile?
        get() = currentConfig?.creativeProfiles?.let { cp ->
            cp.profiles?.get(activeCreativeProfileId)
        }

    /** activeCreativeProfileId — ID do perfil ativo (default "leica_authentic").
     *  v6.3.4: checa LeicaRuntimeState.creativeProfileOverride primeiro (setado pelo menu). */
    val activeCreativeProfileId: String
        get() = LeicaRuntimeState.creativeProfileOverride
            ?: currentConfig?.creativeProfiles?.activeProfile
            ?: "leica_authentic"

    /** activeLutId — LUT ID do perfil ativo (default "leica_m9"). */
    val activeLutId: String
        get() = activeCreativeProfile?.lutId ?: "leica_m9"

    /** activeDcpId — DCP ID do perfil ativo (default Leica M8). */
    val activeDcpId: String
        get() = activeCreativeProfile?.dcpId ?: "builtin_dcp_Leica M8 Camera Standard"

    /** activeFrameId — Frame ID do perfil ativo (default "leica"). */
    val activeFrameId: String
        get() = activeCreativeProfile?.frameId ?: "03_no_frame"

    /** activeToneContrastBoost — boost de contraste do perfil ativo (default 0.0 neutral). */
    val activeToneContrastBoost: Float
        get() = activeCreativeProfile?.toneContrastBoost ?: 0.0f

    /** activeToneWarmthShiftK — shift de Kelvin do perfil ativo (default 0; negativo = mais frio). */
    val activeToneWarmthShiftK: Int
        get() = activeCreativeProfile?.toneWarmthShiftK ?: 0

    /** activeSaturationMultiplier — multiplicador de saturação do perfil ativo (default 1.0). */
    val activeSaturationMultiplier: Float
        get() = activeCreativeProfile?.saturationMultiplier ?: 1.0f

    /** activeBestLens — lens recomendado pro perfil ativo (default "main"). */
    val activeBestLens: String
        get() = activeCreativeProfile?.bestLens ?: "main"

    /** availableCreativeProfiles — todos os perfis disponíveis (Map<ID, CreativeProfile>). */
    val availableCreativeProfiles: Map<String, CreativeProfile>
        get() = currentConfig?.creativeProfiles?.profiles ?: emptyMap()

    /** creativeProfileById — busca um perfil por ID (null se não existir). */
    fun creativeProfileById(id: String): CreativeProfile? {
        return currentConfig?.creativeProfiles?.profiles?.get(id)
    }

    /** creativeProfilesForScene — filtra perfis pela cena (recommended_for contains scene). */
    fun creativeProfilesForScene(scene: String): List<CreativeProfile> {
        val profiles = currentConfig?.creativeProfiles?.profiles ?: return emptyList()
        return profiles.values.filter { scene in (it.recommendedFor ?: emptyList()) }
    }

    /** isActiveProfileBaseline — true quando perfil ativo == "leica_authentic" (baseline neutro). */
    val isActiveProfileBaseline: Boolean
        get() = activeCreativeProfileId == "leica_authentic"

    // ═══════════════════════════════════════════════════════════════════════════
    // v6.2 Effective accessors — combinam global + creative profile boost
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * effectiveToneContrast — combina toneMappingContrast global + activeToneContrastBoost.
     * Exemplo: global 1.10 + boost 0.10 → 1.20 (clampado a 0.5..2.0 pra segurança).
     */
    val effectiveToneContrast: Double
        get() {
            val base = toneMappingContrast
            val boost = activeToneContrastBoost.toDouble()
            return (base + boost).coerceIn(0.5, 2.0)
        }

    /**
     * effectiveSaturationBoost — combina colorSaturationBoost + activeSaturationMultiplier.
     * Exemplo: global 1.02 × multiplier 1.18 → 1.20 (clampado a 0.0..2.0).
     */
    val effectiveSaturationBoost: Double
        get() {
            val base = colorSaturationBoost
            val mult = activeSaturationMultiplier.toDouble()
            return (base * mult).coerceIn(0.0, 2.0)
        }

    /**
     * effectiveWarmthShiftK — shift total de Kelvin (creative profile only).
     * Negativo = mais frio (azul), positivo = mais quente (âmbar).
     */
    val effectiveWarmthShiftK: Int
        get() = activeToneWarmthShiftK

    // ═══════════════════════════════════════════════════════════════════════════
    // SERIALIZER
    // ═══════════════════════════════════════════════════════════════════════════

    private val gson = Gson()

    /** Serializa o config atual de volta pra JSON string (pra debug/save). */
    fun toJson(): String {
        return try {
            gson.toJson(currentConfig ?: LeicaPerfectConfig())
        } catch (t: Throwable) {
            Log.e(TAG, "toJson failed", t)
            "{}"
        }
    }

    /** Setter direto (pra testes/injeção). */
    fun setConfig(config: LeicaPerfectConfig?) {
        currentConfig = config
        lastLoadMs = System.currentTimeMillis()
    }

    /** Verifica se config foi carregado. */
    val isLoaded: Boolean get() = currentConfig != null

    /** Timestamp do último load (ms epoch). */
    val lastLoadTimestamp: Long get() = lastLoadMs

    /** Diagnóstico pra logging — conta perfis criativos disponíveis. */
    val creativeProfileCount: Int
        get() = currentConfig?.creativeProfiles?.profiles?.size ?: 0

    /** Diagnóstico — resumo legível do estado. */
    fun diagnosticSummary(): String {
        val cfg = currentConfig ?: return "LeicaConfig: NOT LOADED (using defaults)"
        return buildString {
            append("LeicaConfig v${cfg.meta?.version ?: "?"} ")
            append("(schema v${cfg.meta?.schemaVersion ?: "?"}) — ")
            append("${countSections(cfg)} sections, ")
            append("${cfg.creativeProfiles?.profiles?.size ?: 0} creative profiles, ")
            append("active=${cfg.creativeProfiles?.activeProfile ?: "leica_authentic"}, ")
            append("capture_mode=${cfg.captureModes?.activeCaptureMode ?: "mode_fast"}, ")
            append("loaded ${if (isLoaded) "OK" else "FAIL"}")
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Static defaults (para accessors que precisam de fallback não-JSON)
    // ═══════════════════════════════════════════════════════════════════════════

    /** Default AgX toe power (1.6). */
    const val DEFAULT_AGX_TOE_POWER = 1.6

    /** Default AgX shoulder power (1.3). */
    const val DEFAULT_AGX_SHOULDER_POWER = 1.3

    /** Default AgX black relative exposure (0.002 — para evitar log(0)). */
    const val DEFAULT_AGX_BLACK_RELATIVE_EXPOSURE = 0.002f

    /** Default AgX white relative exposure (8.0 — entre 4 e 16, logs típicos). */
    const val DEFAULT_AGX_WHITE_RELATIVE_EXPOSURE = 8.0f

    /**
     * agxWhiteRelativeExposure — usado pelo RawToneMappingParameters.
     * v6.2: usa effectiveToneContrast pra derivar o white point (quanto mais contraste,
     * menor o white exposure = highlights mais comprimidos).
     */
    val agxWhiteRelativeExposure: Float
        get() {
            val contrast = effectiveToneContrast.toFloat()
            // Mais contraste → white point cai (highlights rolam mais cedo)
            return (DEFAULT_AGX_WHITE_RELATIVE_EXPOSURE / contrast).coerceIn(2.0f, 16.0f)
        }

    /** agxToePower — derivado do pgtmToePower + boost de creative profile. */
    val agxToePower: Double
        get() = (pgtmToePower + activeToneContrastBoost.toDouble() * 0.5)
            .coerceIn(1.0, 3.0)

    /** agxShoulderPower — derivado do pgtmShoulderPower. */
    val agxShoulderPower: Double
        get() = pgtmShoulderPower.coerceIn(0.8, 2.5)

    /** agxBlackRelativeExposure — floor pra evitar log(0). */
    val agxBlackRelativeExposure: Float
        get() = DEFAULT_AGX_BLACK_RELATIVE_EXPOSURE

    /** Diagnóstico estático — versão do companion. */
    const val COMPANION_VERSION = "v6.2.0 — DEFINITIVE QUALITY"

    // ═══════════════════════════════════════════════════════════════════════════
    // Camera2Controller integration helpers
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * lensKeyFromController — wrapper conveniente pra extrair lensKey do Camera2Controller.
     * Usa CameraCharacteristics se disponível, senão fallback pra cameraId string.
     */
    fun lensKeyFromController(controller: Camera2Controller?, cameraId: String): String {
        // Tentativa 1: CameraCharacteristics (via LENS_FACING + focal length)
        // Tentativa 2: cameraId string (mapeamento dizi 0/2/4/1)
        return lensKeyFromCameraId(cameraId)
    }

    /**
     * resolveCaptureModeForScene — recomenda capture mode baseado na cena.
     * Heurística simples: low-light estática → mode_max; else → mode_fast.
     * v6.4.0: mode_balanced removido; action/burst/casual agora usa mode_fast (disparo rapido inteligente).
     * (Não é override — apenas sugestão. O usuário escolhe explicitamente.)
     */
    fun resolveCaptureModeForScene(sceneLux: Float, isStatic: Boolean): String {
        return when {
            isStatic && sceneLux < 100f -> "mode_max"
            else -> "mode_fast"
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Effective capture params — combina multi_frame + capture_mode + per_lens
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * effectiveFrameCountForLens — combina per-lens frame_count × capture_mode multiplier.
     * Exemplo: main 15 × mode_balanced 0.6 = 9 frames (clampado 3..20).
     */
    fun effectiveFrameCountForLens(lensKey: String): Int {
        val perLensCount = frameCountForLens(lensKey)
        val multiplier = captureModeFrameCountMultiplier
        val raw = (perLensCount * multiplier).toInt()
        return raw.coerceIn(3, 20)
    }

    /**
     * effectiveSuperResolutionScale — combina multi_frame scale × capture_mode scale.
     * mode_max = 2.0× (full SR), mode_balanced/fast = 1.0× (sem SR).
     */
    val effectiveSuperResolutionScale: Float
        get() = (multiFrameSuperResolutionScale * captureModeSuperResolutionScale)
            .coerceIn(1.0f, 2.0f)

    /**
     * effectiveNlmSearchRadius — capture_mode NLM radius override.
     * mode_max=7, mode_balanced=5.
     */
    val effectiveNlmSearchRadius: Int
        get() = captureModeNlmSearchRadius.coerceIn(3, 9)

    /**
     * effectiveForceRawmax — true se multi_frame.force_rawmax AND capture_mode.force_rawmax.
     * mode_balanced mantém RAWmax ligado (qualidade priorizada).
     */
    val effectiveForceRawmax: Boolean
        get() = multiFrameForceRawmax && captureModeForceRawmax

    /**
     * effectiveVideoBitrateMbps — capture_mode override do video bitrate.
     * mode_max=250, mode_balanced=120.
     */
    val effectiveVideoBitrateMbps: Int
        get() = captureModeVideoBitrateMbps.coerceIn(40, 600)

    // ═══════════════════════════════════════════════════════════════════════════
    // Effective accessors combining creative profile + per-lens
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * effectiveTintShiftForLens — combina per-lens tint_shift + color_science tint_shift.
     * Per-lens tem prioridade (mais específico); color_science aplica como offset global.
     */
    fun effectiveTintShiftForLens(lensKey: String): Int {
        val perLens = tintShiftForLens(lensKey)
        val global = colorTintShift
        return perLens + global / 2  // global diluido (50%) pra não dominar
    }

    /**
     * effectiveHighlightCompressionForLens — combina per-lens + color_science.
     */
    fun effectiveHighlightCompressionForLens(lensKey: String): Float {
        val perLens = highlightCompressionEvForLens(lensKey)
        val global = colorScienceHighlightCompressionEv
        return (perLens + global / 2).coerceIn(-1.0f, 0.0f)
    }

    /**
     * effectiveSaturationForLens — combina per-lens saturation_R/G/B × creative profile saturation_multiplier.
     */
    fun effectiveSaturationForLens(lensKey: String, channel: String): Float {
        val perLens = when (channel.lowercase()) {
            "r", "red" -> saturationRedForLens(lensKey)
            "g", "green" -> saturationGreenForLens(lensKey)
            "b", "blue" -> saturationBlueForLens(lensKey)
            else -> 1.0f
        }
        val mult = activeSaturationMultiplier
        return (perLens * mult).coerceIn(0.0f, 2.0f)
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Quality diagnostics
    // ═══════════════════════════════════════════════════════════════════════════

    /** Verifica se todos os componentes críticos estão ativos (para debugging). */
    fun verifyCriticalSettings(): List<String> {
        val issues = mutableListOf<String>()
        if (!multiFrameEnabled) issues += "multiFrame disabled — fallback pra single frame"
        if (!multiFrameForceRawmax) issues += "forceRawmax off — YUV pipeline ao invés de RAW"
        if (!forceHeicExport) issues += "HEIC export off — usando JPEG"
        if (outputQuality < 100) issues += "outputQuality=${outputQuality} (deveria ser 100)"
        if (gainmapJpegQuality < 100) issues += "gainmapJpegQuality=${gainmapJpegQuality} (deveria ser 100)"
        if (!forceHighQualityIsp) issues += "forceHighQualityIsp off — ISP em FAST mode"
        if (videoCodec != "hevc") issues += "videoCodec=$videoCodec (deveria ser hevc)"
        if (videoBitrateMbps < 120) issues += "videoBitrate=${videoBitrateMbps}Mbps (<120 Mbps)"
        if (!isActiveProfileBaseline && !availableCreativeProfiles.containsKey(activeCreativeProfileId)) {
            issues += "active creative profile '$activeCreativeProfileId' não está no map"
        }
        if (!noiseModelFallbackEnabled) issues += "noiseModelFallback disabled — HAL falha = noise profile vazio"
        return issues
    }

    /** Estado completo pra logging estruturado. */
    fun fullStateReport(): String = buildString {
        appendLine("=== LeicaConfig v6.2.0 — DEFINITIVE QUALITY ===")
        appendLine("Schema: v$configSchemaVersion | Loaded: $isLoaded | Sections: ${currentConfig?.let { countSections(it) } ?: 0}")
        appendLine("Active Capture Mode: $activeCaptureMode (frame_mult=${captureModeFrameCountMultiplier}, SR_scale=${captureModeSuperResolutionScale}, NLM=${captureModeNlmSearchRadius}, thermal=${captureModeThermalThrottleAtC}°C)")
        appendLine("Active Creative Profile: $activeCreativeProfileId (baseline=$isActiveProfileBaseline, LUT=$activeLutId, DCP=$activeDcpId, frame=$activeFrameId)")
        appendLine("  Tone boost=${activeToneContrastBoost} | Warmth shift=${activeToneWarmthShiftK}K | Saturation mult=${activeSaturationMultiplier}")
        appendLine("Effective Tone Contrast: $effectiveToneContrast | Effective Saturation: $effectiveSaturationBoost | Effective Warmth: ${effectiveWarmthShiftK}K")
        appendLine("Per-lens frame counts: main=${frameCountForLens("main")} uw=${frameCountForLens("uw")} tele=${frameCountForLens("tele")} front=${frameCountForLens("front")}")
        appendLine("Per-lens gamma_contrast: main=${gammaContrastForLens("main")} uw=${gammaContrastForLens("uw")} tele=${gammaContrastForLens("tele")} front=${gammaContrastForLens("front")}")
        appendLine("Per-lens tint_shift: main=${tintShiftForLens("main")} uw=${tintShiftForLens("uw")} tele=${tintShiftForLens("tele")} front=${tintShiftForLens("front")}")
        appendLine("Sensors: main=14-bit(16383/1024), uw/tele/front=10-bit(1023/64), mtk_bpp=$mtkRawBpp")
        appendLine("Video: $videoCodec ${effectiveVideoBitrateMbps}Mbps B-frames=$videoMaxBFrames $videoColorProfile $videoDefaultResolution@$videoDefaultFps HDR=$videoHdr")
        appendLine("Creative profiles available: $creativeProfileCount (active=$activeCreativeProfileId)")
        appendLine("Super res scale: $effectiveSuperResolutionScale | NLM radius: $effectiveNlmSearchRadius | Force RAWmax: $effectiveForceRawmax")
        val issues = verifyCriticalSettings()
        if (issues.isEmpty()) {
            appendLine("Critical settings: ALL OK ✓")
        } else {
            appendLine("Critical settings issues (${issues.size}):")
            issues.forEach { appendLine("  ⚠️ $it") }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // INIT — auto-load em primeiro acesso
    // ═══════════════════════════════════════════════════════════════════════════

    init {
        // Tenta carregar do path default (assets/leica_perfect.json copiado pelo patch P-1)
        // Em build release, o patch P-1 copia o JSON pra filesDir/leica_perfect.json
        // Aqui apenas logamos — o load real acontece quando CameraApp chama loadFromFile().
        Log.i(TAG, "LeicaConfig companion initialized — $COMPANION_VERSION (schema v$configSchemaVersion)")
    }
}
