package com.civicfix.app.data

import android.content.Context
import org.json.JSONObject

data class Locality(val id: String, val name: String, val lat: Double, val lng: Double)
data class Ward(val id: String, val name: String, val localities: List<Locality>)
data class Zone(val id: String, val name: String, val wards: List<Ward>)
data class City(val id: String, val name: String, val zones: List<Zone>)

data class Department(val id: String, val name: String, val officerRole: String)
data class Category(
    val key: String,
    val label: String,
    val emoji: String,
    val departmentId: String,
    val slaDays: Map<String, Int>,
)
data class EscalationLevel(val level: Int, val afterOverdueDays: Int, val to: String)

/** Resolved position of a locality inside the City → Zone → Ward → Locality tree. */
data class LocationPath(val city: City, val zone: Zone, val ward: Ward, val locality: Locality) {
    val label get() = "${locality.name}, ${ward.name}, ${zone.name}, ${city.name}"
}

/** Static configuration loaded from assets/locations.json and assets/departments.json. */
class ReferenceData(context: Context) {
    val cities: List<City>
    val departments: List<Department>
    val categories: List<Category>
    val escalationLevels: List<EscalationLevel>
    val safetyKeywords: List<String>

    init {
        val loc = JSONObject(context.assets.open("locations.json").bufferedReader().readText())
        val ca = loc.getJSONArray("cities")
        cities = (0 until ca.length()).map { i ->
            val c = ca.getJSONObject(i)
            val za = c.getJSONArray("zones")
            City(c.getString("id"), c.getString("name"), (0 until za.length()).map { j ->
                val z = za.getJSONObject(j)
                val wa = z.getJSONArray("wards")
                Zone(z.getString("id"), z.getString("name"), (0 until wa.length()).map { k ->
                    val w = wa.getJSONObject(k)
                    val la = w.getJSONArray("localities")
                    Ward(w.getString("id"), w.getString("name"), (0 until la.length()).map { m ->
                        val l = la.getJSONObject(m)
                        Locality(l.getString("id"), l.getString("name"), l.getDouble("lat"), l.getDouble("lng"))
                    })
                })
            })
        }

        val dep = JSONObject(context.assets.open("departments.json").bufferedReader().readText())
        val da = dep.getJSONArray("departments")
        departments = (0 until da.length()).map {
            val d = da.getJSONObject(it)
            Department(d.getString("id"), d.getString("name"), d.getString("officerRole"))
        }
        val cats = dep.getJSONArray("categories")
        categories = (0 until cats.length()).map {
            val c = cats.getJSONObject(it)
            val sla = c.getJSONObject("slaDays")
            Category(
                c.getString("key"), c.getString("label"), c.getString("emoji"), c.getString("department"),
                sla.keys().asSequence().associateWith { k -> sla.getInt(k) },
            )
        }
        val esc = dep.getJSONObject("escalation").getJSONArray("levels")
        escalationLevels = (0 until esc.length()).map {
            val e = esc.getJSONObject(it)
            EscalationLevel(e.getInt("level"), e.getInt("afterOverdueDays"), e.getString("to"))
        }
        val kw = dep.getJSONArray("safetyKeywords")
        safetyKeywords = (0 until kw.length()).map { kw.getString(it) }
    }

    fun category(key: String) = categories.firstOrNull { it.key == key } ?: categories.last()
    fun department(id: String) = departments.firstOrNull { it.id == id } ?: departments.last()

    fun pathOf(localityId: String): LocationPath? {
        for (c in cities) for (z in c.zones) for (w in z.wards) for (l in w.localities)
            if (l.id == localityId) return LocationPath(c, z, w, l)
        return null
    }

    fun allLocalities(): List<LocationPath> =
        cities.flatMap { c -> c.zones.flatMap { z -> z.wards.flatMap { w -> w.localities.map { LocationPath(c, z, w, it) } } } }
}
