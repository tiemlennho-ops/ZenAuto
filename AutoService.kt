package com.zen.auto

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.PointF
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.Button
import android.widget.Toast
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs

class AutoService : AccessibilityService() {
    @Volatile private var running = false
    @Volatile private var gen = 0
    private lateinit var btn: Button

    override fun onAccessibilityEvent(e: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onServiceConnected() {
        btn = Button(this).apply { text = "▶ START"; setOnClickListener { toggle() } }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.BOTTOM or Gravity.END; y = 30 }
        (getSystemService(WINDOW_SERVICE) as WindowManager).addView(btn, lp)
    }

    private fun toast(s: String) = Handler(Looper.getMainLooper()).post { Toast.makeText(this, s, Toast.LENGTH_LONG).show() }
    private fun alive(g: Int) = running && gen == g

    private fun toggle() {
        running = !running
        btn.text = if (running) "■ STOP" else "▶ START"
        if (running) { val g = ++gen; Thread { loop(g) }.start() }
    }

    // ---------- chụp màn hình / chạm ----------
    private fun shot(): Bitmap? {
        repeat(3) {
            Thread.sleep(450)
            val latch = CountDownLatch(1); var bmp: Bitmap? = null
            takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
                override fun onSuccess(r: ScreenshotResult) {
                    bmp = Bitmap.wrapHardwareBuffer(r.hardwareBuffer, r.colorSpace)?.copy(Bitmap.Config.ARGB_8888, false)
                    r.hardwareBuffer.close(); latch.countDown()
                }
                override fun onFailure(code: Int) { latch.countDown() }
            })
            latch.await(3, TimeUnit.SECONDS)
            if (bmp != null) return bmp
        }
        return null
    }

    private fun gesture(paths: List<Path>, dur: Long) {
        val b = GestureDescription.Builder()
        paths.forEach { b.addStroke(GestureDescription.StrokeDescription(it, 0, dur)) }
        val latch = CountDownLatch(1)
        dispatchGesture(b.build(), object : GestureResultCallback() {
            override fun onCompleted(g: GestureDescription?) { latch.countDown() }
            override fun onCancelled(g: GestureDescription?) { latch.countDown() }
        }, null)
        latch.await(4, TimeUnit.SECONDS)
    }

    private fun tap(x: Float, y: Float) = gesture(listOf(Path().apply { moveTo(x, y) }), 50)

    // kéo 2 ngón: nội dung dịch (dx*W, dy*H)
    private fun pan(w: Int, h: Int, dx: Float, dy: Float) {
        val sx = 0.5f * w - dx * w / 2; val sy = 0.48f * h - dy * h / 2
        val ex = sx + dx * w; val ey = sy + dy * h
        val off = 0.06f * w
        gesture(listOf(
            Path().apply { moveTo(sx - off, sy); lineTo(ex - off, ey) },
            Path().apply { moveTo(sx + off, sy); lineTo(ex + off, ey) }), 450)
    }

    // ---------- nhận diện ----------
    // Tìm vùng có caro (ô xám xanh 187,186,191), trả về 1 điểm chạm cho mỗi vùng
    private fun taps(b: Bitmap): List<PointF> {
        val w = b.width; val h = b.height; val y0 = (0.13f * h).toInt(); val cs = 12
        val gw = w / cs; val gh = (0.70f * h).toInt() / cs
        val cnt = IntArray(gw * gh); val row = IntArray(w)
        for (gy in 0 until gh) for (yy in 0 until cs) {
            b.getPixels(row, 0, w, 0, y0 + gy * cs + yy, w, 1)
            for (x in 0 until gw * cs) {
                val c = row[x]; val r = c shr 16 and 255; val g = c shr 8 and 255; val bl = c and 255
                if (r in 180..194 && abs(r - g) <= 3 && bl - r in 2..8) cnt[gy * gw + x / cs]++
            }
        }
        val seen = BooleanArray(gw * gh); val out = ArrayList<PointF>()
        for (s in cnt.indices) {
            if (cnt[s] < 12 || seen[s]) continue
            val comp = ArrayList<Int>(); val q = ArrayDeque<Int>(); q.add(s); seen[s] = true
            while (q.isNotEmpty()) {
                val c = q.removeFirst(); comp.add(c)
                for (dy in -2..2) for (dx in -2..2) {
                    val nx = c % gw + dx; val ny = c / gw + dy
                    if (nx < 0 || ny < 0 || nx >= gw || ny >= gh) continue
                    val n = ny * gw + nx
                    if (!seen[n] && cnt[n] >= 12) { seen[n] = true; q.add(n) }
                }
            }
            val mx = comp.map { it % gw }.average(); val my = comp.map { it / gw }.average()
            val best = comp.minByOrNull { (it % gw - mx) * (it % gw - mx) + (it / gw - my) * (it / gw - my) }!!
            out.add(PointF((best % gw * cs + cs / 2).toFloat(), (y0 + best / gw * cs + cs / 2).toFloat()))
        }
        return out
    }

    // 2 ảnh giống nhau (không dịch được nữa = chạm mép tranh)
    private fun same(a: Bitmap, b: Bitmap): Boolean {
        var d = 0L; var n = 0
        for (i in 1..50) for (j in 1..50) {
            val x = a.width * i / 51; val y = (a.height * (0.13f + 0.7f * j / 51)).toInt()
            d += abs((a.getPixel(x, y) shr 8 and 255) - (b.getPixel(x, y) shr 8 and 255)); n++
        }
        return d.toFloat() / n < 0.8f
    }

    private fun close(a: Int, b: Int) =
        abs((a shr 16 and 255) - (b shr 16 and 255)) + abs((a shr 8 and 255) - (b shr 8 and 255)) + abs((a and 255) - (b and 255)) < 20

    // chạm hết các vùng caro đang thấy; trả về số lần chạm
    private fun clearView(g: Int): Int {
        var total = 0; var last = -1
        repeat(6) {
            if (!alive(g)) return total
            val b = shot() ?: return total
            val pts = taps(b)
            if (pts.isEmpty() || (last >= 0 && pts.size >= last)) return total
            last = pts.size
            pts.forEach { tap(it.x, it.y); Thread.sleep(150) }
            total += pts.size
        }
        return total
    }

    // ---------- vòng lặp chính ----------
    private fun loop(g: Int) {
        var k = 0; var idle = 0; var dirX = 1; var vdir = -1
        val first = shot() ?: run { toast("Không chụp được màn hình (cần Android 11+)"); running = false; return }
        val w = first.width; val h = first.height
        repeat(6) { if (alive(g)) pan(w, h, 0.7f, 0.4f) }   // về góc trên-trái
        while (alive(g)) {
            val b0 = shot() ?: continue
            val cx = (0.116f + 0.148f * k) * w; val cy = 0.873f * h
            val c0 = b0.getPixel(cx.toInt(), cy.toInt())
            tap(cx, cy); Thread.sleep(700)
            dirX = -dirX
            var rows = 0
            while (alive(g) && rows < 15) {
                var cols = 0
                while (alive(g) && cols < 12) {
                    clearView(g)
                    val a = shot() ?: break
                    pan(w, h, dirX * 0.6f, 0f)
                    val b = shot() ?: break
                    if (same(a, b)) break
                    cols++
                }
                val a = shot() ?: break
                pan(w, h, 0f, vdir * 0.4f)
                val b = shot() ?: break
                if (same(a, b)) break
                dirX = -dirX; rows++
            }
            vdir = -vdir
            val b1 = shot() ?: continue
            if (close(c0, b1.getPixel(cx.toInt(), cy.toInt()))) { k = (k + 1) % 5; idle++ } else idle = 0
            if (idle >= 8) { toast("Xong hoặc bị kẹt, dừng."); running = false; Handler(Looper.getMainLooper()).post { btn.text = "▶ START" } }
        }
    }
}
