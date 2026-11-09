package app.health

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import java.util.Locale

/** Home-screen widget: today's phone step count from the persistent step service. */
class StepsWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        for (id in ids) render(context, manager, id)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        updateAll(context)
    }

    companion object {
        fun updateAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context) ?: return
            val ids = manager.getAppWidgetIds(ComponentName(context, StepsWidget::class.java))
            for (id in ids) render(context, manager, id)
        }

        private fun render(context: Context, manager: AppWidgetManager, widget: Int) {
            val steps = Store.steps(context)
            val goal = Store.goal(context)
            val views = RemoteViews(context.packageName, R.layout.steps_widget)
            views.setTextViewText(R.id.widget_steps, String.format(Locale("ru"), "%,d", steps))
            views.setTextViewText(
                R.id.widget_goal,
                when {
                    goal <= 0 -> "шагов с телефоном"
                    steps >= goal -> "Цель достигнута ✓"
                    else -> "из " + String.format(Locale("ru"), "%,d", goal) + " шагов · " +
                        (100 * steps / goal).coerceIn(0, 99) + "%"
                },
            )
            views.setViewVisibility(
                R.id.widget_progress,
                if (goal > 0) android.view.View.VISIBLE else android.view.View.GONE,
            )
            if (goal > 0) views.setProgressBar(
                R.id.widget_progress,
                100,
                (100.0 * steps / goal).toInt().coerceIn(0, 100),
                false,
            )
            views.setOnClickPendingIntent(
                R.id.widget_root,
                PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE),
            )
            manager.updateAppWidget(widget, views)
        }
    }
}
