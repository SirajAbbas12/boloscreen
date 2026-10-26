package com.boloscreen

import android.app.Activity
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.*
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Bundle
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import android.view.Gravity
import android.view.View
import android.widget.*
import java.io.File
import java.util.Locale
import kotlin.math.abs

class MainActivity : Activity(), TextToSpeech.OnInitListener {
    private lateinit var tts: TextToSpeech
    private lateinit var sv: ScrollView
    private lateinit var col: LinearLayout
    private var ready = false
    private var voices = listOf<Voice>()
    private var prev: ImageView? = null
    private var prevFrame: FrameLayout? = null
    private lateinit var bottom: LinearLayout
    private var tab = 0
    private var step = 0
    private var dark = false
    private var cBg = 0; private var cCard = 0; private var cInk = 0; private var cMute = 0
    private var cLine = 0; private var cSel = 0; private var cOnSel = 0; private var cOk = 0; private var cBad = 0
    private val gold = 0xFFF2A900.toInt()
    private val navy = 0xFF14233A.toInt()
    private val MATCH = LinearLayout.LayoutParams.MATCH_PARENT
    private val WRAP = LinearLayout.LayoutParams.WRAP_CONTENT

    private val langs = listOf(
        "Hindi" to "hi-IN", "English (India)" to "en-IN", "English (US)" to "en-US",
        "Bengali" to "bn-IN", "Marathi" to "mr-IN", "Tamil" to "ta-IN", "Telugu" to "te-IN",
        "Gujarati" to "gu-IN", "Kannada" to "kn-IN", "Malayalam" to "ml-IN", "Punjabi" to "pa-IN",
        "Urdu" to "ur-IN", "Odia" to "or-IN", "Assamese" to "as-IN", "Nepali" to "ne-NP"
    )

