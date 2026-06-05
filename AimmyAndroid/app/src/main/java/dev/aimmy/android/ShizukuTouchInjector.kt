package dev.aimmy.android

import android.content.Context
import android.hardware.input.InputManager
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.MotionEvent
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.io.OutputStreamWriter
import java.util.Date

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

    private var inputManager: InputManager? = null
    private var shellProcess: Process? = null
    private var shellWriter: BufferedWriter? = null
    private var logFile: File? = null
    
    private var useBinder = false

    private fun logToFile(msg: String) {
        Log.e(TAG, msg)
        try {
            logFile?.let {
                val fw = FileWriter(it, true)
                fw.write("${Date()}: $msg\n")
                fw.close()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun initialize(context: Context? = null) {
        if (context != null) {
            logFile = File(context.getExternalFilesDir(null), "aimmy_shizuku_log.txt")
            logToFile("=== Initializing Shizuku Touch Injector ===")
        }
        
        val shellOk = initShell()
        
        try {
            inputManager = InputManager::class.java.getDeclaredMethod("getInstance").invoke(null) as InputManager
            val testSuccess = testBinderInjection()
            if (testSuccess) {
                useBinder = true
                mode = "BINDER"
                isReady = true
                logToFile("Binder mode successfully initialized")
            } else {
                useBinder = false
                mode = if (shellOk) "SHELL" else "FAILED"
                isReady = shellOk
                logToFile("Binder test failed, falling back to shell")
            }
        } catch (e: Exception) {
            lastError = "Binder init failed: ${e.message}"
            logToFile(lastError)
            useBinder = false
            mode = if (shellOk) "SHELL" else "FAILED"
            isReady = shellOk
        }
    }

    private fun testBinderInjection(): Boolean {
        return try {
            val now = SystemClock.uptimeMillis()
            val event = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, 0f, 0f, 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            
            val injectMethod = InputManager::class.java.getDeclaredMethod(
                "injectInputEvent",
                MotionEvent::class.java,
                Int::class.javaPrimitiveType
            )
            injectMethod.isAccessible = true
            
            val binder = SystemServiceHelper.getSystemService(Context.INPUT_SERVICE)
            val wrappedBinder = ShizukuBinderWrapper(binder)
            val imClass = Class.forName("android.hardware.input.IInputManager\$Stub")
            val asInterfaceMethod = imClass.getDeclaredMethod("asInterface", android.os.IBinder::class.java)
            val iInputManager = asInterfaceMethod.invoke(null, wrappedBinder)
            
            val injectMethodRemote = iInputManager.javaClass.getMethod("injectInputEvent", android.view.InputEvent::class.java, Int::class.javaPrimitiveType)
            val success = injectMethodRemote.invoke(iInputManager, event, 0) as Boolean
            
            val upEvent = MotionEvent.obtain(now, now + 10, MotionEvent.ACTION_UP, 0f, 0f, 0)
            upEvent.source = InputDevice.SOURCE_TOUCHSCREEN
            injectMethodRemote.invoke(iInputManager, upEvent, 0)
            
            success
        } catch (e: Exception) {
            lastError = e.message ?: "Binder test crash"
            false
        }
    }

    private fun initShell(): Boolean {
        try {
            if (!Shizuku.pingBinder()) return false
            val newProcessMethod = Shizuku::class.java.getDeclaredMethod("newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java)
            newProcessMethod.isAccessible = true
            shellProcess = newProcessMethod.invoke(null, arrayOf("sh"), null, null) as Process
            shellWriter = BufferedWriter(OutputStreamWriter(shellProcess!!.outputStream))
            
            Thread {
                try {
                    val reader = java.io.BufferedReader(java.io.InputStreamReader(shellProcess!!.errorStream))
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        logToFile("SHELL STDERR: $line")
                    }
                } catch (e: Exception) {}
            }.start()
            
            shellReady = true
            return true
        } catch (e: Exception) {
            shellReady = false
            return false
        }
    }

    private fun dispatchTouchEvent(action: Int, pointerId: Int, x: Float, y: Float): Boolean {
        if (!useBinder) return false
        return try {
            val now = SystemClock.uptimeMillis()
            val safeId = pointerId.coerceIn(0, 15)
            
            val properties = arrayOfNulls<MotionEvent.PointerProperties>(1)
            properties[0] = MotionEvent.PointerProperties().apply {
                id = safeId
                toolType = MotionEvent.TOOL_TYPE_FINGER
            }
            
            val coords = arrayOfNulls<MotionEvent.PointerCoords>(1)
            coords[0] = MotionEvent.PointerCoords().apply {
                this.x = x
                this.y = y
                pressure = 1.0f
                size = 1.0f
            }
            
            val event = MotionEvent.obtain(
                now, now, action, 1, properties, coords, 0, 0, 1.0f, 1.0f, 0, 0,
                InputDevice.SOURCE_TOUCHSCREEN, 0
            )
            
            val binder = SystemServiceHelper.getSystemService(Context.INPUT_SERVICE)
            val wrappedBinder = ShizukuBinderWrapper(binder)
            val imClass = Class.forName("android.hardware.input.IInputManager\$Stub")
            val asInterfaceMethod = imClass.getDeclaredMethod("asInterface", android.os.IBinder::class.java)
            val iInputManager = asInterfaceMethod.invoke(null, wrappedBinder)
            
            val injectMethodRemote = iInputManager.javaClass.getMethod("injectInputEvent", android.view.InputEvent::class.java, Int::class.javaPrimitiveType)
            val success = injectMethodRemote.invoke(iInputManager, event, 0) as Boolean
            
            if (success) injectCount++ else rejectCount++
            event.recycle()
            success
        } catch (e: Exception) {
            rejectCount++
            lastError = e.message ?: "Dispatch failed"
            logToFile(lastError)
            false
        }
    }

    fun touchDown(pointerId: Int, x: Float, y: Float): Boolean = dispatchTouchEvent(MotionEvent.ACTION_DOWN, pointerId, x, y)
    fun touchMove(pointerId: Int, x: Float, y: Float): Boolean = dispatchTouchEvent(MotionEvent.ACTION_MOVE, pointerId, x, y)
    fun touchUp(pointerId: Int): Boolean = dispatchTouchEvent(MotionEvent.ACTION_UP, pointerId, 0f, 0f)

    private fun shellExec(cmd: String): Boolean {
        if (!shellReady) return false
        return try {
            shellWriter?.write("$cmd\n")
            shellWriter?.flush()
            true
        } catch (e: Exception) {
            false
        }
    }

    fun tap(x: Float, y: Float): Boolean {
        if (useBinder) {
            touchDown(0, x, y)
            Thread.sleep(20)
            return touchUp(0)
        }
        return shellExec("input tap ${x.toInt()} ${y.toInt()}")
    }

    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Int = 50): Boolean {
        if (useBinder) {
            touchDown(0, x1, y1)
            Thread.sleep(10)
            touchMove(0, x2, y2)
            Thread.sleep(durationMs.toLong())
            return touchUp(0)
        }
        return shellExec("input swipe ${x1.toInt()} ${y1.toInt()} ${x2.toInt()} ${y2.toInt()} $durationMs")
    }
}
