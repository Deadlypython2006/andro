package dev.aimmy.android

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.view.accessibility.AccessibilityEvent
import android.util.Log

class AimmyAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        isReady = true
        Log.i(TAG, "Accessibility Service Connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // We don't need to process events for touch injection
    }

    override fun onInterrupt() {
        Log.w(TAG, "Accessibility Service Interrupted")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        isReady = false
        Log.i(TAG, "Accessibility Service Unbound")
        return super.onUnbind(intent)
    }

    companion object {
        private const val TAG = "AimmyAccess"
        
        var instance: AimmyAccessibilityService? = null
            private set
            
        var isReady: Boolean = false
            private set
            
        var injectCount: Int = 0
            private set

        /**
         * Simulates a swipe/drag gesture from (startX, startY) to (endX, endY)
         */
        fun swipe(startX: Float, startY: Float, endX: Float, endY: Float, durationMs: Long = 300): Boolean {
            val service = instance ?: return false
            
            val path = Path().apply {
                moveTo(startX, startY)
                lineTo(endX, endY)
            }
            
            val stroke = GestureDescription.StrokeDescription(path, 0, durationMs)
            val gesture = GestureDescription.Builder().addStroke(stroke).build()
            
            val result = service.dispatchGesture(gesture, object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    super.onCompleted(gestureDescription)
                    injectCount++
                }
                override fun onCancelled(gestureDescription: GestureDescription?) {
                    super.onCancelled(gestureDescription)
                    Log.w(TAG, "Gesture Cancelled")
                }
            }, null)
            
            return result
        }

        /**
         * Simulates a quick tap at (x, y)
         */
        fun tap(x: Float, y: Float): Boolean {
            val service = instance ?: return false
            val path = Path().apply { moveTo(x, y) }
            val stroke = GestureDescription.StrokeDescription(path, 0, 50)
            val gesture = GestureDescription.Builder().addStroke(stroke).build()
            return service.dispatchGesture(gesture, null, null)
        }

        // ─── Continuous Dragging State ───
        private var isDragging = false
        private var lastDragX = 0f
        private var lastDragY = 0f

        /**
         * Starts or continues a drag from the last known position to the new (x, y).
         * Uses `willContinue = true` to allow seamless chaining without lifting the finger.
         */
        fun dragTo(x: Float, y: Float) {
            val service = instance ?: return
            
            val startX = if (isDragging) lastDragX else x
            val startY = if (isDragging) lastDragY else y
            
            val path = Path().apply {
                moveTo(startX, startY)
                lineTo(x, y)
            }
            
            // 30ms is approximately 1 frame at 30fps.
            // willContinue = true keeps the pointer down on the screen.
            val stroke = GestureDescription.StrokeDescription(path, 0, 30, true)
            val gesture = GestureDescription.Builder().addStroke(stroke).build()
            
            service.dispatchGesture(gesture, null, null)
            
            isDragging = true
            lastDragX = x
            lastDragY = y
        }

        /**
         * Lifts the finger to end a continuous drag.
         */
        fun stopDrag() {
            if (!isDragging) return
            val service = instance ?: return
            
            // To stop, we just dispatch a 1ms gesture at the last location with willContinue = false
            val path = Path().apply { moveTo(lastDragX, lastDragY) }
            val stroke = GestureDescription.StrokeDescription(path, 0, 10, false)
            val gesture = GestureDescription.Builder().addStroke(stroke).build()
            
            service.dispatchGesture(gesture, null, null)
            isDragging = false
        }
    }
}