    // ---------- helpers ----------
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun c(l: Long, d: Long) = (if (dark) d else l).toInt()
    private fun put(f: (SharedPreferences.Editor) -> Unit) {
        Prefs.p(this).edit().also(f).apply()
        ReaderService.inst?.let { it.applyVoice(); it.refreshButton() }
    }
    private fun shape(fill: Int, stroke: Int = 0, r: Int = 16, sw: Int = 0) = GradientDrawable().apply {
        setColor(fill); cornerRadius = dp(r).toFloat(); if (sw > 0) setStroke(dp(sw), stroke)
    }
    private fun ripple(d: Drawable): Drawable = RippleDrawable(ColorStateList.valueOf(0x33808080), d, null)
    private fun tv(t: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = t; textSize = size; setTextColor(color); if (bold) setTypeface(null, Typeface.BOLD)
    }
    private fun icon(res: Int, tint: Int, size: Int = 24) = ImageView(this).apply {
        setImageResource(res); imageTintList = ColorStateList.valueOf(tint)
        layoutParams = LinearLayout.LayoutParams(dp(size), dp(size))
    }
    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; background = shape(cCard, cLine, 20, 1)
        setPadding(dp(16), dp(16), dp(16), dp(16))
        col.addView(this, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(12) })
    }
    private fun head(p: LinearLayout, res: Int, t: String) {
        val r = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        r.addView(icon(res, cInk, 22))
        r.addView(tv(t, 17f, cInk, true), LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = dp(10) })
        p.addView(r, LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(8) })
    }
    private fun label(p: LinearLayout, t: String) {
        p.addView(tv(t, 14f, cMute), LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(14); bottomMargin = dp(4) })
    }
    private fun chip(t: String, on: Boolean, a: () -> Unit) = TextView(this).apply {
        text = t; textSize = 14f; gravity = Gravity.CENTER; minHeight = dp(48)
        setPadding(dp(8), dp(4), dp(8), dp(4))
        setTextColor(if (on) cOnSel else cInk)
        background = ripple(shape(if (on) cSel else cCard, if (on) cSel else cLine, 14, 1))
        isClickable = true; isFocusable = true; setOnClickListener { a() }
    }
    private fun chipGrid(p: LinearLayout, cols: Int, labels: List<String>, selected: Int, a: (Int) -> Unit) {
        labels.chunked(cols).forEachIndexed { r, items ->
            val row = LinearLayout(this)
            items.forEachIndexed { i, l ->
                val k = r * cols + i
                row.addView(chip(l, k == selected) { a(k) },
                    LinearLayout.LayoutParams(0, WRAP, 1f).apply { setMargins(dp(3), dp(3), dp(3), dp(3)) })
            }
            repeat(cols - items.size) { row.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f).apply { setMargins(dp(3), 0, dp(3), 0) }) }
            p.addView(row, LinearLayout.LayoutParams(MATCH, WRAP))
        }
    }
    private fun button(t: String, primary: Boolean, a: () -> Unit) = TextView(this).apply {
        text = t; textSize = 15f; gravity = Gravity.CENTER; minHeight = dp(48); setTypeface(null, Typeface.BOLD)
        setPadding(dp(16), dp(8), dp(16), dp(8))
        setTextColor(if (primary) navy else cInk)
        background = ripple(shape(if (primary) gold else cCard, if (primary) gold else cLine, 14, 1))
        isClickable = true; isFocusable = true; setOnClickListener { a() }
    }
    private fun seek(p: LinearLayout, min: Int, max: Int, cur: Int, onChange: (Int) -> Unit, onEnd: () -> Unit = {}) {
        val s = SeekBar(this).apply {
            this.max = max - min; progress = cur - min
            progressTintList = ColorStateList.valueOf(cSel); thumbTintList = ColorStateList.valueOf(cSel)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, v: Int, user: Boolean) { if (user) onChange(v + min) }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) { onEnd() }
            })
        }
        p.addView(s, LinearLayout.LayoutParams(MATCH, dp(48)))
    }
    private fun open(i: Intent) { try { startActivity(i) } catch (_: Exception) { Toast.makeText(this, "Ye phone mein available nahi", Toast.LENGTH_SHORT).show() } }
    private fun isOn() = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)?.contains(packageName) == true

    // ---------- lifecycle ----------
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        dark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        cBg = c(0xFFF4F7FB, 0xFF0E1726); cCard = c(0xFFFFFFFF, 0xFF17233A); cInk = c(0xFF14233A, 0xFFEAF0F8)
        cMute = c(0xFF4A5B73, 0xFFA9B8CE); cLine = c(0xFFC9D4E3, 0xFF2F4263); cSel = c(0xFF14233A, 0xFFF2A900)
        cOnSel = c(0xFFFFFFFF, 0xFF14233A); cOk = c(0xFF0F7B55, 0xFF4ADE9C); cBad = c(0xFFB42318, 0xFFFF8A80)
        window.statusBarColor = cBg; window.navigationBarColor = cBg
        if (!dark) window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        tts = TextToSpeech(this, this)
        sv = ScrollView(this).apply { setBackgroundColor(cBg) }
        col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(40), dp(16), dp(40)) }
        val wrap = FrameLayout(this)
        wrap.addView(col, FrameLayout.LayoutParams(minOf(resources.displayMetrics.widthPixels, dp(600)), WRAP, Gravity.CENTER_HORIZONTAL))
        sv.addView(wrap)
        bottom = LinearLayout(this).apply { background = shape(cCard, cLine, 0, 1); setPadding(0, dp(6), 0, dp(10)) }
        val rootL = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(cBg) }
        rootL.addView(sv, LinearLayout.LayoutParams(MATCH, 0, 1f))
        rootL.addView(bottom, LinearLayout.LayoutParams(MATCH, WRAP))
        setContentView(rootL)
        render()
    }

    override fun onInit(s: Int) { ready = s == TextToSpeech.SUCCESS; render() }
    override fun onResume() { super.onResume(); render() }
    override fun onDestroy() { tts.shutdown(); super.onDestroy() }

    // ---------- screen ----------
    private fun render() {
        val y = sv.scrollY
        col.removeAllViews()
        val done = Prefs.p(this).getBoolean("onb", false)
        if (!done && isOn() && step < 2) step = 2
        if (!done) { bottom.visibility = View.GONE; onboarding() }
        else { bottom.visibility = View.VISIBLE; when (tab) { 0 -> home(); 1 -> voiceTab(); else -> buttonTab() }; drawTabs() }
        sv.post { sv.scrollTo(0, y) }
    }

    private fun onboarding() {
        val data = listOf(
            Triple(R.drawable.ic_volume, "Kuch bhi padhwao", "Bolo Screen kisi bhi app ka text aapko bol kar sunati hai. Padhne ki zaroorat nahi."),
            Triple(R.drawable.ic_shield, "Ek baar permission do", "Isse app screen ka text dekh paati hai. Ye sirf padhne ke liye hai, aur aapka data phone se bahar nahi jata."),
            Triple(R.drawable.ic_check, "Sab taiyaar hai", "Floating button dabao, phir play dabao ya kisi line par tap karo. Wo bolne lagegi.")
        )
        val (ico, ttl, body) = data[step]
        col.addView(View(this), LinearLayout.LayoutParams(MATCH, dp(24)))
        val circle = FrameLayout(this).apply { background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor((gold and 0xFFFFFF) or 0x33000000) } }
        circle.addView(icon(ico, cInk, 56), FrameLayout.LayoutParams(dp(56), dp(56), Gravity.CENTER))
        col.addView(circle, LinearLayout.LayoutParams(dp(128), dp(128)).apply { gravity = Gravity.CENTER_HORIZONTAL })
        col.addView(tv(ttl, 26f, cInk, true).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(28) })
        col.addView(tv(body, 16f, cMute).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(12) })
        val dots = LinearLayout(this).apply { gravity = Gravity.CENTER }
        for (i in 0..2) dots.addView(View(this).apply { background = shape(if (i == step) cSel else cLine, 0, 4) },
            LinearLayout.LayoutParams(dp(if (i == step) 24 else 8), dp(8)).apply { setMargins(dp(4), 0, dp(4), 0) })
        col.addView(dots, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(28) })
        val lbl = listOf("Aage", "Permission do", "Shuru karo")[step]
        col.addView(button(lbl, true) {
            when (step) {
                0 -> { step = 1; render() }
                1 -> open(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                else -> { Prefs.p(this).edit().putBoolean("onb", true).apply(); tab = 0; render() }
            }
        }, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(24) })
        if (step == 1) col.addView(tv("Option grey ho to: Settings, Apps, Bolo Screen, upar teen dot, Allow restricted settings.", 13f, cMute).apply { gravity = Gravity.CENTER },
            LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(14) })
    }

    private fun drawTabs() {
        bottom.removeAllViews()
        listOf(Triple(R.drawable.ic_home, "Home", 0), Triple(R.drawable.ic_volume, "Awaaz", 1), Triple(R.drawable.ic_image, "Button", 2)).forEach { (ic, name, i) ->
            val sel = tab == i
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; minimumHeight = dp(60)
                background = ripple(shape(0, 0, 0))
                isClickable = true; isFocusable = true; contentDescription = name
                setOnClickListener { tab = i; sv.scrollTo(0, 0); render() }
            }
            item.addView(icon(ic, if (sel) cSel else cMute, 24))
            item.addView(tv(name, 12f, if (sel) cSel else cMute, sel).apply { gravity = Gravity.CENTER; maxLines = 1 },
                LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = dp(2) })
            bottom.addView(item, LinearLayout.LayoutParams(0, WRAP, 1f))
        }
    }

    private fun home() {
        val on = isOn()
        load()
        col.addView(tv("Bolo Screen", 28f, cInk, true))
        col.addView(tv("Screen ka text padhkar sunata hai", 15f, cMute))

        // status
        val h = card()
        val top = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val tone = if (on) cOk else cBad
        val badge = FrameLayout(this).apply { background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor((tone and 0xFFFFFF) or 0x26000000) } }
        badge.addView(icon(if (on) R.drawable.ic_check else R.drawable.ic_alert, tone, 28), FrameLayout.LayoutParams(dp(28), dp(28), Gravity.CENTER))
        top.addView(badge, LinearLayout.LayoutParams(dp(52), dp(52)))
        val tx = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        tx.addView(tv(if (on) "Chalu hai" else "Band hai", 20f, cInk, true))
        tx.addView(tv(if (on) "Floating button screen par dikh raha hai." else "Accessibility mein Bolo Screen on karo.", 14f, cMute))
        top.addView(tx, LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = dp(14) })
        h.addView(top)
        if (!on) {
            h.addView(button("Permission do", true) { open(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
                LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(14) })
            h.addView(tv("Option grey ho to: Settings, Apps, Bolo Screen, upar teen dot, Allow restricted settings.", 13f, cMute),
                LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(10) })
        }

        if (on) {
            val fb = card(); head(fb, R.drawable.ic_settings, "Floating button")
            val show = Prefs.p(this).getBoolean("show", true)
            chipGrid(fb, 2, listOf("Dikhao", "Chhupao"), if (show) 0 else 1) { k -> put { it.putBoolean("show", k == 0) }; render() }
            fb.addView(tv("Button dabao, phir play dabao ya kisi line par tap karo.", 14f, cMute), LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(8) })
            val tc = card(); head(tc, R.drawable.ic_play, "Awaaz test karo")
            tc.addView(button("Sample suno", true) { preview() })
        }
        val pc = card(); head(pc, R.drawable.ic_shield, "Privacy")
        pc.addView(tv("Bolo Screen sirf screen ka text padhti hai. Kuch save ya bhejti nahi. Aapka data phone se bahar nahi jata.", 14f, cMute))
        pc.addView(tv("Version 1.8", 13f, cMute), LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = dp(8) })
    }

    private fun voiceTab() {
        val cols = if (minOf(resources.displayMetrics.widthPixels, dp(600)) >= dp(480)) 4 else 3
        col.addView(tv("Awaaz", 28f, cInk, true))
        col.addView(tv("Bhasha, awaaz aur speed chuno", 15f, cMute))
        // language
        val lc = card(); head(lc, R.drawable.ic_globe, "Bhasha")
        val li = langs.indexOfFirst { it.second == Prefs.s(this, "lang", "hi-IN") }
        chipGrid(lc, cols, langs.map { it.first }, li) { k -> put { it.putString("lang", langs[k].second) }; render() }

        // voice
        load()
        val vc = card(); head(vc, R.drawable.ic_volume, "Awaaz")
        if (!ready) vc.addView(tv("Awaazein load ho rahi hain...", 14f, cMute))
        else if (voices.isEmpty()) vc.addView(tv("Is bhasha ki awaaz abhi phone mein nahi hai. Neeche download karo.", 14f, cMute))
        val curV = voices.indexOfFirst { it.name == Prefs.s(this, "voice", "") }.let { if (it < 0) 0 else it }
        voices.forEachIndexed { i, v ->
            val sel = i == curV
            val row = LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL; setPadding(dp(14), 0, dp(4), 0)
                background = ripple(shape(if (sel) cSel else cCard, if (sel) cSel else cLine, 14, 1))
                isClickable = true; isFocusable = true
                setOnClickListener { put { it.putString("voice", v.name) }; render() }
            }
            row.addView(tv("Awaaz ${i + 1}" + if (v.isNetworkConnectionRequired) " (online)" else "", 15f, if (sel) cOnSel else cInk, true),
                LinearLayout.LayoutParams(0, WRAP, 1f))
            row.addView(ImageView(this).apply {
                setImageResource(R.drawable.ic_play); imageTintList = ColorStateList.valueOf(if (sel) cOnSel else cInk)
                setPadding(dp(14), dp(14), dp(14), dp(14)); contentDescription = "Awaaz ${i + 1} suno"
                isClickable = true; setOnClickListener { preview(v) }
            }, LinearLayout.LayoutParams(dp(48), dp(48)))
            vc.addView(row, LinearLayout.LayoutParams(MATCH, dp(52)).apply { topMargin = dp(6) })
        }
        label(vc, "Awaaz ka tone")
        val pt = Prefs.f(this, "pitch", 1f)
        val pi = when { abs(pt - 1.25f) < 0.05f -> 0; abs(pt - 0.8f) < 0.05f -> 2; else -> 1 }
        chipGrid(vc, 3, listOf("Patli", "Normal", "Bhaari"), pi) { k ->
            put { it.putFloat("pitch", floatArrayOf(1.25f, 1f, 0.8f)[k]) }; preview(); render()
        }
        vc.addView(button("Aur awaazein download karo", false) { open(Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA)) },
            LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(12) })

        // speed
        val sc = card(); head(sc, R.drawable.ic_zap, "Bolne ki speed")
        val rate = Prefs.f(this, "rate", 1f)
        val lab = tv(String.format(Locale.US, "%.1fx", rate), 14f, cMute)
        sc.addView(lab)
        seek(sc, 40, 200, (rate * 100).toInt(), { v -> lab.text = String.format(Locale.US, "%.1fx", v / 100f); put { it.putFloat("rate", v / 100f) } }, { preview() })
        val rv = floatArrayOf(0.6f, 0.8f, 1f, 1.4f)
        val ri = rv.indexOfFirst { abs(it - rate) < 0.05f }
        chipGrid(sc, 4, listOf("Bahut dheere", "Dheere", "Normal", "Tez"), ri) { k -> put { it.putFloat("rate", rv[k]) }; preview(); render() }

    }

    private fun buttonTab() {
        col.addView(tv("Button", 28f, cInk, true))
        col.addView(tv("Floating button ko apne hisaab se banao", 15f, cMute))
        val ah = card(); head(ah, R.drawable.ic_chevron_left, "Idle mein halka")
        val auto = Prefs.p(this).getBoolean("autohide", true)
        chipGrid(ah, 2, listOf("Chalu", "Band"), if (auto) 0 else 1) { k -> put { it.putBoolean("autohide", k == 0) }; render() }
        ah.addView(tv("Chalu ho to kuch der baad button kinare par halka ho jata hai. Chhupane ke liye use kinare ki taraf dhakel do.", 14f, cMute),
            LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(8) })
        val cc = card(); head(cc, R.drawable.ic_image, "Button ka rang")
        chipGrid(cc, 2, listOf("Neela (Google)", "Kaala", "Safed", "Peela"), Prefs.bidx(this)) { k -> put { it.putInt("bcolor", k) }; render() }
        // floating button
        val fc = card(); head(fc, R.drawable.ic_image, "Floating button")
        val box = FrameLayout(this).apply { background = shape(cBg, cLine, 16, 1) }
        prev = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = GradientDrawable().apply { setColor(Prefs.bcolor(this@MainActivity)); setStroke(dp(2), 0xCC000000.toInt()) }
            outlineProvider = android.view.ViewOutlineProvider.BACKGROUND; clipToOutline = true
            contentDescription = "Button ka preview"
            val f = File(filesDir, "btn.png")
            if (f.exists()) setImageBitmap(BitmapFactory.decodeFile(f.path))
            else { setPadding(dp(16), dp(16), dp(16), dp(16)); setImageResource(R.drawable.ic_volume); imageTintList = ColorStateList.valueOf(Prefs.btint(this@MainActivity)) }
        }
        val pf = FrameLayout(this).apply { background = GradientDrawable().apply { setColor(0xD9000000.toInt()); setStroke(dp(1), 0x33FFFFFF) } }
        prevFrame = pf
        pf.addView(prev, FrameLayout.LayoutParams(dp(64), dp(64), Gravity.CENTER))
        box.addView(pf, FrameLayout.LayoutParams(dp(90), dp(90), Gravity.CENTER))
        fc.addView(box, LinearLayout.LayoutParams(MATCH, dp(120)))
        applyPrev()
        val br = LinearLayout(this)
        br.addView(button("Apni photo lagao", true) { startActivityForResult(Intent(Intent.ACTION_GET_CONTENT).setType("image/*"), 1) },
            LinearLayout.LayoutParams(0, WRAP, 2f).apply { marginEnd = dp(8) })
        br.addView(button("Hatao", false) { File(filesDir, "btn.png").delete(); ReaderService.inst?.refreshButton(); render() },
            LinearLayout.LayoutParams(0, WRAP, 1f))
        fc.addView(br, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(12) })
        label(fc, "Size")
        seek(fc, 40, 100, Prefs.i(this, "size", 64), { v -> put { it.putInt("size", v) }; applyPrev() })
        label(fc, "Dhundhla se saaf (transparency)")
        seek(fc, 30, 100, Prefs.i(this, "alpha", 100), { v -> put { it.putInt("alpha", v) }; applyPrev() })
        fc.addView(tv("Tip: asli button ko ungli se kahin bhi khinch kar rakh sakte ho.", 13f, cMute),
            LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(8) })

    }

    private fun applyPrev() {
        prev?.let { p ->
            val s = dp(Prefs.i(this, "size", 64))
            p.layoutParams = FrameLayout.LayoutParams(s, s, Gravity.CENTER)
            val pad = (s * 0.18f).toInt()
            prevFrame?.apply {
                layoutParams = FrameLayout.LayoutParams(s + 2 * pad, s + 2 * pad, Gravity.CENTER)
                alpha = Prefs.i(this@MainActivity, "alpha", 100) / 100f
                (background as? GradientDrawable)?.cornerRadius = (s + 2 * pad) * 0.3f
            }
            (p.background as? GradientDrawable)?.apply { cornerRadius = s * 0.24f; setColor(Prefs.bcolor(this@MainActivity)) }
        }
    }

    private fun load() {
        if (!ready) return
        val lang = Locale.forLanguageTag(Prefs.s(this, "lang", "hi-IN")).language
        voices = (tts.voices ?: emptySet()).filter { it.locale.language == lang }.sortedBy { it.name }
    }

    private fun preview(v: Voice? = null) {
        if (!ready) return
        val loc = Locale.forLanguageTag(Prefs.s(this, "lang", "hi-IN"))
        tts.language = loc
        (v ?: voices.firstOrNull { it.name == Prefs.s(this, "voice", "") })?.let { tts.voice = it }
        tts.setSpeechRate(Prefs.f(this, "rate", 1f)); tts.setPitch(Prefs.f(this, "pitch", 1f))
        val txt = if (loc.language == "hi") "नमस्ते, यह मेरी आवाज़ है। मैं आपकी स्क्रीन पढ़कर सुनाऊँगा।"
        else "Hello, this is my voice. I will read your screen aloud."
        tts.speak(txt, TextToSpeech.QUEUE_FLUSH, null, "p")
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val u = data?.data ?: return
        if (requestCode != 1 || resultCode != RESULT_OK) return
        try {
            val bm = ImageDecoder.decodeBitmap(ImageDecoder.createSource(contentResolver, u)) { d, info, _ ->
                val m = 256f / maxOf(info.size.width, info.size.height)
                d.setTargetSize(maxOf(1, (info.size.width * m).toInt()), maxOf(1, (info.size.height * m).toInt()))
                d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
            File(filesDir, "btn.png").outputStream().use { bm.compress(Bitmap.CompressFormat.PNG, 100, it) }
            ReaderService.inst?.refreshButton(); render()
        } catch (_: Exception) { Toast.makeText(this, "Photo nahi khuli", Toast.LENGTH_SHORT).show() }
    }
}
