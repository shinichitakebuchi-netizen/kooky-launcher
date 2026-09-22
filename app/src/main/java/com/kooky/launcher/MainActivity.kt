package com.kooky.launcher

import android.app.Activity
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ResolveInfo
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.AlarmClock
import android.provider.CalendarContract
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

    private val apps = mutableListOf<AppEntry>()   // 全アプリ（頻度順）
    private val shown = mutableListOf<AppEntry>()   // 実際に表示する分（検索で絞られる）
    private lateinit var prefs: SharedPreferences
    private val appAdapter = AppAdapter()

    private lateinit var searchBar: EditText
    private lateinit var clockText: TextView
    private lateinit var dateText: TextView

    private val gridColumns = 4
    private val topGapDp = 290
    private val appsTopPadDp = 4

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

        loadApps()

        clockText = TextView(this).apply {
            textSize = 46f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setTypeface(typeface, Typeface.BOLD)
            setOnClickListener { openClock() }
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
    }

    override fun onResume() {
        super.onResume()
        sortApps()
        applyFilter()
        resetSearch()
        timeHandler.removeCallbacks(tick)
        timeHandler.post(tick)
    }

    override fun onPause() {
        super.onPause()
        timeHandler.removeCallbacks(tick)
    }

    // 時計アプリを開く（3段構え：直接起動→時計画面指定→アラーム画面）
    private fun openClock() {
        // ① 端末の「時計」アプリを直接起動できるなら起動
        val clockPackages = listOf(
            "com.google.android.deskclock",   // Google/Pixel系
            "com.sec.android.app.clockpackage" // Samsung Galaxy系
        )
        for (pkg in clockPackages) {
            val intent = packageManager.getLaunchIntentForPackage(pkg)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
                return
            }
        }
        // ② それ以外の端末：時計カテゴリのアプリを探して起動
        try {
            startActivity(Intent(Intent.ACTION_MAIN)
                .addCategory("android.intent.category.APP_DESKCLOCK")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        } catch (e: Exception) {}
        // ③ 最後の手段：アラーム画面
        try {
            startActivity(Intent(AlarmClock.ACTION_SHOW_ALARMS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        } catch (e: Exception) {}
        Toast.makeText(this, "時計アプリが見つかりませんでした", Toast.LENGTH_SHORT).show()
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
                .thenBy { it.label.lowercase() }
        )
    }

    private fun applyFilter() {
        val q = searchBar.text?.toString()?.trim() ?: ""
        shown.clear()
        if (q.isEmpty()) {
            shown.addAll(apps)
        } else {
            shown.addAll(apps.filter { it.label.contains(q, ignoreCase = true) })
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
            val icon = ImageView(this@MainActivity).apply {
                setImageDrawable(app.icon)
                layoutParams = LinearLayout.LayoutParams(dp(56), dp(56))
            }
            val label = TextView(this@MainActivity).apply {
                text = app.label
                setTextColor(Color.WHITE)
                textSize = 12f
                gravity = Gravity.CENTER
                maxLines = 1
                setPadding(0, dp(4), 0, 0)
            }
            cell.addView(icon)
            cell.addView(label)
            return cell
        }
    }
}