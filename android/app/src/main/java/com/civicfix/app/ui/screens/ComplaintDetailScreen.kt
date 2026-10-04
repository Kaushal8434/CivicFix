package com.civicfix.app.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.civicfix.app.CivicFixApp
import com.civicfix.app.Nav
import com.civicfix.app.data.Complaint
import com.civicfix.app.data.Role
import com.civicfix.app.data.Session
import com.civicfix.app.data.Status
import com.civicfix.app.data.TimelineEvent
import com.civicfix.app.domain.DAY_MS
import com.civicfix.app.ui.components.AppScaffold
import com.civicfix.app.ui.components.PhotoInput
import com.civicfix.app.ui.components.PhotoLarge
import com.civicfix.app.ui.components.SectionCard
import com.civicfix.app.ui.components.Selector
import com.civicfix.app.ui.components.SlaBar
import com.civicfix.app.ui.components.StatusPill
import com.civicfix.app.ui.theme.Danger
import com.civicfix.app.ui.theme.Ok
import com.civicfix.app.util.Notifier
import com.civicfix.app.util.Photos
import com.civicfix.app.util.formatDate
import com.civicfix.app.util.formatDay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun ComplaintDetailScreen(app: CivicFixApp, session: Session, nav: Nav, id: String) {
    val all by app.repo.complaints.collectAsState()
    val c = all.firstOrNull { it.id == id } ?: return AppScaffold("Not found", onBack = nav::back) {}
    val now = app.clock.now()
    val cat = app.ref.category(c.category)
    val dept = app.ref.department(c.departmentId)
    val context = LocalContext.current
    LaunchedEffect(id) { app.repo.refreshSla() }

    AppScaffold(title = c.id, onBack = nav::back) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("${cat.emoji} ${cat.label}", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            StatusPill(c, now)

            SectionCard("⏱️ Resolution deadline (SLA)") {
                SlaBar(app.sla.progress(c, now), c.isOverdue(now))
                Text("Submitted ${formatDate(c.createdAt)}")
                Text("Target date ${formatDay(c.dueAt)}  ·  severity ${c.severity}", fontWeight = FontWeight.SemiBold)
                when {
                    c.status == Status.CLOSED -> Text("Verified fixed by citizen on ${formatDay(c.closedAt ?: now)}", color = Ok)
                    c.isOverdue(now) -> Text("Overdue by ${(now - c.dueAt) / DAY_MS + 1} day(s)", color = Danger)
                    c.status.isOpen -> Text("${(c.dueAt - now) / DAY_MS} day(s) remaining")
                }
                if (c.escalationLevel > 0) Text("Escalated to: ${app.sla.escalatedTo(c.escalationLevel) ?: "senior management"}", color = Danger)
            }

            SectionCard("🧭 Routed to") {
                Text(dept.name, fontWeight = FontWeight.SemiBold)
                Text("Officer: ${dept.officerRole}")
                Text("Field team: ${c.assignedTeam ?: "not yet assigned"}")
            }

            SectionCard("📍 Location") {
                Text(c.locationLabel)
                if (c.landmark.isNotBlank()) Text("Landmark: ${c.landmark}")
                if (c.lat != null && c.lng != null) {
                    OutlinedButton(onClick = {
                        val uri = Uri.parse("geo:${c.lat},${c.lng}?q=${c.lat},${c.lng}(${Uri.encode(c.id)})")
                        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, uri)) }
                    }) { Text("Open in Maps") }
                }
            }

            SectionCard("📝 Description") {
                Text(c.description.ifBlank { "(none)" })
                Text("Reported by ${c.citizenName}" + if (c.supporters.isNotEmpty()) " + ${c.supporters.size} more citizen(s)" else "",
                    style = MaterialTheme.typography.bodySmall)
                c.aiSummary?.let { Text("🤖 $it", style = MaterialTheme.typography.bodySmall) }
            }

            SectionCard("📷 Before / After evidence") {
                PhotoLarge(c.beforePhoto, "Before (citizen)")
                if (c.afterPhoto != null || !c.status.isOpen) PhotoLarge(c.afterPhoto, "After (department)")
                c.actionTaken?.let { Text("Action taken: $it") }
                c.afterPhotoAiCheck?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }

            when {
                session.role != Role.CITIZEN -> OfficerActions(app, session, c)
                c.citizenName == session.name -> CitizenVerification(app, session, c)
                c.status.isOpen && session.name !in c.supporters -> OutlinedButton(onClick = {
                    app.repo.update(c.id) {
                        it.copy(supporters = it.supporters + session.name,
                            timeline = it.timeline + TimelineEvent(app.clock.now(), "Another citizen reported this", "${session.name} supported the complaint", "Citizen"))
                    }
                }, modifier = Modifier.fillMaxWidth()) { Text("👍 I'm facing this too – support complaint") }
            }

            SectionCard("🕒 Timeline") {
                c.timeline.sortedBy { it.time }.forEach { e ->
                    Row {
                        Box(Modifier.padding(top = 6.dp).size(10.dp).background(MaterialTheme.colorScheme.primary, CircleShape))
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text(e.title, fontWeight = FontWeight.SemiBold)
                            if (e.note.isNotBlank()) Text(e.note, style = MaterialTheme.typography.bodySmall)
                            Text("${formatDate(e.time)} · ${e.actor}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

/** Department workflow (report section 4.2): New → Assigned → In Progress → Resolved (+ after photo). */
@Composable
private fun OfficerActions(app: CivicFixApp, session: Session, c: Complaint) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var team by remember(c.id) { mutableStateOf(c.assignedTeam ?: "Field Team A") }
    var action by remember(c.id) { mutableStateOf("") }
    var after by remember(c.id) { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val actor = "${session.name} (${if (session.role == Role.SUPERVISOR) "Supervisor" else "Officer"})"

    fun event(title: String, note: String) = TimelineEvent(app.clock.now(), title, note, actor)

    SectionCard("🛠️ Officer actions") {
        when (c.status) {
            Status.NEW, Status.REOPENED -> {
                OutlinedTextField(team, { team = it }, label = { Text("Assign to field team") }, modifier = Modifier.fillMaxWidth())
                Button(onClick = {
                    app.repo.update(c.id) { it.copy(status = Status.ASSIGNED, assignedTeam = team, timeline = it.timeline + event("Assigned", "Assigned to $team")) }
                }, modifier = Modifier.fillMaxWidth()) { Text("Assign") }
            }
            Status.ASSIGNED -> Button(onClick = {
                app.repo.update(c.id) { it.copy(status = Status.IN_PROGRESS, timeline = it.timeline + event("Work started", "${it.assignedTeam} is on site")) }
            }, modifier = Modifier.fillMaxWidth()) { Text("Start work (In Progress)") }
            Status.IN_PROGRESS -> {
                Text("Upload completion evidence (Action Taken Report)", fontWeight = FontWeight.SemiBold)
                PhotoInput("After photo", after, "after") { after = it }
                OutlinedTextField(action, { action = it }, label = { Text("Action taken") }, minLines = 2, modifier = Modifier.fillMaxWidth())
                Button(
                    enabled = after != null && action.isNotBlank() && !busy,
                    onClick = {
                        busy = true
                        scope.launch {
                            val check = withContext(Dispatchers.Default) {
                                if (!app.ai.image.available) null
                                else Photos.loadBitmap(after!!)?.let { app.ai.checkAfterPhoto(it, c.category) }
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
                    }, modifier = Modifier.fillMaxWidth(),
                ) {
                    if (busy) CircularProgressIndicator(Modifier.size(18.dp)) else Text("Mark resolved")
                }
                if (after == null) Text("An after-work photo is mandatory.", style = MaterialTheme.typography.bodySmall)
            }
            Status.RESOLVED -> Text("Waiting for the citizen to verify the repair.")
            Status.CLOSED -> Text("Closed – citizen confirmed the problem is fixed. ✅")
        }

        if (c.status.isOpen) {
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
    var reason by remember { mutableStateOf("") }
    SectionCard("✅ Is the problem actually fixed?") {
        Text("The department marked this complaint as resolved. Compare the before/after photos or visit the spot, then confirm.")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                val now = app.clock.now()
                app.repo.update(c.id) {
                    it.copy(status = Status.CLOSED, closedAt = now,
                        timeline = it.timeline + TimelineEvent(now, "Verified fixed", "Citizen confirmed the repair", session.name))
                }
            }, colors = ButtonDefaults.buttonColors(containerColor = Ok), modifier = Modifier.weight(1f)) { Text("Yes, fixed") }
            Button(onClick = {
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
            }, colors = ButtonDefaults.buttonColors(containerColor = Danger), modifier = Modifier.weight(1f)) { Text("No, not fixed") }
        }
        OutlinedTextField(reason, { reason = it }, label = { Text("If not fixed, what is still wrong? (optional)") }, modifier = Modifier.fillMaxWidth())
    }
}
