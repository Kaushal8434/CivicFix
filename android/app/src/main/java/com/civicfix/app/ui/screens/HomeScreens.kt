package com.civicfix.app.ui.screens

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.civicfix.app.CivicFixApp
import com.civicfix.app.Nav
import com.civicfix.app.Screen
import com.civicfix.app.data.Complaint
import com.civicfix.app.data.Role
import com.civicfix.app.data.Session
import com.civicfix.app.data.Status
import com.civicfix.app.ui.components.AppScaffold
import com.civicfix.app.ui.components.ComplaintCard
import com.civicfix.app.ui.components.SectionCard
import com.civicfix.app.ui.components.Selector
import com.civicfix.app.ui.theme.Amber
import com.civicfix.app.ui.theme.Danger
import com.civicfix.app.ui.theme.Info
import com.civicfix.app.ui.theme.Ok

@Composable
fun LoginScreen(app: CivicFixApp, onLogin: (Session) -> Unit) {
    var name by remember { mutableStateOf("") }
    var role by remember { mutableStateOf(Role.CITIZEN) }
    var dept by remember { mutableStateOf(app.ref.departments.first()) }
    AppScaffold(title = "CivicFix") { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("Report. Route. Resolve. Verify.", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(
                "Smart location-based civic grievance & resolution system. Report potholes, broken streetlights, " +
                    "leakages, drainage and garbage problems – routed to the right department with a deadline.",
                style = MaterialTheme.typography.bodyMedium,
            )
            SectionCard("Sign in (prototype)") {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Your name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                RoleOption("Citizen – report & track problems", role == Role.CITIZEN) { role = Role.CITIZEN }
                RoleOption("Department officer – resolve assigned complaints", role == Role.OFFICER) { role = Role.OFFICER }
                RoleOption("Supervisor – all departments & escalations", role == Role.SUPERVISOR) { role = Role.SUPERVISOR }
                if (role == Role.OFFICER) {
                    Selector("Department", app.ref.departments, dept, { it.name }, { dept = it })
                }
                Button(
                    onClick = {
                        onLogin(Session(role, name.trim().ifEmpty { if (role == Role.CITIZEN) "Citizen" else "Officer" }, dept.id.takeIf { role == Role.OFFICER }))
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Continue") }
            }
            Text(
                "Academic prototype: authentication, government integration and official boundaries are simulated.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun RoleOption(text: String, selected: Boolean, onClick: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = onClick)
        Text(text, modifier = Modifier.padding(start = 4.dp))
    }
}

@Composable
fun TopActions(nav: Nav, onLogout: () -> Unit) {
    TextButton(onClick = { nav.go(Screen.Analytics) }) { Text("📊", fontSize = 20.sp) }
    TextButton(onClick = { nav.go(Screen.ModelInfo) }) { Text("🤖", fontSize = 20.sp) }
    TextButton(onClick = onLogout) { Text("Exit", color = MaterialTheme.colorScheme.onPrimary) }
}

@Composable
fun CitizenHomeScreen(app: CivicFixApp, session: Session, nav: Nav, onLogout: () -> Unit) {
    val all by app.repo.complaints.collectAsState()
    val now = app.clock.now()
    var tab by remember { mutableIntStateOf(0) }
    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) {
        app.repo.refreshSla()
        if (Build.VERSION.SDK_INT >= 33) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
    val mine = all.filter { it.citizenName == session.name || session.name in it.supporters }.sortedByDescending { it.createdAt }
    val community = all.sortedByDescending { it.createdAt }
    val toVerify = mine.count { it.status == Status.RESOLVED && it.citizenName == session.name }

    AppScaffold(
        title = "Hi, ${session.name}",
        actions = { TopActions(nav, onLogout) },
        floating = {
            ExtendedFloatingActionButton(onClick = { nav.go(Screen.Report) }) { Text("➕  Report a problem") }
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            if (toVerify > 0) {
                Card(
                    Modifier.fillMaxWidth().padding(12.dp),
                    colors = CardDefaults.cardColors(containerColor = Amber.copy(alpha = 0.18f)),
                ) {
                    Text(
                        "🔔 $toVerify complaint(s) marked resolved – please verify whether the problem is actually fixed.",
                        modifier = Modifier.padding(12.dp),
                    )
                }
            }
            TabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("My reports (${mine.size})") })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Community (${community.size})") })
            }
            ComplaintList(if (tab == 0) mine else community, app, now, nav,
                empty = if (tab == 0) "You have not reported anything yet.\nTap “Report a problem”." else "No complaints yet.")
        }
    }
}

@Composable
private fun ComplaintList(list: List<Complaint>, app: CivicFixApp, now: Long, nav: Nav, empty: String) {
    if (list.isEmpty()) {
        Text(empty, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(32.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    LazyColumn(
        contentPadding = PaddingValues(12.dp, 12.dp, 12.dp, 96.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(list, key = { it.id }) { c -> ComplaintCard(c, app.ref, now) { nav.go(Screen.Detail(c.id)) } }
    }
}

@Composable
fun OfficerDashboardScreen(app: CivicFixApp, session: Session, nav: Nav, onLogout: () -> Unit) {
    val all by app.repo.complaints.collectAsState()
    val now = app.clock.now()
    LaunchedEffect(Unit) { app.repo.refreshSla() }
    var filter by remember { mutableStateOf("open") }

    val scope = when (session.role) {
        Role.OFFICER -> all.filter { it.departmentId == session.departmentId }
        else -> all
    }
    val shown = when (filter) {
        "open" -> scope.filter { it.status.isOpen }
        "overdue" -> scope.filter { it.isOverdue(now) }
        "escalated" -> scope.filter { it.escalationLevel > 0 && it.status.isOpen }
        "resolved" -> scope.filter { !it.status.isOpen }
        else -> scope
    }.sortedWith(compareByDescending<Complaint> { it.isOverdue(now) }.thenBy { it.dueAt })

    val title = if (session.role == Role.OFFICER) app.ref.department(session.departmentId ?: "").name else "Supervisor – all departments"
    AppScaffold(title = title, actions = { TopActions(nav, onLogout) }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatTile("New", scope.count { it.status == Status.NEW || it.status == Status.REOPENED }, Info, Modifier.weight(1f))
                StatTile("Active", scope.count { it.status == Status.ASSIGNED || it.status == Status.IN_PROGRESS }, Amber, Modifier.weight(1f))
                StatTile("Overdue", scope.count { it.isOverdue(now) }, Danger, Modifier.weight(1f))
                StatTile("Done", scope.count { !it.status.isOpen }, Ok, Modifier.weight(1f))
            }
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("open" to "Open", "overdue" to "Overdue", "escalated" to "Escalated", "resolved" to "Resolved", "all" to "All").forEach { (k, l) ->
                    FilterChip(selected = filter == k, onClick = { filter = k }, label = { Text(l) })
                }
            }
            Spacer(Modifier.height(4.dp))
            ComplaintList(shown, app, now, nav, empty = "Nothing here 🎉")
        }
    }
}

@Composable
private fun StatTile(label: String, value: Int, color: Color, modifier: Modifier) {
    Card(modifier, colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.12f))) {
        Column(Modifier.padding(10.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("$value", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = color)
            Text(label, style = MaterialTheme.typography.labelSmall)
        }
    }
}
