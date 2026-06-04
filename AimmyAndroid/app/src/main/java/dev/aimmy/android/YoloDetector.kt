package dev.aimmy.android

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.FloatBuffer
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

class YoloDetector(context: Context) {
    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private var session: OrtSession? = null
    
    val inputSize = 640
    val numClasses = 1 // Assuming single class aimbot (e.g. enemy)
    
    init {
        try {
            // In a real app, you'd allow users to pick a model. 
            // For now, assume a model named "yolov8_aimbot.onnx" is in the assets.
            val modelBytes = context.assets.open("yolov8_aimbot.onnx").readBytes()
            val options = OrtSession.SessionOptions()
            options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            session = env.createSession(modelBytes, options)
        } catch (e: Exception) {
            e.printStackTrace()
            // Model not found in assets, handle gracefully in UI
        }
    }

    data class Detection(val rect: RectF, val confidence: Float, val classId: Int)

    fun detect(bitmap: Bitmap, confidenceThreshold: Float): Detection? {
        if (session == null) return null

        val resizedBitmap = Bitmap.createScaledBitmap(bitmap, inputSize, inputSize, true)
        val floatBuffer = allocateBuffer(resizedBitmap)

        val inputTensor = OnnxTensor.createTensor(env, floatBuffer, longArrayOf(1, 3, inputSize.toLong(), inputSize.toLong()))
        val inputName = session!!.inputNames.iterator().next()

        val results = session!!.run(mapOf(inputName to inputTensor))
        val output = results.iterator().next().value as Array<Array<FloatArray>>
        
        // Output format for YOLOv8: [1, 84, 8400] -> [batch, values(cx, cy, w, h, class_probs...), anchors]
        val boxes = output[0]
        val numAnchors = boxes[0].size
        val numValues = boxes.size

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

        // Apply NMS (Non-Maximum Suppression) here if needed, 
        // but for a simple aimbot, just finding the one closest to the center is enough.

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
            // R
            buffer.put(i, ((pixel shr 16) and 0xFF) / 255.0f)
            // G
            buffer.put(inputSize * inputSize + i, ((pixel shr 8) and 0xFF) / 255.0f)
            // B
            buffer.put(2 * inputSize * inputSize + i, (pixel and 0xFF) / 255.0f)
        }
        return buffer
    }

    fun close() {
        session?.close()
        env.close()
    }
}
