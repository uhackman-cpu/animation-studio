package com.uhackman.animationstudio

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.ArrayDeque
import java.util.UUID

class EditorController(private val context: Context, private val onChanged: () -> Unit) {
    private val lock = Any()
    private val undoStack = ArrayDeque<String>()
    private val redoStack = ArrayDeque<String>()
    private var document = newDocument("Untitled Animation", 1920, 1080, 30, 450)

    private fun newDocument(name: String, width: Int, height: Int, fps: Int, duration: Int) =
        JSONObject().put("name", name).put("width", width).put("height", height)
            .put("fps", fps).put("durationFrames", duration).put("currentFrame", 0)
            .put("playing", false).put("tool", "select").put("activeLayerId", "layer-1")
            .put("layers", JSONArray().put(JSONObject().put("id", "layer-1").put("name", "Layer 1")
                .put("visible", true).put("locked", false).put("objects", JSONArray())))
            .put("keyframes", JSONArray())

    fun state(): JSONObject = synchronized(lock) { JSONObject(document.toString()) }
    fun isPlaying(): Boolean = synchronized(lock) { document.optBoolean("playing", false) }
    fun setTool(tool: String) { execute("set_tool", JSONObject().put("tool", tool)) }

    fun advanceFrame() {
        synchronized(lock) {
            if (!document.optBoolean("playing", false)) return
            val duration = document.optInt("durationFrames", 450).coerceAtLeast(1)
            document.put("currentFrame", (document.optInt("currentFrame", 0) + 1) % duration)
        }
        onChanged()
    }

    fun execute(name: String, args: JSONObject = JSONObject()): JSONObject {
        val result = synchronized(lock) {
            when (name) {
                "set_active_layer" -> setActiveLayer(args)
                "get_editor_state" -> JSONObject().put("success", true).put("state", JSONObject(document.toString()))
                "create_project" -> createProject(args)
                "save_project" -> saveProject(args)
                "load_project" -> loadProject(args)
                "add_layer" -> addLayer(args)
                "update_layer" -> updateLayer(args)
                "delete_layer" -> deleteLayer(args)
                "add_shape" -> addShape(args)
                "update_shape" -> updateShape(args)
                "begin_shape_move" -> beginShapeMove(args)
                "move_shape" -> moveShape(args)
                "delete_shape" -> deleteShape(args)
                "add_keyframe" -> addKeyframe(args)
                "set_frame" -> setFrame(args)
                "set_tool" -> setToolLocked(args)
                "play" -> { document.put("playing", true); ok("Playback started") }
                "stop" -> { document.put("playing", false); ok("Playback stopped") }
                "undo" -> undo()
                "redo" -> redo()
                "run_self_tests" -> runSelfTests()
                else -> fail("Unknown command: " + name)
            }
        }
        if (result.optBoolean("success", false) && name != "get_editor_state" && name != "run_self_tests") onChanged()
        return result
    }

    fun renderedObjects(layer: JSONObject): JSONArray = synchronized(lock) {
        val source = layer.optJSONArray("objects") ?: JSONArray()
        val result = JSONArray()
        for (i in 0 until source.length()) {
            val item = source.optJSONObject(i) ?: continue
            result.put(applyKeyframes(item))
        }
        result
    }

    private fun applyKeyframes(source: JSONObject): JSONObject {
        val obj = JSONObject(source.toString())
        val frames = document.optJSONArray("keyframes") ?: JSONArray()
        val objectId = obj.optString("id")
        val properties = linkedSetOf<String>()
        for (i in 0 until frames.length()) {
            val key = frames.optJSONObject(i) ?: continue
            if (key.optString("objectId") == objectId) properties.add(key.optString("property"))
        }
        for (property in properties) {
            val matching = ArrayList<JSONObject>()
            for (i in 0 until frames.length()) {
                val key = frames.optJSONObject(i) ?: continue
                if (key.optString("objectId") == objectId && key.optString("property") == property) matching.add(key)
            }
            matching.sortBy { it.optInt("frame", 0) }
            val current = document.optInt("currentFrame", 0)
            val before = matching.lastOrNull { it.optInt("frame", 0) <= current }
            val after = matching.firstOrNull { it.optInt("frame", 0) >= current }
            if (before != null && after != null && before.optInt("frame") != after.optInt("frame")) {
                val start = before.optDouble("value", Double.NaN)
                val end = after.optDouble("value", Double.NaN)
                if (!start.isNaN() && !end.isNaN()) {
                    val a = before.optInt("frame", 0)
                    val b = after.optInt("frame", a)
                    val t = (current - a).toDouble() / (b - a).toDouble()
                    obj.put(property, start + (end - start) * t)
                }
            } else if (before != null) obj.put(property, before.opt("value"))
            else if (after != null) obj.put(property, after.opt("value"))
        }
        return obj
    }

