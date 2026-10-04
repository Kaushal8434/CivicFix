package com.civicfix.app.ui.components

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.civicfix.app.util.Photos
import java.io.File

/** Camera / gallery photo capture that stores the image inside app storage. */
@Composable
fun PhotoInput(label: String, path: String?, prefix: String, onPath: (String) -> Unit) {
    val context = LocalContext.current
    var pendingPath by rememberSaveable { mutableStateOf<String?>(null) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val savedPath = pendingPath
        if (ok && savedPath != null) {
            val f = File(savedPath)
            if (f.exists() && f.length() > 0) {
                onPath(f.absolutePath)
            }
        }
    }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) Photos.importUri(context, uri, prefix)?.let { onPath(it.absolutePath) }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        PhotoLarge(path, label)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Button(onClick = {
                val f = Photos.newFile(context, prefix)
                f.parentFile?.mkdirs()
                runCatching { f.createNewFile() }
                pendingPath = f.absolutePath
                camera.launch(Photos.uriFor(context, f))
            }, modifier = Modifier.weight(1f)) { Text("📷 Camera") }
            OutlinedButton(onClick = {
                gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }, modifier = Modifier.weight(1f)) { Text("🖼️ Gallery") }
        }
    }
}
