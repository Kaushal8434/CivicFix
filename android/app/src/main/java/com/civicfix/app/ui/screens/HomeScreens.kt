package com.civicfix.app.ui.screens

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import com.civicfix.app.ui.components.PrimaryButton
import com.civicfix.app.ui.components.SectionCard
import com.civicfix.app.ui.components.Selector
import com.civicfix.app.ui.components.StatTile
import com.civicfix.app.ui.theme.Amber
import com.civicfix.app.ui.theme.Danger
import com.civicfix.app.ui.theme.HeroGradient
import com.civicfix.app.ui.theme.Info
import com.civicfix.app.ui.theme.Ok

@Composable
fun LoginScreen(app: CivicFixApp, onLogin: (Session) -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    var role by rememberSaveable { mutableStateOf(Role.CITIZEN) }
    var deptId by rememberSaveable { mutableStateOf(app.ref.departments.first().id) }
    val dept = app.ref.department(deptId)

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).verticalScroll(rememberScrollState())) {
        Box(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(bottomStart = 36.dp, bottomEnd = 36.dp)).background(HeroGradient)
                .statusBarsPadding().padding(horizontal = 24.dp, vertical = 36.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.size(64.dp).clip(RoundedCornerShape(20.dp)).background(Color.White.copy(alpha = 0.18f)), contentAlignment = Alignment.Center) {
                    Text("🏙️", fontSize = 34.sp)
                }
                Text("CivicFix", color = Color.White, style = MaterialTheme.typography.headlineMedium)
                Text("Report · Route · Resolve · Verify", color = Color.White.copy(alpha = 0.9f), style = MaterialTheme.typography.titleSmall)
                Text(
                    "Snap a photo of a pothole, broken streetlight, leak, overflowing drain or garbage – AI identifies it and sends it to the right department with a deadline.",
                    color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            SectionCard("Sign in", icon = "👋") {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Your name") }, singleLine = true,
                    shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth())
                Text("I am a…", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                RoleOption("🙋", "Citizen", "Report problems and track them", role == Role.CITIZEN) { role = Role.CITIZEN }
                RoleOption("👷", "Department officer", "Resolve complaints for one department", role == Role.OFFICER) { role = Role.OFFICER }
                RoleOption("🧑‍💼", "Supervisor", "All departments, overdue & escalations", role == Role.SUPERVISOR) { role = Role.SUPERVISOR }
                if (role == Role.OFFICER) Selector("Department", app.ref.departments, dept, { it.name }, { deptId = it.id })
                Spacer(Modifier.height(4.dp))
                PrimaryButton("Continue", {
                    onLogin(Session(role, name.trim().ifEmpty { if (role == Role.CITIZEN) "Citizen" else "Officer" }, dept.id.takeIf { role == Role.OFFICER }))
                })
            }
            Text(
                "Academic prototype: sign-in, government integration and ward boundaries are simulated. Everything runs offline on this phone.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            )
        }
    }
}

@Composable
private fun RoleOption(emoji: String, title: String, text: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick, shape = RoundedCornerShape(16.dp),
        color = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.08f) else MaterialTheme.colorScheme.surfaceContainerLowest,
        border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(emoji, fontSize = 26.sp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Box(
                Modifier.size(20.dp).clip(CircleShape)
                    .background(if (selected) MaterialTheme.colorScheme.primary else Color.Transparent),
                contentAlignment = Alignment.Center,
            ) {
                if (selected) Text("✓", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                else Box(Modifier.size(20.dp).clip(CircleShape).background(MaterialTheme.colorScheme.outlineVariant))
            }
        }
    }
}

@Composable
fun TopActions(nav: Nav, onLogout: () -> Unit) {
    TextButton(onClick = { nav.go(Screen.Analytics) }) { Text("📊", fontSize = 20.sp) }
    TextButton(onClick = { nav.go(Screen.ModelInfo) }) { Text("🤖", fontSize = 20.sp) }
    TextButton(onClick = onLogout) { Text("Exit", color = Color.White, fontWeight = FontWeight.SemiBold) }
}

