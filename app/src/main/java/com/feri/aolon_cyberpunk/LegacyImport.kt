package com.feri.aolon_cyberpunk

import android.content.Context
import com.google.firebase.database.DataSnapshot
import org.json.JSONArray
import org.json.JSONObject

/**
 * One-time move of records the pre-Firebase build kept in SharedPreferences "aolon".
 * Keys are derived from the old data, so a retry after a failed write never duplicates anything.
 */
object LegacyImport {
    private const val FILE = "aolon"

    // The first build shipped Indonesian labels.
    private val ENGLISH = mapOf(
        "Jalan kaki" to "Walk", "Lari" to "Run", "Bersepeda" to "Cycle", "Latihan bebas" to "Workout",
        "Sen" to "Mon", "Sel" to "Tue", "Rab" to "Wed", "Kam" to "Thu", "Jum" to "Fri", "Sab" to "Sat", "Min" to "Sun",
    )
    private fun en(label: String) = ENGLISH[label] ?: label

    class Plan(val updates: Map<String, Any?>, val workouts: Int, val stepDays: Int)

    /** Builds the Firebase multi-path update, or null when there is nothing left on the phone. */
    fun plan(context: Context, cloud: DataSnapshot): Plan? {
        val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        if (prefs.all.isEmpty()) return null
        val updates = mutableMapOf<String, Any?>()
        fun array(key: String) = runCatching { JSONArray(prefs.getString(key, null) ?: "[]") }.getOrDefault(JSONArray())
        fun each(array: JSONArray, block: (Int, JSONObject) -> Unit) =
            (0 until array.length()).forEach { i -> runCatching { block(i, array.getJSONObject(i)) } }

        var workouts = 0
        each(array("sessions")) { _, s ->
            updates["sessions/legacy-${s.getLong("startedAt")}"] = mapOf(
                "activity" to en(s.getString("activity")), "startedAt" to s.getLong("startedAt"), "duration" to s.getLong("duration"),
            )
            workouts++
        }
        each(array("plans")) { i, p ->
            val days = p.getJSONArray("days")
            updates["plans/legacy-$i"] = mapOf("activity" to en(p.getString("activity")), "days" to List(days.length()) { en(days.getString(it)) })
        }
        each(array("alarms")) { i, a ->
            updates["alarms/legacy-$i"] = mapOf("label" to a.getString("label"), "time" to a.getString("time"))
        }
        // Settings and the paired watch already in Firebase are newer, so they win.
        if (!cloud.hasChild("settings") && prefs.contains("distanceUnit")) {
            updates["settings"] = mapOf(
                "watchface" to (prefs.getString("watchface", null) ?: "violet"),
                "stepGoal" to prefs.getInt("stepGoal", 0).takeIf { it > 0 },
                "distanceUnit" to (prefs.getString("distanceUnit", null) ?: "km"),
            )
        }
        val address = prefs.getString("watchAddress", null)
        if (!cloud.hasChild("watch") && address != null) {
            updates["watch"] = mapOf("address" to address, "name" to (prefs.getString("watchName", null) ?: address))
        }
        val stepDays = runCatching { JSONObject(prefs.getString("steps", null) ?: "{}") }.getOrDefault(JSONObject())
        stepDays.keys().forEach { day ->
            val hours = stepDays.optJSONObject(day) ?: return@forEach
            hours.keys().forEach { hour ->
                val key = "steps/$day/h%02d".format(hour.toIntOrNull() ?: return@forEach)
                val cloudSteps = cloud.child(key).getValue(Long::class.java) ?: 0L
                // The watch only ever raises an hour's count, so the larger value is the true one.
                if (hours.getInt(hour) > cloudSteps) updates[key] = hours.getInt(hour)
            }
        }
        return Plan(updates, workouts, stepDays.length())
    }

    fun clear(context: Context) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().clear().commit()
        context.deleteSharedPreferences(FILE)
    }
}
