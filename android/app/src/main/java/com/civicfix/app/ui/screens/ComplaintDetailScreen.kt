package com.civicfix.app.ui.screens

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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import com.civicfix.app.data.Role
import com.civicfix.app.data.Session
import com.civicfix.app.data.Status
import com.civicfix.app.data.TimelineEvent
import com.civicfix.app.domain.DAY_MS
import com.civicfix.app.ui.components.AppScaffold
import com.civicfix.app.ui.components.Banner
import com.civicfix.app.ui.components.EmojiBadge
import com.civicfix.app.ui.components.InfoLine
import com.civicfix.app.ui.components.PhotoInput
import com.civicfix.app.ui.components.PhotoLarge
import com.civicfix.app.ui.components.PrimaryButton
import com.civicfix.app.ui.components.SecondaryButton
import com.civicfix.app.ui.components.SectionCard
import com.civicfix.app.ui.components.Selector
import com.civicfix.app.ui.components.SlaBar
import com.civicfix.app.ui.components.StatusPill
import com.civicfix.app.ui.theme.Danger
import com.civicfix.app.ui.theme.Info
import com.civicfix.app.ui.theme.Ok
import com.civicfix.app.ui.theme.categoryColor
import com.civicfix.app.util.Maps
import com.civicfix.app.util.Notifier
import com.civicfix.app.util.Photos
import com.civicfix.app.util.formatDate
import com.civicfix.app.util.formatDay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun ComplaintDetailScreen(app: CivicFixApp, session: Session, nav: Nav, id: String) {
    val all by app.repo.complaints.collectAsState()
    val c = all.firstOrNull { it.id == id } ?: return AppScaffold("Not found", onBack = nav::back) {}
    val now = app.clock.now()
    val cat = app.ref.category(c.category)
    val accent = categoryColor(cat.key)
    val dept = app.ref.department(c.departmentId)
    val context = LocalContext.current
    LaunchedEffect(id) { app.repo.refreshSla() }

    AppScaffold(title = c.id, subtitle = "Submitted ${formatDay(c.createdAt)}", onBack = nav::back) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // Hero: photo (or category colour) with title overlay
            Box(Modifier.fillMaxWidth().height(220.dp).clip(RoundedCornerShape(24.dp)).background(accent.copy(alpha = 0.18f))) {
                if (c.beforePhoto != null && File(c.beforePhoto).exists()) {
                    AsyncImage(model = File(c.beforePhoto), contentDescription = "Before photo", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                } else {
                    Text(cat.emoji, fontSize = 72.sp, modifier = Modifier.align(Alignment.Center))
                }
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.4f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.75f))))
                Column(Modifier.align(Alignment.BottomStart).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${cat.emoji} ${cat.label}", color = Color.White, style = MaterialTheme.typography.headlineSmall)
                    StatusPill(c, now)
                }
            }

            SectionCard("Resolution deadline", icon = "⏱️", accent = if (c.isOverdue(now)) Danger else Ok) {
                SlaBar(app.sla.progress(c, now), c.isOverdue(now))
                InfoLine("Target date", formatDay(c.dueAt))
                InfoLine("Severity", c.severity.replaceFirstChar { it.uppercase() })
                when {
                    c.status == Status.CLOSED -> Banner("Verified fixed by the citizen on ${formatDay(c.closedAt ?: now)}", Ok, "✅")
                    c.isOverdue(now) -> Banner("Overdue by ${(now - c.dueAt) / DAY_MS + 1} day(s)", Danger, "⏰")
                    c.status.isOpen -> Text("${(c.dueAt - now) / DAY_MS} day(s) remaining", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                }
                if (c.escalationLevel > 0) Banner("Escalated to ${app.sla.escalatedTo(c.escalationLevel) ?: "senior management"}", Danger, "📣")
            }

            SectionCard("Routed to", icon = "🧭", accent = accent) {
                Text(dept.name, style = MaterialTheme.typography.titleSmall)
                InfoLine("Officer", dept.officerRole)
                InfoLine("Field team", c.assignedTeam ?: "Not yet assigned")
            }

            SectionCard("Location", icon = "📍", accent = Info) {
                Text(c.locationLabel, style = MaterialTheme.typography.bodyMedium)
                if (c.landmark.isNotBlank()) InfoLine("Landmark", c.landmark)
                if (c.lat != null && c.lng != null) {
                    SecondaryButton("🗺️  Open in Google Maps", { Maps.openGoogleMaps(context, c.lat, c.lng, "${c.id} - ${c.locationLabel}") })
                }
            }

            SectionCard("Description", icon = "📝") {
                Text(c.description.ifBlank { "(none)" }, style = MaterialTheme.typography.bodyMedium)
                Text("Reported by ${c.citizenName}" + if (c.supporters.isNotEmpty()) " + ${c.supporters.size} more citizen(s)" else "",
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                c.aiSummary?.let { Banner(it, Info, "🤖") }
            }

            if (c.afterPhoto != null || !c.status.isOpen) {
                SectionCard("Before / after", icon = "📷") {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Box(Modifier.weight(1f)) { PhotoLarge(c.beforePhoto, "Before", 160) }
                        Box(Modifier.weight(1f)) { PhotoLarge(c.afterPhoto, "After", 160) }
                    }
                    c.actionTaken?.let { InfoLine("Action taken", it) }
                    c.afterPhotoAiCheck?.let { Banner(it, if (it.startsWith("⚠")) Danger else Ok) }
                }
            }

            when {
                session.role != Role.CITIZEN -> OfficerActions(app, session, c)
                c.citizenName == session.name -> CitizenVerification(app, session, c)
                c.status.isOpen && session.name !in c.supporters -> SecondaryButton("👍  I'm facing this too – support complaint", {
                    app.repo.update(c.id) {
                        it.copy(supporters = it.supporters + session.name,
                            timeline = it.timeline + TimelineEvent(app.clock.now(), "Another citizen reported this", "${session.name} supported the complaint", "Citizen"))
                    }
                })
            }

            SectionCard("Timeline", icon = "🕒") {
                val events = c.timeline.sortedBy { it.time }
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

/** Department workflow (report section 4.2): New → Assigned → In Progress → Resolved (+ after photo). */
@Composable
private fun OfficerActions(app: CivicFixApp, session: Session, c: Complaint) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var team by rememberSaveable(c.id) { mutableStateOf(c.assignedTeam ?: "Field Team A") }
    var action by rememberSaveable(c.id) { mutableStateOf("") }
    var after by rememberSaveable(c.id) { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val actor = "${session.name} (${if (session.role == Role.SUPERVISOR) "Supervisor" else "Officer"})"

    fun event(title: String, note: String) = TimelineEvent(app.clock.now(), title, note, actor)

    SectionCard("Officer actions", icon = "🛠️") {
        when (c.status) {
            Status.NEW, Status.REOPENED -> {
                OutlinedTextField(team, { team = it }, label = { Text("Assign to field team") }, singleLine = true,
                    shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth())
                PrimaryButton("Assign", {
                    app.repo.update(c.id) { it.copy(status = Status.ASSIGNED, assignedTeam = team, timeline = it.timeline + event("Assigned", "Assigned to $team")) }
                })
            }
            Status.ASSIGNED -> PrimaryButton("Start work (In Progress)", {
                app.repo.update(c.id) { it.copy(status = Status.IN_PROGRESS, timeline = it.timeline + event("Work started", "${it.assignedTeam} is on site")) }
            })
            Status.IN_PROGRESS -> {
                Text("Upload completion evidence (Action Taken Report)", style = MaterialTheme.typography.titleSmall)
                PhotoInput("After photo", after, "after", { after = it })
                OutlinedTextField(action, { action = it }, label = { Text("Action taken") }, minLines = 2,
                    shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth())
                if (busy) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(10.dp))
                        Text("Checking the after-photo with AI…")
                    }
                } else {
                    PrimaryButton("Mark resolved", {
                        busy = true
                        scope.launch {
                            val check = withContext(Dispatchers.Default) {
                                if (!app.ai.image.available) null
                                else after?.let { Photos.loadBitmap(it) }?.let { app.ai.checkAfterPhoto(it, c.category) }
                            }
                            val now = app.clock.now()
                            app.repo.update(c.id) {
                                it.copy(status = Status.RESOLVED, afterPhoto = after, actionTaken = action.trim(), resolvedAt = now,
                                    afterPhotoAiCheck = check,
                                    timeline = it.timeline + event("Marked resolved", "${action.trim()}. Awaiting citizen verification."))
                            }
                            Notifier.notify(context, c.id.hashCode(), "Complaint ${c.id} resolved",
                                "The department marked your complaint as resolved. Is the problem actually fixed? Open CivicFix to verify.")
                            busy = false
                        }
                    }, enabled = after != null && action.isNotBlank(), color = Ok)
                }
                if (after == null) Text("An after-work photo is mandatory.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Status.RESOLVED -> Banner("Waiting for the citizen to verify the repair.", Info, "⏳")
            Status.CLOSED -> Banner("Closed – citizen confirmed the problem is fixed.", Ok, "✅")
        }

        if (c.status.isOpen) {
            HorizontalDivider()
            // Authorised users can correct the AI/citizen category (report section 5.1) – this re-routes the complaint.
            Selector("Correct category (re-routes complaint)", app.ref.categories, app.ref.category(c.category), { "${it.emoji} ${it.label}" }, { newCat ->
                if (newCat.key != c.category) app.repo.update(c.id) {
                    it.copy(category = newCat.key, departmentId = newCat.departmentId, status = Status.NEW, assignedTeam = null,
                        timeline = it.timeline + event("Category corrected", "Re-routed to ${app.ref.department(newCat.departmentId).name}"))
                }
            })
        }
    }
}

/** Citizen verification (report section 4.1 step 6): Fixed → Closed, Not fixed → Reopen + escalate. */
@Composable
private fun CitizenVerification(app: CivicFixApp, session: Session, c: Complaint) {
    if (c.status != Status.RESOLVED) return
    var reason by rememberSaveable { mutableStateOf("") }
    SectionCard("Is the problem actually fixed?", icon = "✅", accent = Ok) {
        Text("The department marked this complaint as resolved. Compare the before/after photos or visit the spot, then confirm.",
            style = MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PrimaryButton("Yes, fixed", {
                val now = app.clock.now()
                app.repo.update(c.id) {
                    it.copy(status = Status.CLOSED, closedAt = now,
                        timeline = it.timeline + TimelineEvent(now, "Verified fixed", "Citizen confirmed the repair", session.name))
                }
            }, Modifier.weight(1f), color = Ok)
            PrimaryButton("Not fixed", {
                val now = app.clock.now()
                app.repo.update(c.id) {
                    val newLevel = it.escalationLevel + 1
                    val slaDays = ((it.dueAt - it.createdAt) / DAY_MS / 2).coerceAtLeast(1)
                    it.copy(
                        status = Status.REOPENED, reopenCount = it.reopenCount + 1, escalationLevel = newLevel,
                        resolvedAt = null, dueAt = now + slaDays * DAY_MS,
                        timeline = it.timeline +
                            TimelineEvent(now, "Citizen: NOT fixed – reopened", reason.ifBlank { "Problem still exists" }, session.name) +
                            TimelineEvent(now, "Escalated (level $newLevel)", "Notified: ${app.sla.escalatedTo(newLevel) ?: "senior officer"}. New target ${formatDay(now + slaDays * DAY_MS)}", "System"),
                    )
                }
            }, Modifier.weight(1f), color = Danger)
        }
        OutlinedTextField(reason, { reason = it }, label = { Text("If not fixed, what is still wrong? (optional)") },
            shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth())
    }
}
