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
    private var searching = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("usage", Context.MODE_PRIVATE)

        loadApps()

        // 検索バー（最初は非表示）
        searchBar = EditText(this).apply {
            hint = "アプリ検索"
            setSingleLine(true)
            setTextColor(Color.WHITE)
            setHintTextColor(Color.parseColor("#888888"))
            setBackgroundColor(Color.parseColor("#1C1C22"))
            val p = dp(12)
            setPadding(p, p, p, p)
            visibility = View.GONE
            addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) { filter(s?.toString() ?: "") }
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            })
            setOnEditorActionListener { _, _, _ ->
                if (shown.isNotEmpty()) { launchApp(shown[0].packageName); exitSearch() }
                true
            }
        }

        // アプリ一覧グリッド
        val grid = GridView(this).apply {
            numColumns = 4
            val pad = dp(12)
            setPadding(pad, dp(40), pad, dp(96)) // 下は検索ボタンに隠れないよう広め
            clipToPadding = false
            verticalSpacing = dp(16)
            adapter = appAdapter
            setOnItemClickListener { _, _, position, _ ->
                launchApp(shown[position].packageName)
                if (searching) exitSearch()
            }
        }

        // 検索バー＋グリッドを縦に並べる
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#101014"))
            addView(searchBar, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(grid, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }

        // 右下の丸い検索ボタン
        searchButton = ImageView(this).apply {
            setImageResource(android.R.drawable.ic_menu_search)
            setColorFilter(Color.WHITE)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#3A3AFF"))
            }
