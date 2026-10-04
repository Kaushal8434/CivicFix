package com.civicfix.app.domain

import com.civicfix.app.data.Complaint
import com.civicfix.app.data.LocationPath
import com.civicfix.app.data.ReferenceData
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

const val HOUR_MS = 60L * 60 * 1000
const val DAY_MS = 24 * HOUR_MS

// Routing, deadlines, escalation and duplicate detection run on the backend
// (backend/app/services.py, workflows.py) so the app and the website share one set of rules.

/** 0..1 fraction of the deadline window already used. */
fun slaProgress(c: Complaint, now: Long): Float {
    val end = c.resolvedAt ?: now
    return ((end - c.createdAt).toFloat() / (c.dueAt - c.createdAt).coerceAtLeast(1)).coerceIn(0f, 1f)
}

/** "5h 20m left" / "late by 2d 3h" */
fun timeLeft(dueAt: Long, now: Long): String {
    val d = dueAt - now
    val abs = kotlin.math.abs(d)
    val h = abs / HOUR_MS
    val m = (abs % HOUR_MS) / 60_000
    val txt = if (h >= 48) "${h / 24}d ${h % 24}h" else "${h}h ${m}m"
    return if (d < 0) "late by $txt" else "$txt left"
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
