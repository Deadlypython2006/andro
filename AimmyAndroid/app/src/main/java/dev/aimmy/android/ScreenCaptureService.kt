package dev.aimmy.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.RectF
import android.hardware.display.DisplayManager
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.core.app.NotificationCompat

class ScreenCaptureService : Service() {

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: android.hardware.display.VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var yoloDetector: YoloDetector? = null
    private lateinit var prefs: SharedPreferences

    private var screenWidth = 0
    private var screenHeight = 0
    private var screenDensity = 0

    @Volatile private var isProcessing = false
    private var processingThread: HandlerThread? = null
    private var processingHandler: Handler? = null
    private var lastProcessTime = 0L

    // Pre-allocated bitmap for pixel extraction — avoids GC per frame
    private var captureBitmap: Bitmap? = null
    private var frameBitmap: Bitmap? = null

    // Persistent aim pointer — prevents jittery down/up cycles every frame
    private var isAimPointerDown = false
    private var currentAimX = 0f
    private var currentAimY = 0f
    private var aimPointerStartX = 0f
    private var aimPointerStartY = 0f

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        prefs = getSharedPreferences("AimmyPrefs", Context.MODE_PRIVATE)

        processingThread = HandlerThread("AimmyInference", android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY)
        processingThread?.start()
        processingHandler = Handler(processingThread!!.looper)

        // Initialize detector and Shizuku on the processing thread
        processingHandler?.post {
            yoloDetector = YoloDetector(this)
            try { ShizukuTouchInjector.initialize() } catch (_: Exception) {}
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1, createNotification(), android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(1, createNotification())
        }

        if (intent?.action == "START_PROJECTION") {
            val resultCode = intent.getIntExtra("RESULT_CODE", -1)
            @Suppress("DEPRECATION")
            val resultData = intent.getParcelableExtra<Intent>("DATA")

            if (resultCode == android.app.Activity.RESULT_OK && resultData != null) {
                setupMediaProjection(resultCode, resultData)
            } else {
                stopSelf()
            }
        }

