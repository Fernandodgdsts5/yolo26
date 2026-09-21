package com.yolo.camstream.camera

import android.content.Context
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.yolo.camstream.model.AppSettings
import com.yolo.camstream.monitor.PerformanceMonitor
import com.yolo.camstream.network.RawFramePacket
import com.yolo.camstream.network.TcpNetworkClient
import com.yolo.camstream.processor.YuvToJpegConverter
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * Gestor de cámara CameraX optimizado para baja latencia.
 */
class CameraManager(
    private val context: Context,
    private val previewView: PreviewView,
    private val monitor: PerformanceMonitor,
    private val networkClient: TcpNetworkClient
) {
    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private val frameIdCounter = AtomicLong(0L)
    private var lastFrameTimestamp = 0L

    @Volatile
    var settings: AppSettings = AppSettings()

    fun updateSettings(newSettings: AppSettings) {
        this.settings = newSettings
    }

    fun startCamera(lifecycleOwner: LifecycleOwner, initialSettings: AppSettings) {
        this.settings = initialSettings
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)

        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }

            val currentSettings = settings
            val imageAnalysis = ImageAnalysis.Builder()
                .setTargetResolution(Size(currentSettings.targetWidth, currentSettings.targetHeight))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()

            imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy ->
                try {
                    monitor.onFrameCaptured()
                    val activeSettings = settings
                    val now = System.currentTimeMillis()

                    // Control de límite de FPS (Capping)
                    val frameIntervalMs = 1000 / activeSettings.maxFps.coerceAtLeast(1)
                    if (now - lastFrameTimestamp >= frameIntervalMs) {
                        lastFrameTimestamp = now

                        if (activeSettings.isStreaming) {
                            val t0 = System.currentTimeMillis()
                            val jpegBytes = YuvToJpegConverter.convertYuvToJpeg(imageProxy, activeSettings.jpegQuality)
                            val compressMs = System.currentTimeMillis() - t0

                            val packet = RawFramePacket(
                                frameId = frameIdCounter.incrementAndGet(),
                                timestampMs = now,
                                width = imageProxy.width,
                                height = imageProxy.height,
                                jpegData = jpegBytes
                            )

                            val result = networkClient.frameChannel.trySend(packet)
                            if (result.isSuccess) {
                                monitor.onFrameSent(jpegBytes.size, compressMs)
                            } else {
                                monitor.onFrameDropped()
                            }
                        }
                    } else {
                        monitor.onFrameDropped()
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                } finally {
                    // Cierre garantizado en try-finally para evitar Memory Leaks y bloqueos de sensor
                    imageProxy.close()
                }
            }

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    imageAnalysis
                )
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }, ContextCompat.getMainExecutor(context))
    }

    fun shutdown() {
        cameraExecutor.shutdown()
    }
}
