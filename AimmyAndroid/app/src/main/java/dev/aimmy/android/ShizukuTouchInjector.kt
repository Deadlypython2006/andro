package dev.aimmy.android

import android.content.Context
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.Date

/**
 * Shell-based touch injector using sendevent for persistent hold+drag.
 *
 * Uses raw `sendevent` commands piped into a Shizuku shell process.
 * This writes directly to the kernel input device, which:
 *   1. Maintains a continuous touch (no gaps between moves).
 *   2. Uses a unique tracking ID that doesn't conflict with real fingers.
 *   3. Supports true hold+drag (press fire button, drag to aim, release).
 */
object ShizukuTouchInjector {
    private const val TAG = "ShizukuTouch"

    var isReady = false; private set
    var mode = "UNINITIALIZED"; private set
    var shellReady = false; private set
    var injectCount = 0; private set
    var rejectCount = 0; private set
    var lastError = "Not initialized"; private set

    private var shellProcess: Process? = null
    private var shellWriter: BufferedWriter? = null
    private var logFile: File? = null

    // Touch device info (detected at init)
    private var devicePath = ""
    private var xMax = 0
    private var yMax = 0
    private var screenW = 0
    private var screenH = 0
    private var currentTrackingId = 12000 // High ID to avoid conflict with real fingers

    // State
    private var isDown = false

    private fun log(msg: String) {
        Log.d(TAG, msg)
        try { logFile?.appendText("${Date()}: $msg\n") } catch (_: Exception) {}
    }

    fun initialize(context: Context? = null) {
        if (context != null) {
            logFile = File(context.getExternalFilesDir(null), "aimmy_touch_log.txt")
            log("=== Initializing sendevent Touch Injector ===")

            val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val m = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(m)
            screenW = m.widthPixels
            screenH = m.heightPixels
            log("Screen: ${screenW}x${screenH}")
        }

        if (!initShell()) { mode = "FAILED"; return }
        if (!detectDevice(context)) { 
            // Fallback to input swipe mode
            mode = "SHELL_SWIPE"
            isReady = true
            log("sendevent detection failed, using input swipe fallback")
            return
        }
        mode = "SENDEVENT"
        isReady = true
        log("Ready! Device=$devicePath xMax=$xMax yMax=$yMax")
    }

