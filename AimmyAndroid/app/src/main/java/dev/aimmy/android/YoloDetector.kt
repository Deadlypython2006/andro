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
        val rows = boxes.size
        val cols = boxes[0].size

        val detections = mutableListOf<Detection>()

        val isYoloV8 = rows < cols

        if (isYoloV8) {
            // YOLOv8 Format: [num_classes + 4, num_anchors] (e.g., 5 rows, 8400 cols)
            val numAnchors = cols
            for (i in 0 until numAnchors) {
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
        } else {
            // YOLOv5 Format: [num_anchors, num_classes + 5] (e.g., 25200 rows, 6 cols)
            val numAnchors = rows
            for (i in 0 until numAnchors) {
                val anchorData = boxes[i]
                val objConf = anchorData[4]
                
                if (objConf < confidenceThreshold) continue

                var maxClassConf = 0f
                var maxClass = 0
                for (c in 0 until numClasses) {
                    val classConf = anchorData[5 + c]
                    if (classConf > maxClassConf) {
                        maxClassConf = classConf
                        maxClass = c
                    }
                }

                val finalConf = objConf * maxClassConf
                if (finalConf < confidenceThreshold) continue

                val cx = anchorData[0]
                val cy = anchorData[1]
                val w = anchorData[2]
                val h = anchorData[3]

                detections.add(
                    Detection(
                        RectF(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2),
                        finalConf, maxClass
                    )
                )
            }
        }

        inputTensor.close()
        results.close()

        // Apply Non-Maximum Suppression (NMS)
        return applyNMS(detections)
    }

    private fun applyNMS(detections: List<Detection>, iouThreshold: Float = 0.45f): List<Detection> {
        val nmsDetections = mutableListOf<Detection>()
        val sortedDetections = detections.sortedByDescending { it.confidence }

        for (detection in sortedDetections) {
            var keep = true
            for (kept in nmsDetections) {
                if (calculateIoU(detection.rect, kept.rect) > iouThreshold) {
                    keep = false
                    break
                }
            }
            if (keep) {
                nmsDetections.add(detection)
            }
        }
        return nmsDetections
    }

    private fun calculateIoU(box1: RectF, box2: RectF): Float {
        val intersectionLeft = kotlin.math.max(box1.left, box2.left)
        val intersectionTop = kotlin.math.max(box1.top, box2.top)
        val intersectionRight = kotlin.math.min(box1.right, box2.right)
        val intersectionBottom = kotlin.math.min(box1.bottom, box2.bottom)

        if (intersectionRight <= intersectionLeft || intersectionBottom <= intersectionTop) {
            return 0f
        }

        val intersectionArea = (intersectionRight - intersectionLeft) * (intersectionBottom - intersectionTop)
        val box1Area = (box1.right - box1.left) * (box1.bottom - box1.top)
        val box2Area = (box2.right - box2.left) * (box2.bottom - box2.top)

        return intersectionArea / (box1Area + box2Area - intersectionArea)
    }

    fun close() {
        session?.close()
        env.close()
    }
}
