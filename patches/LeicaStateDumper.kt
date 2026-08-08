package com.hinnka.mycamera.raw

import android.util.Log

/**
 * LeicaStateDumper — Comprehensive app state logger (v6.3.8-fix5)
 *
 * Dumps the ENTIRE LeicaConfig + LeicaRuntimeState to logcat with tag "LeicaPerfectState".
 * Designed to match the Photon logs format: one config item per line, timestamped by logcat.
 *
 * Usage: LeicaStateDumper.dump()  (called from MyCameraApplication.onCreate after all init)
 *
 * Output covers:
 *  - Meta (config version, schema, device)
 *  - Runtime state (overrides from SharedPreferences)
 *  - Capture mode (active + all modes + frame counts)
 *  - Creative profile (active + LUT/DCP + effective color science)
 *  - Multi-frame / HDR / demosaic / processing
 *  - Per-lens values (main/uw/tele/front): edgeMode, nrMode, shading, hotPixel, tonemap,
 *    whiteLevel, blackLevel, tintShift, saturation, noiseModel, frameCount, SR, EV
 *  - Video config (codec, bitrate, fps, resolution, HDR10, audio)
 *  - Output (format, quality, DNG export, gainmap)
 *  - DCP/LUT forcing
 *  - Sensors (black/white levels per lens)
 *  - Color science (effective* accessors)
 */
object LeicaStateDumper {

    private const val TAG = "LeicaPerfectState"

    /** Main entry point — call after LeicaConfig.load() + LeicaRuntimeState.init(). */
    fun dump() {
        Log.i(TAG, "═══════════════════════════════════════════════════════════════")
        Log.i(TAG, "Leica Perfect — FULL STATE DUMP (v6.3.8-fix5)")
        Log.i(TAG, "═══════════════════════════════════════════════════════════════")

        dumpMeta()
        dumpRuntimeState()
        dumpCaptureMode()
        dumpCreativeProfile()
        dumpMultiFrame()
        dumpToneMapping()
        dumpSharpeningNr()
        dumpColorScience()
        dumpDemosaic()
        dumpProcessing()
        dumpDcpLut()
        dumpOutput()
        dumpVideo()
        dumpPerLens()
        dumpSensors()
        dumpAdvanced()

        Log.i(TAG, "═══════════════════════════════════════════════════════════════")
        Log.i(TAG, "END STATE DUMP — ${LeicaConfig.configVersion} · schema ${LeicaConfig.configSchemaVersion}")
        Log.i(TAG, "═══════════════════════════════════════════════════════════════")
    }

    private fun l(msg: String) = Log.i(TAG, msg)

    private fun dumpMeta() {
        Log.i(TAG, "── META ──")
        Log.i(TAG, "  config_version: ${LeicaConfig.configVersion}")
        Log.i(TAG, "  schema_version: ${LeicaConfig.configSchemaVersion}")
        Log.i(TAG, "  config_loaded: ${LeicaConfig.isLoaded}")
        Log.i(TAG, "  active_version: ${LeicaConfig.ACTIVE_VERSION}")
    }

    private fun dumpRuntimeState() {
        Log.i(TAG, "── RUNTIME STATE (overrides) ──")
        Log.i(TAG, "  capture_mode_override: ${LeicaRuntimeState.captureModeOverride ?: "(none — using JSON default)"}")
        Log.i(TAG, "  creative_profile_override: ${LeicaRuntimeState.creativeProfileOverride ?: "(none — using JSON default)"}")
    }

    private fun dumpCaptureMode() {
        Log.i(TAG, "── CAPTURE MODE ──")
        Log.i(TAG, "  active: ${LeicaConfig.activeCaptureMode}")
        val modes = LeicaConfig.configSnapshot?.captureModes?.modes
        if (modes != null) {
            modes.forEach { (id, mode) ->
                Log.i(TAG, "  mode[$id]: frameMult=${mode.frameCountMultiplier}, SR=${mode.superResolutionScale}, NLM=${mode.nlmSearchRadius}, RAWmax=${mode.forceRawmax}, videoBr=${mode.videoBitrateMbps}")
            }
        }
        Log.i(TAG, "  effectiveFrameCount(main): ${LeicaConfig.effectiveFrameCountForLens("main")}")
    }

