// ═══════════════════════════════════════════════════════════════════════════════
// VideoPipeline.patch.kt — P-32 (v6.0) — split em P-32a + P-32b
// ═══════════════════════════════════════════════════════════════════════════════
//
// ⚠️  DOCUMENTATION-ONLY — Este arquivo NÃO é compilado. Mostra o que o sed em
//     build-archlinux.sh cmd_patch() P-32a + P-32b injeta no upstream PhotonCamera.
//
// O QUE FAZ:
//   P-32a: Atualiza defaults do VideoConfig em VideoTypes.kt pra HEVC + log +
//          2160p@30fps + bitrate 250Mbps (mode_max) — derivando do LeicaConfig.
//   P-32b: Converte constantes hardcoded em VideoRecorder.kt pra race-safe
//          `val X get() = LeicaConfig.Y`:
//            - KEY_MAX_B_FRAMES: 0 → 2 (B-frames pra melhor compressão)
//            - I_FRAME_INTERVAL: 1 → 1 (1s keyframe interval)
//            - AUDIO_MONO_BITRATE: 96k → 256k (AAC高质量)
//            - AUDIO_STEREO_BITRATE: 192k → 512k (stereo AAC)
//            - bitrateMode: CBR preferring → extended com VBR check
//
// POR QUE:
//   - Stock PhotonCamera usa H.264 50Mbps sem B-frames, sem log, sem HDR — qualidade
//     de celular 2018. v6.0 leva a Xiaomi 15T Dimensity 8300-Ultra ao limite.
//   - HEVC 250Mbps B-frames=2 + Apple-Log2: vídeo cinematográfico com 10-bit
//     no container HEIF/HEIC, pronto pra color grading em DaVinci/Premiere.
//   - HDR10 habilitado via COLOR_STANDARD_BT2020 + COLOR_RANGE_FULL (HEVC profile Main10).
//   - AAC 256k mono / 512k stereo: áudio cristalino, sem artifact de compressão.
//
// ARQUIVOS TARGET:
//   - app/src/main/java/com/hinnka/mycamera/video/VideoTypes.kt (P-32a)
//   - app/src/main/java/com/hinnka/mycamera/video/VideoRecorder.kt (P-32b)
//
// ÂNCORAS SED:
//   P-32a: linhas `val defaultCodec: VideoCodec = VideoCodec.H264` etc em VideoTypes.kt
//   P-32b: linhas `private const val KEY_MAX_B_FRAMES = 0` etc em VideoRecorder.kt
//
// LeicaConfig ACCESSORS USADOS:
//   - LeicaConfig.videoCodec / videoBitrateMbps / videoMaxBFrames / videoColorProfile
//   - LeicaConfig.videoDefaultResolution / videoDefaultFps / videoAudioBitrateKbps
//   - LeicaConfig.videoIFrameIntervalSec / videoRateControl
//
// LIMITAÇÕES:
//   - HDR video (LeicaConfig.videoHdr) é tratado INDIRETAMENTE via HEVC + Apple-Log2
//     selection (triggers 10-bit surface + COLOR_STANDARD_BT2020 via VideoEncoderColorConfig).
//     Não há explicit KEY_HDR_INFO injection pra v6.0.
//   - bitrate_mbps é read 1x no VideoConfig defaults; mudar em runtime requer reload.
//   - VBR mode pode variar bitrate real ±20% do target (50-300Mbps típico).
// ═══════════════════════════════════════════════════════════════════════════════

package com.hinnka.mycamera.video

import com.hinnka.mycamera.raw.LeicaConfig

// ─── P-32a: VideoTypes.kt — ANTES ────────────────────────────────────────────
data class VideoConfig(
    val defaultCodec: VideoCodec = VideoCodec.H264,
    val defaultLogProfile: VideoLogProfile = VideoLogProfile.NONE,
    val defaultResolution: VideoResolutionPreset = VideoResolutionPreset.P1080,
    val defaultBitrate: VideoBitratePreset = VideoBitratePreset.BITRATE_50M,
    val defaultFps: Int = 30,
    // ...
)

// ─── P-32a: VideoTypes.kt — DEPOIS ────────────────────────────────────────────
data class VideoConfig(
    val defaultCodec: VideoCodec get() = if (LeicaConfig.videoCodec == "hevc") VideoCodec.HEVC else VideoCodec.H264,
    val defaultLogProfile: VideoLogProfile get() = if (LeicaConfig.videoColorProfile == "log") VideoLogProfile.LOG else VideoLogProfile.NONE,
    val defaultResolution: VideoResolutionPreset get() = VideoResolutionPreset.entries.firstOrNull { it.displayName.contains(LeicaConfig.videoDefaultResolution) } ?: VideoResolutionPreset.P1080,
    val defaultBitrate: VideoBitratePreset get() = VideoBitratePreset.entries.firstOrNull { it.bitrateMbps >= LeicaConfig.videoBitrateMbps } ?: VideoBitratePreset.BITRATE_50M,
    val defaultFps: Int get() = LeicaConfig.videoDefaultFps,
    // ...
)

