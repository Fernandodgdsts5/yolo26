package com.yolo.camstream.model

/**
 * Snapshot de rendimiento y diagnósticos en tiempo real.
 */
data class PerformanceStats(
    val captureFps: Int = 0,
    val sentFps: Int = 0,
    val droppedFrames: Int = 0,
    val compressionTimeMs: Long = 0L,
    val kbps: Double = 0.0
)