    private fun initShell(): Boolean {
        try {
            if (!Shizuku.pingBinder()) { lastError = "Shizuku not running"; log(lastError); return false }
            val m = Shizuku::class.java.getDeclaredMethod("newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java)
            m.isAccessible = true
            shellProcess = m.invoke(null, arrayOf("sh"), null, null) as Process
            shellWriter = BufferedWriter(OutputStreamWriter(shellProcess!!.outputStream))
            Thread { try { BufferedReader(InputStreamReader(shellProcess!!.errorStream)).forEachLine { log("ERR: $it") } } catch (_: Exception) {} }.start()
            shellReady = true
            return true
        } catch (e: Exception) { lastError = "Shell init: ${e.message}"; log(lastError); return false }
    }

    private fun detectDevice(context: Context?): Boolean {
        try {
            val script = """
                for dev in /dev/input/event*; do
                  if getevent -pl "${'$'}dev" 2>/dev/null | grep -q "ABS_MT_POSITION_X"; then
                    echo "DEVICE:${'$'}dev"
                    getevent -pl "${'$'}dev" 2>/dev/null | grep "ABS_MT_POSITION_" | head -2
                    break
                  fi
                done
            """.trimIndent()
            
            val m = Shizuku::class.java.getDeclaredMethod("newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java)
            m.isAccessible = true
            val process = m.invoke(null, arrayOf("sh", "-c", script), null, null) as Process
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val lines = reader.readLines()
            process.waitFor()
            
            log("Detection output: $lines")

            for (line in lines) {
                if (line.startsWith("DEVICE:")) {
                    devicePath = line.removePrefix("DEVICE:").trim()
                }
                if (line.contains("ABS_MT_POSITION_X") && line.contains("max")) {
                    val maxMatch = Regex("max\\s+(\\d+)").find(line)
                    if (maxMatch != null) xMax = maxMatch.groupValues[1].toInt()
                }
                if (line.contains("ABS_MT_POSITION_Y") && line.contains("max")) {
                    val maxMatch = Regex("max\\s+(\\d+)").find(line)
                    if (maxMatch != null) yMax = maxMatch.groupValues[1].toInt()
                }
            }
            return devicePath.isNotEmpty() && xMax > 0 && yMax > 0
        } catch (e: Exception) { log("Detection error: ${e.message}"); return false }
    }

    private fun shellExec(cmd: String): Boolean {
        if (!shellReady) return false
        return try {
            shellWriter?.write("$cmd\n")
            shellWriter?.flush()
            true
        } catch (e: Exception) { rejectCount++; lastError = e.message ?: "write fail"; false }
    }

    private fun toRawX(screenX: Float): Int = if (screenW > 0) (screenX / screenW * xMax).toInt().coerceIn(0, xMax) else screenX.toInt()
    private fun toRawY(screenY: Float): Int = if (screenH > 0) (screenY / screenH * yMax).toInt().coerceIn(0, yMax) else screenY.toInt()

    // ─── Core Touch API ──────────────────────────────────────────────────────

    fun touchDown(pointerId: Int, x: Float, y: Float): Boolean {
        if (mode == "SHELL_SWIPE") {
            // Fallback: start a long swipe (holds touch for 10 seconds)
            shellExec("input swipe ${x.toInt()} ${y.toInt()} ${x.toInt()} ${y.toInt()} 10000 &")
            isDown = true
            injectCount++
            return true
        }
        if (mode != "SENDEVENT" || devicePath.isEmpty()) return false
        val rx = toRawX(x); val ry = toRawY(y)
        currentTrackingId++
        // Use semicolons to batch all sendevents into one shell command for speed
        val cmd = "sendevent $devicePath 3 47 1;" +        // ABS_MT_SLOT 1 (avoid slot 0 used by real finger)
                  "sendevent $devicePath 3 57 $currentTrackingId;" + // ABS_MT_TRACKING_ID
                  "sendevent $devicePath 3 53 $rx;" +      // ABS_MT_POSITION_X
                  "sendevent $devicePath 3 54 $ry;" +      // ABS_MT_POSITION_Y
                  "sendevent $devicePath 3 48 5;" +        // ABS_MT_TOUCH_MAJOR
                  "sendevent $devicePath 3 58 50;" +       // ABS_MT_PRESSURE
                  "sendevent $devicePath 0 0 0"            // SYN_REPORT
        isDown = true
        injectCount++
        log("DOWN rx=$rx ry=$ry")
        return shellExec(cmd)
    }

    fun touchMove(pointerId: Int, x: Float, y: Float): Boolean {
        if (!isDown) return false
        if (mode == "SHELL_SWIPE") {
            // For swipe fallback, issue a quick swipe from current to new position
            shellExec("input swipe ${x.toInt()} ${y.toInt()} ${x.toInt()} ${y.toInt()} 30 &")
            injectCount++
            return true
        }
        if (mode != "SENDEVENT" || devicePath.isEmpty()) return false
        val rx = toRawX(x); val ry = toRawY(y)
        val cmd = "sendevent $devicePath 3 47 1;" +        // ABS_MT_SLOT 1
                  "sendevent $devicePath 3 53 $rx;" +      // ABS_MT_POSITION_X
                  "sendevent $devicePath 3 54 $ry;" +      // ABS_MT_POSITION_Y
                  "sendevent $devicePath 0 0 0"            // SYN_REPORT
        injectCount++
        return shellExec(cmd)
    }

    fun touchUp(pointerId: Int): Boolean {
        if (!isDown) return false
        isDown = false
        if (mode == "SHELL_SWIPE") {
            // Kill any background input swipe processes
            shellExec("kill %1 2>/dev/null; input tap 0 0 &")
            return true
        }
        if (mode != "SENDEVENT" || devicePath.isEmpty()) return false
        val cmd = "sendevent $devicePath 3 47 1;" +        // ABS_MT_SLOT 1
                  "sendevent $devicePath 3 57 4294967295;" + // ABS_MT_TRACKING_ID = -1 (release)
                  "sendevent $devicePath 0 0 0"            // SYN_REPORT
        log("UP")
        return shellExec(cmd)
    }

    // ─── Convenience ─────────────────────────────────────────────────────────

    fun tap(x: Float, y: Float): Boolean {
        return shellExec("input tap ${x.toInt()} ${y.toInt()}")
    }

    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Int = 50): Boolean {
        return shellExec("input swipe ${x1.toInt()} ${y1.toInt()} ${x2.toInt()} ${y2.toInt()} $durationMs")
    }
}
