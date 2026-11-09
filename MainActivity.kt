package com.zen.auto

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {
    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        val t = TextView(this).apply {
            setPadding(48, 48, 48, 48)
            text = "1) Bật 'Zen Auto' trong Trợ năng.\n2) Mở Zen Color, chọn tranh, zoom vừa đủ đọc số.\n3) Bấm nút ▶ START ở góc dưới phải."
        }
        val b = Button(this).apply {
            text = "Mở cài đặt Trợ năng"
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        }
        setContentView(LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; addView(t); addView(b) })
    }
}
