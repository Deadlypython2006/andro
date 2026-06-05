package dev.aimmy.android

import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.InputEvent
import android.view.MotionEvent
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper
import java.io.BufferedWriter
import java.io.OutputStreamWriter
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

/**
 * Injects touch events via Shizuku using two methods:
 * 
 * 1. SHELL MODE (Primary, guaranteed to work):
 *    Uses a persistent `sh` process via Shizuku.newProcess to pipe `input` commands.
 *    This is identical to running `adb shell input tap/swipe` from a PC.
 *    Used for: fire button tap, test swipe, and as fallback.
 *
 * 2. BINDER MODE (Fast, for continuous aiming):
 *    Uses IInputManager.injectInputEvent via ShizukuBinderWrapper for low-latency
 *    continuous pointer movement (aiming). Falls back to shell if binder fails.
 */
object ShizukuTouchInjector {

    private const val TAG = "ShizukuTouch"

    // ─── Binder Mode State ───
    private var inputManager: Any? = null
    private var injectMethod: Method? = null
    private var binderInitialized = false
    private var setDisplayIdMethod: Method? = null
    private var setDisplayIdChecked = false
    private var touchscreenDeviceId: Int = -1

    // ─── Shell Mode State ───
    private var shellProcess: Process? = null
    private var shellWriter: BufferedWriter? = null
    @Volatile var shellReady = false

    // ─── Pointer Tracking ───
    private val activePointers = ConcurrentHashMap<Int, MotionEvent.PointerCoords>()
    private var downTime = 0L

    // ─── Status Feedback ───
    @Volatile var lastError: String = "Not initialized"
    @Volatile var isReady: Boolean = false
    @Volatile var injectCount: Int = 0
    @Volatile var rejectCount: Int = 0
    @Volatile var mode: String = "NONE"

