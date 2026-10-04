package com.civicfix.app.ui.components

import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import coil.compose.AsyncImage
import com.civicfix.app.util.Photos
import java.io.File

/**
 * Photo capture for evidence (before / after photos).
 *
 *  * "Camera"  - in-app CameraX camera ([CameraCaptureDialog]); if the camera permission is
 *                refused or CameraX cannot start, the phone's own camera app is used instead.
 *  * "Gallery" - Android photo picker; the image is copied into app storage.
 *
 * All state that must survive Android recreating the activity is saveable.
 * [overlay] is drawn on top of the preview (used for the live AI hint).
 */
@Composable
fun PhotoInput(
    label: String,
    path: String?,
    prefix: String,
    onPath: (String?) -> Unit,
    overlay: @Composable () -> Unit = {},
) {
    val context = LocalContext.current
    var pendingPath by rememberSaveable { mutableStateOf<String?>(null) }
    var showCamera by rememberSaveable { mutableStateOf(false) }

    fun useFile(f: File) {
        if (f.exists() && f.length() > 0) onPath(f.absolutePath)
    }

    // Fallback: system camera app.
    val systemCamera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val saved = pendingPath?.let(::File)
        if (ok && saved != null) useFile(saved) else saved?.takeIf { it.length() == 0L }?.delete()
        pendingPath = null
    }
    fun launchSystemCamera() {
        val f = Photos.newFile(context, prefix)
        runCatching { f.createNewFile() }
        pendingPath = f.absolutePath
        try {
            systemCamera.launch(Photos.uriFor(context, f))
        } catch (e: Exception) {
            pendingPath = null
            f.delete()
            Toast.makeText(context, "No camera app found – please use Gallery", Toast.LENGTH_LONG).show()
        }
    }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) showCamera = true else launchSystemCamera()
    }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            val f = Photos.importUri(context, uri, prefix)
            if (f != null) useFile(f) else Toast.makeText(context, "Could not open that image", Toast.LENGTH_SHORT).show()
        }
    }

    fun openCamera() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) showCamera = true
        else permission.launch(Manifest.permission.CAMERA)
    }

    if (showCamera) {
        CameraCaptureDialog(
            prefix = prefix,
            onCaptured = { p -> showCamera = false; onPath(p) },
            onDismiss = { showCamera = false },
            onError = { showCamera = false; launchSystemCamera() },
        )
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        val hasPhoto = path != null && File(path).exists()
        if (hasPhoto) {
            Box(Modifier.fillMaxWidth().height(240.dp).clip(RoundedCornerShape(20.dp))) {
                AsyncImage(model = File(path!!), contentDescription = label, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                Box(
                    Modifier.fillMaxSize().background(
                        Brush.verticalGradient(0.55f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.6f)),
                    ),
                )
                Text(label, color = Color.White, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.align(Alignment.TopStart).padding(12.dp)
                        .background(Color.Black.copy(alpha = 0.35f), RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 3.dp))
                Box(Modifier.align(Alignment.BottomStart).padding(12.dp)) { overlay() }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = ::openCamera, modifier = Modifier.weight(1f), shape = RoundedCornerShape(14.dp)) { Text("📷  Retake") }
                OutlinedButton(onClick = {
                    gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(14.dp)) { Text("🖼️  Change") }
                OutlinedButton(onClick = { onPath(null) }, shape = RoundedCornerShape(14.dp)) { Text("✕") }
            }
        } else {
            Surface(
                onClick = ::openCamera,
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.06f),
                border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)),
                modifier = Modifier.fillMaxWidth().height(170.dp),
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Text("📸", fontSize = 40.sp)
                    Spacer(Modifier.height(6.dp))
                    Text("Tap to take a photo", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                    Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = ::openCamera, modifier = Modifier.weight(1f).height(50.dp), shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors()) { Text("📷  Camera") }
                OutlinedButton(onClick = {
                    gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }, modifier = Modifier.weight(1f).height(50.dp), shape = RoundedCornerShape(14.dp)) { Text("🖼️  Gallery") }
            }
        }
    }
}
