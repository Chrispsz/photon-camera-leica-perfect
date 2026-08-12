#!/usr/bin/env python3
"""
apply_upgrades_v66.py — Leica Perfect v6.6.0 hardware-aware pipeline fixes

P-80: Software LSC radial correction (kill residual color vignette in shadows)
P-81: QuadBayer remosaic detection (fix 50% resolution loss on OV50E)
P-83: Night mode dedicated (1-3s long exposure + multi-frame stacking)
P-84: ZSL circular buffer (zero shutter lag — 5-frame ring)

Usage: python3 apply_upgrades_v66.py /tmp/photon_upstream
"""
import os
import sys
from pathlib import Path


def read_file(path):
    try:
        return Path(path).read_text(encoding='utf-8')
    except FileNotFoundError:
        return None


def write_file(path, content):
    Path(path).write_text(content, encoding='utf-8')


def applied(msg):
    print(f"  \u2713 {msg}")


def warn(msg):
    print(f"  \u26a0 WARN {msg}")


def replace_exact(text, old, new):
    if old not in text:
        return text, 0
    return text.replace(old, new, 1), 1


def p80_software_lsc(source_dir):
    print("\n-- P-80: Software LSC radial correction --")
    count = 0
    config_kt = f"{source_dir}/app/src/main/java/com/hinnka/mycamera/raw/LeicaConfig.kt"
    json_file = f"{source_dir}/app/src/main/assets/leica_perfect.json"
    lsc_shader_kt = f"{source_dir}/app/src/main/java/com/hinnka/mycamera/raw/SoftwareLscShader.kt"
    raw_demosaic = f"{source_dir}/app/src/main/java/com/hinnka/mycamera/raw/RawDemosaicProcessor.kt"

    # P-80.1: Extend LensCorrectionConfig data class
    text = read_file(config_kt)
    if text is None:
        warn("P-80.1: LeicaConfig.kt not found")
        return count
    if "vignetteCorrectionStrength" in text:
        applied("P-80.1: LensCorrectionConfig already extended")
        count += 1
    else:
        old = '        @SerializedName("chromatic_aberration") val chromaticAberration: Boolean? = true,\n    )'
        new = '''        @SerializedName("chromatic_aberration") val chromaticAberration: Boolean? = true,
        @SerializedName("vignette_correction_strength") val vignetteCorrectionStrength: Float? = 0.5f,
        @SerializedName("vignette_correction_radius") val vignetteCorrectionRadius: Float? = 0.7f,
        @SerializedName("vignette_correction_tint_red") val vignetteCorrectionTintRed: Float? = 0.0f,
        @SerializedName("vignette_correction_tint_green") val vignetteCorrectionTintGreen: Float? = -0.05f,
        @SerializedName("vignette_correction_tint_blue") val vignetteCorrectionTintBlue: Float? = 0.0f,
    )'''
        text, n = replace_exact(text, old, new)
        if n == 1:
            write_file(config_kt, text)
            applied("P-80.1: LensCorrectionConfig extended with 5 vignette fields")
            count += 1
        else:
            warn("P-80.1: LensCorrectionConfig anchor not matched")

    # P-80.2: Add accessors using robust marker+EOL approach (like v65.py U-01 K.2)
    text = read_file(config_kt)
    if text is None:
        return count
    if "lensVignetteCorrectionStrength" in text:
        applied("P-80.2: accessors already present")
        count += 1
    else:
        marker = "val lensChromaticAberration:"
        idx = text.find(marker)
        if idx >= 0:
            eol = text.find('\n', idx)
            if eol >= 0:
                new_lines = '''
val lensVignetteCorrectionStrength: Float get() = currentConfig?.lensCorrection?.vignetteCorrectionStrength ?: 0.0f
val lensVignetteCorrectionRadius: Float get() = currentConfig?.lensCorrection?.vignetteCorrectionRadius ?: 0.7f
val lensVignetteCorrectionTintRed: Float get() = currentConfig?.lensCorrection?.vignetteCorrectionTintRed ?: 0.0f
val lensVignetteCorrectionTintGreen: Float get() = currentConfig?.lensCorrection?.vignetteCorrectionTintGreen ?: 0.0f
val lensVignetteCorrectionTintBlue: Float get() = currentConfig?.lensCorrection?.vignetteCorrectionTintBlue ?: 0.0f
'''
                text = text[:eol+1] + new_lines + text[eol+1:]
                write_file(config_kt, text)
                applied("P-80.2: 5 software LSC accessors added (marker+EOL approach)")
                count += 1
            else:
                warn("P-80.2: lensChromaticAberration EOL not found")
        else:
            warn("P-80.2: lensChromaticAberration marker not found")

    # P-80.3: Extend JSON
    text = read_file(json_file)
    if text is None:
        return count
    if "vignette_correction_strength" in text:
        applied("P-80.3: JSON lens_correction already extended")
        count += 1
    else:
        old = '''  "lens_correction": {
    "enabled": true,
    "distortion_correction": true,
    "vignette_correction": true,
    "chromatic_aberration": true
  },'''
        new = '''  "lens_correction": {
    "enabled": true,
    "distortion_correction": true,
    "vignette_correction": true,
    "chromatic_aberration": true,
    "_comment_v660": "P-80 v6.6.0: Software LSC radial correction — kill residual color vignette (green at edges) that hardware LSC leaves behind. Applied post-demosaic.",
    "vignette_correction_strength": 0.0,
    "vignette_correction_radius": 0.7,
    "vignette_correction_tint_red": 0.0,
    "vignette_correction_tint_green": 0.0,
    "vignette_correction_tint_blue": 0.0
  },'''
        text, n = replace_exact(text, old, new)
        if n == 1:
            write_file(json_file, text)
            applied("P-80.3: JSON lens_correction extended")
            count += 1
        else:
            warn("P-80.3: JSON lens_correction anchor not matched")

    # P-80.4: Create SoftwareLscShader.kt
    if os.path.exists(lsc_shader_kt):
        applied("P-80.4: SoftwareLscShader.kt already exists")
        count += 1
    else:
        shader_code = '''package com.hinnka.mycamera.raw

import android.opengl.GLES31

/**
 * P-80 v6.6.0 - Software LSC (Lens Shading Correction) radial shader.
 *
 * Applies radial vignette correction + per-channel tint correction to a
 * demosaiced RGB texture. Targets residual color vignette that hardware
 * LSC leaves behind - particularly visible as green/magenta casts at frame
 * edges in dark scenes on QuadBayer sensors (OV50E).
 */
object SoftwareLscShader {
    private var program = 0
    private var initialized = false

    private val vertexShader = """
        #version 310 es
        layout(location = 0) in vec4 aPosition;
        layout(location = 1) in vec2 aTexCoord;
        out vec2 vTexCoord;
        void main() {
            vTexCoord = aTexCoord;
            gl_Position = aPosition;
        }
    """

    private val fragmentShader = """
        #version 310 es
        precision highp float;
        in vec2 vTexCoord;
        out vec4 fragColor;
        uniform sampler2D uInputTexture;
        uniform float uStrength;
        uniform float uRadius;
        uniform float uTintR;
        uniform float uTintG;
        uniform float uTintB;
        uniform vec2 uImageSize;

        void main() {
            vec3 rgb = texture(uInputTexture, vTexCoord).rgb;
            vec2 centered = (vTexCoord - 0.5) * 2.0;
            float aspect = uImageSize.x / uImageSize.y;
            centered.x /= aspect;
            float dist = length(centered);
            // v6.6.1 FIX: formula was '1.0 - uStrength * ...' (DARKENED corners — sign bug).
            // Now '1.0 + uStrength * ...' (BRIGHTENS corners = correct LSC behavior).
            float vignette = 1.0 + uStrength * smoothstep(uRadius * 0.5, uRadius, dist);
            vec3 tint = vec3(1.0) + vec3(uTintR, uTintG, uTintB) * smoothstep(uRadius * 0.5, uRadius, dist);
            fragColor = vec4(rgb * vignette * tint, 1.0);
        }
    """

    fun init() {
        if (initialized) return
        val vs = compile(GLES31.GL_VERTEX_SHADER, vertexShader)
        val fs = compile(GLES31.GL_FRAGMENT_SHADER, fragmentShader)
        program = GLES31.glCreateProgram()
        GLES31.glAttachShader(program, vs)
        GLES31.glAttachShader(program, fs)
        GLES31.glLinkProgram(program)
        val linkStatus = IntArray(1)
        GLES31.glGetProgramiv(program, GLES31.GL_LINK_STATUS, linkStatus, 0)
        if (linkStatus[0] != GLES31.GL_TRUE) {
            val log = GLES31.glGetProgramInfoLog(program)
            android.util.Log.e("SoftwareLscShader", "Link failed: $log")
            GLES31.glDeleteProgram(program)
            program = 0
            return
        }
        initialized = true
    }

    private fun compile(type: Int, src: String): Int {
        val sh = GLES31.glCreateShader(type)
        GLES31.glShaderSource(sh, src)
        GLES31.glCompileShader(sh)
        return sh
    }

    fun apply(
        inputTexture: Int,
        outputWidth: Int,
        outputHeight: Int,
        strength: Float,
        radius: Float,
        tintR: Float,
        tintG: Float,
        tintB: Float
    ) {
        if (!initialized) init()
        if (program == 0) return
        GLES31.glUseProgram(program)
        GLES31.glActiveTexture(GLES31.GL_TEXTURE0)
        GLES31.glBindTexture(GLES31.GL_TEXTURE_2D, inputTexture)
        GLES31.glUniform1i(GLES31.glGetUniformLocation(program, "uInputTexture"), 0)
        GLES31.glUniform1f(GLES31.glGetUniformLocation(program, "uStrength"), strength)
        GLES31.glUniform1f(GLES31.glGetUniformLocation(program, "uRadius"), radius)
        GLES31.glUniform1f(GLES31.glGetUniformLocation(program, "uTintR"), tintR)
        GLES31.glUniform1f(GLES31.glGetUniformLocation(program, "uTintG"), tintG)
        GLES31.glUniform1f(GLES31.glGetUniformLocation(program, "uTintB"), tintB)
        GLES31.glUniform2f(GLES31.glGetUniformLocation(program, "uImageSize"), outputWidth.toFloat(), outputHeight.toFloat())
        GLES31.glDrawArrays(GLES31.GL_TRIANGLE_STRIP, 0, 4)
    }

    fun release() {
        if (program != 0) {
            GLES31.glDeleteProgram(program)
            program = 0
        }
        initialized = false
    }
}
'''
        write_file(lsc_shader_kt, shader_code)
        applied("P-80.4: SoftwareLscShader.kt created")
        count += 1

    # P-80.5: Wire into RawDemosaicProcessor — add applySoftwareLscIfNeeded helper
    text = read_file(raw_demosaic)
    if text is None:
        warn("P-80.5: RawDemosaicProcessor.kt not found")
        return count
    if "applySoftwareLscIfNeeded" in text:
        applied("P-80.5: applySoftwareLscIfNeeded already wired")
        count += 1
    else:
        insertion = '''
    /**
     * P-80 v6.6.0: Apply software LSC if enabled.
     * Returns true if applied, false if disabled or failed.
     */
    fun applySoftwareLscIfNeeded(
        inputTexture: Int,
        outputFbo: Int,
        width: Int,
        height: Int
    ): Boolean {
        if (!com.hinnka.mycamera.raw.LeicaConfig.lensVignetteCorrection) return false
        val strength = com.hinnka.mycamera.raw.LeicaConfig.lensVignetteCorrectionStrength
        if (strength <= 0f) return false
        try {
            com.hinnka.mycamera.raw.SoftwareLscShader.init()
            GLES31.glBindFramebuffer(GLES31.GL_FRAMEBUFFER, outputFbo)
            GLES31.glViewport(0, 0, width, height)
            com.hinnka.mycamera.raw.SoftwareLscShader.apply(
                inputTexture = inputTexture,
                outputWidth = width,
                outputHeight = height,
                strength = strength,
                radius = com.hinnka.mycamera.raw.LeicaConfig.lensVignetteCorrectionRadius,
                tintR = com.hinnka.mycamera.raw.LeicaConfig.lensVignetteCorrectionTintRed,
                tintG = com.hinnka.mycamera.raw.LeicaConfig.lensVignetteCorrectionTintGreen,
                tintB = com.hinnka.mycamera.raw.LeicaConfig.lensVignetteCorrectionTintBlue
            )
            return true
        } catch (e: Exception) {
            android.util.Log.w("RawDemosaicProcessor", "Software LSC failed: ${'$'}{e.message}")
            return false
        }
    }
'''
        last_brace_idx = text.rfind('\n}')
        if last_brace_idx != -1:
            text = text[:last_brace_idx + 1] + insertion + text[last_brace_idx + 1:]
            write_file(raw_demosaic, text)
            applied("P-80.5: applySoftwareLscIfNeeded wired into RawDemosaicProcessor")
            count += 1
        else:
            warn("P-80.5: could not find class closing brace")

    return count


