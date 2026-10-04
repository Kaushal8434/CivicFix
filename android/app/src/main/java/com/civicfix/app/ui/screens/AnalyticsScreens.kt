package com.civicfix.app.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.civicfix.app.CivicFixApp
import com.civicfix.app.Nav
import com.civicfix.app.Screen
import com.civicfix.app.data.AppNotification
import com.civicfix.app.data.Role
import com.civicfix.app.data.Session
import com.civicfix.app.ui.components.AppScaffold
import com.civicfix.app.ui.components.Banner
import com.civicfix.app.ui.components.BarRow
import com.civicfix.app.ui.components.SectionCard
import com.civicfix.app.ui.components.StatTile
import com.civicfix.app.ui.theme.Amber
import com.civicfix.app.ui.theme.Danger
import com.civicfix.app.ui.theme.Info
import com.civicfix.app.ui.theme.Ok
import com.civicfix.app.ui.theme.categoryColor
import com.civicfix.app.util.formatDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Government analytics (report section 5.6) – live from the server. */
@Composable
fun AnalyticsScreen(app: CivicFixApp, nav: Nav) {
    var s by remember { mutableStateOf<JSONObject?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { runCatching { app.repo.stats() }.onSuccess { s = it }.onFailure { error = it.message } }

    AppScaffold("Analytics", subtitle = "City-wide performance", onBack = nav::back) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            error?.let { Banner(it, Danger, "📡") }
            val st = s ?: return@Column LinearProgressIndicator(Modifier.fillMaxWidth())
            val pct = if (st.isNull("onTimePct")) "–" else "${st.getInt("onTimePct")}%"
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatTile("Total", "${st.getInt("total")}", MaterialTheme.colorScheme.primary, Modifier.weight(1f), "📋")
                StatTile("Open", "${st.getInt("open")}", Info, Modifier.weight(1f), "📂")
                StatTile("Overdue", "${st.getInt("overdue")}", Danger, Modifier.weight(1f), "⏰")
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatTile("On-time", pct, Ok, Modifier.weight(1f), "🎯")
                StatTile("Escalated", "${st.getInt("escalated")}", Danger, Modifier.weight(1f), "🔺")
                StatTile("Reopened", "${st.getInt("reopened")}", Amber, Modifier.weight(1f), "🔁")
            }
            SectionCard("Complaints by category", icon = "🗂️") {
                val byCat = st.getJSONObject("byCategory")
                val rows = byCat.keys().asSequence().map { it to byCat.getJSONObject(it) }.sortedByDescending { it.second.getInt("count") }.toList()
                val max = rows.maxOfOrNull { it.second.getInt("count") }?.toFloat()?.coerceAtLeast(1f) ?: 1f
                rows.forEach { (k, o) -> BarRow("${app.ref.category(k).emoji} ${o.getString("label")}", o.getInt("count").toFloat(), max, "${o.getInt("count")}", categoryColor(k)) }
            }
            SectionCard("Departments (open / overdue / escalated)", icon = "🏢") {
                val byDept = st.getJSONObject("byDepartment")
                val rows = byDept.keys().asSequence().map { byDept.getJSONObject(it) }.filter { it.getInt("total") > 0 }.toList()
                val max = rows.maxOfOrNull { it.getInt("open") }?.toFloat()?.coerceAtLeast(1f) ?: 1f
                if (rows.isEmpty()) Text("No complaints yet.")
                rows.sortedByDescending { it.getInt("open") }.forEach { d ->
                    val avg = if (d.isNull("avgResolutionHours")) "" else " · avg ${d.getDouble("avgResolutionHours")} h"
                    BarRow(d.getString("agency"), d.getInt("open").toFloat(), max,
                        "${d.getInt("open")} / ${d.getInt("overdue")} / ${d.getInt("escalated")}$avg",
                        if (d.getInt("overdue") > 0) Danger else MaterialTheme.colorScheme.primary)
                }
            }
            SectionCard("Hotspots", icon = "🔥", accent = Danger) {
                val hot = st.getJSONArray("hotspots")
                val max = (0 until hot.length()).maxOfOrNull { hot.getJSONArray(it).getInt(1) }?.toFloat() ?: 1f
                (0 until hot.length()).forEach { i ->
                    val h = hot.getJSONArray(i)
                    BarRow(h.getString(0), h.getInt(1).toFloat(), max, "${h.getInt(1)} reports (incl. +1)")
                }
            }
        }
    }
}