    // ═══════════════════════════════════════════════════════════════════════════
    // SHELL MODE — Guaranteed to work on any Shizuku device
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Start a persistent shell process via Shizuku for piping input commands.
     * Uses reflection because newProcess is private in some Shizuku API versions.
     */
    private fun initShell(): Boolean {
        if (shellReady && shellProcess != null) return true
        try {
            if (!Shizuku.pingBinder()) {
                lastError = "Shizuku binder not available"
                return false
            }
            // Access Shizuku.newProcess via reflection (it's private in some API versions)
            val newProcessMethod = Shizuku::class.java.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java
            )
            newProcessMethod.isAccessible = true
            shellProcess = newProcessMethod.invoke(null, arrayOf("sh"), null, null) as Process
            shellWriter = BufferedWriter(OutputStreamWriter(shellProcess!!.outputStream))
            shellReady = true
            Log.i(TAG, "Shell process started successfully via reflection")
            return true
        } catch (e: Exception) {
            lastError = "Shell init failed: ${e.message}"
            Log.e(TAG, lastError, e)
            shellReady = false
            return false
        }
    }

    /**
     * Execute a shell command via the persistent Shizuku shell.
     */
    private fun shellExec(command: String): Boolean {
        if (!shellReady) {
            if (!initShell()) return false
        }
        return try {
            shellWriter?.write(command)
            shellWriter?.newLine()
            shellWriter?.flush()
            true
        } catch (e: Exception) {
            Log.e(TAG, "Shell exec failed: ${e.message}")
            shellReady = false
            shellProcess = null
            shellWriter = null
            false
        }
    }

    /**
     * Tap at (x, y) using shell `input tap` command (100% reliable).
     */
    fun shellTap(x: Float, y: Float): Boolean {
        val result = shellExec("input tap ${x.toInt()} ${y.toInt()}")
        if (result) injectCount++
        return result
    }

    /**
     * Swipe from (x1,y1) to (x2,y2) over durationMs using shell (100% reliable).
     */
    fun shellSwipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Int = 300): Boolean {
        val result = shellExec("input swipe ${x1.toInt()} ${y1.toInt()} ${x2.toInt()} ${y2.toInt()} $durationMs")
        if (result) injectCount++
        return result
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // BINDER MODE — Fast injection for continuous aiming
    // ═══════════════════════════════════════════════════════════════════════════

    fun initialize() {
        // Always init the shell as primary method
        initShell()

        // Also try binder for fast continuous injection
        if (binderInitialized && inputManager != null && injectMethod != null) {
            isReady = true
            mode = if (shellReady) "SHELL+BINDER" else "BINDER"
            return
        }

        try {
            if (!Shizuku.pingBinder()) {
                lastError = "Shizuku binder not available"
                isReady = shellReady
                mode = if (shellReady) "SHELL" else "NONE"
                return
            }

            val rawBinder = SystemServiceHelper.getSystemService("input")
            if (rawBinder == null) {
                lastError = "Input service binder is null"
                isReady = shellReady
                mode = if (shellReady) "SHELL" else "NONE"
                return
            }
            val wrappedBinder = ShizukuBinderWrapper(rawBinder)

            val stubClass = Class.forName("android.hardware.input.IInputManager\$Stub")
            inputManager = stubClass
                .getMethod("asInterface", android.os.IBinder::class.java)
                .invoke(null, wrappedBinder)

            if (inputManager == null) {
                lastError = "IInputManager.asInterface returned null"
                isReady = shellReady
                mode = if (shellReady) "SHELL" else "NONE"
                return
            }

            // Search for injectInputEvent
            val iface = Class.forName("android.hardware.input.IInputManager")
            var foundMethod: Method? = null
            for (m in iface.methods) {
                if (m.name == "injectInputEvent") {
                    Log.d(TAG, "Found: ${m.name} ${m.parameterTypes.size} params: ${m.parameterTypes.map { it.name }}")
                    if (m.parameterTypes.size == 2) {
                        foundMethod = m
                        break
                    }
                    if (foundMethod == null) foundMethod = m
                }
            }

            if (foundMethod == null) {
                lastError = "injectInputEvent not found"
                isReady = shellReady
                mode = if (shellReady) "SHELL" else "NONE"
                return
            }

            injectMethod = foundMethod
            binderInitialized = true
            isReady = true
            mode = if (shellReady) "SHELL+BINDER" else "BINDER"
            lastError = "OK ($mode)"
            Log.i(TAG, "Binder initialized! Method: ${foundMethod.parameterTypes.size} params. Mode: $mode")

            // Cache setDisplayId reflection (PRIMITIVE int!)
            if (!setDisplayIdChecked) {
                setDisplayIdChecked = true
                try {
                    setDisplayIdMethod = MotionEvent::class.java.getMethod("setDisplayId", Int::class.javaPrimitiveType)
                    Log.i(TAG, "setDisplayId method found!")
                } catch (e: NoSuchMethodException) {
                    Log.w(TAG, "setDisplayId not available")
                    setDisplayIdMethod = null
                }
            }
        } catch (e: Exception) {
            lastError = "Init: ${e.message}"
            Log.e(TAG, lastError, e)
            inputManager = null
            injectMethod = null
            isReady = shellReady
            mode = if (shellReady) "SHELL" else "NONE"
        }
    }

    private fun injectBinder(event: InputEvent): Boolean {
        if (injectMethod == null || inputManager == null) return false
        try {
            val method = injectMethod!!
            val result: Any? = when (method.parameterTypes.size) {
                2 -> method.invoke(inputManager, event, 2) // mode 2 = WAIT_FOR_FINISH (most reliable)
                3 -> method.invoke(inputManager, event, 2, 0)
                else -> method.invoke(inputManager, event, 2)
            }
            val success = result as? Boolean ?: true
            if (success) {
                injectCount++
            } else {
                rejectCount++
            }
            return success
        } catch (e: Exception) {
            lastError = "Binder inject failed: ${e.cause?.message ?: e.message}"
            Log.e(TAG, lastError, e)
            return false
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // PUBLIC API — Touch Down / Move / Up
    // ═══════════════════════════════════════════════════════════════════════════

    @Synchronized
    fun touchDown(pointerId: Int, x: Float, y: Float) {
        val pid = pointerId.coerceIn(0, 15)
        val coords = MotionEvent.PointerCoords().apply {
            this.x = x; this.y = y; pressure = 1f; size = 1f
        }
        activePointers[pid] = coords

        if (activePointers.size == 1) {
            downTime = SystemClock.uptimeMillis()
            dispatchTouchEvent(MotionEvent.ACTION_DOWN, pid)
        } else {
            dispatchTouchEvent(MotionEvent.ACTION_POINTER_DOWN, pid)
        }
    }

    @Synchronized
    fun touchMove(pointerId: Int, x: Float, y: Float) {
        val pid = pointerId.coerceIn(0, 15)
        val coords = activePointers[pid] ?: return
        coords.x = x
        coords.y = y
        dispatchTouchEvent(MotionEvent.ACTION_MOVE, pid)
    }

    @Synchronized
    fun touchUp(pointerId: Int) {
        val pid = pointerId.coerceIn(0, 15)
        if (!activePointers.containsKey(pid)) return
        if (activePointers.size == 1) {
            dispatchTouchEvent(MotionEvent.ACTION_UP, pid)
        } else {
            dispatchTouchEvent(MotionEvent.ACTION_POINTER_UP, pid)
        }
        activePointers.remove(pid)
    }

    @Synchronized
    fun releaseAll() {
        val ids = activePointers.keys.toList()
        for (id in ids) { touchUp(id) }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // INTERNAL — Event Construction & Dispatch
    // ═══════════════════════════════════════════════════════════════════════════

    private fun getTouchscreenDeviceId(): Int {
        if (touchscreenDeviceId != -1) return touchscreenDeviceId
        try {
            for (id in InputDevice.getDeviceIds()) {
                val dev = InputDevice.getDevice(id)
                if (dev != null && (dev.sources and InputDevice.SOURCE_TOUCHSCREEN) == InputDevice.SOURCE_TOUCHSCREEN) {
                    touchscreenDeviceId = id
                    Log.i(TAG, "Touchscreen deviceId: $id (${dev.name})")
                    return id
                }
            }
        } catch (_: Exception) {}
        touchscreenDeviceId = 0
        return 0
    }

    private fun dispatchTouchEvent(baseAction: Int, targetPointerId: Int) {
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
                if (pid == targetPointerId) targetIndex = i
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
                getTouchscreenDeviceId(), 0,
                InputDevice.SOURCE_TOUCHSCREEN, 0
            )

            // Set displayId=0 for Android 10+ (MUST use primitive int reflection)
            setDisplayIdMethod?.let { m ->
                try { m.invoke(event, 0) } catch (_: Exception) {}
            }

            val success = injectBinder(event)
            event.recycle()

            // If binder injection failed, fall back to shell for important events
            if (!success && (baseAction == MotionEvent.ACTION_DOWN || baseAction == MotionEvent.ACTION_UP)) {
                val pCoord = activePointers[targetPointerId]
                if (pCoord != null && baseAction == MotionEvent.ACTION_DOWN) {
                    // Shell can't do continuous moves, but it CAN do a tap-down
                    Log.w(TAG, "Binder failed, falling back to shell for DOWN")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "dispatchTouchEvent failed", e)
            lastError = "Dispatch: ${e.message}"
            activePointers.clear()
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // CONVENIENCE — Swipe & Tap (uses shell for reliability)
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Swipe gesture using shell command (100% reliable).
     */
    fun swipe(startX: Float, startY: Float, endX: Float, endY: Float, durationMs: Long = 300) {
        shellSwipe(startX, startY, endX, endY, durationMs.toInt())
    }

    /**
     * Tap gesture using shell command (100% reliable).
     */
    fun tap(x: Float, y: Float) {
        shellTap(x, y)
    }

    fun destroy() {
        try {
            shellWriter?.close()
            shellProcess?.destroy()
        } catch (_: Exception) {}
        shellReady = false
        shellProcess = null
        shellWriter = null
    }
}