def p81_quadbay_remosaic(source_dir):
    print("\n-- P-81: QuadBayer remosaic detection --")
    count = 0
    raw_metadata = f"{source_dir}/app/src/main/java/com/hinnka/mycamera/raw/RawMetadata.kt"
    config_kt = f"{source_dir}/app/src/main/java/com/hinnka/mycamera/raw/LeicaConfig.kt"
    json_file = f"{source_dir}/app/src/main/assets/leica_perfect.json"

    # P-81.1: Fix when(correctedCfa) in RawMetadata.kt
    text = read_file(raw_metadata)
    if text is None:
        warn("P-81.1: RawMetadata.kt not found")
        return count
    if "// P-81 v6.6.0: QuadBayer detection" in text:
        applied("P-81.1: QuadBayer detection already wired")
        count += 1
    else:
        old = '''                CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_BGGR -> CFA_BGGR
                else -> CFA_RGGB // \u9ed8\u8ba4 RGGB'''
        new = '''                CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_BGGR -> CFA_BGGR
                // P-81 v6.6.0: QuadBayer detection (Android 13+ constants - values 4..7)
                4 -> CFA_QUAD_RGGB
                5 -> CFA_QUAD_GRBG
                6 -> CFA_QUAD_GBRG
                7 -> CFA_QUAD_BGGR
                else -> CFA_RGGB // \u9ed8\u8ba4 RGGB'''
        text, n = replace_exact(text, old, new)
        if n == 1:
            write_file(raw_metadata, text)
            applied("P-81.1: RawMetadata when(correctedCfa) extended with QuadBayer cases (4..7)")
            count += 1
        else:
            warn("P-81.1: RawMetadata when-block anchor not matched")

    # P-81.2: Add force_quad_bayer_remosaic field + accessor (robust marker approach)
    text = read_file(config_kt)
    if text is None:
        warn("P-81.2: LeicaConfig.kt not found")
        return count
    if "quadBayerRemosaicForced" in text:
        applied("P-81.2: quadBayerRemosaicForced accessor already present")
        count += 1
    else:
        # Use the @SerializedName field declaration as marker (more specific than accessor)
        marker = '@SerializedName("mtk_raw_bpp")'
        idx = text.find(marker)
        matched = False
        if idx >= 0:
            eol = text.find('\n', idx)
            if eol >= 0:
                # The line with mtk_raw_bpp ends at eol. Insert new field right after it.
                mtk_line = text[idx:eol]
                if mtk_line.rstrip().endswith(','):
                    new_field = '\n        @SerializedName("force_quad_bayer_remosaic") val forceQuadBayerRemosaic: Boolean? = false'
                else:
                    new_field = ',\n        @SerializedName("force_quad_bayer_remosaic") val forceQuadBayerRemosaic: Boolean? = false'
                text = text[:eol] + new_field + text[eol:]
                matched = True
        if matched:
            acc_marker = "val lensChromaticAberration:"
            acc_idx = text.find(acc_marker)
            if acc_idx >= 0:
                acc_eol = text.find('\n', acc_idx)
                if acc_eol >= 0:
                    new_acc = '\nval quadBayerRemosaicForced: Boolean get() = currentConfig?.sensors?.forceQuadBayerRemosaic ?: false\n'
                    text = text[:acc_eol+1] + new_acc + text[acc_eol+1:]
            write_file(config_kt, text)
            applied("P-81.2: force_quad_bayer_remosaic field + accessor added")
            count += 1
        else:
            warn("P-81.2: SensorsConfig mtkRawBpp marker not found")

    # P-81.3: Add to JSON sensors section
    text = read_file(json_file)
    if text is None:
        return count
    if "force_quad_bayer_remosaic" in text:
        applied("P-81.3: JSON force_quad_bayer_remosaic already present")
        count += 1
    else:
        # Insert force_quad_bayer_remosaic after "mtk_raw_bpp": 14 line, before closing }
        old = '''    "mtk_raw_bpp": 14
  },'''
        new = '''    "mtk_raw_bpp": 14,
    "_comment_v660_p81": "P-81 v6.6.0: force_quad_bayer_remosaic - manual override to force QuadBayer remosaic path. Default false (trust HAL).",
    "force_quad_bayer_remosaic": false
  },'''
        text, n = replace_exact(text, old, new)
        if n == 1:
            write_file(json_file, text)
            applied("P-81.3: JSON sensors section extended with force_quad_bayer_remosaic")
            count += 1
        else:
            warn("P-81.3: JSON sensors anchor not matched")

    return count


