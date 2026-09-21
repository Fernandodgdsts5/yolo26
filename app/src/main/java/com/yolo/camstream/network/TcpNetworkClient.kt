package com.yolo.camstream.network

import com.yolo.camstream.model.ConnectionState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class RawFramePacket(
    val frameId: Long,
    val timestampMs: Long,
    val width: Int,
    val height: Int,
    val jpegData: ByteArray
)

/**
 * Cliente TCP de baja latencia con canal DROP_OLDEST para evitar acumulación de frames.
 */
class TcpNetworkClient {
    private var socket: Socket? = null
    private var outputStream: DataOutputStream? = null

    val connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)

    val frameChannel = Channel<RawFramePacket>(
        capacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    suspend fun connect(ip: String, port: Int) = withContext(Dispatchers.IO) {
        try {
            disconnect()
            connectionState.value = ConnectionState.Connecting
            val newSocket = Socket()
            newSocket.tcpNoDelay = true // Desactiva algoritmo de Nagle para latencia mínima
            newSocket.connect(InetSocketAddress(ip, port), 3000)
            socket = newSocket
            outputStream = DataOutputStream(newSocket.getOutputStream())

            // Limpia frames antiguos acumulados
            while (frameChannel.tryReceive().isSuccess) {}

            connectionState.value = ConnectionState.Connected
        } catch (e: Exception) {
            connectionState.value = ConnectionState.Error("Fallo de conexión: ${e.message ?: e.toString()}")
            disconnect()
        }
    }

    suspend fun startSenderLoop(onFrameSent: (Int) -> Unit) = withContext(Dispatchers.IO) {
        val magic = "YOLO".toByteArray(Charsets.US_ASCII)

        try {
            for (packet in frameChannel) {
                val currentSocket = socket
                val currentStream = outputStream
                if (currentSocket?.isConnected != true || currentStream == null || connectionState.value !is ConnectionState.Connected) {
                    break
                }

                val headerBuffer = ByteBuffer.allocate(32).apply {
                    order(ByteOrder.BIG_ENDIAN) // Network Byte Order
                    put(magic)                  // 4 Bytes: "YOLO"
                    putLong(packet.frameId)     // 8 Bytes: Frame ID
                    putLong(packet.timestampMs) // 8 Bytes: Timestamp MS
                    putInt(packet.width)        // 4 Bytes: Width
                    putInt(packet.height)       // 4 Bytes: Height
                    putInt(packet.jpegData.size)// 4 Bytes: Payload Size
                }

                currentStream.write(headerBuffer.array())
                currentStream.write(packet.jpegData)
                currentStream.flush()

                val totalBytes = 32 + packet.jpegData.size
                onFrameSent(totalBytes)
            }
        } catch (e: Exception) {
            if (connectionState.value is ConnectionState.Connected) {
                connectionState.value = ConnectionState.Error("Error de envío: ${e.message ?: e.toString()}")
            }
            disconnect()
        }
    }

    fun disconnect() {
        try {
            outputStream?.close()
            socket?.close()
        } catch (_: Exception) {}
        outputStream = null
        socket = null
        if (connectionState.value is ConnectionState.Connected || connectionState.value is ConnectionState.Connecting) {
            connectionState.value = ConnectionState.Disconnected
        }
    }
}
