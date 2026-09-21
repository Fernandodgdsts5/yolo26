package com.yolo.camstream.processor

import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import androidx.camera.core.ImageProxy
import java.io.ByteArrayOutputStream

/**
 * Convertidor directo de YUV_420_888 a JPEG sin asignación intermedia de Bitmap.
 * Soporta alineación de stride y formatos variados de planos (NV21 / YUV_420_888).
 */
object YuvToJpegConverter {
    fun convertYuvToJpeg(image: ImageProxy, quality: Int): ByteArray {
        val nv21 = yuv420888ToNv21(image)
        val yuvImage = YuvImage(nv21, ImageFormat.NV21, image.width, image.height, null)
        val out = ByteArrayOutputStream()
        yuvImage.compressToJpeg(Rect(0, 0, image.width, image.height), quality, out)
        return out.toByteArray()
    }

    private fun yuv420888ToNv21(image: ImageProxy): ByteArray {
        val width = image.width
        val height = image.height
        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]

        val yBuffer = yPlane.buffer
        val uBuffer = uPlane.buffer
        val vBuffer = vPlane.buffer

        yBuffer.rewind()
        uBuffer.rewind()
        vBuffer.rewind()

        val nv21 = ByteArray(width * height * 3 / 2)

        // Copiar plano Y
        val yRowStride = yPlane.rowStride
        val yPixelStride = yPlane.pixelStride
        var outputOffset = 0

        if (yRowStride == width && yPixelStride == 1) {
            yBuffer.get(nv21, 0, width * height)
            outputOffset = width * height
        } else {
            for (row in 0 until height) {
                yBuffer.position(row * yRowStride)
                yBuffer.get(nv21, outputOffset, width)
                outputOffset += width
            }
        }

        // Copiar planos UV (intercalados V, U para formato NV21)
        val chromaWidth = width / 2
        val chromaHeight = height / 2
        val vRowStride = vPlane.rowStride
        val vPixelStride = vPlane.pixelStride
        val uPixelStride = uPlane.pixelStride

        val vBufferPos = vBuffer.position()
        val uBufferPos = uBuffer.position()

        // Si los planos UV ya están intercalados (pixelStride == 2)
        if (vPixelStride == 2 && uPixelStride == 2 && vRowStride == width) {
            val uvLength = width * chromaHeight - 1
            if (vBuffer.remaining() >= uvLength) {
                vBuffer.get(nv21, outputOffset, uvLength)
                if (uBuffer.remaining() > 0) {
                    nv21[outputOffset + uvLength] = uBuffer.get(uBuffer.limit() - 1)
                }
                return nv21
            }
        }

        // Copia estándar píxel por píxel para NV21
        for (row in 0 until chromaHeight) {
            val vRowStart = row * vRowStride
            val uRowStart = row * uPlane.rowStride
            for (col in 0 until chromaWidth) {
                val vIndex = vRowStart + col * vPixelStride
                val uIndex = uRowStart + col * uPixelStride
                nv21[outputOffset++] = vBuffer.get(vBufferPos + vIndex)
                nv21[outputOffset++] = uBuffer.get(uBufferPos + uIndex)
            }
        }

        return nv21
    }
}
