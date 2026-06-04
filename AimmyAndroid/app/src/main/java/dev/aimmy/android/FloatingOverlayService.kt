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
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

class FloatingOverlayService : Service(), Choreographer.FrameCallback {

    private lateinit var windowManager: WindowManager
    private lateinit var prefs: SharedPreferences

    // UI Elements
    private lateinit var canvasView: DrawingView
    private lateinit var menuBubble: FrameLayout
    private lateinit var aimTrigger: FrameLayout
    private lateinit var fireTargetMarker: ImageView
    private var controlPanel: LinearLayout? = null
    private lateinit var tempTextView: TextView

    // Layout Params
    private lateinit var menuParams: WindowManager.LayoutParams
    private lateinit var aimParams: WindowManager.LayoutParams
    private lateinit var fireTargetParams: WindowManager.LayoutParams
    private var isAimTriggerVisible = false

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
        private const val COLOR_INACTIVE = 0xAA16161A.toInt()  // AimmyDark translucent
        private const val COLOR_ACTIVE = 0xCCB24BF3.toInt()    // AimmyPurple translucent
        private const val MENU_SIZE = 120
        private const val TRIGGER_SIZE = 160
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        prefs = getSharedPreferences("AimmyPrefs", Context.MODE_PRIVATE)

        val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

