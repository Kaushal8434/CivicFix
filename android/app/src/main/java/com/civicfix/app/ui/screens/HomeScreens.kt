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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
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
import com.civicfix.app.ui.components.Banner
import com.civicfix.app.ui.components.ComplaintCard
import com.civicfix.app.ui.components.PrimaryButton
import com.civicfix.app.ui.components.SectionCard
import com.civicfix.app.ui.components.StatTile
import com.civicfix.app.ui.theme.Amber
import com.civicfix.app.ui.theme.Danger
import com.civicfix.app.ui.theme.HeroGradient
import com.civicfix.app.ui.theme.Info
import com.civicfix.app.ui.theme.Ok
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun LoginScreen(app: CivicFixApp, onLogin: (Session) -> Unit) {
    var createMode by rememberSaveable { mutableStateOf(false) }
    var name by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var confirm by rememberSaveable { mutableStateOf("") }
    var phone by rememberSaveable { mutableStateOf("") }
    var showPassword by rememberSaveable { mutableStateOf(false) }
    var showServer by rememberSaveable { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun submit() {
        error = null
        if (createMode && password != confirm) { error = "Passwords do not match"; return }
        busy = true
        scope.launch {
            try {
                app.repo.loadMeta()
                onLogin(if (createMode) app.repo.register(name.trim(), password, phone.ifBlank { null }) else app.repo.login(name.trim(), password))
            } catch (e: Exception) {
                error = e.message
            }
            busy = false
        }
    }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).verticalScroll(rememberScrollState())) {
        Box(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(bottomStart = 36.dp, bottomEnd = 36.dp)).background(HeroGradient)
                .statusBarsPadding().padding(horizontal = 24.dp, vertical = 32.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.size(64.dp).clip(RoundedCornerShape(20.dp)).background(Color.White.copy(alpha = 0.18f)), contentAlignment = Alignment.Center) {
                    Text("🏙️", fontSize = 34.sp)
                }
                Text("CivicFix", color = Color.White, style = MaterialTheme.typography.headlineMedium)
                Text("Report · Route · Resolve · Verify", color = Color.White.copy(alpha = 0.9f), style = MaterialTheme.typography.titleSmall)
                Text(
                    "Snap a photo of a pothole, broken streetlight, leak, overflowing drain or garbage – AI identifies it, the right Delhi department gets a deadline, and it is escalated if the deadline is missed.",
                    color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            SectionCard {
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceContainer).padding(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    listOf(false to "Sign in", true to "Create account").forEach { (mode, label) ->
                        Surface(
                            onClick = { createMode = mode; error = null },
                            shape = RoundedCornerShape(11.dp),
                            color = if (createMode == mode) MaterialTheme.colorScheme.surfaceContainerLowest else Color.Transparent,
                            shadowElevation = if (createMode == mode) 2.dp else 0.dp,
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(label, textAlign = TextAlign.Center, fontWeight = FontWeight.SemiBold,
                                color = if (createMode == mode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(vertical = 10.dp))
                        }
                    }
                }

                OutlinedTextField(
                    value = name, onValueChange = { name = it }, label = { Text("Name") }, singleLine = true,
                    shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next),
                )
                if (createMode) {
                    OutlinedTextField(
                        value = phone, onValueChange = { v -> phone = v.filter { it.isDigit() || it == '+' || it == ' ' }.take(16) },
                        label = { Text("Phone number (optional)") }, singleLine = true, prefix = { Text("📞 ") },
                        supportingText = { Text("Lets the department call you about the complaint") },
                        shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone, imeAction = ImeAction.Next),
                    )
                }
                OutlinedTextField(
                    value = password, onValueChange = { password = it }, label = { Text("Password") }, singleLine = true,
                    visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                    // Not focusable, so the keyboard's "Next" goes to the next field instead of this toggle.
                    trailingIcon = {
                        TextButton(onClick = { showPassword = !showPassword }, modifier = Modifier.focusProperties { canFocus = false }) {
                            Text(if (showPassword) "Hide" else "Show")
                        }
                    },
                    supportingText = if (createMode) ({ Text("At least 6 characters") }) else null,
                    shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = if (createMode) ImeAction.Next else ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { if (!createMode) submit() }),
                )
                if (createMode) {
                    OutlinedTextField(
                        value = confirm, onValueChange = { confirm = it }, label = { Text("Confirm password") }, singleLine = true,
                        visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                        isError = confirm.isNotEmpty() && confirm != password,
                        shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { submit() }),
                    )
                    Text("New accounts are citizen accounts. Officers and supervisors get their account from the administrator.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                error?.let { Banner(it, Danger, "⚠️") }
                Spacer(Modifier.height(4.dp))
                PrimaryButton(
                    if (busy) "Please wait…" else if (createMode) "Create account" else "Sign in", ::submit,
                    enabled = !busy && name.isNotBlank() && password.isNotEmpty() && (!createMode || confirm.isNotEmpty()),
                )
                TextButton(onClick = { showServer = !showServer }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                    Text(if (showServer) "Hide server settings" else "⚙️ Server settings")
                }
                if (showServer) ServerAddressField(app)
            }
            Text(
                "Citizens, officers, supervisors and the administrator all sign in here. Passwords are stored only as salted hashes on the CivicFix server.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            )
        }
    }
}

