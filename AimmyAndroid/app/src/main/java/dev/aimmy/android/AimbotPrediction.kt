package dev.aimmy.android

import android.os.SystemClock

object AimbotPrediction {
    
    // EMA (WiseTheFox) Prediction
    object WiseTheFox {
        private var lastUpdateTime = 0L
        private const val ALPHA = 0.5f // Smoothing factor
        
        private var emaX = 0f
        private var emaY = 0f
        private var velocityX = 0f
        private var velocityY = 0f
        private var prevX = 0f
        private var prevY = 0f
        private var initialized = false

        fun updateAndPredict(targetX: Float, targetY: Float, leadTimeMultiplier: Float): Pair<Float, Float> {
            val now = SystemClock.uptimeMillis()
            
            if (!initialized) {
                emaX = targetX
                emaY = targetY
                prevX = targetX
                prevY = targetY
                velocityX = 0f
                velocityY = 0f
                lastUpdateTime = now
                initialized = true
                return Pair(targetX, targetY)
            }
            
            // Calculate time delta in seconds, clamped between 1ms and 100ms
            var dt = (now - lastUpdateTime) / 1000f
            dt = dt.coerceIn(0.001f, 0.1f)
            
            // Apply EMA to position
            emaX = ALPHA * targetX + (1.0f - ALPHA) * emaX
            emaY = ALPHA * targetY + (1.0f - ALPHA) * emaY
            
            // Calculate velocity (pixels per second)
            val newVelocityX = (emaX - prevX) / dt
            val newVelocityY = (emaY - prevY) / dt
            
            // Apply EMA to velocity for smoothing
            velocityX = ALPHA * newVelocityX + (1.0f - ALPHA) * velocityX
            velocityY = ALPHA * newVelocityY + (1.0f - ALPHA) * velocityY
            
            prevX = emaX
            prevY = emaY
            lastUpdateTime = now
            
            // Predict where target will be after lead time
            val predictedX = emaX + velocityX * (leadTimeMultiplier / 10f) // Scale multiplier for UI friendly values
            val predictedY = emaY + velocityY * (leadTimeMultiplier / 10f)
            
            return Pair(predictedX, predictedY)
        }
        
        fun reset() {
            initialized = false
        }
    }

    // Velocity History (Shall0e) Prediction
    object Shalloe {
        private val velocityXHistory = mutableListOf<Float>()
        private val velocityYHistory = mutableListOf<Float>()
        
        private var prevX = 0f
        private var prevY = 0f
        private var initialized = false
        
        private const val MAX_HISTORY = 5
        
        fun updateAndPredict(targetX: Float, targetY: Float, leadTimeMultiplier: Float): Pair<Float, Float> {
            if (!initialized) {
                prevX = targetX
                prevY = targetY
                initialized = true
                return Pair(targetX, targetY)
            }
            
            val velocityX = targetX - prevX
            val velocityY = targetY - prevY
            
            if (velocityXHistory.size >= MAX_HISTORY) {
                velocityXHistory.removeAt(0)
                velocityYHistory.removeAt(0)
            }
            
            velocityXHistory.add(velocityX)
            velocityYHistory.add(velocityY)
            
            prevX = targetX
            prevY = targetY
            
            val avgVelocityX = if (velocityXHistory.isNotEmpty()) velocityXHistory.average().toFloat() else 0f
            val avgVelocityY = if (velocityYHistory.isNotEmpty()) velocityYHistory.average().toFloat() else 0f
            
            val predictedX = prevX + avgVelocityX * leadTimeMultiplier
            val predictedY = prevY + avgVelocityY * leadTimeMultiplier
            
            return Pair(predictedX, predictedY)
        }
        
        fun reset() {
            velocityXHistory.clear()
            velocityYHistory.clear()
            initialized = false
        }
    }
}
