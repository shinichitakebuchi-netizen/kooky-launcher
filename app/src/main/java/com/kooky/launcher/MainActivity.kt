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
    class Category(val name: String, val apps: MutableList<LinkApp>)

    private val apps = mutableListOf<AppEntry>()
    private val shown = mutableListOf<AppEntry>()
    private lateinit var prefs: SharedPreferences
    private val appAdapter = AppAdapter()

    private lateinit var searchBar: EditText
    private lateinit var clockText: TextView
    private lateinit var dateText: TextView
    private lateinit var weatherView: WeatherView
    private lateinit var statusArc: StatusArcView
    private lateinit var robot: ImageView
    private lateinit var rootFrame: FrameLayout

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

    private val cardBg = Color.parseColor("#1C1C22")
    private val cardBorder = Color.parseColor("#333333")
    private val divider = Color.parseColor("#2A2A30")
    private val orange = Color.parseColor("#FF8C00")
    private val grayText = Color.parseColor("#666666")

    private val releasesUrl = "https://github.com/shinichitakebuchi-netizen/kooky-launcher/releases/latest"

    // カテゴリ（可変・保存対象）
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
                if (!standby) {
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
            timeHandler.postDelayed(this, 15_000L)
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
        loadCategories()

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
                cornerRadius = dp(cornerR).toFloat()
                setColor(Color.TRANSPARENT)
                setStroke(dp(2), orange)
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
                showAppMenu(shown[position]); true
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
        }

        rootFrame = FrameLayout(this).apply {
            addView(content, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(robot, FrameLayout.LayoutParams(dp(robotSizeDp), dp(robotSizeDp)))
        }

        setContentView(rootFrame)

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

    // ===== カテゴリの保存/読み込み =====
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
        if (standby) return
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
        if (standby) return
        if (isSleepTime()) { enterSleepIfNeeded() }
        updateRobotFace()
        robotVX = -1.8f; robotVY = 1.4f
    }

    private fun animateRobot() {
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
        if (flying) return
        isShowingQuestion = false
        if (charging && chargingParked) { robot.setImageResource(R.drawable.robot_charging); return }
        if (sleeping) { robot.setImageResource(R.drawable.robot_sleep); return }
        robot.setImageResource(R.drawable.robot_normal)
        applyFacing()
    }

    private fun randomFace() {
        if (sleeping || charging || flying || standby) return
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
        if (robotBusy || sleeping || charging || flying || standby) return
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
        if (robotBusy || sleeping || flying || charging || standby) return
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
        robotBusy = true
        isShowingQuestion = false
        robot.setImageResource(R.drawable.robot_happy)
        applyFacing()
        val baseScale = kotlin.math.abs(robot.scaleX)
        robot.animate().scaleX(if (facingLeft) -(baseScale + 0.2f) else baseScale + 0.2f)
            .scaleY(baseScale + 0.2f).setDuration(120).withEndAction {
            robot.animate().scaleX(if (facingLeft) -baseScale else baseScale).scaleY(baseScale)
                .setDuration(120).withEndAction {
                robotBusy = false
                updateRobotFace()
                showCategoryMenu()
            }.start()
        }.start()
    }

    private fun showCategoryMenu() {
        val card = bubbleCard()
        card.addView(bubbleTitle(robotLines[Random.nextInt(robotLines.size)]))
        for (cat in categories) {
            card.addView(bubbleDivider())
            card.addView(bubbleRow(cat.name, "›", orange, false) {
                showAppMenuInCategory(cat)
            })
        }
        showBubble(card)
    }

    private fun showAppMenuInCategory(cat: Category) {
        val card = bubbleCard()
        card.addView(bubbleTitle(cat.name))
        card.addView(bubbleDivider())
        card.addView(bubbleRow("◁", "", orange, true) {
            showCategoryMenu()
        })
        for (app in cat.apps) {
            card.addView(bubbleDivider())
            val row = bubbleRow(app.name, "▶", orange, false) {
                aiPopup?.dismiss(); openLinkApp(app)
            }
            // 長押しで削除
            row.setOnLongClickListener {
                confirmРemoveApp(cat, app)
                true
            }
            card.addView(row)
        }
        // ＋ アプリを追加
        card.addView(bubbleDivider())
        card.addView(bubbleRow("＋ アプリを追加", "", orange, true) {
            showAddAppPicker(cat)
        })
        showBubble(card)
    }

    private fun confirmРemoveApp(cat: Category, app: LinkApp) {
        AlertDialog.Builder(this)
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

    // アプリ追加：全アプリ一覧（検索付き）
    private fun showAddAppPicker(cat: Category) {
        aiPopup?.dismiss()
        val card = menuCard()
        card.addView(menuTitle("${cat.name} に追加"))
        card.addView(menuDivider())

        val search = EditText(this).apply {
            hint = "アプリを検索"
            setHintTextColor(grayText)
            setTextColor(Color.WHITE)
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
                    setTextColor(Color.WHITE)
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

        dialog = AlertDialog.Builder(this).setView(card).create()
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
            setTextColor(Color.WHITE)
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
            setTextColor(if (leftAccent) orange else Color.WHITE)
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

    private fun showBubble(card: LinearLayout) {
        aiPopup?.dismiss()
        val popup = PopupWindow(card,
            dp(210), ViewGroup.LayoutParams.WRAP_CONTENT, true).apply {
            isOutsideTouchable = true
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
        }
        aiPopup = popup

        val loc = IntArray(2)
        robot.getLocationInWindow(loc)
        val robotCx = loc[0] + robot.width / 2
        val robotTop = loc[1]
        val robotBottom = loc[1] + robot.height
        val half = rootFrame.height / 2
        val popupW = dp(210)
        val x = (robotCx - popupW / 2).coerceIn(dp(8), rootFrame.width - popupW - dp(8))

        if (robotTop > half) {
            popup.showAtLocation(rootFrame, Gravity.NO_GRAVITY, x, robotTop - dp(240))
        } else {
            popup.showAtLocation(rootFrame, Gravity.NO_GRAVITY, x, robotBottom + dp(8))
        }
    }

    private fun openLinkApp(app: LinkApp) {
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
            if (!charging && !flying && !standby) {
                robot.setImageResource(if (torchOn) R.drawable.robot_happy else R.drawable.robot_normal)
                applyFacing()
                if (!torchOn) timeHandler.postDelayed({ updateRobotFace() }, 600)
            }
        } catch (e: Exception) {
            Toast.makeText(this, "ライトの切替に失敗しました", Toast.LENGTH_SHORT).show()
        }
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
        timeHandler.removeCallbacks(chargeTick)
        timeHandler.post(chargeTick)
        timeHandler.removeCallbacks(robotAnim)
        timeHandler.post(robotAnim)
        lastInteractMs = System.currentTimeMillis()
        if (standby) {
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
        statusArc.stopBlink()
        aiPopup?.dismiss()
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
        return Pair(wifi, mobile)
    }

    private fun updateConnectivity() {
        val (wifi, mobile) = readNet()
        statusArc.wifiOn = wifi
        statusArc.mobileOn = mobile
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
            setTextColor(Color.WHITE)
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
            text = label; setTextColor(Color.WHITE); textSize = 14f
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
            setTextColor(Color.WHITE)
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
        card.addView(menuRow("UPDATE", "›", grayText) {
            dialog.dismiss(); openUpdatePage()
        })
        dialog = AlertDialog.Builder(this).setView(card).create()
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
            setTextColor(Color.WHITE)
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

        dialog = AlertDialog.Builder(this).setView(card).create()
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