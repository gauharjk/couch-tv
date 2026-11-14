package dev.couchtv.ontv

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Process
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.util.Base64
import android.util.LruCache
import android.util.SparseArray
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.animation.PathInterpolator
import android.webkit.CookieManager
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Smooth tiles (NATIVE-TILES-PLAN.md): the home's dock and its Keep watching cards drawn by plain Android
 * views over the page, so a focus move is a scale, a lift and a fade on the render thread and, in a window of
 * their own, never makes the WebView draw again. The page stays the source of truth: it lays its own tiles
 * out as always (hidden while these are up) and sends each one's box in device pixels (TVOS.tiles), the focus
 * on every paint (TVOS.tilesFocus) and whether to show them (TVOS.tilesShow). This only draws and animates;
 * it never lays anything out and never opens anything (OK still goes to the page). Everything here runs on
 * the main thread, except decoding pictures (TilesLoader, one background thread).
 */
internal class TilesLayer(ctx: Context, private val host: Host) : TilesBox(ctx) {
    interface Host {
        /** An app's icon drawn at size x size (on the loader's thread). */
        fun icon(pkg: String, size: Int): Bitmap?
        /** A Watch Next card's picture, tvos.invalid/art/<id> (on the loader's thread). */
        fun art(id: String): ByteArray?
        /** Where the page's WebView is on screen: the boxes the page sends are from its corner. */
        fun origin(out: IntArray)
        /** The tiles asked for with tilesShow(true, n) are on screen: the page may hide its own. */
        fun ready(n: Int)
        /** A guessed move the page never answered: ask it for everything again. */
        fun resync()
    }

    companion object {
        /** In a sub-window of its own. False: over the WebView in the activity (to compare; also the fallback). */
        const val IN_WINDOW = true
        /** Left and right move this focus at once, before the page has answered the key. */
        const val PREDICT = true
        const val CACHE_BYTES = 6 * 1024 * 1024
        private const val PENDING_MS = 400L
        private const val REVEAL_MS = 600L
        internal val EASE = PathInterpolator(0.2f, 0.8f, 0.2f, 1f)   // app.css --ease
        /** app.css .theme-night .app .face.word: for an app whose icon cannot be read. */
        internal val WORD_BG = Color.rgb(42, 42, 48)

        /** Parsed on the JavaBridge thread, so the main thread only applies it. */
        fun parse(json: String) = Model(JSONObject(json))
        fun parseFocus(json: String) = Focus(JSONObject(json))
    }

    // ---------------------------------------------------------------- what the page sends

    class Text(j: JSONObject?) {
        val size = j.f("size", 16f); val lh = j.f("lh", size * 1.2f); val weight = j?.optInt("weight", 400) ?: 400
        val color = colour(j.s("color"), Color.WHITE); val ls = j.f("ls"); val upper = j?.optBoolean("upper") ?: false
        val pad = j.f("pad")
    }

    class Look(j: JSONObject?) {
        val scale = j.f("scale", 1.16f); val lift = j.f("lift"); val ring = j.f("ring").roundToInt()
        val z0 = j.f("z0"); val z1 = j.f("z1"); val dim = j.f("dim", 0.18f)
        val cardScale = j.f("cardScale", 1.05f); val cardRing = j.f("cardRing").roundToInt(); val cz = j.f("cz")
        val edge = j.f("edge", 1f).roundToInt(); val edgeColor = colour(j.s("edgeColor"), Color.TRANSPARENT)
        val dotRing = j.f("dotRing").roundToInt()
        val dur = j.f("dur", 200f).toLong(); val scroll = j.f("scroll", 280f).toLong(); val fade = j.f("fade", 240f).toLong()
        val label = Text(j?.optJSONObject("label")); val word = Text(j?.optJSONObject("word"))
        val nap = Text(j?.optJSONObject("nap")); val title = Text(j?.optJSONObject("title")); val sub = Text(j?.optJSONObject("sub"))
    }

    data class Tile(val key: String, val kind: String, val rect: Rect, val img: String?, val glyph: String?, val grect: Rect?,
                    val bg: Int, val radius: Float, val label: String, val lrect: Rect?, val word: String?,
                    val dot: Rect?, val dotc: Int) {
        constructor(j: JSONObject) : this(j.s("key") ?: "", j.s("kind") ?: "app", j.rect("rect") ?: Rect(), j.s("img"),
            j.s("glyph"), j.rect("grect"), colour(j.s("bg"), Color.TRANSPARENT), j.f("radius"), j.s("label") ?: "",
            j.rect("lrect"), j.s("word"), j.rect("dot"), colour(j.s("dotc"), Color.rgb(10, 132, 255)))
    }

