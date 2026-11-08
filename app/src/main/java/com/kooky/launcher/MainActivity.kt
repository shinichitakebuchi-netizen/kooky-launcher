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
    private var lastBadgePkgs: Set<String> = emptySet()
    // アイコンの絵を覚えておく（毎回描かない）。見た目に関わる設定を全部キーに入れる
    private val iconCache = HashMap<String, android.graphics.drawable.Drawable>()
    private lateinit var mediaBar: MediaBar
    private lateinit var storageStat: StatItemView
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
    private val cornerR = 2
    private val fastChargeMa = 1500

    // テーマ：0=墨（ダーク）/ 1=白練（ライト）/ 2=移ろい（日の出〜日の入りは白練）
    private var themeMode = 0
    private var isLight = false

    // 色はすべて Wa（日本の伝統色）から。ダーク＝藍の地、ライト＝鳥の子の地
    private val orange get() = Wa.accent   // 差し色（設定で選んだ色。初期値は紅緋）
    private val bgColor get() = Wa.bg(isLight)         // 背景（藍色）
    private val textColor get() = Wa.ink(isLight)      // 文字・主（鳥の子色）
    private val subText get() = Wa.sub(isLight)        // 文字・補足（甕覗70%）
    private val cardBg get() = bgColor                 // カードは塗らない（背景と同じ）
    private val cardBorder get() = Wa.line(isLight)    // 枠線（利休鼠）
    private val divider get() = Wa.line(isLight)       // 罫線（利休鼠）
    private val grayText get() = Wa.sub(isLight)
    private val iconFill get() = bgColor

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

    private val fallbackLat = 36.389
    private val fallbackLon = 139.063
    private val LOC_REQ = 1001
    private val AUDIO_REQ = 1002

    private var showLabels = true
    private var useDesignIcon = true
    private var showInitial = true
    // 伝統文様（Pattern.kt）：0=なし / 1〜6。入れ方 0=薄く敷く / 1=角だけ / 2=枠を文様に
    private var patternType = 0
    private var patternStyle = 0

    private val readingMap = mapOf(
        "あはも" to "ahamo",
        "ぺいぺい" to "paypay",
        "らいん" to "line"
    )

    private var netCallback: ConnectivityManager.NetworkCallback? = null

    private var charging = false

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            if (level >= 0 && scale > 0) statusArc.ratio = level.toFloat() / scale.toFloat()
            val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                             status == BatteryManager.BATTERY_STATUS_FULL
            updateChargeMode(isCharging)
            charging = isCharging
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
    private var lastThemeMinute = -1L

    // ===== 四季のカラクリ（天気の長押し・新しい通知のとき） =====
    private lateinit var seasonView: SeasonView
    private var seasonOnNotif = true   // 新しい通知が届いたら舞わせる
    private var homeShown = false      // ホームが前に出ているか
    private var seasonPending = false  // ホームを見ていない間に通知が届いた

    private fun onNotifArrive() {
        if (!seasonOnNotif || !::seasonView.isInitialized) return
        if (homeShown) { if (!seasonView.isPlaying()) seasonView.play() }
        else seasonPending = true   // 次にホームを開いたときに1回だけ
    }
    private val tick = object : Runnable {
        override fun run() {
            val now = Date()
            val hm = hmFormat.format(now)
            val ss = secFormat.format(now)
            val text = "$hm $ss"
            val sp = SpannableString(text)
            sp.setSpan(RelativeSizeSpan(0.6f), hm.length + 1, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            clockText.text = sp
            val ds = dateFormat.format(now)
            if (dateText.text.toString() != ds) dateText.text = ds
            // 移ろい：分が変わったときに日の出・日の入りを過ぎたか確かめて、色を切り替える
            if (themeMode == 2 && now.time / 60000 != lastThemeMinute) {
                lastThemeMinute = now.time / 60000
                if (computeLight() != isLight) { applyTheme(); thread { LockWallpaper.update(this@MainActivity) } }
            }
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("usage", Context.MODE_PRIVATE)
        showLabels = prefs.getBoolean("show_labels", true)
        useDesignIcon = prefs.getBoolean("use_design_icon", true)
        showInitial = prefs.getBoolean("show_initial", true)
        patternType = prefs.getInt("pattern", 0).coerceIn(0, Pattern.NAMES.size - 1)
        patternStyle = prefs.getInt("pattern_style", 0).coerceIn(0, Pattern.STYLES.size - 1)
        themeMode = prefs.getInt("theme_mode", 0)
        Wa.loadAccent(this)
        fontIndex = prefs.getInt("font", 0).coerceIn(0, AppFonts.names.size - 1)
        appTf = AppFonts.typeface(this, fontIndex)
        isLight = computeLight()
        loadCategories()

        loadApps()

        NotificationService.onArrive = { runOnUiThread { onNotifArrive() } }
        NotificationService.onChange = {
            runOnUiThread {
                // 通知の点が変わったアプリがあるときだけ、アイコンを描き直す
                val now = NotificationService.activePackages
                if (now != lastBadgePkgs) { lastBadgePkgs = now; appAdapter.notifyDataSetChanged() }
                if (::ticker.isInitialized) updateTicker()
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
            // 長押し：四季のカラクリ（春は桜・夏は蛍・秋は紅葉・冬は六花）
            override fun onLongPress(e: MotionEvent) {
                if (::seasonView.isInitialized) {
                    weatherView.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                    seasonView.play()
                }
            }
        })
        weatherView = WeatherView(this).apply {
            weatherType = prefs.getInt("weather_type", 0)
            setOnTouchListener { _, ev -> weatherGesture.onTouchEvent(ev); true }
        }
        // インジケーター：数字は常に表示。ダブルタップでコントロールセンター（クイック設定）
        val arcGesture = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean { showWifiInfo(); return true }
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
        textOnlyTouch(clockText)
        dateText = TextView(this).apply {
            textSize = 16f
            setTextColor(subText)
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, 0)
            setOnClickListener { openCalendar() }
        }
        textOnlyTouch(dateText)

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
            // 時計・日付は横いっぱい（毎秒の書き換えで画面全体の並べ直しが起きないように）
            // タップは文字のあるところだけ（textOnlyTouch）
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
            setHintTextColor(subText)
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
            background = searchUnderline()
            setPadding(0, 0, 0, 0)
            addView(searchBar, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(searchIcon, LinearLayout.LayoutParams(dp(40), dp(40)))
        }

        mediaBar = MediaBar(this).apply {
            onActiveChanged = { active -> updateTicker(); if (active) maybeAskAudioPermission() }
        }
        ticker = TickerView(this).apply {
            visibility = View.GONE
            onItemTap = { openNotification(it) }
            onDoubleTap = { openNotificationPanel() }
            addOnLayoutChangeListener { _, l, t, r, b, ol, ot, orr, ob ->
                if (l != ol || t != ot || r != orr || b != ob) widenTickerTouch()
            }
        }

        grid = GridView(this).apply {
            numColumns = gridColumns
            val pad = dp(sideMarginDp)
            setPadding(pad, dp(appsTopPadDp), pad, dp(24))
            clipToPadding = false
            verticalSpacing = dp(16)
            adapter = appAdapter
            // タップはアプリの四角の中だけで反応させる（AppAdapter の icon に付けている）
            // 枠の外やアプリ名を押しても反応しないよう、マス全体の押した表示も消す
            selector = android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
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

        seasonOnNotif = prefs.getBoolean("season_notif", true)
        seasonView = SeasonView(this)
        rootFrame = FrameLayout(this).apply {
            addView(contentLayout, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            // いちばん上に重ねる（触っても下のホームに届く）
            addView(seasonView, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }

        setContentView(rootFrame)
        applyTheme()

        applyFilter()
        maybeAskNotificationAccess()
        maybeAskLocation()

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

    // アプリ一覧の箱：再生中は一番上に再生バー（アプリ1段分）が入り、アプリが1段下にずれる
    private val gridBox: LinearLayout by lazy {
        LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    }

    private fun buildGridBox(): LinearLayout {
        (mediaBar.parent as? ViewGroup)?.removeView(mediaBar)
        (grid.parent as? ViewGroup)?.removeView(grid)
        gridBox.removeAllViews()
        val side = dp(sideMarginDp + cellPadDp)
        gridBox.addView(mediaBar, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(cellHeightDp)).apply {
            setMargins(side, 0, side, 0)
        })
        gridBox.addView(grid, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        return gridBox
    }

    // 縦向き：上に時計まわり、下にアプリ（3段）
    // 横向き：左右50%ずつ。左に時計まわり、右に掲示板・検索・アプリ（2段）
    // 文字の左右の外を押したときは反応させない（文字の幅だけが押せる）
    private fun textOnlyTouch(tv: TextView) {
        tv.setOnTouchListener { v, e ->
            val l = (v as TextView).layout ?: return@setOnTouchListener false
            if (e.actionMasked != MotionEvent.ACTION_DOWN) return@setOnTouchListener false
            val left = v.totalPaddingLeft + l.getLineLeft(0) - dp(6)
            val right = v.totalPaddingLeft + l.getLineRight(0) + dp(6)
            // 外なら、この指の動きは丸ごと受け取って何もしない
            e.x < left || e.x > right
        }
    }

    // 流れる通知は20dpと細いので、押せる範囲を上に12dp・下に4dp広げる
    private fun widenTickerTouch() {
        val parent = ticker.parent as? View ?: return
        ticker.post {
            val r = android.graphics.Rect()
            ticker.getHitRect(r)
            r.top -= dp(12); r.bottom += dp(4)
            parent.touchDelegate = android.view.TouchDelegate(r, ticker)
        }
    }

    private fun arrangeLayout() {
        val land = resources.configuration.orientation ==
            android.content.res.Configuration.ORIENTATION_LANDSCAPE
        for (v in listOf<View>(topArea, ticker, searchBarRow, rightCol, gridBox)) {
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
            rightCol.addView(buildGridBox(), LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(2 * cellHeightDp)))
            contentLayout.addView(rightCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        } else {
            contentLayout.orientation = LinearLayout.VERTICAL
            contentLayout.addView(topArea, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            contentLayout.addView(ticker, tickerLp)
            contentLayout.addView(searchBarRow, barLp)
            contentLayout.addView(buildGridBox(), LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(gridRows * cellHeightDp)))
        }
    }

    // 画面の向きが変わったとき（作り直さずに並べ替える）
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        arrangeLayout()
        hideSystemBars()
    }

    // 移ろい：日の出から日の入りまで白練、それ以外は墨
    private fun computeLight(): Boolean = ThemeClock.isLight(this, themeMode)

    private fun themeLabel(): String = ThemeClock.NAMES.getOrElse(themeMode) { ThemeClock.NAMES[0] }

    // ===== 文字のフォント（縁と同じ3種類） =====
    private var fontIndex = 0
    private var appTf: Typeface = Typeface.SERIF
    private val FIXED_FONT = "fixedFont"   // このタグの文字は自分のフォントのまま（設定の見本など）

    // 画面の中の文字をすべて選んだフォントに（太字などの書体の強さはそのまま）
    private fun applyFont(v: View?) {
        when (v) {
            null -> return
            is StatItemView -> v.font = appTf
            is StatusArcView -> v.font = appTf
            is TickerView -> v.font = appTf
            is TextView -> if (v.tag != FIXED_FONT) {
                val style = v.typeface?.style ?: Typeface.NORMAL
                v.typeface = Typeface.create(appTf, style)
            }
            is ViewGroup -> for (i in 0 until v.childCount) applyFont(v.getChildAt(i))
        }
    }

    // 差し色を変える → ホーム・設定・ロック画面の壁紙に反映
    private fun setAccentColor(i: Int) {
        Wa.setAccent(i)
        prefs.edit().putInt(Wa.KEY_ACCENT, Wa.accentIndex).apply()
        applyTheme()
        mediaBar.restyle()
        renderSettings()
        thread { LockWallpaper.update(this, true) }
    }

    private fun setFont(i: Int) {
        fontIndex = i
        appTf = AppFonts.typeface(this, i)
        prefs.edit().putInt("font", i).apply()
        applyFont(window.decorView)
        appAdapter.notifyDataSetChanged()
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
        searchBarRow.background = searchUnderline()
        weatherView.lightMode = isLight
        statusArc.lightMode = isLight
        dataStat.lightMode = isLight
        ticker.lightMode = isLight
        mediaBar.lightMode = isLight
        storageStat.lightMode = isLight
        if (::seasonView.isInitialized) seasonView.lightMode = isLight
        applyFont(window.decorView)
        appAdapter.notifyDataSetChanged()
        thread { LockWallpaper.update(this) }
    }

    private fun dialogBuilder(): AlertDialog.Builder {
        val style = if (isLight) R.style.WaDialog_Light else R.style.WaDialog
        return AlertDialog.Builder(this, style)
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

    // ===== Wi-Fi情報＋速度測定（インジケーターを1回タップ） =====
    private fun showWifiInfo() {
        if (!readNet().first) {
            Toast.makeText(this, "Wi-Fiにつながっていません", Toast.LENGTH_SHORT).show(); return
        }
        var ssid = "--"; var rssi = 0; var freq = 0; var rx = -1; var tx = -1
        try {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
            @Suppress("DEPRECATION")
            val info = wm.connectionInfo
            ssid = info.ssid?.trim('"') ?: "--"
            if (ssid.isEmpty() || ssid.contains("unknown ssid")) ssid = "（位置情報オンで表示）"
            rssi = info.rssi
            freq = info.frequency
            if (android.os.Build.VERSION.SDK_INT >= 29) { rx = info.rxLinkSpeedMbps; tx = info.txLinkSpeedMbps }
            if (rx <= 0) rx = info.linkSpeed
        } catch (e: Exception) {}

        val strength = when { rssi >= -55 -> "強い"; rssi >= -70 -> "普通"; rssi != 0 -> "弱い"; else -> "--" }
        val band = when { freq <= 0 -> "--"; freq < 3000 -> "2.4 GHz"; freq < 5925 -> "5 GHz"; else -> "6 GHz" }
        val ch = when {
            freq <= 0 -> 0
            freq == 2484 -> 14
            freq < 3000 -> (freq - 2407) / 5
            freq < 5925 -> (freq - 5000) / 5
            else -> (freq - 5950) / 5
        }

        lateinit var dialog: AlertDialog
        var alive = true
        val card = menuCard()
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(10))
        }
        head.addView(TextView(this).apply {
            text = "Wi-Fi"; setTextColor(orange); textSize = 15f
            typeface = Typeface.create(Typeface.SERIF, Typeface.NORMAL); letterSpacing = 0.27f
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        head.addView(TextView(this).apply { text = "接続中"; setTextColor(Wa.WAKATAKE); textSize = 12f })
        card.addView(head)

        fun infoRow(label: String, value: String, sub: String = "") {
            card.addView(menuDivider())
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(18), dp(11), dp(18), dp(11))
            }
            row.addView(TextView(this).apply { text = label; setTextColor(grayText); textSize = 13f },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(TextView(this).apply {
                text = value; setTextColor(textColor); textSize = 14f; setTypeface(typeface, Typeface.BOLD)
            })
            if (sub.isNotEmpty()) row.addView(TextView(this).apply {
                text = "  $sub"; setTextColor(grayText); textSize = 11f
            })
            card.addView(row)
        }
        infoRow("ネットワーク", ssid)
        infoRow("電波", strength, if (rssi != 0) "$rssi dBm" else "")
        infoRow("周波数", band, if (ch > 0) "ch $ch" else "")
        infoRow("リンク速度", if (tx > 0) "$rx / $tx" else if (rx > 0) "$rx" else "--",
            if (tx > 0) "Mbps 下り/上り" else "Mbps")

        // 速度測定エリア
        card.addView(menuDivider())
        val area = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(12), dp(18), dp(14))
        }
        card.addView(area)

        fun showButton(label: String) {
            area.removeAllViews()
            area.addView(TextView(this).apply {
                text = label; setTextColor(textColor); textSize = 13f
                gravity = Gravity.CENTER
                setPadding(0, dp(9), 0, dp(9))
                background = GradientDrawable().apply {
                    cornerRadius = dp(cornerR).toFloat(); setColor(Color.TRANSPARENT); setStroke(dp(1), cardBorder)
                }
                isClickable = true
                setOnClickListener { runSpeedTest(area, { alive }) { showButton("もう一度測る") } }
            })
        }
        showButton("実際の速度を測る")

        applyFont(card)
        dialog = dialogBuilder().setView(card).create()
        dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
        dialog.setOnDismissListener { alive = false }
        dialog.show()
    }

    // 速度測定：応答時間（3回の平均）→ ダウンロード速度（最大25MB・最長8秒）
    private fun runSpeedTest(area: LinearLayout, isAlive: () -> Boolean, onDone: () -> Unit) {
        area.removeAllViews()
        val status = TextView(this).apply { text = "測定中…"; setTextColor(grayText); textSize = 12f }
        val track = FrameLayout(this).apply {
            background = GradientDrawable().apply { cornerRadius = dp(2).toFloat(); setColor(divider) }
        }
        val bar = View(this).apply {
            background = GradientDrawable().apply { cornerRadius = dp(2).toFloat(); setColor(orange) }
        }
        track.addView(bar, FrameLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT))
        area.addView(status)
        area.addView(track, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(4)).apply { topMargin = dp(6) })
        applyFont(area)

        thread {
            var pingMs = -1L
            var mbps = -1.0
            try {
                // 応答時間
                val pings = ArrayList<Long>()
                for (i in 0 until 3) {
                    val t = System.nanoTime()
                    val c = URL("https://speed.cloudflare.com/__down?bytes=0").openConnection() as HttpURLConnection
                    c.connectTimeout = 5000; c.readTimeout = 5000; c.useCaches = false
                    c.inputStream.use { it.readBytes() }
                    c.disconnect()
                    pings.add((System.nanoTime() - t) / 1_000_000)
                }
                pingMs = pings.sorted()[1]
                // ダウンロード
                val c = URL("https://speed.cloudflare.com/__down?bytes=25000000").openConnection() as HttpURLConnection
                c.connectTimeout = 5000; c.readTimeout = 8000; c.useCaches = false
                val start = System.nanoTime()
                var total = 0L
                var lastUi = 0L
                c.inputStream.use { ins ->
                    val buf = ByteArray(64 * 1024)
                    while (isAlive()) {
                        val n = ins.read(buf)
                        if (n < 0) break
                        total += n
                        val el = (System.nanoTime() - start) / 1e9
                        if (el > 8.0) break
                        if (System.nanoTime() - lastUi > 150_000_000L && el > 0.2) {
                            lastUi = System.nanoTime()
                            val cur = total * 8 / el / 1_000_000
                            val frac = (total / 25_000_000f).coerceAtMost(1f)
                            runOnUiThread {
                                status.text = "測定中… ${cur.toInt()} Mbps"
                                bar.layoutParams = (bar.layoutParams as FrameLayout.LayoutParams).apply {
                                    width = (track.width * frac).toInt()
                                }
                                bar.requestLayout()
                            }
                        }
                    }
                }
                c.disconnect()
                val el = (System.nanoTime() - start) / 1e9
                if (el > 0) mbps = total * 8 / el / 1_000_000
            } catch (e: Exception) {}

            if (!isAlive()) return@thread
            runOnUiThread {
                area.removeAllViews()
                area.post { applyFont(area) }   // 結果の文字も選んだフォントに
                if (mbps < 0) {
                    area.addView(TextView(this).apply {
                        text = "測定できませんでした"; setTextColor(grayText); textSize = 12f; gravity = Gravity.CENTER
                    })
                } else {
                    val res = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                    fun cell(label: String, value: String, unit: String, color: Int) =
                        LinearLayout(this).apply {
                            orientation = LinearLayout.VERTICAL
                            gravity = Gravity.CENTER
                            addView(TextView(this@MainActivity).apply {
                                text = label; setTextColor(grayText); textSize = 11f; gravity = Gravity.CENTER
                            })
                            addView(TextView(this@MainActivity).apply {
                                text = SpannableString("$value $unit").apply {
                                    setSpan(RelativeSizeSpan(0.5f), value.length, value.length + unit.length + 1,
                                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                                }
                                setTextColor(color); textSize = 22f; setTypeface(typeface, Typeface.BOLD)
                                gravity = Gravity.CENTER
                            })
                        }
                    res.addView(cell("下り", if (mbps >= 100) mbps.toInt().toString() else String.format(Locale.US, "%.1f", mbps), "Mbps", orange),
                        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    res.addView(cell("応答", if (pingMs >= 0) pingMs.toString() else "--", "ms", textColor),
                        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    area.addView(res)
                }
                onDone.let {
                    val again = TextView(this).apply {
                        text = "もう一度測る"; setTextColor(grayText); textSize = 12f; gravity = Gravity.CENTER
                        setPadding(0, dp(10), 0, 0); isClickable = true
                        setOnClickListener { runSpeedTest(area, isAlive, onDone) }
                    }
                    area.addView(again)
                }
            }
        }
    }

    // 音に合わせたイコライザー用：最初に再生バーが出たときに1回だけ許可を聞く
    private fun maybeAskAudioPermission() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) return
        if (prefs.getBoolean("asked_audio", false)) return
        prefs.edit().putBoolean("asked_audio", true).apply()
        Toast.makeText(this, "音に合わせてバーを動かすために「マイク」の許可を使います（録音はしません）", Toast.LENGTH_LONG).show()
        requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), AUDIO_REQ)
    }

    // 掲示板の更新（再生バーとは別に、検索バーの上でいつも流す）
    private fun updateTicker() {
        ticker.setItems(NotificationService.items)
        ticker.start()
    }

    // 上から引き下ろす通知パネルを開く
    @android.annotation.SuppressLint("WrongConstant")
    private fun openNotificationPanel() {
        try {
            val sbm = getSystemService("statusbar")
            Class.forName("android.app.StatusBarManager").getMethod("expandNotificationsPanel").invoke(sbm)
        } catch (e: Exception) {
            Toast.makeText(this, "通知パネルを開けませんでした", Toast.LENGTH_SHORT).show()
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
        if (isShortcut(app.pkg)) { startShortcutKey(app.pkg); return }
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
        homeShown = true
        if (seasonPending && seasonOnNotif && ::seasonView.isInitialized) {
            seasonPending = false
            timeHandler.postDelayed({ if (homeShown) seasonView.play() }, 500)
        }
        hideSystemBars()
        if (computeLight() != isLight) applyTheme()
        thread { LockWallpaper.update(this) }
        registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        registerNetworkCallback()
        updateConnectivity()
        loadShortcuts()
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
        timeHandler.removeCallbacks(statsTick)
        timeHandler.post(statsTick)
        mediaBar.start()
        updateTicker()
    }

    override fun onPause() {
        super.onPause()
        try { unregisterReceiver(batteryReceiver) } catch (e: Exception) {}
        unregisterNetworkCallback()
        timeHandler.removeCallbacks(tick)
        timeHandler.removeCallbacks(weatherTick)
        timeHandler.removeCallbacks(chargeTick)
        homeShown = false
        if (::seasonView.isInitialized) seasonView.stop()
        timeHandler.removeCallbacks(statsTick)
        ticker.stop()
        mediaBar.stop()
        statusArc.stopBlink()
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
        if (requestCode == AUDIO_REQ) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) mediaBar.eq.restart()
        }
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
                if (loc != null) {
                    lat = loc.latitude; lon = loc.longitude
                    ThemeClock.saveLocation(this, lat, lon)   // 移ろいの日の出・日の入りにも使う
                }
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
        Toast.makeText(this, "「使用状況へのアクセス」で「庵」を許可してください", Toast.LENGTH_LONG).show()
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

    // 検索バー：下に細い線が1本だけ
    private fun searchUnderline(): Drawable {
        val line = GradientDrawable().apply {
            setColor(Color.TRANSPARENT)
            setStroke(dp(1), textColor)
        }
        return android.graphics.drawable.LayerDrawable(arrayOf(line)).apply {
            setLayerInset(0, -dp(2), -dp(2), -dp(2), 0)
        }
    }

    private fun circleIcon(): Drawable {
        val size = dp(24)
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; color = textColor; strokeWidth = 1.3f * resources.displayMetrics.density
        }
        // 文様を選んでいるときは、その文様の印。なしのときはハンバーガーマーク（横線3本）
        if (patternType > 0) {
            Pattern.drawGlyph(canvas, patternType, size.toFloat(), orange, textColor, 1.3f * resources.displayMetrics.density)
            return BitmapDrawable(resources, bmp)
        }
        p.strokeCap = Paint.Cap.ROUND
        val l = dp(3).toFloat(); val r = size - dp(3).toFloat()
        for (y in listOf(dp(6), dp(12), dp(18))) {
            canvas.drawLine(l, y.toFloat(), r, y.toFloat(), p)
        }
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
            setTextColor(orange)
            textSize = 15f
            typeface = Typeface.create(Typeface.SERIF, Typeface.NORMAL)
            letterSpacing = 0.27f
            setPadding(dp(18), dp(16), dp(18), dp(10))
        }
    }

    private fun menuDivider(): View {
        return View(this).apply {
            setBackgroundColor(divider)
            // 細い罫線（0.5dp）
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                maxOf(1, (0.5f * resources.displayMetrics.density).toInt()))
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

    // ===== 設定（藍・鳥の子・紅緋の全画面。縁アプリと同じ配色・デザイン） =====
    private val SUMI = Wa.SUMI            // 背景：墨
    private val KINARI = Wa.TORINOKO      // 文字：鳥の子色
    private val USUZUMI = Wa.KAMENOZOKI70 // 補足：甕覗 70%
    private val SUMI_LINE = Wa.RIKYU      // 罫線：利休鼠
    private val SHU get() = Wa.accent     // 見出し：差し色（初期値は紅緋）
    private var settingsDialog: android.app.Dialog? = null
    private var settingsCol: LinearLayout? = null
    private var settingsScroll: ScrollView? = null
    // 設定の階層：-1=一覧 / 0〜7=各ページ
    private var settingsPage = -1
    private val SETTING_PAGES = listOf("表示", "色合い", "文様", "文字", "四季", "通信量", "ロック画面", "控え")

    private fun openSettingsPage(p: Int) {
        settingsPage = p
        renderSettings()
        settingsScroll?.scrollTo(0, 0)
    }

    private fun showSettingsMenu() {
        settingsPage = -1
        if (settingsDialog?.isShowing == true) { renderSettings(); return }
        val d = android.app.Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(28), dp(36), dp(28), dp(32))
        }
        val scroll = ScrollView(this).apply {
            setBackgroundColor(SUMI)
            isFillViewport = true
            addView(col, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        d.setContentView(scroll)
        d.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(SUMI))
        try {
            d.window?.statusBarColor = SUMI
            d.window?.navigationBarColor = SUMI
        } catch (e: Exception) {}
        d.setOnDismissListener { settingsDialog = null; settingsCol = null; settingsScroll = null; hideSystemBars() }
        // 戻るボタン：ページの中なら一覧へ、一覧なら閉じる
        d.setOnKeyListener { _, code, ev ->
            if (code == android.view.KeyEvent.KEYCODE_BACK && settingsPage >= 0) {
                if (ev.action == android.view.KeyEvent.ACTION_UP) openSettingsPage(-1)
                true
            } else false
        }
        settingsDialog = d
        settingsCol = col
        settingsScroll = scroll
        renderSettings()
        d.show()
    }

    private fun renderSettings() {
        val col = settingsCol ?: return
        col.removeAllViews()
        // 見出しも本文も、設定の「文字」で選んだフォント
        val serif = Typeface.create(appTf, Typeface.NORMAL)
        val serifBold = Typeface.create(appTf, Typeface.BOLD)

        fun rule(thin: Boolean = false) = View(this).apply {
            setBackgroundColor(SUMI_LINE)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, if (thin) 1 else dp(1))
        }
        fun note(t: String) = col.addView(TextView(this).apply {
            text = t; setTextColor(USUZUMI); textSize = 12f; setLineSpacing(dp(6).toFloat(), 1f)
        })
        fun section(t: String) {
            col.addView(TextView(this).apply {
                text = t; setTextColor(SHU); textSize = 15f; typeface = serif; letterSpacing = 0.27f
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(40); bottomMargin = dp(10)
            })
            col.addView(rule())
        }
        fun label(t: String) = col.addView(TextView(this).apply {
            text = t; setTextColor(KINARI); textSize = 15f
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(18); bottomMargin = dp(10)
        })
        // 入／切：行全体を押して切り替え
        fun toggle(t: String, on: Boolean, onChange: (Boolean) -> Unit) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(14), 0, dp(14))
                isClickable = true
                setOnClickListener { onChange(!on) }
            }
            row.addView(TextView(this).apply { text = t; setTextColor(KINARI); textSize = 15f },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(TextView(this).apply { text = "入"; setTextColor(if (on) SHU else SUMI_LINE); textSize = 14f })
            row.addView(TextView(this).apply { text = "　/　"; setTextColor(SUMI_LINE); textSize = 12f })
            row.addView(TextView(this).apply { text = "切"; setTextColor(if (on) SUMI_LINE else KINARI); textSize = 14f })
            col.addView(row)
            col.addView(rule(thin = true))
        }
        // 値の行：押すと入力
        fun valueRow(t: String, v: String, onClick: () -> Unit) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(14), 0, dp(14))
                isClickable = true
                setOnClickListener { onClick() }
            }
            row.addView(TextView(this).apply { text = t; setTextColor(KINARI); textSize = 15f },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(TextView(this).apply { text = v; setTextColor(USUZUMI); textSize = 14f })
            col.addView(row)
            col.addView(rule(thin = true))
        }
        // 選択肢：選択中は生成りで塗り、それ以外は細い枠
        fun choices(items: List<String>, selected: Int, onPick: (Int) -> Unit) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            items.forEachIndexed { i, t ->
                val sel = i == selected
                row.addView(TextView(this).apply {
                    text = t; textSize = 14f
                    setTextColor(if (sel) SUMI else KINARI)
                    setPadding(dp(16), dp(8), dp(16), dp(8))
                    background = GradientDrawable().apply {
                        cornerRadius = dp(2).toFloat()
                        if (sel) setColor(KINARI) else { setColor(Color.TRANSPARENT); setStroke(dp(1), SUMI_LINE) }
                    }
                    isClickable = true
                    setOnClickListener { onPick(i) }
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    if (i > 0) leftMargin = dp(10)
                })
            }
            col.addView(row)
        }

        // 一覧の1行：名前・今の設定・›
        fun menuRow(i: Int, summary: String) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(16), 0, dp(16))
                isClickable = true
                setOnClickListener { openSettingsPage(i) }
            }
            val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            texts.addView(TextView(this).apply { text = SETTING_PAGES[i]; setTextColor(KINARI); textSize = 15f })
            texts.addView(TextView(this).apply {
                text = summary; setTextColor(USUZUMI); textSize = 12f; setPadding(0, dp(4), 0, 0)
            })
            row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(TextView(this).apply { text = "›"; setTextColor(USUZUMI); textSize = 20f })
            col.addView(row)
            col.addView(rule(thin = true))
        }

        if (settingsPage < 0) {
            // ── 表題 ──
            val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.BOTTOM }
            head.addView(TextView(this).apply { text = "庵"; setTextColor(KINARI); textSize = 44f; typeface = serifBold })
            head.addView(TextView(this).apply {
                text = "いおり"; setTextColor(USUZUMI); textSize = 13f; letterSpacing = 0.23f
                setPadding(dp(12), 0, 0, dp(10))
            })
            head.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
            head.addView(TextView(this).apply {
                text = "閉じる"; setTextColor(USUZUMI); textSize = 13f; letterSpacing = 0.15f
                setPadding(dp(12), dp(8), 0, dp(10)); isClickable = true
                setOnClickListener { settingsDialog?.dismiss() }
            })
            col.addView(head)
            col.addView(View(this), LinearLayout.LayoutParams(1, dp(10)))
            note("ホームの見た目と動きを整えます。変えた内容はすぐにホームに反映されます。")

            // ── 一覧 ──
            section("設定")
            menuRow(0, listOf(if (showLabels) "アプリ名" else "アプリ名なし", if (showInitial) "頭文字" else "頭文字なし",
                if (useDesignIcon) "オリジナル" else "シンプル").joinToString("・"))
            menuRow(1, ThemeClock.NAMES.getOrElse(themeMode) { "" } + "・差し色 " + Wa.PICK_NAMES[Wa.accentIndex])
            menuRow(2, if (patternType == 0) "なし" else Pattern.NAMES[patternType] + "・" + Pattern.STYLES[patternStyle])
            menuRow(3, AppFonts.names[fontIndex])
            menuRow(4, if (seasonOnNotif) "通知で舞う" else "長押しのときだけ")
            menuRow(5, "締め日 ${closeDayLabel()}・上限 ${dataLimitGb()}GB")
            menuRow(6, if (LockWallpaper.isOn(this)) "1年の○を壁紙に" else "使わない")
            menuRow(7, "書き出す・読み込む")

            // ── 更新 ──
            val ver = try { packageManager.getPackageInfo(packageName, 0).versionName ?: "" } catch (e: Exception) { "" }
            col.addView(TextView(this).apply {
                text = "更新する　v$ver"; setTextColor(USUZUMI); textSize = 13f; letterSpacing = 0.15f
                gravity = Gravity.CENTER
                setPadding(dp(16), dp(12), dp(16), dp(12)); isClickable = true
                setOnClickListener { settingsDialog?.dismiss(); openUpdatePage() }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.CENTER_HORIZONTAL; topMargin = dp(56)
            })
            applyFont(col)
            return
        }

        // ── ページの上：‹ 設定（戻る）・閉じる ──
        val nav = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        nav.addView(TextView(this).apply {
            text = "‹　設定"; setTextColor(USUZUMI); textSize = 14f; letterSpacing = 0.15f
            setPadding(0, dp(8), dp(16), dp(8)); isClickable = true
            setOnClickListener { openSettingsPage(-1) }
        })
        nav.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        nav.addView(TextView(this).apply {
            text = "閉じる"; setTextColor(USUZUMI); textSize = 13f; letterSpacing = 0.15f
            setPadding(dp(16), dp(8), 0, dp(8)); isClickable = true
            setOnClickListener { settingsDialog?.dismiss() }
        })
        col.addView(nav)

        if (settingsPage == 0) {
        // ── 表示 ──
        section("表示")
        toggle("アプリ名", showLabels) {
            showLabels = it; prefs.edit().putBoolean("show_labels", it).apply()
            appAdapter.notifyDataSetChanged(); renderSettings()
        }
        toggle("頭文字", showInitial) {
            showInitial = it; prefs.edit().putBoolean("show_initial", it).apply()
            appAdapter.notifyDataSetChanged(); renderSettings()
        }
        label("アイコン")
        choices(listOf("オリジナル", "シンプル"), if (useDesignIcon) 0 else 1) {
            useDesignIcon = it == 0; prefs.edit().putBoolean("use_design_icon", useDesignIcon).apply()
            appAdapter.notifyDataSetChanged(); renderSettings()
        }

        }
        if (settingsPage == 1) {
        // ── 色合い ──
        section("色合い")
        col.addView(View(this), LinearLayout.LayoutParams(1, dp(14)))
        choices(ThemeClock.NAMES, themeMode) {
            themeMode = it; prefs.edit().putInt("theme_mode", it).apply()
            applyTheme(); renderSettings()
        }
        col.addView(View(this), LinearLayout.LayoutParams(1, dp(10)))
        val (rise, set) = ThemeClock.sunTimes(this)
        note("移ろいは、日の出から日の入りまで白練、それ以外は墨になります。" +
            "今日は ${rise / 60}:${"%02d".format(rise % 60)} 〜 ${set / 60}:${"%02d".format(set % 60)}。")

        // 差し色：見出し・ゲージ・電池の輪などの強調の色。18色（6色×3行）から選ぶ
        label("差し色")
        for (r in 0 until 3) {
            val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for (c in 0 until 6) {
                val i = r * 6 + c
                val sel = i == Wa.accentIndex
                val cell = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_HORIZONTAL
                    setPadding(0, dp(8), 0, 0)
                    isClickable = true
                    contentDescription = Wa.PICK_NAMES[i]
                    setOnClickListener { if (i != Wa.accentIndex) setAccentColor(i) }
                }
                val ring = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL; setColor(Color.TRANSPARENT)
                    if (sel) setStroke(dp(1), KINARI)   // 選択中は鳥の子色の細い輪
                }
                val dot = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL; setColor(Wa.PICK[i])
                    setStroke(maxOf(1, (0.5f * resources.displayMetrics.density).toInt()), SUMI_LINE)
                }
                cell.addView(View(this).apply {
                    background = android.graphics.drawable.LayerDrawable(arrayOf(ring, dot)).apply {
                        setLayerInset(1, dp(3), dp(3), dp(3), dp(3))
                    }
                }, LinearLayout.LayoutParams(dp(32), dp(32)))
                cell.addView(TextView(this).apply {
                    text = Wa.PICK_NAMES[i]; setTextColor(USUZUMI); textSize = 10f
                    maxLines = 1; gravity = Gravity.CENTER; setPadding(0, dp(3), 0, 0)
                })
                line.addView(cell, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            }
            col.addView(line)
        }
        col.addView(View(this), LinearLayout.LayoutParams(1, dp(10)))
        note("見出し・ゲージ・電池の輪・ロック画面の今日の○などの色です。アイコンの色は変わりません。")

        }
        if (settingsPage == 2) {
        // ── 文様 ──
        section("文様")
        col.addView(View(this), LinearLayout.LayoutParams(1, dp(14)))
        for (r in 0 until 2) {
            val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for (c in 0 until 4) {
                val i = r * 4 + c
                if (i >= Pattern.NAMES.size) {
                    line.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f)); continue
                }
                val sel = i == patternType
                val cell = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_HORIZONTAL
                    setPadding(0, dp(6), 0, dp(6))
                    isClickable = true
                    contentDescription = Pattern.NAMES[i]
                    setOnClickListener {
                        if (i != patternType) {
                            patternType = i; prefs.edit().putInt("pattern", i).apply()
                            applyTheme(); renderSettings()
                        }
                    }
                }
                // 見本：選んでいる入れ方で、その文様を描いた四角
                val sz = dp(48)
                val bmp = Bitmap.createBitmap(sz, sz, Bitmap.Config.ARGB_8888)
                val cv = Canvas(bmp)
                val ins = dp(2).toFloat()
                val pen = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(1).toFloat(); color = KINARI }
                if (i > 0) Pattern.drawInIcon(cv, i, patternStyle, sz.toFloat(), ins, dp(2).toFloat(),
                    when (patternStyle) { 1 -> Wa.KAMENOZOKI; 2 -> KINARI; else -> Wa.RIKYU },
                    SUMI, (if (patternStyle == 2) 0.7f else 0.6f) * resources.displayMetrics.density)
                if (i == 0 || patternStyle != 2) cv.drawRoundRect(ins, ins, sz - ins, sz - ins, dp(2).toFloat(), dp(2).toFloat(), pen)
                cell.addView(ImageView(this).apply {
                    setImageBitmap(bmp)
                    setPadding(dp(3), dp(3), dp(3), dp(3))
                    // 選択中は鳥の子色の細い枠
                    background = GradientDrawable().apply {
                        cornerRadius = dp(2).toFloat(); setColor(Color.TRANSPARENT)
                        if (sel) setStroke(dp(1), KINARI)
                    }
                }, LinearLayout.LayoutParams(dp(56), dp(56)))
                cell.addView(TextView(this).apply {
                    text = Pattern.NAMES[i]; textSize = 11f
                    setTextColor(if (sel) KINARI else USUZUMI)
                    gravity = Gravity.CENTER; setPadding(0, dp(4), 0, 0)
                })
                line.addView(cell, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            }
            col.addView(line)
        }
        label("入れ方")
        choices(Pattern.STYLES, patternStyle) {
            patternStyle = it; prefs.edit().putInt("pattern_style", it).apply()
            applyTheme(); renderSettings()
        }
        col.addView(View(this), LinearLayout.LayoutParams(1, dp(10)))
        note("アプリの四角と、検索バーの右にある設定の印に使います。設定の印は、なしのときは三本線です。")

        }
        if (settingsPage == 3) {
        // ── 文字 ──
        section("文字")
        col.addView(View(this), LinearLayout.LayoutParams(1, dp(14)))
        AppFonts.order.forEachIndexed { pos, i ->
            val name = AppFonts.names[i]
            val sel = i == fontIndex
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), dp(12), dp(16), dp(12))
                background = GradientDrawable().apply {
                    cornerRadius = dp(2).toFloat(); setColor(Color.TRANSPARENT)
                    // 選択中は鳥の子色、それ以外は利休鼠の細い枠
                    setStroke(dp(1), if (sel) KINARI else SUMI_LINE)
                }
                isClickable = true
                setOnClickListener { if (i != fontIndex) { setFont(i); renderSettings() } }
            }
            // 見本はそのフォントで表示（全体のフォント変更の対象外）
            row.addView(TextView(this).apply {
                text = "庵　いおり"; setTextColor(KINARI); textSize = 22f
                typeface = AppFonts.typeface(this@MainActivity, i); tag = FIXED_FONT
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(TextView(this).apply { text = name; setTextColor(USUZUMI); textSize = 12f })
            col.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                if (pos > 0) topMargin = dp(10)
            })
        }
        col.addView(View(this), LinearLayout.LayoutParams(1, dp(10)))
        note("Kaisei Tokumin・Yuji Syuku は SIL Open Font License 1.1 の無料フォントです。")

        }
        if (settingsPage == 4) {
        // ── 四季 ──
        section("四季のカラクリ")
        toggle("通知で四季が舞う", seasonOnNotif) {
            seasonOnNotif = it; prefs.edit().putBoolean("season_notif", it).apply()
            if (!it) seasonPending = false
            renderSettings()
        }
        note("新しい通知が届くと、春は桜、夏は昼が水面の波紋・夜が蛍、秋は紅葉、冬は六花が舞います。ホームを見ていないときに届いたら、次にホームを開いたときに1回。天気を長押ししても舞います。")
        }
        if (settingsPage == 5) {
        // ── 通信量 ──
        section("通信量")
        valueRow("締め日", closeDayLabel()) { showDataSettings() }
        valueRow("月の上限", "${dataLimitGb()}GB") { showDataSettings() }

        }
        if (settingsPage == 6) {
        // ── ロック画面 ──
        section("ロック画面")
        toggle("1年の○を壁紙にする", LockWallpaper.isOn(this)) { on ->
            prefs.edit().putBoolean(LockWallpaper.KEY_ON, on).apply()
            renderSettings()
            thread {
                if (on) LockWallpaper.update(this, true) else LockWallpaper.turnOff(this)
                runOnUiThread {
                    Toast.makeText(this,
                        if (on) "ロック画面の壁紙を設定しました" else "ロック画面の壁紙を元に戻しました",
                        Toast.LENGTH_SHORT).show()
                }
            }
        }

        }
        if (settingsPage == 7) {
        // ── 控え ──
        section("控え")
        col.addView(View(this), LinearLayout.LayoutParams(1, dp(14)))
        note("利用回数・名前の変更・カテゴリ・設定をファイルに保存し、あとで戻せます。")
        col.addView(View(this), LinearLayout.LayoutParams(1, dp(14)))
        choices(listOf("書き出す", "読み込む"), -1) {
            settingsDialog?.dismiss()
            if (it == 0) startExport() else startImport()
        }

        }
        applyFont(col)
    }

    // ===== バックアップ（書き出し／読み込み） =====
    private val REQ_EXPORT = 2001
    private val REQ_IMPORT = 2002
    private val backupSkipKeys = setOf("lock_dots_last")

    private fun showBackupMenu() {
        dialogBuilder()
            .setTitle("バックアップ")
            .setMessage("利用回数・名前の変更・カテゴリ・設定をファイルに保存／復元します。\n保存先はダウンロードやGoogleドライブを選べます。")
            .setPositiveButton("書き出す") { _, _ -> startExport() }
            .setNeutralButton("読み込む") { _, _ -> startImport() }
            .setNegativeButton("キャンセル", null)
            .show()
    }

    private fun startExport() {
        val name = "kooky-backup-" + SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date()) + ".json"
        try {
            startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "application/json"
                putExtra(Intent.EXTRA_TITLE, name)
            }, REQ_EXPORT)
        } catch (e: Exception) {
            Toast.makeText(this, "保存先を選ぶ画面を開けませんでした", Toast.LENGTH_SHORT).show()
        }
    }

    private fun startImport() {
        try {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
            }, REQ_IMPORT)
        } catch (e: Exception) {
            Toast.makeText(this, "ファイルを選ぶ画面を開けませんでした", Toast.LENGTH_SHORT).show()
        }
    }

    private fun backupJson(): String {
        val root = JSONObject()
        root.put("app", "kooky-launcher")
        root.put("version", 1)
        root.put("saved_at", System.currentTimeMillis())
        val data = JSONObject()
        for ((k, v) in prefs.all) {
            if (k in backupSkipKeys || v == null) continue
            val o = JSONObject()
            when (v) {
                is Boolean -> { o.put("t", "b"); o.put("v", v) }
                is Int -> { o.put("t", "i"); o.put("v", v) }
                is Long -> { o.put("t", "l"); o.put("v", v) }
                is Float -> { o.put("t", "f"); o.put("v", v.toDouble()) }
                is String -> { o.put("t", "s"); o.put("v", v) }
                else -> continue
            }
            data.put(k, o)
        }
        root.put("data", data)
        return root.toString(2)
    }

    private fun restoreJson(text: String): Int {
        val root = JSONObject(text)
        if (root.optString("app") != "kooky-launcher") throw IllegalArgumentException("not kooky")
        val data = root.getJSONObject("data")
        val ed = prefs.edit()
        ed.clear()
        var n = 0
        for (k in data.keys()) {
            val o = data.getJSONObject(k)
            when (o.getString("t")) {
                "b" -> ed.putBoolean(k, o.getBoolean("v"))
                "i" -> ed.putInt(k, o.getInt("v"))
                "l" -> ed.putLong(k, o.getLong("v"))
                "f" -> ed.putFloat(k, o.getDouble("v").toFloat())
                "s" -> ed.putString(k, o.getString("v"))
                else -> continue
            }
            n++
        }
        ed.commit()
        return n
    }

    @Deprecated("Activity API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (resultCode != RESULT_OK || uri == null) return
        when (requestCode) {
            REQ_EXPORT -> try {
                contentResolver.openOutputStream(uri, "wt")?.use { it.write(backupJson().toByteArray(Charsets.UTF_8)) }
                Toast.makeText(this, "バックアップを保存しました", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this, "保存に失敗しました", Toast.LENGTH_SHORT).show()
            }
            REQ_IMPORT -> {
                val text = try {
                    contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
                } catch (e: Exception) { null }
                if (text == null) {
                    Toast.makeText(this, "ファイルを読めませんでした", Toast.LENGTH_SHORT).show(); return
                }
                dialogBuilder()
                    .setTitle("読み込み")
                    .setMessage("今の利用回数や設定は、このバックアップの内容に置き換わります。よろしいですか？")
                    .setPositiveButton("読み込む") { _, _ ->
                        try {
                            val n = restoreJson(text)
                            Toast.makeText(this, "${n}件を復元しました", Toast.LENGTH_SHORT).show()
                            recreate()
                        } catch (e: Exception) {
                            Toast.makeText(this, "庵のバックアップではないようです", Toast.LENGTH_LONG).show()
                        }
                    }
                    .setNegativeButton("キャンセル", null)
                    .show()
            }
        }
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
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1))
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
        card.addView(usageSection(app))
        card.addView(menuDivider())
        card.addView(favSection(app))
        card.addView(menuDivider())
        if (isShortcut(app.packageName)) {
            card.addView(menuItem("ホームから外す") {
                dialog.dismiss(); removeShortcutKey(app.packageName)
            })
        } else {
            card.addView(menuItem("アプリ詳細") {
                dialog.dismiss(); openAppDetail(app.packageName)
            })
        }

        applyFont(card)
        dialog = dialogBuilder().setView(card).create()
        dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
        // 閉じたときに回数の並び替えを反映
        dialog.setOnDismissListener { sortApps(); applyFilter() }
        dialog.show()
    }

    // ===== 利用回数（見える化＋編集） =====
    private fun usageSection(app: AppEntry): View {
        val key = app.packageName
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(12), dp(18), dp(12))
        }
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        head.addView(TextView(this).apply { text = "利用回数"; setTextColor(textColor); textSize = 14f },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        fun btn(t: String) = TextView(this).apply {
            text = t; setTextColor(orange); textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            isClickable = true
        }
        val minus = btn("−")
        val countText = TextView(this).apply {
            setTextColor(textColor); textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            minWidth = dp(48)
            isClickable = true
        }
        val plus = btn("＋")
        head.addView(minus, LinearLayout.LayoutParams(dp(36), dp(32)))
        head.addView(countText, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(32)))
        head.addView(plus, LinearLayout.LayoutParams(dp(36), dp(32)))
        box.addView(head)

        // バー（いちばん使っているアプリを100%として表示）
        // 2dpの線：残りは利休鼠、回数ぶんは鳥の子色
        val track = FrameLayout(this).apply { setBackgroundColor(divider) }
        val bar = View(this).apply { setBackgroundColor(textColor) }
        track.addView(bar, FrameLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT))
        box.addView(track, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(2)).apply {
            topMargin = dp(10)
        })
        val foot = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val rankText = TextView(this).apply { setTextColor(grayText); textSize = 12f }
        val reset = TextView(this).apply {
            text = "リセット"; setTextColor(grayText); textSize = 12f
            setPadding(dp(8), dp(6), 0, dp(2)); isClickable = true
        }
        foot.addView(rankText, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        foot.addView(reset)
        box.addView(foot, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(4)
        })

        fun refresh() {
            val c = prefs.getInt(key, 0)
            countText.text = c.toString()
            val counts = apps.map { if (it.packageName == key) c else prefs.getInt(it.packageName, 0) }
            val max = (counts.maxOrNull() ?: 0).coerceAtLeast(1)
            val rank = counts.count { it > c } + 1
            rankText.text = if (c == 0) "まだ使っていません" else "全${apps.size}個中 ${rank}位"
            track.post {
                bar.layoutParams = (bar.layoutParams as FrameLayout.LayoutParams).apply {
                    width = (track.width * (c.toFloat() / max)).toInt()
                }
                bar.requestLayout()
            }
        }
        fun set(v: Int) {
            val n = v.coerceIn(0, 99999)
            if (n == 0) prefs.edit().remove(key).apply() else prefs.edit().putInt(key, n).apply()
            refresh()
        }
        minus.setOnClickListener { set(prefs.getInt(key, 0) - 1) }
        plus.setOnClickListener { set(prefs.getInt(key, 0) + 1) }
        reset.setOnClickListener { set(0) }
        countText.setOnClickListener {
            val input = EditText(this).apply {
                setText(prefs.getInt(key, 0).toString()); setTextColor(textColor); setSingleLine(true)
                inputType = android.text.InputType.TYPE_CLASS_NUMBER
                setSelection(text.length)
            }
            dialogBuilder().setTitle("利用回数を入力").setView(input)
                .setPositiveButton("保存") { _, _ -> set(input.text.toString().toIntOrNull() ?: 0) }
                .setNegativeButton("キャンセル", null).show()
        }
        refresh()
        return box
    }

    // ===== お気に入り（アプリ枠の色） =====
    // 保存値：0=なし / 101〜118=伝統色18色（Wa.PICK の順）
    // 以前の値（1=オレンジ / 2=青 / 3=赤）は 紅緋 / 新橋色 / 韓紅花 として読む
    private val FAV_BASE = 100

    private fun favColorOf(key: String): Int? {
        val f = prefs.getInt("fav_$key", 0)
        return when (f) {
            1 -> Wa.BENIHI
            2 -> Wa.PICK[12]   // 新橋色
            3 -> Wa.KARAKURENAI
            in (FAV_BASE + 1)..(FAV_BASE + Wa.PICK.size) -> Wa.PICK[f - FAV_BASE - 1]
            else -> null
        }
    }

    // 今の保存値を 0〜18 の番号に（0=なし）
    private fun favIndexOf(key: String): Int = when (val f = prefs.getInt("fav_$key", 0)) {
        1 -> 4; 2 -> 13; 3 -> 3
        in (FAV_BASE + 1)..(FAV_BASE + Wa.PICK.size) -> f - FAV_BASE
        else -> 0
    }

    // 6色×3行。丸い色見本の下に小さく色名、選択中は鳥の子色の細い輪
    private fun favSection(app: AppEntry): View {
        val key = app.packageName
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(12), dp(18), dp(14))
        }
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        head.addView(TextView(this).apply { text = "お気に入り"; setTextColor(textColor); textSize = 14f },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val none = TextView(this).apply {
            text = "なし"; textSize = 12f
            setPadding(dp(12), dp(4), dp(12), dp(4))
            isClickable = true
        }
        head.addView(none)
        box.addView(head)

        val thin = maxOf(1, (0.5f * resources.displayMetrics.density).toInt())
        val swatches = ArrayList<View>()
        fun paint() {
            val cur = favIndexOf(key)
            for ((i, v) in swatches.withIndex()) {
                val sel = cur == i + 1
                val ring = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL; setColor(Color.TRANSPARENT)
                    if (sel) setStroke(maxOf(1, (1f * resources.displayMetrics.density).toInt()), Wa.TORINOKO)
                }
                val dot = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL; setColor(Wa.PICK[i])
                    setStroke(thin, Wa.RIKYU)   // 藍色など地に溶ける色も見えるように
                }
                v.background = android.graphics.drawable.LayerDrawable(arrayOf(ring, dot)).apply {
                    setLayerInset(1, dp(3), dp(3), dp(3), dp(3))
                }
            }
            val noneSel = cur == 0
            none.setTextColor(if (noneSel) bgColor else textColor)
            none.background = GradientDrawable().apply {
                cornerRadius = dp(2).toFloat()
                if (noneSel) setColor(textColor) else { setColor(Color.TRANSPARENT); setStroke(dp(1), cardBorder) }
            }
        }
        fun pick(i: Int) {
            if (i == 0) prefs.edit().remove("fav_$key").apply()
            else prefs.edit().putInt("fav_$key", FAV_BASE + i).apply()
            paint(); appAdapter.notifyDataSetChanged()
        }
        none.setOnClickListener { pick(0) }

        for (r in 0 until 3) {
            val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for (c in 0 until 6) {
                val i = r * 6 + c
                val cell = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_HORIZONTAL
                    setPadding(0, dp(8), 0, 0)
                    isClickable = true
                    contentDescription = Wa.PICK_NAMES[i]
                    setOnClickListener { pick(i + 1) }
                }
                val sw = View(this)
                swatches.add(sw)
                cell.addView(sw, LinearLayout.LayoutParams(dp(30), dp(30)))
                cell.addView(TextView(this).apply {
                    text = Wa.PICK_NAMES[i]; setTextColor(subText); textSize = 9f
                    maxLines = 1; gravity = Gravity.CENTER
                    setPadding(0, dp(2), 0, 0)
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                line.addView(cell, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            }
            box.addView(line)
        }
        paint()
        return box
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
                "通知バッジを使うには「通知へのアクセス」で「庵」を許可してください",
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
        loadShortcuts()
        sortApps()
    }

    // ===== ブラウザ等から「ホーム画面に追加」されたショートカット =====
    // アプリ一覧では packageName を "shortcut:<アプリ>/<ID>" として扱う
    private val SC = "shortcut:"

    private fun isShortcut(key: String) = key.startsWith(SC)

    private fun loadShortcuts() {
        apps.removeAll { isShortcut(it.packageName) }
        try {
            val la = getSystemService(Context.LAUNCHER_APPS_SERVICE) as android.content.pm.LauncherApps
            if (!la.hasShortcutHostPermission()) return
            val q = android.content.pm.LauncherApps.ShortcutQuery()
                .setQueryFlags(android.content.pm.LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED)
            val list = la.getShortcuts(q, android.os.Process.myUserHandle()) ?: return
            val dpi = resources.displayMetrics.densityDpi
            for (sc in list) {
                if (!sc.isEnabled) continue
                val label = (sc.shortLabel ?: sc.longLabel ?: "").toString()
                val icon = try { la.getShortcutIconDrawable(sc, dpi) } catch (e: Exception) { null }
                    ?: packageManager.getApplicationIcon(sc.`package`)
                apps.add(AppEntry(label, SC + sc.`package` + "/" + sc.id, icon))
            }
        } catch (e: Exception) {}
    }

    private fun startShortcutKey(key: String): Boolean {
        return try {
            val body = key.removePrefix(SC)
            val pkg = body.substringBefore("/")
            val id = body.substringAfter("/")
            val la = getSystemService(Context.LAUNCHER_APPS_SERVICE) as android.content.pm.LauncherApps
            la.startShortcut(pkg, id, null, null, android.os.Process.myUserHandle())
            true
        } catch (e: Exception) {
            Toast.makeText(this, "ショートカットを開けませんでした", Toast.LENGTH_SHORT).show()
            false
        }
    }

    // ショートカットをホームから外す
    private fun removeShortcutKey(key: String) {
        try {
            val body = key.removePrefix(SC)
            val pkg = body.substringBefore("/")
            val id = body.substringAfter("/")
            val la = getSystemService(Context.LAUNCHER_APPS_SERVICE) as android.content.pm.LauncherApps
            val q = android.content.pm.LauncherApps.ShortcutQuery()
                .setPackage(pkg)
                .setQueryFlags(android.content.pm.LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED)
            val keep = (la.getShortcuts(q, android.os.Process.myUserHandle()) ?: emptyList())
                .map { it.id }.filter { it != id }
            la.pinShortcuts(pkg, keep, android.os.Process.myUserHandle())
            prefs.edit().remove(key).remove("name_$key").remove("fav_$key").apply()
            loadShortcuts(); sortApps(); applyFilter()
            Toast.makeText(this, "ホームから外しました", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "外せませんでした", Toast.LENGTH_SHORT).show()
        }
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
        if (isShortcut(pkg)) { startShortcutKey(pkg); return }
        packageManager.getLaunchIntentForPackage(pkg)?.let { startActivity(it) }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    // シンプルアイコン用：お気に入りの色枠で囲む
    private fun framedIcon(base: Drawable, color: Int, withNotif: Boolean): Drawable {
        val size = dp(56)
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val pad = dp(5)
        val i = dp(2).toFloat()
        val waku = patternType > 0 && patternStyle == 2
        if (patternType > 0 && !waku) drawIconPattern(canvas, size, color)   // 文様はアイコンの後ろに
        base.setBounds(pad, pad, size - pad, size - pad); base.draw(canvas)
        if (waku) drawIconPattern(canvas, size, color)
        else {
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE; this.color = color; strokeWidth = dp(1).toFloat()
            }
            canvas.drawRoundRect(i, i, size - i, size - i, dp(cornerR).toFloat(), dp(cornerR).toFloat(), p)
        }
        if (withNotif) drawDot(canvas, size)
        return BitmapDrawable(resources, bmp)
    }

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
        val frameColor = favColorOf(app.packageName) ?: textColor
        if (patternType > 0) drawIconPattern(canvas, size, frameColor)
        // 「枠を文様に」のときは、細い線の枠の代わりに文様の帯
        if (patternType == 0 || patternStyle != 2) {
            val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE; color = frameColor
                strokeWidth = dp(1).toFloat()
            }
            canvas.drawRoundRect(inset, inset, size - inset, size - inset, radius, radius, border)
        }
        if (showInitial) {
            val ch = firstChar(displayName(app))
            if (ch.isNotEmpty()) {
                val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = textColor; textSize = size * 0.42f
                    textAlign = Paint.Align.CENTER; typeface = appTf
                }
                val cy = size / 2f - (tp.descent() + tp.ascent()) / 2f
                canvas.drawText(ch, size / 2f, cy, tp)
            }
        }
        if (withNotif) drawDot(canvas, size)
        return BitmapDrawable(resources, bmp)
    }

    // アプリの四角に文様を入れる。frameColor は「枠を文様に」のときの色（お気に入りの色など）
    private fun drawIconPattern(canvas: Canvas, size: Int, frameColor: Int,
                                type: Int = patternType, style: Int = patternStyle) {
        if (type <= 0) return
        val d = resources.displayMetrics.density
        val color = when (style) {
            1 -> if (isLight) Wa.RIKYU else Wa.KAMENOZOKI   // 角だけ：甕覗（白練の地では利休鼠）
            2 -> frameColor                                   // 枠を文様に：枠と同じ色
            else -> Wa.RIKYU                                  // 薄く敷く：利休鼠
        }
        val sw = (if (style == 2) 0.7f else 0.6f) * d
        Pattern.drawInIcon(canvas, type, style, size.toFloat(), dp(2).toFloat(), dp(cornerR).toFloat(),
            color, iconFill, sw)
    }

    private fun firstChar(name: String): String {
        val t = name.trim()
        return if (t.isEmpty()) "" else t.substring(0, 1).uppercase()
    }

    private fun drawDot(canvas: Canvas, size: Int) {
        val radius = dp(7).toFloat()
        val cx = size - radius - dp(2)
        val cy = radius + dp(2)
        // 紅緋の点。まわりを背景色で抜いて、枠と重ならないように
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = bgColor }
        canvas.drawCircle(cx, cy, radius + dp(2), stroke)
        val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = orange }
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
            val fav = favColorOf(app.packageName)
            val key = "${app.packageName}|$hasNotif|$fav|$isLight|${Wa.accentIndex}|$patternType|$patternStyle|" +
                "$fontIndex|$showInitial|$useDesignIcon|${displayName(app)}"
            if (iconCache.size > 400) iconCache.clear()
            val iconDrawable = iconCache.getOrPut(key) {
                if (useDesignIcon) designIcon(app, hasNotif)
                else if (fav != null) framedIcon(app.icon, fav, hasNotif)
                else if (hasNotif) withBadge(app.icon) else app.icon
            }
            val icon = ImageView(this@MainActivity).apply {
                setImageDrawable(iconDrawable)
                layoutParams = LinearLayout.LayoutParams(dp(56), dp(56))
                contentDescription = displayName(app)
                // 四角の中だけが押せる場所
                setOnClickListener { launchApp(app.packageName) }
                setOnLongClickListener { showAppMenu(app); true }
                // 押している間だけ少し薄くする
                setOnTouchListener { v, e ->
                    when (e.actionMasked) {
                        android.view.MotionEvent.ACTION_DOWN -> v.alpha = 0.6f
                        android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> v.alpha = 1f
                    }
                    false
                }
            }
            cell.addView(icon)
            val label = TextView(this@MainActivity).apply {
                text = if (showLabels) displayName(app) else ""
                setTextColor(textColor); textSize = 12f
                gravity = Gravity.CENTER; maxLines = 1
                setPadding(0, dp(4), 0, 0)
                typeface = appTf
            }
            cell.addView(label)
            return cell
        }
    }
}
