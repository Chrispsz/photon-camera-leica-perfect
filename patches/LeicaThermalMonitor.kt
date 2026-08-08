package com.hinnka.mycamera.raw

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.util.Log
import com.hinnka.mycamera.utils.PLog

/**
 * LeicaThermalMonitor — Singleton que monitora temperatura da bateria via
 * ACTION_BATTERY_CHANGED sticky broadcast (sem precisar de permissão).
 *
 * v6.3.8 — usado por P-59 pra gatear captura quando device está quente.
 * v6.3.8.6 — mode_fast removido; thermal degradation agora vai pra mode_balanced.
 * v6.4.0 — mode_balanced removido; thermal degradation agora vai pra mode_fast (disparo rápido inteligente).
 *
 * JSON config (leica_perfect.json → capture_modes.modes.{mode_max,fast}.thermal_throttle_at_c):
 *   - mode_max:  45°C (qualidade máxima, mas esquenta mais)
 *   - mode_fast: 55°C (default — disparo rápido, esquenta menos)
 *
 * Quando temperatura atual >= threshold, CameraViewModel.capture() pode:
 *   1. Auto-degradar pra mode_fast (menos frames que mode_max = menos CPU = menos calor)
 *   2. Avisar usuario via toast/log
 *
 * Não bloqueia captura — apenas degrada. Bloqueio hard seria frustrante.
 */
object LeicaThermalMonitor {
    private const val TAG = "LeicaThermalMonitor"

    @Volatile
    private var lastTempC: Float = 25.0f

    @Volatile
    private var lastUpdateMs: Long = 0L

    @Volatile
    private var registered: Boolean = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_BATTERY_CHANGED) {
                val tempRaw = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1)
                if (tempRaw >= 0) {
                    // EXTRA_TEMPERATURE é em décimos de grau Celsius
                    lastTempC = tempRaw / 10.0f
                    lastUpdateMs = System.currentTimeMillis()
                }
            }
        }
    }

    /**
     * init — registra o receiver ACTION_BATTERY_CHANGED (sticky).
     * Chamar de MyCameraApplication.onCreate().
     * Idempotente — seguro chamar várias vezes.
     */
    fun init(context: Context) {
        if (registered) return
        try {
            // ACTION_BATTERY_CHANGED é sticky — receiver dispara imediatamente com último valor
            val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            val stickyIntent = context.registerReceiver(receiver, filter)
            // Dispara manualmente com sticky intent pra ter valor imediato
            if (stickyIntent != null) {
                val tempRaw = stickyIntent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1)
                if (tempRaw >= 0) {
                    lastTempC = tempRaw / 10.0f
                    lastUpdateMs = System.currentTimeMillis()
                }
            }
            registered = true
            PLog.i(TAG, "LeicaThermalMonitor initialized — battery temp: ${lastTempC}°C")
        } catch (e: Exception) {
            PLog.e(TAG, "Failed to register battery receiver", e)
        }
    }

    /**
     * currentTempC — retorna última temperatura conhecida em °C.
     * Default 25°C se receiver ainda não disparou (dev frio).
     */
    fun currentTempC(): Float = lastTempC

    /**
     * isThrottled — true se temperatura atual >= threshold ativo.
     * Threshold vem de LeicaConfig.captureModeThermalThrottleAtC (per capture mode).
     */
    fun isThrottled(): Boolean {
        val threshold = LeicaConfig.captureModeThermalThrottleAtC
        val current = lastTempC
        return current >= threshold.toFloat()
    }

    /**
     * thermalStatus — string formatada pra logs.
     */
    fun thermalStatus(): String {
        val threshold = LeicaConfig.captureModeThermalThrottleAtC
        return "temp=${lastTempC}°C, threshold=${threshold}°C, mode=${LeicaConfig.activeCaptureMode}, throttled=${isThrottled()}"
    }

    /**
     * shouldDegradeCapture — true se device está quente E não está já em mode_fast.
     * CameraViewModel.capture() usa isso pra decidir se auto-degrada pra mode_fast.
     * v6.3.8.6: antes degradava pra mode_fast (removido); agora vai pra mode_balanced.
     * v6.4.0: mode_balanced removido; agora degrada pra mode_fast (disparo rápido inteligente).
     */
    fun shouldDegradeCapture(): Boolean {
        if (isThrottled() && LeicaConfig.activeCaptureMode != "mode_fast") {
            PLog.w(TAG, "Thermal throttle recommended: ${thermalStatus()}")
            return true
        }
        return false
    }
}
