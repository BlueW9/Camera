package com.bluew9.camera

import android.content.ContentValues
import android.content.Context
import android.graphics.*
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.widget.SeekBar
import androidx.exifinterface.media.ExifInterface
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.ByteBufferExtractor
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.imagesegmenter.ImageSegmenter
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.*

object Util {

  // Decode a picked image (no storage permission needed), fix its rotation, cap its longest side.
  fun load(ctx: Context, uri: Uri, max: Int): Bitmap? {
    val cr = ctx.contentResolver
    val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o) }
    if (o.outWidth <= 0 || o.outHeight <= 0) return null
    var s = 1
    while (maxOf(o.outWidth, o.outHeight) / (s * 2) >= max) s *= 2
    val d = BitmapFactory.Options().apply { inSampleSize = s; inPreferredConfig = Bitmap.Config.ARGB_8888 }
    var b = cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, d) } ?: return null
    val side = maxOf(b.width, b.height)
    if (side > max) {
      val f = max / side.toFloat()
      b = Bitmap.createScaledBitmap(b, maxOf(1, (b.width * f).toInt()), maxOf(1, (b.height * f).toInt()), true)
    }
    val ori = try {
      cr.openInputStream(uri)?.use {
        ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
      } ?: ExifInterface.ORIENTATION_NORMAL
    } catch (_: Exception) { ExifInterface.ORIENTATION_NORMAL }
    val deg = when (ori) { ExifInterface.ORIENTATION_ROTATE_90 -> 90f; ExifInterface.ORIENTATION_ROTATE_180 -> 180f
      ExifInterface.ORIENTATION_ROTATE_270 -> 270f; else -> 0f }
    if (deg != 0f) {
      val mx = Matrix().apply { postRotate(deg) }
      b = Bitmap.createBitmap(b, 0, 0, b.width, b.height, mx, true)
    }
    return b
  }

  // Save as a NEW jpeg in Pictures/Camera. Re-encoding drops all metadata (location, device...).
  fun save(ctx: Context, bmp: Bitmap, prefix: String): Boolean {
    val name = prefix + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
    val v = ContentValues().apply {
      put(MediaStore.MediaColumns.DISPLAY_NAME, name); put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
      if (Build.VERSION.SDK_INT >= 29) put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/Camera")
    }
    val uri = ctx.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v) ?: return false
    return try {
      ctx.contentResolver.openOutputStream(uri)?.use { bmp.compress(Bitmap.CompressFormat.JPEG, 95, it) } ?: false
    } catch (_: Exception) { false }
  }

  // Strong blur: shrink, 3x box blur, enlarge (cheap and smooth).
  fun blur(src: Bitmap, strength: Int): Bitmap {
    val w = src.width; val h = src.height
    val k = maxOf(1, (maxOf(w, h) + 479) / 480)
    val sw = maxOf(1, w / k); val sh = maxOf(1, h / k)
    val small = Bitmap.createScaledBitmap(src, sw, sh, true)
    val px = IntArray(sw * sh); small.getPixels(px, 0, sw, 0, 0, sw, sh)
    boxBlur(px, sw, sh, strength)
    val b = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888); b.setPixels(px, 0, sw, 0, 0, sw, sh)
    return Bitmap.createScaledBitmap(b, w, h, true)
  }

  private fun boxBlur(px: IntArray, w: Int, h: Int, r: Int) {
    if (r < 1) return
    val tmp = IntArray(px.size)
    repeat(3) { pass(px, tmp, w, h, r, true); pass(tmp, px, w, h, r, false) }
  }

  private fun pass(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int, horiz: Boolean) {
    val len = if (horiz) w else h
    val lines = if (horiz) h else w
    val step = if (horiz) 1 else w
    val win = 2 * r + 1
    for (l in 0 until lines) {
      val base = if (horiz) l * w else l
      var sa = 0; var sr = 0; var sg = 0; var sb = 0
      for (i in -r..r) {
        val c = src[base + i.coerceIn(0, len - 1) * step]
        sa += c ushr 24; sr += (c shr 16) and 255; sg += (c shr 8) and 255; sb += c and 255
      }
      for (i in 0 until len) {
        dst[base + i * step] = ((sa / win) shl 24) or ((sr / win) shl 16) or ((sg / win) shl 8) or (sb / win)
        val add = src[base + (i + r + 1).coerceAtMost(len - 1) * step]
        val rem = src[base + (i - r).coerceAtLeast(0) * step]
        sa += (add ushr 24) - (rem ushr 24)
        sr += ((add shr 16) and 255) - ((rem shr 16) and 255)
        sg += ((add shr 8) and 255) - ((rem shr 8) and 255)
        sb += (add and 255) - (rem and 255)
      }
    }
  }

  // Final full-resolution result. dstOut=false: blur only where the mask is painted.
  // dstOut=true: blur everywhere EXCEPT where the mask is painted (the person stays sharp).
  fun compose(orig: Bitmap, blurred: Bitmap, mask: Bitmap, dstOut: Boolean): Bitmap {
    val out = orig.copy(Bitmap.Config.ARGB_8888, true)
    val w = out.width.toFloat(); val h = out.height.toFloat()
    val c = Canvas(out)
    val sc = c.saveLayer(0f, 0f, w, h, null)
    c.drawBitmap(blurred, 0f, 0f, null)
    val p = Paint(Paint.FILTER_BITMAP_FLAG)
    p.xfermode = PorterDuffXfermode(if (dstOut) PorterDuff.Mode.DST_OUT else PorterDuff.Mode.DST_IN)
    c.drawBitmap(mask, null, RectF(0f, 0f, w, h), p)
    c.restoreToCount(sc)
    return out
  }

  // On-device person segmentation (bundled model, no network). Returns a mask whose alpha = "person".
  fun segment(ctx: Context, bmp: Bitmap): Bitmap {
    val f = 1024f / maxOf(bmp.width, bmp.height)
    val small = if (f < 1f) Bitmap.createScaledBitmap(bmp, maxOf(1, (bmp.width * f).toInt()), maxOf(1, (bmp.height * f).toInt()), true) else bmp
    val base = BaseOptions.builder().setModelAssetPath("selfie_segmenter.tflite").build()
    val opt = ImageSegmenter.ImageSegmenterOptions.builder()
      .setBaseOptions(base).setRunningMode(RunningMode.IMAGE)
      .setOutputConfidenceMasks(true).setOutputCategoryMask(false).build()
    val seg = ImageSegmenter.createFromOptions(ctx, opt)
    try {
      val res = seg.segment(BitmapImageBuilder(small).build())
      val list = res.confidenceMasks().get()
      val m = list[list.size - 1]
      val w = m.width; val h = m.height
      val bb = ByteBufferExtractor.extract(m)
      bb.rewind()
      val fb = bb.order(ByteOrder.nativeOrder()).asFloatBuffer()
      val v = FloatArray(w * h) { fb.get(it) }
      // Guess which side is the person: in a portrait the centre is the person, the border is not.
      var cs = 0.0; var cn = 0; var bs = 0.0; var bn = 0
      val mx = w / 10; val my = h / 10
      for (y in 0 until h) for (x in 0 until w) {
        if (x in w / 4 until 3 * w / 4 && y in h / 4 until 3 * h / 4) { cs += v[y * w + x]; cn++ }
        else if (x < mx || x >= w - mx || y < my || y >= h - my) { bs += v[y * w + x]; bn++ }
      }
      val invert = cn > 0 && bn > 0 && bs / bn > cs / cn
      val px = IntArray(w * h) {
        var a = if (invert) 1f - v[it] else v[it]
        a = ((a - 0.3f) / 0.4f).coerceIn(0f, 1f)
        ((a * 255f).toInt() shl 24) or 0xFFFFFF
      }
      val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
      out.setPixels(px, 0, w, 0, 0, w, h)
      return out
    } finally { seg.close() }
  }

  fun invertMask(k: Bitmap): Bitmap {
    val w = k.width; val h = k.height
    val px = IntArray(w * h); k.getPixels(px, 0, w, 0, 0, w, h)
    for (i in px.indices) px[i] = ((255 - (px[i] ushr 24)) shl 24) or 0xFFFFFF
    val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    out.setPixels(px, 0, w, 0, 0, w, h)
    return out
  }

  fun seek(ctx: Context, max: Int, p: Int, onChange: (Int) -> Unit, onDone: (Int) -> Unit): SeekBar =
    SeekBar(ctx).apply {
      this.max = max; progress = p
      setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(s: SeekBar?, v: Int, u: Boolean) { onChange(v) }
        override fun onStartTrackingTouch(s: SeekBar?) {}
        override fun onStopTrackingTouch(s: SeekBar?) { onDone(progress) }
      })
    }
}
