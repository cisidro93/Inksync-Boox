package com.example.inksyncboox.eink

import android.os.Build
import android.view.View

/**
 * Hardware E-Ink Display Controller Bridge for Onyx Boox (Note Air 4C / Kaleido 3).
 *
 * Uses reflection against `com.onyx.android.sdk.api.device.epd.EpdController` so the app
 * compiles without proprietary Maven credentials while automatically engaging native
 * Regal / Fast / A2 waveform switching when running on a physical Boox device.
 */
enum class BooxRefreshMode(
    val title: String,
    val subtitle: String,
    val onyxModeName: String
) {
    REGAL_HD(
        title = "Regal HD",
        subtitle = "300 PPI Deep Anti-Ghosting (Reading & PDF Tiers)",
        onyxModeName = "REGAL"
    ),
    FAST_SPEED(
        title = "Speed Mode",
        subtitle = "Low-Latency Pan, Zoom & NeoFlow Grid Editing",
        onyxModeName = "FAST"
    ),
    A2_ULTRA(
        title = "A2 Ultra",
        subtitle = "Zero-Lag Wacom Scribble & Rapid Scroll",
        onyxModeName = "A2"
    )
}

object BooxEpdController {

    val isBooxHardware: Boolean by lazy {
        val manufacturer = Build.MANUFACTURER.lowercase()
        val brand = Build.BRAND.lowercase()
        val model = Build.MODEL.lowercase()
        manufacturer.contains("onyx") || brand.contains("onyx") || brand.contains("boox") || model.contains("noteair") || model.contains("boox")
    }

    val hardwareModelSummary: String by lazy {
        if (isBooxHardware) {
            "${Build.MANUFACTURER} ${Build.MODEL} (Kaleido 3 EPD Active)"
        } else {
            "${Build.MANUFACTURER} ${Build.MODEL} (Boox Kaleido 3 Simulator)"
        }
    }

    /**
     * Triggers a full GC16 / Regal waveform flash to eliminate E-Ink ghosting after page or tier turns.
     */
    fun triggerRegalFullRefresh(view: View?) {
        if (view == null) return
        try {
            val epdClass = Class.forName("com.onyx.android.sdk.api.device.epd.EpdController")
            val invalidateMethod = epdClass.methods.firstOrNull {
                it.name == "invalidate" && it.parameterTypes.size == 2
            }
            val updateModeClass = Class.forName("com.onyx.android.sdk.api.device.epd.UpdateMode")
            val regalConstant = updateModeClass.enumConstants?.firstOrNull {
                it.toString().contains("REGAL", ignoreCase = true) || it.toString().contains("GC", ignoreCase = true)
            }
            if (invalidateMethod != null && regalConstant != null) {
                invalidateMethod.invoke(null, view, regalConstant)
                return
            }
        } catch (_: Throwable) {
            // Fallback on non-Boox or newer SDK signatures
        }
        view.postInvalidate()
    }

    /**
     * Switches the active E-Ink controller waveform mode (e.g., FAST during pinch/drag, REGAL on release).
     */
    fun applyRefreshMode(view: View?, mode: BooxRefreshMode) {
        if (view == null) return
        try {
            val epdClass = Class.forName("com.onyx.android.sdk.api.device.epd.EpdController")
            val updateModeClass = Class.forName("com.onyx.android.sdk.api.device.epd.UpdateMode")
            val targetEnum = updateModeClass.enumConstants?.firstOrNull {
                it.toString().contains(mode.onyxModeName, ignoreCase = true)
            }
            val applyMethod = epdClass.methods.firstOrNull {
                it.name == "applyApplicationFastMode"
            }
            if (applyMethod != null) {
                val enableFast = mode != BooxRefreshMode.REGAL_HD
                applyMethod.invoke(null, "com.example.inksyncboox", enableFast, true)
            } else if (targetEnum != null) {
                val setViewDefaultUpdateMode = epdClass.methods.firstOrNull {
                    it.name == "setViewDefaultUpdateMode" && it.parameterTypes.size == 2
                }
                setViewDefaultUpdateMode?.invoke(null, view, targetEnum)
            }
        } catch (_: Throwable) {
            // Safe no-op on emulator or non-Onyx hardware
        }
        view.postInvalidate()
    }
}
