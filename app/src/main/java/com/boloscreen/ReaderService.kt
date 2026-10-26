package com.boloscreen

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.*
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import java.io.File
import java.util.Locale
import kotlin.math.abs

class ReaderService : AccessibilityService(), TextToSpeech.OnInitListener {
    companion object { var inst: ReaderService? = null }
    class Item(val text: String, val off: Int, val r: Rect, val node: AccessibilityNodeInfo)

    private var tts: TextToSpeech? = null
    private var ttsMsg: TextToSpeech? = null
    private var ready = false
    private var msgReady = false
    private lateinit var wm: WindowManager
    private lateinit var lp: WindowManager.LayoutParams
    private var btn: FrameLayout? = null
    private var img: ImageView? = null
    private var imgRadius = 0f
    private var strip: View? = null
    private var stripLeft = true
    private var layer: Layer? = null
    private var panel: View? = null
    private var playBtn: ImageView? = null
    private var items = listOf<Item>()
    private var queue = listOf<Int>()
    private var qi = 0
    private var playing = false
    private var paused = false
    private var rootNull = false
    private var hasPhoto = false
    private var tucked = false
    private var faded = false
    private var hideLeft = true
    private val main = Handler(Looper.getMainLooper())
    private val fadeRunner = Runnable { fadeNow() }
    private val idleRunner = Runnable { if (!playing && panel != null) collapse() }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun mm(v: Float) = android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_MM, v, resources.displayMetrics).toInt()
    private fun screenW() = resources.displayMetrics.widthPixels
    private fun alphaUser() = Prefs.i(this, "alpha", 100) / 100f
    private fun dockX() = if (hideLeft) 0 else screenW() - lp.width
    private fun upd() { btn?.let { try { wm.updateViewLayout(it, lp) } catch (_: Exception) {} } }
    private fun clampY() { lp.y = lp.y.coerceIn(dp(24), maxOf(dp(24), resources.displayMetrics.heightPixels - lp.height - dp(48))) }

    override fun onServiceConnected() {
        inst = this
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        tts = TextToSpeech(this, this)
        ttsMsg = TextToSpeech(this) { st ->
            if (st == TextToSpeech.SUCCESS) { ttsMsg?.language = Locale("en", "IN"); msgReady = true }
        }
        addButton()
    }

    override fun onInit(status: Int) {
        ready = status == TextToSpeech.SUCCESS
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) {
                val p = id?.drop(1)?.toIntOrNull() ?: return
                main.post { qi = p; layer?.hi = items.getOrNull(queue.getOrNull(p) ?: -1)?.r; layer?.invalidate() }
            }
            override fun onRangeStart(id: String?, start: Int, end: Int, frame: Int) {
                val p = id?.drop(1)?.toIntOrNull() ?: return
                main.post { wordBox(p, start, end) }
            }
            override fun onDone(id: String?) {
                val p = id?.drop(1)?.toIntOrNull() ?: return
                if (p == queue.lastIndex) main.post { finishReading() }
            }
            @Deprecated("Deprecated in Java")
            override fun onError(id: String?) {}
        })
        applyVoice()
    }

    override fun onAccessibilityEvent(e: AccessibilityEvent?) {
        if (e?.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED) adjustForIme()
    }
    override fun onInterrupt() { tts?.stop() }

    override fun onDestroy() {
        inst = null
        collapse()
        strip?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        btn?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        tts?.shutdown(); ttsMsg?.shutdown()
        super.onDestroy()
    }

    private fun adjustForIme() {
        if (panel != null) return
        val r = Rect()
        val ime = windows.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
        ime?.getBoundsInScreen(r)
        val saved = Prefs.i(this, "y", 400)
        val limit = if (ime != null) r.top - lp.height - dp(8) else saved
        val ny = minOf(saved, limit).coerceAtLeast(dp(24))
        if (ny != lp.y) { lp.y = ny; upd() }
    }

    fun applyVoice() {
        val t = tts ?: return
        if (!ready) return
        val loc = Locale.forLanguageTag(Prefs.s(this, "lang", "hi-IN"))
        t.language = loc
        val name = Prefs.s(this, "voice", "")
        t.voices?.firstOrNull { it.name == name && it.locale.language == loc.language }?.let { t.voice = it }
        t.setSpeechRate(Prefs.f(this, "rate", 1f))
        t.setPitch(Prefs.f(this, "pitch", 1f))
    }

    // ---------- floating button ----------
    private fun styleButton() {
        val b = btn ?: return
        val im = img ?: return
        val sz = if (tucked) dp(28) else dp(Prefs.i(this, "size", 64))
        val pad = (sz * 0.18f).toInt()
        val w = sz + 2 * pad
        lp.width = w; lp.height = w
        (b.background as? GradientDrawable)?.cornerRadius = w * 0.3f
        im.layoutParams = FrameLayout.LayoutParams(sz, sz, Gravity.CENTER)
        (im.background as? GradientDrawable)?.setColor(Prefs.bcolor(this))
        imgRadius = sz * 0.24f
        im.invalidateOutline()
        val pd = if (hasPhoto) 0 else (sz * 0.22f).toInt()
        im.setPadding(pd, pd, pd, pd)
        b.alpha = alphaUser() * (if (tucked) 0.7f else if (faded) 0.4f else 1f)
    }

    fun refreshButton() {
        val b = btn ?: return
        val im = img ?: return
        val f = File(filesDir, "btn.png")
        hasPhoto = f.exists()
        if (hasPhoto) { im.imageTintList = null; im.setImageBitmap(BitmapFactory.decodeFile(f.path)) }
        else { im.imageTintList = ColorStateList.valueOf(Prefs.btint(this)); im.setImageResource(R.drawable.ic_volume) }
        styleButton()
        lp.x = dockX()
        if (panel == null) b.visibility = if (Prefs.p(this).getBoolean("show", true)) View.VISIBLE else View.GONE
        upd()
        syncStrip()
        scheduleFade()
    }

    private fun syncStrip() {
        val want = tucked && panel == null && Prefs.p(this).getBoolean("show", true)
        if (!want) { strip?.let { try { wm.removeView(it) } catch (_: Exception) {} }; strip = null; return }
        if (strip != null && stripLeft == hideLeft) return
        strip?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        val v = View(this)
        v.background = GradientDrawable(
            if (hideLeft) GradientDrawable.Orientation.LEFT_RIGHT else GradientDrawable.Orientation.RIGHT_LEFT,
            intArrayOf(0x40000000, 0x00000000))
        val p = WindowManager.LayoutParams(
            mm(3f), WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = (if (hideLeft) Gravity.START else Gravity.END) or Gravity.TOP }
        wm.addView(v, p)
        strip = v; stripLeft = hideLeft
        btn?.let { try { wm.removeView(it); wm.addView(it, lp) } catch (_: Exception) {} }
    }

    private fun moveX(to: Int) {
        val b = btn ?: return
        android.animation.ValueAnimator.ofInt(lp.x, to).apply {
            duration = 180
            addUpdateListener { try { lp.x = it.animatedValue as Int; wm.updateViewLayout(b, lp) } catch (_: Exception) {} }
            start()
        }
    }

    private fun snapToEdge() {
        hideLeft = lp.x + lp.width / 2 < screenW() / 2
        clampY()
        moveX(dockX())
        Prefs.p(this).edit().putInt("x", dockX()).putInt("y", lp.y).putBoolean("tucked", false).apply()
        scheduleFade()
    }

    private fun tuck() {
        tucked = true; faded = false
        styleButton(); clampY(); lp.x = dockX(); upd()
        Prefs.p(this).edit().putInt("x", lp.x).putInt("y", lp.y).putBoolean("tucked", true).apply()
        syncStrip()
    }

    private fun untuck() {
        tucked = false
        syncStrip(); styleButton(); lp.x = dockX(); upd()
        Prefs.p(this).edit().putBoolean("tucked", false).apply()
    }

    private fun scheduleFade() {
        main.removeCallbacks(fadeRunner)
        if (Prefs.p(this).getBoolean("autohide", true) && panel == null && !tucked) main.postDelayed(fadeRunner, 4000)
    }

    private fun fadeNow() {
        if (panel != null || tucked) return
        faded = true; styleButton(); upd()
    }

    private fun addButton() {
        val im = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = GradientDrawable().apply { setColor(0xFF3F51B5.toInt()) }
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(v: View, o: Outline) { o.setRoundRect(0, 0, v.width, v.height, imgRadius) }
            }
            clipToOutline = true
        }
        val b = FrameLayout(this).apply {
            contentDescription = "Bolo Screen"
            background = GradientDrawable().apply { setColor(0xD9000000.toInt()); setStroke(dp(1), 0x33FFFFFF) }
            outlineProvider = ViewOutlineProvider.BACKGROUND
            clipToOutline = true
            addView(im, FrameLayout.LayoutParams(dp(64), dp(64), Gravity.CENTER))
        }
        lp = WindowManager.LayoutParams(
            0, 0, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START; x = Prefs.i(this@ReaderService, "x", 40); y = Prefs.i(this@ReaderService, "y", 400) }
        hideLeft = lp.x + dp(32) < screenW() / 2
        tucked = Prefs.p(this).getBoolean("tucked", false)
        var dx = 0f; var dy = 0f; var sx = 0; var sy = 0; var moved = false
        b.setOnTouchListener { v, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    main.removeCallbacks(fadeRunner)
                    if (tucked) untuck() else if (faded) { faded = false; styleButton(); upd() }
                    dx = e.rawX; dy = e.rawY; sx = lp.x; sy = lp.y; moved = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val mx = (e.rawX - dx).toInt(); val my = (e.rawY - dy).toInt()
                    if (abs(mx) > 12 || abs(my) > 12) moved = true
                    if (moved) { lp.x = (sx + mx).coerceIn(-lp.width, screenW()); lp.y = sy + my; wm.updateViewLayout(v, lp) }
                }
                MotionEvent.ACTION_UP -> {
                    if (moved) {
                        val cx = lp.x + lp.width / 2
                        if (cx < 0 || cx > screenW()) { hideLeft = cx < 0; tuck() } else snapToEdge()
                    } else expand()
                }
            }
            true
        }
        btn = b
        img = im
        wm.addView(b, lp)
        refreshButton()
    }

    // ---------- control bar + highlight ----------
    private fun expand() {
        if (panel != null) return
        main.removeCallbacks(fadeRunner)
        items = scan(); queue = listOf(); qi = 0
        val mp = WindowManager.LayoutParams.MATCH_PARENT
        val l = Layer(this)
        wm.addView(l, WindowManager.LayoutParams(
            mp, mp, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT))
        layer = l
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = GradientDrawable().apply { cornerRadius = dp(28).toFloat(); setColor(0xF014233A.toInt()) }
            setPadding(dp(6), dp(4), dp(6), dp(4))
        }
        fun key(res: Int, desc: String, color: Int = Color.WHITE, a: () -> Unit): ImageView {
            val v = ImageView(this).apply {
                setImageResource(res); imageTintList = ColorStateList.valueOf(color)
                setPadding(dp(12), dp(12), dp(12), dp(12)); contentDescription = desc
                isClickable = true
                setOnClickListener { main.removeCallbacks(idleRunner); a(); if (!playing) main.postDelayed(idleRunner, 8000) }
            }
            bar.addView(v, LinearLayout.LayoutParams(dp(48), dp(48)))
            return v
        }
        key(R.drawable.ic_settings, "Settings") { startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        key(R.drawable.ic_prev, "Pichla") { if (queue.isNotEmpty()) speakQueue(queue, maxOf(qi - 1, 0)) }
        playBtn = key(R.drawable.ic_play, "Play ya pause") { playPause() }
        key(R.drawable.ic_next, "Agla") { if (queue.isNotEmpty()) speakQueue(queue, minOf(qi + 1, queue.lastIndex)) }
        key(R.drawable.ic_stop, "Roko", 0xFFFF8A80.toInt()) { tts?.stop(); playing = false; paused = false; collapse() }
        key(R.drawable.ic_chevron_left, "Chhupao") { collapse() }
        val pl = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = lp.x.coerceIn(0, maxOf(0, screenW() - dp(300)))
            y = lp.y
        }
        wm.addView(bar, pl)
        panel = bar
        btn?.visibility = View.GONE
        syncStrip()
        updatePlay()
        main.postDelayed(idleRunner, 8000)
    }

    private fun collapse() {
        main.removeCallbacks(idleRunner)
        layer?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        panel?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        layer = null; panel = null; playBtn = null
        if (btn != null) refreshButton()
    }

    private fun finishReading() {
        playing = false; paused = false
        layer?.hi = null; layer?.invalidate()
        collapse()
    }

    private fun updatePlay() { playBtn?.setImageResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play) }

    private fun playPause() {
        if (playing) { tts?.stop(); playing = false; paused = true; updatePlay(); return }
        if (paused && queue.isNotEmpty()) { speakQueue(queue, qi); return }
        items = scan()
        layer?.hi = null
        if (items.isNotEmpty()) speakQueue(items.indices.toList(), 0) else say(rootNull)
    }

    private fun speakQueue(list: List<Int>, from: Int) {
        val t = tts ?: return
        if (list.isEmpty() || from !in list.indices) return
        main.removeCallbacks(idleRunner)
        queue = list; qi = from
        t.stop()
        for (p in from until list.size) t.speak(items[list[p]].text, if (p == from) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, "i$p")
        playing = true; paused = false
        updatePlay()
    }

    private fun say(blocked: Boolean) {
        tts?.stop()
        playing = false; paused = false
        layer?.hi = null; layer?.invalidate(); updatePlay()
        if (msgReady) ttsMsg?.speak(if (blocked) "This screen cannot be read." else "No text at this location.", TextToSpeech.QUEUE_FLUSH, null, "msg")
        main.removeCallbacks(idleRunner); main.postDelayed(idleRunner, 8000)
    }

    private fun pickPoint(x: Int, y: Int) {
        items = scan()
        val i = items.withIndex().filter { it.value.r.contains(x, y) }
            .minByOrNull { it.value.r.width() * it.value.r.height() }?.index
        if (i != null) speakQueue(listOf(i), 0) else say(rootNull)
    }

    private fun pickRect(r: Rect) {
        items = scan()
        val ix = items.withIndex().filter { r.contains(it.value.r.centerX(), it.value.r.centerY()) }.map { it.index }
        if (ix.isNotEmpty()) speakQueue(ix, 0) else say(rootNull)
    }

    private fun wordBox(p: Int, s: Int, e: Int) {
        val itm = items.getOrNull(queue.getOrNull(p) ?: return) ?: return
        try {
            val a = Bundle().apply {
                putInt(AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_ARG_START_INDEX, itm.off + s)
                putInt(AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_ARG_LENGTH, e - s)
            }
            if (!itm.node.refreshWithExtraData(AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_KEY, a)) return
            @Suppress("DEPRECATION")
            val arr = itm.node.extras.getParcelableArray(AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_KEY) ?: return
            var u: RectF? = null
            for (x in arr) {
                val rf = x as? RectF ?: continue
                if (u == null) u = RectF(rf) else u.union(rf)
            }
            if (u != null && !u.isEmpty) {
                layer?.hi = Rect(u.left.toInt() - 3, u.top.toInt() - 3, u.right.toInt() + 3, u.bottom.toInt() + 3)
                layer?.invalidate()
            }
        } catch (_: Exception) {}
    }

    // ---------- screen text ----------
    private fun scan(): List<Item> {
        val out = mutableListOf<Item>()
        val r = rootInActiveWindow
        rootNull = r == null
        r?.let { collect(it, out) }
        return out.sortedWith(compareBy<Item>({ it.r.top / 24 }, { it.r.left }))
    }

    private fun collect(n: AccessibilityNodeInfo, out: MutableList<Item>): Boolean {
        if (!n.isVisibleToUser) return false
        val raw = (n.text ?: n.contentDescription)?.toString() ?: ""
        val before = out.size
        for (i in 0 until n.childCount) n.getChild(i)?.let { collect(it, out) }
        val childHas = out.size > before
        if (raw.isNotBlank() && !childHas) {
            val r = Rect(); n.getBoundsInScreen(r)
            val area = r.width().toLong() * r.height()
            val screen = resources.displayMetrics.widthPixels.toLong() * resources.displayMetrics.heightPixels
            if (r.width() >= dp(10) && r.height() >= dp(10) && area < screen * 6 / 10) {
                out.add(Item(raw.trim().take(3900), raw.length - raw.trimStart().length, r, n))
            }
        }
        return raw.isNotBlank() || childHas
    }

    inner class Layer(c: Context) : View(c) {
        var hi: Rect? = null
        var sel: Rect? = null
        private val stroke = Paint().apply { style = Paint.Style.STROKE; strokeWidth = 6f; color = 0xFF2ECC40.toInt() }
        private val fill = Paint().apply { style = Paint.Style.FILL; color = 0x332ECC40 }
        private var sx = 0f; private var sy = 0f; private var drag = false

        override fun onDraw(c: Canvas) {
            val l = IntArray(2); getLocationOnScreen(l)
            c.translate(-l[0].toFloat(), -l[1].toFloat())
            hi?.let { c.drawRect(it, fill); c.drawRect(it, stroke) }
            sel?.let { c.drawRect(it, stroke) }
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            if (playing) return true
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { sx = e.rawX; sy = e.rawY; drag = false }
                MotionEvent.ACTION_MOVE -> {
                    if (abs(e.rawX - sx) > 25 || abs(e.rawY - sy) > 25) drag = true
                    if (drag) {
                        sel = Rect(minOf(sx, e.rawX).toInt(), minOf(sy, e.rawY).toInt(), maxOf(sx, e.rawX).toInt(), maxOf(sy, e.rawY).toInt())
                        invalidate()
                    }
                }
                MotionEvent.ACTION_UP -> {
                    val s = sel
                    if (drag && s != null) pickRect(s) else pickPoint(e.rawX.toInt(), e.rawY.toInt())
                    sel = null; invalidate()
                }
            }
            return true
        }
    }
}