    private fun dumpCreativeProfile() {
        Log.i(TAG, "── CREATIVE PROFILE ──")
        Log.i(TAG, "  active_profile: ${LeicaConfig.activeCreativeProfileId}")
        Log.i(TAG, "  available_profiles: ${LeicaConfig.availableCreativeProfiles.size}")
        val active = LeicaConfig.availableCreativeProfiles[LeicaConfig.activeCreativeProfileId]
        if (active != null) {
            Log.i(TAG, "  active_lut: ${active.lutId ?: "(default)"}")
            Log.i(TAG, "  active_dcp: ${active.dcpId ?: "(default)"}")
            Log.i(TAG, "  active_frame: ${active.frameId ?: "(default)"}")
            Log.i(TAG, "  active_tone_contrast_boost: ${active.toneContrastBoost ?: 0.0f}")
            Log.i(TAG, "  active_tone_warmth_shift_k: ${active.toneWarmthShiftK ?: 0}")
            Log.i(TAG, "  active_saturation_multiplier: ${active.saturationMultiplier ?: 1.0f}")
            Log.i(TAG, "  active_best_lens: ${active.bestLens ?: "main"}")
        }
        // Effective color science (from creative profile)
        Log.i(TAG, "  effectiveToneContrast: ${LeicaConfig.effectiveToneContrast}")
        Log.i(TAG, "  effectiveSaturationBoost: ${LeicaConfig.effectiveSaturationBoost}")
        Log.i(TAG, "  effectiveWarmthShiftK: ${LeicaConfig.effectiveWarmthShiftK}")
    }

    private fun dumpMultiFrame() {
        Log.i(TAG, "── MULTI-FRAME ──")
        val mf = LeicaConfig.configSnapshot?.multiFrame
        Log.i(TAG, "  enabled: ${mf?.enabled ?: true}")
        Log.i(TAG, "  frame_count: ${mf?.frameCount ?: 15}")
        Log.i(TAG, "  super_resolution_scale: ${mf?.superResolutionScale ?: 2.0f}")
        Log.i(TAG, "  long_frame_exposure_ev: ${mf?.longFrameExposureEv ?: 2.0f}")
        Log.i(TAG, "  short_frame_exposure_divisor: ${mf?.shortFrameExposureDivisor ?: 4.0f}")
        Log.i(TAG, "  force_rawmax: ${mf?.forceRawmax ?: true}")
    }

    private fun dumpToneMapping() {
        Log.i(TAG, "── TONE MAPPING ──")
        val tm = LeicaConfig.configSnapshot?.toneMapping
        Log.i(TAG, "  enabled: ${tm?.enabled ?: true}")
        Log.i(TAG, "  contrast: ${tm?.contrast ?: 1.0}")
        Log.i(TAG, "  highlight_rolloff: ${tm?.highlightRolloff ?: 0.85}")
        Log.i(TAG, "  shadow_lift: ${tm?.shadowLift ?: 0.08}")
        Log.i(TAG, "  film_like_curve: ${tm?.filmLikeCurve ?: true}")
    }

    private fun dumpSharpeningNr() {
        Log.i(TAG, "── SHARPENING / NOISE REDUCTION ──")
        val sh = LeicaConfig.configSnapshot?.sharpening
        Log.i(TAG, "  sharpening.enabled: ${sh?.enabled ?: true}")
        Log.i(TAG, "  sharpening.amount: ${sh?.amount ?: 1.0}")
        Log.i(TAG, "  sharpening.radius: ${sh?.radius ?: 0.8}")
        Log.i(TAG, "  sharpening.threshold: ${sh?.threshold ?: 2}")
        val nr = LeicaConfig.configSnapshot?.noiseReduction
        Log.i(TAG, "  nr.enabled: ${nr?.enabled ?: true}")
        Log.i(TAG, "  nr.luminance: ${nr?.luminance ?: 0.4}")
        Log.i(TAG, "  nr.chrominance: ${nr?.chrominance ?: 0.6}")
        Log.i(TAG, "  nr.detail_preserve: ${nr?.detailPreserve ?: 0.7}")
    }

