package app.health

import android.app.Instrumentation
import android.os.Bundle
import org.json.JSONArray
import org.json.JSONObject

/** Integration check on real Android SQLite and preferences, isolated from user data. */
class HistoryCheck : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
    override fun onStart() {
        val result = Bundle()
        try {
            val c = targetContext.createDeviceProtectedStorageContext()
            c.deleteDatabase("history.db")
            c.getSharedPreferences("health", 0).edit().clear()
                .putString("meals", JSONArray().put(JSONObject().put("name", "Legacy meal").put("time", 100L)).toString()).commit()
            check(Store.array(c, "meals").getJSONObject(0).getString("name") == "Legacy meal") { "Legacy migration" }
            val workout = JSONObject().put("id", "check-session").put("start", 200L).put("type", "Бег").put("notes", "test")
            Store.add(c, "workouts", workout)
            Store.add(c, "workouts", workout)
            check(Store.array(c, "workouts").length() == 1) { "Duplicate recovery" }
            workout.put("notes", "updated"); Store.add(c, "workouts", workout)
            check(Store.array(c, "workouts").getJSONObject(0).getString("notes") == "updated") { "Edit notes" }
            for (i in 0..204) Store.add(c, "moods", JSONObject().put("id", "m$i").put("time", i.toLong()))
            check(Store.array(c, "moods").length() == 205) { "History retention" }
            Store.remove(c, "workouts", "check-session")
            check(Store.array(c, "workouts").length() == 0) { "Delete record" }
            check(Store.array(c, "meals").length() == 1) { "Unrelated history retained" }
            check(FoodCatalog.FOODS.size == 427 && Workouts.TYPES.size == 63)
            result.putString("stream", "PASS: legacy migration, duplicate recovery, note edit, >200 records, deletion, catalog retention\n")
            finish(-1, result)
        } catch (e: Throwable) {
            result.putString("stream", "FAIL: ${e.stackTraceToString()}\n"); finish(0, result)
        }
    }
}
