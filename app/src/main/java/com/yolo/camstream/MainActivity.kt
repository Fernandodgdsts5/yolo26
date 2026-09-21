package com.yolo.camstream

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.yolo.camstream.camera.CameraManager
import com.yolo.camstream.model.AppSettings
import com.yolo.camstream.model.ConnectionState
import com.yolo.camstream.monitor.PerformanceMonitor
import com.yolo.camstream.network.TcpNetworkClient
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val networkClient = TcpNetworkClient()
    private val monitor = PerformanceMonitor()
    private var cameraManager: CameraManager? = null

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            recreate()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissionLauncher.launch(Manifest.permission.CAMERA)
        }

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = Color.Black) {
                    MainScreen(
                        networkClient = networkClient,
                        monitor = monitor,
                        onCameraReady = { previewView, lifecycleOwner, settings ->
                            if (cameraManager == null) {
                                cameraManager = CameraManager(this, previewView, monitor, networkClient).apply {
                                    startCamera(lifecycleOwner, settings)
                                }
                            }
                        },
                        onSettingsChanged = { updatedSettings ->
                            cameraManager?.updateSettings(updatedSettings)
                        }
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraManager?.shutdown()
        networkClient.disconnect()
    }
}

@Composable
fun MainScreen(
    networkClient: TcpNetworkClient,
    monitor: PerformanceMonitor,
    onCameraReady: (PreviewView, LifecycleOwner, AppSettings) -> Unit,
    onSettingsChanged: (AppSettings) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    var settings by remember { mutableStateOf(AppSettings()) }
    val connState by networkClient.connectionState.collectAsState()

    val captureFps by monitor.captureFps.collectAsState()
    val sentFps by monitor.sentFps.collectAsState()
    val dropped by monitor.droppedFrames.collectAsState()
    val kbps by monitor.kbps.collectAsState()

    val previewView = remember { PreviewView(context) }

    LaunchedEffect(Unit) {
        onCameraReady(previewView, lifecycleOwner, settings)
    }

    LaunchedEffect(settings) {
        onSettingsChanged(settings)
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // Vista previa de cámara nativa en vivo
        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())

        // Panel HUD de Diagnóstico y Control
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .background(Color(0xCC000000))
                .padding(12.dp)
        ) {
            val statusColor = when (connState) {
                is ConnectionState.Connected -> Color.Green
                is ConnectionState.Connecting -> Color.Yellow
                is ConnectionState.Disconnected -> Color.Gray
                is ConnectionState.Error -> Color.Red
            }

            val statusText = when (val state = connState) {
                is ConnectionState.Connected -> "CONECTADO"
                is ConnectionState.Connecting -> "CONECTANDO..."
                is ConnectionState.Disconnected -> "DESCONECTADO"
                is ConnectionState.Error -> "ERROR: ${state.message}"
            }

            Text(
                text = "ESTADO: $statusText",
                color = statusColor,
                fontSize = 14.sp
            )

            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Capture: $captureFps FPS | Transmit: $sentFps FPS | Dropped: $dropped | ${String.format("%.1f", kbps)} KB/s",
                color = Color.White,
                fontSize = 12.sp
            )

            Spacer(modifier = Modifier.height(8.dp))

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = settings.serverIp,
                    onValueChange = { settings = settings.copy(serverIp = it) },
                    label = { Text("IP PC", color = Color.White) },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White
                    ),
                    modifier = Modifier.weight(1f),
                    enabled = !settings.isStreaming
                )

                OutlinedTextField(
                    value = settings.serverPort.toString(),
                    onValueChange = { settings = settings.copy(serverPort = it.toIntOrNull() ?: 5000) },
                    label = { Text("Puerto", color = Color.White) },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White
                    ),
                    modifier = Modifier.width(90.dp),
                    enabled = !settings.isStreaming
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Button(
                onClick = {
                    if (settings.isStreaming) {
                        settings = settings.copy(isStreaming = false)
                        networkClient.disconnect()
                    } else {
                        settings = settings.copy(isStreaming = true)
                        scope.launch {
                            networkClient.connect(settings.serverIp, settings.serverPort)
                            if (networkClient.connectionState.value is ConnectionState.Connected) {
                                networkClient.startSenderLoop { /* frame count */ }
                            } else {
                                settings = settings.copy(isStreaming = false)
                            }
                        }
                    }
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (settings.isStreaming) Color.Red else Color(0xFF0088FF)
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (settings.isStreaming) "DETENER TRANSMISIÓN" else "INICIAR TRANSMISIÓN")
            }
        }
    }
}
