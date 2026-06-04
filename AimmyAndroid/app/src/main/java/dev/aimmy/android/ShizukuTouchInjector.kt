package dev.aimmy.android

import android.content.Context
import android.os.SystemClock
import android.view.InputDevice
import android.view.InputEvent
import android.view.MotionEvent
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper
import java.lang.reflect.Method

object ShizukuTouchInjector {

    private var inputManager: Any? = null
    private var injectInputEventMethod: Method? = null

    fun initialize() {
        if (!Shizuku.pingBinder()) return
        
        try {
            val binder = SystemServiceHelper.getSystemService("input")
            if (binder == null) return
            
            val wrappedBinder = ShizukuBinderWrapper(binder)
            val iInputManagerClass = Class.forName("android.hardware.input.IInputManager\$Stub")
            inputManager = iInputManagerClass.getDeclaredMethod("asInterface", android.os.IBinder::class.java)
                .invoke(null, wrappedBinder)

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
            injectInputEventMethod?.invoke(inputManager, event, 0)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun swipe(startX: Float, startY: Float, endX: Float, endY: Float, steps: Int) {
        val downTime = SystemClock.uptimeMillis()
        var eventTime = downTime

        injectEvent(getMotionEvent(downTime, eventTime, MotionEvent.ACTION_DOWN, startX, startY))

        val stepX = (endX - startX) / steps
        val stepY = (endY - startY) / steps

        for (i in 1..steps) {
            val x = startX + stepX * i
            val y = startY + stepY * i
            eventTime += 1
            injectEvent(getMotionEvent(downTime, eventTime, MotionEvent.ACTION_MOVE, x, y))
        }

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
        val pointerProperties = arrayOf(
            MotionEvent.PointerProperties().apply {
                id = 0
                toolType = MotionEvent.TOOL_TYPE_FINGER
            }
        )

        val pointerCoords = arrayOf(
            MotionEvent.PointerCoords().apply {
                this.x = x
                this.y = y
                pressure = 1f
                size = 1f
            }
        )

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
