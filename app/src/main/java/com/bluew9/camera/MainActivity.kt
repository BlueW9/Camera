package com.bluew9.camera
import android.Manifest.permission.*
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.*
import android.provider.MediaStore
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.*
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.*
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.exifinterface.media.ExifInterface
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {
  lateinit var pv: PreviewView
  lateinit var shutter: Button
  var ic: ImageCapture? = null
  var vc: VideoCapture<Recorder>? = null
  var rec: Recording? = null
  var cam: Camera? = null
  lateinit var flashBtn: Button
  var front = false; var video = false; var useLoc = false; var delay = 0; var flash = 0

  override fun onCreate(b: Bundle?) {
    super.onCreate(b)
    val root = FrameLayout(this); root.setBackgroundColor(0xFF120B1F.toInt())
    pv = PreviewView(this); root.addView(pv, MATCH_PARENT, MATCH_PARENT)
    val bar = LinearLayout(this).apply {
      gravity = Gravity.CENTER; setBackgroundColor(0xCC120B1F.toInt()); setPadding(0, 24, 0, 96) }
    val top = LinearLayout(this).apply {
      gravity = Gravity.CENTER; setBackgroundColor(0xCC120B1F.toInt()); setPadding(0, 16, 0, 16) }
    fun mk(t: String, to: LinearLayout, f: (Button) -> Unit) = Button(this).apply {
      text = t; setTextColor(0xFFC9B1FF.toInt()); setBackgroundColor(0); setOnClickListener { f(this) }
    }.also { to.addView(it, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)) }
    flashBtn = mk("Flash off", top) { flash = (flash + 1) % 3; applyFlash() }
    mk("Timer 0", top) { delay = when (delay) { 0 -> 3; 3 -> 5; 5 -> 10; else -> 0 }; it.text = "Timer $delay" }
    mk("Loc off", top) { if (!useLoc) askLoc(); useLoc = !useLoc; it.text = if (useLoc) "Loc on" else "Loc off" }
    mk("Tools", top) {
      androidx.appcompat.app.AlertDialog.Builder(this@MainActivity)
        .setItems(arrayOf("Blur photo", "Isolate background")) { _, i ->
          startActivity(android.content.Intent(this@MainActivity, EditorActivity::class.java)
            .putExtra("mode", if (i == 0) "blur" else "isolate"))
        }.show()
    }
    mk("Photo", bar) { it.text = if (video) "Photo" else "Video"; video = !video; bind() }
    shutter = mk("●", bar) { go() }.apply { textSize = 34f; setTextColor(0xFF9B6DFF.toInt()) }
    mk("Flip", bar) { front = !front; bind() }
    root.addView(top, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.TOP))
    root.addView(bar, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.BOTTOM))
    setContentView(root)
    if (ContextCompat.checkSelfPermission(this, CAMERA) == PackageManager.PERMISSION_GRANTED) bind()
    else ActivityCompat.requestPermissions(this, arrayOf(CAMERA, RECORD_AUDIO), 1)
  }

  override fun onRequestPermissionsResult(c: Int, p: Array<out String>, r: IntArray) {
    super.onRequestPermissionsResult(c, p, r)
    if (c == 1 && r.isNotEmpty() && r[0] == PackageManager.PERMISSION_GRANTED) bind()
  }

  fun askLoc() { ActivityCompat.requestPermissions(this, arrayOf(ACCESS_FINE_LOCATION), 2) }

  @SuppressLint("MissingPermission")
  fun loc(): Location? {
    if (!useLoc || ContextCompat.checkSelfPermission(this, ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return null
    val lm = getSystemService(LOCATION_SERVICE) as LocationManager
    return lm.getProviders(true).mapNotNull { lm.getLastKnownLocation(it) }.maxByOrNull { it.time }
  }

  fun bind() {
    val f = ProcessCameraProvider.getInstance(this)
    f.addListener({
      val p = f.get(); p.unbindAll()
      val pre = Preview.Builder().build().also { it.setSurfaceProvider(pv.surfaceProvider) }
      val sel = if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
      if (video) {
        val r = Recorder.Builder().setQualitySelector(QualitySelector.fromOrderedList(
          listOf(Quality.UHD, Quality.FHD, Quality.HD), FallbackStrategy.lowerQualityOrHigherThan(Quality.SD))).build()
        vc = VideoCapture.withOutput(r); cam = p.bindToLifecycle(this, sel, pre, vc)
      } else {
        ic = ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY).build()
        cam = p.bindToLifecycle(this, sel, pre, ic)
      }
      applyFlash()
    }, ContextCompat.getMainExecutor(this))
  }

  // Flash tool: photo = off / auto / on (fires with the shot). Video = off / on (torch stays lit).
  fun applyFlash() {
    val c = cam
    if (c == null || !c.cameraInfo.hasFlashUnit()) { flashBtn.text = "No flash"; return }
    flashBtn.text = arrayOf("Flash off", "Flash auto", "Flash on")[flash]
    if (video) c.cameraControl.enableTorch(flash == 2)
    else {
      c.cameraControl.enableTorch(false)
      ic?.flashMode = when (flash) { 1 -> ImageCapture.FLASH_MODE_AUTO; 2 -> ImageCapture.FLASH_MODE_ON; else -> ImageCapture.FLASH_MODE_OFF }
    }
  }

  fun go() {
    if (rec != null) { rec?.stop(); rec = null; shutter.text = "●"; return }
    if (delay == 0) fire() else object : CountDownTimer(delay * 1000L, 1000) {
      override fun onTick(m: Long) { shutter.text = "${m / 1000 + 1}" }
      override fun onFinish() { shutter.text = "●"; fire() }
    }.start()
  }

  fun cv(prefix: String, mime: String, dir: String): ContentValues {
    val n = prefix + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
    return ContentValues().apply {
      put(MediaStore.MediaColumns.DISPLAY_NAME, n); put(MediaStore.MediaColumns.MIME_TYPE, mime)
      if (Build.VERSION.SDK_INT >= 29) put(MediaStore.MediaColumns.RELATIVE_PATH, "$dir/Camera")
    }
  }

  @SuppressLint("MissingPermission")
  fun fire() {
    val ex = ContextCompat.getMainExecutor(this)
    if (video) {
      val o = MediaStoreOutputOptions.Builder(contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
        .setContentValues(cv("VID_", "video/mp4", "Movies")).apply { loc()?.let { setLocation(it) } }.build()
      var pr = vc!!.output.prepareRecording(this, o)
      if (ContextCompat.checkSelfPermission(this, RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) pr = pr.withAudioEnabled()
      rec = pr.start(ex) {}; shutter.text = "■"
    } else {
      val o = ImageCapture.OutputFileOptions.Builder(contentResolver,
        MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv("IMG_", "image/jpeg", "Pictures")).build()
      ic!!.takePicture(o, ex, object : ImageCapture.OnImageSavedCallback {
        override fun onImageSaved(r: ImageCapture.OutputFileResults) { r.savedUri?.let { tag(it) } }
        override fun onError(e: ImageCaptureException) { Toast.makeText(this@MainActivity, "Error", Toast.LENGTH_SHORT).show() }
      })
    }
  }

  fun tag(u: android.net.Uri) {
    try {
      contentResolver.openFileDescriptor(u, "rw")?.use { fd ->
        val e = ExifInterface(fd.fileDescriptor)
        e.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).format(Date()))
        e.setAttribute(ExifInterface.TAG_MAKE, Build.MANUFACTURER)
        e.setAttribute(ExifInterface.TAG_MODEL, Build.MODEL)
        loc()?.let { e.setGpsInfo(it) }
        e.saveAttributes()
      }
    } catch (_: Exception) {}
  }
}
