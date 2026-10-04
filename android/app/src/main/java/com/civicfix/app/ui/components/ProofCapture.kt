package com.civicfix.app.ui.components

import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import coil.compose.AsyncImage
import com.civicfix.app.ui.theme.Ok
import java.io.File

/**
 * Completion proof: a LIVE photo and a LIVE video, both captured in-app at the spot
 * (no gallery), as required before a complaint can be marked resolved.
 */
@Composable
fun ProofCapture(photo: String?, video: String?, onPhoto: (String, Long) -> Unit, onVideo: (String, Long) -> Unit) {
    val context = LocalContext.current
    var open by rememberSaveable { mutableStateOf<String?>(null) }   // "photo" | "video"
    var pending by rememberSaveable { mutableStateOf<String?>(null) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) open = pending else Toast.makeText(context, "Camera permission is needed for live proof", Toast.LENGTH_LONG).show()
    }
    fun launch(kind: String) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) open = kind
        else { pending = kind; permission.launch(Manifest.permission.CAMERA) }
    }

    when (open) {
        "photo" -> CameraCaptureDialog("proof", onCaptured = { open = null; onPhoto(it, System.currentTimeMillis()) },
            onDismiss = { open = null }, onError = { open = null; Toast.makeText(context, it, Toast.LENGTH_LONG).show() })
        "video" -> VideoCaptureDialog("proof", onRecorded = { open = null; onVideo(it, System.currentTimeMillis()) },
            onDismiss = { open = null }, onError = { open = null; Toast.makeText(context, it, Toast.LENGTH_LONG).show() })
    }

    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        ProofTile("📷", "Live photo", photo, isVideo = false, Modifier.weight(1f)) { launch("photo") }
        ProofTile("🎥", "Live video", video, isVideo = true, Modifier.weight(1f)) { launch("video") }
    }
}

@Composable
private fun ProofTile(emoji: String, label: String, path: String?, isVideo: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val done = path != null && File(path).exists()
    Surface(
        onClick = onClick, modifier = modifier.height(130.dp), shape = RoundedCornerShape(16.dp),
        color = if (done) Ok.copy(alpha = 0.10f) else MaterialTheme.colorScheme.primary.copy(alpha = 0.06f),
        border = BorderStroke(1.5.dp, if (done) Ok else MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)),
    ) {
        Box {
            if (done && !isVideo) {
                AsyncImage(File(path!!), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(16.dp)))
            }
            Column(Modifier.align(Alignment.Center).padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                if (!done || isVideo) Text(emoji, fontSize = 30.sp)
                Surface(color = if (done && !isVideo) Color.Black.copy(alpha = 0.5f) else Color.Transparent, shape = RoundedCornerShape(8.dp)) {
                    Text(
                        if (done) "✅ $label – tap to redo" else "Tap to record\n$label",
                        textAlign = TextAlign.Center, fontWeight = FontWeight.SemiBold, fontSize = 13.sp,
                        color = if (done && !isVideo) Color.White else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }
        }
    }
}
