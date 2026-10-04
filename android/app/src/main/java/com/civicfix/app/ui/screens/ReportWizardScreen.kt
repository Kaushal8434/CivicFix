package com.civicfix.app.ui.screens

import android.Manifest
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.civicfix.app.CivicFixApp
import com.civicfix.app.Nav
import com.civicfix.app.Screen
import com.civicfix.app.data.Complaint
import com.civicfix.app.data.LocationPath
import com.civicfix.app.data.Session
import com.civicfix.app.data.TimelineEvent
import com.civicfix.app.domain.HOUR_MS
import com.civicfix.app.data.DuplicateMatch
import com.civicfix.app.util.formatDate
import java.io.File
import com.civicfix.app.domain.nearestLocality
import com.civicfix.app.ml.AiResult
import com.civicfix.app.ui.components.AppScaffold
import com.civicfix.app.ui.components.Banner
import com.civicfix.app.ui.components.CategoryTile
import com.civicfix.app.ui.components.ComplaintCard
import com.civicfix.app.ui.components.ConfidenceRow
import com.civicfix.app.ui.components.InfoLine
import com.civicfix.app.ui.components.MapPicker
import com.civicfix.app.util.Geo
import com.civicfix.app.util.Place
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import com.civicfix.app.ui.components.PhotoInput
import com.civicfix.app.ui.components.PhotoLarge
import com.civicfix.app.ui.components.PrimaryButton
import com.civicfix.app.ui.components.SecondaryButton
import com.civicfix.app.ui.components.SectionCard
import com.civicfix.app.ui.components.Selector
import com.civicfix.app.ui.components.StepIndicator
import com.civicfix.app.ui.theme.Amber
import com.civicfix.app.ui.theme.Danger
import com.civicfix.app.ui.theme.Info
import com.civicfix.app.ui.theme.Ok
import com.civicfix.app.ui.theme.categoryColor
import com.civicfix.app.util.Gps
import com.civicfix.app.util.Maps
import com.civicfix.app.util.Photos
import com.civicfix.app.util.formatDay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val STEPS = listOf("Location", "Evidence", "Category", "Review")