        return START_NOT_STICKY
    }

    @Suppress("DEPRECATION")
    private fun setupMediaProjection(resultCode: Int, resultData: Intent) {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        wm.defaultDisplay.getRealMetrics(metrics)
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels
        screenDensity = metrics.densityDpi

        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = mpm.getMediaProjection(resultCode, resultData)

        mediaProjection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                super.onStop()
                stopSelf()
            }
        }, processingHandler)

        rebuildVirtualDisplay()
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        rebuildVirtualDisplay()
    }

    private fun rebuildVirtualDisplay() {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = android.util.DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)
        
        val newWidth = metrics.widthPixels
        val newHeight = metrics.heightPixels
        val newDensity = metrics.densityDpi

        if (screenWidth == newWidth && screenHeight == newHeight && virtualDisplay != null) {
            return // No change needed
        }

        screenWidth = newWidth
        screenHeight = newHeight
        screenDensity = newDensity

        imageReader?.close()
        imageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2)

        if (virtualDisplay == null) {
            virtualDisplay = mediaProjection?.createVirtualDisplay(
                "AimmyCapture",
                screenWidth, screenHeight, screenDensity,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader?.surface, null, null
            )
        } else {
            virtualDisplay?.surface = imageReader?.surface
            virtualDisplay?.resize(screenWidth, screenHeight, screenDensity)
        }

        imageReader?.setOnImageAvailableListener({ reader ->
            // Skip frame if user is not holding the aim button
            if (!OverlayState.isAimbotEnabled) {
                reader.acquireLatestImage()?.close()
                if (isAimPointerDown) {
                    ShizukuTouchInjector.touchUp(1)
                    isAimPointerDown = false
                }
                return@setOnImageAvailableListener
            }

            // Skip frame if still processing previous one
            if (isProcessing) {
                reader.acquireLatestImage()?.close()
                return@setOnImageAvailableListener
            }

            // Thermal Throttling & Eco Mode
            val now = SystemClock.uptimeMillis()
            val temp = OverlayState.currentTemperature
            
            val baseInterval = if (prefs.getBoolean("ecoMode", true)) 33 else 16
            
            val dynamicInterval = when {
                temp >= 45f -> 100
                temp >= 40f -> 66
                else -> baseInterval
            }

            if ((now - lastProcessTime) < dynamicInterval) {
                reader.acquireLatestImage()?.close()
                return@setOnImageAvailableListener
            }

            val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
            isProcessing = true
            lastProcessTime = now

            processingHandler?.post {
                try {
                    processFrame(image)
                } catch (e: Exception) {
                    e.printStackTrace()
                } finally {
                    try { image.close() } catch (_: Exception) {}
                    isProcessing = false
                }
            }
        }, processingHandler)
    }

    private fun processFrame(image: Image) {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * screenWidth

        // Reuse the capture bitmap when possible
        val bitmapWidth = screenWidth + rowPadding / pixelStride
        if (captureBitmap == null || captureBitmap!!.width != bitmapWidth || captureBitmap!!.height != screenHeight) {
            captureBitmap?.recycle()
            captureBitmap = Bitmap.createBitmap(bitmapWidth, screenHeight, Bitmap.Config.ARGB_8888)
        }

        buffer.rewind()
        captureBitmap!!.copyPixelsFromBuffer(buffer)

        val modelSize = yoloDetector?.inputSize ?: 640

        // Crop the center of the screen to a square for the model.
        // Use the smaller of width/height to get the largest centered square.
        val cropSize = kotlin.math.min(screenWidth, screenHeight)
        val cropStartX = (screenWidth - cropSize) / 2
        val cropStartY = (screenHeight - cropSize) / 2

        // Crop the center square from the captured screen
        if (frameBitmap == null || frameBitmap!!.width != cropSize || frameBitmap!!.height != cropSize) {
            frameBitmap?.recycle()
            frameBitmap = Bitmap.createBitmap(cropSize, cropSize, Bitmap.Config.ARGB_8888)
        }
        
        val canvas = android.graphics.Canvas(frameBitmap!!)
        canvas.drawColor(android.graphics.Color.BLACK)
        
        val srcRect = android.graphics.Rect(cropStartX, cropStartY, cropStartX + cropSize, cropStartY + cropSize)
        val dstRect = android.graphics.Rect(0, 0, cropSize, cropSize)
        canvas.drawBitmap(captureBitmap!!, srcRect, dstRect, null)

        // Expose debug preview
        OverlayState.latestFrameBitmap = frameBitmap

        val confidenceThreshold = prefs.getFloat("confidence", 60f) / 100f
        val allDetections = yoloDetector?.detect(frameBitmap!!, confidenceThreshold) ?: emptyList()

        // YOLO returns coords in modelSize (640) space.
        // We need to scale them back to cropSize, then offset to screen coords.
        val scale = cropSize.toFloat() / modelSize.toFloat()

        val fovRadius = prefs.getFloat("fov", 150f)
        val aimSpeed = prefs.getFloat("speed", 50f)
        val offsetX = prefs.getFloat("offsetX", 0f)
        val offsetY = prefs.getFloat("offsetY", 0f)

        val screenCenterX = screenWidth / 2f
        val screenCenterY = screenHeight / 2f

        // ─── Map detections from model space → screen space ──────────────────
        var activeTargetMapped: YoloDetector.Detection? = null
        var bestDist = Float.MAX_VALUE
        var targetDx = 0f
        var targetDy = 0f

        val mappedDetections = mutableListOf<YoloDetector.Detection>()

        for (detection in allDetections) {
            // Step 1: Scale from 640 model space to cropSize pixel space
            // Step 2: Offset from crop-local coords to full-screen coords
            val mappedRect = RectF(
                detection.rect.left * scale + cropStartX,
                detection.rect.top * scale + cropStartY,
                detection.rect.right * scale + cropStartX,
                detection.rect.bottom * scale + cropStartY
            )

            // Aim at head region (top 30% of bounding box)
            val aimX = mappedRect.centerX() + offsetX
            val boxHeight = mappedRect.height()
            val aimY = mappedRect.top + boxHeight * 0.3f + offsetY

            val dx = aimX - screenCenterX
            val dy = aimY - screenCenterY
            val dist = kotlin.math.sqrt((dx * dx + dy * dy).toDouble()).toFloat()

            // Only process detections inside the FOV circle
            if (dist <= fovRadius) {
                val mapped = YoloDetector.Detection(mappedRect, detection.confidence, detection.classId)
                mappedDetections.add(mapped)

                if (dist < bestDist) {
                    bestDist = dist
                    targetDx = dx
                    targetDy = dy
                    activeTargetMapped = mapped
                }
            }
        }

        // ─── Update overlay for rendering ─────────────────────────────────────
        OverlayState.updateDetections(mappedDetections, activeTargetMapped)

        // ─── Aim assist: Smooth touch injection ───────────────────────────────
        if (OverlayState.isAimbotEnabled && activeTargetMapped != null && bestDist > 2f) {
            val moveRatio = (aimSpeed / 100f).coerceIn(0.05f, 0.8f)

            val deltaX = targetDx * moveRatio
            val deltaY = targetDy * moveRatio

            // If we don't have currentAimX initialized, set it to the mapped fire target
            if (OverlayState.currentAimX == 0f && OverlayState.currentAimY == 0f) {
                OverlayState.currentAimX = prefs.getFloat("fireTargetX", prefs.getInt("triggerX", screenWidth / 2).toFloat())
                OverlayState.currentAimY = prefs.getFloat("fireTargetY", prefs.getInt("triggerY", screenHeight / 2).toFloat())
            }

            OverlayState.currentAimX += deltaX
            OverlayState.currentAimY += deltaY

            OverlayState.currentAimX = OverlayState.currentAimX.coerceIn(0f, screenWidth.toFloat())
            OverlayState.currentAimY = OverlayState.currentAimY.coerceIn(0f, screenHeight.toFloat())

            ShizukuTouchInjector.touchMove(0, OverlayState.currentAimX, OverlayState.currentAimY)

        }
    }

    override fun onDestroy() {
        super.onDestroy()
        mediaProjection?.stop()
        virtualDisplay?.release()
        imageReader?.close()
        yoloDetector?.close()
        processingThread?.quitSafely()
        captureBitmap?.recycle()
        frameBitmap?.recycle()
        captureBitmap = null
        frameBitmap = null
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "aimmy_capture", "Aimbot Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Aimmy screen capture notification" }
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, "aimmy_capture")
            .setContentTitle("Aimmy Active")
            .setContentText("AI inference running")
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .build()
    }
}
