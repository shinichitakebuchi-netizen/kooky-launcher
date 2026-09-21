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

        //
