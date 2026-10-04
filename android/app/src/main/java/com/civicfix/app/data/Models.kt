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

enum class Role { CITIZEN, OFFICER, SUPERVISOR }

data class Session(val role: Role, val name: String, val departmentId: String? = null)

data class TimelineEvent(val time: Long, val title: String, val note: String, val actor: String) {
    fun toJson() = JSONObject().put("time", time).put("title", title).put("note", note).put("actor", actor)

    companion object {
        fun fromJson(o: JSONObject) =
            TimelineEvent(o.getLong("time"), o.getString("title"), o.optString("note"), o.optString("actor"))
    }
}

data class Complaint(
    val id: String,
    val citizenName: String,
    val cityId: String,
    val zoneId: String,
    val wardId: String,
    val localityId: String,
    val locationLabel: String,
    val lat: Double?,
    val lng: Double?,
    val landmark: String,
    val category: String,
    val severity: String,
    val description: String,
    val beforePhoto: String?,
    val afterPhoto: String? = null,
    val departmentId: String,
    val assignedTeam: String? = null,
    val status: Status = Status.NEW,
    val createdAt: Long,
    val dueAt: Long,
    val resolvedAt: Long? = null,
    val closedAt: Long? = null,
    val escalationLevel: Int = 0,
    val supporters: List<String> = emptyList(),
    val actionTaken: String? = null,
    val aiSummary: String? = null,
    val afterPhotoAiCheck: String? = null,
    val reopenCount: Int = 0,
    val timeline: List<TimelineEvent> = emptyList(),
) {
    fun isOverdue(now: Long) = status.isOpen && now > dueAt

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("citizenName", citizenName)
        put("cityId", cityId); put("zoneId", zoneId); put("wardId", wardId); put("localityId", localityId)
        put("locationLabel", locationLabel); put("lat", lat ?: JSONObject.NULL); put("lng", lng ?: JSONObject.NULL)
        put("landmark", landmark); put("category", category); put("severity", severity)
        put("description", description); put("beforePhoto", beforePhoto ?: JSONObject.NULL)
        put("afterPhoto", afterPhoto ?: JSONObject.NULL); put("departmentId", departmentId)
        put("assignedTeam", assignedTeam ?: JSONObject.NULL); put("status", status.name)
        put("createdAt", createdAt); put("dueAt", dueAt)
        put("resolvedAt", resolvedAt ?: JSONObject.NULL); put("closedAt", closedAt ?: JSONObject.NULL)
        put("escalationLevel", escalationLevel); put("supporters", JSONArray(supporters))
        put("actionTaken", actionTaken ?: JSONObject.NULL); put("aiSummary", aiSummary ?: JSONObject.NULL)
        put("afterPhotoAiCheck", afterPhotoAiCheck ?: JSONObject.NULL); put("reopenCount", reopenCount)
        put("timeline", JSONArray(timeline.map { it.toJson() }))
    }

    companion object {
        private fun JSONObject.str(k: String) = if (isNull(k) || !has(k)) null else getString(k)
        private fun JSONObject.lng(k: String) = if (isNull(k) || !has(k)) null else getLong(k)
        private fun JSONObject.dbl(k: String) = if (isNull(k) || !has(k)) null else getDouble(k)

        fun fromJson(o: JSONObject): Complaint {
            val sup = o.optJSONArray("supporters") ?: JSONArray()
            val tl = o.optJSONArray("timeline") ?: JSONArray()
            return Complaint(
                id = o.getString("id"), citizenName = o.getString("citizenName"),
                cityId = o.getString("cityId"), zoneId = o.getString("zoneId"),
                wardId = o.getString("wardId"), localityId = o.getString("localityId"),
                locationLabel = o.getString("locationLabel"), lat = o.dbl("lat"), lng = o.dbl("lng"),
                landmark = o.optString("landmark"), category = o.getString("category"),
                severity = o.getString("severity"), description = o.getString("description"),
                beforePhoto = o.str("beforePhoto"), afterPhoto = o.str("afterPhoto"),
                departmentId = o.getString("departmentId"), assignedTeam = o.str("assignedTeam"),
                status = Status.valueOf(o.getString("status")), createdAt = o.getLong("createdAt"),
                dueAt = o.getLong("dueAt"), resolvedAt = o.lng("resolvedAt"), closedAt = o.lng("closedAt"),
                escalationLevel = o.optInt("escalationLevel"),
                supporters = (0 until sup.length()).map { sup.getString(it) },
                actionTaken = o.str("actionTaken"), aiSummary = o.str("aiSummary"),
                afterPhotoAiCheck = o.str("afterPhotoAiCheck"), reopenCount = o.optInt("reopenCount"),
                timeline = (0 until tl.length()).map { TimelineEvent.fromJson(tl.getJSONObject(it)) },
            )
        }
    }
}
