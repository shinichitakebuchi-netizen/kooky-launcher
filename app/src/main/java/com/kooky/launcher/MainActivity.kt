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
import android.hardware.camera2.CameraManager
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
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread
import kotlin.random.Random

class MainActivity : Activity() {

    data class AppEntry(val label: String, val packageName: String, val icon: Drawable)
    data class LinkApp(val name: String, val pkg: String, val url: String)
    class Category(var name: String, val apps: MutableList<LinkApp>)

    private val apps = mutableListOf<AppEntry>()
    private val shown = mutableListOf<AppEntry>()
    private lateinit var prefs: SharedPreferences
    private val appAdapter = AppAdapter()

    private lateinit var searchBar: EditText
    private lateinit var clockText: TextView
    private lateinit var dateText: TextView
    private lateinit var weatherView: WeatherView
    private lateinit var statusArc: StatusArcView
    private lateinit var dataStat: StatItemView
    private lateinit var ticker: TickerView
    private lateinit var storageStat: StatItemView
    private lateinit var robot: ImageView
    private lateinit var rootFrame: FrameLayout
    private lateinit var contentLayout: LinearLayout
    private lateinit var topArea: LinearLayout
    private lateinit var searchBarRow: LinearLayout
    private lateinit var grid: GridView
    private lateinit var rightCol: LinearLayout
    private lateinit var searchIcon: ImageView

    private val gridColumns = 4
    private val appsTopPadDp = 4
    private val sideMarginDp = 12
    private val cellPadDp = 14
    private val gridRows = 3
    private val cellHeightDp = 108
    private val cornerR = 6
    private val robotSizeDp = 76
    private val idleTimeoutMs = 120_000L
    private val fastChargeMa = 1500

    // テーマ：0=ダーク / 1=ライト / 2=オート(6:00〜21:00ライト)
    private var themeMode = 0
    private var isLight = false

    private val orange = Color.parseColor("#FF8C00")
    private val bgColor get() = if (isLight) Color.parseColor("#F5F5F7") else Color.BLACK
    private val textColor get() = if (isLight) Color.parseColor("#1C1C1E") else Color.WHITE
    private val subText get() = if (isLight) Color.parseColor("#6E6E73") else Color.parseColor("#CCCCCC")
    private val cardBg get() = if (isLight) Color.WHITE else Color.parseColor("#1C1C22")
    private val cardBorder get() = if (isLight) Color.parseColor("#D1D1D6") else Color.parseColor("#333333")
    private val divider get() = if (isLight) Color.parseColor("#E5E5EA") else Color.parseColor("#2A2A30")
    private val grayText get() = if (isLight) Color.parseColor("#8E8E93") else Color.parseColor("#666666")
    private val iconFill get() = if (isLight) Color.WHITE else Color.BLACK

    private val releasesUrl = "https://github.com/shinichitakebuchi-netizen/kooky-launcher/releases/latest"

    private val categories = mutableListOf<Category>()

    private fun defaultCategories(): MutableList<Category> = mutableListOf(
        Category("AI", mutableListOf(
            LinkApp("Claude", "com.anthropic.claude", "https://claude.ai"),
            LinkApp("ChatGPT", "com.openai.chatgpt", "https://chatgpt.com"),
            LinkApp("Gemini", "com.google.android.apps.bard", "https://gemini.google.com")
        )),
        Category("おさいふ", mutableListOf(
            LinkApp("d払い", "com.nttdocomo.keitai.payment", ""),
            LinkApp("楽天ペイ", "jp.co.rakuten.pay", "")
        )),
        Category("仕事", mutableListOf(
            LinkApp("Chrome", "com.android.chrome", "https://www.google.com"),
            LinkApp("スプレッドシート", "com.google.android.apps.docs.editors.sheets", "https://docs.google.com/spreadsheets"),
            LinkApp("Gmail", "com.google.android.gm", "https://mail.google.com")
        ))
    )

    private val robotLines = listOf(
        "どれにする？", "だれを呼ぶ？", "何をひらく？",
        "ぼくにまかせて！", "どこにいく？"
    )

    private val fallbackLat = 36.389
    private val fallbackLon = 139.063
    private val LOC_REQ = 1001

    private var showLabels = true
    private var useDesignIcon = true
    private var showInitial = true
    private var showRobot = true

    private val readingMap = mapOf(
        "あはも" to "ahamo",
        "ぺいぺい" to "paypay",
        "らいん" to "line"
    )

    private var netCallback: ConnectivityManager.NetworkCallback? = null

    private var robotX = 0f
    private var robotY = 0f
    private var robotVX = 2.2f
    private var robotVY = 1.6f
    private var robotTick = 0
    private var robotBusy = false
    private var facingLeft = false
    private var isShowingQuestion = false

    private var flying = false
    private var flyVX = 0f
    private var flyVY = 0f

    private var sleeping = false
    private var awakeUntil = 0L

    private var charging = false
    private var chargingParked = false

    private var standby = false
    private var lastInteractMs = 0L

    private var talking = false
    private var talkAnchorX = 0f
    private var talkAnchorY = 0f
    private var switchingBubble = false

    private var torchOn = false
    private var cameraManager: CameraManager? = null
    private var torchCameraId: String? = null

