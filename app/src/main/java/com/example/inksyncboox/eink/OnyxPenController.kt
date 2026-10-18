package com.example.inksyncboox.eink

import android.graphics.Rect
import android.util.Log
import android.view.View
import com.onyx.android.sdk.api.device.epd.EpdController
import com.onyx.android.sdk.data.note.TouchPoint
import com.onyx.android.sdk.pen.RawInputCallback
import com.onyx.android.sdk.pen.TouchHelper
import com.onyx.android.sdk.pen.data.TouchPointList
import com.onyx.android.sdk.pen.style.StrokeStyle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Onyx's low-latency pen layer ("raw drawing") for Boox devices, via onyxsdk-pen's [TouchHelper].
 *
 * While raw drawing is enabled, the e-ink controller draws pen ink itself with almost no lag,
 * and the app's own screen updates are held back. The flow follows Onyx's Pen SDK docs and the
 * Notable app (whose pen handling mirrors NeoReader, `com.onyx.kreader`):
 *
 * 1. The firmware draws the stroke live inside the limit rect.
 * 2. On pen-up we receive the full point list and the app stores and draws the stroke itself.
 * 3. We briefly switch the raw-drawing render off and on, so the panel shows the app's rendering
 *    (Notable waits 500 ms on color panels like the Note Air 4C, 300 ms on monochrome).
 *
 * Raw drawing must be OFF whenever the app needs the screen to change (menus, page turns),
 * which the caller handles through [setActive].
 */
class OnyxPenController(
    hostView: View,
    private val isColorPanel: Boolean = true
) {
    /** A pen point in host-view pixels, with pressure in 0..1. */
    data class PenPoint(val x: Float, val y: Float, val pressure: Float)

    /** Receives each finished stroke (pen tip) or eraser path (pen eraser end / button). */
    var onStroke: (List<PenPoint>) -> Unit = {}
    var onErase: (List<PenPoint>) -> Unit = {}

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var releaseJob: Job? = null
    private var opened = false
    private var enabled = false
    private var lastLimit: Rect? = null

    private val maxPressure: Float by lazy {
        try {
            EpdController.getMaxTouchPressure().takeIf { it > 0f } ?: 4096f
        } catch (_: Throwable) {
            4096f
        }
    }

    private val callback = object : RawInputCallback() {
        override fun onBeginRawDrawing(b: Boolean, point: TouchPoint?) {}
        override fun onEndRawDrawing(b: Boolean, point: TouchPoint?) {}
        override fun onRawDrawingTouchPointMoveReceived(point: TouchPoint?) {}

        override fun onRawDrawingTouchPointListReceived(list: TouchPointList?) {
            val points = list?.points?.map(::toPenPoint).orEmpty()
            if (points.size >= 2) onStroke(points)
            releaseScreenAfterStroke()
        }

        override fun onBeginRawErasing(b: Boolean, point: TouchPoint?) {}
        override fun onEndRawErasing(b: Boolean, point: TouchPoint?) {}
        override fun onRawErasingTouchPointMoveReceived(point: TouchPoint?) {}

        override fun onRawErasingTouchPointListReceived(list: TouchPointList?) {
            val points = list?.points?.map(::toPenPoint).orEmpty()
            if (points.isNotEmpty()) onErase(points)
            releaseScreenAfterStroke()
        }
    }

    private val helper: TouchHelper? by lazy {
        if (!BooxEpdController.isBooxHardware) return@lazy null
        try {
            TouchHelper.create(hostView, callback)
        } catch (t: Throwable) {
            Log.w(TAG, "TouchHelper unavailable: $t")
            null
        }
    }

    /** True on Boox hardware where the Onyx pen SDK initialised. */
    val isAvailable: Boolean get() = helper != null

    private fun toPenPoint(p: TouchPoint) = PenPoint(p.x, p.y, (p.pressure / maxPressure).coerceIn(0.05f, 1f))

    /**
     * Turns raw drawing on inside [limit] (host-view pixels) with the given ink, or off when
     * [active] is false. Safe to call repeatedly.
     */
    fun setActive(active: Boolean, limit: Rect?, highlighter: Boolean, widthPx: Float, color: Int) {
        val h = helper ?: return
        try {
            if (!active || limit == null || limit.isEmpty) {
                if (enabled) {
                    releaseJob?.cancel()
                    h.setRawDrawingEnabled(false)
                    enabled = false
                }
                return
            }
            if (!opened || limit != lastLimit) {
                if (opened) h.closeRawDrawing()
                h.setLimitRect(mutableListOf(Rect(limit)))
                    .setExcludeRect(emptyList())
                    .openRawDrawing()
                opened = true
                lastLimit = Rect(limit)
                enabled = false
            }
            if (!enabled) {
                h.setRawDrawingEnabled(true)
                enabled = true
            }
            // setRawDrawingEnabled(true) resets the pen style, so always re-apply it afterwards.
            h.setStrokeStyle(if (highlighter) StrokeStyle.MARKER else StrokeStyle.PENCIL)
            h.setStrokeWidth(widthPx)
            h.setStrokeColor(color)
        } catch (t: Throwable) {
            Log.w(TAG, "setActive failed: $t")
        }
    }

    /** Hands the screen back to the app briefly so it shows the app's own rendering. */
    private fun releaseScreenAfterStroke() {
        val h = helper ?: return
        releaseJob?.cancel()
        releaseJob = scope.launch {
            try {
                h.isRawDrawingRenderEnabled = false
                delay(if (isColorPanel) 500L else 300L)
                if (enabled) h.isRawDrawingRenderEnabled = true
            } catch (t: Throwable) {
                Log.w(TAG, "release failed: $t")
            }
        }
    }

    fun close() {
        releaseJob?.cancel()
        try {
            helper?.setRawDrawingEnabled(false)
            helper?.closeRawDrawing()
        } catch (_: Throwable) {
        }
        opened = false
        enabled = false
        lastLimit = null
        scope.cancel()
    }

    companion object {
        private const val TAG = "OnyxPen"
    }
}
