package com.civicfix.app.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Everything the screens need from the backend. The backend owns the rules
 * (routing, deadlines, escalation, duplicate AI) so the app and website always agree.
 */
class Repository(val api: Api) {
    private val _complaints = MutableStateFlow<List<Complaint>>(emptyList())
    /** The current user's working list (own + community for citizens, tasks for staff, everything for admin). */
    val complaints: StateFlow<List<Complaint>> = _complaints

    private val _unread = MutableStateFlow(0)
    val unread: StateFlow<Int> = _unread

    var workflows: Map<String, Workflow> = emptyMap(); private set
    var categoryDepartments: Map<String, String> = emptyMap(); private set
    private var serverOffset = 0L

    /** Server time (includes the demo clock offset used to show escalation). */
    fun now() = System.currentTimeMillis() + serverOffset

    private fun complaint(o: JSONObject) = Complaint.fromJson(o, api::mediaUrl)
    private fun list(a: Any) = (a as JSONArray).let { arr -> (0 until arr.length()).map { complaint(arr.getJSONObject(it)) } }

    // ---------------------------------------------------------------- accounts
    suspend fun login(name: String, password: String): Session =
        authResult(api.post("/api/auth/login", JSONObject().put("name", name).put("password", password)) as JSONObject)

    suspend fun register(name: String, password: String, phone: String?): Session =
        authResult(api.post("/api/auth/register", JSONObject().put("name", name).put("password", password).put("phone", phone ?: JSONObject.NULL)) as JSONObject)

    private fun authResult(o: JSONObject): Session {
        api.token = o.getString("token")
        return Session.fromJson(o.getJSONObject("user"))
    }

    /** Validates the saved token and refreshes the user + unread count. Null if signed out. */
    suspend fun me(): Session? {
        if (api.token == null) return null
        return try {
            val o = api.get("/api/me") as JSONObject
            serverOffset = o.getLong("serverTime") - System.currentTimeMillis()
            _unread.value = o.optInt("unreadNotifications")
            Session.fromJson(o.getJSONObject("user"))
        } catch (e: ApiException) {
            if (e.code == 401) { api.token = null; null } else throw e
        }
    }

    fun logout() {
        api.token = null
        _complaints.value = emptyList()
    }

    suspend fun loadMeta() {
        if (workflows.isNotEmpty()) return
        val o = api.get("/api/meta") as JSONObject
        val wf = o.getJSONObject("workflows")
        workflows = wf.keys().asSequence().associateWith { k ->
            val w = wf.getJSONObject(k)
            val chain = w.getJSONArray("chain")
            val sla = w.getJSONObject("sla_hours")
            Workflow(w.getString("agency"), w.getString("name"), (0 until chain.length()).map { chain.getString(it) },
                sla.keys().asSequence().associateWith { sla.getInt(it) }, w.optString("helpline"))
        }
        val cats = o.getJSONObject("categories")
        categoryDepartments = cats.keys().asSequence().associateWith { cats.getJSONObject(it).getString("department") }
        serverOffset = o.optLong("serverTime", System.currentTimeMillis()) - System.currentTimeMillis()
    }

    fun workflowFor(category: String) = workflows[categoryDepartments[category] ?: "general"]

    // ---------------------------------------------------------------- complaints
    suspend fun refresh(session: Session) {
        _complaints.value = when (session.role) {
            Role.CITIZEN -> {
                val mine = list(api.get("/api/complaints?scope=mine"))
                val community = list(api.get("/api/complaints?scope=community&limit=100"))
                (mine + community).distinctBy { it.id }
            }
            Role.OFFICER -> list(api.get("/api/complaints?scope=assigned"))
            Role.SUPERVISOR -> list(api.get("/api/complaints?scope=team"))
            Role.ADMIN -> list(api.get("/api/complaints?scope=all&limit=1000"))
        }
    }

    suspend fun get(id: String): Complaint = store(complaint(api.get("/api/complaints/$id") as JSONObject))

    private fun store(c: Complaint): Complaint {
        val cur = _complaints.value
        _complaints.value = if (cur.any { it.id == c.id }) cur.map { if (it.id == c.id) c else it } else listOf(c) + cur
        return c
    }