    private var aiPopup: PopupWindow? = null

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            if (level >= 0 && scale > 0) statusArc.ratio = level.toFloat() / scale.toFloat()
            val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                             status == BatteryManager.BATTERY_STATUS_FULL
            updateChargeMode(isCharging)
            if (isCharging != charging) {
                charging = isCharging
                if (!standby && !talking) {
                    if (charging) startCharging() else stopCharging()
                }
            }
        }
    }

    private fun updateChargeMode(isCharging: Boolean) {
        if (!isCharging) {
            statusArc.chargeMode = 0
            statusArc.stopBlink()
            return
        }
        val ma = readChargeCurrentMa()
        val mode = if (ma >= fastChargeMa) 2 else 1
        statusArc.chargeMode = mode
        statusArc.startBlink()
    }

    private fun readChargeCurrentMa(): Int {
        return try {
            val bm = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            val now = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
            kotlin.math.abs(now) / 1000
        } catch (e: Exception) { 0 }
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
            if (themeMode == 2 && computeLight() != isLight) applyTheme()
            timeHandler.postDelayed(this, 1000)
        }
    }

    private val weatherTick = object : Runnable {
        override fun run() {
            updateWeatherAtCurrentLocation(false)
            timeHandler.postDelayed(this, 60 * 60 * 1000L)
        }
    }

    private val chargeTick = object : Runnable {
        override fun run() {
            if (charging) updateChargeMode(true)
            updateConnectivity()
            timeHandler.postDelayed(this, 15_000L)
        }
    }

    private val statsTick = object : Runnable {
        override fun run() {
            updateStats()
            timeHandler.postDelayed(this, 5 * 60 * 1000L)
        }
    }

    private val robotAnim = object : Runnable {
        override fun run() {
            animateRobot()
            timeHandler.postDelayed(this, 33)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("usage", Context.MODE_PRIVATE)
        showLabels = prefs.getBoolean("show_labels", true)
        useDesignIcon = prefs.getBoolean("use_design_icon", true)
        showInitial = prefs.getBoolean("show_initial", true)
        showRobot = prefs.getBoolean("show_robot", true)
        themeMode = prefs.getInt("theme_mode", 0)
        isLight = computeLight()
        loadCategories()

        loadApps()

        NotificationService.onChange = {
            runOnUiThread {
                appAdapter.notifyDataSetChanged()
                if (::ticker.isInitialized) ticker.setItems(NotificationService.items)
            }
        }

        // 天気：タップで更新 / ダブルタップでYahoo!天気
        val weatherGesture = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                Toast.makeText(this@MainActivity, "天気を更新中…", Toast.LENGTH_SHORT).show()
                updateWeatherAtCurrentLocation(true)
                return true
            }
            override fun onDoubleTap(e: MotionEvent): Boolean {
                openYahooWeather(); return true
            }
        })
        weatherView = WeatherView(this).apply {
            weatherType = prefs.getInt("weather_type", 0)
            setOnTouchListener { _, ev -> weatherGesture.onTouchEvent(ev); true }
        }
        // インジケーター：数字は常に表示。ダブルタップでコントロールセンター（クイック設定）
        val arcGesture = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true
            override fun onDoubleTap(e: MotionEvent): Boolean { openQuickSettings(); return true }
        })
        statusArc = StatusArcView(this).apply {
            showBatteryNumber = true
            setOnTouchListener { _, ev -> arcGesture.onTouchEvent(ev); true }
        }

        clockText = TextView(this).apply {
            textSize = 46f
            setTextColor(textColor)
            gravity = Gravity.CENTER
            setTypeface(typeface, Typeface.BOLD)
            setOnClickListener { openClock() }
            setOnLongClickListener { showClockDiagnostics(); true }
        }
        dateText = TextView(this).apply {
            textSize = 16f
            setTextColor(subText)
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, 0)
            setOnClickListener { openCalendar() }
        }

        val weatherRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(weatherView, LinearLayout.LayoutParams(dp(72), dp(72)))
            addView(statusArc, LinearLayout.LayoutParams(dp(48), dp(48)).apply {
                leftMargin = dp(8)
            })
        }

        dataStat = StatItemView(this).apply {
            iconType = 0
            setOnClickListener { if (!hasUsageAccess()) openUsageAccess() }
        }
        storageStat = StatItemView(this).apply { iconType = 1 }
        val statsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            addView(dataStat, LinearLayout.LayoutParams(dp(84), dp(20)))
            addView(storageStat, LinearLayout.LayoutParams(dp(84), dp(20)).apply { leftMargin = dp(6) })
        }

        topArea = LinearLayout(this).apply {
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
            addView(statsRow, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = dp(6)
            })
        }

        searchBar = EditText(this).apply {
            setSingleLine(true)
            setTextColor(textColor)
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

        searchIcon = ImageView(this).apply {
            setImageDrawable(circleIcon())
            val p = dp(8)
            setPadding(p, p, p, p)
            setOnLongClickListener { showSettingsMenu(); true }
        }

        searchBarRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(cornerR).toFloat()
                setColor(Color.TRANSPARENT)
                setStroke(dp(2), orange)
            }
            setPadding(0, 0, 0, 0)
            addView(searchBar, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(searchIcon, LinearLayout.LayoutParams(dp(40), dp(40)))
        }

        ticker = TickerView(this).apply {
            visibility = View.GONE
            onItemTap = { openNotification(it) }
        }

        grid = GridView(this).apply {
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
                showAppMenu(shown[position]); true
            }
        }

        contentLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bgColor)
            isFocusable = true
            isFocusableInTouchMode = true
        }
        rightCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
        }
        arrangeLayout()

        val robotGesture = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                lastInteractMs = System.currentTimeMillis()
                if (standby) { wakeFromStandby(); return true }
                onRobotTap(); return true
            }
            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (standby) { wakeFromStandby(); return true }
                lastInteractMs = System.currentTimeMillis()
                if (sleeping && isSleepTime()) { wakeUp(); return true }
                toggleTorch(); return true
            }
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                if (standby) { wakeFromStandby(); return true }
                lastInteractMs = System.currentTimeMillis()
                onRobotFling(vx, vy); return true
            }
        })

        robot = ImageView(this).apply {
            setImageResource(R.drawable.robot_normal)
            setOnTouchListener { _, ev -> robotGesture.onTouchEvent(ev); true }
            visibility = if (showRobot) View.VISIBLE else View.GONE
        }

        rootFrame = FrameLayout(this).apply {
            addView(contentLayout, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(robot, FrameLayout.LayoutParams(dp(robotSizeDp), dp(robotSizeDp)))
        }

        setContentView(rootFrame)
        applyTheme()

        cameraManager = getSystemService(Context.CAMERA_SERVICE) as? CameraManager
        try {
            torchCameraId = cameraManager?.cameraIdList?.firstOrNull { id ->
                cameraManager?.getCameraCharacteristics(id)
                    ?.get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            }
        } catch (e: Exception) {}

        applyFilter()
        maybeAskNotificationAccess()
        maybeAskLocation()

        robot.post {
            robotX = (rootFrame.width - dp(robotSizeDp)) / 2f
            robotY = rootFrame.height * 0.62f
            robot.x = robotX
            robot.y = robotY
            lastInteractMs = System.currentTimeMillis()
            enterSleepIfNeeded()
            updateRobotFace()
        }
    }

    private fun loadCategories() {
        categories.clear()
        val json = prefs.getString("categories_json", null)
        if (json == null) {
            categories.addAll(defaultCategories())
            saveCategories()
            return
        }
        try {
            val arr = JSONArray(json)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val appsArr = o.getJSONArray("apps")
                val list = mutableListOf<LinkApp>()
                for (j in 0 until appsArr.length()) {
                    val a = appsArr.getJSONObject(j)
                    list.add(LinkApp(a.getString("name"), a.getString("pkg"), a.optString("url", "")))
                }
                categories.add(Category(o.getString("name"), list))
            }
            if (categories.isEmpty()) categories.addAll(defaultCategories())
        } catch (e: Exception) {
            categories.clear()
            categories.addAll(defaultCategories())
        }
    }

    private fun saveCategories() {
        try {
            val arr = JSONArray()
            for (cat in categories) {
                val o = JSONObject()
                o.put("name", cat.name)
                val appsArr = JSONArray()
                for (a in cat.apps) {
                    val ao = JSONObject()
                    ao.put("name", a.name); ao.put("pkg", a.pkg); ao.put("url", a.url)
                    appsArr.put(ao)
                }
                o.put("apps", appsArr)
                arr.put(o)
            }
            prefs.edit().putString("categories_json", arr.toString()).apply()
        } catch (e: Exception) {}
    }

    // 縦向き：上に時計まわり、下にアプリ（3段）
    // 横向き：左右50%ずつ。左に時計まわり、右に掲示板・検索・アプリ（2段）
    private fun arrangeLayout() {
        val land = resources.configuration.orientation ==
            android.content.res.Configuration.ORIENTATION_LANDSCAPE
        for (v in listOf<View>(topArea, ticker, searchBarRow, grid, rightCol)) {
            (v.parent as? ViewGroup)?.removeView(v)
        }
        rightCol.removeAllViews()
        contentLayout.removeAllViews()
        val barMargin = dp(sideMarginDp + cellPadDp)
        val tickerLp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(20)).apply {
            setMargins(barMargin, 0, barMargin, dp(4))
        }
        val barLp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            setMargins(barMargin, 0, barMargin, dp(8))
        }
        if (land) {
            contentLayout.orientation = LinearLayout.HORIZONTAL
            contentLayout.addView(topArea, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
            rightCol.addView(ticker, tickerLp)
            rightCol.addView(searchBarRow, barLp)
            rightCol.addView(grid, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(2 * cellHeightDp)))
            contentLayout.addView(rightCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        } else {
            contentLayout.orientation = LinearLayout.VERTICAL
            contentLayout.addView(topArea, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            contentLayout.addView(ticker, tickerLp)
            contentLayout.addView(searchBarRow, barLp)
            contentLayout.addView(grid, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(gridRows * cellHeightDp)))
        }
    }

    // 画面の向きが変わったとき（作り直さずに並べ替える）
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        if (talking) { switchingBubble = true; aiPopup?.dismiss(); switchingBubble = false; talking = false }
        arrangeLayout()
        hideSystemBars()
        rootFrame.post {
            if (!showRobot) return@post
            robot.animate().cancel()
            val size = dp(robotSizeDp)
            robotX = robotX.coerceIn(0f, (rootFrame.width - size).toFloat().coerceAtLeast(0f))
            robotY = robotY.coerceIn(rootFrame.height * 0.08f, (rootFrame.height - size).toFloat().coerceAtLeast(0f))
            robot.x = robotX; robot.y = robotY
            when {
                standby -> goStandby()
                charging -> startCharging()
                else -> { robotBusy = false; flying = false; updateRobotFace() }
            }
        }
    }

    private fun computeLight(): Boolean {
        return when (themeMode) {
            1 -> true
            2 -> {
                val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
                hour in 6..20
            }
            else -> false
        }
    }

    private fun themeLabel(): String = when (themeMode) {
        1 -> "ライト"
        2 -> "オート"
        else -> "ダーク"
    }

    // 画面全体に今のテーマ色を反映
    private fun applyTheme() {
        isLight = computeLight()
        window.decorView.setBackgroundColor(bgColor)
        applySystemBarColors()
        contentLayout.setBackgroundColor(bgColor)
        clockText.setTextColor(textColor)
        dateText.setTextColor(subText)
        searchBar.setTextColor(textColor)
        searchIcon.setImageDrawable(circleIcon())
        weatherView.lightMode = isLight
        statusArc.lightMode = isLight
        dataStat.lightMode = isLight
        ticker.lightMode = isLight
        storageStat.lightMode = isLight
        appAdapter.notifyDataSetChanged()
        thread { LockWallpaper.update(this) }
    }

    private fun dialogBuilder(): AlertDialog.Builder {
        val style = if (isLight) android.R.style.Theme_Material_Light_Dialog_Alert
                    else android.R.style.Theme_Material_Dialog_Alert
        return AlertDialog.Builder(this, style)
    }

    // ROBOTの表示/非表示を切り替え
    private fun applyRobotVisibility() {
        if (showRobot) {
            robot.visibility = View.VISIBLE
            standby = false
            flying = false
            robotBusy = false
            lastInteractMs = System.currentTimeMillis()
            robot.animate().cancel()
            robot.rotation = 0f; robot.rotationY = 0f
            robot.scaleX = 1f; robot.scaleY = 1f
            robotX = (rootFrame.width - dp(robotSizeDp)) / 2f
            robotY = rootFrame.height * 0.62f
            robot.x = robotX; robot.y = robotY
            if (charging) startCharging()
            else { enterSleepIfNeeded(); updateRobotFace() }
        } else {
            if (talking) {
                switchingBubble = true; aiPopup?.dismiss(); switchingBubble = false
                talking = false
            }
            robot.animate().cancel()
            robot.visibility = View.GONE
        }
    }

    private fun goStandby() {
        standby = true
        flying = false
        sleeping = false
        robotBusy = false
        chargingParked = false
        isShowingQuestion = false
        aiPopup?.dismiss()
        val w = rootFrame.width
        val size = dp(robotSizeDp)
        robotX = (w - size - dp(8)).toFloat()
        robotY = rootFrame.height * 0.12f
        robot.animate().cancel()
        robot.animate().x(robotX).y(robotY).setDuration(500).withEndAction {
            robot.rotation = 0f; robot.rotationY = 0f
            robot.scaleX = 1f; robot.scaleY = 1f
            robot.setImageResource(R.drawable.robot_standby)
        }.start()
    }

    private fun wakeFromStandby() {
        standby = false
        lastInteractMs = System.currentTimeMillis()
        robotBusy = false
        flying = false
        robot.animate().cancel()
        robot.rotation = 0f; robot.rotationY = 0f
        robot.scaleX = 1f; robot.scaleY = 1f
        if (charging) startCharging()
        else { enterSleepIfNeeded(); updateRobotFace() }
    }

    private fun isSleepTime(): Boolean {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        return hour >= 21 || hour < 6
    }

    private fun enterSleepIfNeeded() {
        if (charging || standby) return
        if (isSleepTime()) {
            sleeping = true
            awakeUntil = 0L
            robot.rotation = 0f
            robot.rotationY = 0f
            robot.scaleX = 1f
            robot.scaleY = 1f
            robot.setImageResource(R.drawable.robot_sleep)
        } else {
            sleeping = false
        }
    }

    private fun wakeUp() {
        sleeping = false
        awakeUntil = System.currentTimeMillis() + 20_000L
        robot.setImageResource(R.drawable.robot_happy)
        applyFacing()
        timeHandler.postDelayed({
            if (isSleepTime() && !charging && !standby) enterSleepIfNeeded()
            updateRobotFace()
        }, 20_000L)
    }

    private fun startCharging() {
        if (!showRobot || standby || talking) return
        sleeping = false
        flying = false
        robotBusy = true
        chargingParked = false
        isShowingQuestion = false
        robot.setImageResource(R.drawable.robot_dash)
        val w = rootFrame.width
        val size = dp(robotSizeDp)
        val targetX = (w - size - dp(8)).toFloat()
        val targetY = rootFrame.height * 0.12f
        facingLeft = false
        applyFacing()
        robot.rotation = 0f; robot.rotationY = 0f
        robot.animate().x(targetX).y(targetY).setDuration(700).withEndAction {
            robotX = targetX
            robotY = targetY
            chargingParked = true
            robotBusy = false
            robot.setImageResource(R.drawable.robot_charging)
            robot.scaleX = 1f; robot.scaleY = 1f; robot.rotation = 0f; robot.rotationY = 0f
        }.start()
    }

    private fun stopCharging() {
        chargingParked = false
        robotBusy = false
        if (standby || talking) return
        if (isSleepTime()) { enterSleepIfNeeded() }
        updateRobotFace()
        robotVX = -1.8f; robotVY = 1.4f
    }

    private fun animateRobot() {
        if (!showRobot) return
        if (talking) return

        if (!standby && !charging && !sleeping &&
            System.currentTimeMillis() - lastInteractMs > idleTimeoutMs) {
            goStandby()
        }

        if (standby) { robotTick++; return }

        val w = rootFrame.width
        val h = rootFrame.height
        if (w == 0 || h == 0) return
        val size = dp(robotSizeDp)

        if (charging) {
            if (chargingParked) {
                val fy = Math.sin(robotTick / 12.0).toFloat() * dp(3)
                robot.y = robotY + fy
            }
            robotTick++
            return
        }

        if (isSleepTime() && !sleeping && awakeUntil != 0L && System.currentTimeMillis() > awakeUntil) {
            enterSleepIfNeeded(); updateRobotFace()
        }
        if (isSleepTime() && !sleeping && awakeUntil == 0L) {
            enterSleepIfNeeded(); updateRobotFace()
        }
        if (!isSleepTime() && sleeping) {
            sleeping = false; updateRobotFace()
        }
        if (sleeping) { robotTick++; return }

        if (flying) {
            robotX += flyVX
            robotY += flyVY
            val minX = 0f
            val maxX = (w - size).toFloat()
            val minY = h * 0.08f
            val maxY = (h - size).toFloat()
            if (robotX < minX) { robotX = minX; flyVX = -flyVX * 0.7f }
            if (robotX > maxX) { robotX = maxX; flyVX = -flyVX * 0.7f }
            if (robotY < minY) { robotY = minY; flyVY = -flyVY * 0.7f }
            if (robotY > maxY) { robotY = maxY; flyVY = -flyVY * 0.7f }
            flyVX *= 0.96f
            flyVY *= 0.96f
            robot.x = robotX
            robot.y = robotY
            robot.rotation = (robotTick * 8f) % 360
            if (kotlin.math.abs(flyVX) < 1.2f && kotlin.math.abs(flyVY) < 1.2f) {
                flying = false
                robot.rotation = 0f
                robotVX = if (flyVX < 0) -1.6f else 1.6f
                robotVY = if (flyVY < 0) -1.2f else 1.2f
                updateRobotFace()
            }
            robotTick++
            return
        }

        if (!robotBusy) {
            if (robotTick % 40 == 0) {
                robotVX += (Random.nextFloat() - 0.5f) * 0.6f
                robotVY += (Random.nextFloat() - 0.5f) * 0.5f
                robotVX = robotVX.coerceIn(-3.2f, 3.2f)
                robotVY = robotVY.coerceIn(-2.6f, 2.6f)
                if (kotlin.math.abs(robotVX) < 0.8f) robotVX = if (robotVX < 0) -1.2f else 1.2f
            }
            robotX += robotVX
            robotY += robotVY
            val minX = 0f
            val maxX = (w - size).toFloat()
            val minY = h * 0.30f
            val maxY = h * 0.72f
            if (robotX < minX) { robotX = minX; robotVX = kotlin.math.abs(robotVX) }
            if (robotX > maxX) { robotX = maxX; robotVX = -kotlin.math.abs(robotVX) }
            if (robotY < minY) { robotY = minY; robotVY = kotlin.math.abs(robotVY) }
            if (robotY > maxY) { robotY = maxY; robotVY = -kotlin.math.abs(robotVY) }

            facingLeft = robotVX < 0

            val fy = Math.sin(robotTick / 12.0).toFloat() * dp(3)
            val depth = Math.sin(robotTick / 55.0).toFloat()
            val scale = 0.9f + (depth + 1f) / 2f * 0.25f
            val tilt = Math.sin(robotTick / 38.0).toFloat() * 6f
            val ry = Math.sin(robotTick / 60.0).toFloat() * 12f

            robot.x = robotX
            robot.y = robotY + fy
            robot.rotation = tilt
            robot.rotationY = ry
            robot.scaleY = scale
            if (isShowingQuestion) robot.scaleX = scale
            else robot.scaleX = if (facingLeft) -scale else scale
        }
        robotTick++

        if (robotTick % 90 == 0 && !robotBusy) {
            val r = Random.nextFloat()
            when {
                r < 0.15f -> startDash()
                r < 0.28f -> restPause()
                else -> randomFace()
            }
        }
    }

    private fun applyFacing() {
        if (isShowingQuestion) {
            robot.scaleX = kotlin.math.abs(robot.scaleX).let { if (it < 0.1f) 1f else it }
            return
        }
        val s = kotlin.math.abs(robot.scaleX).let { if (it < 0.1f) 1f else it }
        robot.scaleX = if (facingLeft) -s else s
    }

    private fun updateRobotFace() {
        if (standby) { robot.setImageResource(R.drawable.robot_standby); return }
        if (talking) return
        if (flying) return
        isShowingQuestion = false
        if (charging && chargingParked) { robot.setImageResource(R.drawable.robot_charging); return }
        if (sleeping) { robot.setImageResource(R.drawable.robot_sleep); return }
        robot.setImageResource(R.drawable.robot_normal)
        applyFacing()
    }

    private fun randomFace() {
        if (sleeping || charging || flying || standby || talking) return
        val faces = intArrayOf(
            R.drawable.robot_normal, R.drawable.robot_wink,
            R.drawable.robot_question, R.drawable.robot_hello,
            R.drawable.robot_transform
        )
        val pick = faces[Random.nextInt(faces.size)]
        isShowingQuestion = (pick == R.drawable.robot_question)
        robot.setImageResource(pick)
        applyFacing()
        timeHandler.postDelayed({ updateRobotFace() }, 1200)
    }

    private fun restPause() {
        if (robotBusy || sleeping || charging || flying || standby || talking) return
        robotBusy = true
        isShowingQuestion = true
        robot.setImageResource(R.drawable.robot_question)
        applyFacing()
        timeHandler.postDelayed({
            updateRobotFace()
            robotBusy = false
        }, 1400)
    }

    private fun startDash() {
        if (robotBusy || sleeping || flying || charging || standby || talking) return
        robotBusy = true
        isShowingQuestion = false
        val w = rootFrame.width
        val size = dp(robotSizeDp)
        robot.setImageResource(R.drawable.robot_dash)
        val fromLeft = robot.x < w / 2f
        facingLeft = !fromLeft
        applyFacing()
        val endX = if (fromLeft) (w - size).toFloat() else 0f
        robot.animate().x(endX).setDuration(850).withEndAction {
            robotX = robot.x
            robotVX = if (fromLeft) -kotlin.math.abs(robotVX) else kotlin.math.abs(robotVX)
            updateRobotFace()
            robotBusy = false
        }.start()
    }

    private fun onRobotFling(vx: Float, vy: Float) {
        if (sleeping && isSleepTime()) { wakeUp(); return }
        if (charging) return
        robotBusy = false
        isShowingQuestion = false
        flying = true
        robotX = robot.x
        robotY = robot.y
        flyVX = (vx / 55f).coerceIn(-40f, 40f)
        flyVY = (vy / 55f).coerceIn(-40f, 40f)
        if (kotlin.math.abs(flyVX) < 6f && kotlin.math.abs(flyVY) < 6f) {
            flyVX = if (flyVX < 0) -12f else 12f
        }
        robot.rotation = 0f
        robot.rotationY = 0f
        robot.scaleX = 1f
        robot.scaleY = 1f
        robot.setImageResource(R.drawable.robot_angry)
    }

    private fun onRobotTap() {
        if (sleeping && isSleepTime()) { wakeUp(); return }
        if (flying) return
        talking = true
        robotBusy = false
        flying = false
        robot.animate().cancel()
        robot.rotation = 0f; robot.rotationY = 0f
        robot.scaleX = 1f; robot.scaleY = 1f
        robot.setImageResource(R.drawable.robot_happy)
        val size = dp(robotSizeDp)
        talkAnchorX = dp(16).toFloat()
        talkAnchorY = (rootFrame.height - size - dp(120)).toFloat()
        robotX = talkAnchorX
        robotY = talkAnchorY
        robot.animate().x(talkAnchorX).y(talkAnchorY).setDuration(350).withEndAction {
            robot.x = talkAnchorX
            robot.y = talkAnchorY
            showCategoryMenu()
        }.start()
    }

    private fun stopTalking() {
        if (!talking) return
        talking = false
        lastInteractMs = System.currentTimeMillis()
        robotVX = if (Random.nextBoolean()) 1.8f else -1.8f
        robotVY = if (Random.nextBoolean()) 1.4f else -1.4f
        if (charging) startCharging()
        else { enterSleepIfNeeded(); updateRobotFace() }
    }

    private fun showCategoryMenu() {
        val card = bubbleCard()
        card.addView(bubbleTitle(robotLines[Random.nextInt(robotLines.size)]))
        for (cat in categories) {
            card.addView(bubbleDivider())
            val row = bubbleRow(cat.name, "›", orange, false) {
                showAppMenuInCategory(cat)
            }
            row.setOnLongClickListener { editCategory(cat); true }
            card.addView(row)
        }
        card.addView(bubbleDivider())
        card.addView(bubblePlusRight { addCategory() })
        showBubble(card)
    }

    private fun addCategory() {
        val input = EditText(this).apply {
            hint = "カテゴリ名"; setHintTextColor(grayText); setTextColor(textColor); setSingleLine(true)
        }
        dialogBuilder()
            .setTitle("カテゴリを追加")
            .setView(input)
            .setPositiveButton("追加") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) {
                    categories.add(Category(name, mutableListOf()))
                    saveCategories()
                    showCategoryMenu()
                }
            }
            .setNegativeButton("キャンセル", null)
            .show()
    }

    private fun editCategory(cat: Category) {
        val input = EditText(this).apply {
            setText(cat.name); setTextColor(textColor); setSingleLine(true)
        }
        dialogBuilder()
            .setTitle("カテゴリを編集")
            .setView(input)
            .setPositiveButton("保存") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) { cat.name = name; saveCategories() }
                showCategoryMenu()
            }
            .setNeutralButton("削除") { _, _ ->
                dialogBuilder()
                    .setTitle("削除")
                    .setMessage("「${cat.name}」を削除しますか？")
                    .setPositiveButton("削除") { _, _ ->
                        categories.remove(cat); saveCategories(); showCategoryMenu()
                    }
                    .setNegativeButton("キャンセル") { _, _ -> showCategoryMenu() }
                    .show()
            }
            .setNegativeButton("キャンセル", null)
            .show()
    }

    private fun showAppMenuInCategory(cat: Category) {
        val card = bubbleCard()
        card.addView(bubbleTitle(cat.name))
        card.addView(bubbleDivider())
        card.addView(bubbleBackAddBar({ showCategoryMenu() }, { showAddAppPicker(cat) }))
        for (app in cat.apps) {
            card.addView(bubbleDivider())
            val row = bubbleRow(app.name, "▶", orange, false) {
                openLinkApp(app)
            }
            row.setOnLongClickListener { confirmRemoveApp(cat, app); true }
            card.addView(row)
        }
        showBubble(card)
    }

    private fun confirmRemoveApp(cat: Category, app: LinkApp) {
        dialogBuilder()
            .setTitle("削除")
            .setMessage("「${app.name}」を${cat.name}から削除しますか？")
            .setPositiveButton("削除") { _, _ ->
                cat.apps.remove(app)
                saveCategories()
                showAppMenuInCategory(cat)
            }
            .setNegativeButton("キャンセル", null)
            .show()
    }

    private fun showAddAppPicker(cat: Category) {
        switchingBubble = true
        aiPopup?.dismiss()
        switchingBubble = false
        val card = menuCard()
        card.addView(menuTitle("${cat.name} に追加"))
        card.addView(menuDivider())

        val search = EditText(this).apply {
            hint = "アプリを検索"
            setHintTextColor(grayText)
            setTextColor(textColor)
            setSingleLine(true)
            background = null
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.setMargins(dp(14), dp(4), dp(14), dp(4))
            layoutParams = lp
            setPadding(dp(4), dp(6), dp(4), dp(6))
        }
        card.addView(search)
        card.addView(menuDivider())

        val listContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(360))
            addView(listContainer)
        }
        card.addView(scroll)

        lateinit var dialog: AlertDialog

        fun renderList(query: String) {
            listContainer.removeAllViews()
            val q = query.trim().lowercase()
            val list = apps.filter { q.isEmpty() || it.label.lowercase().contains(q) }
            for (a in list) {
                val row = TextView(this).apply {
                    text = a.label
                    setTextColor(textColor)
                    textSize = 14f
                    setPadding(dp(16), dp(12), dp(16), dp(12))
                    isClickable = true
                    setOnClickListener {
                        if (cat.apps.none { it.pkg == a.packageName }) {
                            cat.apps.add(LinkApp(a.label, a.packageName, ""))
                            saveCategories()
                            Toast.makeText(this@MainActivity, "${a.label}を追加しました", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(this@MainActivity, "すでに追加済みです", Toast.LENGTH_SHORT).show()
                        }
                        dialog.dismiss()
                        showAppMenuInCategory(cat)
                    }
                }
                listContainer.addView(row)
                listContainer.addView(View(this).apply {
                    setBackgroundColor(divider)
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1))
                })
            }
        }
        renderList("")
        search.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) { renderList(s?.toString() ?: "") }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })

        dialog = dialogBuilder().setView(card).create()
        dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
        dialog.show()
    }

    private fun bubbleCard(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(14).toFloat()
                setColor(cardBg)
                setStroke(dp(1), orange)
            }
        }
    }

    private fun bubbleTitle(text: String): TextView {
        return TextView(this).apply {
            this.text = text
            setTextColor(textColor)
            textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(12), dp(16), dp(8))
        }
    }

    private fun bubbleDivider(): View {
        return View(this).apply {
            setBackgroundColor(divider)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1))
        }
    }

    private fun bubbleRow(label: String, arrow: String, arrowColor: Int, leftAccent: Boolean, onClick: () -> Unit): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(11), dp(16), dp(11))
            isClickable = true
            setOnClickListener { onClick() }
        }
        val left = TextView(this).apply {
            text = label
            setTextColor(if (leftAccent) orange else textColor)
            textSize = if (leftAccent) 16f else 14f
            setTypeface(typeface, if (leftAccent) Typeface.BOLD else Typeface.NORMAL)
        }
        row.addView(left, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (arrow.isNotEmpty()) {
            val right = TextView(this).apply {
                text = arrow; setTextColor(arrowColor); textSize = 14f
            }
            row.addView(right, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        return row
    }

    private fun bubbleBackAddBar(onBack: () -> Unit, onAdd: () -> Unit): LinearLayout {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(10))
        }
        val back = TextView(this).apply {
            text = "◁"; setTextColor(orange); textSize = 17f
            setTypeface(typeface, Typeface.BOLD)
            isClickable = true; setOnClickListener { onBack() }
        }
        val add = TextView(this).apply {
            text = "＋"; setTextColor(orange); textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.END
            isClickable = true; setOnClickListener { onAdd() }
        }
        bar.addView(back, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        bar.addView(add, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        return bar
    }

    private fun bubblePlusRight(onAdd: () -> Unit): LinearLayout {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(10))
        }
        val spacer = View(this)
        val add = TextView(this).apply {
            text = "＋"; setTextColor(orange); textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.END
            isClickable = true; setOnClickListener { onAdd() }
        }
        bar.addView(spacer, LinearLayout.LayoutParams(0, dp(1), 1f))
        bar.addView(add, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return bar
    }

    // 窓をロボの右上に固定（ロボは触らない・切り替えdismissではstopTalkingしない）
    private fun showBubble(card: LinearLayout) {
        switchingBubble = true
        aiPopup?.dismiss()
        switchingBubble = false

        val popupW = dp(210)
        val popup = PopupWindow(card, popupW, ViewGroup.LayoutParams.WRAP_CONTENT, true).apply {
            isOutsideTouchable = true
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
            setOnDismissListener { if (!switchingBubble) stopTalking() }
        }
        aiPopup = popup

        val loc = IntArray(2)
        rootFrame.getLocationInWindow(loc)
        val rootTop = loc[1]
        val size = dp(robotSizeDp)

        var winX = (talkAnchorX + size - dp(8)).toInt()
        winX = winX.coerceIn(dp(8), rootFrame.width - popupW - dp(8))
        var winY = (talkAnchorY - dp(210)).toInt()
        winY = winY.coerceAtLeast(dp(8))

        popup.showAtLocation(rootFrame, Gravity.NO_GRAVITY, winX, rootTop + winY)
    }

    // 掲示板の通知をタップ → その通知を開く（だめならアプリを開く）
    private fun openNotification(item: NotificationService.Item) {
        try {
            if (item.intent != null) {
                if (android.os.Build.VERSION.SDK_INT >= 34) {
                    val opts = android.app.ActivityOptions.makeBasic()
                    opts.setPendingIntentBackgroundActivityStartMode(
                        android.app.ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
                    item.intent.send(this, 0, null, null, null, null, opts.toBundle())
                } else {
                    item.intent.send()
                }
                return
            }
        } catch (e: Exception) {}
        packageManager.getLaunchIntentForPackage(item.pkg)?.let {
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); startActivity(it)
        }
    }

    // 上から引き下ろすクイック設定パネルを開く
    @android.annotation.SuppressLint("WrongConstant")
    private fun openQuickSettings() {
        try {
            val sbm = getSystemService("statusbar")
            val cls = Class.forName("android.app.StatusBarManager")
            cls.getMethod("expandSettingsPanel").invoke(sbm)
            return
        } catch (e: Exception) {}
        try {
            val sbm = getSystemService("statusbar")
            Class.forName("android.app.StatusBarManager").getMethod("expandNotificationsPanel").invoke(sbm)
            return
        } catch (e: Exception) {}
        Toast.makeText(this, "コントロールセンターを開けませんでした", Toast.LENGTH_SHORT).show()
    }

    private fun openYahooWeather() {
        val pkg = "jp.co.yahoo.android.weather.type1"
        packageManager.getLaunchIntentForPackage(pkg)?.let {
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); startActivity(it); return
        }
        // 入っていなければWeb版
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://weather.yahoo.co.jp/"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            Toast.makeText(this, "Yahoo!天気を開けませんでした", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openLinkApp(app: LinkApp) {
        aiPopup?.dismiss()
        try {
            val intent = packageManager.getLaunchIntentForPackage(app.pkg)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent); return
            }
        } catch (e: Exception) {}
        if (app.url.isNotEmpty()) {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(app.url))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            } catch (e: Exception) {}
        }
        Toast.makeText(this, "${app.name}を開けませんでした", Toast.LENGTH_SHORT).show()
    }

    private fun toggleTorch() {
        val id = torchCameraId
        if (id == null) {
            Toast.makeText(this, "ライトが使えません", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            torchOn = !torchOn
            cameraManager?.setTorchMode(id, torchOn)
            if (!charging && !flying && !standby && !talking) {
                robot.setImageResource(if (torchOn) R.drawable.robot_happy else R.drawable.robot_normal)
                applyFacing()
                if (!torchOn) timeHandler.postDelayed({ updateRobotFace() }, 600)
            }
        } catch (e: Exception) {
            Toast.makeText(this, "ライトの切替に失敗しました", Toast.LENGTH_SHORT).show()
        }
    }

    private fun hideSystemBars() {
        var flags = (View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            or View.SYSTEM_UI_FLAG_FULLSCREEN
            or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
        // ライトモード時は下のナビボタンを黒に
        if (isLight) flags = flags or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        window.decorView.systemUiVisibility = flags
    }

    // 上（カメラ穴まわり）と下（ナビバー）をテーマ色に塗る
    private fun applySystemBarColors() {
        try {
            if (android.os.Build.VERSION.SDK_INT >= 28) {
                window.attributes = window.attributes.apply {
                    layoutInDisplayCutoutMode =
                        android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
            }
            window.statusBarColor = bgColor
            window.navigationBarColor = bgColor
        } catch (e: Exception) {}
        hideSystemBars()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
        if (computeLight() != isLight) applyTheme()
        thread { LockWallpaper.update(this) }
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
        timeHandler.removeCallbacks(chargeTick)
        timeHandler.post(chargeTick)
        timeHandler.removeCallbacks(robotAnim)
        timeHandler.post(robotAnim)
        timeHandler.removeCallbacks(statsTick)
        timeHandler.post(statsTick)
        ticker.setItems(NotificationService.items)
        ticker.start()
        lastInteractMs = System.currentTimeMillis()
        if (talking) { switchingBubble = true; aiPopup?.dismiss(); switchingBubble = false; talking = false }
        if (!showRobot) {
            robot.visibility = View.GONE
        } else if (standby) {
            robot.post { goStandby() }
        } else {
            if (!charging) { enterSleepIfNeeded(); updateRobotFace() }
        }
    }

    override fun onPause() {
        super.onPause()
        try { unregisterReceiver(batteryReceiver) } catch (e: Exception) {}
        unregisterNetworkCallback()
        timeHandler.removeCallbacks(tick)
        timeHandler.removeCallbacks(weatherTick)
        timeHandler.removeCallbacks(chargeTick)
        timeHandler.removeCallbacks(robotAnim)
        timeHandler.removeCallbacks(statsTick)
        ticker.stop()
        statusArc.stopBlink()
        switchingBubble = true
        aiPopup?.dismiss()
        switchingBubble = false
        if (torchOn) {
            try { torchCameraId?.let { cameraManager?.setTorchMode(it, false) } } catch (e: Exception) {}
            torchOn = false
        }
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
        // WiFi中はモバイル回線が休んでいるので、「モバイルデータ設定がON＋SIMが使える」でもONにする
        if (!mobile) {
            try {
                val tm = getSystemService(Context.TELEPHONY_SERVICE) as android.telephony.TelephonyManager
                val simOk = tm.simState == android.telephony.TelephonyManager.SIM_STATE_READY
                @Suppress("DEPRECATION")
                val dataOn = tm.isDataEnabled
                if (simOk && dataOn) mobile = true
            } catch (e: Exception) {}
        }
        return Pair(wifi, mobile)
    }

    private fun updateConnectivity() {
        val (wifi, mobile) = readNet()
        statusArc.wifiOn = wifi
        statusArc.mobileOn = mobile
        statusArc.signalLevel = if (mobile) readSignalLevel() else 0
    }

    // モバイル電波の強さ（0〜4）。取れない端末ではつながっていれば4扱い
    private fun readSignalLevel(): Int {
        return try {
            if (android.os.Build.VERSION.SDK_INT >= 28) {
                val tm = getSystemService(Context.TELEPHONY_SERVICE) as android.telephony.TelephonyManager
                tm.signalStrength?.level ?: 4
            } else 4
        } catch (e: Exception) { 4 }
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

    // ===== 通信量・内部ストレージ =====
    private fun hasUsageAccess(): Boolean {
        return try {
            val ops = getSystemService(Context.APP_OPS_SERVICE) as android.app.AppOpsManager
            @Suppress("DEPRECATION")
            val mode = ops.checkOpNoThrow(android.app.AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(), packageName)
            mode == android.app.AppOpsManager.MODE_ALLOWED
        } catch (e: Exception) { false }
    }

    private fun openUsageAccess() {
        Toast.makeText(this, "「使用状況へのアクセス」でKooky Launcherを許可してください", Toast.LENGTH_LONG).show()
        try {
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {}
    }

    private fun closeDay(): Int = prefs.getInt("data_close_day", 31)       // 31=末日
    private fun dataLimitGb(): Int = prefs.getInt("data_limit_gb", 20)

    // 締め日の翌日0時（＝今回の集計開始）
    private fun cycleStartMs(): Long {
        val now = Calendar.getInstance()
        val start = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        fun startIn(cal: Calendar) {
            val last = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
            val close = closeDay()
            if (close >= last) {
                cal.set(Calendar.DAY_OF_MONTH, 1)                   // 末日締め→1日開始
            } else {
                cal.set(Calendar.DAY_OF_MONTH, close + 1)
            }
        }
        // 前月の締め日翌日から始まる場合もあるので、今月→前月の順に試す
        startIn(start)
        if (start.after(now)) {
            start.set(Calendar.DAY_OF_MONTH, 1)
            start.add(Calendar.MONTH, -1)
            val close = closeDay()
            val last = start.getActualMaximum(Calendar.DAY_OF_MONTH)
            if (close >= last) start.add(Calendar.MONTH, 1)         // 前月末締め→今月1日
            else start.set(Calendar.DAY_OF_MONTH, close + 1)
        }
        return start.timeInMillis
    }

    private fun readMobileBytes(): Long {
        return try {
            val nsm = getSystemService(Context.NETWORK_STATS_SERVICE) as android.app.usage.NetworkStatsManager
            val b = nsm.querySummaryForDevice(ConnectivityManager.TYPE_MOBILE, null,
                cycleStartMs(), System.currentTimeMillis())
            b.rxBytes + b.txBytes
        } catch (e: Exception) { -1L }
    }

    private fun fmtGb(bytes: Long): Pair<String, String> {
        val gb = bytes / 1_000_000_000.0
        return when {
            gb < 1.0 -> Pair(String.format(Locale.US, "%d", (bytes / 1_000_000L)), "MB")
            gb < 10.0 -> Pair(String.format(Locale.US, "%.1f", gb), "GB")
            else -> Pair(String.format(Locale.US, "%d", gb.toLong()), "GB")
        }
    }

    private fun updateStats() {
        thread {
            // 内部ストレージ
            var usedGb = 0L; var totalGb = 0L; var sRatio = 0f
            try {
                // 設定アプリの「ストレージ」と同じ数え方（システム領域も含めた本体容量）
                var total: Long
                var free: Long
                try {
                    val ssm = getSystemService(Context.STORAGE_STATS_SERVICE) as android.app.usage.StorageStatsManager
                    total = ssm.getTotalBytes(android.os.storage.StorageManager.UUID_DEFAULT)
                    free = ssm.getFreeBytes(android.os.storage.StorageManager.UUID_DEFAULT)
                } catch (e: Exception) {
                    val st = android.os.StatFs(android.os.Environment.getDataDirectory().path)
                    total = st.totalBytes
                    free = st.availableBytes
                }
                val used = total - free
                // Galaxyの設定画面と同じ1GB=1024MBで計算（256GB本体が256と出る）
                usedGb = used / (1024L * 1024L * 1024L)
                totalGb = total / (1024L * 1024L * 1024L)
                sRatio = if (total > 0) used.toFloat() / total else 0f
            } catch (e: Exception) {}
            // 通信量
            val granted = hasUsageAccess()
            val bytes = if (granted) readMobileBytes() else -1L
            runOnUiThread {
                storageStat.value = usedGb.toString()
                storageStat.unit = "/${totalGb}GB"
                storageStat.ratio = sRatio
                storageStat.alert = sRatio >= 0.9f
                if (!granted) {
                    dataStat.value = "許可"; dataStat.unit = "タップ"; dataStat.ratio = 0f; dataStat.alert = false
                } else if (bytes < 0) {
                    dataStat.value = "--"; dataStat.unit = "GB"; dataStat.ratio = 0f; dataStat.alert = false
                } else {
                    val (v, u) = fmtGb(bytes)
                    dataStat.value = v; dataStat.unit = u
                    val limit = dataLimitGb() * 1_000_000_000.0
                    val r = if (limit > 0) (bytes / limit).toFloat() else 0f
                    dataStat.ratio = r
                    dataStat.alert = r >= 1f
                }
            }
        }
    }

    private fun closeDayLabel(): String = if (closeDay() >= 31) "末日" else "${closeDay()}日"

    private fun showDataSettings() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }
        fun field(label: String, value: String): EditText {
            box.addView(TextView(this).apply { text = label; setTextColor(grayText); textSize = 12f })
            val e = EditText(this).apply {
                setText(value); setTextColor(textColor); setSingleLine(true)
                inputType = android.text.InputType.TYPE_CLASS_NUMBER
            }
            box.addView(e)
            return e
        }
        val closeIn = field("締め日（1〜31、31＝末日）", closeDay().toString())
        val limitIn = field("月の上限（GB）", dataLimitGb().toString())
        dialogBuilder()
            .setTitle("通信量の設定")
            .setView(box)
            .setPositiveButton("保存") { _, _ ->
                val c = closeIn.text.toString().toIntOrNull()?.coerceIn(1, 31) ?: closeDay()
                val l = limitIn.text.toString().toIntOrNull()?.coerceIn(1, 999) ?: dataLimitGb()
                prefs.edit().putInt("data_close_day", c).putInt("data_limit_gb", l).apply()
                updateStats(); showSettingsMenu()
            }
            .setNegativeButton("キャンセル") { _, _ -> showSettingsMenu() }
            .show()
    }

    private fun circleIcon(): Drawable {
        val size = dp(24)
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; color = textColor; strokeWidth = dp(2).toFloat()
        }
        canvas.drawCircle(size / 2f, size / 2f, size / 2f - dp(2), p)
        return BitmapDrawable(resources, bmp)
    }

    private fun menuCard(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(cornerR).toFloat()
                setColor(cardBg)
                setStroke(dp(1), cardBorder)
            }
        }
    }

    private fun menuTitle(text: String): TextView {
        return TextView(this).apply {
            this.text = text
            setTextColor(textColor)
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(18), dp(16), dp(18), dp(10))
        }
    }

    private fun menuDivider(): View {
        return View(this).apply {
            setBackgroundColor(divider)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1))
        }
    }

    private fun menuRow(label: String, state: String, stateColor: Int, onClick: () -> Unit): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(14), dp(18), dp(14))
            isClickable = true
            setOnClickListener { onClick() }
        }
        val left = TextView(this).apply {
            text = label; setTextColor(textColor); textSize = 14f
        }
        val right = TextView(this).apply {
            text = state; setTextColor(stateColor); textSize = 13f
        }
        row.addView(left, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(right, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return row
    }

    private fun menuItem(label: String, onClick: () -> Unit): View {
        return TextView(this).apply {
            text = label
            setTextColor(textColor)
            textSize = 14f
            setPadding(dp(18), dp(14), dp(18), dp(14))
            isClickable = true
            setOnClickListener { onClick() }
        }
    }

    private fun showSettingsMenu() {
        lateinit var dialog: AlertDialog
        val card = menuCard()
        card.addView(menuTitle("設定"))
        card.addView(menuDivider())
        card.addView(menuRow("NAME", if (showLabels) "表示" else "非表示",
            if (showLabels) orange else grayText) {
            showLabels = !showLabels; prefs.edit().putBoolean("show_labels", showLabels).apply()
            appAdapter.notifyDataSetChanged(); dialog.dismiss(); showSettingsMenu()
        })
        card.addView(menuDivider())
        card.addView(menuRow("ICON", if (useDesignIcon) "オリジナル" else "シンプル", orange) {
            useDesignIcon = !useDesignIcon; prefs.edit().putBoolean("use_design_icon", useDesignIcon).apply()
            appAdapter.notifyDataSetChanged(); dialog.dismiss(); showSettingsMenu()
        })
        card.addView(menuDivider())
        card.addView(menuRow("INITIAL", if (showInitial) "表示" else "非表示",
            if (showInitial) orange else grayText) {
            showInitial = !showInitial; prefs.edit().putBoolean("show_initial", showInitial).apply()
            appAdapter.notifyDataSetChanged(); dialog.dismiss(); showSettingsMenu()
        })
        card.addView(menuDivider())
        card.addView(menuRow("ROBOT", if (showRobot) "表示" else "非表示",
            if (showRobot) orange else grayText) {
            showRobot = !showRobot; prefs.edit().putBoolean("show_robot", showRobot).apply()
            applyRobotVisibility(); dialog.dismiss(); showSettingsMenu()
        })
        card.addView(menuDivider())
        card.addView(menuRow("THEME", themeLabel(), orange) {
            themeMode = (themeMode + 1) % 3
            prefs.edit().putInt("theme_mode", themeMode).apply()
            applyTheme(); dialog.dismiss(); showSettingsMenu()
        })
        card.addView(menuDivider())
        card.addView(menuRow("DATA", "締め${closeDayLabel()}・${dataLimitGb()}GB", orange) {
            dialog.dismiss(); showDataSettings()
        })
        card.addView(menuDivider())
        val lockOn = LockWallpaper.isOn(this)
        card.addView(menuRow("LOCK", if (lockOn) "オン" else "オフ",
            if (lockOn) orange else grayText) {
            val on = !LockWallpaper.isOn(this)
            prefs.edit().putBoolean(LockWallpaper.KEY_ON, on).apply()
            dialog.dismiss()
            thread {
                if (on) LockWallpaper.update(this, true) else LockWallpaper.turnOff(this)
                runOnUiThread {
                    Toast.makeText(this,
                        if (on) "ロック画面の壁紙を設定しました" else "ロック画面の壁紙を元に戻しました",
                        Toast.LENGTH_SHORT).show()
                    showSettingsMenu()
                }
            }
        })
        card.addView(menuDivider())
        card.addView(menuRow("UPDATE", "›", grayText) {
            dialog.dismiss(); openUpdatePage()
        })
        dialog = dialogBuilder().setView(card).create()
        dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
        dialog.show()
    }

    private fun openUpdatePage() {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(releasesUrl))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            Toast.makeText(this, "更新ページを開けませんでした", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showAppMenu(app: AppEntry) {
        lateinit var dialog: AlertDialog
        val card = menuCard()
        card.addView(menuTitle("名前を変更"))

        val input = EditText(this).apply {
            setText(displayName(app))
            setSingleLine(true)
            setTextColor(textColor)
            background = null
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.setMargins(dp(18), 0, dp(18), 0)
            layoutParams = lp
            setPadding(dp(2), dp(4), dp(2), dp(6))
        }
        val underline = View(this).apply {
            setBackgroundColor(orange)
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(2))
            lp.setMargins(dp(18), 0, dp(18), 0)
            layoutParams = lp
        }
        card.addView(input)
        card.addView(underline)

        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(dp(18), dp(10), dp(18), dp(12))
        }
        val cancel = TextView(this).apply {
            text = "キャンセル"; setTextColor(grayText); textSize = 13f
            setPadding(dp(10), dp(4), dp(10), dp(4)); isClickable = true
            setOnClickListener { dialog.dismiss() }
        }
        val restore = TextView(this).apply {
            text = "元に戻す"; setTextColor(grayText); textSize = 13f
            setPadding(dp(10), dp(4), dp(10), dp(4)); isClickable = true
            setOnClickListener {
                prefs.edit().remove("name_" + app.packageName).apply()
                sortApps(); applyFilter(); dialog.dismiss()
            }
        }
        val save = TextView(this).apply {
            text = "保存"; setTextColor(orange); textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(10), dp(4), dp(10), dp(4)); isClickable = true
            setOnClickListener {
                val newName = input.text.toString().trim()
                if (newName.isNotEmpty()) prefs.edit().putString("name_" + app.packageName, newName).apply()
                else prefs.edit().remove("name_" + app.packageName).apply()
                sortApps(); applyFilter(); dialog.dismiss()
            }
        }
        btnRow.addView(restore); btnRow.addView(cancel); btnRow.addView(save)
        card.addView(btnRow)

        card.addView(menuDivider())
        card.addView(menuItem("利用回数をリセット") {
            prefs.edit().remove(app.packageName).apply()
            sortApps(); applyFilter()
            Toast.makeText(this, "利用回数をリセットしました", Toast.LENGTH_SHORT).show()
            dialog.dismiss()
        })
        card.addView(menuDivider())
        card.addView(menuItem("アプリ詳細") {
            dialog.dismiss(); openAppDetail(app.packageName)
        })

        dialog = dialogBuilder().setView(card).create()
        dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
        dialog.show()
    }

    private fun openAppDetail(pkg: String) {
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            intent.data = Uri.parse("package:$pkg")
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "アプリ詳細を開けませんでした", Toast.LENGTH_SHORT).show()
        }
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
        val ma = readChargeCurrentMa()
        Toast.makeText(this, "充電電流: ${ma}mA（1500以上=急速）", Toast.LENGTH_LONG).show()
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
        if (aiPopup?.isShowing == true) { aiPopup?.dismiss(); return }
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
        val radius = dp(cornerR).toFloat()
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = iconFill }
        canvas.drawRoundRect(inset, inset, size - inset, size - inset, radius, radius, fill)
        val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; color = textColor; strokeWidth = dp(2).toFloat()
        }
        canvas.drawRoundRect(inset, inset, size - inset, size - inset, radius, radius, border)
        if (showInitial) {
            val ch = firstChar(displayName(app))
            if (ch.isNotEmpty()) {
                val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = textColor; textSize = size * 0.42f
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
                setTextColor(textColor); textSize = 12f
                gravity = Gravity.CENTER; maxLines = 1
                setPadding(0, dp(4), 0, 0)
            }
            cell.addView(label)
            return cell
        }
    }
}