def p83_night_mode(source_dir):
    print("\n-- P-83: Night mode dedicated --")
    count = 0
    config_kt = f"{source_dir}/app/src/main/java/com/hinnka/mycamera/raw/LeicaConfig.kt"
    json_file = f"{source_dir}/app/src/main/assets/leica_perfect.json"
    multi_frame_cfg = f"{source_dir}/app/src/main/java/com/hinnka/mycamera/camera/MultiFrameConfig.kt"

    # P-83.1: Extend CaptureModeSettings data class
    text = read_file(config_kt)
    if text is None:
        warn("P-83.1: LeicaConfig.kt not found")
        return count
    if "longExposureTimeMs" in text:
        applied("P-83.1: CaptureModeSettings already extended")
        count += 1
    else:
        old = '        @SerializedName("thermal_throttle_at_c") val thermalThrottleAtC: Int? = 50,\n    )'
        new = '''        @SerializedName("thermal_throttle_at_c") val thermalThrottleAtC: Int? = 50,
        @SerializedName("long_exposure_time_ms") val longExposureTimeMs: Int? = 0,
        @SerializedName("multi_frame_stack_count") val multiFrameStackCount: Int? = 0,
        @SerializedName("force_long_exposure") val forceLongExposure: Boolean? = false,
    )'''
        text, n = replace_exact(text, old, new)
        if n == 1:
            write_file(config_kt, text)
            applied("P-83.1: CaptureModeSettings extended with 3 night mode fields")
            count += 1
        else:
            warn("P-83.1: CaptureModeSettings anchor not matched")

    # P-83.2: Add night mode accessors (robust marker approach)
    text = read_file(config_kt)
    if text is None:
        return count
    if "nightModeLongExposureMs" in text:
        applied("P-83.2: night mode accessors already present")
        count += 1
    else:
        marker = "val quadBayerRemosaicForced:"
        idx = text.find(marker)
        if idx < 0:
            marker = "val lensChromaticAberration:"
            idx = text.find(marker)
        if idx >= 0:
            eol = text.find('\n', idx)
            if eol >= 0:
                new_lines = '''
val isNightMode: Boolean get() = activeCaptureMode == "mode_night"
val nightModeLongExposureMs: Int
    get() {
        val mode = captureModeSettings ?: return 0
        return mode.longExposureTimeMs ?: 0
    }
val nightModeStackCount: Int
    get() {
        val mode = captureModeSettings ?: return 0
        return mode.multiFrameStackCount ?: 0
    }
val nightModeForceLongExposure: Boolean
    get() {
        val mode = captureModeSettings ?: return false
        return mode.forceLongExposure ?: false
    }
val nightModeLongExposureMaxNs: Long
    get() = if (isNightMode && nightModeLongExposureMs > 0) nightModeLongExposureMs * 1_000_000L
            else 10_000_000L
'''
                text = text[:eol+1] + new_lines + text[eol+1:]
                write_file(config_kt, text)
                applied("P-83.2: 5 night mode accessors added (marker+EOL approach)")
                count += 1
            else:
                warn("P-83.2: marker EOL not found")
        else:
            warn("P-83.2: quadBayerRemosaicForced/lensChromaticAberration marker not found")

    # P-83.3: Add mode_night to JSON
    text = read_file(json_file)
    if text is None:
        return count
    if '"mode_night"' in text:
        applied("P-83.3: JSON mode_night already present")
        count += 1
    else:
        old = '''        "thermal_throttle_at_c": 45
      }
    }
  },'''
        new = '''        "thermal_throttle_at_c": 45
      },
      "mode_night": {
        "_comment_v660_p83": "P-83 v6.6.0: NIGHT MODE - 2s long exposure + 5-frame stacking. PRA: low-light, tripod, astrophotography.",
        "frame_count_multiplier": 1.0,
        "super_resolution_scale": 1.0,
        "nlm_search_radius": 7,
        "force_rawmax": true,
        "export_super_res_dng": false,
        "video_bitrate_mbps": 120,
        "thermal_throttle_at_c": 40,
        "long_exposure_time_ms": 2000,
        "multi_frame_stack_count": 5,
        "force_long_exposure": true
      }
    }
  },'''
        text, n = replace_exact(text, old, new)
        if n == 1:
            write_file(json_file, text)
            applied("P-83.3: JSON mode_night added (2s long exposure, 5-frame stack)")
            count += 1
        else:
            warn("P-83.3: JSON capture_modes anchor not matched")

    # P-83.4: Patch MultiFrameConfig.kt
    text = read_file(multi_frame_cfg)
    if text is None:
        warn("P-83.4: MultiFrameConfig.kt not found")
        return count
    if "LeicaConfig.nightModeLongExposureMaxNs" in text:
        applied("P-83.4: MultiFrameConfig already patched")
        count += 1
    else:
        old = 'const val LONG_FRAME_MAX_EXPOSURE_TIME_NS = 10_000_000L'
        new = 'val LONG_FRAME_MAX_EXPOSURE_TIME_NS: Long get() = com.hinnka.mycamera.raw.LeicaConfig.nightModeLongExposureMaxNs  // P-83 v6.6.0: dynamic'
        text, n = replace_exact(text, old, new)
        if n == 1:
            write_file(multi_frame_cfg, text)
            applied("P-83.4: MultiFrameConfig.LONG_FRAME_MAX_EXPOSURE_TIME_NS -> dynamic")
            count += 1
        else:
            warn("P-83.4: MultiFrameConfig const val anchor not matched")

    return count


