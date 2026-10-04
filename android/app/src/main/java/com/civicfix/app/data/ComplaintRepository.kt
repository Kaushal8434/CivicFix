package com.civicfix.app.data

import android.content.Context
import com.civicfix.app.domain.DAY_MS
import com.civicfix.app.domain.DemoClock
import com.civicfix.app.domain.SlaEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.random.Random

/**
 * Local persistence (JSON file) for complaints. In a production deployment this
 * class would be replaced by calls to the backend REST API (see documentation).
 */
class ComplaintRepository(
    context: Context,
    private val ref: ReferenceData,
    private val sla: SlaEngine,
    private val clock: DemoClock,
) {
    private val file = File(context.filesDir, "complaints.json")
    private val _complaints = MutableStateFlow<List<Complaint>>(emptyList())
    val complaints: StateFlow<List<Complaint>> = _complaints

    init {
        _complaints.value = load()
        if (_complaints.value.isEmpty()) seedDemoData()
        refreshSla()
    }

    fun get(id: String) = _complaints.value.firstOrNull { it.id == id }

    fun newId(now: Long): String {
        val day = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date(now))
        var id: String
        do {
            id = "CF-$day-${Random.nextInt(1000, 9999)}"
        } while (get(id) != null)
        return id
    }

    fun add(c: Complaint) = save(_complaints.value + c)

    fun update(id: String, transform: (Complaint) -> Complaint) =
        save(_complaints.value.map { if (it.id == id) transform(it) else it })

    /** Apply SLA escalation rules to every open complaint (called on app start and on screen refresh). */
    fun refreshSla() {
        val now = clock.now()
        val updated = _complaints.value.map { sla.apply(it, now) }
        if (updated != _complaints.value) save(updated)
    }

    fun resetDemo() {
        clock.offsetMs = 0
        save(emptyList())
        seedDemoData()
    }

    private fun save(list: List<Complaint>) {
        _complaints.value = list
        file.writeText(JSONArray(list.map { it.toJson() }).toString())
    }

    private fun load(): List<Complaint> = try {
        if (!file.exists()) emptyList() else {
            val arr = JSONArray(file.readText())
            (0 until arr.length()).map { Complaint.fromJson(arr.getJSONObject(it)) }
        }
    } catch (e: Exception) {
        emptyList()
    }

    /** A few sample complaints so the officer dashboard and analytics are not empty on first run. */
    private fun seedDemoData() {
        val now = clock.now()
        data class Seed(val loc: String, val cat: String, val sev: String, val desc: String, val ageDays: Int, val status: Status)
        val seeds = listOf(
            Seed("del-connaught", "pothole_road_damage", "high", "Deep pothole near Connaught Place inner circle, bikes skidding.", 6, Status.IN_PROGRESS),
            Seed("har-cybercity", "streetlight", "medium", "Three street lights not working near DLF Cyber City phase 2.", 9, Status.ASSIGNED),
            Seed("del-karolbagh", "garbage", "medium", "Garbage not lifted for 4 days near Karol Bagh market.", 1, Status.NEW),
            Seed("har-sec15faridabad", "drainage", "high", "Drain overflowing, dirty water entering houses after rain.", 2, Status.NEW),
            Seed("har-panchkula5", "water_leakage", "medium", "Pipeline leaking on the main road in Sector 5, water wasted.", 12, Status.CLOSED),
            Seed("del-lajpat", "damaged_infrastructure", "high", "Manhole cover missing on the footpath near Lajpat Nagar metro gate.", 4, Status.RESOLVED),
            Seed("har-modeltownkarnal", "road_blockage", "medium", "Construction material dumped on the road, traffic jam every evening.", 5, Status.ASSIGNED),
        )
        val list = seeds.map { s ->
            val path = ref.pathOf(s.loc)!!
            val cat = ref.category(s.cat)
            val created = now - s.ageDays * DAY_MS
            val due = created + (cat.slaDays[s.sev] ?: 14) * DAY_MS
            val tl = mutableListOf(TimelineEvent(created, "Complaint submitted", "Routed to ${ref.department(cat.departmentId).name}", "Citizen"))
            if (s.status != Status.NEW) tl += TimelineEvent(created + DAY_MS / 2, "Assigned", "Assigned to Field Team A", "Officer")
            if (s.status in listOf(Status.IN_PROGRESS, Status.RESOLVED, Status.CLOSED)) tl += TimelineEvent(created + DAY_MS, "Work started", "Team on site", "Officer")
            if (s.status in listOf(Status.RESOLVED, Status.CLOSED)) tl += TimelineEvent(created + 2 * DAY_MS, "Marked resolved", "Repair completed", "Officer")
            if (s.status == Status.CLOSED) tl += TimelineEvent(created + 3 * DAY_MS, "Verified fixed", "Citizen confirmed the problem is fixed", "Citizen")
            Complaint(
                id = "CF-DEMO-${1000 + seeds.indexOf(s)}", citizenName = "Demo Citizen",
                cityId = path.city.id, zoneId = path.zone.id, wardId = path.ward.id, localityId = path.locality.id,
                locationLabel = path.label, lat = path.locality.lat, lng = path.locality.lng, landmark = "",
                category = s.cat, severity = s.sev, description = s.desc, beforePhoto = null,
                departmentId = cat.departmentId,
                assignedTeam = if (s.status != Status.NEW) "Field Team A" else null,
                status = s.status, createdAt = created, dueAt = due,
                resolvedAt = if (s.status in listOf(Status.RESOLVED, Status.CLOSED)) created + 2 * DAY_MS else null,
                closedAt = if (s.status == Status.CLOSED) created + 3 * DAY_MS else null,
                actionTaken = if (s.status in listOf(Status.RESOLVED, Status.CLOSED)) "Repair completed by field team" else null,
                timeline = tl,
            )
        }
        save(list)
    }
}
