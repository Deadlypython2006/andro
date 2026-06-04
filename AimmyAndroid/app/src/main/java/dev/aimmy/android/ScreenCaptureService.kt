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

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        prefs = getSharedPreferences("AimmyPrefs", Context.MODE_PRIVATE)

        processingThread = HandlerThread("AimmyInference", android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY)
        processingThread?.start()
        processingHandler = Handler(processingThread!!.looper)

        // Initialize detector on the processing thread so it doesn't block UI
        processingHandler?.post {
            yoloDetector = YoloDetector(this)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1, createNotification(), android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(1, createNotification())
        }

        val resultCode = intent?.getIntExtra("RESULT_CODE", -1) ?: -1
        @Suppress("DEPRECATION")
        val resultData = intent?.getParcelableExtra<Intent>("DATA")

        if (resultCode == android.app.Activity.RESULT_OK && resultData != null) {
            setupMediaProjection(resultCode, resultData)
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

    private fun rebuildVirtualDisplay() {
        virtualDisplay?.release()
        imageReader?.close()

        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = android.util.DisplayMetrics()
        wm.defaultDisplay.getRealMetrics(metrics)
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels
        screenDensity = metrics.densityDpi

        // Use maxImages=2 to allow double-buffering without blocking
        imageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2)

        mediaProjection?.createVirtualDisplay(
            "AimmyCapture",
            screenWidth, screenHeight, screenDensity,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface, null, null
        )

        imageReader?.setOnImageAvailableListener({ reader ->
            // Skip frame if user is not holding the aim button
            if (!OverlayState.isAimbotEnabled) {
                reader.acquireLatestImage()?.close()
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
            
            val baseInterval = if (prefs.getBoolean("ecoMode", true)) 33 else 16 // 30 FPS vs ~60 FPS
            
            val dynamicInterval = when {
                temp >= 45f -> 100 // 10 FPS (Critical heat)
                temp >= 40f -> 66  // 15 FPS (Warm)
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

        captureBitmap!!.copyPixelsFromBuffer(buffer)

        val modelSize = yoloDetector?.inputSize ?: 640
        
        // Match PC Aimmy: Capture exactly a 640x640 box in the center of the screen (1:1 pixel scale, no downscaling!)
        val cropStartX = (screenWidth - modelSize) / 2
        val cropStartY = (screenHeight - modelSize) / 2

        // Reuse frame bitmap to prevent massive memory leak (100MB/sec)
        if (frameBitmap == null || frameBitmap!!.width != modelSize) {
            frameBitmap?.recycle()
            frameBitmap = Bitmap.createBitmap(modelSize, modelSize, Bitmap.Config.ARGB_8888)
        }
        
        val canvas = android.graphics.Canvas(frameBitmap!!)
        val srcRect = android.graphics.Rect(cropStartX, cropStartY, cropStartX + modelSize, cropStartY + modelSize)
        val dstRect = android.graphics.Rect(0, 0, modelSize, modelSize)
        canvas.drawBitmap(captureBitmap!!, srcRect, dstRect, null)

        // Expose debug preview
        OverlayState.latestFrameBitmap = frameBitmap

        val confidenceThreshold = prefs.getFloat("confidence", 60f) / 100f
        val allDetections = yoloDetector?.detect(frameBitmap!!, confidenceThreshold) ?: emptyList()

        if (frameBitmap !== captureBitmap) {
            frameBitmap.recycle()
        }

        val fovRadius = prefs.getFloat("fov", 150f)
        val aimSpeed = prefs.getFloat("speed", 50f)
        val offsetX = prefs.getFloat("offsetX", 0f)
        val offsetY = prefs.getFloat("offsetY", 0f)

        val screenCenterX = screenWidth / 2f
        val screenCenterY = screenHeight / 2f

        // Map detections to screen coordinates and find the best target
        var activeTargetRaw: YoloDetector.Detection? = null
        var bestDist = Float.MAX_VALUE
        var targetDx = 0f
        var targetDy = 0f

        val mappedDetections = allDetections.map { detection ->
            // Match PC Aimmy: Direct translation without any scaling
            val mappedRect = RectF(
                detection.rect.left + cropStartX,
                detection.rect.top + cropStartY,
                detection.rect.right + cropStartX,
                detection.rect.bottom + cropStartY
            )

            val targetX = mappedRect.centerX() + offsetX
            val targetY = mappedRect.centerY() + offsetY

            val dx = targetX - screenCenterX
            val dy = targetY - screenCenterY
            val dist = kotlin.math.sqrt((dx * dx + dy * dy).toDouble()).toFloat()

            // Determine if this is the closest within FOV
            if (dist <= fovRadius && dist < bestDist) {
                bestDist = dist
                targetDx = dx
                targetDy = dy
                activeTargetRaw = detection
            }

            YoloDetector.Detection(mappedRect, detection.confidence, detection.classId)
        }

        // Active target mapped
        val activeTargetMapped = if (activeTargetRaw != null) {
            val idx = allDetections.indexOf(activeTargetRaw)
            if (idx != -1) mappedDetections[idx] else null
        } else null

        // Update the shared overlay state for rendering
        OverlayState.updateDetections(mappedDetections, activeTargetMapped)

        if (activeTargetMapped != null && bestDist > 2f) { // 2px deadzone prevents jitter
            val moveRatio = (aimSpeed / 100f).coerceIn(0.01f, 1f)
            // Use pointerId 1 for the Aimbot (0 is reserved for the Fire button pass-through)
            ShizukuTouchInjector.swipe(
                1,
                screenCenterX, screenCenterY,
                screenCenterX + targetDx * moveRatio,
                screenCenterY + targetDy * moveRatio,
                steps = 3
            )
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        mediaProjection?.stop()
        yoloDetector?.close()
        processingThread?.quitSafely()
        captureBitmap?.recycle()
        captureBitmap = null
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

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = android.util.DisplayMetrics()
        wm.defaultDisplay.getRealMetrics(metrics)
        
        if (screenWidth != metrics.widthPixels || screenHeight != metrics.heightPixels) {
            rebuildVirtualDisplay()
        }
    }
}
