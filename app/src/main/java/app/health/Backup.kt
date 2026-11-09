package app.health

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Building and restoring the JSON backup. Import is additive: identical record IDs replace, nothing is deleted. */
object Backup {
    const val SCHEMA = 1

    fun build(c: Context): JSONObject {
        val backup = JSONObject()
        backup.put("schema", SCHEMA)
        backup.put("created", System.currentTimeMillis())
        for (kind in Store.HISTORY) backup.put(kind, Store.array(c, kind))
        backup.put("profile", Store.obj(c, "profile"))
        val totals = JSONObject()
        for ((key, value) in Store.prefs(c).all) {
            if (key.startsWith("steps_") && key.removePrefix("steps_").startsWith("20") ||
                key.startsWith("water_") || key.startsWith("band_steps_")) totals.put(key, value)
        }
        backup.put("dailyTotals", totals)
        return backup
    }

    /** Adds records from a backup file and returns a human-readable summary in Russian. */
    @Throws(Exception::class)
    fun import(c: Context, text: String): String {
        val json = JSONObject(text)
        if (json.optInt("schema", -1) != SCHEMA) throw IllegalArgumentException("Не тот формат файла")
        val counts = LinkedHashMap<String, Int>()
        for (kind in Store.HISTORY) {
            val items = json.optJSONArray(kind) ?: continue
            for (index in 0 until items.length()) {
                val record = items.optJSONObject(index) ?: continue
                if (!record.has("id")) {
                    val timestamp = record.optLong("start", record.optLong("time", 0L))
                    record.put("id", "import-$kind-$timestamp-$index")
                }
                Store.add(c, kind, record)
                counts[kind] = (counts[kind] ?: 0) + 1
            }
        }
        var restoredDays = 0
        val today = Store.day()
        val edit = Store.prefs(c).edit()
        val totals = json.optJSONObject("dailyTotals") ?: JSONObject()
        for (key in totals.keys()) {
            if (!(key.startsWith("steps_") || key.startsWith("water_") || key.startsWith("band_steps_"))) continue
            val day = key.substringAfterLast('_')
            val date = try { java.time.LocalDate.parse(day) } catch (error: java.time.format.DateTimeParseException) { continue }
            if (date.toString() >= today) continue // Сегодняшний счёт живой: его не трогаем.
            if (c.getSharedPreferences("health", Context.MODE_PRIVATE).contains(key)) continue
            val value = totals.opt(key) ?: continue
            val number = when (value) {
                is Int -> value
                is Long -> if (value in 0..Int.MAX_VALUE.toLong()) value.toInt() else null
                is String -> value.toIntOrNull()
                else -> null
            } ?: continue
            edit.putInt(key, number)
            restoredDays++
        }
        edit.apply()
        val profile = json.optJSONObject("profile")
        var importedProfile = false
        if (profile != null && profile.has("height") && !Store.obj(c, "profile").has("height")) {
            Store.save(c, "profile", profile)
            importedProfile = true
        }
        val parts = counts.entries.map { (kind, count) -> "${label(kind)}: $count" }.joinToString(", ")
        val summary = if (parts.isEmpty()) "Записей в файле не найдено" else "Импортировано — $parts"
        val extras = StringBuilder()
        if (restoredDays > 0) extras.append("\nДневные суммы восстановлены за $restoredDays дн.")
        if (importedProfile) extras.append("\nПрофиль заполнен из копии")
        return summary + extras
    }

    private fun label(kind: String): String = when (kind) {
        "workouts" -> "тренировки"
        "sleep" -> "ночи"
        "meals" -> "приёмы пищи"
        "measurements" -> "измерения"
        "moods" -> "самочувствие"
        "water" -> "вода"
        else -> kind
    }
}
