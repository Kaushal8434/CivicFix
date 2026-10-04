package com.civicfix.app.ui.screens

import android.Manifest
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.net.Uri
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import coil.compose.AsyncImage
import com.civicfix.app.CivicFixApp
import com.civicfix.app.Nav
import com.civicfix.app.data.Complaint
import com.civicfix.app.data.Person
import com.civicfix.app.data.Role
import com.civicfix.app.data.Session
import com.civicfix.app.data.StaffMember
import com.civicfix.app.data.Status
import com.civicfix.app.domain.HOUR_MS
import com.civicfix.app.domain.slaProgress
import com.civicfix.app.domain.timeLeft
import com.civicfix.app.ui.components.AppScaffold
import com.civicfix.app.ui.components.Banner
import com.civicfix.app.ui.components.InfoLine
import com.civicfix.app.ui.components.MapPicker
import com.civicfix.app.ui.components.PhotoLarge
import com.civicfix.app.ui.components.PrimaryButton
import com.civicfix.app.ui.components.ProofCapture
import com.civicfix.app.ui.components.SecondaryButton
import com.civicfix.app.ui.components.SectionCard
import com.civicfix.app.ui.components.Selector
import com.civicfix.app.ui.components.SlaBar
import com.civicfix.app.ui.components.StatusPill
import com.civicfix.app.ui.theme.Amber
import com.civicfix.app.ui.theme.Danger
import com.civicfix.app.ui.theme.Info
import com.civicfix.app.ui.theme.Ok
import com.civicfix.app.ui.theme.categoryColor
import com.civicfix.app.util.Gps
import com.civicfix.app.util.Maps
import com.civicfix.app.util.formatDate
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

