package dev.aimmy.android

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.util.TypedValue
import android.view.Choreographer
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class FloatingOverlayService : Service(), Choreographer.FrameCallback {

    private lateinit var windowManager: WindowManager
    private lateinit var prefs: SharedPreferences

    // UI Elements
    private lateinit var canvasView: DrawingView
    private lateinit var menuBubble: FrameLayout
    private lateinit var aimTrigger: FrameLayout
    private lateinit var fireTargetMarker: FrameLayout
    private var controlPanel: ScrollView? = null
    private lateinit var tempTextView: TextView

    // Layout Params
    private lateinit var menuParams: WindowManager.LayoutParams
    private lateinit var fireTargetParams: WindowManager.LayoutParams
    private lateinit var aimParams: WindowManager.LayoutParams
    private var isAimTriggerVisible = false
    private var isOverlayLocked = false

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_BATTERY_CHANGED) {
                val temp = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10f
                OverlayState.currentTemperature = temp
                if (::tempTextView.isInitialized) {
                    tempTextView.text = "${temp.toInt()}°C"
                    if (temp >= 40f) tempTextView.setTextColor(Color.RED)
                    else tempTextView.setTextColor(Color.WHITE)
                }
            }
        }
    }

    companion object {
        private const val PURPLE = 0xFFB24BF3.toInt()
        private const val PURPLE_DIM = 0x44B24BF3.toInt()
        private const val DARK_BG = 0xF016161A.toInt()
        private const val CARD_BG = 0xFF1F1F28.toInt()
        private const val GRAY_BTN = 0xFF2A2A35.toInt()
        private const val MENU_SIZE = 100
        private const val TRIGGER_SIZE = 130
        private const val FIRE_MARKER_SIZE = 50
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        prefs = getSharedPreferences("AimmyPrefs", Context.MODE_PRIVATE)

        val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

        // 1. Canvas (pass-through drawing layer)
        canvasView = DrawingView(this)
        val canvasParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            layoutFlag,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        windowManager.addView(canvasView, canvasParams)

        // 2. Menu Bubble
        isOverlayLocked = prefs.getBoolean("overlayLocked", false)
        menuParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutFlag,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 20; y = 200
        }
        menuBubble = createMenuBubble()
        windowManager.addView(menuBubble, menuParams)

        // 3. Aim Trigger
        aimParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutFlag,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = prefs.getInt("triggerX", 200)
            y = prefs.getInt("triggerY", 500)
        }
        aimTrigger = createAimTrigger()
        isAimTriggerVisible = prefs.getBoolean("triggerVisible", false)
        aimTrigger.visibility = if (isAimTriggerVisible) View.VISIBLE else View.GONE
        windowManager.addView(aimTrigger, aimParams)

        // 4. Fire Target Marker
        fireTargetParams = WindowManager.LayoutParams(
            FIRE_MARKER_SIZE, FIRE_MARKER_SIZE,
            layoutFlag,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = prefs.getFloat("fireTargetX", 0f).toInt() - FIRE_MARKER_SIZE / 2
            y = prefs.getFloat("fireTargetY", 0f).toInt() - FIRE_MARKER_SIZE / 2
        }
        fireTargetMarker = createFireMarker()
        fireTargetMarker.visibility = if (isAimTriggerVisible) View.VISIBLE else View.GONE
        windowManager.addView(fireTargetMarker, fireTargetParams)

        // Start render loop & battery monitor
        Choreographer.getInstance().postFrameCallback(this)
        registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    }

    // ─── Styled Drawables ─────────────────────────────────────────────────────

    private fun makeCircleBg(fillColor: Int, strokeColor: Int = Color.WHITE, strokeWidth: Int = 3): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(fillColor)
            setStroke(strokeWidth, strokeColor)
        }
    }

    private fun makeRoundRectBg(fillColor: Int, radius: Float = 28f, strokeColor: Int = PURPLE, strokeWidth: Int = 2): GradientDrawable {
        return GradientDrawable().apply {
            setColor(fillColor)
            cornerRadius = radius
            setStroke(strokeWidth, strokeColor)
        }
    }

    private fun makePanelButton(text: String, iconResId: Int, bgColor: Int, onClick: () -> Unit): LinearLayout {
        val dp = { v: Int -> TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt() }

        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
            background = makeRoundRectBg(bgColor, 14f, Color.TRANSPARENT, 0)

            val icon = ImageView(this@FloatingOverlayService).apply {
                setImageResource(iconResId)
                setColorFilter(Color.WHITE)
                layoutParams = LinearLayout.LayoutParams(dp(20), dp(20)).apply {
                    marginEnd = dp(12)
                }
            }
            addView(icon)

            val label = TextView(this@FloatingOverlayService).apply {
                this.text = text
                setTextColor(Color.WHITE)
                textSize = 13f
                typeface = Typeface.DEFAULT_BOLD
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            addView(label)

            setOnClickListener { onClick() }
        }
    }

    // ─── Menu Bubble ──────────────────────────────────────────────────────────

    private fun createMenuBubble(): FrameLayout {
        val container = FrameLayout(this)

        val bg = ImageView(this).apply {
            background = makeCircleBg(DARK_BG, PURPLE, 3)
        }

        val icon = ImageView(this).apply {
            setImageResource(R.drawable.ic_menu_gear)
            setColorFilter(Color.WHITE)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(28, 28, 28, 28)
        }

        tempTextView = TextView(this).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f)
            text = ""
            gravity = Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM
            setPadding(0, 0, 0, 8)
            setShadowLayer(3f, 0f, 0f, Color.BLACK)
        }

        container.addView(bg, FrameLayout.LayoutParams(MENU_SIZE, MENU_SIZE))
        container.addView(icon, FrameLayout.LayoutParams(MENU_SIZE, MENU_SIZE))
        container.addView(tempTextView, FrameLayout.LayoutParams(MENU_SIZE, MENU_SIZE))

        var initX = 0; var initY = 0
        var initTouchX = 0f; var initTouchY = 0f
        var isClick = false
        var lastProcessTime = 0L

        container.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initX = menuParams.x; initY = menuParams.y
                    initTouchX = event.rawX; initTouchY = event.rawY
                    isClick = true; true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (isOverlayLocked) return@setOnTouchListener true
                    val dx = event.rawX - initTouchX
                    val dy = event.rawY - initTouchY
                    if (kotlin.math.abs(dx) > 10 || kotlin.math.abs(dy) > 10) isClick = false
                    val now = System.currentTimeMillis()
                    if (now - lastProcessTime > 16) {
                        lastProcessTime = now
                        menuParams.x = initX + dx.toInt()
                        menuParams.y = initY + dy.toInt()
                        windowManager.updateViewLayout(container, menuParams)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (isClick) showControlPanel()
                    true
                }
                else -> false
            }
        }
        return container
    }

    // ─── Control Panel (Premium UI) ───────────────────────────────────────────

    private fun showControlPanel() {
        if (controlPanel != null) return

        val dp = { v: Int -> TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt() }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(20), dp(24), dp(20))
            background = makeRoundRectBg(DARK_BG, 28f, PURPLE, 2)

            // ─── Header ───
            val header = LinearLayout(this@FloatingOverlayService).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, 0, 0, dp(16))
            }
            val headerIcon = ImageView(this@FloatingOverlayService).apply {
                setImageResource(R.drawable.ic_notification)
                setColorFilter(Color.parseColor("#B24BF3"))
                layoutParams = LinearLayout.LayoutParams(dp(28), dp(28)).apply { marginEnd = dp(12) }
            }
            header.addView(headerIcon)
            val headerTitle = TextView(this@FloatingOverlayService).apply {
                text = "AIMMY"
                setTextColor(Color.WHITE)
                textSize = 20f
                typeface = Typeface.DEFAULT_BOLD
                letterSpacing = 0.15f
            }
            header.addView(headerTitle)
            addView(header)

            // Divider
            addView(View(this@FloatingOverlayService).apply {
                setBackgroundColor(Color.parseColor("#33B24BF3"))
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 2).apply {
                    bottomMargin = dp(14)
                }
            })

            // ─── Buttons ───
            val toggleAimBtn = makePanelButton(
                if (isAimTriggerVisible) "Hide Aim Trigger" else "Show Aim Trigger",
                R.drawable.ic_crosshair, GRAY_BTN
            ) {
                isAimTriggerVisible = !isAimTriggerVisible
                prefs.edit().putBoolean("triggerVisible", isAimTriggerVisible).apply()
                aimTrigger.visibility = if (isAimTriggerVisible) View.VISIBLE else View.GONE
                fireTargetMarker.visibility = if (isAimTriggerVisible) View.VISIBLE else View.GONE
                dismissControlPanel()
            }
            addView(toggleAimBtn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) })

            val lockBtn = makePanelButton(
                if (isOverlayLocked) "🔓 Unlock UI (Edit)" else "🔒 Lock UI (Combat)",
                R.drawable.ic_menu_gear, Color.parseColor("#E65100")
            ) {
                isOverlayLocked = !isOverlayLocked
                prefs.edit().putBoolean("overlayLocked", isOverlayLocked).apply()
                dismissControlPanel()
            }
            addView(lockBtn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) })

            val mapFireBtn = makePanelButton(
                "Map Fire Button", R.drawable.ic_fire, PURPLE
            ) {
                dismissControlPanel()
                enterPlacementMode()
            }
            addView(mapFireBtn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) })

            val openAppBtn = makePanelButton(
                "Open Aimmy App", R.drawable.ic_notification, GRAY_BTN
            ) {
                val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
                if (launchIntent != null) {
                    launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(launchIntent)
                }
                dismissControlPanel()
            }
            addView(openAppBtn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) })

            // Divider
            addView(View(this@FloatingOverlayService).apply {
                setBackgroundColor(Color.parseColor("#22FFFFFF"))
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1).apply {
                    topMargin = dp(4); bottomMargin = dp(10)
                }
            })

            val closePanelBtn = makePanelButton(
                "Close Panel", R.drawable.ic_menu_gear, GRAY_BTN
            ) {
                dismissControlPanel()
            }
            addView(closePanelBtn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) })

            val exitBtn = makePanelButton(
                "Exit Overlay", R.drawable.ic_notification, Color.parseColor("#B71C1C")
            ) {
                stopSelf()
            }
            addView(exitBtn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }

        controlPanel = ScrollView(this).apply {
            addView(content)
        }

        val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

        val panelParams = WindowManager.LayoutParams(
            dp(280),
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutFlag,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
        }

        menuBubble.visibility = View.GONE
        windowManager.addView(controlPanel, panelParams)
    }

    private fun dismissControlPanel() {
        if (controlPanel?.isAttachedToWindow == true) {
            windowManager.removeView(controlPanel)
        }
        controlPanel = null
        menuBubble.visibility = View.VISIBLE
    }

    // ─── Fire Target Placement ────────────────────────────────────────────────

    private fun createFireMarker(): FrameLayout {
        return FrameLayout(this).apply {
            val bg = ImageView(this@FloatingOverlayService).apply {
                background = makeCircleBg(Color.parseColor("#CCFF1744"), Color.WHITE, 3)
            }
            val icon = ImageView(this@FloatingOverlayService).apply {
                setImageResource(R.drawable.ic_fire)
                setColorFilter(Color.WHITE)
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                setPadding(10, 10, 10, 10)
            }
            addView(bg, FrameLayout.LayoutParams(FIRE_MARKER_SIZE, FIRE_MARKER_SIZE))
            addView(icon, FrameLayout.LayoutParams(FIRE_MARKER_SIZE, FIRE_MARKER_SIZE))
        }
    }

    private fun enterPlacementMode() {
        val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

        val placementParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            layoutFlag,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        val placementView = FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#99000000"))

            // Pulsing crosshair indicator
            val instructions = TextView(this@FloatingOverlayService).apply {
                text = "🎯  Tap on your in-game Fire button"
                setTextColor(Color.WHITE)
                textSize = 20f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                setShadowLayer(8f, 0f, 0f, Color.BLACK)
            }
            addView(instructions, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            ))
        }

        placementView.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_DOWN) {
                val fireX = event.rawX
                val fireY = event.rawY
                prefs.edit().putFloat("fireTargetX", fireX).putFloat("fireTargetY", fireY).apply()

                // Update fire marker position
                fireTargetParams.x = fireX.toInt() - FIRE_MARKER_SIZE / 2
                fireTargetParams.y = fireY.toInt() - FIRE_MARKER_SIZE / 2
                windowManager.updateViewLayout(fireTargetMarker, fireTargetParams)
                fireTargetMarker.visibility = View.VISIBLE

                windowManager.removeView(placementView)
                menuBubble.visibility = View.VISIBLE
                true
            } else false
        }

        windowManager.addView(placementView, placementParams)
    }

    // ─── Aim Trigger Button ───────────────────────────────────────────────────

    private fun createAimTrigger(): FrameLayout {
        val container = FrameLayout(this)

        val bg = ImageView(this).apply {
            background = makeCircleBg(DARK_BG, PURPLE, 3)
        }

        val icon = ImageView(this).apply {
            setImageResource(R.drawable.ic_crosshair)
            setColorFilter(Color.WHITE)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(30, 30, 30, 30)
        }

        val label = TextView(this).apply {
            setTextColor(Color.WHITE)
            text = "AIM"
            textSize = 10f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM
            setPadding(0, 0, 0, 12)
            setShadowLayer(5f, 0f, 0f, Color.BLACK)
        }

        container.addView(bg, FrameLayout.LayoutParams(TRIGGER_SIZE, TRIGGER_SIZE))
        container.addView(icon, FrameLayout.LayoutParams(TRIGGER_SIZE, TRIGGER_SIZE))
        container.addView(label, FrameLayout.LayoutParams(TRIGGER_SIZE, TRIGGER_SIZE))

        var initX = 0; var initY = 0
        var initTouchX = 0f; var initTouchY = 0f
        var isClick = false
        var lastProcessTime = 0L
        var initFireX = 0f
        var initFireY = 0f

        container.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initX = aimParams.x; initY = aimParams.y
                    initTouchX = event.rawX; initTouchY = event.rawY
                    isClick = true

                    bg.background = makeCircleBg(PURPLE, Color.WHITE, 3)

                    initFireX = prefs.getFloat("fireTargetX", event.rawX)
                    initFireY = prefs.getFloat("fireTargetY", event.rawY)
                    ShizukuTouchInjector.touchDown(0, initFireX, initFireY)

                    OverlayState.isAimbotEnabled = true
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - initTouchX
                    val dy = event.rawY - initTouchY

                    if (isOverlayLocked) {
                        ShizukuTouchInjector.touchMove(0, initFireX + dx, initFireY + dy)
                    } else {
                        if (kotlin.math.abs(dx) > 10 || kotlin.math.abs(dy) > 10) {
                            if (isClick) ShizukuTouchInjector.touchUp(0)
                            isClick = false
                        }
                        val now = System.currentTimeMillis()
                        if (now - lastProcessTime > 16) {
                            lastProcessTime = now
                            aimParams.x = initX + dx.toInt()
                            aimParams.y = initY + dy.toInt()
                            windowManager.updateViewLayout(container, aimParams)
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    // Release BOTH pointers:
                    // Pointer 1 = aim movement (held by ScreenCaptureService)
                    // Pointer 0 = fire button
                    ShizukuTouchInjector.touchUp(1)
                    ShizukuTouchInjector.touchUp(0)
                    bg.background = makeCircleBg(DARK_BG, PURPLE, 3)
                    OverlayState.isAimbotEnabled = false
                    OverlayState.clear()
                    true
                }
                else -> false
            }
        }
        return container
    }

    // ─── Render Loop ──────────────────────────────────────────────────────────

    override fun doFrame(frameTimeNanos: Long) {
        canvasView.invalidate()
        Choreographer.getInstance().postFrameCallback(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        Choreographer.getInstance().removeFrameCallback(this)
        try { unregisterReceiver(batteryReceiver) } catch (_: Exception) {}
        if (::menuBubble.isInitialized) windowManager.removeView(menuBubble)
        if (::aimTrigger.isInitialized) windowManager.removeView(aimTrigger)
        if (::fireTargetMarker.isInitialized) windowManager.removeView(fireTargetMarker)
        if (controlPanel != null) windowManager.removeView(controlPanel)
        if (::canvasView.isInitialized) windowManager.removeView(canvasView)
        OverlayState.clear()
    }

    // ─── Drawing View ─────────────────────────────────────────────────────────

    inner class DrawingView(context: Context) : View(context) {
        private val fovPaint = Paint().apply {
            color = Color.parseColor("#40FFFFFF")
            style = Paint.Style.STROKE
            strokeWidth = 2f
            pathEffect = android.graphics.DashPathEffect(floatArrayOf(10f, 10f), 0f)
            isAntiAlias = true
        }
        private val boxPaint = Paint().apply {
            color = Color.parseColor("#4400BFFF")
            style = Paint.Style.STROKE
            strokeWidth = 4f
            isAntiAlias = true
        }
        private val activeBoxPaint = Paint().apply {
            color = Color.parseColor("#00BFFF")
            style = Paint.Style.STROKE
            strokeWidth = 6f
            isAntiAlias = true
        }
        private val textPaint = Paint().apply {
            color = Color.WHITE
            textSize = 30f
            isAntiAlias = true
            setShadowLayer(5f, 0f, 0f, Color.BLACK)
        }

        init {
            setWillNotDraw(false)
            setBackgroundColor(Color.TRANSPARENT)
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)

            // Always draw FOV circle
            val fovRadius = prefs.getFloat("fov", 150f)
            canvas.drawCircle(width / 2f, height / 2f, fovRadius, fovPaint)

            if (!OverlayState.isAimbotEnabled) return

            val centerX = width / 2f
            val centerY = height / 2f

            val allDetections = OverlayState.detections
            val target = OverlayState.activeTarget

            if (allDetections.isEmpty()) {
                canvas.drawText("Scanning...", centerX - 70f, 100f, textPaint)
            }

            for (detection in allDetections) {
                val isActive = detection == target
                val paint = if (isActive) activeBoxPaint else boxPaint

                canvas.drawRect(detection.rect, paint)
                val confText = "${(detection.confidence * 100).toInt()}%"
                canvas.drawText(confText, detection.rect.left, detection.rect.top - 10, textPaint)

                if (isActive) {
                    canvas.drawLine(centerX, 0f, detection.rect.centerX(), detection.rect.centerY(), paint)
                }
            }
        }
    }
}
