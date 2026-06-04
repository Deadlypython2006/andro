package dev.aimmy.android

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView

class FloatingOverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var overlayView: View
    private var isAimbotActive = false

    companion object {
        private const val COLOR_INACTIVE = 0xFF16161A.toInt()  // AimmyDark
        private const val COLOR_ACTIVE = 0xFFB24BF3.toInt()    // AimmyPurple
        private const val BUTTON_SIZE = 120
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        overlayView = android.widget.FrameLayout(this).apply {
            val btn = ImageView(this@FloatingOverlayService).apply {
                setBackgroundColor(COLOR_INACTIVE)
                alpha = 0.85f
            }
            addView(btn, android.widget.FrameLayout.LayoutParams(BUTTON_SIZE, BUTTON_SIZE))
        }

        val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutFlag,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0; y = 200
        }

        windowManager.addView(overlayView, params)

        // Draggable + clickable touch handler
        var initX = 0; var initY = 0
        var initTouchX = 0f; var initTouchY = 0f
        var isClick = false

        overlayView.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initX = params.x; initY = params.y
                    initTouchX = event.rawX; initTouchY = event.rawY
                    isClick = true; true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - initTouchX
                    val dy = event.rawY - initTouchY
                    if (kotlin.math.abs(dx) > 8 || kotlin.math.abs(dy) > 8) isClick = false
                    params.x = initX + dx.toInt()
                    params.y = initY + dy.toInt()
                    windowManager.updateViewLayout(overlayView, params)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (isClick) toggleAimbot()
                    true
                }
                else -> false
            }
        }
    }

    private fun toggleAimbot() {
        isAimbotActive = !isAimbotActive
        val btn = (overlayView as android.widget.FrameLayout).getChildAt(0) as ImageView
        if (isAimbotActive) {
            btn.setBackgroundColor(COLOR_ACTIVE)
            sendBroadcast(Intent("DEV_AIMMY_START"))
        } else {
            btn.setBackgroundColor(COLOR_INACTIVE)
            sendBroadcast(Intent("DEV_AIMMY_STOP"))
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::overlayView.isInitialized) {
            windowManager.removeView(overlayView)
        }
    }
}
