package com.kooky.launcher

import android.graphics.Color

// 日本の伝統色（縁アプリと共通）。ここにある色以外は使わない
object Wa {
    val SUMI = Color.parseColor("#313131")        // 墨：ダークの背景
    val SHIRONERI = Color.parseColor("#F3F3F2")   // 白練：ライトの背景
    val AI = Color.parseColor("#264348")          // 藍色：ライトの文字
    val TORINOKO = Color.parseColor("#E2BE9F")    // 鳥の子色：文字（主）
    val KAMENOZOKI = Color.parseColor("#C6C2B6")  // 甕覗
    val KAMENOZOKI70 = Color.parseColor("#B3C6C2B6") // 甕覗 70%：文字（補足）
    val RIKYU = Color.parseColor("#656255")       // 利休鼠：罫線・枠線
    val BENIHI = Color.parseColor("#F35336")      // 紅緋：見出し・強調
    val WAKATAKE = Color.parseColor("#6B9362")    // 若竹色：状態（稼働中・充電中）
    val KARAKURENAI = Color.parseColor("#C91F37") // 韓紅花：注意（残りわずか）
    val UKON = Color.parseColor("#E69B3A")        // 鬱金色：雷

    // 差し色（強調の色）。設定の「差し色」で18色から選べる。初期値は紅緋
    const val KEY_ACCENT = "accent_color"   // 保存値：Wa.PICK の番号 0〜17
    const val DEFAULT_ACCENT = 3            // 紅緋
    var accent: Int = BENIHI
        private set
    var accentIndex: Int = DEFAULT_ACCENT
        private set

    fun setAccent(i: Int) {
        accentIndex = i.coerceIn(0, PICK.size - 1)
        accent = PICK[accentIndex]
    }

    // 保存してある差し色を読む（壁紙の更新など、画面がないときにも使う）
    fun loadAccent(ctx: android.content.Context) {
        setAccent(ctx.getSharedPreferences("usage", android.content.Context.MODE_PRIVATE)
            .getInt(KEY_ACCENT, DEFAULT_ACCENT))
    }

    // 背景：ダークは墨、ライトは白練。文字：ダークは鳥の子、ライトは藍。補足・線は利休鼠
    fun bg(light: Boolean) = if (light) SHIRONERI else SUMI
    fun ink(light: Boolean) = if (light) AI else TORINOKO
    fun sub(light: Boolean) = if (light) RIKYU else KAMENOZOKI70
    fun line(light: Boolean) = RIKYU

    // 色を選ぶ設定の18色（6色×3行）
    val PICK_NAMES = arrayOf(
        "一斤染", "鴇色", "韓紅花", "紅緋", "柿渋色", "鬱金色",
        "玉子色", "鳥の子色", "鶸色", "萌黄", "松葉色", "若竹色",
        "新橋色", "甕覗", "藍色", "利休鼠", "紫苑", "桔梗色")
    val PICK = intArrayOf(
        Color.parseColor("#F08F90"), Color.parseColor("#F3A696"), Color.parseColor("#C91F37"),
        Color.parseColor("#F35336"), Color.parseColor("#934337"), Color.parseColor("#E69B3A"),
        Color.parseColor("#FFA631"), Color.parseColor("#E2BE9F"), Color.parseColor("#BDA928"),
        Color.parseColor("#5B8930"), Color.parseColor("#454D32"), Color.parseColor("#6B9362"),
        Color.parseColor("#006C7F"), Color.parseColor("#C6C2B6"), Color.parseColor("#264348"),
        Color.parseColor("#656255"), Color.parseColor("#976E9A"), Color.parseColor("#5D3F6A"))
}
