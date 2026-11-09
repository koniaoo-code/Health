package app.health

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager

/** Kotlin entry point. Existing Material Views are retained during the gradual migration. */
class MainActivity : HealthViewsActivity() {
    override fun onResume() {
        super.onResume()
        StepsWidget.updateAll(this)
        if (Store.prefs(this).getBoolean("steps_enabled", false) && !StepsService.running &&
            checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED) {
            try { startForegroundService(Intent(this, StepsService::class.java).setAction("ON")) }
            catch (error: RuntimeException) {
                Store.prefs(this).edit().putString("steps_error", "Не удалось восстановить шагомер. Включи его на главной.").apply()
            }
        }
    }
}