@Composable
fun ComplaintDetailScreen(app: CivicFixApp, session: Session, nav: Nav, id: String) {
    var c by remember { mutableStateOf(app.repo.complaints.value.firstOrNull { it.id == id }) }
    var error by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var now by remember { mutableLongStateOf(app.repo.now()) }
    LaunchedEffect(id, reload) {
        runCatching { app.repo.get(id) }.onSuccess { c = it; error = null }.onFailure { error = it.message }
    }
    LaunchedEffect(Unit) { while (true) { delay(30_000); now = app.repo.now() } }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val update: (Complaint) -> Unit = { c = it }

    val complaint = c ?: return AppScaffold("Complaint", onBack = nav::back) { pad ->
        Column(Modifier.padding(pad).padding(16.dp)) {
            if (error == null) LinearProgressIndicator(Modifier.fillMaxWidth()) else Banner(error!!, Danger, "⚠️")
        }
    }
    val cat = app.ref.category(complaint.category)
    val accent = categoryColor(cat.key)

    AppScaffold(title = complaint.id, subtitle = "Submitted ${formatDate(complaint.createdAt)}", onBack = nav::back) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            error?.let { Banner(it, Danger, "📡") }
            // Hero: photo with title overlay
            Box(Modifier.fillMaxWidth().height(220.dp).clip(RoundedCornerShape(24.dp)).background(accent.copy(alpha = 0.18f))) {
                if (complaint.beforePhoto != null) {
                    AsyncImage(model = complaint.beforePhoto, contentDescription = "Before photo", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                } else {
                    Text(cat.emoji, fontSize = 72.sp, modifier = Modifier.align(Alignment.Center))
                }
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.4f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.75f))))
                Column(Modifier.align(Alignment.BottomStart).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${cat.emoji} ${cat.label}", color = Color.White, style = MaterialTheme.typography.headlineSmall)
                    StatusPill(complaint, now)
                }
            }

            SectionCard("Deadline", icon = "⏱️", accent = if (complaint.isOverdue(now)) Danger else Ok) {
                SlaBar(slaProgress(complaint, now), complaint.isOverdue(now))
                InfoLine("Due", formatDate(complaint.dueAt))
                if (complaint.status.isOpen) Text(timeLeft(complaint.dueAt, now), fontWeight = FontWeight.Bold,
                    color = if (complaint.isOverdue(now)) Danger else Ok)
                InfoLine("Severity", "${complaint.severity.replaceFirstChar { it.uppercase() }} · target ${complaint.slaHours} h")
                when {
                    complaint.status == Status.CLOSED -> Banner("Verified fixed by the citizen on ${formatDate(complaint.closedAt ?: now)}", Ok, "✅")
                    complaint.escalationLevel >= 2 -> Banner("Escalated to ${complaint.escalatedTo?.let { "${it.name} (${it.designation})" } ?: "a senior officer"} – late by 2× the allowed time.", Danger, "🔺")
                    complaint.escalationLevel == 1 && complaint.status.isOpen -> Banner("Deadline missed – the officer has been warned and the supervisor informed.", Amber, "⚠️")
                }
            }

            SectionCard("Responsible", icon = "🧭", accent = accent) {
                Text(complaint.agency, style = MaterialTheme.typography.titleSmall)
                PersonLine("Assigned officer", complaint.officer)
                PersonLine("Supervisor", complaint.supervisor)
                complaint.escalatedTo?.let { PersonLine("Escalated to", it) }
                complaint.assignedTeam?.let { InfoLine("Field team", it) }
            }

            SectionCard("Location", icon = "📍", accent = Info) {
                if (complaint.lat != null && complaint.lng != null) {
                    MapPicker(complaint.lat, complaint.lng, heightDp = 180,
                        onClick = { Maps.openGoogleMaps(context, complaint.lat, complaint.lng, "${complaint.id} - ${complaint.address.ifBlank { complaint.locationLabel }}") })
                }
                if (complaint.address.isNotBlank()) Text(complaint.address, style = MaterialTheme.typography.bodyMedium)
                Text("Ward office: ${complaint.locationLabel}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (complaint.landmark.isNotBlank()) InfoLine("Landmark", complaint.landmark)
                if (complaint.lat != null && complaint.lng != null) {
                    SecondaryButton("🗺️  Open in Google Maps", { Maps.openGoogleMaps(context, complaint.lat, complaint.lng, "${complaint.id} - ${complaint.address.ifBlank { complaint.locationLabel }}") })
                }
            }

            SectionCard("Description", icon = "📝") {
                Text(complaint.description.ifBlank { "(none)" }, style = MaterialTheme.typography.bodyMedium)
                Text("Reported by ${complaint.citizenName}" + if (complaint.supportCount > 0) " + ${complaint.supportCount} more citizen(s) (+1)" else "",
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (session.role.isStaff && complaint.citizenPhone != null) {
                    SecondaryButton("📞  Call citizen (${complaint.citizenPhone})", {
                        runCatching { context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:${complaint.citizenPhone}"))) }
                    })
                }
                complaint.aiSummary?.let { Banner(it, Info, "🤖") }
            }

            if (complaint.afterPhoto != null || complaint.afterVideo != null) {
                SectionCard("Completion proof (live)", icon = "🎥", accent = Ok) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Box(Modifier.weight(1f)) { PhotoLarge(complaint.beforePhoto, "Before", 150) }
                        Box(Modifier.weight(1f)) { PhotoLarge(complaint.afterPhoto, "After (live)", 150) }
                    }
                    complaint.afterVideo?.let { url ->
                        SecondaryButton("▶  Play completion video", {
                            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(url), "video/*")) }
                        })
                    }
                    complaint.actionTaken?.let { InfoLine("Action taken", it) }
                    complaint.proofCheck?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    complaint.afterPhotoAiCheck?.let { Banner(it, if (it.startsWith("⚠")) Danger else Ok) }
                }
            }

            when {
                session.role.isStaff -> StaffActions(app, session, complaint, update) { reload++ }
                complaint.isMine -> CitizenVerification(app, complaint, update)
                complaint.status.isOpen -> SecondaryButton(
                    if (complaint.hasSupported) "You support this complaint (+1)" else "👍  I'm facing this too – +1",
                    { scope.launch { runCatching { update(app.repo.support(complaint.id)) } } },
                    enabled = !complaint.hasSupported,
                )
            }

            SectionCard("Timeline", icon = "🕒") {
                val events = complaint.timeline.sortedBy { it.time }
                events.forEachIndexed { i, e ->
                    Row {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(Modifier.padding(top = 4.dp).size(12.dp).clip(CircleShape)
                                .background(if (i == events.lastIndex) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline))
                            if (i != events.lastIndex) Box(Modifier.width(2.dp).height(44.dp).background(MaterialTheme.colorScheme.outlineVariant))
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.padding(bottom = 8.dp)) {
                            Text(e.title, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                            if (e.note.isNotBlank()) Text(e.note, style = MaterialTheme.typography.bodySmall)
                            Text("${formatDate(e.time)} · ${e.actor}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun PersonLine(role: String, p: Person?) {
    InfoLine(role, p?.let { "${it.name}${it.designation?.let { d -> " · $d" } ?: ""}" } ?: "–")
}

/** Officer / supervisor workflow: start → resolve with LIVE photo + video; supervisor can (re)assign with a deadline. */
@Composable
private fun StaffActions(app: CivicFixApp, session: Session, c: Complaint, update: (Complaint) -> Unit, refresh: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var team by rememberSaveable(c.id) { mutableStateOf(c.assignedTeam ?: "Field Team A") }
    var action by rememberSaveable(c.id) { mutableStateOf("") }
    var photo by rememberSaveable(c.id) { mutableStateOf<String?>(null) }
    var video by rememberSaveable(c.id) { mutableStateOf<String?>(null) }
    var capturedAt by rememberSaveable(c.id) { mutableLongStateOf(0L) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var showAssign by remember { mutableStateOf(false) }
    val canSupervise = session.role == Role.SUPERVISOR || session.role == Role.ADMIN

    fun act(block: suspend () -> Complaint) {
        busy = true; error = null
        scope.launch {
            runCatching { block() }.onSuccess(update).onFailure { error = it.message }
            busy = false
        }
    }

    /** Proof is uploaded with the officer's current GPS position, so the server can check it was taken at the spot. */
    fun submitProof() {
        busy = true; error = null
        Gps.current(context) { loc ->
            act { app.repo.resolve(c.id, File(photo!!), File(video!!), action.trim(), loc?.latitude, loc?.longitude, capturedAt) }
        }
    }
    val locationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { submitProof() }

    if (showAssign) AssignDialog(app, c, onDismiss = { showAssign = false }) { officer, due, note ->
        showAssign = false
        act { app.repo.assign(c.id, officer.id, due, team.ifBlank { null }, note) }
    }

    if (!c.status.isOpen) {
        if (c.status == Status.RESOLVED) Banner("Waiting for the citizen to verify the repair.", Info, "⏳")
        return
    }
    SectionCard("Officer actions", icon = "🛠️") {
        if (c.status in listOf(Status.NEW, Status.ASSIGNED, Status.REOPENED)) {
            OutlinedTextField(team, { team = it }, label = { Text("Field team") }, singleLine = true,
                shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth())
            PrimaryButton("Start work (In Progress)", { act { app.repo.start(c.id, team.ifBlank { null }) } }, enabled = !busy)
        }
        if (c.status == Status.IN_PROGRESS || c.status == Status.REOPENED || c.status == Status.ASSIGNED) {
            HorizontalDivider()
            Text("Completion proof – live photo AND live video at the spot", style = MaterialTheme.typography.titleSmall)
            Text("Taken in-app only (no gallery). Your GPS position and the capture time are checked by the server.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            ProofCapture(photo, video, onPhoto = { p, t -> photo = p; capturedAt = t }, onVideo = { v, t -> video = v; if (capturedAt == 0L) capturedAt = t })
            OutlinedTextField(action, { action = it }, label = { Text("Action taken") }, minLines = 2,
                shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth())
            if (busy) Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(10.dp))
                Text("Uploading proof…")
            } else PrimaryButton("Upload proof & mark resolved", {
                if (Gps.hasPermission(context)) submitProof()
                else locationPermission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
            }, enabled = photo != null && video != null && action.isNotBlank(), color = Ok)
        }
        if (canSupervise) SecondaryButton("👤  Assign / change deadline", { showAssign = true })
        error?.let { Banner(it, Danger, "⚠️") }
        HorizontalDivider()
        // Correcting the category re-routes the complaint to the right department.
        Selector("Correct category (re-routes complaint)", app.ref.categories, app.ref.category(c.category), { "${it.emoji} ${it.label}" }, { newCat ->
            if (newCat.key != c.category) act { app.repo.correctCategory(c.id, newCat.key) }
        })
    }
}

@Composable
private fun AssignDialog(app: CivicFixApp, c: Complaint, onDismiss: () -> Unit, onAssign: (StaffMember, Long?, String) -> Unit) {
    var staff by remember { mutableStateOf<List<StaffMember>>(emptyList()) }
    var chosen by remember { mutableStateOf<StaffMember?>(null) }
    var hours by remember { mutableStateOf<Int?>(null) }
    var note by remember { mutableStateOf("") }
    LaunchedEffect(c.departmentId) {
        staff = runCatching { app.repo.staff(c.departmentId) }.getOrDefault(emptyList())
        chosen = staff.firstOrNull { it.id == c.officer?.id } ?: staff.firstOrNull()
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Assign ${c.id}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Selector("Officer", staff, chosen, { "${it.name} – ${it.designation} (${it.open} open)" }, { chosen = it })
                Text("New deadline", style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(null to "Keep", 12 to "12 h", 24 to "1 day", 72 to "3 days").forEach { (h, l) ->
                        FilterChip(selected = hours == h, onClick = { hours = h }, label = { Text(l) })
                    }
                }
                OutlinedTextField(note, { note = it }, label = { Text("Note to officer") }, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            TextButton(onClick = { chosen?.let { onAssign(it, hours?.let { h -> app.repo.now() + h * HOUR_MS }, note) } }, enabled = chosen != null) { Text("Assign") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Citizen verification (report section 4.1 step 6): Fixed → Closed, Not fixed → Reopen + escalate. */
@Composable
private fun CitizenVerification(app: CivicFixApp, c: Complaint, update: (Complaint) -> Unit) {
    if (c.status != Status.RESOLVED) return
    val scope = rememberCoroutineScope()
    var reason by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    fun verify(fixed: Boolean) = scope.launch {
        runCatching { app.repo.verify(c.id, fixed, reason) }.onSuccess(update).onFailure { error = it.message }
    }
    SectionCard("Is the problem actually fixed?", icon = "✅", accent = Ok) {
        Text("The department uploaded a live photo and video of the completed work. Compare them with your photo or visit the spot, then confirm.",
            style = MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PrimaryButton("Yes, fixed", { verify(true) }, Modifier.weight(1f), color = Ok)
            PrimaryButton("Not fixed", { verify(false) }, Modifier.weight(1f), color = Danger)
        }
        OutlinedTextField(reason, { reason = it }, label = { Text("If not fixed, what is still wrong? (optional)") },
            shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth())
        error?.let { Banner(it, Danger, "⚠️") }
    }
}
