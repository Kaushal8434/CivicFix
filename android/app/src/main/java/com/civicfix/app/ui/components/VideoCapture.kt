package com.civicfix.app.ui.components

import android.annotation.SuppressLint
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Recording
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.camera.view.video.AudioConfig
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import kotlinx.coroutines.delay
import java.io.File

/** Longest proof video (keeps uploads small on mobile data). */
private const val MAX_SECONDS = 30

/**
 * Full-screen in-app VIDEO recorder used for completion proof. Recording only happens live
 * through this camera – there is no gallery option – so the officer must be at the spot.
 * Audio is not recorded (no microphone permission needed).
 */
@SuppressLint("UnsafeOptInUsageError", "MissingPermission")
@Composable
fun VideoCaptureDialog(prefix: String, onRecorded: (String) -> Unit, onDismiss: () -> Unit, onError: (String) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var recording by remember { mutableStateOf<Recording?>(null) }
    var seconds by remember { mutableIntStateOf(0) }
    var message by remember { mutableStateOf<String?>(null) }

    val controller = remember {
        LifecycleCameraController(context).apply { setEnabledUseCases(LifecycleCameraController.VIDEO_CAPTURE) }
    }
    DisposableEffect(lifecycleOwner) {
        try {
            controller.bindToLifecycle(lifecycleOwner)
        } catch (e: Exception) {
            onError(e.message ?: "Camera unavailable")
        }
        onDispose {
            recording?.stop()
            controller.unbind()
        }
    }

    fun start() {
        val file = File(Photos.dir(context), "${prefix}_${System.currentTimeMillis()}.mp4")
        seconds = 0
        message = null
        recording = controller.startRecording(
            FileOutputOptions.Builder(file).build(),
            AudioConfig.AUDIO_DISABLED,
            ContextCompat.getMainExecutor(context),
        ) { event ->
            if (event is VideoRecordEvent.Finalize) {
                recording = null
                if (!event.hasError() && file.length() > 0) onRecorded(file.absolutePath)
                else { file.delete(); message = "Recording failed – try again" }
            }
        }
    }

    LaunchedEffect(recording) {
        while (recording != null) {
            delay(1000)
            seconds++
            if (seconds >= MAX_SECONDS) recording?.stop()
        }
    }

    Dialog(onDismissRequest = { if (recording == null) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
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
            Row(Modifier.fillMaxWidth().statusBarsPadding().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { if (recording == null) onDismiss() }, modifier = Modifier.size(44.dp),
                    colors = IconButtonDefaults.iconButtonColors(containerColor = Color.Black.copy(alpha = 0.45f))) {
                    Icon(Icons.Default.Close, "Close", tint = Color.White)
                }
                Surface(color = if (recording != null) Color(0xFFD32F2F) else Color.Black.copy(alpha = 0.5f), shape = RoundedCornerShape(50),
                    modifier = Modifier.padding(start = 12.dp)) {
                    Text(if (recording != null) "● REC  0:%02d / 0:%02d".format(seconds, MAX_SECONDS) else "Live video of the completed work",
                        color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
                }
            }
            Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(bottom = 28.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                message?.let {
                    Surface(color = Color.Black.copy(alpha = 0.6f), shape = RoundedCornerShape(12.dp)) {
                        Text(it, color = Color.White, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
                    }
                }
                Text(if (recording != null) "Pan slowly across the repaired spot, then tap stop" else "Tap to start recording (max $MAX_SECONDS s)",
                    color = Color.White, fontSize = 13.sp)
                Box(
                    Modifier.size(80.dp).clip(CircleShape).border(4.dp, Color.White, CircleShape).padding(8.dp)
                        .clip(if (recording != null) RoundedCornerShape(8.dp) else CircleShape).background(Color(0xFFD32F2F)),
                ) {
                    IconButton(onClick = { if (recording == null) start() else recording?.stop() }, modifier = Modifier.fillMaxSize()) {}
                }
            }
        }
    }
}
