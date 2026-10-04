package com.civicfix.app.data

import org.json.JSONArray
import org.json.JSONObject

enum class Status(val label: String) {
    NEW("New"),
    ASSIGNED("Assigned"),
    IN_PROGRESS("In Progress"),
    RESOLVED("Resolved – awaiting citizen verification"),
    CLOSED("Closed (verified fixed)"),
    REOPENED("Reopened – not fixed");

    val isOpen get() = this != RESOLVED && this != CLOSED
}

enum class Role {
    CITIZEN, OFFICER, SUPERVISOR, ADMIN;

    val isStaff get() = this != CITIZEN

    companion object {
        fun of(s: String) = entries.firstOrNull { it.name.equals(s, ignoreCase = true) } ?: CITIZEN
    }
}

/** The signed-in user (from the server). */
data class Session(
    val role: Role,
    val name: String,
    val departmentId: String? = null,
    val phone: String? = null,
    val id: Int = 0,
    val level: Int = 0,
    val designation: String? = null,
) {
    companion object {
        fun fromJson(o: JSONObject) = Session(
            role = Role.of(o.getString("role")), name = o.getString("name"),
            departmentId = o.optStringOrNull("departmentId"), phone = o.optStringOrNull("phone"),
            id = o.optInt("id"), level = o.optInt("level"), designation = o.optStringOrNull("designation"),
        )
    }
}

data class TimelineEvent(val time: Long, val title: String, val note: String, val actor: String)

data class Person(val id: Int, val name: String, val designation: String?, val phone: String?) {
    companion object {
        fun fromJson(o: JSONObject?) = o?.let {
            Person(it.optInt("id"), it.getString("name"), it.optStringOrNull("designation"), it.optStringOrNull("phone"))
        }
    }
}

data class Complaint(
    val id: String,
    val citizenName: String,
    val category: String,
    val severity: String,
    val description: String,
    val address: String,
    val landmark: String,
    val lat: Double?,
    val lng: Double?,
    val localityId: String,
    val locationLabel: String,
    val departmentId: String,
    val agency: String,
    val status: Status,
    val createdAt: Long,
    val dueAt: Long,
    val slaHours: Int,
    val resolvedAt: Long?,
    val closedAt: Long?,
    val reopenCount: Int,
    val escalationLevel: Int,
    val supportCount: Int,
    val supporters: List<String>,
    val officer: Person?,
    val supervisor: Person?,
    val escalatedTo: Person?,
    val assignedTeam: String?,
    /** Absolute URLs (server media). */
    val beforePhoto: String?,
    val afterPhoto: String?,
    val afterVideo: String?,
    val actionTaken: String?,
    val proofCheck: String?,
    val afterPhotoAiCheck: String?,
    val aiSummary: String?,
    val isMine: Boolean,
    val hasSupported: Boolean,
    val citizenPhone: String?,
    val timeline: List<TimelineEvent>,
) {
    fun isOverdue(now: Long) = status.isOpen && now > dueAt

    companion object {
        fun fromJson(o: JSONObject, mediaUrl: (String?) -> String?): Complaint {
            val sup = o.optJSONArray("supporters") ?: JSONArray()
            val tl = o.optJSONArray("timeline") ?: JSONArray()
            return Complaint(
                id = o.getString("id"), citizenName = o.optString("citizenName"), category = o.getString("category"),
                severity = o.optString("severity", "medium"), description = o.optString("description"),
                address = o.optString("address"), landmark = o.optString("landmark"),
                lat = o.optDoubleOrNull("lat"), lng = o.optDoubleOrNull("lng"),
                localityId = o.optString("localityId"), locationLabel = o.optString("locationLabel"),
                departmentId = o.optString("departmentId"), agency = o.optString("agency"),
                status = Status.valueOf(o.getString("status")), createdAt = o.getLong("createdAt"), dueAt = o.getLong("dueAt"),
                slaHours = o.optInt("slaHours"), resolvedAt = o.optLongOrNull("resolvedAt"), closedAt = o.optLongOrNull("closedAt"),
                reopenCount = o.optInt("reopenCount"), escalationLevel = o.optInt("escalationLevel"),
                supportCount = o.optInt("supportCount"), supporters = (0 until sup.length()).map { sup.getString(it) },
                officer = Person.fromJson(o.optJSONObject("officer")), supervisor = Person.fromJson(o.optJSONObject("supervisor")),
                escalatedTo = Person.fromJson(o.optJSONObject("escalatedTo")), assignedTeam = o.optStringOrNull("assignedTeam"),
                beforePhoto = mediaUrl(o.optStringOrNull("beforePhoto")), afterPhoto = mediaUrl(o.optStringOrNull("afterPhoto")),
                afterVideo = mediaUrl(o.optStringOrNull("afterVideo")), actionTaken = o.optStringOrNull("actionTaken"),
                proofCheck = o.optStringOrNull("proofCheck"), afterPhotoAiCheck = o.optStringOrNull("afterPhotoAiCheck"),
                aiSummary = o.optStringOrNull("aiSummary"), isMine = o.optBoolean("isMine"), hasSupported = o.optBoolean("hasSupported"),
                citizenPhone = o.optStringOrNull("citizenPhone"),
                timeline = (0 until tl.length()).map {
                    val e = tl.getJSONObject(it)
                    TimelineEvent(e.getLong("time"), e.getString("title"), e.optString("note"), e.optString("actor"))
                },
            )
        }
    }
}

/** A possible duplicate returned by the server's AI check. */
data class DuplicateMatch(val complaint: Complaint, val distanceM: Int, val similarity: Double?, val verdict: String)

data class StaffMember(val id: Int, val name: String, val designation: String, val level: Int, val open: Int)

data class AppNotification(val id: Int, val complaintId: String?, val kind: String, val title: String, val body: String,
                           val createdAt: Long, val read: Boolean)

/** How a department works (from the server's Delhi workflow table). */
data class Workflow(val agency: String, val name: String, val chain: List<String>, val slaHours: Map<String, Int>, val helpline: String)

fun JSONObject.optStringOrNull(k: String): String? = if (!has(k) || isNull(k)) null else optString(k)
fun JSONObject.optDoubleOrNull(k: String): Double? = if (!has(k) || isNull(k)) null else optDouble(k)
fun JSONObject.optLongOrNull(k: String): Long? = if (!has(k) || isNull(k)) null else optLong(k)
