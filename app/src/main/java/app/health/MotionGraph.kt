package app.health

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import org.json.JSONArray

/** Движения за ночь линией: чем ровнее сон, тем ровнее линия. Без синтетических фаз сна. */
class MotionGraph(context: Context, binsIn: JSONArray?, private val color: Int) : View(context) {
    private val bins: JSONArray = binsIn ?: JSONArray()
    private val pen = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Path()

    init {
        contentDescription = "Интенсивность движений за ночь, ${bins.length()} минутных интервалов"
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val total = bins.length()
        if (total < 2) return
        val w = width.toFloat()
        val h = height.toFloat()
        val pad = h * 0.14f
        val body = h - pad * 2
        var max = 0.05
        for (i in 0 until total) max = Math.max(max, bins.optJSONObject(i).optDouble("motion", 0.0))
        canvas.save()
        /* Часовые отметки: тонкие вертикали каждые 60 минут. */
        pen.style = Paint.Style.STROKE
        pen.strokeWidth = 1.5f
        pen.color = Color.argb(45, Color.red(color), Color.green(color), Color.blue(color))
        val stepX = w / (total - 1)
        for (i in 0 until total step 60) canvas.drawLine(i * stepX, pad, i * stepX, h - pad, pen)

        /* Сама линия движений. */
        line.reset()
        for (i in 0 until total) {
            val bin = bins.optJSONObject(i)
            val samples = bin.optInt("samples")
            val value = if (samples > 0) bin.optDouble("motion", 0.0) / max else -0.08
            val y = h - pad - Math.max(0.0, value).toFloat() * body
            if (samples > 0 && line.isEmpty) line.moveTo(i * stepX, y)
            else if (samples > 0) line.lineTo(i * stepX, y)
            else if (!line.isEmpty) line.lineTo(i * stepX, h - pad)
        }
        pen.style = Paint.Style.STROKE
        pen.strokeWidth = Math.max(2f, w / total * 0.9f)
        pen.strokeCap = Paint.Cap.ROUND
        pen.strokeJoin = Paint.Join.ROUND
        pen.color = color
        canvas.drawPath(line, pen)
        canvas.restore()
    }
}