/** Address of the backend (emulator: http://10.0.2.2:8000, phone on Wi-Fi: http://<PC IP>:8000, or the deployed URL). */
@Composable
fun ServerAddressField(app: CivicFixApp) {
    var url by rememberSaveable { mutableStateOf(app.api.baseUrl) }
    OutlinedTextField(
        url, { url = it; app.api.baseUrl = it }, label = { Text("Server address") }, singleLine = true,
        supportingText = { Text("Emulator: http://10.0.2.2:8000 · Phone on Wi-Fi: http://<PC IP>:8000") },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
fun TopActions(app: CivicFixApp, nav: Nav, onLogout: () -> Unit) {
    val unread by app.repo.unread.collectAsState()
    TextButton(onClick = { nav.go(Screen.Notifications) }) {
        Text(if (unread > 0) "🔔$unread" else "🔔", fontSize = 18.sp, color = Color.White)
    }
    TextButton(onClick = { nav.go(Screen.Analytics) }) { Text("📊", fontSize = 18.sp) }
    TextButton(onClick = { nav.go(Screen.ModelInfo) }) { Text("🤖", fontSize = 18.sp) }
    TextButton(onClick = onLogout) { Text("Exit", color = Color.White, fontWeight = FontWeight.SemiBold) }
}

/** Loads the list from the server on open and every 60 s; returns (loading, error, reload). */
@Composable
private fun rememberRefresher(app: CivicFixApp, session: Session): Triple<Boolean, String?, () -> Unit> {
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(tick) {
        loading = true
        error = runCatching { app.repo.refresh(session) }.exceptionOrNull()?.message
        loading = false
    }
    LaunchedEffect(Unit) { while (true) { delay(60_000); tick++ } }
    return Triple(loading, error) { tick++ }
}

@Composable
fun CitizenHomeScreen(app: CivicFixApp, session: Session, nav: Nav, onLogout: () -> Unit) {
    val all by app.repo.complaints.collectAsState()
    val (loading, error, reload) = rememberRefresher(app, session)
    val now = app.repo.now()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) { if (Build.VERSION.SDK_INT >= 33) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS) }
    val mine = all.filter { it.isMine || it.hasSupported }.sortedByDescending { it.createdAt }
    val community = all.sortedByDescending { it.createdAt }
    val toVerify = mine.count { it.status == Status.RESOLVED && it.isMine }

    AppScaffold(title = "Hi, ${session.name} 👋", subtitle = "Citizen", actions = { TopActions(app, nav, onLogout) }) { pad ->
        LazyColumn(
            Modifier.padding(pad).fillMaxSize(),
            contentPadding = PaddingValues(16.dp, 16.dp, 16.dp, 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            error?.let { item { Banner("$it – tap to retry", Danger, "📡") }; item { TextButton(onClick = reload) { Text("Retry") } } }
            item { ReportCta { nav.go(Screen.Report) } }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StatTile("My open", "${mine.count { it.status.isOpen }}", Info, Modifier.weight(1f), "📂")
                    StatTile("To verify", "$toVerify", Amber, Modifier.weight(1f), "🔔")
                    StatTile("Fixed", "${mine.count { it.status == Status.CLOSED }}", Ok, Modifier.weight(1f), "✅")
                }
            }
            if (toVerify > 0) item {
                Surface(color = Amber.copy(alpha = 0.15f), shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, Amber.copy(alpha = 0.5f))) {
                    Text("🔔  $toVerify complaint(s) marked resolved – open them and confirm whether the problem is actually fixed.",
                        modifier = Modifier.padding(14.dp), style = MaterialTheme.typography.bodyMedium)
                }
            }
            item {
                TabRow(
                    selectedTabIndex = tab, containerColor = Color.Transparent,
                    indicator = { pos -> TabRowDefaults.PrimaryIndicator(Modifier.tabIndicatorOffset(pos[tab]), width = 48.dp, shape = RoundedCornerShape(3.dp)) },
                ) {
                    Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("My reports (${mine.size})", fontWeight = FontWeight.SemiBold) })
                    Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Community (${community.size})", fontWeight = FontWeight.SemiBold) })
                }
            }
            val list = if (tab == 0) mine else community
            if (list.isEmpty() && !loading) item {
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

/** Officer: own tasks. Supervisor: team tasks + escalations. Admin: everything (full editing on the website). */
@Composable
fun OfficerDashboardScreen(app: CivicFixApp, session: Session, nav: Nav, onLogout: () -> Unit) {
    val all by app.repo.complaints.collectAsState()
    val (loading, error, reload) = rememberRefresher(app, session)
    var nowTick by remember { mutableLongStateOf(app.repo.now()) }
    LaunchedEffect(Unit) { while (true) { delay(30_000); nowTick = app.repo.now() } }
    val now = nowTick
    var filter by rememberSaveable { mutableStateOf("open") }
    val escalatedToMe = all.filter { it.escalatedTo?.id == session.id && it.status.isOpen }

    val shown = when (filter) {
        "open" -> all.filter { it.status.isOpen }
        "overdue" -> all.filter { it.isOverdue(now) }
        "escalated" -> all.filter { it.escalationLevel >= 1 && it.status.isOpen }
        "mine" -> escalatedToMe
        "resolved" -> all.filter { !it.status.isOpen }
        else -> all
    }.sortedWith(compareByDescending<Complaint> { it.isOverdue(now) }.thenBy { it.dueAt })

    val wf = session.departmentId?.let { app.repo.workflows[it] }
    val title = when (session.role) {
        Role.ADMIN -> "Administration"
        else -> wf?.name ?: "Dashboard"
    }
    AppScaffold(title = title, subtitle = "${session.designation ?: session.role.name} · ${session.name}", actions = { TopActions(app, nav, onLogout) }) { pad ->
        LazyColumn(
            Modifier.padding(pad).fillMaxSize(),
            contentPadding = PaddingValues(16.dp, 16.dp, 16.dp, 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            error?.let { item { Banner(it, Danger, "📡") }; item { TextButton(onClick = reload) { Text("Retry") } } }
            wf?.let { item { Text(it.agency, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatTile("Open", "${all.count { it.status.isOpen }}", Info, Modifier.weight(1f), "📂")
                    StatTile("Overdue", "${all.count { it.isOverdue(now) }}", Danger, Modifier.weight(1f), "⏰")
                    StatTile("Due 24h", "${all.count { it.status.isOpen && !it.isOverdue(now) && it.dueAt - now < 86_400_000 }}", Amber, Modifier.weight(1f), "⌛")
                    StatTile("Done", "${all.count { !it.status.isOpen }}", Ok, Modifier.weight(1f), "✅")
                }
            }
            if (escalatedToMe.isNotEmpty()) item {
                Banner("🔺 ${escalatedToMe.size} complaint(s) were escalated to you because the deadline was missed by 2× the allowed time.", Danger)
            }
            if (session.role == Role.ADMIN) item {
                Banner("Full editing, deleting and the audit log are on the website dashboard (same sign-in).", Info, "🖥️")
            }
            item {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    buildList {
                        add("open" to "Open"); add("overdue" to "Overdue"); add("escalated" to "Escalated")
                        if (session.role == Role.SUPERVISOR) add("mine" to "Escalated to me")
                        add("resolved" to "Resolved"); add("all" to "All")
                    }.forEach { (k, l) ->
                        FilterChip(
                            selected = filter == k, onClick = { filter = k }, label = { Text(l, fontWeight = FontWeight.SemiBold) },
                            shape = RoundedCornerShape(50),
                            colors = FilterChipDefaults.filterChipColors(selectedContainerColor = MaterialTheme.colorScheme.primary, selectedLabelColor = Color.White),
                        )
                    }
                }
            }
            if (shown.isEmpty() && !loading) item { EmptyState("Nothing here 🎉") }
            items(shown, key = { it.id }) { c ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    ComplaintCard(c, app.ref, now) { nav.go(Screen.Detail(c.id)) }
                    if (session.role != Role.OFFICER) Text(
                        "👤 ${c.officer?.name ?: "unassigned"}" + (c.escalatedTo?.let { " → 🔺 ${it.name}" } ?: ""),
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 12.dp),
                    )
                }
            }
        }
    }
}
