package app.health

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import org.json.JSONArray
import org.json.JSONObject

/** Schematic path preview drawn from real GPS points only. */
class RouteView(context: Context, pointsIn: JSONArray?, color: Int) : View(context) {
    private val points: JSONArray = pointsIn ?: JSONArray()
    private val pen: Paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG)
    private val line = color

    init {
        contentDescription = "Схема записанного маршрута, ${points.length()} точек"
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (points.length() < 2) return
        var minX = 180.0
        var maxX = -180.0
        var minY = 90.0
        var maxY = -90.0
        val lat = points.optJSONObject(0).optDouble("lat")
        val scale = Math.cos(Math.toRadians(lat))
        for (i in 0 until points.length()) {
            val point = points.optJSONObject(i)
            val x = point.optDouble("lon") * scale
            val y = point.optDouble("lat")
            minX = Math.min(minX, x)
            maxX = Math.max(maxX, x)
            minY = Math.min(minY, y)
            maxY = Math.max(maxY, y)
        }
        val k = Math.min(
            (getWidth() - 64) / Math.max(0.00001, maxX - minX),
            (getHeight() - 64) / Math.max(0.00001, maxY - minY),
        )
        val path = Path()
        var startX = 0f
        var startY = 0f
        var endX = 0f
        var endY = 0f
        for (i in 0 until points.length()) {
            val point = points.optJSONObject(i)
            val x = (32 + (point.optDouble("lon") * scale - minX) * k).toFloat()
            val y = (getHeight() - 32 - (point.optDouble("lat") - minY) * k).toFloat()
            if (i == 0) {
                path.moveTo(x, y)
                startX = x
                startY = y
            } else {
                path.lineTo(x, y)
            }
            endX = x
            endY = y
        }
        pen.color = line
        pen.strokeWidth = 8f
        pen.strokeCap = Paint.Cap.ROUND
        pen.strokeJoin = Paint.Join.ROUND
        pen.style = Paint.Style.STROKE
        canvas.drawPath(path, pen)
        pen.style = Paint.Style.FILL
        canvas.drawCircle(startX, startY, 11f, pen)
        pen.color = Color.rgb(185, 65, 50)
        canvas.drawCircle(endX, endY, 11f, pen)
    }
}