    data class Card(val key: String, val rect: Rect, val bg: Int, val radius: Float, val img: String?, val trect: Rect?,
                    val tbg: Int, val tradius: Float, val app: String, val appIcon: String?, val irect: Rect?, val iradius: Float,
                    val nrect: Rect?, val title: String, val brect: Rect?, val sub: String, val srect: Rect?,
                    val progress: Float, val prect: Rect?, val pbg: Int, val pradius: Float) {
        constructor(j: JSONObject) : this(j.s("key") ?: "", j.rect("rect") ?: Rect(), colour(j.s("bg"), Color.TRANSPARENT),
            j.f("radius"), j.s("img"), j.rect("trect"), colour(j.s("tbg"), Color.TRANSPARENT), j.f("tradius"),
            j.s("app") ?: "", j.s("appIcon"), j.rect("irect"), j.f("iradius"), j.rect("nrect"), j.s("title") ?: "",
            j.rect("brect"), j.s("sub") ?: "", j.rect("srect"), j.f("progress").coerceIn(0f, 1f), j.rect("prect"),
            colour(j.s("pbg"), Color.TRANSPARENT), j.f("pradius"))
    }

    class Model(j: JSONObject) {
        val look = Look(j.optJSONObject("look"))
        val dock: List<Tile> = j.optJSONArray("dock").objects().map { Tile(it) }
        val clip: Rect?
        val cards: List<Card>
        init {
            val sh = j.optJSONObject("shelf")
            clip = sh?.rect("clip")
            cards = if (clip == null) emptyList() else sh?.optJSONArray("tiles").objects().map { Card(it) }
        }
    }

    /** The page's focus: zone "grid" (a dock tile) or "shelf" (a card), anything else none of these. */
    class Focus(j: JSONObject) {
        val zone = j.s("zone") ?: ""; val index = j.optInt("index", -1); val scroll = j.f("scroll")
        val key = j.optInt("k") == 1; val shelf = j.optBoolean("shelf", true)
    }

    // ---------------------------------------------------------------- state

    private val clipBox = TilesBox(ctx)
    private val track = TilesBox(ctx)
    private var dock = HashMap<String, TileView>(); private var dockList = ArrayList<TileView>()
    private var cards = HashMap<String, CardView>(); private var cardList = ArrayList<CardView>()
    private var hasModel = false
    private var look: Look? = null
    private var fZone = ""; private var fIndex = -1; private var lit: Lit? = null
    private var scrollTo = 0f; private var shelfOn = true
    // left and right guessed here before the page answered: how many answers are still to come
    private var pending = 0; private var predZone = ""
    private var wanted = false; private var revealed = false; private var showN = 0
    private var loading = 0
    private val loader = TilesLoader(host)
    private val me = IntArray(2); private val web = IntArray(2)

    init {
        visibility = INVISIBLE
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        clipBox.addView(track, TilesBox.P(0, 0, 0, 0))
    }

    // ---------------------------------------------------------------- from the page

    /** A new model: views are kept by key and moved, new ones made, gone ones dropped. */
    fun model(m: Model) {
        look = m.look; hasModel = true
        getLocationOnScreen(me); host.origin(web)
        val ox = web[0] - me[0]; val oy = web[1] - me[1]
        val nd = HashMap<String, TileView>(); val nl = ArrayList<TileView>(m.dock.size)
        for (t in m.dock) {
            var k = t.key
            while (nd.containsKey(k)) k += "+"
            val v = dock.remove(k) ?: TileView(context, this)
            v.bind(t, m.look)
            place(v, t.rect, ox, oy)
            nd[k] = v; nl.add(v)
        }
        for (v in dock.values) removeView(v)
        dock = nd; dockList = nl
        val nc = HashMap<String, CardView>(); val cl = ArrayList<CardView>(m.cards.size)
        val clip = m.clip
        if (clip != null) {
            place(clipBox, clip, ox, oy)
            clipBox.clipBounds = Rect(0, 0, clip.width(), clip.height())
            clipBox.visibility = VISIBLE
            clipBox.place(track, 0, 0, clip.width(), clip.height())
            for (c in m.cards) {
                var k = c.key
                while (nc.containsKey(k)) k += "+"
                val v = cards.remove(k) ?: CardView(context, this)
                v.bind(c, m.look)
                track.place(v, c.rect, -clip.left, -clip.top)
                nc[k] = v; cl.add(v)
            }
        } else if (clipBox.parent != null) clipBox.visibility = GONE
        for (v in cards.values) track.removeView(v)
        cards = nc; cardList = cl
        // the focus as last told, on the views as they are now
        val v = target(fZone, fIndex)
        if (v !== lit) { lit?.lit(false, false); lit = v }
        v?.lit(true, false)
        if (wanted && !revealed) { removeCallbacks(revealNow); postDelayed(revealNow, REVEAL_MS); tryReveal() }
    }

