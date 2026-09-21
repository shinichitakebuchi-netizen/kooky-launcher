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
import android.widget.BaseAdapter
import android.widget.EditText
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

    private val gridColumns = 4
    // 片手操作用：バーの上に空ける余白の高さ（この数字を変えれば調整できる。0にすればバーが最上部）
    private val topGapDp = 200

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("usage", Context.MODE_PRIVATE)

        loadApps()

        // オレンジの検索バー本体（ヒント文字なし・入力欄）
        searchBar = EditText(this).apply {
            setSingleLine(true)
            setTextColor(Color.parseColor("#101014"))
            background = null
            val p = dp(10)
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

        // 右端に置く検索アイコン
        val searchIcon = ImageView(this).apply {
            setImageResource(android.R.drawable.ic_menu_search)
            setColorFilter(Color.parseColor("#101014"))
            val p = dp(8)
            setPadding(p, p, p, p)
        }

        // 入力欄＋アイコンを横並びにして、オレンジ丸角の背景をつける
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

        // バーの上の空き余白（片手で届く位置までバーとアプリを押し下げる）
        val topGap = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(topGapDp))
        }

        // アプリ一覧グリッド
        val grid = GridView(this).apply {
            numColumns = gridColumns
            val pad = dp(12)
            setPadding(pad, dp(4), pad, dp(24))
            clipToPadding = false
            verticalSpacing = dp(16)
            adapter = appAdapter
            setOnItemClickListener { _, _, position, _ ->
                launchApp(shown[position].packageName)
            }
        }

        // 縦に並べる：空き余白 → 検索バー → アプリ
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#101014"))
            val m = dp(12)
            addView(topGap)
            addView(searchBarRow, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                setMargins(m, 0, m, dp(8))
            })
            addView(grid, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }

        setContentView(content)

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
