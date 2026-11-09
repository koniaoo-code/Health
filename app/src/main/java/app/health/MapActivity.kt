package app.health

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.graphics.Color
import android.view.Gravity
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.DynamicColors
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay

/** Interactive OSM map. Location access runs only while this screen is visible. */
class MapActivity : AppCompatActivity() {
    private lateinit var map: MapView
    private lateinit var location: MyLocationNewOverlay
    private lateinit var status: TextView
    private var resumed = false
    private var routeShown = false
    private var followAfterPermission = false
    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    override fun onCreate(savedInstanceState: Bundle?) {
        DynamicColors.applyToActivityIfAvailable(this)
        super.onCreate(savedInstanceState)
        Configuration.getInstance().apply {
            userAgentValue = "HealthAndroid/0.3 (app.health)"
            osmdroidBasePath = java.io.File(cacheDir, "maps")
            osmdroidTileCache = java.io.File(cacheDir, "maps/tiles")
        }
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars()); v.setPadding(bars.left,bars.top,bars.right,bars.bottom); insets
        }
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(16),dp(8),dp(16),dp(8)) }
        header.addView(MaterialButton(this).apply { text = "Назад"; setOnClickListener { finish() } })
        header.addView(TextView(this).apply { text = "Карта"; textSize = 24f; setPadding(dp(16),0,0,0) })
        root.addView(header)
        status = TextView(this).apply { text = "Поиск геопозиции…"; textSize = 14f; setPadding(dp(20),dp(8),dp(20),dp(8)) }
        root.addView(status)
        map = MapView(this).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            controller.setZoom(2.0)
            controller.setCenter(GeoPoint(0.0,0.0))
        }
        root.addView(map, LinearLayout.LayoutParams(-1,0,1f))
        location = MyLocationNewOverlay(GpsMyLocationProvider(this), map)
        map.overlays.add(location)
        root.addView(MaterialButton(this).apply {
            text = "Моё местоположение"; isAllCaps = false; cornerRadius = dp(28)
            setOnClickListener { locate(true) }
        }, LinearLayout.LayoutParams(-1,dp(56)))
        root.addView(TextView(this).apply { text = "© OpenStreetMap contributors · карта требует интернета"; textSize = 12f; setPadding(dp(16),0,dp(16),dp(8)); setOnClickListener { startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://www.openstreetmap.org/copyright"))) } })
        setContentView(root)
        if (intent.getBooleanExtra("route",false)) {
            val points = Store.obj(this,"map_route").optJSONArray("points")
            if (points != null && points.length() > 0) {
                val path = ArrayList<GeoPoint>()
                for (i in 0 until points.length()) points.optJSONObject(i)?.let { path.add(GeoPoint(it.optDouble("lat"),it.optDouble("lon"))) }
                if (path.isNotEmpty()) {
                    val line = Polyline(map).apply { setPoints(path); outlinePaint.color = Color.rgb(48,110,74); outlinePaint.strokeWidth = dp(4).toFloat() }
                    map.overlays.add(line); map.controller.setZoom(15.0); map.controller.setCenter(path.first()); routeShown = true
                    if (path.size > 1) map.post { map.zoomToBoundingBox(org.osmdroid.util.BoundingBox.fromGeoPoints(path), false, dp(32)) }
                }
            }
        }
    }
    private fun locate(follow: Boolean) {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            followAfterPermission = follow
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.ACCESS_COARSE_LOCATION),71)
            status.text = "Разреши геопозицию, чтобы увидеть себя на карте"; return
        }
        if (!resumed) return
        try {
            location.enableMyLocation()
            if (follow) location.enableFollowLocation()
            location.runOnFirstFix {
                runOnUiThread {
                    if (isFinishing || !resumed) return@runOnUiThread
                    status.text = "Твоё положение отмечено на карте"
                    if (follow) location.myLocation?.let { map.controller.setZoom(17.0); map.controller.animateTo(it) }
                }
            }
            status.text = if (routeShown && !follow) "Сохранённый маршрут · ожидаем твою геопозицию" else "Ожидаем геопозицию. Проверь, что GPS включён"
        } catch (e: SecurityException) { status.text = "Доступ к геопозиции отключён" }
    }
    override fun onResume() { super.onResume(); resumed=true; map.onResume(); locate(!routeShown) }
    override fun onPause() { resumed=false; location.disableMyLocation(); location.disableFollowLocation(); map.onPause(); super.onPause() }
    override fun onDestroy() { map.onDetach(); super.onDestroy() }
    override fun onRequestPermissionsResult(requestCode:Int, permissions:Array<out String>, grantResults:IntArray) {
        super.onRequestPermissionsResult(requestCode,permissions,grantResults)
        if (requestCode==71) {
            if (grantResults.any { it==PackageManager.PERMISSION_GRANTED }) locate(followAfterPermission)
            else status.text="Нет доступа к геопозиции. Карту и сохранённый маршрут можно просматривать вручную"
        }
    }
}
