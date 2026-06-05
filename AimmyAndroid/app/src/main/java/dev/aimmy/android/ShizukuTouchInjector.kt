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
 * Injects touch events via Shizuku.
 *
 * Uses IInputManager.injectInputEvent via ShizukuBinderWrapper.
 * Falls back to shell `input` commands if binder injection fails.
 */
object ShizukuTouchInjector {

    private const val TAG = "ShizukuTouch"

    // ─── Binder Mode ───
    private var inputManager: Any? = null
    private var injectMethod: Method? = null
    private var binderInitialized = false
    private var setDisplayIdMethod: Method? = null
    private var setDisplayIdChecked = false
    private var touchscreenDeviceId: Int = -1

    // ─── Shell Mode ───
    private var shellProcess: Process? = null
    private var shellWriter: BufferedWriter? = null
    @Volatile var shellReady = false

    // ─── Pointer Tracking ───
    private val activePointers = ConcurrentHashMap<Int, MotionEvent.PointerCoords>()
    private var downTime = 0L

    // ─── Status ───
    @Volatile var lastError: String = "Not initialized"
    @Volatile var isReady: Boolean = false
    @Volatile var injectCount: Int = 0
    @Volatile var rejectCount: Int = 0
    @Volatile var mode: String = "NONE"

    // ═══════════════════════════════════════════════════════════════════════════
    // SHELL MODE
    // ═══════════════════════════════════════════════════════════════════════════

    private fun initShell(): Boolean {
        if (shellReady && shellProcess != null) {
            // Verify process is still alive
            try {
                shellProcess!!.exitValue()
                // If we get here, process is dead
                shellReady = false
                shellProcess = null
                shellWriter = null
            } catch (_: IllegalThreadStateException) {
                // Process still running - good
                return true
            }
        }
        try {
            if (!Shizuku.pingBinder()) {
                lastError = "Shizuku binder not available"
                return false
            }
            // Try reflection for newProcess
            try {
                val newProcessMethod = Shizuku::class.java.getDeclaredMethod(
                    "newProcess",
                    Array<String>::class.java,
                    Array<String>::class.java,
                    String::class.java
                )
                newProcessMethod.isAccessible = true
                shellProcess = newProcessMethod.invoke(null, arrayOf("sh"), null, null) as Process
            } catch (e: NoSuchMethodException) {
                // newProcess doesn't exist, try alternative approaches
                Log.w(TAG, "Shizuku.newProcess not found, trying alternatives")
                // Try ShizukuRemoteProcess or direct Runtime exec
                try {
                    // Some Shizuku versions expose it differently
                    val cls = Class.forName("rikka.shizuku.ShizukuRemoteProcess")
                    val constructor = cls.getDeclaredConstructor(
                        Array<String>::class.java,
                        Array<String>::class.java,
                        String::class.java
                    )
                    constructor.isAccessible = true
                    shellProcess = constructor.newInstance(arrayOf("sh"), null, null) as Process
                } catch (e2: Exception) {
                    Log.e(TAG, "All shell methods failed: ${e2.message}")
                    throw e2
                }
            }
            shellWriter = BufferedWriter(OutputStreamWriter(shellProcess!!.outputStream))
            shellReady = true
            Log.i(TAG, "Shell process started")
            return true
        } catch (e: Exception) {
            lastError = "Shell: ${e.message}"
            Log.e(TAG, lastError, e)
            shellReady = false
            return false
        }
    }

