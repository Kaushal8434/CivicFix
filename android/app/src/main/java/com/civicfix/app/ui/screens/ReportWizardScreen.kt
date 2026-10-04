package com.civicfix.app.ui.screens

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.civicfix.app.CivicFixApp
import com.civicfix.app.Nav
import com.civicfix.app.Screen
import com.civicfix.app.data.City
import com.civicfix.app.data.Complaint
import com.civicfix.app.data.Locality
import com.civicfix.app.data.LocationPath
import com.civicfix.app.data.Session
import com.civicfix.app.data.TimelineEvent
import com.civicfix.app.data.Ward
import com.civicfix.app.data.Zone
import com.civicfix.app.domain.DAY_MS
import com.civicfix.app.domain.nearestLocality
import com.civicfix.app.ml.AiResult
import com.civicfix.app.ui.components.AppScaffold
import com.civicfix.app.ui.components.BarRow
import com.civicfix.app.ui.components.ComplaintCard
import com.civicfix.app.ui.components.PhotoInput
import com.civicfix.app.ui.components.SectionCard
import com.civicfix.app.ui.components.Selector
import com.civicfix.app.ui.theme.Amber
import com.civicfix.app.ui.theme.Danger
import com.civicfix.app.util.Gps
import com.civicfix.app.util.Photos
import com.civicfix.app.util.formatDay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val STEPS = listOf("Location", "Evidence", "Category & routing", "Review")

