package com.yolo.camstream.model

/**
 * Representa la configuración configurable en tiempo de ejecución.
 */
data class AppSettings(
    val serverIp: String = "192.168.1.100",
    val serverPort: Int = 5000,
    val targetWidth: Int = 640,
    val targetHeight: Int = 480,
    val jpegQuality: Int = 70,
    val maxFps: Int = 30,
    val isStreaming: Boolean = false
)
