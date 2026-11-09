package app.health

import android.content.ContentValues
import android.content.Context
import android.content.SharedPreferences
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.util.Locale
import java.util.UUID

/** Durable history with a transactional migration from the first two app versions. */
object Store {
    @JvmField val HISTORY = arrayOf("workouts", "sleep", "meals", "measurements", "moods", "water")
    private var helper: Db? = null
    @JvmStatic fun prefs(c: Context): SharedPreferences = c.getSharedPreferences("health", Context.MODE_PRIVATE)
    @Synchronized private fun db(c: Context): SQLiteDatabase {
        val h = helper ?: Db(if (c.isDeviceProtectedStorage) c.createDeviceProtectedStorageContext() else c.applicationContext).also { helper = it }
        return h.writableDatabase
    }
    private class Db(val context: Context) : SQLiteOpenHelper(context, "history.db", null, 1) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE records (id TEXT PRIMARY KEY, kind TEXT NOT NULL, occurred INTEGER NOT NULL, payload TEXT NOT NULL)")
            db.execSQL("CREATE INDEX records_kind_time ON records(kind,occurred DESC)")
            for (kind in HISTORY) {
                val old = try { JSONArray(prefs(context).getString(kind, "[]")) } catch (e: org.json.JSONException) {
                    prefs(context).edit().putString("history_migration_notice", "Часть старой истории повреждена. Исходные данные сохранены в приложении.").apply()
                    continue
                }
                for (i in 0 until old.length()) {
                    val record = old.optJSONObject(i) ?: continue
                    if (!record.has("id")) record.put("id", "legacy-$kind-$i")
                    insert(db, kind, record)
                }
            }
        }
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    }
    @JvmStatic fun obj(c: Context, key: String): JSONObject =
        try { JSONObject(prefs(c).getString(key, "{}") ?: "{}") } catch (e: org.json.JSONException) { JSONObject() }
    @JvmStatic fun array(c: Context, key: String): JSONArray {
        if (key !in HISTORY) return JSONArray(prefs(c).getString(key, "[]") ?: "[]")
        val result = JSONArray()
        db(c).query("records", arrayOf("payload"), "kind=?", arrayOf(key), null, null, "occurred DESC, rowid DESC").use { cursor ->
            while (cursor.moveToNext()) result.put(JSONObject(cursor.getString(0)))
        }
        return result
    }
    @JvmStatic fun put(o: JSONObject, key: String, value: Any?) { o.put(key, value) }
    @JvmStatic fun save(c: Context, key: String, value: JSONObject) { prefs(c).edit().putString(key, value.toString()).apply() }
    private fun insert(db: SQLiteDatabase, kind: String, record: JSONObject) {
        if (!record.has("id")) record.put("id", UUID.randomUUID().toString())
        val values = ContentValues().apply {
            put("id", record.getString("id")); put("kind", kind)
            put("occurred", record.optLong("start", record.optLong("time", 0)))
            put("payload", record.toString())
        }
        check(db.insertWithOnConflict("records", null, values, SQLiteDatabase.CONFLICT_REPLACE) != -1L) { "Не удалось сохранить запись" }
    }
    @JvmStatic @Synchronized fun add(c: Context, kind: String, record: JSONObject) { insert(db(c), kind, record); changed(c) }
    @JvmStatic @Synchronized fun remove(c: Context, kind: String, id: String) {
        db(c).delete("records", "kind=? AND id=?", arrayOf(kind, id)); changed(c)
    }
    private fun changed(c: Context) { prefs(c).edit().putLong("history_revision", prefs(c).getLong("history_revision", 0) + 1).apply() }
    @JvmStatic fun day(): String = LocalDate.now().toString()
    @JvmStatic fun steps(c: Context): Int = prefs(c).getInt("steps_${day()}", 0)
    /** Дневная цель шагов; используется виджетом, уведомлением службы и экраном «Сегодня». */
    @JvmStatic fun goal(c: Context): Int = prefs(c).getInt("steps_goal", 8000)

    /** Примерная дистанция: длина шага ≈ 0.414 × рост; без роста — 72 см (средняя ходьба). */
    @JvmStatic fun stepsKm(c: Context, steps: Int): Double {
        val height = obj(c, "profile").optInt("height", 0)
        val stride = if (height in 100..250) height * 0.414 / 100.0 else 0.72
        return steps * stride / 1000.0
    }
    @JvmStatic fun duration(milliseconds: Long): String {
        val ms = milliseconds.coerceAtLeast(0); val minutes = ms / 60000
        return String.format(Locale.ROOT, "%d:%02d:%02d", minutes / 60, minutes % 60, ms / 1000 % 60)
    }
}
