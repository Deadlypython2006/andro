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
