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
    private val orange get() = Wa.accent   // 差し色（設定で選んだ色。初期値は紅緋）
    private val ink get() = Wa.ink(lightMode)
    private val sub get() = Wa.sub(lightMode)
    private val trackColor get() = Wa.line(lightMode)

    private val d = resources.displayMetrics.density
    private fun dp(v: Int) = (v * d).toInt()

    private val handler = Handler(Looper.getMainLooper())
    private var msm: MediaSessionManager? = null
    private var controller: MediaController? = null
    private var running = false
    var active = false
        private set

    // ===== 見た目 =====
    private val appText = TextView(context).apply { textSize = 12f; setTypeface(typeface, Typeface.BOLD); setTextColor(orange) }
    private val titleText = TextView(context).apply { textSize = 12f; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
    private val posText = TextView(context).apply { textSize = 10f; fontFeatureSettings = "tnum" }
    private val lenText = TextView(context).apply { textSize = 10f; fontFeatureSettings = "tnum" }
    private val track = FrameLayout(context)
    private val fill = View(context)
    private val rw = MediaIconView(context, MediaIconView.Kind.REWIND)
    private val pp = MediaIconView(context, MediaIconView.Kind.PLAY_PAUSE)
    private val ff = MediaIconView(context, MediaIconView.Kind.FORWARD)
    private val prev = MediaIconView(context, MediaIconView.Kind.PREV)
    private val next = MediaIconView(context, MediaIconView.Kind.NEXT)
    private val pill = TextView(context)
    // 音に合わせて動くイコライザー（再生バーの左のすき間）
    val eq = EqualizerView(context)

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
        row2.addView(track, LinearLayout.LayoutParams(0, dp(1), 1f).apply { leftMargin = dp(8); rightMargin = dp(8) })
        row2.addView(lenText)
        col.addView(row2, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) })

        val row3 = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        fun btn(v: MediaIconView, sizeDp: Int) = v.apply {
            iconPx = dp(sizeDp).toFloat()
            isClickable = true
        }
        // 右手で押しやすいように、ボタンは右寄せ（左に余白）
        // 前へ ｜ 10秒戻る ｜ 再生/停止 ｜ 10秒進む ｜ 次へ
        val eqBox = FrameLayout(context).apply { setPadding(dp(4), 0, 0, 0) }
        eqBox.addView(eq, LayoutParams(dp(31), dp(24), Gravity.CENTER_VERTICAL or Gravity.START))
        row3.addView(eqBox, LinearLayout.LayoutParams(0, dp(30), 1.4f))
        row3.addView(btn(prev, 22), LinearLayout.LayoutParams(0, dp(44), 0.9f))
        row3.addView(btn(rw, 26), LinearLayout.LayoutParams(0, dp(44), 1f))
        row3.addView(btn(pp, 34), LinearLayout.LayoutParams(0, dp(44), 1f))
        row3.addView(btn(ff, 26), LinearLayout.LayoutParams(0, dp(44), 1f))
        row3.addView(btn(next, 22), LinearLayout.LayoutParams(0, dp(44), 0.9f))
        col.addView(row3)

        addView(col, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_VERTICAL))

        pill.apply {
            textSize = 12f; setTypeface(typeface, Typeface.BOLD); setTextColor(Wa.bg(lightMode))
            setPadding(dp(10), dp(4), dp(10), dp(4)); visibility = GONE
            background = GradientDrawable().apply { cornerRadius = dp(20).toFloat(); setColor(orange) }
        }
        addView(pill, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER_HORIZONTAL or Gravity.TOP))

        rw.setOnClickListener { seekBy(-seekSec) }
        prev.setOnClickListener { skip(false) }
        next.setOnClickListener { skip(true) }
        ff.setOnClickListener { seekBy(seekSec) }
        pp.setOnClickListener { togglePlay() }
        restyle()
    }

    // 色を塗り直す（テーマや差し色が変わったとき）
    fun restyle() {
        appText.setTextColor(orange)
        pill.background = GradientDrawable().apply { cornerRadius = dp(20).toFloat(); setColor(orange) }
        pill.setTextColor(Wa.bg(lightMode))
        titleText.setTextColor(ink)
        posText.setTextColor(sub); lenText.setTextColor(sub)
        rw.color = ink; ff.color = ink; pp.color = orange
        prev.color = ink; next.color = ink
        // 再生位置の線：通信量のゲージと同じ 1dp の一本線（角丸なし）
        track.background = android.graphics.drawable.ColorDrawable(trackColor)
        fill.background = android.graphics.drawable.ColorDrawable(orange)
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
        eq.start()
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
        eq.stop()
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
        if (c == null || !alive) { eq.playing = false; setActive(false); return }
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
        pp.playing = st.state == PlaybackState.STATE_PLAYING
        val nowPlaying = st.state == PlaybackState.STATE_PLAYING
        if (eq.playing != nowPlaying) eq.playing = nowPlaying
        // 前へ／次へに対応していないときは薄くする
        prev.alpha = if (st.actions and PlaybackState.ACTION_SKIP_TO_PREVIOUS != 0L) 1f else 0.3f
        next.alpha = if (st.actions and PlaybackState.ACTION_SKIP_TO_NEXT != 0L) 1f else 0.3f
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

    // 前の曲・動画／次の曲・動画
    private fun skip(forward: Boolean) {
        val c = controller ?: return
        val st = c.playbackState ?: return
        val action = if (forward) PlaybackState.ACTION_SKIP_TO_NEXT else PlaybackState.ACTION_SKIP_TO_PREVIOUS
        if (st.actions and action == 0L) { flash(if (forward) "次へは使えません" else "前へは使えません"); return }
        try {
            if (forward) c.transportControls.skipToNext() else c.transportControls.skipToPrevious()
            flash(if (forward) "次へ" else "前へ")
        } catch (e: Exception) {}
        handler.postDelayed({ refresh() }, 500)
    }

    private fun togglePlay() {
        val c = controller ?: return
        try {
            if (c.playbackState?.state == PlaybackState.STATE_PLAYING) c.transportControls.pause()
            else c.transportControls.play()
        } catch (e: Exception) {}
        handler.postDelayed({ refresh() }, 300)
    }

}