/** Citizen workflow (report section 4.1): Location → Problem → Evidence → Submit. */
@Composable
fun ReportWizardScreen(app: CivicFixApp, session: Session, nav: Nav) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var step by remember { mutableIntStateOf(0) }

    // Step 1 – location
    var city by remember { mutableStateOf<City?>(null) }
    var zone by remember { mutableStateOf<Zone?>(null) }
    var ward by remember { mutableStateOf<Ward?>(null) }
    var locality by remember { mutableStateOf<Locality?>(null) }
    var landmark by remember { mutableStateOf("") }
    var gps by remember { mutableStateOf<Pair<Double, Double>?>(null) }
    var gpsMsg by remember { mutableStateOf<String?>(null) }
    var gpsBusy by remember { mutableStateOf(false) }

    // Step 2 – evidence
    var photo by remember { mutableStateOf<String?>(null) }
    var description by remember { mutableStateOf("") }

    // Step 3 – AI + category
    var ai by remember { mutableStateOf<AiResult?>(null) }
    var aiBusy by remember { mutableStateOf(false) }
    var aiRanFor by remember { mutableStateOf<Pair<String?, String>?>(null) }
    var category by remember { mutableStateOf<String?>(null) }
    var severity by remember { mutableStateOf("medium") }

    fun applyPath(p: LocationPath) {
        city = p.city; zone = p.zone; ward = p.ward; locality = p.locality
    }

    val locate = {
        gpsBusy = true
        gpsMsg = "Getting your location…"
        Gps.current(context) { loc ->
            gpsBusy = false
            if (loc == null) {
                gpsMsg = "Could not get GPS location. Please select manually."
            } else {
                gps = loc.latitude to loc.longitude
                val near = nearestLocality(app.ref, loc.latitude, loc.longitude)
                if (near != null) {
                    applyPath(near.first)
                    val km = near.second / 1000
                    gpsMsg = if (km < 5) "📍 Matched to ${near.first.locality.name} (${"%.1f".format(km)} km)"
                    else "📍 GPS ${"%.4f, %.4f".format(loc.latitude, loc.longitude)} is outside the demo area – nearest locality pre-selected (${"%.0f".format(km)} km). Please adjust."
                }
            }
        }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        if (res.values.any { it }) {
            locate()
        } else {
            gpsMsg = "Location permission denied – select manually."
        }
    }

    val path = if (city != null && zone != null && ward != null && locality != null) LocationPath(city!!, zone!!, ward!!, locality!!) else null

    // Run the AI when entering step 3 (and again if photo/description changed).
    LaunchedEffect(step) {
        if (step == 2 && aiRanFor != (photo to description)) {
            aiBusy = true
            val result = withContext(Dispatchers.Default) {
                val bmp = photo?.let { Photos.loadBitmap(it) }
                app.ai.analyze(bmp, description)
            }
            ai = result
            aiRanFor = photo to description
            if (result != null) {
                category = result.category
                severity = result.severity
            }
            aiBusy = false
        }
    }

    AppScaffold(title = "Report – ${STEPS[step]}", onBack = { if (step > 0) step-- else nav.back() }) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            LinearProgressIndicator(progress = { (step + 1) / STEPS.size.toFloat() }, modifier = Modifier.fillMaxWidth())
            Text("Step ${step + 1} of ${STEPS.size}", style = MaterialTheme.typography.labelMedium)

            when (step) {
                0 -> {
                    SectionCard("Where is the problem?") {
                        OutlinedButton(onClick = {
                            if (Gps.hasPermission(context)) locate()
                            else permission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                        }, enabled = !gpsBusy, modifier = Modifier.fillMaxWidth()) { Text("📍 Use my current location (GPS)") }
                        gpsMsg?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                        Text("…or select City → Zone → Ward → Locality", style = MaterialTheme.typography.bodySmall)
                        Selector("City", app.ref.cities, city, { it.name }, { city = it; zone = null; ward = null; locality = null })
                        Selector("Town / Zone", city?.zones.orEmpty(), zone, { it.name }, { zone = it; ward = null; locality = null }, enabled = city != null)
                        Selector("Ward / Area", zone?.wards.orEmpty(), ward, { it.name }, { ward = it; locality = null }, enabled = zone != null)
                        Selector("Locality", ward?.localities.orEmpty(), locality, { it.name }, { locality = it }, enabled = ward != null)
                        OutlinedTextField(landmark, { landmark = it }, label = { Text("Nearby landmark (optional)") }, modifier = Modifier.fillMaxWidth())
                    }
                    Button(onClick = { step = 1 }, enabled = path != null, modifier = Modifier.fillMaxWidth()) { Text("Next") }
                }

                1 -> {
                    SectionCard("Photo evidence") {
                        PhotoInput("Before photo", photo, "before") { photo = it }
                        Text("The on-device AI uses the photo to suggest the category.", style = MaterialTheme.typography.bodySmall)
                    }
                    SectionCard("Describe the problem") {
                        OutlinedTextField(
                            description, { description = it },
                            label = { Text("e.g. Deep pothole near the school gate, bikes skidding") },
                            minLines = 3, modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    Button(
                        onClick = { step = 2 },
                        enabled = photo != null || description.trim().length >= 5,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Analyse & continue") }
                    if (photo == null) Text("A photo is strongly recommended – it is required evidence for faster action.", style = MaterialTheme.typography.bodySmall, color = Amber)
                }

                2 -> {
                    SectionCard("🤖 AI suggestion") {
                        when {
                            aiBusy -> Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(Modifier.padding(end = 12.dp))
                                Text("Analysing photo and description…")
                            }
                            ai == null -> Text("AI could not make a suggestion (no photo model bundled and no recognisable words). Please choose the category.")
                            else -> {
                                val r = ai!!
                                r.ranked.forEach { (k, p) ->
                                    val c = app.ref.category(k)
                                    BarRow("${c.emoji} ${c.label}", p, 1f, "${(p * 100).toInt()}%")
                                }
                                Text(
                                    "Based on: " + listOfNotNull("photo".takeIf { r.usedImage }, "description".takeIf { r.usedText }).joinToString(" + ") +
                                        if (!app.ai.image.available) "  (photo model not bundled yet)" else "",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                if (r.lowConfidence) Text("Low confidence – please confirm the category below.", color = Amber, style = MaterialTheme.typography.bodySmall)
                                r.safetyFlag?.let { Text("⚠️ Safety keyword \"$it\" detected – marked HIGH severity.", color = Danger, style = MaterialTheme.typography.bodySmall) }
                            }
                        }
                    }
                    SectionCard("Problem category") {
                        app.ref.categories.chunked(2).forEach { row ->
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                row.forEach { c ->
                                    FilterChip(selected = category == c.key, onClick = { category = c.key },
                                        label = { Text("${c.emoji} ${c.label}") }, modifier = Modifier.weight(1f))
                                }
                            }
                        }
                        Text("Severity", style = MaterialTheme.typography.labelLarge)
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf("low" to "Low", "medium" to "Medium", "high" to "High / safety risk").forEach { (k, l) ->
                                FilterChip(selected = severity == k, onClick = { severity = k }, label = { Text(l) })
                            }
                        }
                    }
                    if (category != null && path != null) {
                        val r = app.routing.route(category!!, severity, path)
                        SectionCard("🧭 Smart routing") {
                            Text(r.department.name, fontWeight = FontWeight.SemiBold)
                            Text(r.officeLabel, style = MaterialTheme.typography.bodySmall)
                            Text("Handled by: ${r.officerRole}", style = MaterialTheme.typography.bodySmall)
                            Text("Target resolution: ${r.slaDays} day(s) – by ${formatDay(app.clock.now() + r.slaDays * DAY_MS)}", fontWeight = FontWeight.SemiBold)
                        }
                        val similar = app.duplicates.findSimilar(app.repo.complaints.value, category!!, path.locality.id, gps?.first, gps?.second)
                        if (similar.isNotEmpty()) {
                            SectionCard("⚠️ Similar open complaint(s) nearby") {
                                Text("This may already be reported. You can support an existing complaint instead of creating a duplicate.", style = MaterialTheme.typography.bodySmall)
                                similar.take(3).forEach { c ->
                                    ComplaintCard(c, app.ref, app.clock.now()) { nav.go(Screen.Detail(c.id)) }
                                    OutlinedButton(onClick = {
                                        app.repo.update(c.id) {
                                            if (session.name in it.supporters || it.citizenName == session.name) it
                                            else it.copy(
                                                supporters = it.supporters + session.name,
                                                timeline = it.timeline + TimelineEvent(app.clock.now(), "Another citizen reported this", "${session.name} supported the complaint", "Citizen"),
                                            )
                                        }
                                        nav.replace(Screen.Detail(c.id))
                                    }, modifier = Modifier.fillMaxWidth()) { Text("👍 Support ${c.id} instead") }
                                }
                            }
                        }
                    }
                    Button(onClick = { step = 3 }, enabled = category != null && !aiBusy, modifier = Modifier.fillMaxWidth()) { Text("Next") }
                }

                3 -> {
                    val p = path!!
                    val cat = app.ref.category(category!!)
                    val r = app.routing.route(cat.key, severity, p)
                    SectionCard("Review your complaint") {
                        Text("${cat.emoji} ${cat.label}  ·  severity $severity", fontWeight = FontWeight.SemiBold)
                        Text("📍 ${p.label}" + if (landmark.isNotBlank()) " (near $landmark)" else "")
                        gps?.let { Text("GPS: %.5f, %.5f".format(it.first, it.second), style = MaterialTheme.typography.bodySmall) }
                        Text("📝 ${description.ifBlank { "(no description)" }}")
                        Text("➡️ ${r.department.name}")
                        Text("⏱️ Target: ${r.slaDays} day(s)")
                        if (photo != null) com.civicfix.app.ui.components.PhotoLarge(photo, "Photo")
                    }
                    Button(onClick = {
                        scope.launch {
                            val now = app.clock.now()
                            val id = app.repo.newId(now)
                            val c = Complaint(
                                id = id, citizenName = session.name,
                                cityId = p.city.id, zoneId = p.zone.id, wardId = p.ward.id, localityId = p.locality.id,
                                locationLabel = p.label, lat = gps?.first ?: p.locality.lat, lng = gps?.second ?: p.locality.lng,
                                landmark = landmark, category = cat.key, severity = severity, description = description.trim(),
                                beforePhoto = photo, departmentId = r.department.id, createdAt = now, dueAt = now + r.slaDays * DAY_MS,
                                aiSummary = ai?.summary(app.ref)?.let { s -> if (ai?.category != cat.key) "$s – citizen chose ${cat.label}" else s },
                                timeline = listOf(
                                    TimelineEvent(now, "Complaint submitted", "Complaint ID $id created", session.name),
                                    TimelineEvent(now, "Routed automatically", "${r.officeLabel}. Target date ${formatDay(now + r.slaDays * DAY_MS)}", "System"),
                                ),
                            )
                            app.repo.add(c)
                            nav.replace(Screen.Detail(id))
                        }
                    }, modifier = Modifier.fillMaxWidth()) { Text("✅ Submit complaint") }
                }
            }
        }
    }
}
