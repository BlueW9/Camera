package com.bluew9.camera

import android.content.Context
import android.graphics.*
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

// Shows the photo, a blurred copy and a mask. Painting edits the mask.
class EditorView(c: Context) : View(c) {
  var orig: Bitmap? = null
  var blurred: Bitmap? = null
  var mask: Bitmap? = null
  var dstOut = false
  var erase = false
  var brush = 40f
  private val m = Matrix()
  private val inv = Matrix()
  private val pBase = Paint(Paint.FILTER_BITMAP_FLAG)
  private val pMask = Paint(Paint.FILTER_BITMAP_FLAG)
  private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; color = Color.WHITE }
  private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = Color.WHITE }
  private val clear = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
  private var lx = 0f
  private var ly = 0f

  fun set(o: Bitmap, b: Bitmap, k: Bitmap) { orig = o; blurred = b; mask = k; fit(); invalidate() }

  private fun fit() {
    val o = orig ?: return
    if (width == 0 || height == 0) return
    val s = minOf(width / o.width.toFloat(), height / o.height.toFloat())
    m.reset(); m.postScale(s, s)
    m.postTranslate((width - o.width * s) / 2f, (height - o.height * s) / 2f)
    m.invert(inv)
  }

  override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) { super.onSizeChanged(w, h, ow, oh); fit() }

  override fun onDraw(canvas: Canvas) {
    val o = orig ?: return
    val ow = o.width.toFloat(); val oh = o.height.toFloat()
    canvas.save(); canvas.concat(m)
    canvas.drawBitmap(o, 0f, 0f, pBase)
    val b = blurred; val k = mask
    if (b != null && k != null) {
      val sc = canvas.saveLayer(0f, 0f, ow, oh, null)
      canvas.drawBitmap(b, 0f, 0f, pBase)
      pMask.xfermode = PorterDuffXfermode(if (dstOut) PorterDuff.Mode.DST_OUT else PorterDuff.Mode.DST_IN)
      canvas.drawBitmap(k, null, RectF(0f, 0f, ow, oh), pMask)
      canvas.restoreToCount(sc)
    }
    canvas.restore()
  }

  private fun prep(w: Float) {
    val mf = BlurMaskFilter(maxOf(1f, w * 0.2f), BlurMaskFilter.Blur.NORMAL)
    stroke.strokeWidth = w; stroke.maskFilter = mf; fill.maskFilter = mf
    val xf = if (erase) clear else null
    stroke.xfermode = xf; fill.xfermode = xf
  }

  override fun onTouchEvent(e: MotionEvent): Boolean {
    val o = orig ?: return false
    val k = mask ?: return false
    val pt = floatArrayOf(e.x, e.y); inv.mapPoints(pt)
    val kx = k.width / o.width.toFloat()
    val x = pt[0] * kx; val y = pt[1] * kx
    val v = FloatArray(9); m.getValues(v)
    val w = brush / v[Matrix.MSCALE_X] * kx
    val c = Canvas(k)
    when (e.actionMasked) {
      MotionEvent.ACTION_DOWN -> { prep(w); c.drawCircle(x, y, w / 2f, fill); lx = x; ly = y }
      MotionEvent.ACTION_MOVE -> { prep(w); c.drawLine(lx, ly, x, y, stroke); lx = x; ly = y }
      else -> return true
    }
    invalidate()
    return true
  }
}

// mode "blur": paint the parts of a picked photo you want blurred.
// mode "isolate": the person is detected on-device and kept sharp, the background is blurred.
class EditorActivity : AppCompatActivity() {
  lateinit var ev: EditorView
  lateinit var status: TextView
  var mode = "blur"
  var strength = 14
  var orig: Bitmap? = null

  val pick = registerForActivityResult(ActivityResultContracts.GetContent()) { u: Uri? ->
    if (u == null) finish() else openPhoto(u)
  }

  fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_LONG).show()

  override fun onCreate(b: Bundle?) {
    super.onCreate(b)
    mode = intent.getStringExtra("mode") ?: "blur"
    val d = resources.displayMetrics.density
    val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(0xFF120B1F.toInt()) }
    status = TextView(this).apply {
      setTextColor(0xFFC9B1FF.toInt()); gravity = Gravity.CENTER; setPadding(16, 20, 16, 20); text = "Choose a photo..." }
    ev = EditorView(this).apply { dstOut = mode != "blur"; brush = 50f * d }
    root.addView(status, MATCH_PARENT, WRAP_CONTENT)
    root.addView(ev, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))

    fun label(t: String) = TextView(this).apply { text = t; setTextColor(0xFFC9B1FF.toInt()); setPadding(0, 8, 0, 0) }
    val panel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 8, 32, 24) }
    panel.addView(label("Brush size"))
    panel.addView(Util.seek(this, 140, 40, { ev.brush = (10 + it) * d }, {}), MATCH_PARENT, WRAP_CONTENT)
    panel.addView(label("Blur strength"))
    panel.addView(Util.seek(this, 39, strength - 1, {}, { strength = it + 1; rebuild() }), MATCH_PARENT, WRAP_CONTENT)

    val row = LinearLayout(this).apply { gravity = Gravity.CENTER }
    fun btn(t: String, f: (Button) -> Unit) = Button(this).apply {
      text = t; setTextColor(0xFFC9B1FF.toInt()); setBackgroundColor(0); setOnClickListener { f(this) }
    }.also { row.addView(it, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)) }
    btn("Brush") { it.text = if (ev.erase) "Brush" else "Eraser"; ev.erase = !ev.erase }
    if (mode == "blur") btn("Clear") { ev.mask?.eraseColor(0); ev.invalidate() }
    else btn("Swap") { ev.mask?.let { k -> ev.mask = Util.invertMask(k); ev.invalidate() } }
    btn("Save") { save() }
    btn("Close") { finish() }
    panel.addView(row, MATCH_PARENT, WRAP_CONTENT)
    root.addView(panel, MATCH_PARENT, WRAP_CONTENT)
    setContentView(root)
    if (b == null) pick.launch("image/*")
  }

  fun openPhoto(uri: Uri) {
    status.text = if (mode == "blur") "Loading..." else "Finding the person..."
    Thread {
      val bmp = Util.load(this, uri, 3072)
      if (bmp == null) { runOnUiThread { toast("Cannot open this image"); finish() }; return@Thread }
      val bl = Util.blur(bmp, strength)
      val f = minOf(1f, 1024f / maxOf(bmp.width, bmp.height))
      val mw = maxOf(1, (bmp.width * f).toInt()); val mh = maxOf(1, (bmp.height * f).toInt())
      val mk: Bitmap = try {
        if (mode == "blur") Bitmap.createBitmap(mw, mh, Bitmap.Config.ARGB_8888)
        else Bitmap.createScaledBitmap(Util.segment(this, bmp), mw, mh, true).copy(Bitmap.Config.ARGB_8888, true)
      } catch (t: Throwable) {
        runOnUiThread { toast("Isolation failed: ${t.message}"); finish() }; return@Thread
      }
      runOnUiThread {
        orig = bmp; ev.set(bmp, bl, mk)
        status.text = if (mode == "blur") "Paint over what you want blurred"
        else "Person kept sharp. Paint to fix edges, or tap Swap if reversed"
      }
    }.start()
  }

  fun rebuild() {
    val o = orig ?: return
    Thread { val bl = Util.blur(o, strength); runOnUiThread { ev.blurred = bl; ev.invalidate() } }.start()
  }

  fun save() {
    val o = orig ?: return
    val bl = ev.blurred ?: return
    val k = ev.mask ?: return
    toast("Saving...")
    Thread {
      val out = Util.compose(o, bl, k, ev.dstOut)
      val ok = Util.save(this, out, if (mode == "blur") "BLUR_" else "ISO_")
      out.recycle()
      runOnUiThread { toast(if (ok) "Saved to Pictures/Camera as a new photo" else "Save failed") }
    }.start()
  }
}