/** AI models + (for supervisors / admin) the demo clock that shows warnings and escalation live. */
@Composable
fun ModelInfoScreen(app: CivicFixApp, session: Session, nav: Nav) {
    var info by remember { mutableStateOf<Pair<String, String>?>(null) }
    var clockMsg by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) {
        info = withContext(Dispatchers.Default) {
            val ai = app.ai
            val m = ai.text.metrics
            val text = "TF-IDF + Logistic Regression, trained on ${m.optInt("train_rows")} complaint texts.\n" +
                "Category accuracy: ${pct(m.optDouble("handwritten_category_accuracy"))} on ${m.optInt("handwritten_rows")} hand-written complaints.\n" +
                "Severity accuracy: ${pct(m.optDouble("handwritten_severity_accuracy"))} (plus safety-keyword rules)."
            val img = ai.image
            val image = if (img.available)
                "MobileNetV3-Large (ONNX Runtime, on-device) fine-tuned on ${img.trainImages ?: "?"} real photos, cleaned with CLIP.\n" +
                    "Held-out test set: ${img.testImages ?: "?"} real photos – accuracy ${img.testAccuracy?.let { pct(it) } ?: "n/a"}, " +
                    "macro-F1 ${img.testMacroF1?.let { pct(it) } ?: "n/a"}.\n" +
                    "The server uses the same network to compare photos for duplicate complaints (within 100 m)."
            else "Not bundled. (${img.loadError ?: ""})"
            text to image
        }
    }
    AppScaffold("AI models & demo", onBack = nav::back) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SectionCard("Photo recognition model", icon = "📷") { Text(info?.second ?: "Loading…", style = MaterialTheme.typography.bodyMedium) }
            SectionCard("Complaint text model", icon = "📝") { Text(info?.first ?: "Loading…", style = MaterialTheme.typography.bodyMedium) }
            SectionCard("Datasets", icon = "🗃️") {
                Text("• Kaggle – Road Issues Detection Dataset (potholes, garbage, signs, vandalism, parking)")
                Text("• Kaggle – Pothole Detection Dataset (potholes vs. normal roads)")
                Text("• Kaggle – Indian civic issues, waterlogging, stagnant water, uncovered gutters and open-manhole datasets (drainage)")
                Text("• GitHub – Team16Project Street-Light-Dataset (Chennai)")
                Text("• Wikimedia Commons + Openverse – openly-licensed photos (licences in ml/data/raw/web/attribution.csv)")
                Text("• OpenAI CLIP ViT-L/14 used to clean labels and remove duplicates")
                Text("• CivicFix complaint-text corpus (generated, English + Hinglish)")
            }
            if (session.role == Role.SUPERVISOR || session.role == Role.ADMIN) {
                SectionCard("Demo clock (server)", icon = "🧪") {
                    Text("Moves the server clock forward so missed deadlines, warnings and escalations happen immediately.",
                        style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(24 to "+1 day", 72 to "+3 days").forEach { (h, l) ->
                            Button(onClick = {
                                scope.launch {
                                    clockMsg = runCatching { app.repo.demoTime(h) }.fold(
                                        { "Clock +${it.getInt("offsetHours")} h · ${it.getJSONArray("escalated").length()} escalation step(s)" }, { it.message })
                                }
                            }) { Text(l) }
                        }
                        OutlinedButton(onClick = {
                            scope.launch { clockMsg = runCatching { app.repo.demoTime(0, reset = true) }.fold({ "Clock reset" }, { it.message }) }
                        }) { Text("Reset") }
                    }
                    clockMsg?.let { Text(it, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold) }
                    Text("Now (server): ${formatDate(app.repo.now())}", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
fun NotificationsScreen(app: CivicFixApp, nav: Nav) {
    var list by remember { mutableStateOf<List<AppNotification>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var tick by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(tick) { runCatching { app.repo.notifications() }.onSuccess { list = it }.onFailure { error = it.message } }
    val icon = mapOf("warning" to "⚠️", "escalation" to "🔺", "resolved" to "✅")
    AppScaffold("Notifications", onBack = nav::back, actions = {
        TextButton(onClick = { scope.launch { runCatching { app.repo.markAllRead() }; tick++ } }) {
            Text("Mark all read", color = androidx.compose.ui.graphics.Color.White)
        }
    }) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            error?.let { Banner(it, Danger, "📡") }
            val l = list ?: return@Column LinearProgressIndicator(Modifier.fillMaxWidth())
            if (l.isEmpty()) Text("No notifications yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            l.forEach { n ->
                Surface(
                    onClick = { n.complaintId?.let { nav.go(Screen.Detail(it)) } },
                    shape = RoundedCornerShape(16.dp),
                    color = if (n.read) MaterialTheme.colorScheme.surfaceContainerLow else MaterialTheme.colorScheme.primary.copy(alpha = 0.07f),
                    border = BorderStroke(1.dp, if (n.read) MaterialTheme.colorScheme.outlineVariant else MaterialTheme.colorScheme.primary),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("${icon[n.kind] ?: "🔔"} ${n.title}", fontWeight = FontWeight.SemiBold)
                        Text(n.body, style = MaterialTheme.typography.bodySmall)
                        Text(formatDate(n.createdAt), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

private fun pct(v: Double) = if (v.isNaN()) "n/a" else "%.1f%%".format(v * 100)