@Composable
fun CitizenHomeScreen(app: CivicFixApp, session: Session, nav: Nav, onLogout: () -> Unit) {
    val all by app.repo.complaints.collectAsState()
    val now = app.clock.now()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) {
        app.repo.refreshSla()
        if (Build.VERSION.SDK_INT >= 33) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
    val mine = all.filter { it.citizenName == session.name || session.name in it.supporters }.sortedByDescending { it.createdAt }
    val community = all.sortedByDescending { it.createdAt }
    val toVerify = mine.count { it.status == Status.RESOLVED && it.citizenName == session.name }

    AppScaffold(title = "Hi, ${session.name} 👋", subtitle = "Citizen", actions = { TopActions(nav, onLogout) }) { pad ->
        LazyColumn(
            Modifier.padding(pad).fillMaxSize(),
            contentPadding = PaddingValues(16.dp, 16.dp, 16.dp, 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                ReportCta { nav.go(Screen.Report) }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StatTile("My open", "${mine.count { it.status.isOpen }}", Info, Modifier.weight(1f), "📂")
                    StatTile("To verify", "$toVerify", Amber, Modifier.weight(1f), "🔔")
                    StatTile("Fixed", "${mine.count { it.status == Status.CLOSED }}", Ok, Modifier.weight(1f), "✅")
                }
            }
            if (toVerify > 0) item {
                Surface(color = Amber.copy(alpha = 0.15f), shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, Amber.copy(alpha = 0.5f))) {
                    Text(
                        "🔔  $toVerify complaint(s) marked resolved – open them and confirm whether the problem is actually fixed.",
                        modifier = Modifier.padding(14.dp), style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            item {
                TabRow(
                    selectedTabIndex = tab,
                    containerColor = Color.Transparent,
                    indicator = { pos ->
                        TabRowDefaults.PrimaryIndicator(Modifier.tabIndicatorOffset(pos[tab]), width = 48.dp, shape = RoundedCornerShape(3.dp))
                    },
                ) {
                    Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("My reports (${mine.size})", fontWeight = FontWeight.SemiBold) })
                    Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Community (${community.size})", fontWeight = FontWeight.SemiBold) })
                }
            }
            val list = if (tab == 0) mine else community
            if (list.isEmpty()) item {
                EmptyState(if (tab == 0) "You have not reported anything yet.\nTap “Report a problem” to start." else "No complaints yet.")
            }
            items(list, key = { it.id }) { c -> ComplaintCard(c, app.ref, now) { nav.go(Screen.Detail(c.id)) } }
        }
    }
}

@Composable
private fun ReportCta(onClick: () -> Unit) {
    Surface(onClick = onClick, shape = RoundedCornerShape(24.dp), color = Color.Transparent, modifier = Modifier.fillMaxWidth()) {
        Box(Modifier.background(HeroGradient).padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Report a problem", color = Color.White, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text("Take a photo – AI detects the issue and routes it to the right department.",
                        color = Color.White.copy(alpha = 0.88f), style = MaterialTheme.typography.bodySmall)
                }
                Spacer(Modifier.width(12.dp))
                Box(Modifier.size(58.dp).clip(CircleShape).background(Color.White), contentAlignment = Alignment.Center) {
                    Text("📸", fontSize = 28.sp)
                }
            }
        }
    }
}

@Composable
private fun EmptyState(text: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 36.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("🌱", fontSize = 44.sp)
        Spacer(Modifier.height(8.dp))
        Text(text, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun OfficerDashboardScreen(app: CivicFixApp, session: Session, nav: Nav, onLogout: () -> Unit) {
    val all by app.repo.complaints.collectAsState()
    val now = app.clock.now()
    LaunchedEffect(Unit) { app.repo.refreshSla() }
    var filter by rememberSaveable { mutableStateOf("open") }

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

    val title = if (session.role == Role.OFFICER) app.ref.department(session.departmentId ?: "").name else "All departments"
    val subtitle = if (session.role == Role.OFFICER) "Officer · ${session.name}" else "Supervisor · ${session.name}"
    AppScaffold(title = title, subtitle = subtitle, actions = { TopActions(nav, onLogout) }) { pad ->
        LazyColumn(
            Modifier.padding(pad).fillMaxSize(),
            contentPadding = PaddingValues(16.dp, 16.dp, 16.dp, 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatTile("New", "${scope.count { it.status == Status.NEW || it.status == Status.REOPENED }}", Info, Modifier.weight(1f), "🆕")
                    StatTile("Active", "${scope.count { it.status == Status.ASSIGNED || it.status == Status.IN_PROGRESS }}", Amber, Modifier.weight(1f), "🛠️")
                    StatTile("Overdue", "${scope.count { it.isOverdue(now) }}", Danger, Modifier.weight(1f), "⏰")
                    StatTile("Done", "${scope.count { !it.status.isOpen }}", Ok, Modifier.weight(1f), "✅")
                }
            }
            item {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("open" to "Open", "overdue" to "Overdue", "escalated" to "Escalated", "resolved" to "Resolved", "all" to "All").forEach { (k, l) ->
                        FilterChip(
                            selected = filter == k, onClick = { filter = k }, label = { Text(l, fontWeight = FontWeight.SemiBold) },
                            shape = RoundedCornerShape(50),
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.primary,
                                selectedLabelColor = Color.White,
                            ),
                        )
                    }
                }
            }
            if (shown.isEmpty()) item { EmptyState("Nothing here 🎉") }
            items(shown, key = { it.id }) { c -> ComplaintCard(c, app.ref, now) { nav.go(Screen.Detail(c.id)) } }
        }
    }
}