    private fun createProject(args: JSONObject): JSONObject {
        pushUndo()
        val name = args.optString("name", "Untitled Animation")
        val width = args.optInt("width", 1920).coerceIn(16, 8192)
        val height = args.optInt("height", 1080).coerceIn(16, 8192)
        val fps = args.optInt("fps", 30).coerceIn(1, 120)
        val duration = args.optInt("durationFrames", fps * 15).coerceIn(1, 100000)
        document = newDocument(name, width, height, fps, duration)
        return ok("Project created").put("project", name)
    }

    private fun saveProject(args: JSONObject): JSONObject {
        val requested = args.optString("name", document.optString("name", "project"))
        val file = projectFile(requested)
        file.parentFile?.mkdirs()
        file.writeText(document.toString(2))
        return ok("Project saved").put("file", file.absolutePath)
    }

    private fun loadProject(args: JSONObject): JSONObject {
        val name = args.optString("name", "")
        if (name.isBlank()) return fail("Project name is required")
        val file = projectFile(name)
        if (!file.exists()) return fail("Project not found: " + file.name)
        val loaded = JSONObject(file.readText())
        if (!loaded.has("layers") || !loaded.has("width") || !loaded.has("height")) return fail("Invalid project file")
        pushUndo()
        document = loaded
        return ok("Project loaded").put("name", document.optString("name"))
    }

