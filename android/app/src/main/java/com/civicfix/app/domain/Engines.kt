package com.civicfix.app.domain

import android.content.Context
import com.civicfix.app.data.Complaint
import com.civicfix.app.data.Department
import com.civicfix.app.data.LocationPath
import com.civicfix.app.data.ReferenceData
import com.civicfix.app.data.Status
import com.civicfix.app.data.TimelineEvent
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

const val DAY_MS = 24L * 60 * 60 * 1000

/**
 * App clock with a "demo offset" so SLA expiry and escalation can be shown
 * live in a presentation (Settings → simulate +1 day).
 */
class DemoClock(context: Context) {
    private val prefs = context.getSharedPreferences("clock", Context.MODE_PRIVATE)
    var offsetMs: Long
        get() = prefs.getLong("offset", 0L)
        set(v) = prefs.edit().putLong("offset", v).apply()

    fun now() = System.currentTimeMillis() + offsetMs
}

data class RoutingResult(
    val department: Department,
    val officeLabel: String,
    val officerRole: String,
    val slaDays: Int,
)

/** Report → ROUTE: category decides the department, the ward decides which office of it. */
class RoutingEngine(private val ref: ReferenceData) {
    fun route(categoryKey: String, severity: String, path: LocationPath): RoutingResult {
        val cat = ref.category(categoryKey)
        val dept = ref.department(cat.departmentId)
        val sla = cat.slaDays[severity] ?: cat.slaDays["medium"] ?: 14
        return RoutingResult(
            department = dept,
            officeLabel = "${dept.name} – ${path.zone.name} office, ${path.ward.name}, ${path.city.name}",
            officerRole = dept.officerRole,
            slaDays = sla,
        )
    }
}

/** SLA tracking + automatic escalation of overdue complaints. */
class SlaEngine(private val ref: ReferenceData) {

    fun escalationLevelFor(c: Complaint, now: Long): Int {
        if (!c.status.isOpen) return c.escalationLevel
        if (now <= c.dueAt) return c.escalationLevel
        val overdueDays = ((now - c.dueAt) / DAY_MS).toInt()
        val bySla = ref.escalationLevels.filter { overdueDays >= it.afterOverdueDays }.maxOfOrNull { it.level } ?: 0
        return maxOf(c.escalationLevel, bySla)
    }

    fun escalatedTo(level: Int) = ref.escalationLevels.firstOrNull { it.level == level }?.to

    /** Returns the complaint with escalation applied, or the same instance if nothing changed. */
    fun apply(c: Complaint, now: Long): Complaint {
        val newLevel = escalationLevelFor(c, now)
        if (newLevel <= c.escalationLevel) return c
        val events = (c.escalationLevel + 1..newLevel).map { lvl ->
            TimelineEvent(now, "Escalated (level $lvl)", "Target date passed. Notified: ${escalatedTo(lvl)}", "System")
        }
        return c.copy(escalationLevel = newLevel, timeline = c.timeline + events)
    }

    /** 0..1 fraction of the SLA window already used. */
    fun progress(c: Complaint, now: Long): Float {
        val end = c.resolvedAt ?: now
        return ((end - c.createdAt).toFloat() / (c.dueAt - c.createdAt).coerceAtLeast(1)).coerceIn(0f, 1f)
    }
}

/** Groups repeated reports of the same issue (report section 5.3). */
class DuplicateDetector {
    fun findSimilar(all: List<Complaint>, category: String, localityId: String, lat: Double?, lng: Double?): List<Complaint> =
        all.filter { c ->
            c.category == category && c.status.isOpen && (
                c.localityId == localityId ||
                    (lat != null && lng != null && c.lat != null && c.lng != null &&
                        distanceMeters(lat, lng, c.lat, c.lng) <= RADIUS_M)
                )
        }

    companion object {
        const val RADIUS_M = 150.0
    }
}

fun distanceMeters(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
    val r = 6_371_000.0
    val dLat = Math.toRadians(lat2 - lat1)
    val dLng = Math.toRadians(lng2 - lng1)
    val a = sin(dLat / 2) * sin(dLat / 2) +
        cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLng / 2) * sin(dLng / 2)
    return 2 * r * atan2(sqrt(a), sqrt(1 - a))
}

fun nearestLocality(ref: ReferenceData, lat: Double, lng: Double): Pair<LocationPath, Double>? =
    ref.allLocalities()
        .map { it to distanceMeters(lat, lng, it.locality.lat, it.locality.lng) }
        .minByOrNull { it.second }

fun statusAfterVerification(fixed: Boolean) = if (fixed) Status.CLOSED else Status.REOPENED