    private fun dumpColorScience() {
        Log.i(TAG, "── COLOR SCIENCE ──")
        val cs = LeicaConfig.configSnapshot?.colorScience
        Log.i(TAG, "  tint_shift: ${cs?.tintShift ?: -12}")
        Log.i(TAG, "  saturation_red_pct: ${cs?.saturationRedPct ?: -5}")
        Log.i(TAG, "  saturation_green_pct: ${cs?.saturationGreenPct ?: -10}")
        Log.i(TAG, "  saturation_blue_pct: ${cs?.saturationBluePct ?: -7}")
        Log.i(TAG, "  highlight_compression_ev: ${cs?.highlightCompressionEv ?: -0.15f}")
        Log.i(TAG, "  skin_tone_protection: ${cs?.skinToneProtection ?: 0.8}")
        val color = LeicaConfig.configSnapshot?.color
        Log.i(TAG, "  color.saturation_boost: ${color?.saturationBoost ?: 1.02}")
        Log.i(TAG, "  color.warmth: ${color?.warmth ?: 0}")
        Log.i(TAG, "  color.leica_look: ${color?.leicaLook ?: true}")
    }

    private fun dumpDemosaic() {
        Log.i(TAG, "── DEMOSAIC ──")
        val dm = LeicaConfig.configSnapshot?.demosaic
        Log.i(TAG, "  highlight_reconstruction_threshold: ${dm?.highlightReconstructionThreshold ?: 0.98f}")
        Log.i(TAG, "  nlm_search_radius: ${dm?.nlmSearchRadius ?: 7}")
        Log.i(TAG, "  nlm_patch_radius: ${dm?.nlmPatchRadius ?: 1}")
        Log.i(TAG, "  effectiveNlmSearchRadius: ${LeicaConfig.effectiveNlmSearchRadius}")
    }

    private fun dumpProcessing() {
        Log.i(TAG, "── PROCESSING ──")
        val p = LeicaConfig.configSnapshot?.processing
        Log.i(TAG, "  usm_radius: ${p?.usmRadius ?: 0.8f}")
        Log.i(TAG, "  usm_threshold: ${p?.usmThreshold ?: 2}")
        Log.i(TAG, "  pgtm_toe_power: ${p?.pgtmToePower ?: 0.55}")
        Log.i(TAG, "  pgtm_mid_power: ${p?.pgtmMidPower ?: 0.5}")
        Log.i(TAG, "  pgtm_shoulder_power: ${p?.pgtmShoulderPower ?: 0.45}")
        Log.i(TAG, "  pgtm_balance: ${p?.pgtmBalance ?: 0.5}")
        Log.i(TAG, "  filmic_default_contrast: ${p?.filmicDefaultContrast ?: 1.2}")
    }

    private fun dumpDcpLut() {
        Log.i(TAG, "── DCP / LUT FORCING ──")
        val dcp = LeicaConfig.configSnapshot?.dcp
        Log.i(TAG, "  force_dcp_id: ${dcp?.forceDcpId ?: "(none)"}")
        Log.i(TAG, "  force_baseline_lut_id: ${dcp?.forceBaselineLutId ?: "(none)"}")
        Log.i(TAG, "  dcp_ratio_warm: ${dcp?.dcpRatioWarm ?: 1.0f}")
        Log.i(TAG, "  dcp_ratio_cool: ${dcp?.dcpRatioCool ?: 1.0f}")
        Log.i(TAG, "  force_heic_export: ${dcp?.forceHeicExport ?: false}")
        Log.i(TAG, "  force_frame_id: ${dcp?.forceFrameId ?: 15}")
    }

