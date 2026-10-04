package com.civicfix.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.civicfix.app.CivicFixApp
import com.civicfix.app.Nav
import com.civicfix.app.domain.DAY_MS
import com.civicfix.app.ui.components.AppScaffold
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
import kotlinx.coroutines.withContext

/** Government analytics (report section 5.6). */
@Composable
fun AnalyticsScreen(app: CivicFixApp, nav: Nav) {
    val all by app.repo.complaints.collectAsState()
    val now = app.clock.now()
    val open = all.filter { it.status.isOpen }
    val overdue = all.count { it.isOverdue(now) }
    val resolved = all.filter { it.resolvedAt != null }
    val onTime = resolved.count { it.resolvedAt!! <= it.dueAt }
    val verified = all.count { it.closedAt != null }
    val reopened = all.count { it.reopenCount > 0 }

    AppScaffold("Analytics", subtitle = "City-wide performance", onBack = nav::back) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatTile("Total", "${all.size}", MaterialTheme.colorScheme.primary, Modifier.weight(1f), "📋")
                StatTile("Open", "${open.size}", Info, Modifier.weight(1f), "📂")
                StatTile("Overdue", "$overdue", Danger, Modifier.weight(1f), "⏰")
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatTile("On-time", if (resolved.isEmpty()) "–" else "${onTime * 100 / resolved.size}%", Ok, Modifier.weight(1f), "🎯")
                StatTile("Verified", "$verified", Ok, Modifier.weight(1f), "✅")
                StatTile("Reopened", "$reopened", Amber, Modifier.weight(1f), "🔁")
            }
            SectionCard("Overview", icon = "📈") {
                Metric("Escalated (open)", "${open.count { it.escalationLevel > 0 }}")
                Metric("Resolved within SLA", if (resolved.isEmpty()) "–" else "${onTime * 100 / resolved.size}%")
                Metric("Verified fixed by citizens", "$verified")
                Metric("Reopened (not actually fixed)", "$reopened")
            }

            SectionCard("Complaints by category", icon = "🗂️") {
                val byCat = all.groupingBy { it.category }.eachCount().entries.sortedByDescending { it.value }
                val max = byCat.maxOfOrNull { it.value }?.toFloat() ?: 1f
                byCat.forEach { (k, v) -> val c = app.ref.category(k); BarRow("${c.emoji} ${c.label}", v.toFloat(), max, "$v", categoryColor(k)) }
            }

            SectionCard("Average resolution time by department", icon = "⏱️") {
                val byDept = resolved.groupBy { it.departmentId }
                    .mapValues { (_, l) -> l.map { (it.resolvedAt!! - it.createdAt).toFloat() / DAY_MS }.average().toFloat() }
                val max = byDept.values.maxOrNull() ?: 1f
                if (byDept.isEmpty()) Text("No resolved complaints yet.")
                byDept.entries.sortedByDescending { it.value }.forEach { (d, days) ->
                    BarRow(app.ref.department(d).name, days, max, "%.1f days".format(days))
                }
            }

            SectionCard("Department workload (open / overdue)", icon = "🏢") {
                val byDept = open.groupBy { it.departmentId }
                val max = byDept.values.maxOfOrNull { it.size }?.toFloat() ?: 1f
                if (byDept.isEmpty()) Text("No open complaints.")
                byDept.entries.sortedByDescending { it.value.size }.forEach { (d, l) ->
                    val od = l.count { it.isOverdue(now) }
                    BarRow(app.ref.department(d).name, l.size.toFloat(), max, "${l.size} open · $od overdue", if (od > 0) Danger else MaterialTheme.colorScheme.primary)
                }
            }

            SectionCard("Hotspots – localities with most complaints", icon = "🔥", accent = Danger) {
                val byLoc = all.groupBy { it.localityId }.entries.sortedByDescending { it.value.size }.take(6)
                val max = byLoc.maxOfOrNull { it.value.size }?.toFloat() ?: 1f
                byLoc.forEach { (id, l) ->
                    val p = app.ref.pathOf(id)
                    BarRow("${p?.locality?.name ?: id}, ${p?.city?.name ?: ""}", l.size.toFloat(), max, "${l.size}")
                }
            }

            SectionCard("Repeated problems at the same place", icon = "🔁", accent = Amber) {
                val repeated = all.groupBy { it.localityId to it.category }.filter { it.value.size + it.value.sumOf { c -> c.supporters.size } >= 2 }
                if (repeated.isEmpty()) Text("None detected.")
                repeated.forEach { (key, l) ->
                    val reports = l.size + l.sumOf { it.supporters.size }
                    Text("• ${app.ref.category(key.second).label} at ${app.ref.pathOf(key.first)?.locality?.name}: $reports reports")
                }
            }
        }
    }
}