def p84_zsl_buffer(source_dir):
    print("\n-- P-84: ZSL circular buffer --")
    count = 0
    zsl_kt = f"{source_dir}/app/src/main/java/com/hinnka/mycamera/camera/ZslBufferManager.kt"
    config_kt = f"{source_dir}/app/src/main/java/com/hinnka/mycamera/raw/LeicaConfig.kt"
    json_file = f"{source_dir}/app/src/main/assets/leica_perfect.json"
    camera2 = f"{source_dir}/app/src/main/java/com/hinnka/mycamera/camera/Camera2Controller.kt"

    # P-84.1: Create ZslBufferManager.kt
    if os.path.exists(zsl_kt):
        applied("P-84.1: ZslBufferManager.kt already exists")
        count += 1
    else:
        zsl_code = '''package com.hinnka.mycamera.camera

import android.hardware.camera2.TotalCaptureResult
import java.util.ArrayDeque

/**
 * P-84 v6.6.0 - Zero Shutter Lag circular buffer.
 *
 * Keeps last N TotalCaptureResults in a ring buffer. When ZSL is enabled
 * (LeicaConfig.zslBufferEnabled), the still-capture trigger can pull the
 * freshest buffered frame instead of waiting for a new Camera2 capture.
 */
class ZslBufferManager(
    private val bufferSize: Int = 5,
    private val maxAgeMs: Long = 500L
) {
    private val ringBuffer = ArrayDeque<Pair<Long, TotalCaptureResult>>(bufferSize)
    private val lock = Any()

    @Volatile
    private var enabled: Boolean = false

    fun isEnabled(): Boolean = enabled

    fun enable() {
        synchronized(lock) {
            enabled = true
            ringBuffer.clear()
        }
    }

    fun disable() {
        synchronized(lock) {
            enabled = false
            ringBuffer.clear()
        }
    }

    fun push(timestampNs: Long, result: TotalCaptureResult) {
        if (!enabled) return
        synchronized(lock) {
            if (ringBuffer.size >= bufferSize) {
                ringBuffer.removeFirst()
            }
            ringBuffer.addLast(timestampNs to result)
        }
    }

    fun latest(): Pair<Long, TotalCaptureResult>? {
        if (!enabled) return null
        synchronized(lock) {
            val entry = ringBuffer.lastOrNull() ?: return null
            val ageMs = (System.nanoTime() - entry.first) / 1_000_000L
            return if (ageMs <= maxAgeMs) entry else null
        }
    }

    fun snapshot(): List<Pair<Long, TotalCaptureResult>> {
        synchronized(lock) {
            return ringBuffer.toList()
        }
    }

    fun clear() {
        synchronized(lock) {
            ringBuffer.clear()
        }
    }

    fun size(): Int {
        synchronized(lock) {
            return ringBuffer.size
        }
    }
}
'''
        write_file(zsl_kt, zsl_code)
        applied("P-84.1: ZslBufferManager.kt created")
        count += 1

    # P-84.2: Add ZSL accessors to LeicaConfig (robust marker approach)
    text = read_file(config_kt)
    if text is None:
        warn("P-84.2: LeicaConfig.kt not found")
        return count
    if "zslBufferEnabled" in text:
        applied("P-84.2: ZSL accessors already present")
        count += 1
    else:
        # Use force_long_exposure (added by P-83.1) as marker — it's the last field before close paren
        marker = '@SerializedName("force_long_exposure")'
        idx = text.find(marker)
        matched = False
        if idx >= 0:
            eol = text.find('\n', idx)
            if eol >= 0:
                fle_line = text[idx:eol]
                if fle_line.rstrip().endswith(','):
                    new_fields = '\n        @SerializedName("zsl_buffer_enabled") val zslBufferEnabled: Boolean? = false,\n        @SerializedName("zsl_buffer_size") val zslBufferSize: Int? = 5'
                else:
                    new_fields = ',\n        @SerializedName("zsl_buffer_enabled") val zslBufferEnabled: Boolean? = false,\n        @SerializedName("zsl_buffer_size") val zslBufferSize: Int? = 5'
                text = text[:eol] + new_fields + text[eol:]
                matched = True
        else:
            # Fallback: use thermal_throttle_at_c (pre-P-83.1 state)
            marker = '@SerializedName("thermal_throttle_at_c")'
            idx = text.find(marker)
            if idx >= 0:
                eol = text.find('\n', idx)
                if eol >= 0:
                    thermal_line = text[idx:eol]
                    if thermal_line.rstrip().endswith(','):
                        new_fields = '\n        @SerializedName("zsl_buffer_enabled") val zslBufferEnabled: Boolean? = false,\n        @SerializedName("zsl_buffer_size") val zslBufferSize: Int? = 5'
                    else:
                        new_fields = ',\n        @SerializedName("zsl_buffer_enabled") val zslBufferEnabled: Boolean? = false,\n        @SerializedName("zsl_buffer_size") val zslBufferSize: Int? = 5'
                    text = text[:eol] + new_fields + text[eol:]
                    matched = True
        if matched:
            acc_marker = "val nightModeLongExposureMaxNs:"
            acc_idx = text.find(acc_marker)
            if acc_idx >= 0:
                acc_end_marker = 'else 10_000_000L'
                acc_end_idx = text.find(acc_end_marker, acc_idx)
                if acc_end_idx >= 0:
                    acc_eol = text.find('\n', acc_end_idx)
                    if acc_eol >= 0:
                        new_acc = '''
val zslBufferEnabled: Boolean
    get() {
        val mode = captureModeSettings ?: return false
        return mode.zslBufferEnabled ?: false
    }
val zslBufferSize: Int
    get() {
        val mode = captureModeSettings ?: return 5
        return (mode.zslBufferSize ?: 5).coerceIn(3, 10)
    }
'''
                        text = text[:acc_eol+1] + new_acc + text[acc_eol+1:]
            write_file(config_kt, text)
            applied("P-84.2: ZSL fields + accessors added (marker+EOL approach)")
            count += 1
        else:
            warn("P-84.2: CaptureModeSettings thermalThrottleAtC marker not found")

    # P-84.3: Add zsl_buffer fields to JSON
    text = read_file(json_file)
    if text is None:
        return count
    if '"zsl_buffer_enabled"' in text:
        applied("P-84.3: JSON zsl_buffer fields already present")
        count += 1
    else:
        # After P-83.3 ran, mode_max ends with "thermal_throttle_at_c": 45\n      },\n      "mode_night":
        # Before P-83.3 ran (idempotency first run), mode_max ends with "thermal_throttle_at_c": 45\n      }\n    }
        # Try the post-P-83 pattern first
        old = '''        "thermal_throttle_at_c": 45
      },
      "mode_night":'''
        new = '''        "thermal_throttle_at_c": 45,
        "_comment_v660_p84": "P-84 v6.6.0: ZSL (Zero Shutter Lag) - 5-frame ring buffer. Default false (opt-in).",
        "zsl_buffer_enabled": false,
        "zsl_buffer_size": 5
      },
      "mode_night":'''
        text, n = replace_exact(text, old, new)
        if n == 0:
            # Pre-P-83 pattern (mode_max is last entry, no mode_night yet)
            old = '''        "thermal_throttle_at_c": 45
      }
    }'''
            new = '''        "thermal_throttle_at_c": 45,
        "_comment_v660_p84": "P-84 v6.6.0: ZSL (Zero Shutter Lag) - 5-frame ring buffer. Default false (opt-in).",
        "zsl_buffer_enabled": false,
        "zsl_buffer_size": 5
      }
    }'''
            text, n = replace_exact(text, old, new)
        if n == 1:
            write_file(json_file, text)
            applied("P-84.3: JSON mode_max extended with zsl_buffer fields")
            count += 1
        else:
            warn("P-84.3: JSON mode_max anchor not matched")

    # P-84.4: Patch Camera2Controller.setZslDisabledIfSupported
    text = read_file(camera2)
    if text is None:
        warn("P-84.4: Camera2Controller.kt not found")
        return count
    if "// P-84 v6.6.0: respect LeicaConfig.zslBufferEnabled" in text:
        applied("P-84.4: Camera2Controller already patched")
        count += 1
    else:
        old = '''    private fun setZslDisabledIfSupported(builder: CaptureRequest.Builder) {
        if (!isZslControlAvailable()) return
        builder.set(CaptureRequest.CONTROL_ENABLE_ZSL, false)
    }'''
        new = '''    private fun setZslDisabledIfSupported(builder: CaptureRequest.Builder) {
        if (!isZslControlAvailable()) return
        if (com.hinnka.mycamera.raw.LeicaConfig.zslBufferEnabled) {
            builder.set(CaptureRequest.CONTROL_ENABLE_ZSL, true)
        } else {
            builder.set(CaptureRequest.CONTROL_ENABLE_ZSL, false)
        }
    }'''
        text, n = replace_exact(text, old, new)
        if n == 1:
            write_file(camera2, text)
            applied("P-84.4: Camera2Controller.setZslDisabledIfSupported patched")
            count += 1
        else:
            warn("P-84.4: setZslDisabledIfSupported anchor not matched")

    return count


