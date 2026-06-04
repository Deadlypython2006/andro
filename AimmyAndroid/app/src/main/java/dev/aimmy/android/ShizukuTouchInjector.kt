package dev.aimmy.android

import android.os.SystemClock
import android.view.InputDevice
import android.view.InputEvent
import android.view.MotionEvent
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper
import java.lang.reflect.Method

/**
 * Injects touch events at the system level via Shizuku + IInputManager binder.
 * This bypasses the slow `adb shell input` approach and provides ~1ms latency.
 */
object ShizukuTouchInjector {

    private var inputManager: Any? = null
    private var injectMethod: Method? = null
    private var initialized = false

    fun initialize() {
        if (initialized && inputManager != null) return
        if (!Shizuku.pingBinder()) return

        try {
            val rawBinder = SystemServiceHelper.getSystemService("input") ?: return
            val wrappedBinder = ShizukuBinderWrapper(rawBinder)

            val stubClass = Class.forName("android.hardware.input.IInputManager\$Stub")
            inputManager = stubClass
                .getMethod("asInterface", android.os.IBinder::class.java)
                .invoke(null, wrappedBinder)

            // Find the injectInputEvent(InputEvent, int) method
            val iface = Class.forName("android.hardware.input.IInputManager")
            for (m in iface.methods) {
                if (m.name == "injectInputEvent" && m.parameterTypes.size == 2) {
                    injectMethod = m
                    break
                }
            }
            initialized = true
        } catch (e: Exception) {
            e.printStackTrace()
            inputManager = null
            injectMethod = null
        }
    }

    private fun inject(event: InputEvent) {
        if (injectMethod == null || inputManager == null) {
            initialize()
            if (injectMethod == null) return
        }
        try {
            // Mode 0 = INJECT_INPUT_EVENT_MODE_ASYNC (non-blocking, lowest latency)
            injectMethod?.invoke(inputManager, event, 0)
        } catch (e: Exception) {
            // Binder might have died — reset so next call re-initializes
            initialized = false
            inputManager = null
        }
    }

    /**
     * Performs a smooth swipe gesture from (startX, startY) to (endX, endY).
     * @param steps Number of intermediate MOVE events. More = smoother but slower.
     */
    fun swipe(startX: Float, startY: Float, endX: Float, endY: Float, steps: Int = 3) {
        val downTime = SystemClock.uptimeMillis()
        var eventTime = downTime

        // DOWN
        inject(createEvent(downTime, eventTime, MotionEvent.ACTION_DOWN, startX, startY))

        // MOVE (interpolate linearly)
        if (steps > 0) {
            val stepX = (endX - startX) / steps
            val stepY = (endY - startY) / steps
            for (i in 1..steps) {
                eventTime += 2 // 2ms between steps looks organic
                inject(createEvent(downTime, eventTime, MotionEvent.ACTION_MOVE,
                    startX + stepX * i, startY + stepY * i))
            }
        }

        // UP
        eventTime += 2
        inject(createEvent(downTime, eventTime, MotionEvent.ACTION_UP, endX, endY))
    }

    fun tap(x: Float, y: Float) {
        val t = SystemClock.uptimeMillis()
        inject(createEvent(t, t, MotionEvent.ACTION_DOWN, x, y))
        inject(createEvent(t, t + 10, MotionEvent.ACTION_UP, x, y))
    }

    private fun createEvent(
        downTime: Long, eventTime: Long, action: Int, x: Float, y: Float
    ): MotionEvent {
        val props = arrayOf(MotionEvent.PointerProperties().apply {
            id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER
        })
        val coords = arrayOf(MotionEvent.PointerCoords().apply {
            this.x = x; this.y = y; pressure = 1f; size = 1f
        })
        return MotionEvent.obtain(
            downTime, eventTime, action, 1,
            props, coords, 0, 0, 1f, 1f, 0, 0,
            InputDevice.SOURCE_TOUCHSCREEN, 0
        )
    }
}
