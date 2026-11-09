package app.health

import android.Manifest
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Typeface
import android.hardware.Sensor
import android.hardware.SensorManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Space
import android.widget.Spinner
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.color.DynamicColors
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

open class HealthViewsActivity : AppCompatActivity(), BandClient.Listener {
    protected var root: LinearLayout? = null
    protected var body: LinearLayout? = null
    private var deviceList: LinearLayout? = null
    private var live: TextView? = null
    private var sleepLive: TextView? = null
    private var bandStatus: TextView? = null
    private var bandMetrics: TextView? = null
    private var dailySteps: TextView? = null
    private var nightElapsed: TextView? = null
    protected var tab = 0
    private lateinit var band: BandClient
    private var afterPermission: (() -> Unit)? = null
    private var exportText: String? = null
    private var foodDay: String = Store.day()
    private val handler = Handler(Looper.getMainLooper())
    private val found = HashSet<String>()
    private var night = false
    private var workoutChoice = "Бег"
    private var selectedMealKind = "Завтрак"
    private var historyKind = "all"
    private var historyDay = ""
    private var historyOpen = false
    private var historyLimit = 40
    private val refresh: Runnable = object : Runnable {
        override fun run() {
            updateLive()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val mode = Store.prefs(this).getInt("theme", 0)
        AppCompatDelegate.setDefaultNightMode(
            when (mode) {
                1 -> AppCompatDelegate.MODE_NIGHT_YES
                2 -> AppCompatDelegate.MODE_NIGHT_NO
                else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            }
        )
        DynamicColors.applyToActivityIfAvailable(this)
        super.onCreate(savedInstanceState)
        if (intent.getBooleanExtra("sleep", false)) tab = 2 // Открыто из уведомления сна.
        band = BandClient(this, this)
        recover()
        build()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    night -> build()
                    historyOpen -> {
                        historyOpen = false
                        page()
                    }
                    else -> {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                    }
                }
            }
        })
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 45)
    }

    private fun dp(n: Int): Int = (resources.displayMetrics.density * n).toInt()
    private fun color(attr: Int): Int = MaterialColors.getColor(this, attr, Color.DKGRAY)
    private fun primary(): Int = color(androidx.appcompat.R.attr.colorPrimary)
    private fun ink(): Int = color(com.google.android.material.R.attr.colorOnSurface)
    private fun secondaryInk(): Int = color(com.google.android.material.R.attr.colorOnSurfaceVariant)

    private fun text(s: CharSequence, size: Int): TextView {
        val v = TextView(this)
        v.text = s
        v.textSize = size.toFloat()
        v.setTextColor(ink())
        v.fontFeatureSettings = "tnum"
        return v
    }

    private fun column(): LinearLayout {
        val l = LinearLayout(this)
        l.orientation = LinearLayout.VERTICAL
        return l
    }

    private fun gap(d: Int) {
        body!!.addView(Space(this), LinearLayout.LayoutParams(1, dp(d)))
    }

    private fun label(s: String) {
        gap(20)
        body!!.addView(text(s, 24))
        gap(12)
    }

    private fun note(s: String) {
        val t = text(s, 15)
        t.setLineSpacing(dp(3).toFloat(), 1f)
        body!!.addView(t)
        gap(12)
    }

    private fun button(title: String, run: () -> Unit) {
        val b = pill(title, false, run)
        body!!.addView(b, LinearLayout.LayoutParams(-1, dp(56)))
    }

    /* Тональные кнопки-пилюли и иконки шапки. */
    private fun pill(title: String, prominent: Boolean, action: () -> Unit): MaterialButton {
        val b = MaterialButton(this)
        b.text = title
        b.isAllCaps = false
        b.textSize = 16f
        b.cornerRadius = dp(28)
        b.minHeight = dp(52)
        b.setInsetTop(dp(4))
        b.setInsetBottom(dp(4))
        b.setElevation(0f)
        if (!prominent) {
            b.backgroundTintList = android.content.res.ColorStateList.valueOf(
                color(com.google.android.material.R.attr.colorSecondaryContainer)
            )
            b.setTextColor(color(com.google.android.material.R.attr.colorOnSecondaryContainer))
        }
        b.setOnClickListener { action() }
        return b
    }

    /** Квадратные иконные кнопки шапки: центрированный значок и равные стороны. */
    private fun headerIconButton(icon: Int, description: String, action: () -> Unit): MaterialButton {
        val b = MaterialButton(this)
        b.text = ""
        b.contentDescription = description
        b.setIconResource(icon)
        b.setIconGravity(MaterialButton.ICON_GRAVITY_TEXT_START)
        b.setIconSize(dp(24))
        b.setInsetTop(dp(2))
        b.setInsetBottom(dp(2))
        b.setPadding(0, 0, 0, 0)
        b.isAllCaps = false
        b.cornerRadius = dp(24)
        b.setElevation(0f)
        b.setStrokeWidth(0)
        b.minWidth = dp(48)
        b.minimumWidth = dp(48)
        b.minimumHeight = dp(48)
        b.backgroundTintList = android.content.res.ColorStateList.valueOf(
            color(com.google.android.material.R.attr.colorSecondaryContainer)
        )
        b.setTextColor(
            android.content.res.ColorStateList.valueOf(
                color(com.google.android.material.R.attr.colorOnSecondaryContainer)
            )
        )
        b.setIconTint(
            android.content.res.ColorStateList.valueOf(
                color(com.google.android.material.R.attr.colorOnSecondaryContainer)
            )
        )
        b.setOnClickListener { action() }
        return b
    }

    private fun primaryButton(title: String, icon: Int, action: () -> Unit) {
        val b = pill(title, true, action)
        b.setIconResource(icon)
        b.setIconGravity(MaterialButton.ICON_GRAVITY_TEXT_START)
        body!!.addView(b, LinearLayout.LayoutParams(-1, dp(64)))
    }

    /* Карточки, строки, чипы. */
    private fun surface(tone: Int): LinearLayout {
        val c = MaterialCardView(this)
        c.radius = dp(24).toFloat()
        c.strokeWidth = 0
        c.cardElevation = 0f
        c.setCardBackgroundColor(color(tone))
        val box = column()
        box.setPadding(dp(20), dp(20), dp(20), dp(20))
        c.addView(box)
        body!!.addView(c, LinearLayout.LayoutParams(-1, -2))
        gap(12)
        return box
    }

    private fun card(title: String, value: String) {
        val c = MaterialCardView(this)
        c.radius = dp(20).toFloat()
        c.cardElevation = 0f
        c.strokeWidth = 0
        c.setCardBackgroundColor(color(com.google.android.material.R.attr.colorSurfaceContainer))
        val box = column()
        box.setPadding(dp(20), dp(18), dp(20), dp(18))
        box.addView(text(title, 16))
        box.addView(text(value, 32))
        c.addView(box)
        body!!.addView(c, LinearLayout.LayoutParams(-1, -2))
        gap(12)
    }

    private fun heading(title: String, description: String?) {
        gap(12)
        val h = text(title, 32)
        h.setTypeface(h.typeface, Typeface.BOLD)
        body!!.addView(h)
        gap(6)
        if (description != null) {
            val sub = text(description, 14)
            sub.setTextColor(secondaryInk())
            body!!.addView(sub)
        }
        gap(20)
    }

    private fun row(parent: LinearLayout, title: String, subtitle: String?, icon: Int, action: (() -> Unit)?) {
        val line = LinearLayout(this)
        line.gravity = Gravity.CENTER_VERTICAL
        line.setPadding(dp(4), dp(12), dp(4), dp(12))
        line.minimumHeight = dp(68)
        val symbol = ImageView(this)
        symbol.setImageResource(icon)
        symbol.imageTintList = android.content.res.ColorStateList.valueOf(primary())
        line.addView(symbol, LinearLayout.LayoutParams(dp(24), dp(24)))
        val words = column()
        val wp = LinearLayout.LayoutParams(0, -2, 1f)
        wp.setMargins(dp(16), 0, dp(8), 0)
        line.addView(words, wp)
        words.addView(text(title, 16))
        if (subtitle != null && subtitle.isNotEmpty()) {
            val s = text(subtitle, 13)
            s.setTextColor(secondaryInk())
            s.setPadding(0, dp(3), 0, 0)
            words.addView(s)
        }
        if (action != null) {
            val arrow = ImageView(this)
            arrow.setImageResource(R.drawable.ic_chevron)
            arrow.imageTintList = android.content.res.ColorStateList.valueOf(secondaryInk())
            line.addView(arrow, LinearLayout.LayoutParams(dp(20), dp(20)))
            val tv = android.util.TypedValue()
            theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
            line.setBackgroundResource(tv.resourceId)
            line.isClickable = true
            line.isFocusable = true
            line.setOnClickListener { action() }
        }
        parent.addView(line, LinearLayout.LayoutParams(-1, -2))
    }

    private fun chips(titles: Array<String>, actions: Array<() -> Unit>) {
        val group = ChipGroup(this)
        group.setChipSpacingHorizontal(dp(8))
        group.setChipSpacingVertical(dp(4))
        for (i in titles.indices) {
            val k = i
            val c = Chip(this)
            c.text = titles[i]
            c.setEnsureMinTouchTargetSize(true)
            c.chipMinHeight = dp(40).toFloat()
            c.chipCornerRadius = dp(24).toFloat()
            c.chipStrokeWidth = 0f
            c.chipBackgroundColor = android.content.res.ColorStateList.valueOf(
                color(com.google.android.material.R.attr.colorSecondaryContainer)
            )
            c.setTextColor(color(com.google.android.material.R.attr.colorOnSecondaryContainer))
            c.setOnClickListener { actions[k]() }
            group.addView(c)
        }
        body!!.addView(group)
        gap(8)
    }

    private fun emptyState(title: String, sub: String, icon: Int) {
        val p = surface(com.google.android.material.R.attr.colorSurfaceContainerLow)
        row(p, title, sub, icon, null)
    }

    private fun field(parent: LinearLayout, hint: String, value: String, number: Boolean): EditText {
        val wrap = TextInputLayout(this, null, com.google.android.material.R.attr.textInputOutlinedStyle)
        wrap.hint = hint
        val e = TextInputEditText(wrap.context)
        e.setText(value)
        if (number) e.setInputType(
            android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
        )
        wrap.addView(e)
        val lp = LinearLayout.LayoutParams(-1, -2)
        lp.setMargins(0, dp(16), 0, dp(8))
        parent.addView(wrap, lp)
        return e
    }

    /* Построение каркаса экрана. */
    private fun build() {
        night = false
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val attributes = window.attributes
        attributes.screenBrightness = -1f
        window.attributes = attributes
        val dark = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.isAppearanceLightStatusBars = !dark
        controller.isAppearanceLightNavigationBars = !dark
        root = column()
        root!!.setBackgroundColor(color(com.google.android.material.R.attr.colorSurface))
        ViewCompat.setOnApplyWindowInsetsListener(root!!) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        setContentView(root)
        val header = LinearLayout(this)
        header.gravity = Gravity.CENTER_VERTICAL
        header.setPadding(dp(24), dp(12), dp(12), dp(12))
        header.addView(text("Health", 24), LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(
            headerIconButton(R.drawable.ic_clock, "История") { openHistory("all") },
            LinearLayout.LayoutParams(dp(48), dp(48)),
        )
        header.addView(
            headerIconButton(R.drawable.ic_profile, "Профиль и настройки") { showProfile() },
            LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginStart = dp(8) },
        )
        root!!.addView(header)
        val scroll = ScrollView(this)
        scroll.isFillViewport = true
        body = column()
        body!!.setPadding(dp(20), 0, dp(20), dp(24))
        scroll.addView(body)
        root!!.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        val nav = BottomNavigationView(this)
        val names = arrayOf("Сегодня", "Движение", "Сон", "Еда")
        val tabs = intArrayOf(0, 1, 2, 4)
        val icons = intArrayOf(R.drawable.ic_home, R.drawable.ic_activity, R.drawable.ic_sleep, R.drawable.ic_food)
        for (j in names.indices) nav.menu.add(0, tabs[j] + 1, j, names[j]).setIcon(icons[j])
        nav.selectedItemId = tab + 1
        nav.setOnItemSelectedListener { item ->
            tab = item.itemId - 1
            page()
            true
        }
        root!!.addView(nav)
        page()
    }

    private fun page() {
        dailySteps = null
        historyOpen = false
        body?.removeAllViews()
        live = null
        sleepLive = null
        deviceList = null
        bandStatus = null
        bandMetrics = null
        nightElapsed = null
        when {
            tab == 0 -> today()
            tab == 1 -> activity()
            tab == 2 -> sleep()
            tab == 3 -> bandPage()
            else -> food()
        }
    }

    /* Сегодня. */
    private fun today() {
        heading(
            "Сегодня",
            LocalDate.now().format(DateTimeFormatter.ofPattern("d MMMM, EEEE", Locale("ru"))),
        )
        val steps = surface(com.google.android.material.R.attr.colorPrimaryContainer)
        val goal = Store.goal(this)
        val count = Store.steps(this)
        val st = text(String.format(Locale("ru"), "%,d", count), 54)
        st.setTextColor(color(com.google.android.material.R.attr.colorOnPrimaryContainer))
        dailySteps = st
        steps.addView(st)
        val cap = text(
            "шагов с телефоном · ≈ " + String.format(Locale.ROOT, "%.1f км", Store.stepsKm(this, count)), 16
        )
        cap.setTextColor(color(com.google.android.material.R.attr.colorOnPrimaryContainer))
        steps.addView(cap)
        if (goal > 0) {
            val gauge = LinearProgressIndicator(this)
            gauge.setIndicatorColor(color(com.google.android.material.R.attr.colorOnPrimaryContainer))
            gauge.setTrackColor(
                ColorUtils.setAlphaComponent(
                    color(com.google.android.material.R.attr.colorOnPrimaryContainer), 60
                )
            )
            gauge.trackCornerRadius = dp(4)
            gauge.max = 100
            gauge.progress = (100.0 * count / goal).toInt().coerceIn(0, 100)
            steps.addView(
                gauge,
                LinearLayout.LayoutParams(-1, dp(8)).apply { setMargins(0, dp(10), 0, dp(6)) },
            )
            val quote = text(
                if (count >= goal) "Цель достигнута ✓"
                else String.format(Locale("ru"), "из %,d шагов · %d%% цели", goal, (100.0 * count / goal).toInt()),
                14,
            )
            quote.setTextColor(color(com.google.android.material.R.attr.colorOnPrimaryContainer))
            steps.addView(quote)
        }
        val bandSaved = Store.prefs(this).getInt("band_steps_" + Store.day(), -1)
        if (bandSaved >= 0) {
            val savedSteps = text("Mi Band 7: $bandSaved · последняя запись", 14)
            savedSteps.setTextColor(color(com.google.android.material.R.attr.colorOnPrimaryContainer))
            steps.addView(savedSteps)
        }
        val active = Store.prefs(this).getBoolean("steps_enabled", false)
        val control = pill(if (active) "Выключить шагомер" else "Включить шагомер", false) {
            permission(false) {
                command(if (active) "OFF" else "ON", null)
                handler.postDelayed({ page() }, 400)
            }
        }
        val cp = LinearLayout.LayoutParams(-2, dp(52))
        cp.setMargins(0, dp(12), 0, 0)
        steps.addView(control, cp)
        val rest = surface(com.google.android.material.R.attr.colorSurfaceContainerLow)
        val nights = Store.array(this, "sleep")
        val latest = nights.optJSONObject(0)
        row(
            rest, "Сон",
            if (latest == null) "Первое наблюдение — сегодня ночью"
            else Store.duration(latest.optLong("end") - latest.optLong("start")) + " · последнее наблюдение",
            R.drawable.ic_sleep,
        ) {
            tab = 2
            build()
        }
        row(rest, "Движение", "Тренировки и прогулки", R.drawable.ic_activity) {
            tab = 1
            build()
        }
        trends()
        row(body!!, "Карта", "Текущее местоположение", R.drawable.ic_home) {
            startActivity(Intent(this, MapActivity::class.java))
        }
        label("Вода")
        row(
            body!!, "Вода",
            Store.prefs(this).getInt("water_" + Store.day(), 0).toString() + " мл сегодня · " +
                Math.round(100.0 * Store.prefs(this).getInt("water_" + Store.day(), 0) / 2000) + "% дневной нормы",
            R.drawable.ic_water,
        ) { water() }
        if (getSystemService(SensorManager::class.java).getDefaultSensor(Sensor.TYPE_STEP_COUNTER) == null) {
            note("Датчик шагов на этом телефоне недоступен. GPS-тренировки работают отдельно.")
        }
        val error = Store.prefs(this).getString("steps_error", "")
        if (!error.isNullOrEmpty()) note(error)
        gap(12)
    }

    /* Средние значения за прошедшие семь полных дней. */
    private fun trends() {
        val panel = surface(com.google.android.material.R.attr.colorSurfaceContainerLow)
        panel.addView(text("Прошлая неделя · средние", 20))
        val prefs = Store.prefs(this)
        val today = LocalDate.now()
        var stepsSum = 0L
        var stepsDays = 0
        var waterSum = 0
        var waterDays = 0
        for (offset in 1..7) {
            val day = today.minusDays(offset.toLong())
            if (prefs.contains("steps_$day")) {
                stepsSum += prefs.getInt("steps_$day", 0)
                stepsDays++
            }
            if (prefs.contains("water_$day")) {
                waterSum += prefs.getInt("water_$day", 0)
                waterDays++
            }
        }
        var sleepSum = 0L
        var nightsShown = 0
        val allNights = Store.array(this, "sleep")
        val earliest = today.minusDays(7).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        for (i in 0 until allNights.length()) {
            val nightRecord = allNights.optJSONObject(i) ?: continue
            val start = nightRecord.optLong("start", 0)
            val end = nightRecord.optLong("end", start)
            if (start <= 0 || end <= start || end < earliest) continue
            sleepSum += end - start
            nightsShown++
        }
        if (stepsDays == 0 && waterDays == 0 && nightsShown == 0) {
            row(panel, "Пока нет данных", "Появятся средние, когда накопятся записи за несколько дней.", R.drawable.ic_clock, null)
            gap(4)
            return
        }
        if (stepsDays > 0) {
            val avg = (stepsSum / stepsDays).toInt()
            row(
                panel, "Шаги",
                String.format(Locale("ru"), "%,d", avg) + " в день · ≈" +
                    String.format(Locale.ROOT, "%.1f км", Store.stepsKm(this, avg)) +
                    " в день · данные за $stepsDays дн.", R.drawable.ic_walk, null,
            )
        }
        if (nightsShown > 0) {
            val minutes = sleepSum / nightsShown / 60000
            row(
                panel, "Сон",
                String.format(Locale.ROOT, "%d ч %02d мин", minutes / 60, minutes % 60) + " · $nightsShown ноч.",
                R.drawable.ic_sleep, null,
            )
        }
        if (waterDays > 0) {
            row(
                panel, "Вода",
                "≈ " + String.format(Locale("ru"), "%,d", waterSum / waterDays) + " мл в день" +
                    " · данные за $waterDays дн.", R.drawable.ic_water, null,
            )
        }
        gap(4)
    }

    /* Движение. */
    private fun activity() {
        heading("Движение", "Тренировка в твоём ритме")
        val active = Store.obj(this, "active")
        if (active.has("start")) {
            val panel = surface(com.google.android.material.R.attr.colorPrimaryContainer)
            panel.addView(text(active.optString("type"), 20))
            live = text("", 34)
            panel.addView(live)
            val paused = active.optBoolean("paused")
            primaryButton(if (paused) "Продолжить" else "Пауза", R.drawable.ic_play) {
                command(if (paused) "RESUME" else "PAUSE", null)
                handler.postDelayed({ page() }, 350)
            }
            chips(
                arrayOf("Сохранить", "Упражнения"),
                arrayOf(
                    {
                        command("STOP", null)
                        handler.postDelayed({ page() }, 400)
                    },
                    { sets() },
                ),
            )
            if (active.optBoolean("gps")) {
                val p = active.optJSONArray("points")
                if (p != null && p.length() > 1) {
                    body!!.addView(RouteView(this, p, primary()), LinearLayout.LayoutParams(-1, dp(220)))
                } else note("Ожидаем точный сигнал GPS…")
            }
        } else {
            val panel = surface(com.google.android.material.R.attr.colorSurfaceContainerLow)
            row(
                panel, workoutChoice,
                if (Workouts.gps(workoutChoice)) "Маршрут · расстояние · время" else "Время · заметки об упражнениях",
                R.drawable.ic_activity, { workoutPicker() },
            )
            chips(arrayOf("Бег", "Прогулка"), arrayOf({ selectWorkout("Бег") }, { selectWorkout("Прогулка") }))
            primaryButton("Начать тренировку", R.drawable.ic_play) { startWorkout(workoutChoice) }
            row(body!!, "Все тренировки", "63 вида активности", R.drawable.ic_activity, { workoutPicker() })
        }
        label("История")
        val history = Store.array(this, "workouts")
        if (history.length() == 0) emptyState("Твоя первая тренировка", "Сохранённые занятия появятся здесь.", R.drawable.ic_activity)
        for (i in 0 until Math.min(3, history.length())) workoutRow(history.optJSONObject(i))
        row(body!!, "Вся история тренировок", history.length().toString() + " записей", R.drawable.ic_clock) {
            openHistory("workouts")
        }
        updateLive()
    }

    private fun startWorkout(type: String) {
        permission(Workouts.gps(type)) {
            Store.prefs(this).edit().remove("workout_notes").apply()
            command("START", type)
            handler.postDelayed({ page() }, 600)
        }
    }

    private fun workoutRow(o: JSONObject) {
        row(
            body!!, o.optString("type", "Тренировка"),
            date(o.optLong("start")) + " · " +
                (if (o.optBoolean("gps")) String.format(Locale.ROOT, "%.2f км · ", o.optDouble("meters") / 1000) else "") +
                Store.duration(o.optLong("elapsed")),
            R.drawable.ic_activity,
        ) { route(o) }
    }

    private fun selectWorkout(type: String) {
        workoutChoice = type
        page()
    }

    /* Сон. */
    private fun sleep() {
        heading("Сон", "Вечером — отдых. Утром — твои наблюдения.")
        val active = Store.obj(this, "sleep_active")
        if (active.has("start")) {
            val p = surface(com.google.android.material.R.attr.colorPrimaryContainer)
            p.addView(text("Наблюдение идёт", 20))
            sleepLive = text("", 40)
            p.addView(sleepLive)
            p.addView(text("Акселерометр телефона", 14))
            primaryButton("Ночной экран", R.drawable.ic_sleep) { nightScreen() }
            chips(
                arrayOf("Завершить и сохранить"),
                arrayOf({
                    MaterialAlertDialogBuilder(this)
                        .setTitle("Завершить наблюдение?")
                        .setMessage("График движений сохранится в историю.")
                        .setPositiveButton("Сохранить") { _, _ ->
                            startService(Intent(this, SleepService::class.java).setAction("STOP"))
                            handler.postDelayed({ page() }, 400)
                        }
                        .setNegativeButton("Продолжать", null)
                        .show()
                }),
            )
        } else {
            val nights = Store.array(this, "sleep")
            val last = nights.optJSONObject(0)
            if (last == null) {
                val panel = surface(com.google.android.material.R.attr.colorSurfaceContainerLow)
                row(panel, "Всё готово ко сну", "Положи телефон рядом на матрас. Экран можно выключить.", R.drawable.ic_sleep, null)
                gap(4)
            } else {
                val p = surface(com.google.android.material.R.attr.colorSurfaceContainerLow)
                row(p, "Последняя ночь", date(last.optLong("start")), R.drawable.ic_sleep) { sleepDetail(last) }
                val value = text(Store.duration(last.optLong("end") - last.optLong("start")), 38)
                p.addView(value)
                p.addView(text("период наблюдения", 14))
                val bins = last.optJSONArray("bins")
                if (bins != null) p.addView(MotionGraph(this, bins, primary()), LinearLayout.LayoutParams(-1, dp(100)))
                val lastStats = sleepStats(last)
                row(
                    p, "Оценка глубокого сна",
                    "≈ " + lastStats.optInt("stillPct") + "% ночи · " + sleepWord(lastStats.optInt("stillPct")),
                    R.drawable.ic_sleep,
                ) { sleepDetail(last) }
            }
            primaryButton("Ложусь спать", R.drawable.ic_sleep) {
                permission(false) {
                    Store.prefs(this).edit().remove("sleep_error").apply()
                    startForegroundService(Intent(this, SleepService::class.java).setAction("START"))
                    handler.postDelayed({
                        if (Store.obj(this, "sleep_active").has("start")) nightScreen() else page()
                    }, 500)
                }
            }
        }
        row(body!!, "Настройки наблюдения", "Ночной экран, датчики и фоновая работа", R.drawable.ic_settings) {
            sleepOptions()
        }
        val error = Store.prefs(this).getString("sleep_error", "")
        if (!error.isNullOrEmpty()) note(error)
        label("Ночи")
        val nights = Store.array(this, "sleep")
        if (nights.length() == 0) note("После первой ночи здесь появится график движений.")
        row(body!!, "Вся история сна", nights.length().toString() + " записей", R.drawable.ic_clock) {
            openHistory("sleep")
        }
        for (i in 0 until Math.min(3, nights.length())) {
            val n = nights.optJSONObject(i)
            val st = sleepStats(n)
            val pct = st.optInt("stillPct")
            row(
                body!!, date(n.optLong("start")),
                Store.duration(n.optLong("end") - n.optLong("start")) + " · покой ≈" + pct + "% · " +
                    sleepWord(pct),
                R.drawable.ic_sleep,
            ) { sleepDetail(n) }
        }
        updateLive()
    }

    /** Честная оценка ночи по движениям: минуты с данными, с движением, самая долгая тишина. */
    private fun sleepStats(n: JSONObject): JSONObject {
        val bins = n.optJSONArray("bins")
        val st = JSONObject()
        var samples = 0
        var moving = 0
        var longest = 0
        var streak = 0
        if (bins != null) {
            for (i in 0 until bins.length()) {
                val bin = bins.optJSONObject(i)
                if (bin.optInt("samples") <= 0) continue
                samples++
                if (bin.optDouble("motion", 0.0) > 0) {
                    moving++
                    streak = 0
                } else {
                    streak++
                    if (streak > longest) longest = streak
                }
            }
        }
        Store.put(st, "minutes", samples)
        Store.put(st, "moving", moving)
        Store.put(st, "longest", longest)
        Store.put(st, "stillPct", if (samples > 0) Math.round(100.0 * (samples - moving) / samples).toInt() else 0)
        return st
    }

    private fun sleepDetail(n: JSONObject) {
        val sheet = BottomSheetDialog(this)
        val content = column()
        content.setPadding(dp(24), dp(12), dp(24), dp(12))
        content.addView(text(date(n.optLong("start")), 20))
        content.addView(text(Store.duration(n.optLong("end") - n.optLong("start")), 44))
        content.addView(text(n.optString("source"), 14))
        val bins = n.optJSONArray("bins")
        if (bins != null) {
            content.addView(
                MotionGraph(this, bins, primary()),
                LinearLayout.LayoutParams(-1, dp(120)).apply { setMargins(0, dp(14), 0, dp(6)) },
            )
            content.addView(text("Линия — интенсивность движений по минутам: чем ровнее сон, тем ровнее линия.", 13))
        }
        val stats = sleepStats(n)
        val pct = stats.optInt("stillPct")
        label2(content, "Итоги ночи")
        row(
            content, "Время покоя",
            (stats.optInt("minutes") - stats.optInt("moving")).toString() + " мин · ≈" + pct + "% ночи · " +
                sleepWord(pct),
            R.drawable.ic_sleep, null,
        )
        row(
            content, "Время движений",
            stats.optInt("moving").toString() + " мин · ≈" + (100 - pct) + "% ночи",
            R.drawable.ic_activity, null,
        )
        row(
            content, "Самая долгая тишина",
            stats.optInt("longest").toString() + " мин подряд",
            R.drawable.ic_clock, null,
        )
        content.addView(
            text("Оценка глубокого сна ≈$pct% — это доля минут покоя. Актография телефона, не клиника.", 13),
        )
        if (bins != null) {
            val gaps = bins.length() - stats.optInt("minutes")
            content.addView(
                text(
                    "Датчик записал " + stats.optInt("minutes") + " минут наблюдения" +
                        if (gaps > 0) ", $gaps мин без сигнала." else ".",
                    13,
                ),
            )
        }
        sheet.setContentView(content)
        sheet.show()
    }

    /** Небольшой подзаголовок внутри шторок (отдельно от body). */
    private fun label2(parent: LinearLayout, s: String) {
        parent.addView(text(s, 18))
        parent.addView(Space(this), LinearLayout.LayoutParams(1, dp(8)))
    }

    private fun nightScreen() {
        night = true
        val keep = Store.prefs(this).getInt("night_mode", 0) == 1
        val panel = column()
        panel.gravity = Gravity.CENTER
        panel.setPadding(dp(32), dp(48), dp(32), dp(48))
        panel.setBackgroundColor(Color.BLACK)
        val clock = text(
            java.time.LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm")),
            60,
        )
        clock.setTextColor(0xff50483e.toInt())
        panel.addView(clock)
        val hint = text(
            if (keep) "Ночное наблюдение\nТусклые часы включены"
            else "Ночное наблюдение\nЭкран погаснет по тайм-ауту.\nМожно нажать кнопку питания.",
            17,
        )
        hint.gravity = Gravity.CENTER
        hint.setTextColor(0xff797069.toInt())
        panel.addView(hint)
        val elapsedLine = text("", 15)
        elapsedLine.setTextColor(0xff797069.toInt())
        panel.addView(elapsedLine)
        nightElapsed = elapsedLine
        val back = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle)
        back.text = "Вернуться к управлению"
        back.setTextColor(0xff998a7b.toInt())
        back.setOnClickListener { build() }
        panel.addView(back)
        setContentView(panel)
        if (keep) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val attrs = window.attributes
        attrs.screenBrightness = .01f
        window.attributes = attrs
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK
        WindowInsetsControllerCompat(window, panel).isAppearanceLightStatusBars = false
        WindowInsetsControllerCompat(window, panel).isAppearanceLightNavigationBars = false
        handler.postDelayed(object : Runnable {
            override fun run() {
                if (!night) return
                clock.text = java.time.LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm"))
                val observing = Store.obj(this@HealthViewsActivity, "sleep_active")
                nightElapsed?.text = if (observing.has("start")) {
                    "Наблюдение · " + Store.duration(Math.max(0L, System.currentTimeMillis() - observing.optLong("start")))
                } else ""
                handler.postDelayed(this, 10000)
            }
        }, 10000)
    }

    /* Браслет. */
    private fun bandPage() {
        heading("Браслет", "Твои показатели с Mi Band 7")
        val device = surface(com.google.android.material.R.attr.colorSurfaceContainerLow)
        row(device, "Mi Band 7", "Обычная версия · Bluetooth", R.drawable.ic_band, null)
        bandStatus = text(band.status(), 14)
        bandStatus!!.setTextColor(primary())
        device.addView(bandStatus)
        bandMetrics = text(bandValues(), 20)
        bandMetrics!!.setLineSpacing(dp(8).toFloat(), 1f)
        bandMetrics!!.setPadding(0, dp(20), 0, 0)
        device.addView(bandMetrics)
        primaryButton("Подключить браслет", R.drawable.ic_band) { connectBandSheet() }
        chips(
            arrayOf("Поиск устройств", "Отключить"),
            arrayOf(
                {
                    bluetoothPermission {
                        found.clear()
                        deviceList?.removeAllViews()
                        band.scan()
                    }
                },
                { band.disconnect() },
            ),
        )
        deviceList = column()
        body!!.addView(deviceList!!)
        label("О подключении")
        row(body!!, "Как подключить Mi Band 7", "Ключ сопряжения и совместимость", R.drawable.ic_info) {
            MaterialAlertDialogBuilder(this)
                .setTitle("Подключение браслета")
                .setMessage(
                    "Для прямого подключения нужен ключ авторизации из первоначального сопряжения. Он вводится только на телефоне и не сохраняется.\n\n" +
                        "Пока приложение открыто, доступны запросы шагов, заряда и пульса. История сна, SpO₂, стресс и фоновое переподключение ещё не реализованы. " +
                        "Поддержка браслета экспериментальная. Версия 7 Pro использует другой протокол."
                )
                .setPositiveButton("Понятно", null)
                .setNeutralButton("Инструкция") { _, _ ->
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://gadgetbridge.org/basics/pairing/huami-xiaomi-server/")))
                }
                .show()
        }
    }

    private fun bandValues(): String =
        "Пульс    " + (if (band.heart >= 0) band.heart.toString() + " уд/мин" else "Нет данных") +
            "\nШаги     " + (if (band.steps >= 0) band.steps.toString() else "Нет данных") +
            "\nЗаряд    " + (if (band.battery >= 0) band.battery.toString() + "%" else "Нет данных")

    private fun connectBandSheet() {
        val sheet = BottomSheetDialog(this)
        val form = column()
        form.setPadding(dp(24), dp(24), dp(24), dp(32))
        form.addView(text("Подключение Mi Band", 26))
        val mac = field(form, "MAC-адрес", Store.prefs(this).getString("band_mac", "") ?: "", false)
        mac.setSingleLine()
        val key = field(form, "Ключ авторизации · 32 символа", "", false)
        key.setSingleLine()
        key.setInputType(android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD)
        form.addView(
            pill("Подключить", true) {
                bluetoothPermission {
                    Store.prefs(this).edit().putString("band_mac", mac.text.toString().trim()).apply()
                    band.connect(mac.text.toString().trim(), key.text.toString())
                    key.setText("")
                    sheet.dismiss()
                }
            }
        )
        sheet.setContentView(form)
        sheet.show()
        sheet.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
    }

    override fun changed(s: String) {
        bandStatus?.text = s
        bandMetrics?.text = bandValues()
    }

    override fun found(name: String, mac: String) {
        val list = deviceList ?: return
        if (!found.add(mac)) return
        row(list, name, mac, R.drawable.ic_band) {
            Store.prefs(this).edit().putString("band_mac", mac).apply()
            connectBandSheet()
        }
    }

    /* Оформление и разрешения. */
    private fun themes() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Оформление")
            .setSingleChoiceItems(
                arrayOf("Как в системе", "Тёмная тема", "Светлая тема"),
                Store.prefs(this).getInt("theme", 0),
            ) { d, w ->
                Store.prefs(this).edit().putInt("theme", w).apply()
                d.dismiss()
                AppCompatDelegate.setDefaultNightMode(
                    when (w) {
                        1 -> AppCompatDelegate.MODE_NIGHT_YES
                        2 -> AppCompatDelegate.MODE_NIGHT_NO
                        else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                    }
                )
                recreate()
            }
            .setNegativeButton("Закрыть", null)
            .show()
    }

    private fun permission(gps: Boolean, run: () -> Unit) {
        val list = ArrayList(listOf(Manifest.permission.ACTIVITY_RECOGNITION))
        if (gps) {
            list.add(Manifest.permission.ACCESS_COARSE_LOCATION)
            list.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        request(list, run)
    }

    private fun bluetoothPermission(run: () -> Unit) {
        request(
            ArrayList(listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)),
            run,
        )
    }

    private fun request(list: ArrayList<String>, run: () -> Unit) {
        list.removeIf { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
        if (list.isEmpty()) run()
        else {
            afterPermission = run
            requestPermissions(list.toTypedArray(), 44)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != 44) return
        val run = afterPermission
        afterPermission = null
        var ok = grantResults.isNotEmpty()
        for (n in grantResults) ok = ok and (n == PackageManager.PERMISSION_GRANTED)
        if (ok && run != null) run()
        else MaterialAlertDialogBuilder(this)
            .setTitle("Нужно разрешение")
            .setMessage("Разреши доступ в настройках Health и повтори действие. Для точного маршрута включи точную геопозицию.")
            .setPositiveButton("Настройки") { _, _ ->
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            }
            .setNegativeButton("Позже", null)
            .show()
    }

    private fun command(cmd: String, type: String?) {
        if (cmd == "ON" || cmd == "OFF") {
            startForegroundService(Intent(this, StepsService::class.java).setAction(cmd))
            if (cmd == "ON" && Store.prefs(this).getBoolean("auto_sleep", false)) {
                startForegroundService(Intent(this, TrackingService::class.java).setAction(cmd))
            }
            return
        }
        if (cmd == "START") {
            startForegroundService(Intent(this, StepsService::class.java).setAction("ON"))
        }
        Store.prefs(this).edit().remove("service_error").apply()
        startForegroundService(Intent(this, TrackingService::class.java).setAction(cmd).putExtra("type", type))
    }

    private fun updateLive() {
        if (night) return
        dailySteps?.text = String.format(Locale("ru"), "%,d", Store.steps(this))
        val workout = Store.obj(this, "active")
        if (live != null) {
            var elapsed = workout.optLong("elapsed")
            if (!workout.optBoolean("paused")) {
                elapsed += Math.max(
                    0L,
                    System.currentTimeMillis() - workout.optLong("checkpoint", System.currentTimeMillis()),
                )
            }
            val km = workout.optDouble("meters") / 1000
            live!!.text =
                String.format(Locale.ROOT, "%.2f км\n%s", km, Store.duration(elapsed)) +
                "\nШаги: " + workout.optInt("stepCount") +
                (if (km > .05) String.format(Locale.ROOT, "\nТемп %.1f мин/км", elapsed / 60000.0 / km) else "")
        }
        if (sleepLive != null) {
            val observing = Store.obj(this, "sleep_active")
            sleepLive!!.text = Store.duration(
                Math.max(0L, System.currentTimeMillis() - observing.optLong("start", System.currentTimeMillis()))
            )
        }
    }

    private fun date(time: Long): String =
        Instant.ofEpochMilli(time).atZone(ZoneId.systemDefault()).format(
            DateTimeFormatter.ofPattern("d MMM, HH:mm", Locale("ru"))
        )

    private fun toast(s: String) {
        android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_LONG).show()
    }

    private fun route(o: JSONObject) {
        val p = o.optJSONArray("points")
        val content = column()
        content.setPadding(dp(24), 0, dp(24), 0)
        content.addView(RouteView(this, p, primary()), LinearLayout.LayoutParams(-1, dp(300)))
        content.addView(
            text(
                "Схема пути · " + (if (p == null) 0 else p.length()) + " GPS-точек\n" +
                    String.format(Locale.ROOT, "%.2f км · %d шагов", o.optDouble("meters") / 1000, o.optInt("stepCount")) +
                    "\n" + o.optString("notes", ""),
                18,
            )
        )
        MaterialAlertDialogBuilder(this)
            .setTitle(o.optString("type", "Маршрут"))
            .setView(content)
            .setPositiveButton("Закрыть", null)
            .setNeutralButton("Карта") { _, _ ->
                Store.save(this, "map_route", o)
                startActivity(Intent(this, MapActivity::class.java).putExtra("route", true))
            }
            .show()
    }

    private fun export(points: JSONArray?) {
        val s = StringBuilder()
        s.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
        s.append("<gpx version=\"1.1\" creator=\"Health\" xmlns=\"http://www.topografix.com/GPX/1/1\"><trk><name>Health</name><trkseg>")
        if (points != null) {
            for (i in 0 until points.length()) {
                val p = points.optJSONObject(i)
                s.append("<trkpt lat=\"").append(p.optDouble("lat")).append("\" lon=\"").append(p.optDouble("lon")).append("\"><time>")
                    .append(Instant.ofEpochMilli(p.optLong("time"))).append("</time></trkpt>")
            }
        }
        exportText = s.append("</trkseg></trk></gpx>").toString()
        startActivityForResult(
            Intent(Intent.ACTION_CREATE_DOCUMENT)
                .setType("application/gpx+xml")
                .addCategory(Intent.CATEGORY_OPENABLE)
                .putExtra(Intent.EXTRA_TITLE, "Health-route.gpx"),
            81,
        )
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if ((requestCode == 81 || requestCode == 82) && resultCode == RESULT_OK && data != null && exportText != null) {
            try {
                contentResolver.openOutputStream(data.data!!).use { out ->
                    out?.write(exportText!!.toByteArray(Charsets.UTF_8))
                }
                toast(if (requestCode == 81) "Маршрут сохранён" else "История сохранена")
            } catch (error: Exception) {
                toast("Не удалось сохранить файл")
            }
        }
        if (requestCode == 83 && resultCode == RESULT_OK && data?.data != null) {
            try {
                val text = contentResolver.openInputStream(data.data!!)!!.bufferedReader(Charsets.UTF_8).use { it.readText() }
                val summary = Backup.import(this, text)
                MaterialAlertDialogBuilder(this)
                    .setTitle("Импорт завершён")
                    .setMessage(summary)
                    .setPositiveButton("Понятно", null)
                    .show()
                historyPage()
            } catch (error: Exception) {
                MaterialAlertDialogBuilder(this)
                    .setTitle("Не удалось импортировать")
                    .setMessage(
                        if (error.message == null) "Файл не похож на резервную копию Health."
                        else "Файл не похож на резервную копию Health.\n\n" + error.message
                    )
                    .setPositiveButton("Понятно", null)
                    .show()
            }
        }
    }

    private fun importHistory() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Восстановить из файла?")
            .setMessage(
                "Записи из копии добавятся к текущей истории; совпадающие записи заменяются по ID, так что дубликаты не появятся. " +
                    "Дневные суммы шагов и воды восстанавливаются только за прошлые дни, сегодняшний счёт не трогается."
            )
            .setPositiveButton("Выбрать файл") { _, _ ->
                try {
                    startActivityForResult(
                        Intent(Intent.ACTION_OPEN_DOCUMENT)
                            .setType("*/*")
                            .addCategory(Intent.CATEGORY_OPENABLE)
                            .putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/json", "text/plain")),
                        83,
                    )
                } catch (error: Exception) {
                    toast("Не удалось открыть выбор файла")
                }
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun exportHistory() {
        exportText = Backup.build(this).toString()
        startActivityForResult(
            Intent(Intent.ACTION_CREATE_DOCUMENT)
                .setType("application/json")
                .addCategory(Intent.CATEGORY_OPENABLE)
                .putExtra(Intent.EXTRA_TITLE, "Health-history-" + Store.day() + ".json"),
            82,
        )
    }

    /* Восстановление после аварийных отключений службы. */
    private fun recover() {
        if (!SleepService.running && Store.obj(this, "sleep_active").has("start")) {
            val n = Store.obj(this, "sleep_active")
            if (!n.has("id")) Store.put(n, "id", "sleep-" + n.optLong("start"))
            Store.put(n, "end", n.optLong("checkpoint", n.optLong("start")))
            Store.put(n, "source", "Наблюдение прервано системой · данные до последнего сохранения")
            Store.add(this, "sleep", n)
            Store.prefs(this).edit().remove("sleep_active").apply()
        }
        if (!TrackingService.running) Store.prefs(this).edit().putBoolean("tracking", false).apply()
        if (!TrackingService.running && Store.obj(this, "active").has("start")) {
            val o = Store.obj(this, "active")
            if (!o.has("id")) Store.put(o, "id", "workout-" + o.optLong("start"))
            Store.put(o, "notes", Store.prefs(this).getString("workout_notes", ""))
            Store.put(o, "type", o.optString("type") + " · прервана")
            Store.put(o, "end", o.optLong("checkpoint"))
            Store.add(this, "workouts", o)
            Store.prefs(this).edit().remove("active").apply()
        }
    }

    override fun onResume() {
        super.onResume()
        recover()
        if (night && !Store.obj(this, "sleep_active").has("start")) build()
        else if (!night) {
            if (historyOpen) historyPage()
            else page()
        }
        Store.prefs(this).registerOnSharedPreferenceChangeListener(historyListener)
        handler.post(refresh)
    }

    override fun onPause() {
        Store.prefs(this).unregisterOnSharedPreferenceChangeListener(historyListener)
        handler.removeCallbacks(refresh)
        super.onPause()
    }

    override fun onDestroy() {
        band.disconnect()
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    /* Тренировки: выбор вида и дневник упражнений. */
    private fun workoutPicker() {
        val content = column()
        content.setPadding(dp(20), dp(8), dp(20), dp(12))
        val query = field(content, "Найти тренировку", "", false)
        val scroll = ScrollView(this)
        val items = column()
        scroll.addView(items)
        content.addView(scroll, LinearLayout.LayoutParams(-1, dp(360)))
        val dialog: AlertDialog = MaterialAlertDialogBuilder(this)
            .setTitle("Виды тренировок")
            .setView(content)
            .setNegativeButton("Закрыть", null)
            .create()
        val show: () -> Unit = {
            items.removeAllViews()
            val q = query.text.toString().lowercase(Locale("ru"))
            for (type in Workouts.TYPES) {
                if (!type.lowercase(Locale("ru")).contains(q)) continue
                row(
                    items, type,
                    if (Workouts.gps(type)) "С GPS-маршрутом" else "Таймер и заметки",
                    R.drawable.ic_activity,
                ) {
                    workoutChoice = type
                    dialog.dismiss()
                    page()
                }
            }
        }
        query.addTextChangedListener(CallbackTextWatcher { show() })
        show()
        dialog.show()
    }

    /* Еда. */
    private fun food() {
        heading("Еда", "Твой дневник приёмов пищи")
        chips(
            arrayOf(foodDay, "Сегодня"),
            arrayOf(
                {
                    val day = LocalDate.parse(foodDay)
                    android.app.DatePickerDialog(
                        this,
                        { _, y, m, d ->
                            foodDay = LocalDate.of(y, m + 1, d).toString()
                            page()
                        },
                        day.year, day.monthValue - 1, day.dayOfMonth,
                    ).show()
                },
                {
                    foodDay = Store.day()
                    page()
                },
            ),
        )
        val all = Store.array(this, "meals")
        var dayKcal = 0
        for (i in 0 until all.length()) {
            val meal = all.optJSONObject(i)
            dayKcal += FoodCatalog.kcal(meal.optString("name"), meal.optString("size", "m"))
        }
        if (dayKcal > 0) {
            val panel = surface(com.google.android.material.R.attr.colorSurfaceContainerLow)
            panel.addView(text("Калории за $foodDay", 20))
            panel.addView(text("≈ " + String.format(Locale("ru"), "%,d", dayKcal) + " ккал", 32))
            val must = text("Оценка по справочнику типовых порций; размер S/M/L уточняет цифру", 13)
            must.setTextColor(secondaryInk())
            panel.addView(must)
            gap(4)
        }
        for (kind in arrayOf("Завтрак", "Обед", "Ужин", "Перекус")) {
            val section = body!!
            var any = false
            for (i in 0 until all.length()) {
                val meal = all.optJSONObject(i)
                if (!meal.optString("day").equals(foodDay) || !meal.optString("kind").equals(kind)) continue
                if (!any) section.addView(text(kind, 20))
                any = true
                val kcal = FoodCatalog.kcal(meal.optString("name"), meal.optString("size", "m"))
                row(
                    section,
                    meal.optString("name").replaceFirst(Regex("^.*? · "), ""),
                    mealLine(meal, kcal),
                    R.drawable.ic_food,
                ) {
                    recordDetail("meals", meal)
                }
            }
            if (!any) row(section, kind, "Пока без записи", R.drawable.ic_food) {
                selectedMealKind = kind
                chooseFood()
            }
        }
        primaryButton("Добавить еду", R.drawable.ic_plus) { chooseFood() }
        gap(8)
    }

    private fun mealEntry(preset: String) {
        val form = column()
        form.setPadding(dp(24), 0, dp(24), 0)
        val name = field(form, "Блюдо или продукт", preset, false)
        val portion = field(form, "Порция: тарелка, штука, 200 г…", "", false)
        var size = "m"
        val sizeDefs = arrayOf("s" to "S", "m" to "M", "l" to "L")
        val sizeButtons = arrayOfNulls<MaterialButton>(sizeDefs.size)

        fun drawSizes() {
            for (i in 0 until sizeDefs.size) {
                val b = sizeButtons[i]!!
                val picked = sizeDefs[i].first == size
                b.backgroundTintList = android.content.res.ColorStateList.valueOf(
                    color(
                        if (picked) com.google.android.material.R.attr.colorPrimaryContainer
                        else com.google.android.material.R.attr.colorSecondaryContainer
                    )
                )
                b.setTextColor(
                    android.content.res.ColorStateList.valueOf(
                        color(
                            if (picked) com.google.android.material.R.attr.colorOnPrimaryContainer
                            else com.google.android.material.R.attr.colorOnSecondaryContainer
                        )
                    )
                )
            }
        }

        val sizes = LinearLayout(this)
        sizes.orientation = LinearLayout.HORIZONTAL
        for (i in 0 until sizeDefs.size) {
            val def = sizeDefs[i]
            val b = pill(def.second, false) { size = def.first; drawSizes() }
            sizeButtons[i] = b
            sizes.addView(b, LinearLayout.LayoutParams(0, dp(52), 1f))
        }
        drawSizes()
        form.addView(sizes)
        form.addView(text("Малая · Средняя · Большая порция", 13).apply { setTextColor(secondaryInk()) })
        val kinds = arrayOf("Завтрак", "Обед", "Ужин", "Перекус")
        val kind = Spinner(this)
        kind.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, kinds)
        kind.setSelection(kinds.indexOf(selectedMealKind))
        form.addView(kind)
        val dialog: AlertDialog = MaterialAlertDialogBuilder(this)
            .setTitle("Запись еды")
            .setView(form)
            .setPositiveButton("Сохранить", null)
            .setNegativeButton("Отмена", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (name.text.toString().trim().isEmpty()) {
                    name.error = "Введи название"
                    return@setOnClickListener
                }
                val m = JSONObject()
                Store.put(m, "name", name.text.toString().trim())
                Store.put(m, "portion", portion.text.toString())
                Store.put(m, "size", size)
                Store.put(m, "kind", kind.selectedItem)
                Store.put(m, "time", System.currentTimeMillis())
                Store.put(m, "day", foodDay)
                Store.add(this, "meals", m)
                dialog.dismiss()
                page()
            }
        }
        dialog.show()
    }

    private fun chooseFood() {
        val sheet = BottomSheetDialog(this)
        val c = column()
        c.setPadding(dp(24), dp(24), dp(24), dp(24))
        c.addView(text("Добавить еду", 26))
        val input = TextInputLayout(this, null, com.google.android.material.R.attr.textInputOutlinedStyle)
        input.hint = "Поиск блюда или продукта"
        val search = TextInputEditText(input.context)
        search.setSingleLine()
        input.addView(search)
        val ip = LinearLayout.LayoutParams(-1, -2)
        ip.setMargins(0, dp(16), 0, dp(12))
        c.addView(input, ip)
        val categories = HorizontalScrollView(this)
        categories.isHorizontalScrollBarEnabled = false
        val categoryRow = LinearLayout(this)
        for (category in arrayOf("Фрукты", "Основные блюда", "McDonald’s", "Молочное", "Напитки")) {
            val chip = pill(category, false) { search.setText(category) }
            val cp = LinearLayout.LayoutParams(-2, dp(48))
            cp.setMargins(0, 0, dp(8), 0)
            categoryRow.addView(chip, cp)
        }
        categories.addView(categoryRow)
        c.addView(categories)
        val scroll = ScrollView(this)
        val results = column()
        scroll.addView(results)
        c.addView(scroll, LinearLayout.LayoutParams(-1, dp(280)))
        fun render() {
            results.removeAllViews()
            val q = search.text.toString().trim().lowercase(Locale("ru"))
            if (q.isEmpty()) {
                row(results, "427 названий в справочнике", "Введи название или выбери категорию", R.drawable.ic_food, null)
                return
            }
            var count = 0
            for (name in FoodCatalog.FOODS) {
                if (!name.lowercase(Locale("ru")).contains(q)) continue
                if (count >= 15) break
                count++
                val parts = name.split(" · ", limit = 2)
                row(results, if (parts.size > 1) parts[1] else name, parts[0], R.drawable.ic_food) {
                    sheet.dismiss()
                    mealEntry(name)
                }
            }
            if (count == 0) row(results, "Ничего не найдено", "Можно добавить своё блюдо", R.drawable.ic_info, null)
        }
        search.addTextChangedListener(CallbackTextWatcher { render() })
        render()
        c.addView(
            pill("Своё блюдо", false) {
                sheet.dismiss()
                mealEntry(search.text.toString())
            }
        )
        sheet.setContentView(c)
        sheet.show()
        sheet.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
    }

    /* Вода и самочувствие. */
    private fun water() {
        val norm = 2000
        val sheet = BottomSheetDialog(this)
        val c = column()
        c.setPadding(dp(24), dp(8), dp(24), dp(18))
        c.addView(text("Вода", 26))
        val sub = text("Сколько выпил сегодня · норма ≈ $norm мл", 14)
        sub.setTextColor(secondaryInk())
        c.addView(sub)

        var current = Store.prefs(this).getInt("water_" + Store.day(), 0)

        /* Моделька: стакан с живой волной, поднимается вместе с каждой порцией. */
        val model = MaterialCardView(this)
        model.radius = dp(24).toFloat()
        model.cardElevation = 0f
        model.strokeWidth = 0
        model.setCardBackgroundColor(color(com.google.android.material.R.attr.colorSurfaceContainer))
        val modelBox = LinearLayout(this)
        modelBox.gravity = Gravity.CENTER_VERTICAL
        modelBox.setPadding(dp(20), dp(12), dp(20), dp(12))
        val glass = WaterGlassView(this)
        glass.setProgress(primary(), current / norm.toFloat())
        modelBox.addView(glass, LinearLayout.LayoutParams(dp(92), dp(126)))
        val facts = column()
        val number = text("$current мл", 30)
        val note = text("", 13)
        note.setTextColor(secondaryInk())
        note.setPadding(0, dp(2), 0, 0)
        val bar = LinearProgressIndicator(this)
        bar.setIndicatorColor(primary())
        bar.setTrackColor(ColorUtils.setAlphaComponent(secondaryInk(), 45))
        bar.trackCornerRadius = dp(4)
        bar.max = 100
        facts.addView(number)
        facts.addView(note)
        facts.addView(
            bar,
            LinearLayout.LayoutParams(-1, dp(8)).apply { setMargins(0, dp(10), 0, 0) },
        )
        modelBox.addView(facts, LinearLayout.LayoutParams(0, -2, 1f).apply { setMarginStart(dp(20)) })
        model.addView(modelBox)
        c.addView(
            model,
            LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, dp(16), 0, dp(14)) },
        )

        fun sync() {
            glass.setProgress(primary(), current / norm.toFloat())
            number.text = "$current мл"
            bar.progress = (100.0 * current / norm).toInt().coerceIn(0, 100)
            val glasses = current / 200
            note.text = when {
                current <= 0 -> "Стакан — 200 · Кружка — 300 · Бутылка — 500"
                current >= norm -> "Норма на день выполнена ✓"
                else -> "$glasses ${glassWord(glasses)} · ${(100.0 * current / norm).toInt()}% дневной нормы"
            }
        }

        fun addWater(ml: Int) {
            val delta = Math.max(0, current + ml) - current
            if (delta != 0 || ml < 0 && current > 0) {
                val entry = JSONObject()
                Store.put(entry, "time", System.currentTimeMillis())
                Store.put(entry, "ml", delta)
                Store.add(this, "water", entry)
            }
            current = Math.max(0, current + ml)
            Store.prefs(this).edit().putInt("water_" + Store.day(), current).apply()
            sync()
        }

        /* Чипы-порции с иконками стаканчика, стакана, кружки и бутылки. */
        fun portion(title: String, icon: Int, ml: Int): MaterialButton {
            val b = MaterialButton(this)
            b.text = title
            b.isAllCaps = false
            b.textSize = 14f
            b.minHeight = dp(46)
            b.cornerRadius = dp(23)
            b.setElevation(0f)
            b.setStrokeWidth(0)
            b.backgroundTintList = android.content.res.ColorStateList.valueOf(
                color(com.google.android.material.R.attr.colorSecondaryContainer)
            )
            b.setTextColor(
                android.content.res.ColorStateList.valueOf(
                    color(com.google.android.material.R.attr.colorOnSecondaryContainer)
                )
            )
            if (icon != 0) {
                b.setIconResource(icon)
                b.setIconGravity(MaterialButton.ICON_GRAVITY_TEXT_START)
                b.setIconSize(dp(18))
                b.setIconTint(
                    android.content.res.ColorStateList.valueOf(
                        color(com.google.android.material.R.attr.colorOnSecondaryContainer)
                    )
                )
            }
            b.setOnClickListener { addWater(ml) }
            return b
        }

        val quick = HorizontalScrollView(this)
        quick.isHorizontalScrollBarEnabled = false
        val quickRow = LinearLayout(this)
        val chip = LinearLayout.LayoutParams(-2, -2)
        chip.setMargins(0, 0, dp(10), 0)
        quickRow.addView(portion("Маленький · 100", R.drawable.ic_cup, 100), chip)
        quickRow.addView(portion("Стакан · 200", R.drawable.ic_glass, 200), chip)
        quickRow.addView(portion("Кружка · 300", R.drawable.ic_mug, 300), chip)
        quickRow.addView(portion("Бутылка · 500", R.drawable.ic_bottle, 500), chip)
        quickRow.addView(portion("Убрать 250 мл", 0, -250), chip)
        quick.addView(quickRow)
        c.addView(quick)

        /* Своя порция цифрами. */
        val customRow = LinearLayout(this)
        customRow.gravity = Gravity.BOTTOM
        val custom = TextInputLayout(this, null, com.google.android.material.R.attr.textInputOutlinedStyle)
        custom.hint = "Своя порция, мл"
        val ml = TextInputEditText(custom.context)
        ml.inputType = android.text.InputType.TYPE_CLASS_NUMBER
        ml.setSingleLine()
        custom.addView(ml)
        customRow.addView(custom, LinearLayout.LayoutParams(0, -2, 1f))
        customRow.addView(
            pill("Добавить", true) {
                val own = ml.text?.toString()?.trim()?.toIntOrNull()
                if (own != null && own != 0) {
                    addWater(own)
                    ml.setText("")
                }
            },
            LinearLayout.LayoutParams(-2, -2).apply { setMarginStart(dp(10)) },
        )
        c.addView(
            customRow,
            LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, dp(12), 0, dp(14)) },
        )

        sync()
        c.addView(
            pill("Готово", false) { sheet.dismiss() },
            LinearLayout.LayoutParams(-1, -2),
        )
        sheet.setOnDismissListener { page() }
        sheet.setContentView(c)
        sheet.show()
    }

    /** Русское склонение «стакан/стакана/стаканов». */
    private fun glassWord(glasses: Int): String = when {
        glasses % 10 == 1 && glasses % 100 != 11 -> "стакан"
        glasses % 10 in 2..4 && (glasses % 100 < 12 || glasses % 100 > 14) -> "стакана"
        else -> "стаканов"
    }

    private fun mood() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Как ты себя чувствуешь?")
            .setItems(arrayOf("Очень хорошо", "Хорошо", "Обычно", "Усталость", "Тяжёлый день")) { _, w ->
                val o = JSONObject()
                Store.put(o, "time", System.currentTimeMillis())
                Store.put(o, "value", w)
                Store.add(this, "moods", o)
                toast("Самочувствие сохранено")
            }
            .show()
    }

    private fun breathe() {
        val prompt = text("Спокойный вдох", 32)
        prompt.gravity = Gravity.CENTER
        prompt.setPadding(dp(24), dp(36), dp(24), dp(36))
        val dialog: AlertDialog = MaterialAlertDialogBuilder(this)
            .setTitle("Минута спокойного дыхания")
            .setView(prompt)
            .setNegativeButton("Завершить", null)
            .create()
        val second = intArrayOf(0)
        val timer: Runnable = object : Runnable {
            override fun run() {
                if (!dialog.isShowing) return
                second[0]++
                if (second[0] >= 60) {
                    prompt.text = "Готово"
                    return
                }
                prompt.text = if (second[0] % 10 < 4) "Спокойный вдох" else "Медленный выдох"
                handler.postDelayed(this, 1000)
            }
        }
        dialog.show()
        handler.postDelayed(timer, 1000)
    }

    /* Профиль и измерения. */
    private fun profile() {
        val saved = Store.obj(this, "profile")
        val form = column()
        form.setPadding(dp(24), 0, dp(24), 0)
        val age = field(form, "Возраст, полных лет", saved.optString("age"), true)
        val height = field(form, "Рост, см", saved.optString("height"), true)
        val weight = field(form, "Вес, кг", saved.optString("weight"), true)
        val info = text(
            "ИМТ = вес / рост². Для детей и подростков оценка зависит от возраста и пола. Здесь отображается только число без взрослых границ нормы и целей веса.",
            15,
        )
        form.addView(info)
        val dialog: AlertDialog = MaterialAlertDialogBuilder(this)
            .setTitle("Профиль и измерения")
            .setView(form)
            .setPositiveButton("Сохранить", null)
            .setNegativeButton("Отмена", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                try {
                    val a = age.text.toString().toInt()
                    val ht = height.text.toString().replace(',', '.').toDouble()
                    val wt = weight.text.toString().replace(',', '.').toDouble()
                    if (a < 1 || a > 120) throw IllegalArgumentException("Проверь возраст")
                    if (!ht.isFinite() || ht < 30 || ht > 260) throw IllegalArgumentException("Проверь рост в сантиметрах")
                    if (!wt.isFinite() || wt < 1 || wt > 500) throw IllegalArgumentException("Проверь вес в килограммах")
                    val p = JSONObject()
                    Store.put(p, "age", a)
                    Store.put(p, "height", ht)
                    Store.put(p, "weight", wt)
                    Store.put(p, "time", System.currentTimeMillis())
                    Store.put(p, "bmi", wt / Math.pow(ht / 100, 2.0))
                    Store.save(this, "profile", p)
                    Store.add(this, "measurements", p)
                    dialog.dismiss()
                    page()
                    toast(String.format(Locale.ROOT, "ИМТ: %.1f", p.optDouble("bmi")))
                } catch (error: Exception) {
                    weight.error = "Проверь возраст, рост и вес"
                }
            }
        }
        dialog.show()
    }

    private fun measurements() {
        val records = Store.array(this, "measurements")
        val summary = StringBuilder()
        for (i in 0 until records.length()) {
            val p = records.optJSONObject(i)
            summary.append(date(p.optLong("time")))
                .append(String.format(Locale.ROOT, " · %.1f кг · %.1f см · ИМТ %.1f%n", p.optDouble("weight"), p.optDouble("height"), p.optDouble("bmi")))
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("История измерений")
            .setMessage(if (summary.isEmpty()) "Добавь первое измерение в профиле." else summary.toString())
            .setPositiveButton("Добавить") { _, _ -> profile() }
            .setNegativeButton("Закрыть", null)
            .show()
    }

    private fun sets() {
        val active = Store.obj(this, "active")
        val f = column()
        f.setPadding(dp(24), 0, dp(24), 0)
        val dNote = field(f, "Упражнения, подходы, повторения", Store.prefs(this).getString("workout_notes", "") ?: "", false)
        dNote.minLines = 4
        MaterialAlertDialogBuilder(this)
            .setTitle("Дневник тренировки")
            .setView(f)
            .setPositiveButton("Сохранить") { _, _ ->
                Store.prefs(this).edit().putString("workout_notes", dNote.text.toString()).apply()
                toast("Запись сохранена")
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    /* Профиль-шторка и цель шагов. */
    private fun showProfile() {
        val sheet = BottomSheetDialog(this)
        val content = column()
        content.setPadding(dp(24), dp(24), dp(24), dp(32))
        content.addView(text("Профиль", 28))
        val p = Store.obj(this, "profile")
        if (p.has("height")) {
            val values = text(
                p.optString("age") + " лет · " + p.optString("height") + " см · " + p.optString("weight") + " кг", 17
            )
            values.setPadding(0, dp(16), 0, dp(8))
            content.addView(values)
        }
        row(content, "Личные данные", "Возраст, рост и вес", R.drawable.ic_profile) {
            sheet.dismiss()
            profile()
        }
        row(content, "История измерений", "Сохранённые записи и ИМТ", R.drawable.ic_activity) {
            sheet.dismiss()
            measurements()
        }
        row(content, "Фоновый шагомер", "Счёт шагов и настройки батареи", R.drawable.ic_walk) {
            sheet.dismiss()
            MaterialAlertDialogBuilder(this)
                .setTitle("Шаги в фоне")
                .setMessage(
                    "Включи шагомер на главной: он будет работать с постоянным уведомлением, даже при выключенном экране. " +
                        "После перезагрузки приложение попытается восстановить службу. После принудительной остановки открой Health вручную. " +
                        "Если прошивка закрывает службу, разреши фоновую работу в настройках батареи."
                )
                .setPositiveButton("Настройки") { _, _ ->
                    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
                }
                .setNegativeButton("Закрыть", null)
                .show()
        }
        row(
            content, "Цель шагов",
            String.format(Locale("ru"), "Сейчас %,d шагов в день", Store.goal(this)),
            R.drawable.ic_walk,
        ) {
            sheet.dismiss()
            goalDialog()
        }
        row(content, "Браслет", "Mi Band 7 и подключение", R.drawable.ic_band) {
            sheet.dismiss()
            tab = 3
            build()
        }
        row(content, "Самочувствие", "Записать, как прошёл день", R.drawable.ic_heart) {
            sheet.dismiss()
            mood()
        }
        row(content, "Дыхательная пауза", "Минута спокойного дыхания", R.drawable.ic_clock) {
            sheet.dismiss()
            breathe()
        }
        row(content, "История и резервная копия", "Все записи по датам", R.drawable.ic_clock) {
            sheet.dismiss()
            openHistory("all")
        }
        row(content, "Оформление", "Цвета из обоев, светлая и тёмная темы", R.drawable.ic_settings) {
            sheet.dismiss()
            themes()
        }
        val profileScroll = ScrollView(this)
        profileScroll.addView(content)
        sheet.setContentView(profileScroll)
        sheet.show()
        sheet.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
    }

    private fun goalDialog() {
        val presets = intArrayOf(8000, 10000, 12000)
        MaterialAlertDialogBuilder(this)
            .setTitle("Цель шагов")
            .setItems(arrayOf("8 000 шагов", "10 000 шагов", "12 000 шагов", "Своя цель…")) { dialog, w ->
                if (w < presets.size) {
                    Store.prefs(this).edit().putInt("steps_goal", presets[w]).apply()
                    dialog.dismiss()
                    toast(String.format(Locale("ru"), "Цель: %,d шагов", presets[w]))
                    page()
                } else {
                    dialog.dismiss()
                    val form = column()
                    form.setPadding(dp(24), 0, dp(24), 0)
                    val input = field(form, "Сколько шагов в день?", Store.goal(this).toString(), true)
                    MaterialAlertDialogBuilder(this)
                        .setTitle("Своя цель")
                        .setView(form)
                        .setPositiveButton("Сохранить") { _, _ ->
                            val value = input.text.toString().toIntOrNull()
                            if (value != null && value in 1000..100000) {
                                Store.prefs(this).edit().putInt("steps_goal", value).apply()
                                toast("Цель: $value шагов")
                                page()
                            } else toast("Укажи число от 1000 до 100000")
                        }
                        .setNegativeButton("Отмена", null)
                        .show()
                }
            }
            .setNegativeButton("Закрыть", null)
            .show()
    }

    private fun sleepOptions() {
        val sheet = BottomSheetDialog(this)
        val c = column()
        c.setPadding(dp(24), dp(24), dp(24), dp(32))
        c.addView(text("Настройки сна", 28))
        row(
            c, "Ночной экран",
            if (Store.prefs(this).getInt("night_mode", 0) == 0) "Экран гаснет, запись продолжается"
            else "Тусклые часы на чёрном фоне",
            R.drawable.ic_sleep,
        ) {
            sheet.dismiss()
            MaterialAlertDialogBuilder(this)
                .setTitle("Ночной экран")
                .setSingleChoiceItems(
                    arrayOf("Экран выключен", "Очень тусклые часы"),
                    Store.prefs(this).getInt("night_mode", 0),
                ) { d, w ->
                    Store.prefs(this).edit().putInt("night_mode", w).apply()
                    d.dismiss()
                }
                .setNegativeButton("Закрыть", null)
                .show()
        }
        val auto = MaterialSwitch(this)
        auto.text = "Оценка отдыха по экрану"
        auto.isChecked = Store.prefs(this).getBoolean("auto_sleep", false)
        auto.setPadding(0, dp(16), 0, dp(16))
        auto.setOnCheckedChangeListener { _, v ->
            Store.prefs(this).edit().putBoolean("auto_sleep", v).apply()
            if (v) permission(false) { command("ON", null) }
        }
        c.addView(auto)
        row(c, "Фоновая работа", "Открыть системные настройки Health", R.drawable.ic_settings) {
            sheet.dismiss()
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }
        row(c, "Как работает наблюдение", "Датчики, размещение телефона и точность", R.drawable.ic_info) {
            sheet.dismiss()
            MaterialAlertDialogBuilder(this)
                .setTitle("Наблюдение за сном")
                .setMessage(
                    "Телефон на матрасе записывает интенсивность движений. Утром доступен график, а не достоверные стадии сна.\n\n" +
                        "При выключенном экране служба продолжает запись. На некоторых устройствах требуется удержание процессора активным, поэтому расход батареи выше. Предел — 14 часов.\n\n" +
                        "Оценка по экрану — отдельная грубая эвристика: 2–14 часов без включения, начало между 20:00 и 06:00. Она не подтверждает сон."
                )
                .setPositiveButton("Понятно", null)
                .show()
        }
        sheet.setContentView(c)
        sheet.show()
        sheet.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
    }

    /* История. */
    val historyListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if ("history_revision" == key) {
            handler.postDelayed({
                if (night) {
                    if (!Store.obj(this, "sleep_active").has("start")) build()
                } else if (historyOpen) historyPage()
                else page()
            }, 200)
        }
    }

    private fun openHistory(kind: String) {
        historyOpen = true
        historyKind = kind
        historyLimit = 40
        historyPage()
    }

    private fun filterHistory(kind: String) {
        historyKind = kind
        historyLimit = 40
        historyPage()
    }

    private fun historyPage() {
        historyOpen = true
        body?.removeAllViews()
        live = null
        sleepLive = null
        heading("История", if (historyDay.isEmpty()) "Все сохранённые записи" else historyDay)
        chips(
            arrayOf("Все", "Шаги", "Тренировки", "Сон", "Еда", "Измерения", "Вода", "Самочувствие"),
            arrayOf(
                { filterHistory("all") },
                { filterHistory("steps") },
                { filterHistory("workouts") },
                { filterHistory("sleep") },
                { filterHistory("meals") },
                { filterHistory("measurements") },
                { filterHistory("water") },
                { filterHistory("moods") },
            ),
        )
        chips(
            arrayOf(
                if (historyDay.isEmpty()) "Выбрать дату" else historyDay,
                "Все даты",
            ),
            arrayOf(
                {
                    val d = if (historyDay.isEmpty()) LocalDate.now() else LocalDate.parse(historyDay)
                    android.app.DatePickerDialog(
                        this,
                        { _, y, m, day ->
                            historyDay = LocalDate.of(y, m + 1, day).toString()
                            historyLimit = 40
                            historyPage()
                        },
                        d.year, d.monthValue - 1, d.dayOfMonth,
                    ).show()
                },
                {
                    historyDay = ""
                    historyPage()
                },
            ),
        )
        val list = ArrayList<JSONObject>()
        for (key in Store.HISTORY) {
            if (historyKind != "all" && historyKind != key) continue
            val a = Store.array(this, key)
            for (i in 0 until a.length()) {
                val o = a.optJSONObject(i) ?: continue
                val timestamp = o.optLong("start", o.optLong("time", 0))
                val day = o.optString(
                    "day",
                    Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault()).toLocalDate().toString(),
                )
                if (historyDay.isNotEmpty() && historyDay != day) continue
                val item = JSONObject()
                Store.put(item, "kind", key)
                Store.put(item, "value", o)
                Store.put(item, "timestamp", timestamp)
                list.add(item)
            }
        }
        if (historyKind == "all" || historyKind == "steps") {
            for (entry in Store.prefs(this).all.entries) {
                if (!entry.key.startsWith("steps_20")) continue
                val day = entry.key.substring(6)
                if (historyDay.isNotEmpty() && historyDay != day) continue
                try {
                    val time = LocalDate.parse(day).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
                    val o = JSONObject()
                    Store.put(o, "day", day)
                    Store.put(o, "time", time)
                    Store.put(o, "steps", entry.value)
                    val item = JSONObject()
                    Store.put(item, "kind", "steps")
                    Store.put(item, "value", o)
                    Store.put(item, "timestamp", time)
                    list.add(item)
                } catch (ignored: java.time.DateTimeException) {}
            }
        }
        list.sortWith { a, b ->
            val av = a.optJSONObject("value")
            val bv = b.optJSONObject("value")
            val ad = av.optString(
                "day",
                Instant.ofEpochMilli(a.optLong("timestamp")).atZone(ZoneId.systemDefault()).toLocalDate().toString(),
            )
            val bd = bv.optString(
                "day",
                Instant.ofEpochMilli(b.optLong("timestamp")).atZone(ZoneId.systemDefault()).toLocalDate().toString(),
            )
            val order = bd.compareTo(ad)
            if (order != 0) order else bv.optLong("timestamp").compareTo(a.optLong("timestamp"))
        }
        var current = ""
        for (i in 0 until Math.min(historyLimit, list.size)) {
            val item = list[i]
            val o = item.optJSONObject("value")
            val key = item.optString("kind")
            val dateGroup = o.optString(
                "day",
                Instant.ofEpochMilli(item.optLong("timestamp")).atZone(ZoneId.systemDefault()).toLocalDate().toString(),
            )
            if (current != dateGroup) {
                label(dateGroup)
                current = dateGroup
            }
            row(body!!, recordTitle(key, o), recordSubtitle(key, o), recordIcon(key)) {
                recordDetail(key, o)
            }
        }
        if (list.isEmpty()) emptyState("Записей пока нет", "Измени фильтр или сохрани первую запись.", R.drawable.ic_clock)
        if (list.size > historyLimit) button("Показать ещё") {
            historyLimit += 40
            historyPage()
        }
        gap(12)
        row(body!!, "Резервная копия", "Сохранить всю историю в JSON", R.drawable.ic_info) { exportHistory() }
        row(body!!, "Восстановить из файла", "Импорт JSON-копии с объединением", R.drawable.ic_plus) { importHistory() }
    }

    private fun recordTitle(kind: String, o: JSONObject): String = when (kind) {
        "steps" -> o.optInt("steps").toString() + " шагов · телефон"
        "workouts" -> o.optString("type", "Тренировка")
        "sleep" -> "Наблюдение за сном"
        "meals" -> o.optString("kind", "Еда") + " · " + o.optString("name")
        "measurements" -> o.optString("weight") + " кг · " + o.optString("height") + " см"
        "water" -> "Вода · " + o.optInt("ml") + " мл"
        else -> "Самочувствие"
    }

    /** Подзаголовок записи еды: порция · размер · оценка калорий. */
    private fun mealLine(m: JSONObject, kcal: Int): String {
        val size = if (m.optString("size").isEmpty()) "M" else m.optString("size").uppercase()
        val bits = ArrayList<String>()
        val portion = m.optString("portion")
        if (portion.isNotEmpty()) bits.add(portion)
        bits.add(size)
        if (kcal > 0) bits.add("≈ $kcal ккал")
        return bits.joinToString(" · ")
    }

    /** Человеческая оценка тихости ночи — по движениям, не диагноз. */
    private fun sleepWord(pct: Int): String = when {
        pct >= 70 -> "очень тихая ночь"
        pct >= 55 -> "спокойная ночь"
        pct >= 40 -> "ночь с перепадами"
        else -> "беспокойная ночь"
    }

    private fun recordSubtitle(kind: String, o: JSONObject): String {
        if (kind == "sleep") {
            val pct = sleepStats(o).optInt("stillPct")
            return date(o.optLong("start")) + " · " + Store.duration(o.optLong("end") - o.optLong("start")) +
                " · покой ≈" + pct + "% · " + sleepWord(pct)
        }
        return when (kind) {
        "steps" -> o.optString("day") + " · ≈ " + String.format(
            Locale.ROOT, "%.1f км", Store.stepsKm(this, o.optInt("steps"))
        )
        "workouts" -> date(o.optLong("start")) + " · " + Store.duration(o.optLong("elapsed"))
        "meals" -> o.optString("kind") + " · " +
            mealLine(o, FoodCatalog.kcal(o.optString("name"), o.optString("size", "m")))
        "measurements" -> date(o.optLong("time")) + String.format(Locale.ROOT, " · ИМТ %.1f", o.optDouble("bmi"))
        "moods" -> arrayOf("Очень хорошо", "Хорошо", "Обычно", "Усталость", "Тяжёлый день")[
            Math.max(0, Math.min(4, o.optInt("value")))
        ]
        else -> date(o.optLong("time"))
        }
    }

    private fun recordIcon(kind: String): Int = when (kind) {
        "sleep" -> R.drawable.ic_sleep
        "meals" -> R.drawable.ic_food
        "water" -> R.drawable.ic_water
        "moods" -> R.drawable.ic_heart
        else -> R.drawable.ic_activity
    }

    private fun recordDetail(kind: String, o: JSONObject) {
        if (kind == "steps" || kind == "water") {
            MaterialAlertDialogBuilder(this)
                .setTitle(recordTitle(kind, o))
                .setMessage(
                    if (kind == "steps") o.optString("day") + "\n≈ " +
                        String.format(Locale.ROOT, "%.1f км", Store.stepsKm(this, o.optInt("steps"))) +
                        "\nШаги, накопленные датчиком телефона за этот день."
                    else recordSubtitle(kind, o)
                )
                .setPositiveButton("Закрыть", null)
                .show()
            return
        }
        val sheet = BottomSheetDialog(this)
        val c = column()
        c.setPadding(dp(24), dp(24), dp(24), dp(24))
        c.addView(text(recordTitle(kind, o), 24))
        val sub = text(recordSubtitle(kind, o), 15)
        sub.setPadding(0, dp(12), 0, dp(16))
        c.addView(sub)
        if (kind == "meals") {
            row(
                c, "Как посчитаны калории",
                FoodCatalog.kcalSource(o.optString("name")) + " · размер " +
                    (if (o.optString("size").isEmpty()) "M" else o.optString("size").uppercase()),
                R.drawable.ic_info, null,
            )
        }
        if (kind == "workouts") {
            row(c, "Экспорт GPX", "Сохранить GPS-трек в файл", R.drawable.ic_info) {
                sheet.dismiss()
                export(o.optJSONArray("points"))
            }
            row(c, "Маршрут и результаты", o.optInt("stepCount").toString() + " шагов", R.drawable.ic_activity) {
                sheet.dismiss()
                route(o)
            }
        }
        if (kind == "sleep") {
            row(c, "График движений", o.optString("source"), R.drawable.ic_sleep) {
                sheet.dismiss()
                sleepDetail(o)
            }
        }
        val notes = o.optString("notes", "")
        if (notes.isNotEmpty()) c.addView(text(notes, 16))
        row(c, "Изменить заметку", "Добавить детали к записи", R.drawable.ic_plus) {
            sheet.dismiss()
            val f = column()
            f.setPadding(dp(24), 0, dp(24), 0)
            val input = field(f, "Заметка", notes, false)
            MaterialAlertDialogBuilder(this)
                .setTitle("Заметка")
                .setView(f)
                .setPositiveButton("Сохранить") { _, _ ->
                    Store.put(o, "notes", input.text.toString())
                    Store.add(this, kind, o)
                    historyPage()
                }
                .setNegativeButton("Отмена", null)
                .show()
        }
        row(c, "Удалить запись", "Потребуется подтверждение", R.drawable.ic_info) {
            sheet.dismiss()
            MaterialAlertDialogBuilder(this)
                .setTitle("Удалить запись?")
                .setMessage(recordTitle(kind, o))
                .setPositiveButton("Удалить") { _, _ ->
                    Store.remove(this, kind, o.optString("id"))
                    historyPage()
                }
                .setNegativeButton("Отмена", null)
                .show()
        }
        val scroll = ScrollView(this)
        scroll.addView(c)
        sheet.setContentView(scroll)
        sheet.show()
        sheet.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
    }
}

/** Минимальный наблюдатель текста, чтобы не дублировать пустые методы. */
class CallbackTextWatcher(private val onChange: () -> Unit) : android.text.TextWatcher {
    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
        onChange()
    }

    override fun afterTextChanged(s: android.text.Editable?) = Unit
}
