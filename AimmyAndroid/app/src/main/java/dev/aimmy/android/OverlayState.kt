package dev.aimmy.android

import android.graphics.RectF
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Shared memory object to pipe ML detections from ScreenCaptureService
 * to the FloatingOverlayService at high frame rates without Broadcast intents.
 */
object OverlayState {
    // Thread-safe list of current bounding boxes
    val detections = CopyOnWriteArrayList<YoloDetector.Detection>()
    
    // The closest target (drawn in a different color)
    @Volatile
    var activeTarget: YoloDetector.Detection? = null

    // Tracking device temperature
    @Volatile
    var currentTemperature: Float = 0f

    // Determines if the user is holding the aim button
    @Volatile
    var isAimbotEnabled: Boolean = false

    // Track the dynamic aim position for the unified fire/aim pointer
    @Volatile var currentAimX: Float = 0f
    @Volatile var currentAimY: Float = 0f

    @Volatile
    var latestFrameBitmap: android.graphics.Bitmap? = null

    fun updateDetections(newDetections: List<YoloDetector.Detection>, target: YoloDetector.Detection?) {
        detections.clear()
        detections.addAll(newDetections)
        activeTarget = target
    }

    fun clear() {
        detections.clear()
        activeTarget = null
    }
}