    private fun dumpOutput() {
        Log.i(TAG, "── OUTPUT ──")
        val out = LeicaConfig.configSnapshot?.output
        Log.i(TAG, "  format: ${out?.format ?: "jpeg+raw"}")
        Log.i(TAG, "  quality: ${out?.quality ?: 100}")
        Log.i(TAG, "  max_resolution: ${out?.maxResolution ?: 0}")
        Log.i(TAG, "  preserve_exif: ${out?.preserveExif ?: true}")
        Log.i(TAG, "  add_watermark: ${out?.addWatermark ?: false}")
        val adv = LeicaConfig.configSnapshot?.advanced
        Log.i(TAG, "  export_dng_with_raw: ${adv?.exportDngWithRaw ?: true}")
        Log.i(TAG, "  export_super_res_dng: ${adv?.exportSuperResDng ?: true}")
        Log.i(TAG, "  gainmap_jpeg_quality: ${adv?.gainmapJpegQuality ?: 100}")
        Log.i(TAG, "  ultra_hdr_quality: ${adv?.ultraHdrQuality ?: 100}")
    }

    private fun dumpVideo() {
        Log.i(TAG, "── VIDEO ──")
        val v = LeicaConfig.configSnapshot?.video
        Log.i(TAG, "  codec: ${v?.codec ?: "hevc"}")
        Log.i(TAG, "  bitrate_mbps: ${v?.bitrateMbps ?: 250}")
        Log.i(TAG, "  max_b_frames: ${v?.maxBFrames ?: 3}")
        Log.i(TAG, "  color_profile: ${v?.colorProfile ?: "rec709"}")
        Log.i(TAG, "  default_resolution: ${v?.defaultResolution ?: "2160"}")
        Log.i(TAG, "  default_fps: ${v?.defaultFps ?: 30}")
        Log.i(TAG, "  hdr_video: ${v?.hdrVideo ?: false}")
        Log.i(TAG, "  videoDefaultCodecEnum: ${LeicaConfig.videoDefaultCodecEnum}")
        Log.i(TAG, "  videoDefaultResolutionEnum: ${LeicaConfig.videoDefaultResolutionEnum}")
        Log.i(TAG, "  videoDefaultFpsEnum: ${LeicaConfig.videoDefaultFpsEnum}")
        Log.i(TAG, "  videoDefaultBitrateEnum: ${LeicaConfig.videoDefaultBitrateEnum}")
        Log.i(TAG, "  videoDefaultLogProfileEnum: ${LeicaConfig.videoDefaultLogProfileEnum}")
        Log.i(TAG, "  videoAudioCodec: ${LeicaConfig.videoAudioCodec}")
        Log.i(TAG, "  videoAudioSampleRate: ${LeicaConfig.videoAudioSampleRate}")
        Log.i(TAG, "  videoAudioMimeType: ${LeicaConfig.videoAudioMimeType}")
        Log.i(TAG, "  videoBitrateMode: ${LeicaConfig.videoBitrateMode}")
        Log.i(TAG, "  videoHdr10Enabled: ${LeicaConfig.videoHdr10Enabled}")
    }

