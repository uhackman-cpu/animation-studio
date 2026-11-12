package com.uhackman.animationstudio

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.FrameLayout

class MainActivity : Activity() {
    private fun label(text: String, size: Float = 13f): TextView =
        TextView(this).apply {
            this.text = text
            textSize = size
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(14, 8, 14, 8)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(18, 20, 27)
        window.navigationBarColor = Color.rgb(18, 20, 27)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(24, 27, 36))
        }
        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.rgb(35, 39, 51))
            addView(label("ANIMATION STUDIO", 15f))
            addView(label("1920 × 1080"))
            addView(label("Select"))
            addView(label("Draw"))
            addView(label("Layers"))
            addView(label("Preview"))
        }
        root.addView(toolbar, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 52
        ))

        val editor = FrameLayout(this)
        editor.addView(AnimationSurface(this), FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))
        val hint = label("CANVAS  •  16:9 LANDSCAPE", 11f).apply {
            setTextColor(Color.rgb(185, 195, 215))
            setBackgroundColor(0x66353B4B)
        }
        val hintParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.START
        )
        editor.addView(hint, hintParams)
        root.addView(editor, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        val timeline = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.rgb(35, 39, 51))
            addView(label("TIMELINE", 12f))
            addView(label("▶"))
            addView(label("＋"))
            addView(label("Frame  001"))
            addView(label("Layer 1"))
            addView(label("00:00 / 00:15"))
        }
        root.addView(timeline, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 58
        ))
        setContentView(root)
    }
}
