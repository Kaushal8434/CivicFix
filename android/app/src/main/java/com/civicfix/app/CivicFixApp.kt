package com.civicfix.app

import android.app.Application
import android.content.Context
import com.civicfix.app.data.ComplaintRepository
import com.civicfix.app.data.ReferenceData
import com.civicfix.app.data.Role
import com.civicfix.app.data.Session
import com.civicfix.app.domain.DemoClock
import com.civicfix.app.domain.DuplicateDetector
import com.civicfix.app.domain.RoutingEngine
import com.civicfix.app.domain.SlaEngine
import com.civicfix.app.ml.AiAnalyzer
import com.civicfix.app.ml.ImageClassifier
import com.civicfix.app.ml.TextClassifier

/** Simple service locator – the prototype does not need a DI framework. */
class CivicFixApp : Application() {
    lateinit var ref: ReferenceData; private set
    lateinit var clock: DemoClock; private set
    lateinit var sla: SlaEngine; private set
    lateinit var routing: RoutingEngine; private set
    lateinit var repo: ComplaintRepository; private set
    val duplicates = DuplicateDetector()

    /** Loaded lazily (a few hundred ms) – first access happens off the main thread. */
    val ai: AiAnalyzer by lazy { AiAnalyzer(ref, ImageClassifier(this), TextClassifier(this)) }

    override fun onCreate() {
        super.onCreate()
        ref = ReferenceData(this)
        clock = DemoClock(this)
        sla = SlaEngine(ref)
        routing = RoutingEngine(ref)
        repo = ComplaintRepository(this, ref, sla, clock)
    }

    fun loadSession(): Session? {
        val p = getSharedPreferences("session", Context.MODE_PRIVATE)
        val role = p.getString("role", null) ?: return null
        return Session(Role.valueOf(role), p.getString("name", "") ?: "", p.getString("dept", null))
    }

    fun saveSession(s: Session?) {
        val p = getSharedPreferences("session", Context.MODE_PRIVATE).edit()
        if (s == null) p.clear() else p.putString("role", s.role.name).putString("name", s.name).putString("dept", s.departmentId)
        p.apply()
    }
}
