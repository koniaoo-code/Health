package app.health

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.*
import android.os.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import kotlin.math.sqrt

class SleepService : Service(), SensorEventListener {
    companion object { @JvmField var running = false }
    private lateinit var sensors: SensorManager
    private var wakeLock: PowerManager.WakeLock? = null
    private var session: JSONObject? = null
    private var bins = JSONArray()
    private var bucket = 0L
    private var sum = 0.0
    private var count = 0
    private val gravity = FloatArray(3)
    private var ready = false
    private val handler = Handler(Looper.getMainLooper())
    private val checkpoint = object : Runnable {
        override fun run() {
            flush()
            if (session?.let { System.currentTimeMillis() - it.getLong("start") >= 14 * 3600000L } == true) {
                finish(); stopSelf()
            } else handler.postDelayed(this, 60000)
        }
    }
    override fun onCreate() {
        super.onCreate(); running = true
        sensors = getSystemService(SensorManager::class.java)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel("sleep", "Ночное наблюдение", NotificationManager.IMPORTANCE_LOW))
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) { stopSelf(); return START_NOT_STICKY }
        if (intent.action == "STOP") { finish(); stopSelf(); return START_NOT_STICKY }
        if (session != null) return START_NOT_STICKY
        val sensor = sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER, true)
            ?: sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        if (sensor == null) {
            Store.prefs(this).edit().putString("sleep_error", "На телефоне нет акселерометра").apply()
            stopSelf(); return START_NOT_STICKY
        }
        val open = PendingIntent.getActivity(this, 20, Intent(this, MainActivity::class.java).putExtra("sleep", true), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, 2, Intent(this, SleepService::class.java).setAction("STOP"), PendingIntent.FLAG_IMMUTABLE)
        try {
            startForeground(2, Notification.Builder(this, "sleep").setSmallIcon(R.drawable.ic_sleep)
                .setContentTitle("Health · ночное наблюдение").setContentText("Экран можно выключить")
                .setContentIntent(open).setOngoing(true)
                .addAction(Notification.Action.Builder(null, "Завершить", stop).build()).build(),
                if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH else 0)
        } catch (e: SecurityException) {
            Store.prefs(this).edit().putString("sleep_error", "Разреши доступ к физической активности").apply()
            stopSelf(); return START_NOT_STICKY
        }
        session = JSONObject().put("id", UUID.randomUUID().toString()).put("start", System.currentTimeMillis())
            .put("source", "Акселерометр телефона · период наблюдения")
        bucket = System.currentTimeMillis()
        if (!sensor.isWakeUpSensor) {
            wakeLock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Health:SleepMotion").also { it.acquire(14 * 3600000L) }
        }
        if (!sensors.registerListener(this, sensor, 100000, 1000000)) {
            session = null
            Store.prefs(this).edit().putString("sleep_error", "Не удалось запустить акселерометр").apply()
            stopSelf(); return START_NOT_STICKY
        }
        Store.save(this, "sleep_active", session!!)
        handler.postDelayed(checkpoint, 60000)
        return START_NOT_STICKY
    }
    override fun onSensorChanged(event: SensorEvent) {
        if (!ready) { event.values.copyInto(gravity, endIndex = 3); ready = true }
        var energy = 0.0
        for (i in 0..2) {
            gravity[i] = .9f * gravity[i] + .1f * event.values[i]
            val d = (event.values[i] - gravity[i]).toDouble(); energy += d * d
        }
        sum += sqrt(energy); count++
    }
    private fun flush() {
        val record = session ?: return
        bins.put(JSONObject().put("time", bucket).put("samples", count).put("motion", if (count > 0) sum / count else JSONObject.NULL))
        record.put("bins", bins).put("checkpoint", System.currentTimeMillis())
        Store.save(this, "sleep_active", record)
        sum = 0.0; count = 0; bucket = System.currentTimeMillis()
    }
    private fun finish() {
        val record = session ?: return
        flush(); record.put("end", System.currentTimeMillis()); Store.add(this, "sleep", record)
        session = null; Store.prefs(this).edit().remove("sleep_active").apply()
    }
    override fun onDestroy() {
        flush(); running = false; handler.removeCallbacksAndMessages(null); sensors.unregisterListener(this)
        wakeLock?.takeIf { it.isHeld }?.release(); super.onDestroy()
    }
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    override fun onBind(intent: Intent?): IBinder? = null
}
