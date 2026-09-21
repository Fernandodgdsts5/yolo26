package com.yolo.camstream.monitor

import kotlinx.coroutines.flow.MutableStateFlow
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Monitor de rendimiento thread-safe para captura, compresión y transmisión TCP.
 */
class PerformanceMonitor {
    private val frameCounter = AtomicInteger(0)
    private val sentCounter = AtomicInteger(0)
    private val droppedCounter = AtomicInteger(0)
    private val bytesSentCounter = AtomicLong(0L)

    val captureFps = MutableStateFlow(0)
    val sentFps = MutableStateFlow(0)
    val droppedFrames = MutableStateFlow(0)
    val compressionTimeMs = MutableStateFlow(0L)
    val kbps = MutableStateFlow(0.0)

    @Volatile
    private var lastCheckTime = System.currentTimeMillis()

    fun onFrameCaptured() {
        frameCounter.incrementAndGet()
        updateStatsIfNeeded()
    }

    fun onFrameSent(bytes: Int, compressMs: Long) {
        sentCounter.incrementAndGet()
        bytesSentCounter.addAndGet(bytes.toLong())
        compressionTimeMs.value = compressMs
        updateStatsIfNeeded()
    }

    fun onFrameDropped() {
        droppedFrames.value = droppedCounter.incrementAndGet()
        updateStatsIfNeeded()
    }

    @Synchronized
    private fun updateStatsIfNeeded() {
        val now = System.currentTimeMillis()
        val elapsed = now - lastCheckTime
        if (elapsed >= 1000) {
            captureFps.value = (frameCounter.getAndSet(0) * 1000 / elapsed).toInt()
            sentFps.value = (sentCounter.getAndSet(0) * 1000 / elapsed).toInt()
            val bytes = bytesSentCounter.getAndSet(0L)
            kbps.value = (bytes / 1024.0) / (elapsed / 1000.0)
            lastCheckTime = now
        }
    }
}
