package dev.aimmy.android

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File
import java.nio.FloatBuffer

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

        // Resize to model input (640x640)
        val resized = Bitmap.createScaledBitmap(bitmap, inputSize, inputSize, true)

        // getPixels returns standard Android ARGB_8888 packed ints:
        //   bits 31..24 = Alpha, 23..16 = Red, 15..8 = Green, 7..0 = Blue
        resized.getPixels(pixelBuffer, 0, inputSize, 0, 0, inputSize, inputSize)
        if (resized !== bitmap) resized.recycle()

        floatBuffer.clear()
        val totalPixels = inputSize * inputSize
        val rOffset = 0
        val gOffset = totalPixels
        val bOffset = 2 * totalPixels

        for (i in 0 until totalPixels) {
            val pixel = pixelBuffer[i]
            // Standard ARGB_8888 extraction — NO channel swap needed!
            // Bitmap.getPixels always returns correct ARGB regardless of ImageReader source.
            val red   = ((pixel shr 16) and 0xFF) / 255.0f
            val green = ((pixel shr 8) and 0xFF) / 255.0f
            val blue  = (pixel and 0xFF) / 255.0f

            // ONNX model expects CHW layout: [R plane, G plane, B plane]
            floatBuffer.put(rOffset + i, red)
            floatBuffer.put(gOffset + i, green)
            floatBuffer.put(bOffset + i, blue)
        }
        floatBuffer.rewind()

        val inputTensor = OnnxTensor.createTensor(
            env, floatBuffer,
            longArrayOf(1, 3, inputSize.toLong(), inputSize.toLong())
        )

        val inputName = currentSession.inputNames.iterator().next()
        val results = currentSession.run(mapOf(inputName to inputTensor))

        // Use FloatBuffer for robust tensor access (avoids fragile 3D array cast)
        val outputTensor = results.get(0) as OnnxTensor
        val outputShape = outputTensor.info.shape // e.g. [1, 5, 8400]
        val outputBuffer = outputTensor.floatBuffer

        val dim1 = outputShape[1].toInt() // 5 (4 box coords + numClasses)
        val dim2 = outputShape[2].toInt() // 8400 (num anchors)

        val detections = mutableListOf<Detection>()

        // Detect format: if dim1 < dim2, it's YOLOv8 [1, 5, 8400]
        // if dim1 > dim2, it's YOLOv5 [1, 25200, 6]
        val isYoloV8 = dim1 < dim2

        if (isYoloV8) {
            // YOLOv8: shape [1, numClasses+4, numAnchors]
            val numAnchors = dim2
            val numClassesActual = dim1 - 4

            for (i in 0 until numAnchors) {
                var maxConf = 0f
                var maxClass = 0
                for (c in 0 until numClassesActual) {
                    val conf = outputBuffer.get((4 + c) * numAnchors + i)
                    if (conf > maxConf) {
                        maxConf = conf
                        maxClass = c
                    }
                }

                if (maxConf < confidenceThreshold) continue

                // YOLO outputs are in 640x640 model coordinate space
                val cx = outputBuffer.get(0 * numAnchors + i)
                val cy = outputBuffer.get(1 * numAnchors + i)
                val w  = outputBuffer.get(2 * numAnchors + i)
                val h  = outputBuffer.get(3 * numAnchors + i)

                detections.add(
                    Detection(
                        RectF(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2),
                        maxConf, maxClass
                    )
                )
            }
        } else {
            // YOLOv5: shape [1, numAnchors, numClasses+5]
            val numAnchors = dim1
            val numCols = dim2

            for (i in 0 until numAnchors) {
                val rowOffset = i * numCols
                val objConf = outputBuffer.get(rowOffset + 4)
                if (objConf < confidenceThreshold) continue

                var maxClassConf = 0f
                var maxClass = 0
                for (c in 0 until numClasses) {
                    val classConf = outputBuffer.get(rowOffset + 5 + c)
                    if (classConf > maxClassConf) {
                        maxClassConf = classConf
                        maxClass = c
                    }
                }

                val finalConf = objConf * maxClassConf
                if (finalConf < confidenceThreshold) continue

                val cx = outputBuffer.get(rowOffset + 0)
                val cy = outputBuffer.get(rowOffset + 1)
                val w  = outputBuffer.get(rowOffset + 2)
                val h  = outputBuffer.get(rowOffset + 3)

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