    fun focus(f: Focus) {
        if (pending > 0) {
            if (f.key) pending--
            // the page is still catching up with keys guessed here: its older answers would pull the focus back
            if (pending > 0 && f.zone == predZone) return
            pending = 0; removeCallbacks(stale)
        }
        val anim = revealed && isShown
        val lk = look
        if (f.shelf != shelfOn) { shelfOn = f.shelf; fadeTo(clipBox, anim, lk?.fade ?: 240L, if (f.shelf) 1f else 0f) }
        if (f.scroll != scrollTo) {
            scrollTo = f.scroll
            if (anim) track.animate().translationX(f.scroll).setDuration(lk?.scroll ?: 280L).setInterpolator(EASE).start()
            else { track.animate().cancel(); track.translationX = f.scroll }
        }
        select(f.zone, f.index, anim)
    }

    /** Show (once the model and its pictures are in, or after REVEAL_MS) or hide; hiding drops any guess. */
    fun show(on: Boolean, n: Int) {
        showN = n
        if (!on) {
            wanted = false; revealed = false; pending = 0
            removeCallbacks(stale); removeCallbacks(revealNow)
            visibility = INVISIBLE
            return
        }
        wanted = true
        if (revealed) { readySoon(n); return }
        removeCallbacks(revealNow); postDelayed(revealNow, REVEAL_MS)
        tryReveal()
    }

    /**
     * A left or right key, before the page has it: moves the focus to the next tile on the same row now. The
     * page's answers then count down [pending]; one that never comes is fixed after PENDING_MS (resync).
     * Never up or down, never past a row's end, never while hidden.
     */
    fun predict(dir: Int): Boolean {
        if (!PREDICT || !revealed || !isShown || !hasModel) return false
        val list: List<View> = when (fZone) { "grid" -> dockList; "shelf" -> cardList; else -> return false }
        val a = list.getOrNull(fIndex) ?: return false
        val b = list.getOrNull(fIndex + dir) ?: return false
        if ((a.layoutParams as TilesBox.P).y != (b.layoutParams as TilesBox.P).y) return false   // the same row only
        pending++; predZone = fZone
        select(fZone, fIndex + dir, true)
        removeCallbacks(stale); postDelayed(stale, PENDING_MS)
        return true
    }

    /** A new page (load, reload, restart()): nothing of the old one stays. */
    fun clear() {
        show(false, 0)
        loader.drop(); loading = 0
        for (v in dockList) removeView(v)
        for (v in cardList) track.removeView(v)
        dock = HashMap(); dockList = ArrayList(); cards = HashMap(); cardList = ArrayList()
        lit = null; fZone = ""; fIndex = -1; hasModel = false; look = null
        scrollTo = 0f; track.animate().cancel(); track.translationX = 0f
        shelfOn = true; clipBox.animate().cancel(); clipBox.alpha = 1f
    }

    /** Memory is critical: the cache goes (the views keep what they show). */
    fun trim() = loader.evict()

    /** The page was released: everything goes. */
    fun release() { clear(); loader.evict() }

    fun destroy() { clear(); loader.quit() }

    // ---------------------------------------------------------------- inside

    private fun target(zone: String, index: Int): Lit? = when (zone) {
        "grid" -> dockList.getOrNull(index)
        "shelf" -> cardList.getOrNull(index)
        else -> null
    }

    private fun select(zone: String, index: Int, animate: Boolean) {
        fZone = zone; fIndex = index
        val v = target(zone, index)
        if (v === lit) return
        lit?.lit(false, animate)
        v?.lit(true, animate)
        lit = v
    }

    private val stale = Runnable { if (pending > 0) { pending = 0; host.resync() } }
    private val revealNow = Runnable { if (wanted && !revealed && hasModel) reveal() }

    private fun tryReveal() { if (wanted && !revealed && hasModel && loading == 0) reveal() }

    private fun reveal() {
        removeCallbacks(revealNow)
        revealed = true
        visibility = VISIBLE
        readySoon(showN)
    }

    /** Two frames on, the tiles have been drawn: the page may hide its own. */
    private fun readySoon(n: Int) {
        postOnAnimation { postOnAnimation { if (wanted && revealed && showN == n) host.ready(n) } }
    }

