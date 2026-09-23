package com.kooky.launcher

import android.app.Activity
import android.app.AlertDialog
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ResolveInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {

    data class AppEntry(val label: String, val packageName: String, val icon: Drawable)

    private val apps = mutableListOf<AppEntry>()
    private val shown = mutableListOf<AppEntry>()
    private lateinit var prefs: SharedPreferences
    private val appAdapter = AppAdapter()

    private lateinit var searchBar: EditText
    private lateinit var clockText: TextView
    private lateinit var dateText: TextView

    private val gridColumns = 4
    private val topGapDp = 290
    private val appsTopPadDp = 4

    private var showLabels = true          // アプリ名の表示/非表示
    private var useDesignIcon = true       // 統一デザインアイコンを使うか
    private var showInitial = true         // true=案Y(頭文字) / false=案X(記号なし)

    private val readingMap = mapOf(
        "あはも" to "ahamo",
        "ぺいぺい" to "paypay",
        "らいん" to "line"
    )

    private val timeHandler = Handler(Looper.getMainLooper())
    private val clockFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    private val dateFormat = SimpleDateFormat("yyyy.MM.dd-EEE", Locale.JAPANESE)
    private val tick = object : Runnable {
        override fun run() {
            val now = Date()
            clockText.text = clockFormat.format(now)
            dateText.text = dateFormat.format(now)
            timeHandler.postDelayed(this, 1000)
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
            addView(clockText)
            addView(dateText)
        }

        searchBar = EditText(this).apply {
            setSingleLine(true)
            setTextColor(Color.parseColor("#101014"))
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
            setImageResource(android.R.drawable.ic_menu_search)
            setColorFilter(Color.parseColor("#101014"))
            val p = dp(8)
            setPadding(p, p, p, p)
            // 長押しで設定メニュー
            setOnLongClickListener { showSettingsMenu(); true }
        }

        val searchBarRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(12).toFloat()
                setColor(Color.parseColor("#FF8C00"))
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
            // アプリ長押しで名前変更
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
    }

    override fun onResume() {
        super.onResume()
        sortApps()
        applyFilter()
        resetSearch()
        appAdapter.notifyDataSetChanged()
        timeHandler.removeCallbacks(tick)
        timeHandler.post(tick)
    }

    override fun onPause() {
        super.onPause()
        timeHandler.removeCallbacks(tick)
    }

    // 設定メニュー（検索アイコン長押し）
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

    // アプリ名の変更（アプリ長押し）
    private fun renameApp(app: AppEntry) {
        val input = EditText(this).apply {
            setText(displayName(app))
            setSingleLine(true)
        }
        AlertDialog.Builder(this)
            .setTitle("名前を変更")
            .setView(input)
            .setPositiveButton("保存") { _, _ ->
                val newName = input.text.toString().trim()
                if (newName.isNotEmpty()) {
                    prefs.edit().putString("name_" + app.packageName, newName).apply()
                } else {
                    prefs.edit().remove("name_" + app.packageName).apply() // 空なら元に戻す
                }
                sortApps()
                applyFilter()
            }
            .setNegativeButton("元に戻す") { _, _ ->
                prefs.edit().remove("name_" + app.packageName).apply()
                sortApps()
                applyFilter()
            }
            .setNeutralButton("キャンセル", null)
            .show()
    }

    // 表示名（変更済みならそれ、無ければ元のラベル）
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
        val clockPackages = listOf(
            "com.google.android.deskclock",
            "com.sec.android.app.clockpackage"
        )
        for (pkg in clockPackages) {
            val intent = packageManager.getLaunchIntentForPackage(pkg)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
                return
            }
        }
        try {
            startActivity(Intent(Intent.ACTION_MAIN)
                .addCategory("android.intent.category.APP_DESKCLOCK")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        } catch (e: Exception) {}
        try {
            startActivity(Intent(AlarmClock.ACTION_SHOW_ALARMS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        } catch (e: Exception) {}
        Toast.makeText(this, "時計アプリが見つかりませんでした", Toast.LENGTH_SHORT).show()
    }

    private fun showClockDiagnostics() {
        val pm = packageManager
        val intent = Intent(Intent.ACTION_MAIN, null).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        val all = pm.queryIntentActivities(intent, 0)
        val hits = all.map { it.activityInfo.packageName }
            .filter {
                it.contains("clock", true) ||
                it.contains("deskclock", true) ||
                it.contains("alarm", true)
            }
            .distinct()
        val msg = if (hits.isEmpty()) {
            "時計候補が見つかりません。一覧の時計アプリ名を教えてください"
        } else {
            "時計候補: " + hits.joinToString(" / ")
        }
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }

    private fun openCalendar() {
        try {
            val builder = CalendarContract.CONTENT_URI.buildUpon().appendPath("time")
            ContentUris.appendId(builder, System.currentTimeMillis())
            startActivity(Intent(Intent.ACTION_VIEW, builder.build())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
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
        if (!searchBar.text.isNullOrEmpty()) {
            resetSearch()
        } else {
            super.onBackPressed()
        }
    }

    private fun loadApps() {
        val pm = packageManager
        val intent = Intent(Intent.ACTION_MAIN, null).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        val list: List<ResolveInfo> = pm.queryIntentActivities(intent, 0)
        apps.clear()
        for (ri in list) {
            val pkg = ri.activityInfo.packageName
            if (pkg == packageName) continue
            apps.add(
                AppEntry(
                    label = ri.loadLabel(pm).toString(),
                    packageName = pkg,
                    icon = ri.loadIcon(pm)
                )
            )
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
        for (c in s) {
            if (c in '\u30A1'..'\u30F6') sb.append(c - 0x60) else sb.append(c)
        }
        return sb.toString()
    }

    private fun applyFilter() {
        val raw = searchBar.text?.toString()?.trim() ?: ""
        shown.clear()
        if (raw.isEmpty()) {
            shown.addAll(apps)
        } else {
            val q = toHiragana(raw.lowercase())
            val extra = readingMap.entries
                .filter { it.key.contains(q) || q.contains(it.key) }
                .map { it.value }
            shown.addAll(apps.filter { app ->
                val name = displayName(app).lowercase()
                val nameHira = toHiragana(name)
                nameHira.contains(q) || extra.any { name.contains(it) }
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

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    // 通知ドットを重ねる
    private fun withBadge(base: Drawable): Drawable {
        val size = dp(56)
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        base.setBounds(0, 0, size, size)
        base.draw(canvas)
        drawDot(canvas, size)
        return BitmapDrawable(resources, bmp)
    }

    // 統一デザインアイコン（□枠白・中黒・角丸6、案Yなら頭文字）を作る
    private fun designIcon(app: AppEntry, withNotif: Boolean): Drawable {
        val size = dp(56)
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val inset = dp(2).toFloat()
        val radius = dp(6).toFloat()
        // 中：黒
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
        canvas.drawRoundRect(inset, inset, size - inset, size - inset, radius, radius, fill)
        // 枠：白（細め）
        val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = Color.WHITE
            strokeWidth = dp(2).toFloat()
        }
        canvas.drawRoundRect(inset, inset, size - inset, size - inset, radius, radius, border)
        // 案Y：頭文字（システムフォント）
        if (showInitial) {
            val ch = firstChar(displayName(app))
            if (ch.isNotEmpty()) {
                val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.WHITE
                    textSize = size * 0.42f
                    textAlign = Paint.Align.CENTER
                    typeface = Typeface.DEFAULT   // システムフォント
                }
                val bounds = Rect()
                tp.getTextBounds(ch, 0, ch.length, bounds)
                val cx = size / 2f
                val cy = size / 2f - (tp.descent() + tp.ascent()) / 2f
                canvas.drawText(ch, cx, cy, tp)
            }
        }
        if (withNotif) drawDot(canvas, size)
        return BitmapDrawable(resources, bmp)
    }

    // 表示名の先頭1文字を取り出す
    private fun firstChar(name: String): String {
        val t = name.trim()
        return if (t.isEmpty()) "" else t.substring(0, 1).uppercase()
    }

    // 右上のオレンジ通知ドット
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
                setPadding(dp(4), dp(8), dp(4), dp(8))
            }
            val hasNotif = NotificationService.activePackages.contains(app.packageName)
            val iconDrawable = if (useDesignIcon) {
                designIcon(app, hasNotif)                 // 統一デザイン
            } else {
                if (hasNotif) withBadge(app.icon) else app.icon   // 元アイコン
            }
            val icon = ImageView(this@MainActivity).apply {
                setImageDrawable(iconDrawable)
                layoutParams = LinearLayout.LayoutParams(dp(56), dp(56))
            }
            cell.addView(icon)
            if (showLabels) {
                val label = TextView(this@MainActivity).apply {
                    text = displayName(app)
                    setTextColor(Color.WHITE)
                    textSize = 12f
                    gravity = Gravity.CENTER
                    maxLines = 1
                    setPadding(0, dp(4), 0, 0)
                }
                cell.addView(label)
            }
            return cell
        }
    }
}