/** Citizen workflow (report section 4.1): Location → Problem → Evidence → Submit. */
@Composable
fun ReportWizardScreen(app: CivicFixApp, session: Session, nav: Nav) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val ref = app.ref

    // Everything the citizen entered is saveable: it survives the camera / photo picker
    // and Android recreating the activity in the background.
    var step by rememberSaveable { mutableStateOf(0) }
    var cityId by rememberSaveable { mutableStateOf<String?>(null) }
    var zoneId by rememberSaveable { mutableStateOf<String?>(null) }
    var wardId by rememberSaveable { mutableStateOf<String?>(null) }
    var localityId by rememberSaveable { mutableStateOf<String?>(null) }
    var landmark by rememberSaveable { mutableStateOf("") }
    // The pin on the map (from GPS, a tap on the map, or an address search).
    var gpsLat by rememberSaveable { mutableStateOf<Double?>(null) }
    var gpsLng by rememberSaveable { mutableStateOf<Double?>(null) }
    var gpsMsg by rememberSaveable { mutableStateOf<String?>(null) }
    var address by rememberSaveable { mutableStateOf("") }
    var searchText by rememberSaveable { mutableStateOf("") }
    var manualWard by rememberSaveable { mutableStateOf(false) }
    var photo by rememberSaveable { mutableStateOf<String?>(null) }
    var description by rememberSaveable { mutableStateOf("") }
    var category by rememberSaveable { mutableStateOf<String?>(null) }
    var severity by rememberSaveable { mutableStateOf("medium") }

    var gpsBusy by remember { mutableStateOf(false) }
    var ai by remember { mutableStateOf<AiResult?>(null) }
    var aiBusy by remember { mutableStateOf(false) }
    var aiRanFor by remember { mutableStateOf<Pair<String?, String>?>(null) }
    var photoGuess by remember { mutableStateOf<Pair<String, Float>?>(null) }
    var photoGuessBusy by remember { mutableStateOf(false) }

    val city = ref.cities.firstOrNull { it.id == cityId }
    val zone = city?.zones?.firstOrNull { it.id == zoneId }
    val ward = zone?.wards?.firstOrNull { it.id == wardId }
    val locality = ward?.localities?.firstOrNull { it.id == localityId }
    val path = if (city != null && zone != null && ward != null && locality != null) LocationPath(city, zone, ward, locality) else null

    fun applyPath(p: LocationPath) {
        cityId = p.city.id; zoneId = p.zone.id; wardId = p.ward.id; localityId = p.locality.id
    }

    var submitting by remember { mutableStateOf(false) }
    var submitError by remember { mutableStateOf<String?>(null) }
    var duplicates by remember { mutableStateOf<List<DuplicateMatch>>(emptyList()) }
    var searchBusy by remember { mutableStateOf(false) }
    var searchResults by remember { mutableStateOf<List<Place>>(emptyList()) }

    /** Moves the pin, picks the ward used for routing, and looks up the street address. */
    fun setPin(lat: Double, lng: Double, knownAddress: String? = null) {
        gpsLat = lat; gpsLng = lng
        searchResults = emptyList()
        nearestLocality(ref, lat, lng)?.let { near ->
            applyPath(near.first)
            val km = near.second / 1000
            gpsMsg = if (km < 5) "Routed via ${near.first.locality.name} ward office (${"%.1f".format(km)} km)"
            else "This spot is outside the demo wards – routed to the nearest office, ${near.first.locality.name} (${"%.0f".format(km)} km)."
        }
        if (knownAddress != null) {
            address = knownAddress
        } else {
            scope.launch {
                withContext(Dispatchers.IO) { Geo.reverse(context, lat, lng) }?.let { address = it }
            }
        }
    }

    val locate = {
        gpsBusy = true
        gpsMsg = "Getting your location…"
        Gps.current(context) { loc ->
            gpsBusy = false
            if (loc == null) gpsMsg = "Could not get a GPS fix. Turn on location, tap the map, or type the address."
            else setPin(loc.latitude, loc.longitude)
        }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        if (res.values.any { it }) locate() else gpsMsg = "Location permission denied – tap the map or type the address."
    }

    fun searchAddress() {
        val q = searchText.trim()
        if (q.length < 3) return
        searchBusy = true
        scope.launch {
            val found = withContext(Dispatchers.IO) { Geo.search(context, q) }
            searchBusy = false
            searchResults = found
            when {
                found.isEmpty() -> gpsMsg = "Address not found on the map – it is saved as typed. Tap the map to place the pin."
                found.size == 1 -> setPin(found[0].lat, found[0].lng, found[0].label)
            }
            if (address.isBlank()) address = q
        }
    }

    // Live photo hint on the evidence step.
    LaunchedEffect(photo) {
        photoGuess = null
        val p = photo ?: return@LaunchedEffect
        photoGuessBusy = true
        photoGuess = withContext(Dispatchers.Default) {
            if (!app.ai.image.available) null else Photos.loadBitmap(p)?.let { app.ai.quickPhotoGuess(it) }
        }
        photoGuessBusy = false
    }

    // Full analysis when entering step 3 (and again if photo/description changed).
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

    val goBack: () -> Unit = { if (step > 0) step-- else nav.back() }
    BackHandler(enabled = step > 0) { step-- }

    val canNext = when (step) {
        0 -> path != null
        1 -> photo != null || description.trim().length >= 5
        2 -> category != null && !aiBusy
        else -> true
    }
    val nextLabel = when (step) {
        0 -> "Next: add evidence"
        1 -> "🤖  Analyse with AI"
        2 -> "Review complaint"
        else -> if (submitting) "Uploading…" else "✅  Submit complaint"
    }

    fun submit() {
        val p = path ?: return
        val cat = ref.category(category ?: return)
        submitting = true
        submitError = null
        scope.launch {
            try {
                val c = app.repo.create(
                    lat = gpsLat ?: p.locality.lat, lng = gpsLng ?: p.locality.lng, category = cat.key, severity = severity,
                    description = description.trim(), address = address.trim(), landmark = landmark.trim(), localityId = p.locality.id,
                    aiSummary = ai?.summary(ref)?.let { s -> if (ai?.category != cat.key) "$s – citizen chose ${cat.label}" else s },
                    photo = photo?.let(::File)?.takeIf { it.exists() },
                )
                nav.replace(Screen.Detail(c.id))
            } catch (e: Exception) {
                submitError = e.message
            }
            submitting = false
        }
    }

    // Server-side duplicate check (same problem within 100 m, photos compared by the AI).
    LaunchedEffect(step, category, gpsLat, gpsLng, photo) {
        val cat = category
        if (step != 2 || cat == null) return@LaunchedEffect
        val lat = gpsLat ?: path?.locality?.lat ?: return@LaunchedEffect
        val lng = gpsLng ?: path?.locality?.lng ?: return@LaunchedEffect
        duplicates = runCatching { app.repo.checkDuplicates(lat, lng, cat, photo?.let(::File)?.takeIf { it.exists() }) }.getOrDefault(emptyList())
    }

    AppScaffold(
        title = "Report a problem",
        subtitle = "Step ${step + 1} of ${STEPS.size} · ${STEPS[step]}",
        onBack = goBack,
        bottomBar = {
            Surface(shadowElevation = 12.dp, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                Row(
                    Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (step > 0) SecondaryButton("Back", { step-- }, Modifier.weight(0.4f))
                    PrimaryButton(nextLabel, {
                        if (step < 3) step++ else submit()
                    }, Modifier.weight(1f), enabled = canNext && !submitting, color = if (step == 3) Ok else null)
                }
            }
        },
    ) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            StepIndicator(STEPS, step)

            AnimatedContent(targetState = step, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "step") { s ->
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    when (s) {
                        0 -> {
                            SectionCard("Where is the problem?", icon = "📍") {
                                FilledTonalButton(
                                    onClick = {
                                        if (Gps.hasPermission(context)) locate()
                                        else permission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                                    },
                                    enabled = !gpsBusy,
                                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                                    shape = RoundedCornerShape(14.dp),
                                ) {
                                    if (gpsBusy) {
                                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                        Spacer(Modifier.width(10.dp))
                                    }
                                    Text("🛰️  Use my current location", fontWeight = FontWeight.SemiBold)
                                }

                                // Type an address / place and find it on the map
                                OutlinedTextField(
                                    searchText, { searchText = it },
                                    label = { Text("Search or type the address") },
                                    placeholder = { Text("e.g. Lajpat Nagar Metro Station, Delhi") },
                                    singleLine = true, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth(),
                                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                                    keyboardActions = KeyboardActions(onSearch = { searchAddress() }),
                                    trailingIcon = {
                                        if (searchBusy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                                        else TextButton(onClick = { searchAddress() }, enabled = searchText.trim().length >= 3) { Text("Find") }
                                    },
                                )
                                if (searchResults.size > 1) {
                                    Text("Pick the right place:", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    searchResults.forEach { pl ->
                                        Surface(
                                            onClick = { setPin(pl.lat, pl.lng, pl.label) },
                                            shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainer,
                                            modifier = Modifier.fillMaxWidth(),
                                        ) { Text("📍 ${pl.label}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(10.dp), maxLines = 2) }
                                    }
                                }

                                MapPicker(gpsLat, gpsLng, onPick = { la, ln -> setPin(la, ln) })
                                Text("Tap the map or drag the pin to the exact spot.", style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)

                                OutlinedTextField(
                                    address, { address = it }, label = { Text("Address (filled from the map – edit if needed)") },
                                    minLines = 2, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth(),
                                )
                                OutlinedTextField(
                                    landmark, { landmark = it }, label = { Text("Nearby landmark (optional)") },
                                    singleLine = true, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth(),
                                )
                                gpsMsg?.let { Banner(it, if (gpsLat != null) Ok else Amber, if (gpsLat != null) "🧭" else "ℹ️") }
                                val lat = gpsLat ?: locality?.lat
                                val lng = gpsLng ?: locality?.lng
                                if (lat != null && lng != null) {
                                    TextButton(onClick = { Maps.openGoogleMaps(context, lat, lng, address.ifBlank { locality?.name ?: "Selected location" }) }) {
                                        Text("🗺️  Check this spot on Google Maps")
                                    }
                                }
                            }

                            // Ward used for routing: filled automatically from the pin, can be set by hand.
                            SectionCard("Ward office (for routing)", icon = "🏛️") {
                                if (path != null && !manualWard) {
                                    Text(path.label, style = MaterialTheme.typography.bodyMedium)
                                    TextButton(onClick = { manualWard = true }) { Text("Change ward manually") }
                                } else {
                                    if (path == null) Text("Drop a pin on the map, or choose the ward yourself:", style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Selector("City / State", ref.cities, city, { it.name }, { cityId = it.id; zoneId = null; wardId = null; localityId = null })
                                    Selector("Town / Zone", city?.zones.orEmpty(), zone, { it.name }, { zoneId = it.id; wardId = null; localityId = null }, enabled = city != null)
                                    Selector("Ward / Area", zone?.wards.orEmpty(), ward, { it.name }, { wardId = it.id; localityId = null }, enabled = zone != null)
                                    Selector("Locality", ward?.localities.orEmpty(), locality, { it.name }, { localityId = it.id }, enabled = ward != null)
                                    if (manualWard && path != null) TextButton(onClick = { manualWard = false }) { Text("Done") }
                                }
                            }
                        }

                        1 -> {
                            SectionCard("Photo evidence", icon = "📷") {
                                PhotoInput("Before photo", photo, "before", { photo = it }) {
                                    AiPhotoChip(app, photoGuess, photoGuessBusy)
                                }
                                Text("Take the photo close enough that the problem fills most of the frame – the on-device AI uses it to pick the category.",
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            SectionCard("Describe the problem", icon = "📝") {
                                OutlinedTextField(
                                    description, { description = it },
                                    placeholder = { Text("e.g. Deep pothole near the school gate, bikes skidding") },
                                    minLines = 3, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth(),
                                )
                                Text("English or Hinglish both work. Mention dangers (children, accident, live wire…) so it is prioritised.",
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (photo == null) Banner("A photo is strongly recommended – it is the evidence officers act on.", Amber, "💡")
                        }

                        2 -> {
                            SectionCard("AI suggestion", icon = "🤖", accent = Info) {
                                when {
                                    aiBusy -> {
                                        Text("Analysing photo and description…", style = MaterialTheme.typography.bodyMedium)
                                        LinearProgressIndicator(Modifier.fillMaxWidth())
                                    }
                                    ai == null -> Text("The AI could not make a suggestion. Please choose the category below.")
                                    else -> {
                                        val r = ai!!
                                        r.ranked.forEachIndexed { i, (k, p) -> ConfidenceRow(ref.category(k), p, i == 0) }
                                        Text(
                                            "Based on " + listOfNotNull("photo".takeIf { r.usedImage }, "description".takeIf { r.usedText }).joinToString(" + ") +
                                                if (!app.ai.image.available) " (photo model not bundled)" else "",
                                            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                        if (r.photoShowsNoIssue) Banner("The photo does not clearly show a civic problem. Retake it closer, or pick the category yourself.", Amber, "🔍")
                                        else if (r.lowConfidence) Banner("Low confidence – please confirm the category below.", Amber, "⚠️")
                                        r.safetyFlag?.let { Banner("Safety keyword \"$it\" detected – marked HIGH severity.", Danger, "🚨") }
                                    }
                                }
                            }
                            SectionCard("Problem category", icon = "🗂️") {
                                val hints = ai?.ranked?.toMap().orEmpty()
                                ref.categories.chunked(2).forEach { row ->
                                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                        row.forEach { c ->
                                            CategoryTile(c, category == c.key, Modifier.weight(1f), hints[c.key]) { category = c.key }
                                        }
                                        if (row.size == 1) Spacer(Modifier.weight(1f))
                                    }
                                }
                            }
                            SectionCard("Severity", icon = "🚦", accent = Danger) {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    listOf(Triple("low", "Low", Ok), Triple("medium", "Medium", Amber), Triple("high", "High / risk", Danger)).forEach { (k, l, col) ->
                                        SeverityOption(l, col, severity == k, Modifier.weight(1f)) { severity = k }
                                    }
                                }
                            }
                            val cat = category
                            if (cat != null && path != null) {
                                val wf = app.repo.workflowFor(cat)
                                if (wf != null) {
                                    val hours = wf.slaHours[severity] ?: 48
                                    SectionCard("Smart routing", icon = "🧭", accent = categoryColor(cat)) {
                                        Text(wf.agency, style = MaterialTheme.typography.titleSmall)
                                        Text("Ward office: ${path.label}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        InfoLine("Assigned to", wf.chain.first())
                                        InfoLine("Supervisor", wf.chain.getOrElse(1) { "-" })
                                        InfoLine("Deadline", "$hours h · ${formatDate(app.repo.now() + hours * HOUR_MS)}")
                                        Text("If the deadline is missed the officer is warned and the supervisor told; late by 2× it goes to the ${wf.chain.getOrElse(2) { "higher officer" }}.",
                                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                                if (duplicates.isNotEmpty()) {
                                    SectionCard("Already reported nearby?", icon = "👥", accent = Amber) {
                                        Text("These open complaints are within 100 m" + (if (photo != null) " and the AI compared the photos" else "") +
                                            ". If it is the same problem, add your +1 – it raises the priority instead of creating a duplicate.",
                                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        duplicates.forEach { m ->
                                            val c = m.complaint
                                            Banner(
                                                (if (m.verdict == "same") "Very likely the same problem" else "Possibly the same problem") +
                                                    " · ${m.distanceM} m away" + (m.similarity?.let { " · photo match ${(it * 100).toInt()}%" } ?: ""),
                                                if (m.verdict == "same") Danger else Amber, "🤖",
                                            )
                                            ComplaintCard(c, ref, app.repo.now()) { nav.go(Screen.Detail(c.id)) }
                                            SecondaryButton(if (c.hasSupported) "You already supported this" else "👍  +1 – I'm facing this too (${c.supportCount})", {
                                                scope.launch {
                                                    runCatching { app.repo.support(c.id) }
                                                    nav.replace(Screen.Detail(c.id))
                                                }
                                            }, enabled = !c.hasSupported && !c.isMine)
                                        }
                                    }
                                }
                            }
                        }

                        else -> {
                            val p = path
                            val cat = category?.let(ref::category)
                            if (p != null && cat != null) {
                                val wf = app.repo.workflowFor(cat.key)
                                if (photo != null) PhotoLarge(photo, "Evidence")
                                SectionCard(cat.label, icon = cat.emoji, accent = categoryColor(cat.key)) {
                                    InfoLine("Severity", severity.replaceFirstChar { it.uppercase() })
                                    wf?.let {
                                        InfoLine("Department", it.agency)
                                        InfoLine("Deadline", "${it.slaHours[severity] ?: 48} hours")
                                    }
                                    HorizontalDivider()
                                    if (address.isNotBlank()) Text("📍 ${address.trim()}", style = MaterialTheme.typography.bodyMedium)
                                    Text((if (address.isBlank()) "📍 " else "🏛️ ") + p.label + if (landmark.isNotBlank()) " (near $landmark)" else "",
                                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    if (gpsLat != null && gpsLng != null) Text("GPS %.5f, %.5f".format(gpsLat, gpsLng), style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text("📝 ${description.ifBlank { "(no description)" }}", style = MaterialTheme.typography.bodyMedium)
                                }
                                Banner("You will get a notification when the department marks it resolved, and you confirm whether it is really fixed.", Info, "🔔")
                                submitError?.let { Banner(it, Danger, "⚠️") }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun AiPhotoChip(app: CivicFixApp, guess: Pair<String, Float>?, busy: Boolean) {
    if (!app.ai.image.available) return
    Surface(color = Color.Black.copy(alpha = 0.55f), shape = RoundedCornerShape(50)) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            when {
                busy -> {
                    CircularProgressIndicator(Modifier.size(14.dp), color = Color.White, strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("AI is looking…", color = Color.White, fontSize = 13.sp)
                }
                guess != null -> {
                    val c = app.ref.category(guess.first)
                    val text = when {
                        guess.second < 0.4f -> "🤖 Not sure yet – add a description"
                        guess.first == "other" -> "🤖 No clear civic problem seen"
                        else -> "🤖 Looks like ${c.emoji} ${c.label} · ${(guess.second * 100).toInt()}%"
                    }
                    Text(text, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
private fun SeverityOption(label: String, color: Color, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Surface(
        onClick = onClick, modifier = modifier.heightIn(min = 52.dp),
        shape = RoundedCornerShape(14.dp),
        color = if (selected) color.copy(alpha = 0.14f) else MaterialTheme.colorScheme.surfaceContainerLowest,
        border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) color else MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Text("●", color = color, fontSize = 14.sp)
            Text(label, style = MaterialTheme.typography.labelLarge, color = if (selected) color else MaterialTheme.colorScheme.onSurface)
        }
    }
}