    suspend fun create(lat: Double, lng: Double, category: String, severity: String, description: String, address: String,
                       landmark: String, localityId: String?, aiSummary: String?, photo: File?): Complaint =
        store(complaint(api.multipart("/api/complaints", mapOf(
            "lat" to lat.toString(), "lng" to lng.toString(), "category" to category, "severity" to severity,
            "description" to description, "address" to address, "landmark" to landmark, "locality_id" to localityId,
            "ai_summary" to aiSummary,
        ), listOfNotNull(photo?.let { FilePart("photo", it, "image/jpeg") })) as JSONObject))

    suspend fun checkDuplicates(lat: Double, lng: Double, category: String?, photo: File?): List<DuplicateMatch> {
        val o = api.multipart("/api/complaints/check-duplicates",
            mapOf("lat" to lat.toString(), "lng" to lng.toString(), "category" to category, "radius" to "100"),
            listOfNotNull(photo?.let { FilePart("photo", it, "image/jpeg") })) as JSONObject
        val arr = o.getJSONArray("matches")
        return (0 until arr.length()).map {
            val m = arr.getJSONObject(it)
            DuplicateMatch(complaint(m), m.optInt("distanceM"), m.optDoubleOrNull("similarity"), m.optString("verdict"))
        }
    }

    suspend fun support(id: String) = store(complaint(api.post("/api/complaints/$id/support") as JSONObject))

    suspend fun verify(id: String, fixed: Boolean, reason: String) =
        store(complaint(api.post("/api/complaints/$id/verify", JSONObject().put("fixed", fixed).put("reason", reason)) as JSONObject))

    suspend fun start(id: String, team: String?) =
        store(complaint(api.post("/api/complaints/$id/start", JSONObject().put("team", team ?: JSONObject.NULL)) as JSONObject))

    suspend fun assign(id: String, officerId: Int, dueAt: Long?, team: String?, note: String) =
        store(complaint(api.post("/api/complaints/$id/assign", JSONObject().put("officer_id", officerId)
            .put("due_at", dueAt ?: JSONObject.NULL).put("team", team ?: JSONObject.NULL).put("note", note)) as JSONObject))

    suspend fun resolve(id: String, photo: File, video: File, actionTaken: String, lat: Double?, lng: Double?, capturedAt: Long) =
        store(complaint(api.multipart("/api/complaints/$id/resolve", mapOf(
            "action_taken" to actionTaken, "lat" to lat?.toString(), "lng" to lng?.toString(),
            "captured_at" to (capturedAt + serverOffset).toString(),
        ), listOf(FilePart("photo", photo, "image/jpeg"), FilePart("video", video, "video/mp4"))) as JSONObject))

    suspend fun correctCategory(id: String, category: String) =
        store(complaint(api.post("/api/complaints/$id/category", JSONObject().put("category", category)) as JSONObject))

    suspend fun staff(departmentId: String): List<StaffMember> {
        val arr = api.get("/api/staff?department=$departmentId") as JSONArray
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            StaffMember(o.getInt("id"), o.getString("name"), o.optString("designation"), o.optInt("level"), o.optInt("open"))
        }
    }

    // ---------------------------------------------------------------- notifications, stats, demo clock
    suspend fun notifications(): List<AppNotification> {
        val arr = api.get("/api/notifications") as JSONArray
        val list = (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            AppNotification(o.getInt("id"), o.optStringOrNull("complaintId"), o.optString("kind"), o.getString("title"),
                o.optString("body"), o.getLong("createdAt"), o.optBoolean("read"))
        }
        _unread.value = list.count { !it.read }
        return list
    }

    suspend fun markAllRead() {
        api.post("/api/notifications/read")
        _unread.value = 0
    }

    suspend fun stats(): JSONObject = api.get("/api/stats") as JSONObject

    suspend fun demoTime(addHours: Int, reset: Boolean = false): JSONObject {
        val o = api.post("/api/admin/time", JSONObject().put("add_hours", addHours).put("reset", reset)) as JSONObject
        serverOffset = o.getLong("now") - System.currentTimeMillis()
        return o
    }
}
