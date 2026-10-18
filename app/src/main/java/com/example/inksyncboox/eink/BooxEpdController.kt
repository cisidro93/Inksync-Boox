package com.example.inksyncboox.eink

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import android.view.View
import org.lsposed.hiddenapibypass.HiddenApiBypass
import java.lang.reflect.Method
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Full-screen e-ink refresh for Onyx Boox devices (Note Air 4C and other Qualcomm-based Boox).
 *
 * Onyx ships extra methods on the framework's `android.view.View` class. KOReader's Android
 * launcher drives the Note Air 4C through exactly this API (see its `QualcommEPDController` /
 * `OnyxEPDController`), so we follow the same approach:
 *
 *  - `View.refreshScreen(x, y, width, height, mode)` requests a hardware refresh of a region.
 *
 * Onyx's own SDK (onyxsdk-device 1.3.6, `SDMDevice`) looks up the same `refreshScreen` method
 * reflectively. Because it is not part of the public SDK, Android's hidden-API restrictions can
 * block the lookup for apps targeting recent SDK levels; like the Notable app for Boox, we fall
 * back to HiddenApiBypass for that one lookup.
 *
 * We deliberately do NOT call `View.setWaveformAndScheme(5, 1, 0)` like KOReader does: that turns
 * off the system's own refresh for the app, which only makes sense for apps that schedule every
 * refresh themselves. This app is a normal Compose UI, so the system keeps handling ordinary
 * partial updates (in whatever mode the user picked in Boox "E-Ink Center"), and we only add an
 * occasional full GC16 flash to clear ghosting after page turns.
 *
 * Everything is reflective, so the app still runs on emulators and non-Boox hardware, where the
 * calls simply do nothing.
 */
object BooxEpdController {

    private const val TAG = "BooxEpd"

    // Waveform constants from KOReader's QualcommEPDController.
    private const val UPDATE_FULL = 32
    private const val MODE_WAIT = 64
    private const val MODE_GC16 = 2

    /** Full-screen, wait-for-completion GC16 refresh (what Boox apps use to clear ghosting). */
    private const val FULL_GC16 = UPDATE_FULL + MODE_WAIT + MODE_GC16

    /** Delay so our flash happens after Compose has drawn the new page. */
    private const val FULL_REFRESH_DELAY_MS = 250L

    /** Reported by community users for Boox firmware; used only if the View API is missing. */
    private const val ACTION_REFRESH_SCREEN = "onyx.android.intent.action.REFRESH_SCREEN"

    private val executor = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "boox-epd").apply { isDaemon = true }
    }

    val isBooxHardware: Boolean by lazy {
        val brand = Build.BRAND.lowercase()
        val manufacturer = Build.MANUFACTURER.lowercase()
        brand == "onyx" || manufacturer.contains("onyx") || brand.contains("boox")
    }

    val isNoteAir4C: Boolean by lazy {
        isBooxHardware && Build.MODEL.lowercase() == "noteair4c"
    }

    private val refreshScreenMethod: Method? by lazy {
        if (!isBooxHardware) return@lazy null
        val params = arrayOf<Class<*>>(Integer.TYPE, Integer.TYPE, Integer.TYPE, Integer.TYPE, Integer.TYPE)
        try {
            View::class.java.getMethod("refreshScreen", *params)
        } catch (direct: Throwable) {
            try {
                HiddenApiBypass.getDeclaredMethod(View::class.java, "refreshScreen", *params)
                    .apply { isAccessible = true }
            } catch (bypassed: Throwable) {
                Log.w(TAG, "View.refreshScreen not available: $direct / $bypassed")
                null
            }
        }
    }

    /** True when the Onyx refresh API was found on this device. */
    val isRefreshApiAvailable: Boolean
        get() = refreshScreenMethod != null

    /** Short human-readable status for the settings screen. */
    val statusSummary: String
        get() = when {
            !isBooxHardware -> "Not a Boox device (${Build.MANUFACTURER} ${Build.MODEL}): full refresh disabled"
            isRefreshApiAvailable -> "Boox ${Build.MODEL}: full-screen refresh available"
            else -> "Boox ${Build.MODEL}: refresh API not found, using system broadcast fallback"
        }

    /**
     * Requests a full-screen GC16 flash after the current frame is drawn.
     * Safe to call on any device.
     */
    fun requestFullRefresh(view: View?) {
        if (view == null || !isBooxHardware) return
        val root = view.rootView ?: view
        val context = view.context.applicationContext
        executor.schedule({
            val width = root.width
            val height = root.height
            val method = refreshScreenMethod
            var done = false
            if (method != null && width > 0 && height > 0) {
                done = try {
                    method.invoke(root, 0, 0, width, height, FULL_GC16)
                    true
                } catch (t: Throwable) {
                    Log.w(TAG, "refreshScreen failed: $t")
                    false
                }
            }
            if (!done) {
                sendRefreshBroadcast(context)
            }
        }, FULL_REFRESH_DELAY_MS, TimeUnit.MILLISECONDS)
    }

    private fun sendRefreshBroadcast(context: Context) {
        try {
            context.sendBroadcast(Intent(ACTION_REFRESH_SCREEN))
        } catch (t: Throwable) {
            Log.w(TAG, "refresh broadcast failed: $t")
        }
    }
}