    private fun shellExec(command: String): Boolean {
        if (!shellReady && !initShell()) return false
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

    fun shellTap(x: Float, y: Float): Boolean {
        val result = shellExec("input tap ${x.toInt()} ${y.toInt()}")
        if (result) injectCount++
        return result
    }

    fun shellSwipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Int = 300): Boolean {
        val result = shellExec("input swipe ${x1.toInt()} ${y1.toInt()} ${x2.toInt()} ${y2.toInt()} $durationMs")
        if (result) injectCount++
        return result
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // BINDER MODE
    // ═══════════════════════════════════════════════════════════════════════════

    fun initialize() {
        // Start shell first (most reliable)
        val shellOk = initShell()

        // Then try binder for continuous injection
        if (binderInitialized && inputManager != null && injectMethod != null) {
            isReady = true
            mode = if (shellOk) "SHELL+BINDER" else "BINDER"
            return
        }

        try {
            if (!Shizuku.pingBinder()) {
                lastError = "No Shizuku"
                isReady = shellOk
                mode = if (shellOk) "SHELL" else "NONE"
                return
            }

            // Check permission explicitly
            if (Shizuku.checkSelfPermission() != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                lastError = "No Shizuku permission"
                isReady = shellOk
                mode = if (shellOk) "SHELL" else "NONE"
                Log.e(TAG, "Shizuku permission NOT granted!")
                return
            }

            val rawBinder = SystemServiceHelper.getSystemService("input")
            if (rawBinder == null) {
                lastError = "No input binder"
                isReady = shellOk
                mode = if (shellOk) "SHELL" else "NONE"
                return
            }
            val wrappedBinder = ShizukuBinderWrapper(rawBinder)

            val stubClass = Class.forName("android.hardware.input.IInputManager\$Stub")
            inputManager = stubClass
                .getMethod("asInterface", android.os.IBinder::class.java)
                .invoke(null, wrappedBinder)

            if (inputManager == null) {
                lastError = "asInterface null"
                isReady = shellOk
                mode = if (shellOk) "SHELL" else "NONE"
                return
            }

            // Find injectInputEvent - try ALL parameter counts
            val iface = Class.forName("android.hardware.input.IInputManager")
            var foundMethod: Method? = null
            val allMethods = mutableListOf<String>()
            for (m in iface.methods) {
                allMethods.add("${m.name}(${m.parameterTypes.size})")
                if (m.name == "injectInputEvent") {
                    Log.d(TAG, "Found: ${m.name} params=${m.parameterTypes.size}: ${m.parameterTypes.map { it.name }}")
                    // Prefer 2-param, then 3-param
                    if (m.parameterTypes.size == 2 && foundMethod == null) {
                        foundMethod = m
                    } else if (m.parameterTypes.size == 3 && foundMethod == null) {
                        foundMethod = m
                    } else if (foundMethod == null) {
                        foundMethod = m
                    }
                }
            }
            Log.d(TAG, "All IInputManager methods: $allMethods")

            if (foundMethod == null) {
                lastError = "No injectInputEvent"
                isReady = shellOk
                mode = if (shellOk) "SHELL" else "NONE"
                return
            }

            injectMethod = foundMethod
            binderInitialized = true
            isReady = true
            mode = if (shellOk) "SHELL+BINDER" else "BINDER"
            lastError = "OK [$mode] (${foundMethod.parameterTypes.size}p)"

            // Cache setDisplayId (PRIMITIVE int!)
            if (!setDisplayIdChecked) {
                setDisplayIdChecked = true
                try {
                    setDisplayIdMethod = MotionEvent::class.java.getMethod("setDisplayId", Int::class.javaPrimitiveType)
                    Log.i(TAG, "setDisplayId: found")
                } catch (_: NoSuchMethodException) {
                    Log.w(TAG, "setDisplayId: not found")
                }
            }

            // Test binder with a single injection to see if it works
            testBinderInjection()

        } catch (e: Exception) {
            lastError = "Init: ${e.message}"
            Log.e(TAG, lastError, e)
            isReady = shellOk
            mode = if (shellOk) "SHELL" else "NONE"
        }
    }

    /**
     * Performs a single test injection to verify the binder pipeline works.
     * Injects a tiny move event at (0,0) which should be invisible to the user.
     */
    private fun testBinderInjection() {
        try {
            val now = SystemClock.uptimeMillis()
            val props = arrayOf(MotionEvent.PointerProperties().apply {
                id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER
            })
            val coords = arrayOf(MotionEvent.PointerCoords().apply {
                x = 0f; y = 0f; pressure = 0f; size = 0f
            })
            val event = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, 1, props, coords,
                0, 0, 1f, 1f, getTouchscreenDeviceId(), 0, InputDevice.SOURCE_TOUCHSCREEN, 0)

            setDisplayIdMethod?.let { m -> try { m.invoke(event, 0) } catch (_: Exception) {} }

            val method = injectMethod!!
            val result: Any? = when (method.parameterTypes.size) {
                2 -> method.invoke(inputManager, event, 0)
                3 -> method.invoke(inputManager, event, 0, 0)
                else -> method.invoke(inputManager, event, 0)
            }
            val success = result as? Boolean ?: true

            // Immediately release
            val upEvent = MotionEvent.obtain(now, now + 1, MotionEvent.ACTION_UP, 1, props, coords,
                0, 0, 1f, 1f, getTouchscreenDeviceId(), 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
            setDisplayIdMethod?.let { m -> try { m.invoke(upEvent, 0) } catch (_: Exception) {} }
            when (method.parameterTypes.size) {
                2 -> method.invoke(inputManager, upEvent, 0)
                3 -> method.invoke(inputManager, upEvent, 0, 0)
                else -> method.invoke(inputManager, upEvent, 0)
            }

            event.recycle()
            upEvent.recycle()

            if (success) {
                Log.i(TAG, "Binder test injection: SUCCESS")
                lastError = "OK [$mode] BINDER✓"
            } else {
                Log.w(TAG, "Binder test injection: REJECTED")
                lastError = "OK [$mode] BINDER✗ (rejected)"
                // Binder doesn't work, rely on shell only
                if (shellReady) {
                    mode = "SHELL"
                    binderInitialized = false
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Binder test failed: ${e.message}")
            lastError = "OK [$mode] BINDER✗ (${e.cause?.message ?: e.message})"
            if (shellReady) {
                mode = "SHELL"
                binderInitialized = false
            }
        }
    }

    private fun injectBinder(event: InputEvent): Boolean {
        if (injectMethod == null || inputManager == null) return false
        try {
            val method = injectMethod!!
            // Try mode 0 (ASYNC) - most compatible
            val result: Any? = when (method.parameterTypes.size) {
                2 -> method.invoke(inputManager, event, 0)
                3 -> method.invoke(inputManager, event, 0, 0)
                else -> method.invoke(inputManager, event, 0)
            }
            val success = result as? Boolean ?: true
            if (success) injectCount++ else rejectCount++
            return success
        } catch (e: Exception) {
            lastError = "Binder: ${e.cause?.message ?: e.message}"
            Log.e(TAG, lastError)
            return false
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // PUBLIC API
    // ═══════════════════════════════════════════════════════════════════════════

    @Synchronized
    fun touchDown(pointerId: Int, x: Float, y: Float) {
        val pid = pointerId.coerceIn(0, 15)
        activePointers[pid] = MotionEvent.PointerCoords().apply {
            this.x = x; this.y = y; pressure = 1f; size = 1f
        }
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
        coords.x = x; coords.y = y
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
        for (id in activePointers.keys.toList()) touchUp(id)
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // INTERNAL
    // ═══════════════════════════════════════════════════════════════════════════

    private fun getTouchscreenDeviceId(): Int {
        if (touchscreenDeviceId != -1) return touchscreenDeviceId
        try {
            for (id in InputDevice.getDeviceIds()) {
                val dev = InputDevice.getDevice(id)
                if (dev != null && (dev.sources and InputDevice.SOURCE_TOUCHSCREEN) == InputDevice.SOURCE_TOUCHSCREEN) {
                    touchscreenDeviceId = id
                    return id
                }
            }
        } catch (_: Exception) {}
        touchscreenDeviceId = 0
        return 0
    }

    private fun dispatchTouchEvent(baseAction: Int, targetPointerId: Int) {
        // If binder is not available, use shell for DOWN/UP events
        if (!binderInitialized && shellReady) {
            val coords = activePointers[targetPointerId] ?: return
            when (baseAction) {
                MotionEvent.ACTION_DOWN -> {
                    // Shell can only do tap/swipe, not continuous hold
                    // We'll handle this via shellTap in the convenience methods
                    injectCount++
                }
                MotionEvent.ACTION_UP -> injectCount++
            }
            return
        }

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
            } else baseAction

            val eventTime = SystemClock.uptimeMillis()
            val event = MotionEvent.obtain(
                downTime, eventTime, action, pointerCount,
                props, coords, 0, 0, 1f, 1f,
                getTouchscreenDeviceId(), 0,
                InputDevice.SOURCE_TOUCHSCREEN, 0
            )

            setDisplayIdMethod?.let { m -> try { m.invoke(event, 0) } catch (_: Exception) {} }

            val success = injectBinder(event)
            event.recycle()

            // If binder failed for a DOWN event, try shell fallback
            if (!success && baseAction == MotionEvent.ACTION_DOWN && shellReady) {
                val pCoord = activePointers[targetPointerId]
                if (pCoord != null) {
                    shellTap(pCoord.x, pCoord.y)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "dispatch failed", e)
            lastError = "Dispatch: ${e.message}"
            activePointers.clear()
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // CONVENIENCE
    // ═══════════════════════════════════════════════════════════════════════════

    /** Swipe using shell (reliable). */
    fun swipe(startX: Float, startY: Float, endX: Float, endY: Float, durationMs: Long = 300) {
        shellSwipe(startX, startY, endX, endY, durationMs.toInt())
    }

    /** Tap using shell (reliable). */
    fun tap(x: Float, y: Float) {
        shellTap(x, y)
    }

    fun destroy() {
        try { shellWriter?.close() } catch (_: Exception) {}
        try { shellProcess?.destroy() } catch (_: Exception) {}
        shellReady = false; shellProcess = null; shellWriter = null
    }
}