        // 1. Setup Canvas View (Pass-through drawing layer)
        canvasView = DrawingView(this)
        val canvasParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            layoutFlag,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        )
        windowManager.addView(canvasView, canvasParams)

        // 2. Setup Menu Bubble
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

        // 3. Setup Aim Trigger
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

        // 4. Setup Fire Target Marker (Red dot)
        fireTargetParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutFlag,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            // Offset by half its size (30 / 2 = 15) to perfectly center on the tap coordinate
            x = prefs.getFloat("fireTargetX", 0f).toInt() - 15
            y = prefs.getFloat("fireTargetY", 0f).toInt() - 15
        }
        fireTargetMarker = ImageView(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.RED)
                setStroke(2, Color.WHITE)
            }
            layoutParams = WindowManager.LayoutParams(30, 30) // Tiny 30x30 dot
        }
        fireTargetMarker.visibility = if (isAimTriggerVisible) View.VISIBLE else View.GONE
        windowManager.addView(fireTargetMarker, fireTargetParams)

        // Start loops
        Choreographer.getInstance().postFrameCallback(this)
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        registerReceiver(batteryReceiver, filter)
    }

    private fun createCircleDrawable(color: Int): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
            setStroke(3, Color.WHITE)
        }
    }

    private fun createMenuBubble(): FrameLayout {
        val container = FrameLayout(this)
        val bg = ImageView(this).apply {
            background = createCircleDrawable(COLOR_INACTIVE)
        }
        
        tempTextView = TextView(this).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            text = "Menu"
            gravity = Gravity.CENTER
            setShadowLayer(3f, 0f, 0f, Color.BLACK)
        }

        container.addView(bg, FrameLayout.LayoutParams(MENU_SIZE, MENU_SIZE))
        container.addView(tempTextView, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

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
                    val dx = event.rawX - initTouchX
                    val dy = event.rawY - initTouchY
                    if (kotlin.math.abs(dx) > 10 || kotlin.math.abs(dy) > 10) isClick = false
                    
                    // Throttle updates to prevent lag
                    val now = System.currentTimeMillis()
                    if (now - lastProcessTime > 16) { // ~60fps throttle
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

    private fun showControlPanel() {
        if (controlPanel != null) return

        controlPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#E616161A"))
            setPadding(40, 40, 40, 40)

            // Dynamic rounded rectangle for panel
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#E616161A"))
                cornerRadius = 20f
                setStroke(2, Color.parseColor("#B24BF3"))
            }

            val title = TextView(this@FloatingOverlayService).apply {
                text = "Control Panel"
                setTextColor(Color.WHITE)
                textSize = 18f
                gravity = Gravity.CENTER
                setPadding(0, 0, 0, 30)
            }
            addView(title)

            val toggleAimBtn = Button(this@FloatingOverlayService).apply {
                text = if (isAimTriggerVisible) "Hide Aim Trigger" else "Show Aim Trigger"
                setBackgroundColor(Color.parseColor("#444444"))
                setTextColor(Color.WHITE)
                setOnClickListener {
                    isAimTriggerVisible = !isAimTriggerVisible
                    prefs.edit().putBoolean("triggerVisible", isAimTriggerVisible).apply()
                    aimTrigger.visibility = if (isAimTriggerVisible) View.VISIBLE else View.GONE
                    fireTargetMarker.visibility = if (isAimTriggerVisible) View.VISIBLE else View.GONE
                    text = if (isAimTriggerVisible) "Hide Aim Trigger" else "Show Aim Trigger"
                }
            }
            addView(toggleAimBtn)

            val placeAimBtn = Button(this@FloatingOverlayService).apply {
                text = "Map Fire Button Location"
                setBackgroundColor(Color.parseColor("#B24BF3"))
                setTextColor(Color.WHITE)
                setOnClickListener {
                    if (controlPanel?.isAttachedToWindow == true) {
                        windowManager.removeView(controlPanel)
                    }
                    controlPanel = null
                    enterPlacementMode()
                }
            }
            addView(placeAimBtn)

            val openAppBtn = Button(this@FloatingOverlayService).apply {
                text = "Open Aimmy App"
                setBackgroundColor(Color.parseColor("#444444"))
                setTextColor(Color.WHITE)
                setOnClickListener {
                    val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
                    if (launchIntent != null) {
                        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        startActivity(launchIntent)
                    }
                }
            }
            addView(openAppBtn)

            val closePanelBtn = Button(this@FloatingOverlayService).apply {
                text = "Close Panel"
                setBackgroundColor(COLOR_ACTIVE)
                setTextColor(Color.WHITE)
                setOnClickListener {
                    if (controlPanel?.isAttachedToWindow == true) {
                        windowManager.removeView(controlPanel)
                    }
                    controlPanel = null
                    menuBubble.visibility = View.VISIBLE
                }
            }
            addView(closePanelBtn)

            val exitBtn = Button(this@FloatingOverlayService).apply {
                text = "Exit Aimmy Overlay"
                setBackgroundColor(Color.RED)
                setTextColor(Color.WHITE)
                setOnClickListener {
                    stopSelf()
                }
            }
            addView(exitBtn)
        }

        val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

        val panelParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
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

    private fun enterPlacementMode() {
        val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

        val placementParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            layoutFlag,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )

        val placementView = FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#66000000")) // Dim background
            val instructions = TextView(this@FloatingOverlayService).apply {
                text = "Tap exactly over your Fire button..."
                setTextColor(Color.WHITE)
                textSize = 24f
                setTypeface(null, android.graphics.Typeface.BOLD)
                gravity = Gravity.CENTER
            }
            addView(instructions, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        }

        placementView.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_DOWN) {
                // Record the actual absolute X, Y coordinates of the Fire target
                val fireX = event.rawX
                val fireY = event.rawY
                
                prefs.edit().putFloat("fireTargetX", fireX).putFloat("fireTargetY", fireY).apply()
                
                // Update marker position
                fireTargetParams.x = fireX.toInt() - 15
                fireTargetParams.y = fireY.toInt() - 15
                windowManager.updateViewLayout(fireTargetMarker, fireTargetParams)
                
                windowManager.removeView(placementView)
                menuBubble.visibility = View.VISIBLE
                true
            } else {
                false
            }
        }

        windowManager.addView(placementView, placementParams)
    }

    private fun createAimTrigger(): FrameLayout {
        val container = FrameLayout(this)
        val bg = ImageView(this).apply {
            background = createCircleDrawable(COLOR_INACTIVE)
        }
        
        val label = TextView(this).apply {
            setTextColor(Color.WHITE)
            text = "AIM"
            textSize = 14f
            setTypeface(null, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
            setShadowLayer(5f, 0f, 0f, Color.BLACK)
        }

        container.addView(bg, FrameLayout.LayoutParams(TRIGGER_SIZE, TRIGGER_SIZE))
        container.addView(label, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        var initX = 0; var initY = 0
        var initTouchX = 0f; var initTouchY = 0f
        var isClick = false
        var lastProcessTime = 0L

        container.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initX = aimParams.x; initY = aimParams.y
                    initTouchX = event.rawX; initTouchY = event.rawY
                    isClick = true
                    
                    bg.background = createCircleDrawable(COLOR_ACTIVE)
                    
                    // Inject touch at the mapped Fire Target location!
                    val fireX = prefs.getFloat("fireTargetX", event.rawX)
                    val fireY = prefs.getFloat("fireTargetY", event.rawY)
                    ShizukuTouchInjector.touchDown(0, fireX, fireY)
                    
                    OverlayState.isAimbotEnabled = true
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - initTouchX
                    val dy = event.rawY - initTouchY
                    if (kotlin.math.abs(dx) > 10 || kotlin.math.abs(dy) > 10) {
                        if (isClick) {
                            ShizukuTouchInjector.touchUp(0)
                        }
                        isClick = false
                    }
                    
                    // Throttle updates
                    val now = System.currentTimeMillis()
                    if (now - lastProcessTime > 16) {
                        lastProcessTime = now
                        aimParams.x = initX + dx.toInt()
                        aimParams.y = initY + dy.toInt()
                        windowManager.updateViewLayout(container, aimParams)
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (isClick) {
                        ShizukuTouchInjector.touchUp(0)
                    }
                    bg.background = createCircleDrawable(COLOR_INACTIVE)
                    OverlayState.isAimbotEnabled = false
                    OverlayState.clear()
                    true
                }
                else -> false
            }
        }
        return container
    }

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

    inner class DrawingView(context: Context) : View(context) {
        private val fovPaint = Paint().apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = 3f
            isAntiAlias = true
            alpha = 150
        }
        private val boxPaint = Paint().apply {
            color = Color.parseColor("#4400BFFF") // Dim Light Blue
            style = Paint.Style.STROKE
            strokeWidth = 4f
            isAntiAlias = true
        }
        private val activeBoxPaint = Paint().apply {
            color = Color.parseColor("#00BFFF") // Deep Sky Blue (Light Blue)
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
            // Force bypass Android's view optimization so it actually draws
            setWillNotDraw(false)
            setBackgroundColor(Color.TRANSPARENT)
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            if (!OverlayState.isAimbotEnabled) return

            val centerX = width / 2f
            val centerY = height / 2f

            if (prefs.getBoolean("showFov", false)) {
                val fovRadius = prefs.getFloat("fov", 150f)
                canvas.drawCircle(centerX, centerY, fovRadius, fovPaint)
            }

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
                    // Draw snapline from TOP-CENTER of the screen to the target
                    canvas.drawLine(centerX, 0f, detection.rect.centerX(), detection.rect.centerY(), paint)
                }
            }
        }
    }
}
