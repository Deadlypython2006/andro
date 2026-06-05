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
        // Screen orientation changed (e.g. user entered a game in landscape)
        // We MUST rebuild the VirtualDisplay or it will capture the wrong dimensions
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

        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "AimmyCapture",
            screenWidth, screenHeight, screenDensity,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface, null, null
        )

        imageReader?.setOnImageAvailableListener({ reader ->
            // Skip frame if user is not holding the aim button
            if (!OverlayState.isAimbotEnabled) {
                reader.acquireLatestImage()?.close()
                // Reset aim pointer state for next session
                isAimPointerDown = false
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

        buffer.rewind()
        captureBitmap!!.copyPixelsFromBuffer(buffer)

        val modelSize = yoloDetector?.inputSize ?: 640
        
        // Guard against screens smaller than 640px (shouldn't happen, but prevents crash)
        val cropW = kotlin.math.min(modelSize, screenWidth)
        val cropH = kotlin.math.min(modelSize, screenHeight)
        val cropStartX = (screenWidth - cropW) / 2
        val cropStartY = (screenHeight - cropH) / 2

        // Reuse frame bitmap to prevent massive memory leak (100MB/sec)
        if (frameBitmap == null || frameBitmap!!.width != modelSize) {
            frameBitmap?.recycle()
            frameBitmap = Bitmap.createBitmap(modelSize, modelSize, Bitmap.Config.ARGB_8888)
        }
        
        val canvas = android.graphics.Canvas(frameBitmap!!)
        val srcRect = android.graphics.Rect(cropStartX, cropStartY, cropStartX + cropW, cropStartY + cropH)
        val dstRect = android.graphics.Rect(0, 0, cropW, cropH)
        canvas.drawBitmap(captureBitmap!!, srcRect, dstRect, null)

        // Expose debug preview
        OverlayState.latestFrameBitmap = frameBitmap

        val confidenceThreshold = prefs.getFloat("confidence", 60f) / 100f
        val localFrame = frameBitmap!!
        val allDetections = yoloDetector?.detect(localFrame, confidenceThreshold) ?: emptyList()

        val fovRadius = prefs.getFloat("fov", 150f)
        val aimSpeed = prefs.getFloat("speed", 50f)
        val offsetX = prefs.getFloat("offsetX", 0f)
        val offsetY = prefs.getFloat("offsetY", 0f)

        val screenCenterX = screenWidth / 2f
        val screenCenterY = screenHeight / 2f

        // ─── Map detections to screen coordinates ─────────────────────────────
        // IMPORTANT: We track activeTarget DIRECTLY as a mapped detection,
        // NOT via indexOf (which fails after FOV filtering changes list size)
        var activeTargetMapped: YoloDetector.Detection? = null
        var bestDist = Float.MAX_VALUE
        var targetDx = 0f
        var targetDy = 0f

        val mappedDetections = mutableListOf<YoloDetector.Detection>()

        for (detection in allDetections) {
            val mappedRect = RectF(
                detection.rect.left + cropStartX,
                detection.rect.top + cropStartY,
                detection.rect.right + cropStartX,
                detection.rect.bottom + cropStartY
            )

            // Match PC Aimmy: Aim at head (top 30% of bounding box), not body center
            // PC uses: yBase + yAdjustment where "Top" alignment sets yAdjustment = 0
            // We use top 30% as a good default for headshots
            val aimX = mappedRect.centerX() + offsetX
            val boxHeight = mappedRect.height()
            val aimY = mappedRect.top + boxHeight * 0.3f + offsetY

            val dx = aimX - screenCenterX
            val dy = aimY - screenCenterY
            val dist = kotlin.math.sqrt((dx * dx + dy * dy).toDouble()).toFloat()

            // Only process detections whose AIM POINT is inside the FOV circle
            if (dist <= fovRadius) {
                val mapped = YoloDetector.Detection(mappedRect, detection.confidence, detection.classId)
                mappedDetections.add(mapped)

                if (dist < bestDist) {
                    bestDist = dist
                    targetDx = dx
                    targetDy = dy
                    activeTargetMapped = mapped  // Direct reference, no fragile indexOf!
                }
            }
        }

        // ─── Update overlay for rendering ─────────────────────────────────────
        OverlayState.updateDetections(mappedDetections, activeTargetMapped)

        // ─── Aim assist: Smooth touch injection ───────────────────────────────
        if (activeTargetMapped != null && bestDist > 2f) { // 2px deadzone prevents jitter
            val moveRatio = (aimSpeed / 100f).coerceIn(0.01f, 1f)

            // Delta = fraction of the remaining distance to the target
            // This creates exponential convergence: large moves when far, tiny moves when close
            val deltaX = targetDx * moveRatio
            val deltaY = targetDy * moveRatio

            if (!isAimPointerDown) {
                // Start touch in the right half of the screen (camera look area in most FPS games)
                aimPointerStartX = screenWidth * 0.75f
                aimPointerStartY = screenHeight * 0.5f
                currentAimX = aimPointerStartX
                currentAimY = aimPointerStartY

                ShizukuTouchInjector.touchDown(1, currentAimX, currentAimY)
                isAimPointerDown = true
            }

            currentAimX += deltaX
            currentAimY += deltaY

            // If the simulated finger drifts too far, lift and reset (like running out of mousepad)
            val maxDrift = screenWidth * 0.25f
            if (kotlin.math.abs(currentAimX - aimPointerStartX) > maxDrift ||
                kotlin.math.abs(currentAimY - aimPointerStartY) > maxDrift) {

                ShizukuTouchInjector.touchUp(1)
                currentAimX = aimPointerStartX
                currentAimY = aimPointerStartY
                ShizukuTouchInjector.touchDown(1, currentAimX, currentAimY)
            } else {
                ShizukuTouchInjector.touchMove(1, currentAimX, currentAimY)
            }

        } else if (isAimPointerDown && activeTargetMapped == null) {
            // No target in FOV — release aim pointer so camera stops moving
            ShizukuTouchInjector.touchUp(1)
            isAimPointerDown = false
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Release aim pointer if still held
        if (isAimPointerDown) {
            ShizukuTouchInjector.touchUp(1)
            isAimPointerDown = false
        }
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
