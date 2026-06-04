package dev.aimmy.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
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
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import kotlin.math.abs

class ScreenCaptureService : Service() {

    private var mediaProjection: MediaProjection? = null
    private var imageReader: ImageReader? = null
    private var yoloDetector: YoloDetector? = null

    private var screenWidth = 0
    private var screenHeight = 0
    private var screenDensity = 0

    private var isProcessing = false
    private var processingThread: HandlerThread? = null
    private var processingHandler: Handler? = null

    // Aimbot settings (could be passed via Intent or SharedPreferences)
    private val fovRadius = 150f
    private val confidenceThreshold = 0.60f
    private val aimSpeed = 50f

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        yoloDetector = YoloDetector(this)
        
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
            
            val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
            isProcessing = true

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

        val bitmap = Bitmap.createBitmap(
            screenWidth + rowPadding / pixelStride,
            screenHeight,
            Bitmap.Config.ARGB_8888
        )
        bitmap.copyPixelsFromBuffer(buffer)

        // Crop actual screen if padded
        val croppedBitmap = if (rowPadding == 0) {
            bitmap
        } else {
            Bitmap.createBitmap(bitmap, 0, 0, screenWidth, screenHeight)
        }

        val detection = yoloDetector?.detect(croppedBitmap, confidenceThreshold)
        
        if (detection != null) {
            val screenCenterX = screenWidth / 2f
            val screenCenterY = screenHeight / 2f

            // Map detection coordinates back to full screen resolution
            val scaleX = screenWidth.toFloat() / yoloDetector!!.inputSize
            val scaleY = screenHeight.toFloat() / yoloDetector!!.inputSize
            
            val targetCenterX = detection.rect.centerX() * scaleX
            val targetCenterY = detection.rect.centerY() * scaleY

            // Check if within FOV
            val dx = targetCenterX - screenCenterX
            val dy = targetCenterY - screenCenterY
            val dist = kotlin.math.sqrt(dx * dx + dy * dy)

            if (dist <= fovRadius) {
                // Calculate swipe endpoint based on Aim Speed (smoothness)
                val moveRatio = aimSpeed / 100f
                val endX = screenCenterX + (dx * moveRatio)
                val endY = screenCenterY + (dy * moveRatio)

                // Inject touch using Shizuku
                ShizukuTouchInjector.swipe(
                    screenCenterX,
                    screenCenterY,
                    endX,
                    endY,
                    steps = 5
                )
            }
        }
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
