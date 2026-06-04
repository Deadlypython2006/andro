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

    init {
        try {
            val prefs = context.getSharedPreferences("AimmyPrefs", Context.MODE_PRIVATE)
            val useCustomModel = prefs.getBoolean("useCustomModel", false)
            val selectedAssetModel = prefs.getString("selectedAssetModel", "yolov8_aimbot.onnx") ?: "yolov8_aimbot.onnx"
            
            val modelBytes: ByteArray = if (useCustomModel) {
                val customFile = File(context.filesDir, "custom_model.onnx")
                if (customFile.exists()) {
                    customFile.readBytes()
                } else {
                    context.assets.open("models/$selectedAssetModel").readBytes()
                }
            } else {
                context.assets.open("models/$selectedAssetModel").readBytes()
            }
            
            val options = OrtSession.SessionOptions()
            options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            session = env.createSession(modelBytes, options)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    data class Detection(val rect: RectF, val confidence: Float, val classId: Int)

    fun detect(bitmap: Bitmap, confidenceThreshold: Float): Detection? {
        val currentSession = session ?: return null

        val resizedBitmap = Bitmap.createScaledBitmap(bitmap, inputSize, inputSize, true)
        val floatBuffer = allocateBuffer(resizedBitmap)
        resizedBitmap.recycle()

        val inputTensor = OnnxTensor.createTensor(env, floatBuffer, longArrayOf(1, 3, inputSize.toLong(), inputSize.toLong()))
        val inputName = currentSession.inputNames.iterator().next()

        val results = currentSession.run(mapOf(inputName to inputTensor))
        
        // Result.get(0) returns OnnxValue, .value returns the raw Object
        val rawOutput = results.get(0).value
        
        // YOLOv8 output: [1, 5, 8400] -> [batch, (cx,cy,w,h,conf), anchors]
        // Cast carefully based on actual tensor shape
        @Suppress("UNCHECKED_CAST")
        val output = rawOutput as Array<Array<FloatArray>>
        val boxes = output[0]
        val numAnchors = boxes[0].size

        val detections = mutableListOf<Detection>()

        for (i in 0 until numAnchors) {
            val cx = boxes[0][i]
            val cy = boxes[1][i]
            val w = boxes[2][i]
            val h = boxes[3][i]
            var maxConf = 0f
            var maxClass = -1

            for (c in 0 until numClasses) {
                val conf = boxes[4 + c][i]
                if (conf > maxConf) {
                    maxConf = conf
                    maxClass = c
                }
            }

            if (maxConf >= confidenceThreshold) {
                val left = cx - w / 2
                val top = cy - h / 2
                val right = cx + w / 2
                val bottom = cy + h / 2
                detections.add(Detection(RectF(left, top, right, bottom), maxConf, maxClass))
            }
        }

        inputTensor.close()
        results.close()

        val centerX = inputSize / 2f
        val centerY = inputSize / 2f

        return detections.minByOrNull {
            val boxCenterX = it.rect.centerX()
            val boxCenterY = it.rect.centerY()
            sqrt((boxCenterX - centerX).pow(2) + (boxCenterY - centerY).pow(2))
        }
    }

    private fun allocateBuffer(bitmap: Bitmap): FloatBuffer {
        val buffer = FloatBuffer.allocate(1 * 3 * inputSize * inputSize)
        val pixels = IntArray(inputSize * inputSize)
        bitmap.getPixels(pixels, 0, inputSize, 0, 0, inputSize, inputSize)

        for (i in 0 until inputSize * inputSize) {
            val pixel = pixels[i]
            buffer.put(i, ((pixel shr 16) and 0xFF) / 255.0f)
            buffer.put(inputSize * inputSize + i, ((pixel shr 8) and 0xFF) / 255.0f)
            buffer.put(2 * inputSize * inputSize + i, (pixel and 0xFF) / 255.0f)
        }
        return buffer
    }

    fun close() {
        session?.close()
        env.close()
    }
}