// ─── P-32b: VideoRecorder.kt — ANTES ─────────────────────────────────────────
companion object {
    private const val KEY_MAX_B_FRAMES = 0
    private const val I_FRAME_INTERVAL = 1
    private const val AUDIO_MONO_BITRATE = 96_000
    private const val AUDIO_STEREO_BITRATE = 192_000
    // ...
    fun prepareVideoEncoder(...) {
        // ...
        if (codecName.contains("hevc")) {
            bitrateMode = BITRATE_MODE_CBR
        }
        // ...
    }
}

// ─── P-32b: VideoRecorder.kt — DEPOIS ────────────────────────────────────────
companion object {
    private val KEY_MAX_B_FRAMES: Int get() = LeicaConfig.videoMaxBFrames  // 2 B-frames
    private val I_FRAME_INTERVAL: Int get() = LeicaConfig.videoIFrameIntervalSec  // 1s
    private val AUDIO_MONO_BITRATE: Int get() = LeicaConfig.videoAudioBitrateKbps * 1000  // 256k
    private val AUDIO_STEREO_BITRATE: Int get() = LeicaConfig.videoAudioBitrateKbps * 1000 * 2  // 512k
    // ...
    fun prepareVideoEncoder(...) {
        // ...
        if (codecName.contains("hevc") && LeicaConfig.videoRateControl != "vbr") {
            bitrateMode = BITRATE_MODE_CBR
        }
        // ...
    }
}

// ─── SED COMMANDS (executados pelo build-archlinux.sh) ───────────────────────
// P-32a (5 seds em VideoTypes.kt):
//   sed -i 's|val defaultCodec: VideoCodec = VideoCodec.H264|val defaultCodec: VideoCodec get() = if (LeicaConfig.videoCodec == "hevc") VideoCodec.HEVC else VideoCodec.H264|' "$vtypes"
//   sed -i 's|val defaultLogProfile: VideoLogProfile = VideoLogProfile.NONE|val defaultLogProfile: VideoLogProfile get() = if (LeicaConfig.videoColorProfile == "log") VideoLogProfile.LOG else VideoLogProfile.NONE|' "$vtypes"
//   sed -i 's|val defaultResolution: VideoResolutionPreset = VideoResolutionPreset.P1080|...|' "$vtypes"
//   sed -i 's|val defaultBitrate: VideoBitratePreset = VideoBitratePreset.BITRATE_50M|...|' "$vtypes"
//   sed -i 's|val defaultFps: Int = 30|val defaultFps: Int get() = LeicaConfig.videoDefaultFps|' "$vtypes"
//
// P-32b (6 seds em VideoRecorder.kt):
//   sed -i 's|private const val KEY_MAX_B_FRAMES = 0|private val KEY_MAX_B_FRAMES: Int get() = LeicaConfig.videoMaxBFrames|' "$vrec"
//   sed -i 's|private const val I_FRAME_INTERVAL = 1|private val I_FRAME_INTERVAL: Int get() = LeicaConfig.videoIFrameIntervalSec|' "$vrec"
//   sed -i 's|private const val AUDIO_MONO_BITRATE = 96_000|private val AUDIO_MONO_BITRATE: Int get() = LeicaConfig.videoAudioBitrateKbps * 1000|' "$vrec"
//   sed -i 's|private const val AUDIO_STEREO_BITRATE = 192_000|private val AUDIO_STEREO_BITRATE: Int get() = LeicaConfig.videoAudioBitrateKbps * 1000 * 2|' "$vrec"
//   sed -i 's|if (codecName.contains("hevc")|if (codecName.contains("hevc") \&\& LeicaConfig.videoRateControl != "vbr"|' "$vrec"
//   grep-guarded import injection

// ─── REFERENCIADO POR (NÃO QUEBRAR) ──────────────────────────────────────────
//   - VideoEncoderColorConfig.kt (10-bit + BT2020 + color range)
//   - HeifWriter.kt (HEIC container para HEVC video)
//   - AudioEncoderConfig.kt (AAC sample rate/channels)
//   - CameraViewModel.kt video recording state
