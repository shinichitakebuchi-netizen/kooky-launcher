package com.kooky.launcher

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.os.BatteryManager
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
    private lateinit var statusArc: StatusArcView

    private val gridColumns = 4
    private val appsTopPadDp = 4
    private val sideMarginDp = 12
    private val cellPadDp = 14
    private val gridRows = 3
    private val cellHeightDp = 108

    private val releasesUrl = "https://github.com/shinichitakebuchi-netizen/kooky-launcher/releases/latest"

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

    private var netCallback: ConnectivityManager.NetworkCallback? = null

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            if (level >= 0 && scale > 0) statusArc.ratio = level.toFloat() / scale.toFloat()
        }
    }

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
            updateWeatherAtCurrentLocation(false)
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
            setOnClickListener {
                Toast.makeText(this@MainActivity, "天気を更新中…", Toast.LENGTH_SHORT).show()
                updateWeatherAtCurrentLocation(true)
            }
        }
        statusArc = StatusArcView(this).apply {
            setOnClickListener { showBatteryNumber = !showBatteryNumber }
            setOnLongClickListener { showNetDiagnostics(); true }
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

        val weatherRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
            addView(weatherView, LinearLayout.LayoutParams(dp(72), dp(72)))
            addView(statusArc, LinearLayout.LayoutParams(dp(36), dp(36)).apply {
                leftMargin = dp(10)
                bottomMargin = dp(4)
            })
        }

        val topArea = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            addView(weatherRow, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = dp(2)
            })
            addView(clockText, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(dateText, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
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
            setPadding(0, 0, 0, 0)
            addView(searchBar, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(searchIcon, LinearLayout.LayoutParams(dp(40), dp(40)))
        }

        val grid = GridView(this).apply {
            numColumns = gridColumns
            val pad = dp(sideMarginDp)
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
            setBackgroundColor(Color.BLACK)
            isFocusable = true
            isFocusableInTouchMode = true
            addView(topArea, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            val barMargin = dp(sideMarginDp + cellPadDp)
            addView(searchBarRow, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                setMargins(barMargin, 0, barMargin, dp(8))
            })
            addView(grid, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(gridRows * cellHeightDp)))
        }

        setContentView(content)

        applyFilter()
        maybeAskNotificationAccess()
        maybeAskLocation()
    }

    private fun hideSystemBars() {
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            or View.SYSTEM_UI_FLAG_FULLSCREEN
            or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        )
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
        registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        registerNetworkCallback()
        updateConnectivity()
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
        try { unregisterReceiver(batteryReceiver) } catch (e: Exception) {}
        unregisterNetworkCallback()
        timeHandler.removeCallbacks(tick)
        timeHandler.removeCallbacks(weatherTick)
    }

    private fun registerNetworkCallback() {
        try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val cb = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) { runOnUiThread { updateConnectivity() } }
                override fun onLost(network: Network) { runOnUiThread { updateConnectivity() } }
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                    runOnUiThread { updateConnectivity() }
                }
            }
            cm.registerNetworkCallback(NetworkRequest.Builder().build(), cb)
            netCallback = cb
        } catch (e: Exception) {}
    }

    private fun unregisterNetworkCallback() {
        try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            netCallback?.let { cm.unregisterNetworkCallback(it) }
            netCallback = null
        } catch (e: Exception) {}
    }

    // 実際にネットに出られる回線だけをWiFi/モバイルとして判定
    private fun readNet(): Pair<Boolean, Boolean> {
        var wifi = false
        var mobile = false
        try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            for (n in cm.allNetworks) {
                val caps = cm.getNetworkCapabilities(n) ?: continue
                val usable = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                             caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                if (!usable) continue
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) wifi = true
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) mobile = true
            }
        } catch (e: Exception) {}
        return Pair(wifi, mobile)
    }

    private fun updateConnectivity() {
        val (wifi, mobile) = readNet()
        statusArc.wifiOn = wifi
        statusArc.mobileOn = mobile
    }

    private fun showNetDiagnostics() {
        val sb = StringBuilder()
        try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val nets = cm.allNetworks
            sb.append("回線数=${nets.size}\n")
            for (n in nets) {
                val caps = cm.getNetworkCapabilities(n)
                val w = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
                val c = caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true
                val v = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
                sb.append("・WiFi=$w Cell=$c 疎通=$v\n")
            }
        } catch (e: Exception) { sb.append("例外:${e.message}") }
        val (wifi, mobile) = readNet()
        sb.append("判定 WiFi=$wifi / モバイル=$mobile")
        Toast.makeText(this, sb.toString(), Toast.LENGTH_LONG).show()
    }

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
            updateWeatherAtCurrentLocation(false)
        }
    }

    private fun updateWeatherAtCurrentLocation(showResult: Boolean) {
        var lat = fallbackLat
        var lon = fallbackLon
        try {
            val granted = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                          checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
            if (granted) {
                val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
                val loc = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                    ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                    ?: lm.getLastKnownLocation(LocationManager.PASSIVE_PROVIDER)
                if (loc != null) { lat = loc.latitude; lon = loc.longitude }
            }
        } catch (e: Exception) {}
        fetchWeather(lat, lon, showResult)
    }

    private fun fetchWeather(lat: Double, lon: Double, showResult: Boolean) {
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
                runOnUiThread {
                    weatherView.weatherType = type
                    if (showResult) Toast.makeText(this, "天気を更新しました", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                if (showResult) runOnUiThread {
                    Toast.makeText(this, "天気の更新に失敗しました", Toast.LENGTH_SHORT).show()
                }
            }
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
        fun box(on: Boolean) = if (on) "■" else "□"
        fun buildItems() = arrayOf(
            "【NAME】     ${box(showLabels)}",
            "【ICON】      ${box(useDesignIcon)}",
            "【INITIAL】  ${box(showInitial)}",
            "【UPDATE】"
        )
        AlertDialog.Builder(this)
            .setTitle("設定")
            .setItems(buildItems()) { d, which ->
                when (which) {
                    0 -> { showLabels = !showLabels; prefs.edit().putBoolean("show_labels", showLabels).apply() }
                    1 -> { useDesignIcon = !useDesignIcon; prefs.edit().putBoolean("use_design_icon", useDesignIcon).apply() }
                    2 -> { showInitial = !showInitial; prefs.edit().putBoolean("show_initial", showInitial).apply() }
                    3 -> { openUpdatePage(); return@setItems }
                }
                appAdapter.notifyDataSetChanged()
                d.dismiss()
                showSettingsMenu()
            }
            .show()
    }

    private fun openUpdatePage() {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(releasesUrl))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            Toast.makeText(this, "更新ページを開けませんでした", Toast.LENGTH_SHORT).show()
        }
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
            startActivity(Intent(Intent.ACTION_VIEW, builder.build()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            Toast.makeText(this, "カレンダーを開けませんでした", Toast.LENGTH_SHORT).show()
        }
    }

    private fun resetSearch() {
        searchBar.setText("")
        searchBar.clearFocus()
        searchBar.isFocusable = false
        searchBar.isFocusableInTouchMode = false
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(searchBar.windowToken, 0)
    }

    override fun onBackPressed() {
        if (!searchBar.text.isNullOrEmpty()) resetSearch() else super.onBackPressed()
    }

    private fun loadApps() {
        val pm = packageManager
        val intent = Intent(Intent.ACTION_MAIN, null).apply { addCategory(Intent.CATEGORY_LAUNCHER) }
        val list: List<ResolveInfo> = pm.queryIntentActivities(intent, 0)
        apps.clear()
        for (ri in list) {
            val pkg = ri.activityInfo.packageName
            if (pkg == packageName) continue
            apps.add(AppEntry(ri.loadLabel(pm).toString(), pkg, ri.loadIcon(pm)))
        }
        sortApps()
    }

    private fun sortApps() {
        apps.sortWith(
            compareByDescending<AppEntry> { prefs.getInt(it.packageName, 0) }
                .thenBy { displayName(it).lowercase() }
        )
    }

    private fun toHiragana(s: String): String {
        val sb = StringBuilder()
        for (c in s) { if (c in '\u30A1'..'\u30F6') sb.append(c - 0x60) else sb.append(c) }
        return sb.toString()
    }

    private fun applyFilter() {
        val raw = searchBar.text?.toString()?.trim() ?: ""
        shown.clear()
        if (raw.isEmpty()) {
            shown.addAll(apps)
        } else {
            val q = toHiragana(raw.lowercase())
            val extra = readingMap.entries.filter { it.key.contains(q) || q.contains(it.key) }.map { it.value }
            shown.addAll(apps.filter { app ->
                val name = displayName(app).lowercase()
                toHiragana(name).contains(q) || extra.any { name.contains(it) }
            })
        }
        appAdapter.notifyDataSetChanged()
    }

    private fun launchApp(pkg: String) {
        val count = prefs.getInt(pkg, 0) + 1
        prefs.edit().putInt(pkg, count).apply()
        resetSearch()
        packageManager.getLaunchIntentForPackage(pkg)?.let { startActivity(it) }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun withBadge(base: Drawable): Drawable {
        val size = dp(56)
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        base.setBounds(0, 0, size, size); base.draw(canvas)
        drawDot(canvas, size)
        return BitmapDrawable(resources, bmp)
    }

    private fun designIcon(app: AppEntry, withNotif: Boolean): Drawable {
        val size = dp(56)
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val inset = dp(2).toFloat()
        val radius = dp(6).toFloat()
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
        canvas.drawRoundRect(inset, inset, size - inset, size - inset, radius, radius, fill)
        val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; color = Color.WHITE; strokeWidth = dp(2).toFloat()
        }
        canvas.drawRoundRect(inset, inset, size - inset, size - inset, radius, radius, border)
        if (showInitial) {
            val ch = firstChar(displayName(app))
            if (ch.isNotEmpty()) {
                val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.WHITE; textSize = size * 0.42f
                    textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT
                }
                val cy = size / 2f - (tp.descent() + tp.ascent()) / 2f
                canvas.drawText(ch, size / 2f, cy, tp)
            }
        }
        if (withNotif) drawDot(canvas, size)
        return BitmapDrawable(resources, bmp)
    }

    private fun firstChar(name: String): String {
        val t = name.trim()
        return if (t.isEmpty()) "" else t.substring(0, 1).uppercase()
    }

    private fun drawDot(canvas: Canvas, size: Int) {
        val radius = dp(7).toFloat()
        val cx = size - radius - dp(2)
        val cy = radius + dp(2)
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        canvas.drawCircle(cx, cy, radius + dp(2), stroke)
        val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#FF8C00") }
        canvas.drawCircle(cx, cy, radius, dot)
    }

    inner class AppAdapter : BaseAdapter() {
        override fun getCount(): Int = shown.size
        override fun getItem(position: Int): Any = shown[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val app = shown[position]
            val cell = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(cellPadDp), dp(8), dp(cellPadDp), dp(8))
            }
            val hasNotif = NotificationService.activePackages.contains(app.packageName)
            val iconDrawable = if (useDesignIcon) designIcon(app, hasNotif)
                else if (hasNotif) withBadge(app.icon) else app.icon
            val icon = ImageView(this@MainActivity).apply {
                setImageDrawable(iconDrawable)
                layoutParams = LinearLayout.LayoutParams(dp(56), dp(56))
            }
            cell.addView(icon)
            val label = TextView(this@MainActivity).apply {
                text = if (showLabels) displayName(app) else ""
                setTextColor(Color.WHITE); textSize = 12f
                gravity = Gravity.CENTER; maxLines = 1
                setPadding(0, dp(4), 0, 0)
            }
            cell.addView(label)
            return cell
        }
    }
}