    private fun projectFile(name: String): File {
        val safe = name.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "project" }
        return File(File(context.filesDir, "projects"), if (safe.endsWith(".json")) safe else "$safe.json")
    }

    private fun addLayer(args: JSONObject): JSONObject {
        pushUndo()
        val layers = document.optJSONArray("layers") ?: JSONArray().also { document.put("layers", it) }
        val id = args.optString("id").ifBlank { "layer-" + UUID.randomUUID().toString().take(8) }
        val layer = JSONObject().put("id", id).put("name", args.optString("name", "Layer " + (layers.length() + 1)))
            .put("visible", args.optBoolean("visible", true)).put("locked", false).put("objects", JSONArray())
        layers.put(layer)
        document.put("activeLayerId", id)
        return ok("Layer added").put("layer", layer)
    }

    private fun updateLayer(args: JSONObject): JSONObject {
        val layer = findLayer(args.optString("layerId")) ?: return fail("Layer not found")
        pushUndo()
        val fields = args.optJSONObject("fields") ?: JSONObject()
        val keys = fields.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            if (key == "name" || key == "visible" || key == "locked") layer.put(key, fields.opt(key))
        }
        return ok("Layer updated").put("layer", layer)
    }

    private fun deleteLayer(args: JSONObject): JSONObject {
        val layers = document.optJSONArray("layers") ?: return fail("No layers")
        val id = args.optString("layerId")
        if (layers.length() <= 1) return fail("The last layer cannot be deleted")
        if (findLayer(id) == null) return fail("Layer not found")
        pushUndo()
        val kept = JSONArray()
        for (i in 0 until layers.length()) {
            val layer = layers.optJSONObject(i) ?: continue
            if (layer.optString("id") != id) kept.put(layer)
        }
        document.put("layers", kept)
        if (document.optString("activeLayerId") == id) document.put("activeLayerId", kept.optJSONObject(0)?.optString("id") ?: "")
        return ok("Layer deleted")
    }

    private fun addShape(args: JSONObject): JSONObject {
        val layerId = args.optString("layerId", document.optString("activeLayerId"))
        val layer = findLayer(layerId) ?: return fail("Target layer not found")
        if (layer.optBoolean("locked", false)) return fail("Target layer is locked")
        if (!layer.optBoolean("visible", true)) return fail("Target layer is hidden")
        pushUndo()
        val shape = JSONObject(args.toString())
        shape.remove("layerId"); shape.remove("action")
        shape.put("id", args.optString("id").ifBlank { "shape-" + UUID.randomUUID().toString().take(8) })
        shape.put("type", args.optString("type", "rect"))
        shape.put("x", args.optDouble("x", 300.0)).put("y", args.optDouble("y", 250.0))
        shape.put("width", args.optDouble("width", 300.0)).put("height", args.optDouble("height", 200.0))
        shape.put("color", args.optString("color", "#F59E0B"))
        layer.optJSONArray("objects")?.put(shape)
        return ok("Shape added").put("object", shape).put("layerId", layerId)
    }

    private fun updateShape(args: JSONObject): JSONObject {
        val id = args.optString("objectId")
        val found = findShape(id) ?: return fail("Object not found")
        val layer = findLayerForShape(id) ?: return fail("Layer not found")
        if (layer.optBoolean("locked", false)) return fail("Object layer is locked")
        val fields = args.optJSONObject("fields") ?: JSONObject().put(args.optString("property"), args.opt("value"))
        pushUndo()
        val keys = fields.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            if (key != "id" && key != "layerId") found.put(key, fields.opt(key))
        }
        return ok("Shape updated").put("object", found)
    }

    private fun beginShapeMove(args: JSONObject): JSONObject {
        val id = args.optString("objectId")
        if (findShape(id) == null) return fail("Object not found")
        val layer = findLayerForShape(id) ?: return fail("Layer not found")
        if (layer.optBoolean("locked", false)) return fail("Object layer is locked")
        pushUndo()
        return ok("Move started")
    }

    private fun moveShape(args: JSONObject): JSONObject {
        val id = args.optString("objectId")
        val found = findShape(id) ?: return fail("Object not found")
        val layer = findLayerForShape(id) ?: return fail("Layer not found")
        if (layer.optBoolean("locked", false)) return fail("Object layer is locked")
        if (args.has("x")) found.put("x", args.optDouble("x"))
        if (args.has("y")) found.put("y", args.optDouble("y"))
        return ok("Object moved").put("object", found)
    }

    private fun deleteShape(args: JSONObject): JSONObject {
        val id = args.optString("objectId")
        val layers = document.optJSONArray("layers") ?: return fail("No layers")
        for (i in 0 until layers.length()) {
            val layer = layers.optJSONObject(i) ?: continue
            val objects = layer.optJSONArray("objects") ?: continue
            var found = false
            for (j in 0 until objects.length()) if (objects.optJSONObject(j)?.optString("id") == id) found = true
            if (!found) continue
            if (layer.optBoolean("locked", false)) return fail("Object layer is locked")
            pushUndo()
            val kept = JSONArray()
            for (j in 0 until objects.length()) {
                val item = objects.optJSONObject(j) ?: continue
                if (item.optString("id") != id) kept.put(item)
            }
            layer.put("objects", kept)
            return ok("Shape deleted")
        }
        return fail("Object not found")
    }

    private fun addKeyframe(args: JSONObject): JSONObject {
        val objectId = args.optString("objectId")
        val found = findShape(objectId) ?: return fail("Object not found")
        val layer = findLayerForShape(objectId) ?: return fail("Layer not found")
        if (layer.optBoolean("locked", false)) return fail("Object layer is locked")
        val property = args.optString("property")
        if (property !in setOf("x", "y", "width", "height", "rotation", "alpha")) return fail("Unsupported animated property")
        val frame = args.optInt("frame", document.optInt("currentFrame", 0)).coerceIn(0, document.optInt("durationFrames", 1) - 1)
        val value = if (args.has("value")) args.opt("value") else found.opt(property)
        if (value == null) return fail("Keyframe value is required")
        pushUndo()
        val current = document.optJSONArray("keyframes") ?: JSONArray().also { document.put("keyframes", it) }
        val kept = JSONArray()
        for (i in 0 until current.length()) {
            val item = current.optJSONObject(i) ?: continue
            if (!(item.optString("objectId") == objectId && item.optString("property") == property && item.optInt("frame") == frame)) kept.put(item)
        }
        val key = JSONObject().put("id", "key-" + UUID.randomUUID().toString().take(8))
            .put("objectId", objectId).put("property", property).put("frame", frame).put("value", value)
        kept.put(key); document.put("keyframes", kept)
        return ok("Keyframe added").put("keyframe", key)
    }

    private fun setFrame(args: JSONObject): JSONObject {
        val frame = args.optInt("frame", 0).coerceAtLeast(0)
        val max = document.optInt("durationFrames", 450).coerceAtLeast(1) - 1
        document.put("currentFrame", frame.coerceAtMost(max))
        return ok("Current frame changed").put("frame", document.optInt("currentFrame"))
    }

    private fun setToolLocked(args: JSONObject): JSONObject {
        val tool = args.optString("tool", "select")
        if (tool !in setOf("select", "rect", "line", "draw", "brush", "eraser")) return fail("Unsupported tool: $tool")
        document.put("tool", tool)
        return ok("Tool selected").put("tool", tool)
    }

    private fun setActiveLayer(args: JSONObject): JSONObject {
        val id = args.optString("layerId")
        if (findLayer(id) == null) return fail("Layer not found")
        document.put("activeLayerId", id)
        return ok("Active layer changed").put("layerId", id)
    }

    private fun findLayer(id: String): JSONObject? {
        val layers = document.optJSONArray("layers") ?: return null
        for (i in 0 until layers.length()) if (layers.optJSONObject(i)?.optString("id") == id) return layers.optJSONObject(i)
        return null
    }

    private fun findLayerForShape(id: String): JSONObject? {
        val layers = document.optJSONArray("layers") ?: return null
        for (i in 0 until layers.length()) {
            val layer = layers.optJSONObject(i) ?: continue
            val objects = layer.optJSONArray("objects") ?: continue
            for (j in 0 until objects.length()) if (objects.optJSONObject(j)?.optString("id") == id) return layer
        }
        return null
    }

    private fun findShape(id: String): JSONObject? {
        val layers = document.optJSONArray("layers") ?: return null
        for (i in 0 until layers.length()) {
            val objects = layers.optJSONObject(i)?.optJSONArray("objects") ?: continue
            for (j in 0 until objects.length()) {
                val shape = objects.optJSONObject(j) ?: continue
                if (shape.optString("id") == id) return shape
            }
        }
        return null
    }

    private fun pushUndo() {
        undoStack.addLast(document.toString())
        while (undoStack.size > 50) undoStack.removeFirst()
        redoStack.clear()
    }

    private fun undo(): JSONObject {
        if (undoStack.isEmpty()) return fail("Nothing to undo")
        redoStack.addLast(document.toString()); document = JSONObject(undoStack.removeLast())
        return ok("Undo complete")
    }

    private fun redo(): JSONObject {
        if (redoStack.isEmpty()) return fail("Nothing to redo")
        undoStack.addLast(document.toString()); document = JSONObject(redoStack.removeLast())
        return ok("Redo complete")
    }

    private fun runSelfTests(): JSONObject {
        val probe = EditorController(context) {}
        val checks = JSONArray()
        var passed = 0
        var failed = 0
        fun record(name: String, condition: Boolean, detail: String) {
            checks.put(JSONObject().put("name", name).put("passed", condition).put("detail", detail))
            if (condition) passed++ else failed++
        }
        try {
            val created = probe.execute("create_project", JSONObject().put("name", "Self Test").put("width", 640).put("height", 360).put("fps", 24).put("durationFrames", 120))
            record("Project creation", created.optBoolean("success"), created.optString("message"))
            val added = probe.execute("add_shape", JSONObject().put("type", "rect").put("x", 100).put("y", 40).put("width", 80).put("height", 60).put("color", "#00AAFF"))
            val shape = added.optJSONObject("object")
            val id = shape?.optString("id") ?: ""
            record("Shape creation", added.optBoolean("success") && shape != null, added.optString("message"))
            val k1 = probe.execute("add_keyframe", JSONObject().put("objectId", id).put("property", "x").put("frame", 0).put("value", 100))
            val k2 = probe.execute("add_keyframe", JSONObject().put("objectId", id).put("property", "x").put("frame", 10).put("value", 200))
            probe.execute("set_frame", JSONObject().put("frame", 5))
            val layer = probe.state().optJSONArray("layers")?.optJSONObject(0) ?: JSONObject()
            val x = probe.renderedObjects(layer).optJSONObject(0)?.optDouble("x", -1.0) ?: -1.0
            record("Keyframe interpolation", k1.optBoolean("success") && k2.optBoolean("success") && kotlin.math.abs(x - 150.0) < 0.01, "Expected 150 at frame 5; received $x")
            val originalColor = probe.findShape(id)?.optString("color")
            probe.execute("update_shape", JSONObject().put("objectId", id).put("fields", JSONObject().put("color", "#FF0000")))
            val undoOk = probe.execute("undo").optBoolean("success")
            val undoColor = probe.findShape(id)?.optString("color")
            val redoOk = probe.execute("redo").optBoolean("success")
            val redoColor = probe.findShape(id)?.optString("color")
            record("Undo / redo", undoOk && redoOk && undoColor == originalColor && redoColor == "#FF0000", "Undo=$undoColor; redo=$redoColor")
            val layerResult = probe.execute("add_layer", JSONObject().put("name", "Test Layer"))
            record("Layer creation", layerResult.optBoolean("success") && probe.state().optJSONArray("layers")?.length() == 2, layerResult.optString("message"))
            val fileName = "animation-studio-self-test-" + UUID.randomUUID().toString()
            val saved = probe.execute("save_project", JSONObject().put("name", fileName))
            val file = probe.projectFile(fileName)
            val loaded = probe.execute("load_project", JSONObject().put("name", fileName))
            record("Project save / load", saved.optBoolean("success") && loaded.optBoolean("success") && file.exists(), loaded.optString("message"))
            file.delete()
            val clamped = probe.execute("set_frame", JSONObject().put("frame", 999)).optInt("frame") == 119
            record("Frame range clamp", clamped, "Frame stays inside project duration")
        } catch (error: Exception) {
            record("Self-test runtime", false, error.message ?: "Unknown error")
        }
        return JSONObject().put("success", failed == 0).put("passed", passed).put("failed", failed)
            .put("total", passed + failed).put("checks", checks)
    }

    private fun ok(message: String) = JSONObject().put("success", true).put("message", message)
    private fun fail(message: String) = JSONObject().put("success", false).put("error", message)
}