    /** A picture at w x h, from the cache or the loader; the first showing waits for these. */
    fun load(url: String, w: Int, h: Int, cb: (Bitmap?) -> Unit) {
        val ww = max(1, w); val hh = max(1, h)
        loader.cached(url, ww, hh)?.let { cb(it); return }
        loading++
        loader.fetch(url, ww, hh) { b -> loading = max(0, loading - 1); cb(b); tryReveal() }
    }
}

// ---------------------------------------------------------------- views

internal interface Lit { fun lit(on: Boolean, animate: Boolean) }

/** Children at fixed boxes (negative ones too: a ring or a label outside the tile), no layout logic. */
internal open class TilesBox(ctx: Context) : ViewGroup(ctx) {
    class P(var x: Int, var y: Int, w: Int, h: Int) : ViewGroup.LayoutParams(w, h)

    init { clipChildren = false; clipToPadding = false }

    fun place(v: View, x: Int, y: Int, w: Int, h: Int) {
        val ww = max(0, w); val hh = max(0, h)
        val p = v.layoutParams as? P
        if (p != null && v.parent === this) {
            if (p.x != x || p.y != y || p.width != ww || p.height != hh) { p.x = x; p.y = y; p.width = ww; p.height = hh; v.requestLayout() }
            return
        }
        (v.parent as? ViewGroup)?.removeView(v)
        addView(v, P(x, y, ww, hh))
    }

    fun place(v: View, r: Rect, dx: Int = 0, dy: Int = 0) = place(v, r.left + dx, r.top + dy, r.width(), r.height())

    override fun onMeasure(ws: Int, hs: Int) {
        for (i in 0 until childCount) {
            val c = getChildAt(i); val p = c.layoutParams as P
            c.measure(MeasureSpec.makeMeasureSpec(p.width, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(p.height, MeasureSpec.EXACTLY))
        }
        setMeasuredDimension(MeasureSpec.getSize(ws), MeasureSpec.getSize(hs))
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        for (i in 0 until childCount) {
            val c = getChildAt(i); val p = c.layoutParams as P
            c.layout(p.x, p.y, p.x + p.width, p.y + p.height)
        }
    }

    override fun checkLayoutParams(p: ViewGroup.LayoutParams?) = p is P
    override fun generateDefaultLayoutParams(): ViewGroup.LayoutParams = P(0, 0, 0, 0)
    override fun generateLayoutParams(p: ViewGroup.LayoutParams?): ViewGroup.LayoutParams = P(0, 0, p?.width ?: 0, p?.height ?: 0)
    override fun shouldDelayChildPressedState() = false
    override fun hasOverlappingRendering() = false
}

/** A face: colour, a picture filling it, a tool's drawing, or an app's name; rounded by its outline. */
internal class FaceView(ctx: Context) : View(ctx) {
    var bg = 0
    var radius = 0f
        set(v) { if (field != v) { field = v; invalidateOutline() } }
    var url: String? = null
    var bmp: Bitmap? = null
    var gurl: String? = null
    var glyph: Bitmap? = null
    val grect = Rect()
    var word: StaticLayout? = null
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val dst = Rect()

    init {
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(v: View, o: Outline) { o.setRoundRect(0, 0, v.width, v.height, radius) }
        }
        clipToOutline = true
    }

    override fun onDraw(c: Canvas) {
        if (Color.alpha(bg) != 0) c.drawColor(bg)
        bmp?.let { dst.set(0, 0, width, height); c.drawBitmap(it, null, dst, paint) }
        glyph?.let { c.drawBitmap(it, null, grect, paint) }
        word?.let { c.save(); c.translate((width - it.width) / 2f, (height - it.height) / 2f); it.draw(c); c.restore() }
    }

    override fun hasOverlappingRendering() = false
}

/** A dock tile: the face, the 18 % dim of an unfocused icon, the white ring, the name under it, the blue dot. */
internal class TileView(ctx: Context, private val layer: TilesLayer) : TilesBox(ctx), Lit {
    private val face = FaceView(ctx)
    private val dim = View(ctx)
    private val ring = View(ctx)
    private val label = TextView(ctx)
    private var dot: View? = null
    private var t: TilesLayer.Tile? = null
    private var look: TilesLayer.Look? = null
    private var on = false

