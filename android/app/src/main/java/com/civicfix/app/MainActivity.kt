package com.civicfix.app

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.civicfix.app.data.Role
import com.civicfix.app.data.Session
import com.civicfix.app.ui.components.PrimaryButton
import com.civicfix.app.ui.screens.AnalyticsScreen
import com.civicfix.app.ui.screens.CitizenHomeScreen
import com.civicfix.app.ui.screens.ComplaintDetailScreen
import com.civicfix.app.ui.screens.LoginScreen
import com.civicfix.app.ui.screens.ModelInfoScreen
import com.civicfix.app.ui.screens.NotificationsScreen
import com.civicfix.app.ui.screens.OfficerDashboardScreen
import com.civicfix.app.ui.screens.ReportWizardScreen
import com.civicfix.app.ui.screens.ServerAddressField
import com.civicfix.app.ui.theme.CivicFixTheme
import com.civicfix.app.util.Notifier
import kotlinx.coroutines.delay

sealed interface Screen {
    data object Home : Screen
    data object Report : Screen
    data class Detail(val id: String) : Screen
    data object Analytics : Screen
    data object ModelInfo : Screen
    data object Notifications : Screen
}

class Nav(initial: List<Screen> = listOf(Screen.Home)) {
    val stack = mutableStateListOf<Screen>().apply { addAll(initial) }
    val current get() = stack.last()
    fun go(s: Screen) { stack.add(s) }
    fun back() { if (stack.size > 1) stack.removeAt(stack.lastIndex) }
    fun replace(s: Screen) { back(); go(s) }

    companion object {
        /**
         * Keeps the back stack when Android recreates the activity – e.g. after the
         * camera or photo picker was open and the system reclaimed memory.
         */
        val Saver = listSaver<Nav, String>(
            save = { nav -> nav.stack.map(::encode) },
            restore = { saved -> Nav(saved.mapNotNull(::decode).ifEmpty { listOf(Screen.Home) }) },
        )

        private fun encode(s: Screen) = when (s) {
            Screen.Home -> "home"
            Screen.Report -> "report"
            is Screen.Detail -> "detail:${s.id}"
            Screen.Analytics -> "analytics"
            Screen.ModelInfo -> "model"
            Screen.Notifications -> "notifications"
        }

        private fun decode(s: String): Screen? = when {
            s == "home" -> Screen.Home
            s == "report" -> Screen.Report
            s.startsWith("detail:") -> Screen.Detail(s.removePrefix("detail:"))
            s == "analytics" -> Screen.Analytics
            s == "model" -> Screen.ModelInfo
            s == "notifications" -> Screen.Notifications
            else -> null
        }
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as CivicFixApp
        setContent { CivicFixTheme { Root(app) } }
    }
}

@Composable
private fun Root(app: CivicFixApp) {
    var session by remember { mutableStateOf<Session?>(null) }
    var checking by remember { mutableStateOf(true) }
    var offline by remember { mutableStateOf<String?>(null) }
    var retry by remember { mutableIntStateOf(0) }
    val nav = rememberSaveable(saver = Nav.Saver) { Nav() }

    // Restore the signed-in user from the saved token.
    LaunchedEffect(retry) {
        checking = true
        offline = null
        try {
            app.repo.loadMeta()
            session = app.repo.me()
        } catch (e: Exception) {
            offline = e.message
        }
        checking = false
    }

    val onSession: (Session?) -> Unit = {
        if (it == null) app.repo.logout()
        session = it
        nav.stack.clear(); nav.stack.add(Screen.Home)
    }

    when {
        checking -> return Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        offline != null && session == null -> return OfflineScreen(app, offline!!) { retry++ }
    }
    val s = session ?: return LoginScreen(app, onLogin = onSession)

    NotificationPoller(app, s)
    BackHandler(enabled = nav.stack.size > 1) { nav.back() }
    when (val screen = nav.current) {
        Screen.Home -> if (s.role == Role.CITIZEN) CitizenHomeScreen(app, s, nav, onLogout = { onSession(null) })
                       else OfficerDashboardScreen(app, s, nav, onLogout = { onSession(null) })
        Screen.Report -> ReportWizardScreen(app, s, nav)
        is Screen.Detail -> ComplaintDetailScreen(app, s, nav, screen.id)
        Screen.Analytics -> AnalyticsScreen(app, nav)
        Screen.ModelInfo -> ModelInfoScreen(app, s, nav)
        Screen.Notifications -> NotificationsScreen(app, nav)
    }
}

/** Shows new server notifications (deadline warnings, escalations, resolved…) as phone notifications. */
@Composable
private fun NotificationPoller(app: CivicFixApp, session: Session) {
    val context = LocalContext.current
    LaunchedEffect(session.id) {
        val prefs = context.getSharedPreferences("notifications", Context.MODE_PRIVATE)
        while (true) {
            runCatching {
                val seen = prefs.getInt("seen_${session.id}", 0)
                val list = app.repo.notifications()
                list.filter { !it.read && it.id > seen }.sortedBy { it.id }.takeLast(5).forEach {
                    Notifier.notify(context, it.id, it.title, it.body)
                }
                list.maxOfOrNull { it.id }?.let { prefs.edit().putInt("seen_${session.id}", maxOf(seen, it)).apply() }
            }
            delay(30_000)
        }
    }
}

@Composable
private fun OfflineScreen(app: CivicFixApp, message: String, onRetry: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Text("📡", style = MaterialTheme.typography.headlineMedium)
        Text("Cannot reach the CivicFix server", style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Text(message, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
        ServerAddressField(app)
        PrimaryButton("Try again", onRetry)
    }
}
