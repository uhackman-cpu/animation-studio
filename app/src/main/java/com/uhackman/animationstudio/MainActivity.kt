package com.uhackman.animationstudio

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class MainActivity : Activity() {
    private lateinit var controller: EditorController
    private lateinit var stage: StageCanvasView
    private lateinit var server: EmbeddedMcpServer
    private lateinit var status: TextView
    private val handler = Handler(Looper.getMainLooper())

    private val playbackTick = object : Runnable {
        override fun run() {
            if (controller.isPlaying()) {
                controller.advanceFrame()
                val delay = 1000L / controller.state().optInt("fps", 30).coerceIn(1, 120)
                handler.postDelayed(this, delay)
            }
        }
    }

    private fun label(text: String, size: Float = 13f, background: Int = 0xFF30394A.toInt()): TextView =
        TextView(this).apply {
            this.text = text
            textSize = size
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(14, 8, 14, 8)
            setBackgroundColor(background)
            isClickable = true
            isFocusable = true
        }

    private fun action(text: String, command: () -> Unit): TextView =
        label(text).apply { setOnClickListener { command() } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(18, 20, 27)
        window.navigationBarColor = Color.rgb(18, 20, 27)

        controller = EditorController(this) {
            runOnUiThread {
                if (::stage.isInitialized) stage.invalidate()
                if (::status.isInitialized) updateStatus()
                if (::controller.isInitialized) {
                    if (controller.isPlaying()) {
                        handler.removeCallbacks(playbackTick)
                        handler.post(playbackTick)
                    } else {
                        handler.removeCallbacks(playbackTick)
                    }
                }
            }
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(24, 27, 36))
        }
        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.rgb(35, 39, 51))
        }
        toolbar.addView(label("ANIMATION STUDIO", 15f, 0xFF202633.toInt()))
        toolbar.addView(action("1920 × 1080") {
            controller.execute("create_project", JSONObject().put("name", "Untitled Animation")
                .put("width", 1920).put("height", 1080))
        })
        toolbar.addView(action("Select") { controller.setTool("select") })
        toolbar.addView(action("Rect") { controller.setTool("rect") })
        toolbar.addView(action("Line") { controller.setTool("line") })
        toolbar.addView(action("+ Layer") { controller.execute("add_layer", JSONObject()) })
        toolbar.addView(action("+ Circle") {
            controller.execute("add_shape", JSONObject().put("type", "ellipse")
                .put("x", 700).put("y", 300).put("width", 240).put("height", 240).put("color", "#39BDF8"))
        })
        toolbar.addView(action("+ Text") {
            controller.execute("add_shape", JSONObject().put("type", "text")
                .put("text", "Your title").put("x", 360).put("y", 300)
                .put("fontSize", 88).put("color", "#FFFFFF"))
        })
        toolbar.addView(action("Undo") { controller.execute("undo") })
        toolbar.addView(action("Redo") { controller.execute("redo") })
        root.addView(toolbar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 52))

        val editor = FrameLayout(this)
        editor.addView(AnimationSurface(this), FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        stage = StageCanvasView(this, controller)
        editor.addView(stage, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        root.addView(editor, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        val timeline = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.rgb(35, 39, 51))
        }
        timeline.addView(label("TIMELINE", 12f, 0xFF202633.toInt()))
        timeline.addView(action("▶ Play") { controller.execute("play") })
        timeline.addView(action("■ Stop") { controller.execute("stop") })
        timeline.addView(action("◀") {
            val frame = (controller.state().optInt("currentFrame") - 1).coerceAtLeast(0)
            controller.execute("set_frame", JSONObject().put("frame", frame))
        })
        timeline.addView(action("▶|") {
            controller.execute("set_frame", JSONObject().put("frame", controller.state().optInt("currentFrame") + 1))
        })
        timeline.addView(action("+ Key") {
            val state = controller.state()
            val firstLayer = state.optJSONArray("layers")?.optJSONObject(0)
            val firstObject = firstLayer?.optJSONArray("objects")?.optJSONObject(0)
            if (firstObject != null) {
                controller.execute("add_keyframe", JSONObject()
                    .put("objectId", firstObject.optString("id"))
                    .put("property", "x").put("frame", state.optInt("currentFrame"))
                    .put("value", firstObject.optDouble("x")))
            }
        })
        status = label("MCP :8765", 11f, 0xFF202633.toInt())
        timeline.addView(status, LinearLayout.LayoutParams(0, 48, 1f))
        root.addView(timeline, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 58))
        setContentView(root)

        server = EmbeddedMcpServer(controller) { captureStageOnUiThread() }
        server.start()
        updateStatus()
    }

    private fun captureStageOnUiThread(): String? {
        if (!::stage.isInitialized || stage.width <= 0 || stage.height <= 0) return null
        if (Looper.myLooper() == Looper.getMainLooper()) return stage.captureBase64()
        var result: String? = null
        val latch = CountDownLatch(1)
        runOnUiThread {
            try { result = if (stage.width > 0 && stage.height > 0) stage.captureBase64() else null }
            finally { latch.countDown() }
        }
        latch.await(5, TimeUnit.SECONDS)
        return result
    }

    private fun updateStatus() {
        val state = controller.state()
        status.text = "MCP :8765  •  Frame " + state.optInt("currentFrame") +
            " / " + state.optInt("durationFrames")
    }

    override fun onDestroy() {
        if (::server.isInitialized) server.stop()
        handler.removeCallbacks(playbackTick)
        super.onDestroy()
    }
}
