package com.hinnka.mycamera.raw

import android.content.Context
import android.util.Log

/**
 * ═══════════════════════════════════════════════════════════════════════════════
 * LeicaRuntimeState — Mutable runtime overrides for LeicaConfig (v6.2.6)
 * ═══════════════════════════════════════════════════════════════════════════════
 *
 * Problem: LeicaConfig.accessors are read-only `val get()` reading from
 *   currentConfig (loaded from assets/leica_perfect.json at startup).
 *   There's no way for the user to change capture mode or creative profile
 *   at runtime — the JSON values are static.
 *
 * Solution: This object holds @Volatile mutable overrides that take priority
 *   over JSON values. LeicaConfig.accessors check LeicaRuntimeState first,
 *   then fall back to currentConfig, then to hardcoded defaults.
 *
 * Persistence: SharedPreferences (synchronous, simple — no DataStore coroutines
 *   needed for 2 string values). Survives app restarts.
 *
 * Lifecycle:
 *   1. MyCameraApplication.onCreate() calls LeicaRuntimeState.init(this)
 *   2. init() loads any saved overrides from SharedPreferences
 *   3. User changes mode/profile in LeicaSettingsScreen → setCaptureMode()/setCreativeProfile()
 *   4. Setter updates @Volatile var + persists to SharedPreferences
 *   5. LeicaConfig.activeCaptureMode / activeCreativeProfileId reflect the change immediately
 *   6. Pipeline (MultiFrameConfig, CameraViewModel, DcpProfile, etc.) reads from LeicaConfig
 *      → effect is live without app restart
 */
object LeicaRuntimeState {

    private const val TAG = "LeicaRuntimeState"
    private const val PREFS_NAME = "leica_runtime_overrides"
    private const val KEY_CAPTURE_MODE = "capture_mode_override"
    private const val KEY_CREATIVE_PROFILE = "creative_profile_override"

    /**
     * Runtime override for capture mode. null = use JSON default (mode_fast).
     * Valid values: "mode_max", "mode_fast"
     * v6.4.0: mode_balanced removido; mode_fast substitui (disparo rapido inteligente).
     */
    @Volatile
    @JvmField
    var captureModeOverride: String? = null

    /**
     * Runtime override for creative profile. null = use JSON default (leica_authentic).
     * Valid values: any profile ID from leica_perfect.json creative_profiles.profiles
     */
    @Volatile
    @JvmField
    var creativeProfileOverride: String? = null

    private var appContext: Context? = null

    /**
     * Initialize persistence — call once from MyCameraApplication.onCreate().
     * Loads any saved overrides from SharedPreferences.
     */
    fun init(context: Context) {
        appContext = context.applicationContext
        try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            captureModeOverride = prefs.getString(KEY_CAPTURE_MODE, null)
            creativeProfileOverride = prefs.getString(KEY_CREATIVE_PROFILE, null)
            Log.i(TAG, "LeicaRuntimeState initialized — " +
                "captureMode=${captureModeOverride ?: "(default)"}, " +
                "creativeProfile=${creativeProfileOverride ?: "(default)"}")
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to load runtime overrides", t)
        }
    }

    /**
     * Set capture mode override. null clears the override (reverts to JSON default).
     * Persists to SharedPreferences immediately.
     */
    fun setCaptureMode(mode: String?) {
        captureModeOverride = mode
        persist()
        Log.i(TAG, "Capture mode set to: ${mode ?: "(default)"}")
    }

    /**
     * Set creative profile override. null clears the override.
     * Persists to SharedPreferences immediately.
     */
    fun setCreativeProfile(profile: String?) {
        creativeProfileOverride = profile
        persist()
        Log.i(TAG, "Creative profile set to: ${profile ?: "(default)"}")
    }

    /**
     * Set runtime LUT override — v6.4.0 (P-63/P-64).
     * Fixes "stuck on m9 CCD" bug: when user picks a LUT in the mod menu,
     * this var takes precedence over activeLutId (which was always returning "leica_m9"
     * from leica_perfect_signature profile, ignoring UI selection).
     * null clears the override (reverts to active profile's LUT).
     */
    fun setRuntimeLutOverride(lutId: String?) {
        LeicaConfig.runtimeLutOverride = lutId
        Log.i(TAG, "Runtime LUT override set: ${lutId ?: "(default)"}")
    }

    /**
     * Cycle to the next capture mode: fast → max → fast.
     * v6.4.0: mode_balanced removido; ciclo agora é fast <-> max.
     * Returns the new mode ID.
     */
    fun cycleCaptureMode(): String {
        val current = LeicaConfig.activeCaptureMode
        val next = when (current) {
            "mode_fast" -> "mode_max"
            "mode_max" -> "mode_fast"
            else -> "mode_fast"
        }
        setCaptureMode(next)
        return next
    }

    private fun persist() {
        val ctx = appContext ?: run {
            Log.w(TAG, "persist() called before init() — override not saved")
            return
        }
        try {
            val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().apply {
                if (captureModeOverride != null) {
                    putString(KEY_CAPTURE_MODE, captureModeOverride)
                } else {
                    remove(KEY_CAPTURE_MODE)
                }
                if (creativeProfileOverride != null) {
                    putString(KEY_CREATIVE_PROFILE, creativeProfileOverride)
                } else {
                    remove(KEY_CREATIVE_PROFILE)
                }
            }.apply()
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to persist runtime overrides", t)
        }
    }
}
