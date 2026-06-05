package dev.aimmy.android

import android.content.Context
import android.hardware.input.InputManager
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.InputEvent
import android.view.MotionEvent
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper
import java.io.File
import java.io.FileWriter
import java.util.Date

/**
 * High-performance Binder-based touch injector.
 * 
 * Reverted to the highly successful Binder API since it provided the best 
 * aimbot tracking ("good shoot and drag").
 * 
 * To fix the joystick blocking and "up/down" flickering, this injector now 
 * uses a custom `deviceId = 99` to simulate a completely separate virtual 
 * touchscreen. This prevents Android's InputDispatcher from mixing up the 
 * aimbot's virtual fingers with the user's real physical fingers.
 */
object ShizukuTouchInjector {
    private const val TAG = "ShizukuTouch"

    var isReady = false; private set
    var mode = "UNINITIALIZED"; private set
    var injectCount = 0; private set
    var rejectCount = 0; private set
    var lastError = "Not initialized"; private set

    private var inputManagerRemote: Any? = null
    private var injectMethodRemote: java.lang.reflect.Method? = null
    private var logFile: File? = null

    // Use a unique virtual device ID so the kernel doesn't confuse our touches
    // with the real physical touchscreen (which is usually ID 0 or 1).
    private const val VIRTUAL_DEVICE_ID = 99

    private fun log(msg: String) {
        Log.d(TAG, msg)
        try { logFile?.appendText("${Date()}: $msg\n") } catch (_: Exception) {}
    }

    fun initialize(context: Context? = null) {
        if (context != null) {
            logFile = File(context.getExternalFilesDir(null), "aimmy_touch_log.txt")
            log("=== Initializing Binder Touch Injector ===")
        }

        try {
            if (!Shizuku.pingBinder()) {
                lastError = "Shizuku not running"
                log(lastError)
                return
            }
            
            // Get the remote IInputManager interface via Shizuku Binder
            val binder = SystemServiceHelper.getSystemService(Context.INPUT_SERVICE)
            val wrappedBinder = ShizukuBinderWrapper(binder)
            val imClass = Class.forName("android.hardware.input.IInputManager\$Stub")
            val asInterfaceMethod = imClass.getDeclaredMethod("asInterface", android.os.IBinder::class.java)
            inputManagerRemote = asInterfaceMethod.invoke(null, wrappedBinder)
            
            // Get the injectInputEvent method
            injectMethodRemote = inputManagerRemote?.javaClass?.getMethod(
                "injectInputEvent", 
                InputEvent::class.java, 
                Int::class.javaPrimitiveType
            )

            // Test injection to ensure permissions are granted
            if (testInjection()) {
                isReady = true
                mode = "BINDER"
                log("Binder injection ready and tested successfully.")
            } else {
                mode = "FAILED"
                log("Binder injection failed test.")
            }
        } catch (e: Exception) {
            lastError = "Init failed: ${e.message}"
            log(lastError)
            mode = "FAILED"
        }
    }

    private fun testInjection(): Boolean {
        return try {
            val now = SystemClock.uptimeMillis()
            val event = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, 0f, 0f, 0)
            event.deviceId = VIRTUAL_DEVICE_ID
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            
            val success = injectMethodRemote?.invoke(inputManagerRemote, event, 0) as? Boolean ?: false
            
            val upEvent = MotionEvent.obtain(now, now + 10, MotionEvent.ACTION_UP, 0f, 0f, 0)
            upEvent.deviceId = VIRTUAL_DEVICE_ID
            upEvent.source = InputDevice.SOURCE_TOUCHSCREEN
            injectMethodRemote?.invoke(inputManagerRemote, upEvent, 0)
            
            success
        } catch (e: Exception) {
            lastError = "Test failed: ${e.message}"
            log(lastError)
            false
        }
    }

    // ─── Multi-touch State Management ────────────────────────────────────────

    private var downTime = 0L
    private val activePointerCoords = linkedMapOf<Int, MotionEvent.PointerCoords>()
    private val activePointerProperties = linkedMapOf<Int, MotionEvent.PointerProperties>()

    fun touchDown(pointerId: Int, x: Float, y: Float): Boolean {
        if (!isReady) return false
        val safeId = pointerId.coerceIn(0, 15)
        
        val coords = MotionEvent.PointerCoords().apply {
            this.x = x; this.y = y; pressure = 1.0f; size = 1.0f
        }
        val props = MotionEvent.PointerProperties().apply {
            id = safeId; toolType = MotionEvent.TOOL_TYPE_FINGER
        }
        
        activePointerCoords[safeId] = coords
        activePointerProperties[safeId] = props
        
        val isFirst = activePointerCoords.size == 1
        val pointerIndex = activePointerCoords.keys.indexOf(safeId)
        
        val action = if (isFirst) {
            MotionEvent.ACTION_DOWN
        } else {
            MotionEvent.ACTION_POINTER_DOWN or (pointerIndex shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
        }
        
        return dispatchGlobalEvent(action)
    }

    fun touchMove(pointerId: Int, x: Float, y: Float): Boolean {
        if (!isReady) return false
        val safeId = pointerId.coerceIn(0, 15)
        if (!activePointerCoords.containsKey(safeId)) return false
        
        activePointerCoords[safeId]?.x = x
        activePointerCoords[safeId]?.y = y
        
        return dispatchGlobalEvent(MotionEvent.ACTION_MOVE)
    }

    fun touchUp(pointerId: Int): Boolean {
        if (!isReady) return false
        val safeId = pointerId.coerceIn(0, 15)
        if (!activePointerCoords.containsKey(safeId)) return false
        
        val isLast = activePointerCoords.size == 1
        val pointerIndex = activePointerCoords.keys.indexOf(safeId)
        
        val action = if (isLast) {
            MotionEvent.ACTION_UP
        } else {
            MotionEvent.ACTION_POINTER_UP or (pointerIndex shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
        }
        
        val success = dispatchGlobalEvent(action)
        
        activePointerCoords.remove(safeId)
        activePointerProperties.remove(safeId)
        
        return success
    }

    private fun dispatchGlobalEvent(action: Int): Boolean {
        if (activePointerCoords.isEmpty()) return false
        
        return try {
            val now = SystemClock.uptimeMillis()
            if (action == MotionEvent.ACTION_DOWN) {
                downTime = now
            }
            
            val propsArray = activePointerProperties.values.toTypedArray()
            val coordsArray = activePointerCoords.values.toTypedArray()
            
            val event = MotionEvent.obtain(
                downTime, now, action,
                propsArray.size, propsArray, coordsArray,
                0, 0, 1.0f, 1.0f, VIRTUAL_DEVICE_ID, 0,
                InputDevice.SOURCE_TOUCHSCREEN, 0
            )
            
            // Mode 0 = INJECT_INPUT_EVENT_MODE_ASYNC
            val success = injectMethodRemote?.invoke(inputManagerRemote, event, 0) as? Boolean ?: false
            
            if (success) injectCount++ else rejectCount++
            event.recycle()
            success
        } catch (e: Exception) {
            rejectCount++
            lastError = e.message ?: "Dispatch failed"
            log(lastError)
            false
        }
    }

    // ─── Convenience API ─────────────────────────────────────────────────────

    fun tap(x: Float, y: Float): Boolean {
        if (!isReady) return false
        touchDown(0, x, y)
        Thread.sleep(20)
        return touchUp(0)
    }

    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Int = 50): Boolean {
        if (!isReady) return false
        touchDown(0, x1, y1)
        Thread.sleep(10)
        touchMove(0, x2, y2)
        Thread.sleep(durationMs.toLong())
        return touchUp(0)
    }
}
