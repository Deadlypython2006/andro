package dev.aimmy.android

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File
import java.nio.FloatBuffer
import kotlin.math.pow
import kotlin.math.sqrt

class YoloDetector(context: Context) {

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private var session: OrtSession? = null

    val inputSize = 640
    private val numClasses = 1

    // Pre-allocate reusable buffers to eliminate GC pressure during inference
    private val pixelBuffer = IntArray(inputSize * inputSize)
    private val floatBuffer = FloatBuffer.allocate(3 * inputSize * inputSize)

    init {
        try {
            val prefs = context.getSharedPreferences("AimmyPrefs", Context.MODE_PRIVATE)
            val useCustom = prefs.getBoolean("useCustomModel", false)
            val selectedModel = prefs.getString("selectedModel", "aio_v7_humanoid.onnx") ?: "aio_v7_humanoid.onnx"

            val modelBytes: ByteArray = if (useCustom) {
                val customFile = File(context.filesDir, "custom_model.onnx")
                if (customFile.exists()) customFile.readBytes()
                else context.assets.open("models/$selectedModel").readBytes()
            } else {
                context.assets.open("models/$selectedModel").readBytes()
            }

            val options = OrtSession.SessionOptions().apply {
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            }
            session = env.createSession(modelBytes, options)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    data class Detection(val rect: RectF, val confidence: Float, val classId: Int)

    fun detect(bitmap: Bitmap, confidenceThreshold: Float): List<Detection> {
        val currentSession = session ?: return emptyList()

        // Resize to model input
        val resized = Bitmap.createScaledBitmap(bitmap, inputSize, inputSize, false)

        // Fill pre-allocated buffers (avoids allocation per frame)
        resized.getPixels(pixelBuffer, 0, inputSize, inputSize, 0, inputSize, inputSize)
        resized.recycle()

        floatBuffer.clear()
        val rOffset = 0
        val gOffset = inputSize * inputSize
        val bOffset = 2 * inputSize * inputSize

        for (i in 0 until inputSize * inputSize) {
            val pixel = pixelBuffer[i]
            floatBuffer.put(rOffset + i, ((pixel shr 16) and 0xFF) / 255.0f)
            floatBuffer.put(gOffset + i, ((pixel shr 8) and 0xFF) / 255.0f)
            floatBuffer.put(bOffset + i, (pixel and 0xFF) / 255.0f)
        }
        floatBuffer.rewind()

        val inputTensor = OnnxTensor.createTensor(
            env, floatBuffer,
            longArrayOf(1, 3, inputSize.toLong(), inputSize.toLong())
        )

        val inputName = currentSession.inputNames.iterator().next()
        val results = currentSession.run(mapOf(inputName to inputTensor))

        // Result.get(0) -> OnnxValue, .value -> raw Object
        @Suppress("UNCHECKED_CAST")
        val output = results.get(0).value as Array<Array<FloatArray>>
        val boxes = output[0]
        val numAnchors = boxes[0].size

        val detections = mutableListOf<Detection>()

        for (i in 0 until numAnchors) {
            // Find max class confidence
            var maxConf = 0f
            var maxClass = 0
            for (c in 0 until numClasses) {
                val conf = boxes[4 + c][i]
                if (conf > maxConf) {
                    maxConf = conf
                    maxClass = c
                }
            }

            if (maxConf < confidenceThreshold) continue

            val cx = boxes[0][i]
            val cy = boxes[1][i]
            val w = boxes[2][i]
            val h = boxes[3][i]

            detections.add(
                Detection(
                    RectF(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2),
                    maxConf, maxClass
                )
            )
        }

        inputTensor.close()
        results.close()

        // Optional: Perform standard NMS (Non-Maximum Suppression) here if needed
        // For aimbots, just having all raw detections above threshold is usually fine
        // since we pick the closest to center anyway.

        return detections
    }

    fun close() {
        session?.close()
        env.close()
    }
}
