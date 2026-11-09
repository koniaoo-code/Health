package app.health

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.*
import android.os.*
import android.provider.Settings
import java.time.LocalDate
import java.util.Locale

/** A user-controlled, persistent step counter independent from workout and sleep sessions. */
class StepsService : Service(), SensorEventListener {
    companion object { @JvmField var running = false }
    private lateinit var sensors: SensorManager
    override fun onCreate() {
        super.onCreate()
        sensors = getSystemService(SensorManager::class.java)
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "OFF") {
            Store.prefs(this).edit().putBoolean("steps_enabled", false).remove("steps_sensor_value").apply()
            stopSelf()
            return START_NOT_STICKY
        }
        if (checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION) != PackageManager.PERMISSION_GRANTED) {
            error("Разреши доступ к физической активности и включи шагомер заново.")
            return START_NOT_STICKY
        }
        val sensor = sensors.getDefaultSensor(Sensor.TYPE_STEP_COUNTER, true)
            ?: sensors.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
        if (sensor == null) {
            error("На этом телефоне нет системного датчика шагов.")
            return START_NOT_STICKY
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("steps", "Фоновый шагомер", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 30, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 31, Intent(this, StepsService::class.java).setAction("OFF"), PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(this, "steps")
            .setSmallIcon(R.drawable.ic_walk)
            .setContentTitle("Health · шагомер включён")
            .setContentText(progressText())
            .setContentIntent(open).setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Выключить", stop).build()).build()
        startForeground(3, notification, if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH else 0)
        if (!running && !sensors.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL)) {
            error("Датчик шагов не удалось запустить.")
            return START_NOT_STICKY
        }
        running = true
        Store.prefs(this).edit().putBoolean("steps_enabled", true).remove("steps_error").apply()
        refresh()
        return START_STICKY
    }

    /** Уведомление и виджет показывают live-прогресс дневной цели. */
    private fun progressText(): String {
        val steps = Store.steps(this)
        val goal = Store.goal(this)
        val format = Locale("ru")
        return when {
            goal > 0 && steps >= goal -> "Цель достигнута · " + String.format(format, "%,d", steps) + " шагов"
            goal > 0 -> String.format(format, "%,d", steps) + " из " + String.format(format, "%,d", goal) + " шагов"
            else -> "Шаги считаются с выключенным экраном"
        }
    }

    private fun notification(): Notification {
        val open = PendingIntent.getActivity(this, 30, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 31, Intent(this, StepsService::class.java).setAction("OFF"), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, "steps")
            .setSmallIcon(R.drawable.ic_walk)
            .setContentTitle("Health · шагомер включён")
            .setContentText(progressText())
            .setContentIntent(open).setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Выключить", stop).build()).build()
    }

    private fun refresh() {
        getSystemService(NotificationManager::class.java).notify(3, notification())
        StepsWidget.updateAll(this)
    }
    private fun error(message: String) {
        Store.prefs(this).edit().putString("steps_error", message).putBoolean("steps_enabled", false).apply()
        stopSelf()
    }
    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_STEP_COUNTER || !Store.prefs(this).getBoolean("steps_enabled", false)) return
        val prefs = Store.prefs(this)
        val boot = Settings.Global.getInt(contentResolver, Settings.Global.BOOT_COUNT, -1)
        val value = event.values[0].toLong()
        val prior = prefs.getLong("steps_sensor_value", -1)
        val sameBoot = boot >= 0 && prefs.getInt("steps_sensor_boot", -2) == boot
        val delta = if (sameBoot && prior >= 0 && value >= prior) (value - prior).coerceAtMost(Int.MAX_VALUE.toLong()).toInt() else 0
        val day = LocalDate.now().toString()
        prefs.edit().putLong("steps_sensor_value", value).putInt("steps_sensor_boot", boot)
            .putInt("steps_$day", prefs.getInt("steps_$day", 0) + delta).apply()
        if (delta > 0) refresh()
    }
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    override fun onDestroy() { sensors.unregisterListener(this); running = false; super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = null
}

class StepsBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!Store.prefs(context).getBoolean("steps_enabled", false)) return
        if (context.checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION) != PackageManager.PERMISSION_GRANTED) return
        try {
            context.startForegroundService(Intent(context, StepsService::class.java).setAction("ON"))
        } catch (error: RuntimeException) {
            Store.prefs(context).edit().putString("steps_error", "Открой Health для восстановления фонового шагомера.").apply()
        }
    }
}
