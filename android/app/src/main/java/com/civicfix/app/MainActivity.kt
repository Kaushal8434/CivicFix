package com.civicfix.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.civicfix.app.data.Role
import com.civicfix.app.data.Session
import com.civicfix.app.ui.screens.AnalyticsScreen
import com.civicfix.app.ui.screens.CitizenHomeScreen
import com.civicfix.app.ui.screens.ComplaintDetailScreen
import com.civicfix.app.ui.screens.LoginScreen
import com.civicfix.app.ui.screens.ModelInfoScreen
import com.civicfix.app.ui.screens.OfficerDashboardScreen
import com.civicfix.app.ui.screens.ReportWizardScreen
import com.civicfix.app.ui.theme.CivicFixTheme

sealed interface Screen {
    data object Home : Screen
    data object Report : Screen
    data class Detail(val id: String) : Screen
    data object Analytics : Screen
    data object ModelInfo : Screen
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
        }

        private fun decode(s: String): Screen? = when {
            s == "home" -> Screen.Home
            s == "report" -> Screen.Report
            s.startsWith("detail:") -> Screen.Detail(s.removePrefix("detail:"))
            s == "analytics" -> Screen.Analytics
            s == "model" -> Screen.ModelInfo
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
    var session by remember { mutableStateOf(app.loadSession()) }
    val nav = rememberSaveable(saver = Nav.Saver) { Nav() }
    val onSession: (Session?) -> Unit = {
        app.saveSession(it); session = it
        nav.stack.clear(); nav.stack.add(Screen.Home)
    }
    val s = session ?: return LoginScreen(app, onLogin = onSession)

    BackHandler(enabled = nav.stack.size > 1) { nav.back() }
    when (val screen = nav.current) {
        Screen.Home -> if (s.role == Role.CITIZEN) CitizenHomeScreen(app, s, nav, onLogout = { onSession(null) })
                       else OfficerDashboardScreen(app, s, nav, onLogout = { onSession(null) })
        Screen.Report -> ReportWizardScreen(app, s, nav)
        is Screen.Detail -> ComplaintDetailScreen(app, s, nav, screen.id)
        Screen.Analytics -> AnalyticsScreen(app, nav)
        Screen.ModelInfo -> ModelInfoScreen(app, nav)
    }
}
