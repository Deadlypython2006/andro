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
import kotlin.math.abs

class ScreenCaptureService : Service() {

    private var mediaProjection: MediaProjection? = null
    private var imageReader: ImageReader? = null
    private var yoloDetector: YoloDetector? = null
    private lateinit var prefs: SharedPreferences

    private var screenWidth = 0
    private var screenHeight = 0
    private var screenDensity = 0

    private var isProcessing = false
    private var processingThread: HandlerThread? = null
    private var processingHandler: Handler? = null
    private var lastProcessTime = 0L

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        yoloDetector = YoloDetector(this)
        prefs = getSharedPreferences("AimmyPrefs", Context.MODE_PRIVATE)
        
        processingThread = HandlerThread("AimmyProcessingThread")
        processingThread?.start()
        processingHandler = Handler(processingThread!!.looper)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = createNotification()
        startForeground(1, notification)

        val resultCode = intent?.getIntExtra("RESULT_CODE", -1) ?: -1
        val resultData = intent?.getParcelableExtra<Intent>("DATA")

        if (resultCode == android.app.Activity.RESULT_OK && resultData != null) {
            setupMediaProjection(resultCode, resultData)
        }

        return START_NOT_STICKY
    }

    private fun setupMediaProjection(resultCode: Int, resultData: Intent) {
        val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        windowManager.defaultDisplay.getRealMetrics(metrics)
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels
        screenDensity = metrics.densityDpi

        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = mpm.getMediaProjection(resultCode, resultData)

        imageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2)
        
        mediaProjection?.createVirtualDisplay(
            "AimmyScreen",
            screenWidth,
            screenHeight,
            screenDensity,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface,
            null,
            null
        )

        imageReader?.setOnImageAvailableListener({ reader ->
            if (isProcessing) return@setOnImageAvailableListener
            
            val ecoMode = prefs.getBoolean("ecoMode", true)
            val now = SystemClock.uptimeMillis()
            
            // Eco Mode caps framerate to ~30 FPS (every 33ms) to save battery and heat
            if (ecoMode && (now - lastProcessTime) < 33) {
                val img = reader.acquireLatestImage()
                img?.close()
                return@setOnImageAvailableListener
            }

            val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
            isProcessing = true
            lastProcessTime = now

            processingHandler?.post {
                try {
                    processImage(image)
                } finally {
                    image.close()
                    isProcessing = false
                }
            }
        }, processingHandler)
    }

    private fun processImage(image: Image) {
        val planes = image.planes
        val buffer = planes[0].buffer
        val pixelStride = planes[0].pixelStride
        val rowStride = planes[0].rowStride
        val rowPadding = rowStride - pixelStride * screenWidth

        var bitmap = Bitmap.createBitmap(
            screenWidth + rowPadding / pixelStride,
            screenHeight,
            Bitmap.Config.ARGB_8888
        )
        bitmap.copyPixelsFromBuffer(buffer)

        var croppedBitmap: Bitmap? = null
        if (rowPadding != 0) {
            croppedBitmap = Bitmap.createBitmap(bitmap, 0, 0, screenWidth, screenHeight)
        }

        val fovRadius = prefs.getFloat("fov", 150f)
        val confidenceThreshold = prefs.getFloat("confidence", 60f) / 100f
        val aimSpeed = prefs.getFloat("speed", 50f)

        val targetBitmap = croppedBitmap ?: bitmap
        val detection = yoloDetector?.detect(targetBitmap, confidenceThreshold)
        
        if (detection != null) {
            val screenCenterX = screenWidth / 2f
            val screenCenterY = screenHeight / 2f

            val scaleX = screenWidth.toFloat() / yoloDetector!!.inputSize
            val scaleY = screenHeight.toFloat() / yoloDetector!!.inputSize
            
            val offsetX = prefs.getFloat("offsetX", 0f)
            val offsetY = prefs.getFloat("offsetY", 0f)

            // Apply Aimmy PC style offsets
            val targetCenterX = (detection.rect.centerX() * scaleX) + offsetX
            val targetCenterY = (detection.rect.centerY() * scaleY) + offsetY

            val dx = targetCenterX - screenCenterX
            val dy = targetCenterY - screenCenterY
            val dist = kotlin.math.sqrt(dx * dx + dy * dy)

            if (dist <= fovRadius) {
                val moveRatio = aimSpeed / 100f
                val endX = screenCenterX + (dx * moveRatio)
                val endY = screenCenterY + (dy * moveRatio)

                ShizukuTouchInjector.swipe(
                    screenCenterX,
                    screenCenterY,
                    endX,
                    endY,
                    steps = 3 // Optimized steps for smoothness
                )
            }
        }

        // Extremely important: Recycle bitmaps to prevent OutOfMemory on mobile
        bitmap.recycle()
        croppedBitmap?.recycle()
    }

    override fun onDestroy() {
        super.onDestroy()
        mediaProjection?.stop()
        yoloDetector?.close()
        processingThread?.quitSafely()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "AimmyCaptureService",
                "Screen Capture Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, "AimmyCaptureService")
            .setContentTitle("Aimmy Aimbot Running")
            .setContentText("Capturing screen and injecting touches")
            //.setSmallIcon(R.mipmap.ic_launcher)
            .build()
    }
}
