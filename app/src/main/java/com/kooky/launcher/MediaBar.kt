package com.kooky.launcher

import android.content.ComponentName
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

// 再生中の動画・音楽をホームから操作するバー（再生コントローラーがある間だけ表示）
class MediaBar(context: Context) : FrameLayout(context) {

    var lightMode: Boolean = false
        set(v) { field = v; restyle() }
    // 表示/非表示が変わったら知らせる（掲示板と入れ替えるため）
    var onActiveChanged: ((Boolean) -> Unit)? = null

    private val seekSec = 10
    private val speeds = floatArrayOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)
    private val orange = Color.parseColor("#FF8C00")
    private val ink get() = if (lightMode) Color.parseColor("#1C1C1E") else Color.WHITE
    private val sub get() = if (lightMode) Color.parseColor("#6E6E73") else Color.parseColor("#9A9AA2")
    private val trackColor get() = if (lightMode) Color.parseColor("#1A000000") else Color.parseColor("#29FFFFFF")

    private val d = resources.displayMetrics.density
    private fun dp(v: Int) = (v * d).toInt()

    private val handler = Handler(Looper.getMainLooper())
    private var msm: MediaSessionManager? = null
    private var controller: MediaController? = null
    private var running = false
    var active = false
        private set

    // ===== 見た目 =====
    private val appText = TextView(context).apply { textSize = 11f; setTypeface(typeface, Typeface.BOLD); setTextColor(orange) }
    private val titleText = TextView(context).apply { textSize = 11f; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
    private val posText = TextView(context).apply { textSize = 10f; fontFeatureSettings = "tnum" }
    private val lenText = TextView(context).apply { textSize = 10f; fontFeatureSettings = "tnum" }
    private val track = FrameLayout(context)
    private val fill = View(context)
    private val rw = TextView(context)
    private val pp = TextView(context)
    private val ff = TextView(context)
    private val sp = TextView(context)
    private val pill = TextView(context)

    init {
        visibility = GONE
        val col = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

        val row1 = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        row1.addView(appText)
        row1.addView(titleText, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(6) })
        col.addView(row1)

        val row2 = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        track.addView(fill, LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT))
        row2.addView(posText)
        row2.addView(track, LinearLayout.LayoutParams(0, dp(3), 1f).apply { leftMargin = dp(8); rightMargin = dp(8) })
        row2.addView(lenText)
        col.addView(row2, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) })

        val row3 = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        fun btn(v: TextView, t: String, size: Float) = v.apply {
            text = t; textSize = size; gravity = Gravity.CENTER; setTypeface(typeface, Typeface.BOLD)
            isClickable = true; setPadding(0, dp(6), 0, dp(6))
        }
        row3.addView(btn(rw, "« ${seekSec}秒", 13f), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row3.addView(btn(pp, "❚❚", 18f), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row3.addView(btn(ff, "${seekSec}秒 »", 13f), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        sp.apply {
            textSize = 12f; setTypeface(typeface, Typeface.BOLD); setTextColor(orange)
            setPadding(dp(8), dp(2), dp(8), dp(2)); isClickable = true
            background = GradientDrawable().apply { cornerRadius = dp(6).toFloat(); setStroke(dp(2), orange) }
        }
        row3.addView(sp)
        col.addView(row3)

        addView(col, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        pill.apply {
            textSize = 12f; setTypeface(typeface, Typeface.BOLD); setTextColor(Color.BLACK)
            setPadding(dp(10), dp(4), dp(10), dp(4)); visibility = GONE
            background = GradientDrawable().apply { cornerRadius = dp(20).toFloat(); setColor(orange) }
        }
        addView(pill, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER_HORIZONTAL or Gravity.TOP))

        rw.setOnClickListener { seekBy(-seekSec) }
        ff.setOnClickListener { seekBy(seekSec) }
        pp.setOnClickListener { togglePlay() }
        sp.setOnClickListener { nextSpeed() }
        restyle()
    }

    private fun restyle() {
        titleText.setTextColor(ink)
        posText.setTextColor(sub); lenText.setTextColor(sub)
        rw.setTextColor(ink); ff.setTextColor(ink); pp.setTextColor(orange)
        track.background = GradientDrawable().apply { cornerRadius = dp(2).toFloat(); setColor(trackColor) }
        fill.background = GradientDrawable().apply { cornerRadius = dp(2).toFloat(); setColor(orange) }
    }

    // ===== 再生セッションの監視 =====
    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { list ->
        handler.post { pick(list ?: emptyList()) }
    }

    private val ctrlCallback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) { handler.post { refresh() } }
        override fun onMetadataChanged(metadata: MediaMetadata?) { handler.post { refresh() } }
        override fun onSessionDestroyed() { handler.post { reload() } }
    }

    private val ticker = object : Runnable {
        override fun run() {
            refresh()
            if (running) handler.postDelayed(this, 500)
        }
    }

    fun start() {
        if (running) return
        running = true
        try {
            val m = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
            msm = m
            val cn = ComponentName(context, NotificationService::class.java)
            m.addOnActiveSessionsChangedListener(sessionsListener, cn, handler)
        } catch (e: Exception) { msm = null }
        reload()
        handler.post(ticker)
    }

    fun stop() {
        running = false
        handler.removeCallbacks(ticker)
        try { msm?.removeOnActiveSessionsChangedListener(sessionsListener) } catch (e: Exception) {}
        try { controller?.unregisterCallback(ctrlCallback) } catch (e: Exception) {}
        controller = null
    }

    private fun reload() {
        val list = try {
            msm?.getActiveSessions(ComponentName(context, NotificationService::class.java)) ?: emptyList()
        } catch (e: Exception) { emptyList<MediaController>() }
        pick(list)
    }

    // 再生中のものを優先、なければ一時停止中のもの
    private fun pick(list: List<MediaController>) {
        val usable = list.filter { it.packageName != context.packageName && it.playbackState != null }
        val next = usable.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: usable.firstOrNull {
                val s = it.playbackState?.state
                s == PlaybackState.STATE_PAUSED || s == PlaybackState.STATE_BUFFERING
            }
        if (next?.sessionToken != controller?.sessionToken) {
            try { controller?.unregisterCallback(ctrlCallback) } catch (e: Exception) {}
            controller = next
            try { next?.registerCallback(ctrlCallback, handler) } catch (e: Exception) {}
        }
        refresh()
    }

    private fun setActive(a: Boolean) {
        visibility = if (a) VISIBLE else GONE
        if (a != active) { active = a; onActiveChanged?.invoke(a) }
    }

    private fun currentPos(st: PlaybackState): Long {
        var p = st.position
        if (st.state == PlaybackState.STATE_PLAYING && st.lastPositionUpdateTime > 0) {
            p += ((SystemClock.elapsedRealtime() - st.lastPositionUpdateTime) * st.playbackSpeed).toLong()
        }
        return p.coerceAtLeast(0L)
    }

    private fun fmt(ms: Long): String {
        val s = ms / 1000
        val h = s / 3600; val m = (s % 3600) / 60; val sec = s % 60
        return if (h > 0) String.format("%d:%02d:%02d", h, m, sec) else String.format("%d:%02d", m, sec)
    }

    private fun refresh() {
        val c = controller
        val st = c?.playbackState
        val alive = st != null && st.state != PlaybackState.STATE_NONE &&
            st.state != PlaybackState.STATE_STOPPED && st.state != PlaybackState.STATE_ERROR
        if (c == null || !alive) { setActive(false); return }
        setActive(true)

        val md = c.metadata
        appText.text = try {
            val pm = context.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(c.packageName, 0)).toString()
        } catch (e: Exception) { c.packageName }
        titleText.text = md?.getString(MediaMetadata.METADATA_KEY_TITLE)
            ?: md?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE) ?: ""

        val len = md?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
        val pos = currentPos(st!!)
        posText.text = fmt(pos)
        lenText.text = if (len > 0) fmt(len) else "--:--"
        val ratio = if (len > 0) (pos.toFloat() / len).coerceIn(0f, 1f) else 0f
        track.post {
            fill.layoutParams = (fill.layoutParams as LayoutParams).apply { width = (track.width * ratio).toInt() }
            fill.requestLayout()
        }
        pp.text = if (st.state == PlaybackState.STATE_PLAYING) "❚❚" else "▶"
        val spd = if (st.playbackSpeed > 0f) st.playbackSpeed else 1f
        sp.text = trimSpeed(spd) + "x"
    }

    private fun trimSpeed(v: Float): String {
        val s = String.format("%.2f", v).trimEnd('0').trimEnd('.')
        return if (s.contains('.')) s else "$s.0"
    }

    // ===== 操作 =====
    private fun flash(msg: String) {
        pill.text = msg
        pill.visibility = VISIBLE
        handler.removeCallbacks(hidePill)
        handler.postDelayed(hidePill, 1200)
    }
    private val hidePill = Runnable { pill.visibility = GONE }

    private fun seekBy(sec: Int) {
        val c = controller ?: return
        val st = c.playbackState ?: return
        val len = c.metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
        var target = currentPos(st) + sec * 1000L
        if (target < 0) target = 0
        if (len > 0 && target > len) target = len
        try {
            if (st.actions and PlaybackState.ACTION_SEEK_TO != 0L) c.transportControls.seekTo(target)
            else if (sec < 0) c.transportControls.rewind() else c.transportControls.fastForward()
            flash(if (sec < 0) "${-sec}秒 戻った" else "${sec}秒 進んだ")
        } catch (e: Exception) { flash("このアプリは操作できません") }
        handler.postDelayed({ refresh() }, 300)
    }

    private fun togglePlay() {
        val c = controller ?: return
        try {
            if (c.playbackState?.state == PlaybackState.STATE_PLAYING) c.transportControls.pause()
            else c.transportControls.play()
        } catch (e: Exception) {}
        handler.postDelayed({ refresh() }, 300)
    }

    private fun nextSpeed() {
        val c = controller ?: return
        if (android.os.Build.VERSION.SDK_INT < 29) { flash("この端末は速度変更に対応していません"); return }
        val cur = c.playbackState?.playbackSpeed ?: 1f
        var idx = speeds.indexOfFirst { kotlin.math.abs(it - cur) < 0.01f }
        idx = if (idx < 0) 2 else (idx + 1) % speeds.size
        val target = speeds[idx]
        try { c.transportControls.setPlaybackSpeed(target) } catch (e: Exception) {}
        flash("速度 ${trimSpeed(target)}x")
        // アプリが受け付けなかったら知らせる
        handler.postDelayed({
            val now = controller?.playbackState?.playbackSpeed ?: 1f
            if (kotlin.math.abs(now - target) > 0.01f) flash("このアプリは速度変更に対応していません")
            refresh()
        }, 900)
    }
}