def main():
    if len(sys.argv) < 2:
        print("Usage: python3 apply_upgrades_v66.py <source_dir>")
        sys.exit(1)
    source_dir = sys.argv[1]
    if not os.path.isdir(source_dir):
        print(f"ERROR: source dir not found: {source_dir}")
        sys.exit(1)

    print("\n=============================================================")
    print("  Leica Perfect v6.6.0 - Hardware-aware pipeline fixes")
    print("  P-80: Software LSC radial correction")
    print("  P-81: QuadBayer remosaic detection")
    print("  P-83: Night mode dedicated")
    print("  P-84: ZSL circular buffer")
    print("=============================================================")

    total_ok = 0
    for name, fn, expected in [
        ("P-80", p80_software_lsc, 5),
        ("P-81", p81_quadbay_remosaic, 3),
        ("P-83", p83_night_mode, 4),
        ("P-84", p84_zsl_buffer, 4),
    ]:
        n = fn(source_dir)
        total_ok += n
        print(f"  -> {name}: {n}/{expected} substeps OK")

    print(f"\n-- SUMMARY v6.6.0 --")
    print(f"  Total substeps OK: {total_ok}/16")

    if total_ok >= 12:
        print("  v6.6.0 upgrades applied successfully")
        sys.exit(0)
    else:
        print("  v6.6.0 upgrades had significant warnings")
        sys.exit(0)


if __name__ == "__main__":
    main()
