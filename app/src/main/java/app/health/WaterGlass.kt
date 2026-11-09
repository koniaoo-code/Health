package app.health

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import kotlin.math.PI
import kotlin.math.sin

/**
 * Моделька стакана для шторки «Вода»: вода с лёгкой волной поднимается
 * до доли дневной нормы, у поверхности медленно всплывают пузырьки.
 * Вид ничего не хранит — все данные живут в Store; рисует по setProgress().
 */
class WaterGlassView(context: Context) : View(context) {
    private var accent = 0xFF36693E.toInt()
    private var shown = 0f /* 0..1 — какая доля стакана плавно налита */
    private var phase = 0.0 /* фаза волны */
    private var rise: ValueAnimator? = null
    private var wave: ValueAnimator? = null
    private var born = System.currentTimeMillis()

    /** tint — цвет воды/контура, fraction — доля от нормы (может быть больше 1). */
    fun setProgress(tint: Int, fraction: Float) {
        accent = tint
        rise?.cancel()
        rise = ValueAnimator.ofFloat(shown, fraction.coerceIn(0f, 1f)).apply {
            duration = 650
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                shown = it.animatedValue as Float
                invalidate()
            }
        }
        rise?.start()
        if (wave == null) {
            wave = ValueAnimator.ofFloat(0f, (2 * PI).toFloat()).apply {
                duration = 2600
                repeatCount = ValueAnimator.INFINITE
                interpolator = LinearInterpolator()
                addUpdateListener {
                    phase = (it.animatedValue as Float).toDouble()
                    invalidate()
                }
            }
            wave?.start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return
        val stroke = w * 0.045f
        val glassLeft = w / 2 - w * 0.34f
        val glassRight = w / 2 + w * 0.34f
        val top = h * 0.13f
        val bottom = h * 0.93f
        val radiusTop = w * 0.10f
        val radiusBottom = w * 0.24f

        val shape = Path()
        shape.addRoundRect(
            RectF(glassLeft, top, glassRight, bottom),
            floatArrayOf(radiusTop, radiusTop, radiusTop, radiusTop, radiusBottom, radiusBottom, radiusBottom, radiusBottom),
            Path.Direction.CW,
        )

        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val inset = stroke * 0.7f
        val left = glassLeft + inset
        val right = glassRight - inset
        val innerTop = top + inset
        val innerBottom = bottom - inset
        val waterColor = Color.argb(215, Color.red(accent), Color.green(accent), Color.blue(accent))

        canvas.save()
        canvas.clipPath(shape)
        paint.style = Paint.Style.FILL
        paint.color = Color.argb(28, Color.red(accent), Color.green(accent), Color.blue(accent))
        canvas.drawRect(left, innerTop, right, innerBottom, paint)

        if (shown > 0f) {
            val amp = w * (0.028f + 0.012f * shown)
            val surface = innerBottom - (innerBottom - innerTop) * shown
            val ridge = Path()
            ridge.moveTo(left, surface - amp * sin(phase).toFloat())
            val steps = 36
            for (i in 1..steps) {
                val t = i / steps.toFloat()
                ridge.lineTo(left + t * (right - left), surface - amp * sin(t * 2.6 * PI + phase).toFloat())
            }
            val water = Path(ridge)
            water.lineTo(right, innerBottom)
            water.lineTo(left, innerBottom)
            water.close()
            paint.style = Paint.Style.FILL
            paint.color = waterColor
            canvas.drawPath(water, paint)

            paint.style = Paint.Style.STROKE
            paint.strokeWidth = Math.max(1.5f, stroke * 0.32f)
            paint.color = Color.argb(110, 255, 255, 255)
            canvas.drawPath(ridge, paint)

            /* Пузырьки: медленно всплывают и растворяются у поверхности. */
            val age = (System.currentTimeMillis() - born) / 1000.0
            if (innerBottom - surface > amp * 3) {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = Math.max(1f, stroke * 0.28f)
                val bubbles = arrayOf(
                    floatArrayOf(-0.17f, 0.55f, 0.30f, 2.4f),
                    floatArrayOf(0.13f, 0.42f, 0.62f, 1.7f),
                    floatArrayOf(0.03f, 0.66f, 0.15f, 2.0f),
                )
                for (b in bubbles) {
                    var cycle = age * b[1] + b[2]
                    cycle %= 1.0
                    if (cycle < 0) cycle += 1.0
                    val f = cycle.toFloat()
                    val span = (innerBottom - surface) * 0.85f
                    val y = innerBottom - span * f - amp * 1.6f
                    val x = w / 2 + b[0] * w * 0.6f + sin(age * b[1] * 4 + b[2] * 9).toFloat() * w * 0.014f
                    paint.color = Color.argb(((1 - f) * 140).toInt(), 255, 255, 255)
                    canvas.drawCircle(x, y, b[3], paint)
                }
            }
        }
        canvas.restore()

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = stroke
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeJoin = Paint.Join.ROUND
        paint.color = accent
        canvas.drawPath(shape, paint)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        wave?.cancel()
        wave = null
        rise?.cancel()
        rise = null
    }
}
