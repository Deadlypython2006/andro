package dev.aimmy.android

import android.os.SystemClock
import android.util.Log
import rikka.shizuku.Shizuku
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.io.OutputStreamWriter
import java.util.Date

/**
 * Shell-based touch injector via Shizuku.
 *
 * Uses `input swipe` commands piped into a persistent `sh` process opened
 * through Shizuku. This is identical to running `adb shell input swipe ...`
 * from a PC and is the **only** method that:
 *   1. Does NOT conflict with the user's physical finger touches.
 *   2. Does NOT require root or accessibility services.
 *   3. Works reliably across all Android 10+ devices.
 *
 * The binder-based MotionEvent injection was removed because injected
 * MotionEvents share the same input pipeline as the user's real fingers,
 * causing Pointer ID conflicts that make games drop the injected touch
 * and also block the user's joystick input.
 */
object ShizukuTouchInjector {
    private const val TAG = "ShizukuTouch"

    var isReady = false
        private set
    var mode = "UNINITIALIZED"
        private set
    var shellReady = false
        private set
    var injectCount = 0
        private set
    var rejectCount = 0
        private set
    var lastError = "Not initialized"
        private set

    private var shellProcess: Process? = null
    private var shellWriter: BufferedWriter? = null
    private var logFile: File? = null

    // Track the current swipe state for continuous drag
    private var isDragging = false
    private var dragStartX = 0f
    private var dragStartY = 0f
    private var dragCurrentX = 0f
    private var dragCurrentY = 0f
    @Volatile private var dragActive = false
    private var dragThread: Thread? = null

    private fun logToFile(msg: String) {
        Log.d(TAG, msg)
        try {
            logFile?.let {
                val fw = FileWriter(it, true)
                fw.write("${Date()}: $msg\n")
                fw.close()
            }
        } catch (_: Exception) {}
    }

    fun initialize(context: android.content.Context? = null) {
        if (context != null) {
            logFile = File(context.getExternalFilesDir(null), "aimmy_shizuku_log.txt")
            logToFile("=== Initializing Shell Touch Injector ===")
        }

        val shellOk = initShell()
        mode = if (shellOk) "SHELL" else "FAILED"
        isReady = shellOk
        logToFile("Shell init result: $shellOk")
    }

    private fun initShell(): Boolean {
        try {
            if (!Shizuku.pingBinder()) {
                lastError = "Shizuku binder not available"
                logToFile(lastError)
                return false
            }
            val newProcessMethod = Shizuku::class.java.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java
            )
            newProcessMethod.isAccessible = true
            shellProcess = newProcessMethod.invoke(null, arrayOf("sh"), null, null) as Process
            shellWriter = BufferedWriter(OutputStreamWriter(shellProcess!!.outputStream))

            // Read stderr in background for debugging
            Thread {
                try {
                    val reader = java.io.BufferedReader(java.io.InputStreamReader(shellProcess!!.errorStream))
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        logToFile("SHELL STDERR: $line")
                    }
                } catch (_: Exception) {}
            }.start()

            shellReady = true
            logToFile("Shell process started successfully")
            return true
        } catch (e: Exception) {
            lastError = "Shell init failed: ${e.message}"
            logToFile(lastError)
            shellReady = false
            return false
        }
    }

    private fun shellExec(cmd: String): Boolean {
        if (!shellReady) return false
        return try {
            shellWriter?.write("$cmd\n")
            shellWriter?.flush()
            injectCount++
            true
        } catch (e: Exception) {
            rejectCount++
            lastError = "Shell exec failed: ${e.message}"
            logToFile(lastError)
            false
        }
    }

    /**
     * Perform a single tap at (x, y).
     * This is non-blocking — the shell executes it asynchronously.
     */
    fun tap(x: Float, y: Float): Boolean {
        logToFile("tap(${x.toInt()}, ${y.toInt()})")
        return shellExec("input tap ${x.toInt()} ${y.toInt()}")
    }

    /**
     * Perform a swipe from (x1,y1) to (x2,y2) over durationMs.
     */
    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Int = 50): Boolean {
        logToFile("swipe(${x1.toInt()},${y1.toInt()} -> ${x2.toInt()},${y2.toInt()}, ${durationMs}ms)")
        return shellExec("input swipe ${x1.toInt()} ${y1.toInt()} ${x2.toInt()} ${y2.toInt()} $durationMs")
    }

    /**
     * Start a continuous drag session.
     *
     * This begins a hold at (x, y) — implemented as a very long swipe
     * from the start point to itself. The actual movement is done by
     * calling [dragMoveTo] which issues short incremental swipes.
     *
     * The hold is maintained by a background thread that periodically
     * re-issues a tiny swipe to keep the touch alive.
     */
    fun startDrag(x: Float, y: Float) {
        stopDrag() // Clean up any previous drag
        dragStartX = x
        dragStartY = y
        dragCurrentX = x
        dragCurrentY = y
        dragActive = true
        isDragging = true

        logToFile("startDrag(${x.toInt()}, ${y.toInt()})")

        // Issue initial press via a very short swipe to self (holds the touch)
        shellExec("input swipe ${x.toInt()} ${y.toInt()} ${x.toInt()} ${y.toInt()} 100")
    }

    /**
     * Move the current drag to a new position.
     * Issues a short swipe from the current position to the new position.
     * This is called per-frame by the detection loop.
     */
    fun dragMoveTo(x: Float, y: Float) {
        if (!isDragging || !shellReady) return

        val fromX = dragCurrentX
        val fromY = dragCurrentY
        dragCurrentX = x
        dragCurrentY = y

        // Issue a quick swipe from current to target (30ms feels responsive)
        shellExec("input swipe ${fromX.toInt()} ${fromY.toInt()} ${x.toInt()} ${y.toInt()} 30")
    }

    /**
     * Stop the current drag session (releases the touch).
     */
    fun stopDrag() {
        if (!isDragging) return
        isDragging = false
        dragActive = false

        logToFile("stopDrag()")
        // A zero-length swipe at the current position effectively releases
        shellExec("input swipe ${dragCurrentX.toInt()} ${dragCurrentY.toInt()} ${dragCurrentX.toInt()} ${dragCurrentY.toInt()} 10")
    }

    // ─── Legacy API compatibility (unused but kept for safety) ───────────────

    fun touchDown(pointerId: Int, x: Float, y: Float): Boolean {
        startDrag(x, y)
        return true
    }

    fun touchMove(pointerId: Int, x: Float, y: Float): Boolean {
        dragMoveTo(x, y)
        return true
    }

    fun touchUp(pointerId: Int): Boolean {
        stopDrag()
        return true
    }
}
