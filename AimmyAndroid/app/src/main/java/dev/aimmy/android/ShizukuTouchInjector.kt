package dev.aimmy.android

import android.os.SystemClock
import android.util.Log
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

    private const val TAG = "ShizukuTouch"

    private var inputManager: Any? = null
    private var injectMethod: Method? = null
    private var initialized = false

    private val activePointers = ConcurrentHashMap<Int, MotionEvent.PointerCoords>()
    private var downTime = 0L

    // Status feedback for UI
    @Volatile var lastError: String = "Not initialized"
    @Volatile var isReady: Boolean = false
    @Volatile var injectCount: Int = 0

    fun initialize() {
        if (initialized && inputManager != null && injectMethod != null) {
            isReady = true
            return
        }

        try {
            if (!Shizuku.pingBinder()) {
                lastError = "Shizuku binder not available"
                Log.e(TAG, lastError)
                isReady = false
                return
            }
        } catch (e: Exception) {
            lastError = "Shizuku ping failed: ${e.message}"
            Log.e(TAG, lastError)
            isReady = false
            return
        }

        try {
            val rawBinder = SystemServiceHelper.getSystemService("input")
            if (rawBinder == null) {
                lastError = "Input service binder is null"
                Log.e(TAG, lastError)
                isReady = false
                return
            }
            val wrappedBinder = ShizukuBinderWrapper(rawBinder)

            val stubClass = Class.forName("android.hardware.input.IInputManager\$Stub")
            inputManager = stubClass
                .getMethod("asInterface", android.os.IBinder::class.java)
                .invoke(null, wrappedBinder)

            if (inputManager == null) {
                lastError = "IInputManager.asInterface returned null"
                Log.e(TAG, lastError)
                isReady = false
                return
            }

            // Search for injectInputEvent — try 2-param first, then 3-param
            val iface = Class.forName("android.hardware.input.IInputManager")
            var foundMethod: Method? = null
            for (m in iface.methods) {
                if (m.name == "injectInputEvent") {
                    Log.d(TAG, "Found method: ${m.name} with ${m.parameterTypes.size} params: ${m.parameterTypes.map { it.name }}")
                    if (m.parameterTypes.size == 2) {
                        foundMethod = m
                        break // Prefer 2-param version
                    }
                    if (foundMethod == null) {
                        foundMethod = m // Fall back to any version
                    }
                }
            }

            if (foundMethod == null) {
                lastError = "injectInputEvent method not found in IInputManager"
                Log.e(TAG, lastError)
                isReady = false
                return
            }

            injectMethod = foundMethod
            initialized = true
            isReady = true
            lastError = "OK (${foundMethod.parameterTypes.size} params)"
            Log.i(TAG, "Initialized successfully! Method: ${foundMethod.parameterTypes.size} params")
        } catch (e: Exception) {
            lastError = "Init exception: ${e.message}"
            Log.e(TAG, lastError, e)
            inputManager = null
            injectMethod = null
            isReady = false
        }
    }

    private fun inject(event: InputEvent): Boolean {
        if (injectMethod == null || inputManager == null) {
            initialize()
            if (injectMethod == null) {
                return false
            }
        }
        try {
            val method = injectMethod!!
            val result: Any? = when (method.parameterTypes.size) {
                2 -> method.invoke(inputManager, event, 2) // mode 2 = WAIT_FOR_FINISH
                3 -> method.invoke(inputManager, event, 2, 0) // 3rd param = displayId
                else -> method.invoke(inputManager, event, 2)
            }
            injectCount++

            if (result is Boolean && !result) {
                Log.w(TAG, "injectInputEvent returned false")
                return false
            }
            return true
        } catch (e: Exception) {
            lastError = "Inject failed: ${e.cause?.message ?: e.message}"
            Log.e(TAG, lastError, e)
            // Don't wipe state on every failure — try to recover
            return false
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

    /**
     * Force-release all pointers. Call on cleanup/error recovery.
     */
    @Synchronized
    fun releaseAll() {
        val ids = activePointers.keys.toList()
        for (id in ids) {
            touchUp(id)
        }
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
                props, coords, 0, 0, 1f, 1f,
                0, // deviceId = 0 (default touchscreen, not spoofed)
                0,
                InputDevice.SOURCE_TOUCHSCREEN, 0
            )
            
            inject(event)
            event.recycle()
        } catch (e: Exception) {
            Log.e(TAG, "dispatchMultiTouchEvent failed", e)
            lastError = "Dispatch error: ${e.message}"
            activePointers.clear()
        }
    }
}