@Composable
private fun Metric(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, modifier = Modifier.weight(1f))
        Text(value, fontWeight = FontWeight.Bold)
    }
}

/** Shows the trained models, datasets used, and demo controls. */
@Composable
fun ModelInfoScreen(app: CivicFixApp, nav: Nav) {
    var info by remember { mutableStateOf<Pair<String, String>?>(null) }
    var offsetDays by remember { mutableIntStateOf((app.clock.offsetMs / DAY_MS).toInt()) }
    LaunchedEffect(Unit) {
        info = withContext(Dispatchers.Default) {
            val ai = app.ai
            val m = ai.text.metrics
            val text = "TF-IDF + Logistic Regression, trained on ${m.optInt("train_rows")} complaint texts.\n" +
                "Category accuracy: ${pct(m.optDouble("category_accuracy"))} (template test split), " +
                "${pct(m.optDouble("handwritten_category_accuracy"))} on ${m.optInt("handwritten_rows")} hand-written complaints.\n" +
                "Severity accuracy: ${pct(m.optDouble("handwritten_severity_accuracy"))} on hand-written complaints (plus safety-keyword rules)."
            val img = ai.image
            val image = if (img.available)
                "MobileNetV3-Large (ONNX Runtime, on-device) fine-tuned on ${img.trainImages ?: "?"} real photos, cleaned with CLIP.\n" +
                    "Classes: ${img.labels.size}. Held-out test set: ${img.testImages ?: "?"} real photos – accuracy " +
                    "${img.testAccuracy?.let { pct(it) } ?: "n/a"}, macro-F1 ${img.testMacroF1?.let { pct(it) } ?: "n/a"}."
            else "Not bundled yet. Train it with ml/03_train_image_classifier.py – it is copied into the app automatically.\n(${ai.image.loadError ?: ""})"
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
                Text("• GitHub – Team16Project Street-Light-Dataset (Chennai)")
                Text("• Wikimedia Commons + Openverse – openly-licensed photos of leaks, drains, blockages, damaged infrastructure (licences in ml/data/raw/web/attribution.csv)")
                Text("• OpenAI CLIP ViT-L/14 used to clean labels and remove duplicates")
                Text("• CivicFix complaint-text corpus (generated, English + Hinglish)")
                Text("See PROJECT_DOCUMENTATION.md for details.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            SectionCard("Demo controls", icon = "🧪") {
                Text("Simulated time offset: +$offsetDays day(s)")
                Text("Now: ${formatDate(app.clock.now())}", style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        app.clock.offsetMs += DAY_MS; offsetDays++; app.repo.refreshSla()
                    }) { Text("+1 day") }
                    Button(onClick = {
                        app.clock.offsetMs += 3 * DAY_MS; offsetDays += 3; app.repo.refreshSla()
                    }) { Text("+3 days") }
                    OutlinedButton(onClick = { app.repo.resetDemo(); offsetDays = 0 }) { Text("Reset demo") }
                }
                Text("Advance time to watch overdue complaints escalate automatically.", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

private fun pct(v: Double) = if (v.isNaN()) "n/a" else "%.1f%%".format(v * 100)