    init {
        ring.alpha = 0f; label.alpha = 0f
        label.setSingleLine(); label.ellipsize = TextUtils.TruncateAt.END; label.gravity = Gravity.CENTER; label.includeFontPadding = false
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(v: View, o: Outline) { o.setRoundRect(0, 0, v.width, v.height, face.radius) }
        }
    }

    fun bind(n: TilesLayer.Tile, lk: TilesLayer.Look) {
        val old = t
        look = lk
        if (old != null && old.copy(rect = n.rect) == n && old.rect.width() == n.rect.width() && old.rect.height() == n.rect.height()) return
        t = n
        val w = n.rect.width(); val h = n.rect.height()
        place(face, 0, 0, w, h)
        face.bg = n.bg; face.radius = n.radius
        invalidateOutline()
        place(dim, 0, 0, w, h)
        dim.background = rounded(Color.argb((lk.dim * 255).roundToInt().coerceIn(0, 255), 0, 0, 0), n.radius)
        val rw = lk.ring
        place(ring, -rw, -rw, w + 2 * rw, h + 2 * rw)
        ring.background = stroke(rw, Color.WHITE, n.radius + rw)
        val lr = n.lrect
        if (lr != null) { place(label, lr); styleText(label, lk.label); label.text = cased(n.label, lk.label) } else removeView(label)
        val dr = n.dot
        if (dr != null) {
            val d = dot ?: View(context).also { dot = it }
            val s = lk.dotRing
            place(d, dr.left - s, dr.top - s, dr.width() + 2 * s, dr.height() + 2 * s)
            d.background = LayerDrawable(arrayOf<Drawable>(oval(Color.argb(77, 0, 0, 0)), oval(n.dotc))).apply { setLayerInset(1, s, s, s, s) }
        } else dot?.let { removeView(it); dot = null }
        face.word = if (n.kind == "word") wordLayout(n.word ?: n.label, lk.word, w) else null
        val g = if (n.kind == "tool") n.glyph else null
        if (g != face.gurl) {
            face.gurl = g; face.glyph = null
            val gr = n.grect
            if (g != null && gr != null) {
                face.grect.set(gr)
                layer.load(g, (gr.width() * lk.scale).roundToInt(), (gr.height() * lk.scale).roundToInt()) { b ->
                    if (face.gurl == g) { face.glyph = b; face.invalidate() }
                }
            }
        }
        val u = if (n.kind == "app") n.img else null
        if (u != face.url) {
            face.url = u; face.bmp = null
            if (u != null) layer.load(u, (w * lk.scale).roundToInt(), (h * lk.scale).roundToInt()) { b ->
                if (face.url == u) {
                    // the page shows the app's name instead of an icon it cannot load; so does this
                    if (b != null) face.bmp = b else { face.bg = TilesLayer.WORD_BG; face.word = wordLayout(n.label, lk.word, w) }
                    dimShown(); face.invalidate()
                }
            }
        }
        dimShown()
        face.invalidate()
        if (old == null) state(false)
    }

    private fun dimShown() { dim.visibility = if (face.bmp != null) VISIBLE else INVISIBLE }

    override fun lit(on: Boolean, animate: Boolean) {
        if (on == this.on) return
        this.on = on
        state(animate)
    }

    private fun state(animate: Boolean) {
        val lk = look ?: return
        moveTo(this, animate, lk.dur, if (on) lk.scale else 1f, if (on) -lk.lift else 0f, if (on) max(lk.z1, 1f) else lk.z0)
        fadeTo(ring, animate, lk.dur, if (on) 1f else 0f)
        fadeTo(dim, animate, lk.dur, if (on) 0f else 1f)
        fadeTo(label, animate, lk.dur, if (on) 1f else 0f)
    }
}

/** A Keep watching card: tint, picture, the app's icon and name, title, episode, progress, and the ring. */
internal class CardView(ctx: Context, private val layer: TilesLayer) : TilesBox(ctx), Lit {
    private val bg = View(ctx)
    private val thumb = FaceView(ctx)
    private val icon = FaceView(ctx)
    private val nap = TextView(ctx)
    private val title = TextView(ctx)
    private val sub = TextView(ctx)
    private val bar = TilesBox(ctx)
    private val fill = View(ctx)
    private val ring = View(ctx)
    private var c: TilesLayer.Card? = null
    private var look: TilesLayer.Look? = null
    private var on = false

