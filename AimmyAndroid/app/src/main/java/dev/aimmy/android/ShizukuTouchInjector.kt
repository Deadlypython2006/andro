package dev.aimmy.android

import android.os.SystemClock
import android.view.InputDevice
import android.view.InputEvent
import android.view.MotionEvent
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

/**
 * Injects multi-touch events at the system level via Shizuku + IInputManager binder.
 */
object ShizukuTouchInjector {

    private var inputManager: Any? = null
    private var injectMethod: Method? = null
    private var initialized = false

    private val activePointers = ConcurrentHashMap<Int, MotionEvent.PointerCoords>()
    private var downTime = 0L

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
            injectMethod?.invoke(inputManager, event, 0)
        } catch (e: Exception) {
            initialized = false
            inputManager = null
        }
    }

    @Synchronized
    fun touchDown(pointerId: Int, x: Float, y: Float) {
        val coords = MotionEvent.PointerCoords().apply {
            this.x = x; this.y = y; pressure = 1f; size = 1f
        }
        activePointers[pointerId] = coords

        if (activePointers.size == 1) {
            downTime = SystemClock.uptimeMillis()
            dispatchMultiTouchEvent(MotionEvent.ACTION_DOWN, pointerId)
        } else {
            dispatchMultiTouchEvent(MotionEvent.ACTION_POINTER_DOWN, pointerId)
        }
    }

    @Synchronized
    fun touchMove(pointerId: Int, x: Float, y: Float) {
        val coords = activePointers[pointerId] ?: return
        coords.x = x
        coords.y = y
        dispatchMultiTouchEvent(MotionEvent.ACTION_MOVE, pointerId)
    }

    @Synchronized
    fun touchUp(pointerId: Int) {
        if (!activePointers.containsKey(pointerId)) return

        if (activePointers.size == 1) {
            dispatchMultiTouchEvent(MotionEvent.ACTION_UP, pointerId)
        } else {
            dispatchMultiTouchEvent(MotionEvent.ACTION_POINTER_UP, pointerId)
        }
        activePointers.remove(pointerId)
    }

    private fun dispatchMultiTouchEvent(baseAction: Int, targetPointerId: Int) {
        try {
            val pointerIds = activePointers.keys.sorted()
            val pointerCount = pointerIds.size
            if (pointerCount == 0) return
            
            val props = Array(pointerCount) { MotionEvent.PointerProperties() }
            val coords = Array(pointerCount) { MotionEvent.PointerCoords() }
            
            var targetIndex = 0

            for (i in 0 until pointerCount) {
                val pid = pointerIds[i]
                props[i].id = pid
                props[i].toolType = MotionEvent.TOOL_TYPE_FINGER
                
                val pCoord = activePointers[pid]!!
                coords[i].x = pCoord.x
                coords[i].y = pCoord.y
                coords[i].pressure = pCoord.pressure
                coords[i].size = pCoord.size
                
                if (pid == targetPointerId) {
                    targetIndex = i
                }
            }

            val action = if (baseAction == MotionEvent.ACTION_POINTER_DOWN || baseAction == MotionEvent.ACTION_POINTER_UP) {
                baseAction or (targetIndex shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
            } else {
                baseAction
            }

            val eventTime = SystemClock.uptimeMillis()
            val event = MotionEvent.obtain(
                downTime, eventTime, action, pointerCount,
                props, coords, 0, 0, 1f, 1f, 99, 0, // spoofed deviceId = 99
                InputDevice.SOURCE_TOUCHSCREEN, 0
            )
            
            inject(event)
            event.recycle()
        } catch (e: Exception) {
            e.printStackTrace()
            // Reset state to prevent Android from thinking finger is permanently stuck down
            activePointers.clear()
            initialized = false
        }
    }

    /**
     * Performs a smooth swipe gesture using a specific pointer ID.
     */
    fun swipe(pointerId: Int, startX: Float, startY: Float, endX: Float, endY: Float, steps: Int = 3) {
        touchDown(pointerId, startX, startY)
        if (steps > 0) {
            val stepX = (endX - startX) / steps
            val stepY = (endY - startY) / steps
            for (i in 1..steps) {
                SystemClock.sleep(2)
                touchMove(pointerId, startX + stepX * i, startY + stepY * i)
            }
        }
        SystemClock.sleep(2)
        touchUp(pointerId)
    }
}
