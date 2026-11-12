package com.uhackman.animationstudio

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class MainActivity : Activity() {
    private lateinit var controller: EditorController
    private lateinit var stage: StageCanvasView
    private lateinit var server: EmbeddedMcpServer
    private lateinit var status: TextView
    private lateinit var frameSeek: SeekBar
    private lateinit var testPanel: LinearLayout
    private lateinit var layerPanel: LinearLayout
    private val handler = Handler(Looper.getMainLooper())
    private var playbackLoopActive = false
    private var updatingSeek = false

    private val playbackTick = object : Runnable {
        override fun run() {
            if (controller.isPlaying()) {
                controller.advanceFrame()
                val delay = 1000L / controller.state().optInt("fps", 30).coerceIn(1, 120)
                handler.postDelayed(this, delay)
            } else playbackLoopActive = false
        }
    }

    private fun label(text: String, size: Float = 13f, background: Int = 0xFF30394A.toInt()): TextView =
        TextView(this).apply {
            this.text = text; textSize = size; setTextColor(Color.WHITE)
            gravity = Gravity.CENTER; setPadding(13, 8, 13, 8)
            setBackgroundColor(background); isClickable = true; isFocusable = true
        }

    private fun action(text: String, command: () -> Unit): TextView =
        label(text).apply { setOnClickListener { command() } }

    private fun toolButton(name: String, tool: String): TextView =
        action(name) { controller.setTool(tool); updateStatus() }

    private fun row(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        setBackgroundColor(Color.rgb(35, 39, 51))
    }

    private fun palette(content: LinearLayout, height: Int = 50) {
        val scroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            setBackgroundColor(Color.rgb(35, 39, 51))
            addView(content, ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, height))
        }
        content.layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, height)
        rootContainer?.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, height))
    }

    private var rootContainer: LinearLayout? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(18, 20, 27)
        window.navigationBarColor = Color.rgb(18, 20, 27)

        controller = EditorController(this) {
            runOnUiThread {
                if (::stage.isInitialized) stage.invalidate()
                if (::status.isInitialized) updateStatus()
                if (::layerPanel.isInitialized) updateLayers()
                if (::controller.isInitialized) {
                    if (controller.isPlaying() && !playbackLoopActive) {
                        playbackLoopActive = true; handler.post(playbackTick)
                    } else if (!controller.isPlaying()) {
                        playbackLoopActive = false; handler.removeCallbacks(playbackTick)
                    }
                }
            }
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.rgb(24, 27, 36))
        }
        rootContainer = root

        val top = row()
        top.addView(label("ANIMATION STUDIO", 15f, 0xFF202633.toInt()))
        root.addView(top, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 44))

        val tools = row()
        tools.addView(toolButton("Select", "select"))
        tools.addView(toolButton("Rect", "rect"))
        tools.addView(toolButton("Line", "line"))
        tools.addView(toolButton("Brush", "brush"))
        tools.addView(toolButton("Draw", "draw"))
        tools.addView(toolButton("Eraser", "eraser"))
        tools.addView(action("Circle") {
            controller.execute("add_shape", JSONObject().put("type", "ellipse")
                .put("x", 700).put("y", 300).put("width", 240).put("height", 240).put("color", "#39BDF8"))
        })
        tools.addView(action("Text") { promptText() })
        tools.addView(action("+ Layer") { controller.execute("add_layer", JSONObject()) })
        tools.addView(action("Undo") { controller.execute("undo") })
        tools.addView(action("Redo") { controller.execute("redo") })
        palette(tools, 48)

        val fileBar = row()
        fileBar.addView(action("New") { newProjectDialog() })
        fileBar.addView(action("Save") { saveProjectDialog() })
        fileBar.addView(action("Load") { loadProjectDialog() })
        fileBar.addView(action("Self Test") { runTests() })
        fileBar.addView(action("Keyframe") { addKeyframeForFirstObject() })
        fileBar.addView(action("MCP API") { showApiInfo() })
        root.addView(fileBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 46))

        layerPanel = row()
        root.addView(layerPanel, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 42))
        updateLayers()

        val editor = FrameLayout(this)
        stage = StageCanvasView(this, controller)
        editor.addView(stage, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        root.addView(editor, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        testPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(12, 8, 12, 8)
            setBackgroundColor(0xFF182233.toInt()); visibility = View.GONE
        }
        root.addView(testPanel, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            (resources.displayMetrics.density * 160).toInt()))

        val timeline = row()
        timeline.addView(label("TIMELINE", 12f, 0xFF202633.toInt()))
        timeline.addView(action("▶") { controller.execute("play") })
        timeline.addView(action("■") { controller.execute("stop") })
        timeline.addView(action("◀") {
            controller.execute("set_frame", JSONObject().put("frame", controller.state().optInt("currentFrame") - 1))
        })
        timeline.addView(action("▶|") {
            controller.execute("set_frame", JSONObject().put("frame", controller.state().optInt("currentFrame") + 1))
        })
        frameSeek = SeekBar(this).apply {
            max = controller.state().optInt("durationFrames", 450).coerceAtLeast(1) - 1
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser && !updatingSeek) controller.execute("set_frame", JSONObject().put("frame", progress))
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) { controller.execute("stop") }
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }
        timeline.addView(frameSeek, LinearLayout.LayoutParams(0, 48, 1f))
        status = label("Frame 0", 11f, 0xFF202633.toInt())
        timeline.addView(status)
        root.addView(timeline, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 56))

        setContentView(root)
        server = EmbeddedMcpServer(controller) { captureStageOnUiThread() }
        server.start()
        restoreAutosave()
        updateStatus()
        updateLayers()
    }

    private fun updateLayers() {
        if (!::layerPanel.isInitialized) return
        layerPanel.removeAllViews()
        layerPanel.addView(label("LAYERS", 11f, 0xFF202633.toInt()))
        val state = controller.state()
        val layers = state.optJSONArray("layers") ?: return
        val active = state.optString("activeLayerId")
        for (i in 0 until layers.length()) {
            val layer = layers.optJSONObject(i) ?: continue
            val selected = layer.optString("id") == active
            val bg = if (selected) 0xFF176B87.toInt() else 0xFF30394A.toInt()
            val item = label((if (layer.optBoolean("visible", true)) "◉ " else "○ ") + layer.optString("name"), 11f, bg)
            item.setOnClickListener {
                controller.execute("set_active_layer", JSONObject().put("layerId", layer.optString("id")))
                updateLayers()
            }
            item.setOnLongClickListener {
                controller.execute("update_layer", JSONObject().put("layerId", layer.optString("id"))
                    .put("fields", JSONObject().put("visible", !layer.optBoolean("visible", true))))
                true
            }
            layerPanel.addView(item)
        }
    }

    private fun updateStatus() {
        val state = controller.state()
        status.text = "F " + state.optInt("currentFrame") + "/" + state.optInt("durationFrames") + " • " + state.optString("tool")
        if (::frameSeek.isInitialized) {
            updatingSeek = true
            frameSeek.max = state.optInt("durationFrames", 1).coerceAtLeast(1) - 1
            frameSeek.progress = state.optInt("currentFrame").coerceIn(0, frameSeek.max)
            updatingSeek = false
        }
    }

    private fun runTests() {
        testPanel.visibility = View.VISIBLE
        testPanel.removeAllViews()
        testPanel.addView(label("RUNNING IN-APP SELF TESTS…", 13f, 0xFF202633.toInt()))
        val result = controller.execute("run_self_tests")
        val checks = result.optJSONArray("checks") ?: org.json.JSONArray()
        val summary = label("SELF TEST: " + result.optInt("passed") + " PASSED • " +
            result.optInt("failed") + " FAILED", 14f,
            if (result.optBoolean("success")) 0xFF14532D.toInt() else 0xFF7F1D1D.toInt())
        testPanel.addView(summary)
        val scroll = ScrollView(this)
        val lines = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        for (i in 0 until checks.length()) {
            val check = checks.optJSONObject(i) ?: continue
            val passed = check.optBoolean("passed")
            lines.addView(label((if (passed) "✓ " else "✕ ") + check.optString("name") +
                " — " + check.optString("detail"), 11f,
                if (passed) 0xFF203A34.toInt() else 0xFF4A252B.toInt()))
        }
        scroll.addView(lines)
        testPanel.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        Toast.makeText(this, "Self-test: " + result.optInt("passed") + "/" + result.optInt("total") + " passed", Toast.LENGTH_LONG).show()
    }

    private fun promptText() {
        val input = EditText(this).apply { setText("Your title"); setSingleLine(true) }
        AlertDialog.Builder(this).setTitle("Add text").setView(input).setNegativeButton("Cancel", null)
            .setPositiveButton("Add") { _, _ ->
                controller.execute("add_shape", JSONObject().put("type", "text").put("text", input.text.toString())
                    .put("x", 300).put("y", 300).put("fontSize", 72).put("color", "#FFFFFF"))
            }.show()
    }

    private fun newProjectDialog() {
        val input = EditText(this).apply { setText("Untitled Animation"); setSingleLine(true) }
        AlertDialog.Builder(this).setTitle("New project name").setView(input).setNegativeButton("Cancel", null)
            .setPositiveButton("Create") { _, _ ->
                controller.execute("create_project", JSONObject().put("name", input.text.toString())
                    .put("width", 1920).put("height", 1080).put("fps", 30).put("durationFrames", 450))
            }.show()
    }

    private fun saveProjectDialog() {
        val input = EditText(this).apply { setText(controller.state().optString("name", "Untitled Animation")); setSingleLine(true) }
        AlertDialog.Builder(this).setTitle("Save project").setView(input).setNegativeButton("Cancel", null)
            .setPositiveButton("Save") { _, _ ->
                val result = controller.execute("save_project", JSONObject().put("name", input.text.toString()))
                Toast.makeText(this, result.optString("message"), Toast.LENGTH_SHORT).show()
            }.show()
    }

    private fun loadProjectDialog() {
        val input = EditText(this).apply { hint = "Saved project name"; setSingleLine(true) }
        AlertDialog.Builder(this).setTitle("Load project").setView(input).setNegativeButton("Cancel", null)
            .setPositiveButton("Load") { _, _ ->
                val result = controller.execute("load_project", JSONObject().put("name", input.text.toString()))
                Toast.makeText(this, result.optString("message", result.optString("error")), Toast.LENGTH_SHORT).show()
            }.show()
    }

    private fun addKeyframeForFirstObject() {
        val state = controller.state()
        val layers = state.optJSONArray("layers")
        var target: JSONObject? = null
        if (layers != null) for (i in 0 until layers.length()) {
            val objects = layers.optJSONObject(i)?.optJSONArray("objects") ?: continue
            if (objects.length() > 0) { target = objects.optJSONObject(0); break }
        }
        val obj = target ?: run {
            Toast.makeText(this, "Create an object first", Toast.LENGTH_SHORT).show(); return
        }
        val key = controller.execute("add_keyframe", JSONObject().put("objectId", obj.optString("id"))
            .put("property", "x").put("frame", state.optInt("currentFrame")).put("value", obj.optDouble("x")))
        Toast.makeText(this, key.optString("message", key.optString("error")), Toast.LENGTH_SHORT).show()
    }

    private fun showApiInfo() {
        AlertDialog.Builder(this).setTitle("Built-in app API")
            .setMessage("Native MCP server: 127.0.0.1:8765\n\nNo MacroDroid or external automation app is used. Editor tools, project state and self-tests live inside Animation Studio.")
            .setPositiveButton("OK", null).show()
    }

    private fun restoreAutosave() {
        val result = controller.execute("load_project", JSONObject().put("name", "animation-studio-autosave"))
        if (result.optBoolean("success")) Toast.makeText(this, "Autosave restored", Toast.LENGTH_SHORT).show()
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

    override fun onPause() {
        if (::controller.isInitialized) controller.execute("save_project", JSONObject().put("name", "animation-studio-autosave"))
        super.onPause()
    }

    override fun onDestroy() {
        if (::server.isInitialized) server.stop()
        handler.removeCallbacks(playbackTick)
        super.onDestroy()
    }
}