    init {
        ring.alpha = 0f
        for (v in arrayOf(nap, title)) { v.setSingleLine(); v.gravity = Gravity.CENTER_VERTICAL or Gravity.START; v.includeFontPadding = false }
        title.ellipsize = TextUtils.TruncateAt.END
        nap.setHorizontallyScrolling(true)
        sub.includeFontPadding = false
        bar.clipToOutline = true
        fill.setBackgroundColor(Color.WHITE)
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(v: View, o: Outline) { o.setRoundRect(0, 0, v.width, v.height, c?.radius ?: 0f) }
        }
    }

    fun bind(n: TilesLayer.Card, lk: TilesLayer.Look) {
        val old = c
        look = lk
        if (old != null && old.copy(rect = n.rect) == n && old.rect.width() == n.rect.width() && old.rect.height() == n.rect.height()) return
        c = n
        val w = n.rect.width(); val h = n.rect.height()
        place(bg, 0, 0, w, h)
        bg.background = rounded(n.bg, n.radius).apply { if (lk.edge > 0) setStroke(lk.edge, lk.edgeColor) }
        invalidateOutline()
        picture(thumb, n.trect, n.img, n.tbg, n.tradius, lk.cardScale)
        picture(icon, n.irect, n.appIcon, Color.TRANSPARENT, n.iradius, lk.cardScale)
        text(nap, n.nrect, n.app, lk.nap)
        text(title, n.brect, n.title, lk.title)
        val sr = n.srect
        if (sr != null && n.sub.isNotEmpty()) {
            place(sub, sr); styleText(sub, lk.sub)
            val lines = max(1, (sr.height() / max(1f, lk.sub.lh)).roundToInt())
            if (lines == 1) { sub.setSingleLine(); sub.gravity = Gravity.CENTER_VERTICAL or Gravity.START; sub.setPadding(0, 0, 0, 0) }
            else {
                // a long episode name wraps, as on the page: line height as the page's, the text in the middle of each line
                sub.setSingleLine(false); sub.maxLines = lines; sub.gravity = Gravity.TOP or Gravity.START
                val extra = lk.sub.lh - sub.paint.fontSpacing
                sub.setLineSpacing(extra, 1f); sub.setPadding(0, max(0, (extra / 2).roundToInt()), 0, 0)
            }
            sub.ellipsize = TextUtils.TruncateAt.END
            sub.text = cased(n.sub, lk.sub)
        } else removeView(sub)
        val pr = n.prect
        if (pr != null) {
            place(bar, pr)
            bar.background = rounded(n.pbg, n.pradius)
            bar.place(fill, 0, 0, (pr.width() * n.progress).roundToInt(), pr.height())
        } else removeView(bar)
        val rw = lk.cardRing
        place(ring, -rw, -rw, w + 2 * rw, h + 2 * rw)
        ring.background = stroke(rw, Color.WHITE, n.radius + rw)
        if (old == null) state(false)
    }

    private fun picture(v: FaceView, r: Rect?, url: String?, colour: Int, radius: Float, scale: Float) {
        if (r == null) { removeView(v); v.url = null; v.bmp = null; return }
        place(v, r)
        v.bg = colour; v.radius = radius
        if (url != v.url) {
            v.url = url; v.bmp = null
            if (url != null) layer.load(url, (r.width() * scale).roundToInt(), (r.height() * scale).roundToInt()) { b ->
                if (v.url == url) { v.bmp = b; v.invalidate() }
            }
        }
        v.invalidate()
    }

    private fun text(v: TextView, r: Rect?, s: String, st: TilesLayer.Text) {
        if (r == null || s.isEmpty()) { removeView(v); return }
        place(v, r); styleText(v, st); v.text = cased(s, st)
    }

    override fun lit(on: Boolean, animate: Boolean) {
        if (on == this.on) return
        this.on = on
        state(animate)
    }

    private fun state(animate: Boolean) {
        val lk = look ?: return
        moveTo(this, animate, lk.dur, if (on) lk.cardScale else 1f, 0f, if (on) max(lk.cz, 1f) else 0f)
        fadeTo(ring, animate, lk.dur, if (on) 1f else 0f)
    }
}

// ---------------------------------------------------------------- pictures

/** Pictures at the size they are shown, decoded on one background thread, kept in a small LRU cache. */
internal class TilesLoader(private val host: TilesLayer.Host) {
    private val cache = object : LruCache<String, Bitmap>(TilesLayer.CACHE_BYTES) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val thread = HandlerThread("couchtv-tiles", Process.THREAD_PRIORITY_BACKGROUND).also { it.start() }
    private val work = Handler(thread.looper)
    private val ui = Handler(Looper.getMainLooper())
    private val waiting = HashMap<String, ArrayList<(Bitmap?) -> Unit>>()
    private var gen = 0

    private fun key(url: String, w: Int, h: Int) = "$w x $h $url"

    fun cached(url: String, w: Int, h: Int): Bitmap? = cache.get(key(url, w, h))

