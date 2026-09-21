package com.kooky.launcher

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ResolveInfo
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {

    data class AppEntry(val label: String, val packageName: String, val icon: Drawable)

    private val apps = mutableListOf<AppEntry>()   // 全アプリ（頻度順）
    private val shown = mutableListOf<AppEntry>()   // 実際に表示する分（検索で絞られる）
    private lateinit var prefs: SharedPreferences
    private val appAdapter = AppAdapter()

    private lateinit var searchBar: EditText
    private lateinit var searchButton: ImageView

    // 片手操作用：上から何行ぶんを空けるか（ここを変えれば調整できる）
    private val gridColumns = 4
    private val topSpacerRows = 3
    private val topSpacerCells get() = gridColumns * topSpacerRows

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("usage", Context.MODE_PRIVATE)

        loadApps()

        // 上部の常時表示・検索バー
        searchBar = EditText(this).apply {
            hint = "アプリ検索"
            setSingleLine(true)
            setTextColor(Color.WHITE)
            setHintTextColor(Color.parseColor("#888888"))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(12).toFloat()
                setColor(Color.parseColor("#1C1C22"))
            }
            val p = dp(12)
            setPadding(p, p, p, p)
            addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) { applyFilter() }
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            })
            setOnEditorActionListener { _, _, _ ->
                if (shown.isNotEmpty()) launchApp(shown[0].packageName)
                true
            }
        }

        // アプリ一覧グリッド
        val grid = GridView(this).apply {
            numColumns = gridColumns
            val pad = dp(12)
            setPadding(pad, dp(12), pad, dp(96)) // 下は検索ボタンに隠れないよう広め
            clipToPadding = false
            verticalSpacing = dp(16)
            adapter = appAdapter
            setOnItemClickListener { _, _, position, _ ->
                if (position < topSpacerCells) return@setOnItemClickListener // 空マスは無視
                val app = shown[position - topSpacerCells]
                launchApp(app.packageName)
            }
        }

        // 検索バー＋グリッドを縦に並べる
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#101014"))
            val m = dp(12)
            addView(searchBar, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                setMargins(m, m, m, dp(4))
            })
            addView(grid, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }

        // 右下の丸い検索ボタン（元サイズ・オレンジ）
        searchButton = ImageView(this).apply {
            setImageResource(android.R.drawable.ic_menu_search)
            setColorFilter(Color.WHITE)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#FF8C00"))
            }
            val p = dp(14)
            setPadding(p, p, p, p)
            setOnClickListener {
                searchBar.requestFocus()
                val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                imm.showSoftInput(searchBar, InputMethodManager.SHOW_IMPLICIT)
            }
        }

        val root = FrameLayout(this).apply {
            addView(content, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(searchButton, FrameLayout.LayoutParams(dp(56), dp(56)).apply {
                gravity = Gravity.BOTTOM or Gravity.END
                val m = dp(24)
                setMargins(m, m, m, m)
            })
        }
        setContentView(root)

        applyFilter() // 初期表示（全アプリ）
    }

    override fun onResume() {
        super.onResume()
        sortApps()
        applyFilter()
    }

    override fun onBackPressed() {
        if (!searchBar.text.isNullOrEmpty()) {
            searchBar.setText("") // 検索中なら戻るでクリア
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
            if (pkg == packageName) continue // 自分自身は隠す
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
        // 使用回数が多い順 → 同数なら名前順
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
        searchBar.setText("") // 起動したら検索をリセット
        packageManager.getLaunchIntentForPackage(pkg)?.let { startActivity(it) }
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    inner class AppAdapter : BaseAdapter() {
        // 空マス（上部スペーサー）＋実際のアプリ数
        override fun getCount(): Int = topSpacerCells + shown.size
        override fun getItem(position: Int): Any = position
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val isSpacer = position < topSpacerCells
            val cell = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(4), dp(8), dp(4), dp(8))
            }
            val icon = ImageView(this@MainActivity).apply {
                layoutParams = LinearLayout.LayoutParams(dp(56), dp(56))
            }
            val label = TextView(this@MainActivity).apply {
                setTextColor(Color.WHITE)
                textSize = 12f
                gravity = Gravity.CENTER
                maxLines = 1
                setPadding(0, dp(4), 0, 0)
            }
            if (!isSpacer) {
                val app = shown[position - topSpacerCells]
                icon.setImageDrawable(app.icon)
                label.text = app.label
            }
            cell.addView(icon)
            cell.addView(label)
            return cell
        }
    }
}
