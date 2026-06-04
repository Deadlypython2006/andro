package dev.aimmy.android

import android.content.Context
import android.hardware.input.InputManager
import android.os.SystemClock
import android.view.InputDevice
import android.view.InputEvent
import android.view.MotionEvent
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuBinderWrapper
import java.lang.reflect.Method

object ShizukuTouchInjector {

    private var inputManager: Any? = null
    private var injectInputEventMethod: Method? = null

    fun initialize() {
        if (!Shizuku.pingBinder()) return
        
        try {
            val binder = ShizukuBinderWrapper(Shizuku.getSystemService(Context.INPUT_SERVICE))
            val iInputManagerClass = Class.forName("android.hardware.input.IInputManager\$Stub")
            inputManager = iInputManagerClass.getDeclaredMethod("asInterface", android.os.IBinder::class.java)
                .invoke(null, binder)

            val iInputManager = Class.forName("android.hardware.input.IInputManager")
            val methods = iInputManager.methods
            for (method in methods) {
                if (method.name == "injectInputEvent" && method.parameterTypes.size == 2) {
                    injectInputEventMethod = method
                    break
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun injectEvent(event: InputEvent) {
        if (inputManager == null || injectInputEventMethod == null) {
            initialize()
        }
        
        try {
            // mode 0 (INJECT_INPUT_EVENT_MODE_ASYNC) is required for smooth aimbots
            injectInputEventMethod?.invoke(inputManager, event, 0)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun swipe(startX: Float, startY: Float, endX: Float, endY: Float, steps: Int) {
        val downTime = SystemClock.uptimeMillis()
        var eventTime = downTime

        // ACTION_DOWN
        injectEvent(getMotionEvent(downTime, eventTime, MotionEvent.ACTION_DOWN, startX, startY))

        val stepX = (endX - startX) / steps
        val stepY = (endY - startY) / steps

        // ACTION_MOVE
        for (i in 1..steps) {
            val x = startX + stepX * i
            val y = startY + stepY * i
            // Advance event time slightly to simulate physical finger movement delay
            eventTime += 1 // 1ms delay per step is extremely fast but looks continuous to the OS
            injectEvent(getMotionEvent(downTime, eventTime, MotionEvent.ACTION_MOVE, x, y))
        }

        // ACTION_UP
        eventTime += 1
        injectEvent(getMotionEvent(downTime, eventTime, MotionEvent.ACTION_UP, endX, endY))
    }

    fun tap(x: Float, y: Float) {
        val downTime = SystemClock.uptimeMillis()
        injectEvent(getMotionEvent(downTime, downTime, MotionEvent.ACTION_DOWN, x, y))
        injectEvent(getMotionEvent(downTime, downTime + 10, MotionEvent.ACTION_UP, x, y))
    }

    private fun getMotionEvent(
        downTime: Long,
        eventTime: Long,
        action: Int,
        x: Float,
        y: Float
    ): MotionEvent {
        val pointerProperties = arrayOfNulls<MotionEvent.PointerProperties>(1)
        val pp = MotionEvent.PointerProperties()
        pp.id = 0
        pp.toolType = MotionEvent.TOOL_TYPE_FINGER
        pointerProperties[0] = pp

        val pointerCoords = arrayOfNulls<MotionEvent.PointerCoords>(1)
        val pc = MotionEvent.PointerCoords()
        pc.x = x
        pc.y = y
        pc.pressure = 1f
        pc.size = 1f
        pointerCoords[0] = pc

        return MotionEvent.obtain(
            downTime,
            eventTime,
            action,
            1,
            pointerProperties,
            pointerCoords,
            0,
            0,
            1f,
            1f,
            0,
            0,
            InputDevice.SOURCE_TOUCHSCREEN,
            0
        )
    }
}