    fun fetch(url: String, w: Int, h: Int, cb: (Bitmap?) -> Unit) {
        val k = key(url, w, h)
        waiting[k]?.let { it.add(cb); return }
        waiting[k] = arrayListOf(cb)
        val g = gen
        work.post {
            val b = try { decode(url, w, h) } catch (e: Throwable) { null }   // OutOfMemoryError too: no picture, no crash
            ui.post {
                if (g != gen) return@post
                val cbs = waiting.remove(k) ?: return@post
                if (b != null) cache.put(k, b)
                for (f in cbs) f(b)
            }
        }
    }

    /** Whatever is on its way is not wanted any more. */
    fun drop() { gen++; waiting.clear(); work.removeCallbacksAndMessages(null) }
    fun evict() = cache.evictAll()
    fun quit() { drop(); evict(); thread.quitSafely() }

    private fun decode(url: String, w: Int, h: Int): Bitmap? {
        val bytes: ByteArray = if (url.startsWith("data:")) {
            Base64.decode(url.substring(url.indexOf(',') + 1), Base64.DEFAULT)
        } else {
            val u = Uri.parse(url)
            if (u.host == "tvos.invalid") {
                // the pictures HomeActivity already makes for the page, without a PNG or JPEG round trip for icons
                val last = u.lastPathSegment ?: return null
                when (u.pathSegments.firstOrNull()) {
                    "icon" -> return host.icon(last, max(w, h))?.let { cover(it, w, h) }
                    "art" -> host.art(last) ?: return null
                    else -> return null
                }
            } else if (u.scheme == "http" || u.scheme == "https") fetchBytes(url) ?: return null
            else return null
        }
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
        if (o.outWidth <= 0 || o.outHeight <= 0) return null
        var s = 1
        while (o.outWidth / (s * 2) >= w && o.outHeight / (s * 2) >= h) s *= 2
        val o2 = BitmapFactory.Options().apply {
            inSampleSize = s
            // a photo has no see-through parts: half the memory
            inPreferredConfig = if (o.outMimeType == "image/jpeg") Bitmap.Config.RGB_565 else Bitmap.Config.ARGB_8888
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o2)?.let { cover(it, w, h) }
    }

    /** The middle of src with the shape of w x h (object-fit: cover), no bigger than w x h. */
    private fun cover(src: Bitmap, w: Int, h: Int): Bitmap {
        val sw = src.width; val sh = src.height
        if (sw == w && sh == h) return src
        val k = max(w.toFloat() / sw, h.toFloat() / sh)
        val cw = min(sw, (w / k).roundToInt()).coerceAtLeast(1); val ch = min(sh, (h / k).roundToInt()).coerceAtLeast(1)
        val ow = if (k >= 1f) cw else w; val oh = if (k >= 1f) ch else h
        val out = Bitmap.createBitmap(ow, oh, if (src.config == Bitmap.Config.RGB_565) Bitmap.Config.RGB_565 else Bitmap.Config.ARGB_8888)
        val sx = (sw - cw) / 2; val sy = (sh - ch) / 2
        Canvas(out).drawBitmap(src, Rect(sx, sy, sx + cw, sy + ch), Rect(0, 0, ow, oh), Paint(Paint.FILTER_BITMAP_FLAG))
        src.recycle()   // never shown: only the copy is
        return out
    }

    private fun fetchBytes(url: String): ByteArray? {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 5000; c.readTimeout = 8000
            // the app's own server wants the page's token, as the page sends it
            val h = c.url.host
            if (h == "127.0.0.1" || h == "localhost") CookieManager.getInstance().getCookie(url)?.let { c.setRequestProperty("Cookie", it) }
            if (c.responseCode !in 200..299) return null
            c.inputStream.use { inp ->
                val out = ByteArrayOutputStream()
                val buf = ByteArray(16 * 1024)
                while (true) {
                    val n = inp.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    if (out.size() > 8 * 1024 * 1024) return null   // not a card's picture
                }
                return out.toByteArray()
            }
        } finally { c.disconnect() }
    }
}

// ---------------------------------------------------------------- small helpers

private fun JSONObject?.f(k: String, d: Float = 0f): Float {
    val v = this?.optDouble(k, d.toDouble()) ?: return d
    return if (v.isNaN() || v.isInfinite()) d else v.toFloat()
}

private fun JSONObject?.s(k: String): String? = if (this == null || isNull(k)) null else optString(k).takeIf { it.isNotEmpty() }

private fun JSONArray.n(i: Int): Int = optDouble(i, 0.0).let { if (it.isNaN() || it.isInfinite()) 0 else it.roundToInt() }

