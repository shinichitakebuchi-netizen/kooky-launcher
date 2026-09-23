package com.kooky.launcher

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.Settings
import android.text.Editable
import android.text.SpannableString
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.RelativeSizeSpan
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.GridView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

class MainActivity : Activity() {

    data class AppEntry(val label: String, val packageName: String, val icon: Drawable)

    private val apps = mutableListOf<AppEntry>()
    private val shown = mutableListOf<AppEntry>()
    private lateinit var prefs: SharedPreferences
    private val appAdapter = AppAdapter()

    private lateinit var searchBar: EditText
    private lateinit var clockText: TextView
    private lateinit var dateText: TextView
    private lateinit var weatherView: WeatherView

    private val gridColumns = 4
    private val topGapDp = 380
    private val appsTopPadDp = 4

    // 位置が取れなかったときの保険＝前橋
    private val fallbackLat = 36.389
    private val fallbackLon = 139.063
    private val LOC_REQ = 1001

    private var showLabels = true
    private var useDesignIcon = true
    private var showInitial = true

    private val readingMap = mapOf(
        "あはも" to "ahamo",
        "ぺいぺい" to "paypay",
        "らいん" to "line"
    )

    private val timeHandler = Handler(Looper.getMainLooper())
    private val hmFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val secFormat = SimpleDateFormat("ss", Locale.getDefault())
    private val dateFormat = SimpleDateFormat("yyyy.MM.dd-EEE", Locale.JAPANESE)
    private val tick = object : Runnable {
        override fun run() {
            val now = Date()
            val hm = hmFormat.format(now)
            val ss = secFormat.format(now)
            val text = "$hm $ss"
            val sp = SpannableString(text)
            sp.setSpan(RelativeSizeSpan(0.6f), hm.length + 1, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            clockText.text = sp
            dateText.text = dateFormat.format(now)
            timeHandler.postDelayed(this, 1000)
        }
    }

    private val weatherTick = object : Runnable {
        override fun run() {
            updateWeatherAtCurrentLocation()
            timeHandler.postDelayed(this, 60 * 60 * 1000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("usage", Context.MODE_PRIVATE)
        showLabels = prefs.getBoolean("show_labels", true)
        useDesignIcon = prefs.getBoolean("use_design_icon", true)
        showInitial = prefs.getBoolean("show_initial", true)

        loadApps()

        NotificationService.onChange = {
            runOnUiThread { appAdapter.notifyDataSetChanged() }
        }

        weatherView = WeatherView(this).apply {
            weatherType = prefs.getInt("weather_type", 0)
        }

        clockText = TextView(this).apply {
            textSize = 46f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setTypeface(typeface, Typeface.BOLD)
            setOnClickListener { openClock() }
            setOnLongClickListener { showClockDiagnostics(); true }
        }
        dateText = TextView(this).apply {
            textSize = 16f
            setTextColor(Color.parseColor("#CCCCCC"))
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, 0)
            setOnClickListener { openCalendar() }
        }
        val topArea = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(topGapDp))
            addView(weatherView, LinearLayout.LayoutParams(dp(72), dp(72)))
            addView(clockText)
            addView(dateText)
        }

        searchBar = EditText(this).apply {
            setSingleLine(true)
            setTextColor(Color.WHITE)
            setHintTextColor(Color.parseColor("#888888"))
            background = null
            val p = dp(10)
            setPadding(p, p, p, p)
            isFocusable = false
            isFocusableInTouchMode = false
            addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) { applyFilter() }
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            })
            setOnTouchListener { _, event ->
                if (event.action == MotionEvent.ACTION_UP) {
                    isFocusableInTouchMode = true
                    isFocusable = true
                    requestFocus()
                    val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                    imm.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
                }
                false
            }
            setOnEditorActionListener { _, _, _ ->
                if (shown.isNotEmpty()) launchApp(shown[0].packageName)
                true
            }
        }

        val searchIcon = ImageView(this).apply {
            setImageDrawable(circleIcon())
            val p = dp(8)
            setPadding(p, p, p, p)
            setOnLongClickListener { showSettingsMenu(); true }
        }

        val searchBarRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(6).toFloat()
                setColor(Color.TRANSPARENT)
                setStroke(dp(2), Color.parseColor("#FF8C00"))
            }
            val ph = dp(6)
            setPadding(ph, 0, ph, 0)
            addView(searchBar, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(searchIcon, LinearLayout.LayoutParams(dp(40), dp(40)))
        }

        val grid = GridView(this).apply {
            numColumns = gridColumns
            val pad = dp(12)
            setPadding(pad, dp(appsTopPadDp), pad, dp(24))
            clipToPadding = false
            verticalSpacing = dp(16)
            adapter = appAdapter
            setOnItemClickListener { _, _, position, _ ->
                launchApp(shown[position].packageName)
            }
            setOnItemLongClickListener { _, _, position, _ ->
                renameApp(shown[position]); true
            }
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#101014"))
            isFocusable = true
            isFocusableInTouchMode = true
            val m = dp(12)
            addView(topArea)
            addView(searchBarRow, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                setMargins(m, 0, m, dp(8))
            })
            addView(grid, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }

        setContentView(content)

        applyFilter()
        maybeAskNotificationAccess()
        maybeAskLocation()
    }

    override fun onResume() {
        super.onResume()
        sortApps()
        applyFilter()
        resetSearch()
        appAdapter.notifyDataSetChanged()
        timeHandler.removeCallbacks(tick)
        timeHandler.post(tick)
        timeHandler.removeCallbacks(weatherTick)
        timeHandler.post(weatherTick)
    }

    override fun onPause() {
        super.onPause()
        timeHandler.removeCallbacks(tick)
        timeHandler.removeCallbacks(weatherTick)
    }

    // 位置情報の許可を求める（未許可なら）
    private fun maybeAskLocation() {
        val ok = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                 checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!ok) {
            requestPermissions(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                LOC_REQ)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == LOC_REQ) {
            updateWeatherAtCurrentLocation() // 許可の可否に関わらず、取れる範囲で天気更新
        }
    }

    // 現在地を取得して、その地点の天気を更新（取れなければ前橋）
    private fun updateWeatherAtCurrentLocation() {
        var lat = fallbackLat
        var lon = fallbackLon
        try {
            val granted = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                          checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
            if (granted) {
                val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
                // 最後に取得された位置（GPS→ネットワークの順で拾う）
                val loc = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                    ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                    ?: lm.getLastKnownLocation(LocationManager.PASSIVE_PROVIDER)
                if (loc != null) { lat = loc.latitude; lon = loc.longitude }
            }
        } catch (e: Exception) { /* 取れなければ前橋のまま */ }
        fetchWeather(lat, lon)
    }

    private fun fetchWeather(lat: Double, lon: Double) {
        thread {
            try {
                val url = URL("https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon&current=weather_code,is_day")
                val conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 8000; conn.readTimeout = 8000
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                conn.disconnect()
                val code = Regex("\"weather_code\"\\s*:\\s*(\\d+)").find(body)?.groupValues?.get(1)?.toInt() ?: 0
                val isDay = Regex("\"is_day\"\\s*:\\s*(\\d+)").find(body)?.groupValues?.get(1)?.toInt() ?: 1
                val type = weatherCodeToType(code, isDay == 1)
                prefs.edit().putInt("weather_type", type).apply()
                runOnUiThread { weatherView.weatherType = type }
            } catch (e: Exception) { /* 失敗時は前回の天気のまま */ }
        }
    }

    private fun weatherCodeToType(code: Int, isDay: Boolean): Int {
        return when (code) {
            0, 1 -> if (isDay) 0 else 5
            2, 3, 45, 48 -> 1
            51,53,55,56,57,61,63,65,66,67,80,81,82 -> 2
            71,73,75,77,85,86 -> 4
            95,96,99 -> 3
            else -> 1
        }
    }

    private fun circleIcon(): Drawable {
        val size = dp(24)
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; color = Color.WHITE; strokeWidth = dp(2).toFloat()
        }
        canvas.drawCircle(size / 2f, size / 2f, size / 2f - dp(2), p)
        return BitmapDrawable(resources, bmp)
    }

    private fun showSettingsMenu() {
        val items = arrayOf(
            if (showLabels) "アプリ名: 表示 → 非表示にする" else "アプリ名: 非表示 → 表示にする",
            if (useDesignIcon) "アイコン: デザイン → 元に戻す" else "アイコン: 元 → デザインにする",
            if (showInitial) "記号: 頭文字(Y) → 記号なし(X)" else "記号: 記号なし(X) → 頭文字(Y)"
        )
        AlertDialog.Builder(this)
            .setTitle("設定")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> { showLabels = !showLabels; prefs.edit().putBoolean("show_labels", showLabels).apply() }
                    1 -> { useDesignIcon = !useDesignIcon; prefs.edit().putBoolean("use_design_icon", useDesignIcon).apply() }
                    2 -> { showInitial = !showInitial; prefs.edit().putBoolean("show_initial", showInitial).apply() }
                }
                appAdapter.notifyDataSetChanged()
            }
            .show()
    }

    private fun renameApp(app: AppEntry) {
        val input = EditText(this).apply {
            setText(displayName(app)); setSingleLine(true)
        }
        AlertDialog.Builder(this)
            .setTitle("名前を変更")
            .setView(input)
            .setPositiveButton("保存") { _, _ ->
                val newName = input.text.toString().trim()
                if (newName.isNotEmpty()) prefs.edit().putString("name_" + app.packageName, newName).apply()
                else prefs.edit().remove("name_" + app.packageName).apply()
                sortApps(); applyFilter()
            }
            .setNegativeButton("元に戻す") { _, _ ->
                prefs.edit().remove("name_" + app.packageName).apply()
                sortApps(); applyFilter()
            }
            .setNeutralButton("キャンセル", null)
            .show()
    }

    private fun displayName(app: AppEntry): String =
        prefs.getString("name_" + app.packageName, null) ?: app.label

    private fun maybeAskNotificationAccess() {
        val enabled = Settings.Secure.getString(
            contentResolver, "enabled_notification_listeners"
        )?.contains(packageName) == true
        if (!enabled && !prefs.getBoolean("asked_notif", false)) {
            prefs.edit().putBoolean("asked_notif", true).apply()
            Toast.makeText(this,
                "通知バッジを使うには「通知へのアクセス」でKooky Launcherを許可してください",
                Toast.LENGTH_LONG).show()
            try {
                startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"))
            } catch (e: Exception) {}
        }
    }

    private fun openClock() {
        val clockPackages = listOf("com.google.android.deskclock", "com.sec.android.app.clockpackage")
        for (pkg in clockPackages) {
            val intent = packageManager.getLaunchIntentForPackage(pkg)
            if (intent != null) { intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); startActivity(intent); return }
        }
        try {
            startActivity(Intent(Intent.ACTION_MAIN).addCategory("android.intent.category.APP_DESKCLOCK")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return
        } catch (e: Exception) {}
        try {
            startActivity(Intent(AlarmClock.ACTION_SHOW_ALARMS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return
        } catch (e: Exception) {}
        Toast.makeText(this, "時計アプリが見つかりませんでした", Toast.LENGTH_SHORT).show()
    }

    private fun showClockDiagnostics() {
        val pm = packageManager
        val intent = Intent(Intent.ACTION_MAIN, null).apply { addCategory(Intent.CATEGORY_LAUNCHER) }
        val all = pm.queryIntentActivities(intent, 0)
        val hits = all.map { it.activityInfo.packageName }
            .filter { it.contains("clock", true) || it.contains("deskclock", true) || it.contains("alarm", true) }
            .distinct()
        val msg = if (hits.isEmpty()) "時計候補が見つかりません。一覧の時計アプリ名を教えてください"
        else "時計候補: " + hits.joinToString(" / ")
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }

    private fun openCalendar() {
        try {
            val builder = CalendarContract.CONTENT_URI.buildUpon().appendPath("time")
            ContentUris.appendId(builder, System.currentTimeMillis())
            startActivity(Intent(Intent.ACTION_VIEW,