    private fun dumpPerLens() {
        Log.i(TAG, "── PER-LENS VALUES ──")
        val lenses = listOf("main", "uw", "tele", "front")
        for (lens in lenses) {
            Log.i(TAG, "  [$lens] frameCount=${LeicaConfig.frameCountForLens(lens)}, SR=${LeicaConfig.configSnapshot?.multiFrame?.superResolutionScale ?: 2.0f}")
            Log.i(TAG, "  [$lens] edgeMode=${LeicaConfig.edgeModeForLens(lens)}, nrMode=${LeicaConfig.camera2NoiseReductionModeForLens(lens)}")
            Log.i(TAG, "  [$lens] shadingMode=${LeicaConfig.shadingModeForLens(lens)}, hotPixelMode=${LeicaConfig.hotPixelModeForLens(lens)}")
            Log.i(TAG, "  [$lens] tonemapMode=${LeicaConfig.tonemapModeForLens(lens)}")
            Log.i(TAG, "  [$lens] whiteLevel=${LeicaConfig.whiteLevelForLens(lens)}, blackLevel=${LeicaConfig.blackLevelForLens(lens)}")
            Log.i(TAG, "  [$lens] tintShift=${LeicaConfig.tintShiftForLens(lens)}, highlightComp=${LeicaConfig.highlightCompressionEvForLens(lens)}")
            Log.i(TAG, "  [$lens] saturationR=${LeicaConfig.saturationRedForLens(lens)}, G=${LeicaConfig.saturationGreenForLens(lens)}, B=${LeicaConfig.saturationBlueForLens(lens)}")
            Log.i(TAG, "  [$lens] noiseModelPreset=${LeicaConfig.noiseModelPresetForLens(lens)}, ccmPreset=${LeicaConfig.ccmPresetForLens(lens)}")
            Log.i(TAG, "  [$lens] gammaPreset=${LeicaConfig.gammaPresetForLens(lens)}, awbMode=${LeicaConfig.awbModeForLens(lens)}")
            Log.i(TAG, "  [$lens] sharpeningMult=${LeicaConfig.sharpeningMultiplierForLens(lens)}, lumaNrMult=${LeicaConfig.lumaNrMultiplierForLens(lens)}, chromaNrMult=${LeicaConfig.chromaNrMultiplierForLens(lens)}")
            val nm = LeicaConfig.noiseModelForLens(lens)
            if (nm != null) {
                Log.i(TAG, "  [$lens] noiseModel: a=${nm.a}, b=${nm.b}")
            }
            val perLens = LeicaConfig.perLensForKey(lens)
            if (perLens != null) {
                Log.i(TAG, "  [$lens] evComp=${perLens.evComp ?: 0.0f}, cfaMode=${LeicaConfig.cfaModeForLens(lens)}")
            }
        }
    }

    private fun dumpSensors() {
        Log.i(TAG, "── SENSORS ──")
        val s = LeicaConfig.configSnapshot?.sensors
        Log.i(TAG, "  main_black_level: ${s?.mainBlackLevel ?: 1024}, main_white_level: ${s?.mainWhiteLevel ?: 16383}")
        Log.i(TAG, "  uw_black_level: ${s?.uwBlackLevel ?: 64}, uw_white_level: ${s?.uwWhiteLevel ?: 1023}")
        Log.i(TAG, "  tele_black_level: ${s?.teleBlackLevel ?: 64}, tele_white_level: ${s?.teleWhiteLevel ?: 1023}")
        Log.i(TAG, "  front_black_level: ${s?.frontBlackLevel ?: 64}, front_white_level: ${s?.frontWhiteLevel ?: 1023}")
    }

    private fun dumpAdvanced() {
        Log.i(TAG, "── ADVANCED ──")
        val adv = LeicaConfig.configSnapshot?.advanced
        Log.i(TAG, "  mertens_contrast_weight: ${adv?.mertensContrastWeight ?: 1.0f}")
        Log.i(TAG, "  mertens_saturation_weight: ${adv?.mertensSaturationWeight ?: 1.05f}")
        Log.i(TAG, "  mertens_exposure_weight: ${adv?.mertensExposureWeight ?: 1.0f}")
        val hdr = LeicaConfig.configSnapshot?.hdr
        Log.i(TAG, "  hdr_yuv_long_ev: ${hdr?.yuvLongEv ?: 2.0f}")
        Log.i(TAG, "  hdr_yuv_short_ev: ${hdr?.yuvShortEv ?: 0.25f}")
    }
}