private fun JSONObject.rect(k: String): Rect? {
    val a = optJSONArray(k) ?: return null
    if (a.length() < 4) return null
    val x = a.n(0); val y = a.n(1)
    return Rect(x, y, x + max(0, a.n(2)), y + max(0, a.n(3)))
}

private fun JSONArray?.objects(): List<JSONObject> {
    if (this == null) return emptyList()
    val out = ArrayList<JSONObject>(length())
    for (i in 0 until length()) optJSONObject(i)?.let { out.add(it) }
    return out
}

/** A CSS colour as the page's getComputedStyle gives it: rgb(), rgba(), #rrggbb or transparent. */
private fun colour(s: String?, d: Int): Int {
    val t = s?.trim() ?: return d
    if (t.isEmpty()) return d
    if (t == "transparent") return Color.TRANSPARENT
    if (t.startsWith("#")) return runCatching { Color.parseColor(t) }.getOrDefault(d)
    val open = t.indexOf('('); val close = t.lastIndexOf(')')
    if (open < 0 || close < open) return d
    val p = t.substring(open + 1, close).split(',', ' ', '/').filter { it.isNotBlank() }
    if (p.size < 3) return d
    return runCatching {
        fun ch(v: String) = (if (v.endsWith("%")) v.dropLast(1).toFloat() * 2.55f else v.toFloat()).roundToInt().coerceIn(0, 255)
        val a = if (p.size > 3) p[3].let { if (it.endsWith("%")) it.dropLast(1).toFloat() / 100f else it.toFloat() } else 1f
        Color.argb((a * 255).roundToInt().coerceIn(0, 255), ch(p[0]), ch(p[1]), ch(p[2]))
    }.getOrDefault(d)
}

private val faces = SparseArray<Typeface>()

/** The system sans at the page's weight (Inter is a woff2 Android cannot load; the boxes come from the page). */
private fun weightFace(weight: Int): Typeface {
    faces.get(weight)?.let { return it }
    val tf = if (Build.VERSION.SDK_INT >= 28) Typeface.create(Typeface.SANS_SERIF, weight.coerceIn(1, 1000), false)
    else if (weight >= 600) Typeface.DEFAULT_BOLD
    else if (weight >= 500) Typeface.create("sans-serif-medium", Typeface.NORMAL)
    else Typeface.SANS_SERIF
    faces.put(weight, tf)
    return tf
}

private fun styleText(v: TextView, s: TilesLayer.Text) {
    v.setTextSize(TypedValue.COMPLEX_UNIT_PX, s.size)
    v.setTextColor(s.color)
    v.typeface = weightFace(s.weight)
    v.letterSpacing = if (s.size > 0f) s.ls / s.size else 0f
    v.includeFontPadding = false
}

private fun cased(s: String, st: TilesLayer.Text) = if (st.upper) s.uppercase(Locale.getDefault()) else s

private fun wordLayout(s: String, st: TilesLayer.Text, w: Int): StaticLayout {
    val p = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = st.size; color = st.color; typeface = weightFace(st.weight)
        letterSpacing = if (st.size > 0f) st.ls / st.size else 0f
    }
    val width = max(1, (w - 2 * st.pad).roundToInt())
    return StaticLayout.Builder.obtain(s, 0, s.length, p, width).setAlignment(Layout.Alignment.ALIGN_CENTER).setIncludePad(false).build()
}

private fun rounded(colour: Int, radius: Float) = GradientDrawable().apply { setColor(colour); cornerRadius = radius }

private fun stroke(width: Int, colour: Int, radius: Float) = GradientDrawable().apply {
    setColor(Color.TRANSPARENT); setStroke(width, colour); cornerRadius = radius
}

private fun oval(colour: Int) = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(colour) }

/** Scale, lift and shadow: only render-node properties, from wherever they are now, so fast repeats stay smooth. */
private fun moveTo(v: View, animate: Boolean, dur: Long, s: Float, ty: Float, z: Float) {
    if (animate) v.animate().scaleX(s).scaleY(s).translationY(ty).translationZ(z).setDuration(dur).setInterpolator(TilesLayer.EASE).start()
    else { v.animate().cancel(); v.scaleX = s; v.scaleY = s; v.translationY = ty; v.translationZ = z }
}

private fun fadeTo(v: View, animate: Boolean, dur: Long, a: Float) {
    if (animate) v.animate().alpha(a).setDuration(dur).setInterpolator(TilesLayer.EASE).start()
    else { v.animate().cancel(); v.alpha = a }
}
