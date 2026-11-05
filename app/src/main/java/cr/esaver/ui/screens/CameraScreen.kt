/*
 * Cámara para escanear el cargador.
 *
 * Usa CameraX con una guía visual. Al capturar, guarda la foto y lanza el OCR;
 * el resultado se devuelve al editor mediante el ViewModel.
 */
package cr.esaver.ui.screens

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.view.Surface
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.FlashOff
import androidx.compose.material.icons.rounded.FlashOn
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import cr.esaver.AppViewModel
import cr.esaver.CaptureMode
import kotlinx.coroutines.launch
import java.io.File

@Composable
fun CameraScreen(
    vm: AppViewModel,
    mode: CaptureMode,
    onClose: () -> Unit,
    onCaptured: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    var permissionAsked by remember { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { result ->
        granted = result
        permissionAsked = true
    }

    LaunchedEffect(Unit) {
        if (!granted && !permissionAsked) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    val previewView = remember {
        PreviewView(context).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FIT_CENTER
        }
    }
    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }
    var boundCamera by remember { mutableStateOf<Camera?>(null) }
    var torchOn by remember { mutableStateOf(false) }
    var processing by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    DisposableEffect(granted, lifecycleOwner) {
        if (!granted) return@DisposableEffect onDispose { }
        var provider: ProcessCameraProvider? = null
        val future = ProcessCameraProvider.getInstance(context)
        val listener = Runnable {
            runCatching {
                val cameraProvider = future.get()
                provider = cameraProvider
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }
                val capture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .build()
                cameraProvider.unbindAll()
                boundCamera = cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    capture,
                )
                imageCapture = capture
            }.onFailure {
                errorMessage = "No se pudo abrir la cámara: ${it.message ?: "error"}"
            }
        }
        future.addListener(listener, ContextCompat.getMainExecutor(context))
        onDispose {
            runCatching { boundCamera?.cameraControl?.enableTorch(false) }
            runCatching { provider?.unbindAll() }
            imageCapture = null
            boundCamera = null
        }
    }

    LaunchedEffect(torchOn, boundCamera) {
        boundCamera?.cameraControl?.enableTorch(torchOn)
    }

    fun takePhoto() {
        if (processing) return
        val capture = imageCapture
        if (capture == null) {
            errorMessage = "La cámara todavía no está lista. Espera un momento."
            return
        }
        processing = true
        errorMessage = null
        val file = File(context.cacheDir, "esaver_capture_${System.currentTimeMillis()}.jpg")
        val options = ImageCapture.OutputFileOptions.Builder(file).build()
        runCatching { capture.targetRotation = previewView.display?.rotation ?: Surface.ROTATION_0 }
        capture.takePicture(
            options,
            ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    scope.launch {
                        val ok = vm.processCapture(file, mode)
                        processing = false
                        if (ok) {
                            onCaptured()
                        } else {
                            errorMessage = "No se pudo procesar la foto. Intenta otra vez."
                        }
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    processing = false
                    errorMessage = "Error al tomar la foto: ${exception.message}"
                }
            },
        )
    }

    Dialog(
        onDismissRequest = { if (!processing) onClose() },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black),
        ) {
            if (granted) {
                AndroidView(
                    factory = { previewView },
                    modifier = Modifier.fillMaxSize(),
                )
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(
                            androidx.compose.ui.graphics.Brush.verticalGradient(
                                0f to Color.Black.copy(alpha = 0.62f),
                                0.25f to Color.Transparent,
                                0.75f to Color.Transparent,
                                1f to Color.Black.copy(alpha = 0.72f),
                            ),
                        ),
                )
            }

            Row(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = { if (!processing) onClose() },
                    modifier = Modifier.background(Color.Black.copy(alpha = 0.35f), CircleShape),
                ) {
                    Icon(Icons.Rounded.Close, contentDescription = "Cerrar", tint = Color.White)
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = if (mode == CaptureMode.CHARGER) "Escanear cargador" else "Foto del recibo",
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White,
                    )
                    Text(
                        text = if (mode == CaptureMode.CHARGER) {
                            "Enfoca la pantalla donde aparecen los kWh"
                        } else {
                            "Enfoca el recibo completo"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.7f),
                    )
                }
                if (mode == CaptureMode.CHARGER) {
                    IconButton(
                        onClick = { torchOn = !torchOn },
                        modifier = Modifier.background(Color.Black.copy(alpha = 0.35f), CircleShape),
                    ) {
                        Icon(
                            imageVector = if (torchOn) Icons.Rounded.FlashOff else Icons.Rounded.FlashOn,
                            contentDescription = "Linterna",
                            tint = Color.White,
                        )
                    }
                }
            }

            if (!granted) {
                Column(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = "Necesitamos acceso a la cámara",
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "Puedes activarlo para escanear el cargador, o ingresar los datos a mano sin cámara.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.72f),
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(16.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) {
                            Text("Permitir")
                        }
                        TextButton(
                            onClick = {
                                val intent = Intent(
                                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    Uri.parse("package:${context.packageName}"),
                                )
                                context.startActivity(intent)
                            },
                        ) {
                            Text("Ajustes", color = Color.White)
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    TextButton(onClick = onClose) {
                        Text("Ingresar a mano", color = Color.White.copy(alpha = 0.8f))
                    }
                }
            } else {
                Column(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .fillMaxWidth(0.9f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = if (mode == CaptureMode.CHARGER) {
                            "Alinea la pantalla del cargador dentro del marco"
                        } else {
                            "Alinea el recibo dentro del marco"
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White.copy(alpha = 0.9f),
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(10.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(0.9f)
                            .aspectRatio(if (mode == CaptureMode.CHARGER) 2.65f else 0.72f)
                            .border(
                                width = 2.dp,
                                color = Color.White.copy(alpha = 0.85f),
                                shape = RoundedCornerShape(18.dp),
                            ),
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = "Evita reflejos y sombras sobre la pantalla",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.62f),
                    )
                }
            }

            if (granted) {
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(bottom = 22.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    if (errorMessage != null) {
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = Color(0xFF3B0A0A),
                            modifier = Modifier
                                .fillMaxWidth(0.86f)
                                .padding(bottom = 12.dp),
                        ) {
                            Text(
                                text = errorMessage ?: "",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.White,
                                modifier = Modifier.padding(12.dp),
                            )
                        }
                    }
                    Text(
                        text = if (mode == CaptureMode.CHARGER) {
                            "La foto también se guarda en la carga"
                        } else {
                            "Se adjuntará al recibo"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.65f),
                        modifier = Modifier.padding(bottom = 12.dp),
                    )
                    Box(
                        modifier = Modifier
                            .size(78.dp)
                            .clip(CircleShape)
                            .border(3.dp, Color.White, CircleShape)
                            .padding(6.dp)
                            .clip(CircleShape)
                            .background(if (processing) Color.Gray else Color.White)
                            .clickable(enabled = !processing) { takePhoto() },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (processing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(30.dp),
                                color = Color.White,
                                strokeWidth = 3.dp,
                            )
                        } else {
                            Box(
                                Modifier
                                    .size(58.dp)
                                    .clip(CircleShape)
                                    .background(Color.White),
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = if (processing) "Leyendo la pantalla…" else "Toca para capturar",
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White.copy(alpha = 0.8f),
                        modifier = Modifier
                            .clip(RoundedCornerShape(20.dp))
                            .background(Color.Black.copy(alpha = 0.35f))
                            .padding(horizontal = 12.dp, vertical = 5.dp),
                    )
                }
            }

            if (processing) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.55f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = Color.White)
                        Spacer(Modifier.height(14.dp))
                        Text(
                            text = "Procesando foto…",
                            style = MaterialTheme.typography.titleMedium,
                            color = Color.White,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "Detectando kWh, tiempo y voltaje",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.72f),
                        )
                    }
                }
            }
        }
    }
}
