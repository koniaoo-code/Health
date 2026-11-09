package app.health

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.hardware.*
import android.location.*
import android.os.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** GPS and step recording for a workout, plus the rough screen-off rest estimate. */
class TrackingService : Service(), SensorEventListener, LocationListener {
    companion object { @JvmField var running = false }
    private lateinit var sensors: SensorManager
    private lateinit var locations: LocationManager
    private var workout: JSONObject? = null
    private var points: JSONArray? = null
    private var last: Location? = null
    private var since = 0L
    private var elapsed = 0L
    private var lastPointTime = 0L
    private var paused = false
    private var counter = -1f
    private var counterDay = ""
    private var screenOff = 0L
    private val handler = Handler(Looper.getMainLooper())
    private val tick: Runnable = object : Runnable {
        override fun run() { checkpoint(); handler.postDelayed(this, 5000) }
    }
    private val screen: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val now = System.currentTimeMillis()
            if (Intent.ACTION_SCREEN_OFF == intent.action) screenOff = now
            else if (screenOff > 0) {
                val span = now - screenOff
                val hour = java.time.Instant.ofEpochMilli(screenOff).atZone(java.time.ZoneId.systemDefault()).hour
                if (!Store.obj(context, "sleep_active").has("start") && Store.prefs(context).getBoolean("auto_sleep", false) &&
                    span >= 2 * 3600000L && span <= 14 * 3600000L && (hour >= 20 || hour < 6)) {
                    val estimate = JSONObject()
                    estimate.put("start", screenOff)
                    estimate.put("end", now)
                    estimate.put("source", "Оценка по выключенному экрану")
                    Store.add(context, "sleep", estimate)
                }
                screenOff = 0
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        running = true
        sensors = getSystemService(SENSOR_SERVICE) as SensorManager
        locations = getSystemService(LOCATION_SERVICE) as LocationManager
        registerReceiver(screen, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }, Context.RECEIVER_NOT_EXPORTED)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel("tracking", "Отслеживание Health", NotificationManager.IMPORTANCE_LOW))
    }
    private fun foreground(gps: Boolean) {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(this, "tracking")
            .setSmallIcon(R.drawable.ic_health)
            .setContentTitle("Health · отслеживание включено")
            .setContentText(if (gps) "Записывается маршрут тренировки" else "Шаги и оценка ночного отдыха")
            .setContentIntent(open).setOngoing(true).build()
        startForeground(1, notification, if (gps) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION or ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH else ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH)
    }
    override fun onStartCommand(intent: Intent?, flags: Int, id: Int): Int {
        if (intent == null) { stopSelf(); return START_NOT_STICKY }
        val cmd = intent.action
        try {
            if ("OFF" == cmd) {
                if (workout != null) finish()
                Store.prefs(this).edit().putBoolean("tracking", false).apply()
                stopSelf()
                return START_NOT_STICKY
            }
            foreground(if (workout != null) workout!!.optBoolean("gps") else "START" == cmd && Workouts.gps(intent.getStringExtra("type")))
            val stepSensor = sensors.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
            if (stepSensor != null) sensors.registerListener(this, stepSensor, SensorManager.SENSOR_DELAY_NORMAL)
            Store.prefs(this).edit().putBoolean("tracking", true).apply()
            if ("START" == cmd && workout == null) {
                workout = JSONObject()
                workout!!.put("id", UUID.randomUUID().toString())
                points = JSONArray()
                workout!!.put("type", intent.getStringExtra("type"))
                workout!!.put("gps", Workouts.gps(intent.getStringExtra("type")))
                workout!!.put("start", System.currentTimeMillis())
                workout!!.put("meters", 0)
                workout!!.put("stepsStart", Store.steps(this))
                elapsed = 0
                since = SystemClock.elapsedRealtime()
                paused = false
                last = null
                lastPointTime = 0
                if (workout!!.optBoolean("gps")) locations.requestLocationUpdates(LocationManager.GPS_PROVIDER, 2000L, 2f, this)
            }
            if ("PAUSE" == cmd && workout != null) {
                if (!paused) elapsed += SystemClock.elapsedRealtime() - since
                paused = true
                last = null
            }
            if ("RESUME" == cmd && workout != null) {
                if (paused) since = SystemClock.elapsedRealtime()
                paused = false
                last = null
            }
            if ("STOP" == cmd && workout != null) finish()
            handler.removeCallbacks(tick)
            handler.post(tick)
        } catch (error: SecurityException) {
            fail(error)
        } catch (error: IllegalArgumentException) {
            fail(error)
        }
        return START_NOT_STICKY
    }
    private fun fail(error: Exception) {
        Store.prefs(this).edit().putString("service_error", "Нет разрешения или датчика: " + error.message).apply()
        stopSelf()
    }
    private fun checkpoint() {
        val record = workout ?: return
        record.put("elapsed", elapsed + (if (paused) 0 else SystemClock.elapsedRealtime() - since))
        record.put("paused", paused)
        record.put("points", points)
        record.put("checkpoint", System.currentTimeMillis())
        Store.save(this, "active", record)
    }
    private fun finish() {
        val record = workout ?: return
        checkpoint()
        record.put("notes", Store.prefs(this).getString("workout_notes", ""))
        record.put("end", System.currentTimeMillis())
        Store.add(this, "workouts", record)
        workout = null
        points = null
        locations.removeUpdates(this)
        Store.prefs(this).edit().remove("active").apply()
        foreground(false)
    }
    override fun onLocationChanged(location: Location) {
        if (workout == null || paused || !location.hasAccuracy() || location.accuracy > 35) return
        val fixTime = location.elapsedRealtimeNanos / 1000000
        if (Math.abs(SystemClock.elapsedRealtime() - fixTime) > 15000) return
        if (last != null) {
            val distance = last!!.distanceTo(location).toDouble()
            val dt = (fixTime - lastPointTime) / 1000.0
            if (dt <= 0 || distance / dt > 60 || distance < 2) return
            workout!!.put("meters", workout!!.optDouble("meters") + distance)
        }
        last = Location(location)
        lastPointTime = fixTime
        val point = JSONObject()
        point.put("lat", location.latitude)
        point.put("lon", location.longitude)
        point.put("time", location.time) // GPS-время точки, а не время приёма.
        points?.put(point)
        checkpoint()
    }
    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_STEP_COUNTER) return
        val day = Store.day()
        val value = event.values[0]
        if (day != counterDay || counter < 0 || value < counter) {
            counter = value
            counterDay = day
        }
        val delta = Math.max(0, (value - counter).toInt())
        counter = value
        if (delta > 0 && workout != null && !paused) workout!!.put("stepCount", workout!!.optInt("stepCount") + delta)
    }
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() {
        checkpoint()
        running = false
        handler.removeCallbacksAndMessages(null)
        sensors.unregisterListener(this)
        locations.removeUpdates(this)
        unregisterReceiver(screen)
        Store.prefs(this).edit().putBoolean("tracking", false).apply()
        super.onDestroy()
    }
}
