package com.civicfix.app.ui.components

import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.height
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import com.civicfix.app.util.Photos

/**
 * Full-screen in-app camera (CameraX).
 *
 * The app does not hand over to the phone's camera app, so Android cannot kill
 * CivicFix in the background while the photo is being taken (the old cause of
 * "the photo disappears after taking it"). The picture is written straight into
 * app storage with its EXIF orientation.
 *
 * [onError] is called when the camera cannot be opened, so the caller can fall
 * back to the system camera app.
 */
@Composable
fun CameraCaptureDialog(prefix: String, onCaptured: (String) -> Unit, onDismiss: () -> Unit, onError: (String) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var busy by remember { mutableStateOf(false) }
    var flash by remember { mutableStateOf(false) }
    var back by remember { mutableStateOf(true) }
    var message by remember { mutableStateOf<String?>(null) }

    val controller = remember {
        LifecycleCameraController(context).apply {
            setEnabledUseCases(LifecycleCameraController.IMAGE_CAPTURE)
            imageCaptureMode = ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY
            // ~2 MP is plenty for evidence and for the 224 px AI model, and keeps files small.
            @Suppress("DEPRECATION")
            imageCaptureTargetSize = CameraController.OutputSize(Size(1440, 1920))
        }
    }
    DisposableEffect(lifecycleOwner) {
        try {
            controller.bindToLifecycle(lifecycleOwner)
        } catch (e: Exception) {
            onError(e.message ?: "Camera unavailable")
        }
        onDispose { controller.unbind() }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            AndroidView(
                factory = { ctx ->
                    PreviewView(ctx).apply {
                        scaleType = PreviewView.ScaleType.FILL_CENTER
                        implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                        this.controller = controller
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )

            // Framing guide
            Box(
                Modifier.align(Alignment.Center).fillMaxWidth(0.84f).aspectRatio(0.8f)
                    .border(2.dp, Color.White.copy(alpha = 0.7f), RoundedCornerShape(24.dp)),
            )
            // Scrims keep the controls readable on bright scenes.
            Box(Modifier.align(Alignment.TopCenter).fillMaxWidth().height(140.dp)
                .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.65f), Color.Transparent))))
            Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(200.dp)
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.65f)))))

            Row(
                Modifier.fillMaxWidth().statusBarsPadding().padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RoundIcon(onClick = onDismiss) { Icon(Icons.Default.Close, "Close", tint = Color.White) }
                Text(
                    "Fit the problem inside the frame", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
                )
                RoundIcon(onClick = {
                    flash = !flash
                    controller.imageCaptureFlashMode = if (flash) ImageCapture.FLASH_MODE_ON else ImageCapture.FLASH_MODE_OFF
                }) { Text(if (flash) "⚡" else "🔦", fontSize = 18.sp) }
            }

            Column(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(bottom = 28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                message?.let {
                    Surface(color = Color.Black.copy(alpha = 0.6f), shape = RoundedCornerShape(12.dp)) {
                        Text(it, color = Color.White, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
                    }
                }
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 32.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(52.dp))
                    // Shutter
                    Box(
                        Modifier.size(80.dp).clip(CircleShape).border(4.dp, Color.White, CircleShape).padding(7.dp)
                            .clip(CircleShape).background(if (busy) Color.Gray else Color.White),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (busy) CircularProgressIndicator(color = Color.Black, strokeWidth = 3.dp, modifier = Modifier.size(28.dp))
                        else IconButton(onClick = {
                            busy = true
                            message = null
                            val file = Photos.newFile(context, prefix)
                            // LifecycleCameraController tracks device rotation and writes it to EXIF.
                            controller.takePicture(
                                ImageCapture.OutputFileOptions.Builder(file).build(),
                                ContextCompat.getMainExecutor(context),
                                object : ImageCapture.OnImageSavedCallback {
                                    override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                                        busy = false
                                        if (file.exists() && file.length() > 0) onCaptured(file.absolutePath)
                                        else message = "Could not save the photo – try again"
                                    }

                                    override fun onError(exception: ImageCaptureException) {
                                        busy = false
                                        file.delete()
                                        message = "Capture failed – try again"
                                    }
                                },
                            )
                        }, modifier = Modifier.fillMaxSize()) {}
                    }
                    RoundIcon(onClick = {
                        val target = if (back) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
                        if (controller.hasCamera(target)) {
                            back = !back
                            controller.cameraSelector = target
                        }
                    }, size = 52) { Icon(Icons.Default.Refresh, "Switch camera", tint = Color.White) }
                }
            }
        }
    }
}

@Composable
private fun RoundIcon(onClick: () -> Unit, size: Int = 44, content: @Composable () -> Unit) {
    IconButton(
        onClick = onClick,
        modifier = Modifier.size(size.dp),
        colors = IconButtonDefaults.iconButtonColors(containerColor = Color.Black.copy(alpha = 0.45f)),
    ) { content() }
}
