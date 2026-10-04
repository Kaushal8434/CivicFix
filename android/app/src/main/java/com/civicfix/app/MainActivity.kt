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

class Nav {
    val stack = mutableStateListOf<Screen>(Screen.Home)
    val current get() = stack.last()
    fun go(s: Screen) { stack.add(s) }
    fun back() { if (stack.size > 1) stack.removeAt(stack.lastIndex) }
    fun replace(s: Screen) { back(); go(s) }
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
    val nav = remember { Nav() }
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
