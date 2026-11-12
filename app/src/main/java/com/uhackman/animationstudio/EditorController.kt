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
        JSONObject()
            .put("name", name).put("width", width).put("height", height)
            .put("fps", fps).put("durationFrames", duration).put("currentFrame", 0)
            .put("playing", false).put("tool", "select").put("activeLayerId", "layer-1")
            .put("layers", JSONArray().put(JSONObject()
                .put("id", "layer-1").put("name", "Layer 1").put("visible", true)
                .put("locked", false).put("objects", JSONArray())))
            .put("keyframes", JSONArray())

    fun state(): JSONObject = synchronized(lock) { JSONObject(document.toString()) }
    fun isPlaying(): Boolean = synchronized(lock) { document.optBoolean("playing", false) }

    fun advanceFrame() {
        synchronized(lock) {
            if (!document.optBoolean("playing", false)) return
            val duration = document.optInt("durationFrames", 450).coerceAtLeast(1)
            document.put("currentFrame", (document.optInt("currentFrame", 0) + 1) % duration)
        }
        onChanged()
    }

    fun setTool(tool: String) {
        synchronized(lock) { document.put("tool", tool) }
        onChanged()
    }

    fun execute(name: String, args: JSONObject = JSONObject()): JSONObject {
        val result = synchronized(lock) {
            when (name) {
                "get_editor_state" -> JSONObject().put("success", true).put("state", JSONObject(document.toString()))
                "create_project" -> createProject(args)
                "save_project" -> saveProject(args)
                "load_project" -> loadProject(args)
                "add_layer" -> addLayer(args)
                "update_layer" -> updateLayer(args)
                "delete_layer" -> deleteLayer(args)
                "add_shape" -> addShape(args)
                "update_shape" -> updateShape(args)
                "delete_shape" -> deleteShape(args)
                "add_keyframe" -> addKeyframe(args)
                "set_frame" -> setFrame(args)
                "set_tool" -> setToolLocked(args)
                "play" -> { document.put("playing", true); ok("Playback started") }
                "stop" -> { document.put("playing", false); ok("Playback stopped") }
                "undo" -> undo()
                "redo" -> redo()
                else -> fail("Unknown command: " + name)
            }
        }
        if (result.optBoolean("success", false) && name != "get_editor_state") onChanged()
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
            if (before != null && after != null && before !== after) {
                val start = before.optDouble("value", Double.NaN)
                val end = after.optDouble("value", Double.NaN)
                if (!start.isNaN() && !end.isNaN()) {
                    val firstFrame = before.optInt("frame", 0)
                    val lastFrame = after.optInt("frame", firstFrame)
                    val amount = if (lastFrame == firstFrame) 0.0 else
                        (current - firstFrame).toDouble() / (lastFrame - firstFrame).toDouble()
                    obj.put(property, start + (end - start) * amount)
                }
            } else if (before != null) {
                obj.put(property, before.opt("value"))
            } else if (after != null) {
                obj.put(property, after.opt("value"))
            }
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
        pushUndo()
        document = JSONObject(file.readText())
        return ok("Project loaded").put("name", document.optString("name"))
    }

    private fun projectFile(name: String): File {
        val safe = name.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "project" }
        val filename = if (safe.endsWith(".json")) safe else safe + ".json"
        return File(File(context.filesDir, "projects"), filename)
    }

    private fun addLayer(args: JSONObject): JSONObject {
        pushUndo()
        val layers = document.optJSONArray("layers") ?: JSONArray().also { document.put("layers", it) }
        val id = args.optString("id").ifBlank { "layer-" + UUID.randomUUID().toString().take(8) }
        val layer = JSONObject()
            .put("id", id).put("name", args.optString("name", "Layer " + (layers.length() + 1)))
            .put("visible", args.optBoolean("visible", true)).put("locked", false)
            .put("objects", JSONArray())
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
        pushUndo()
        val shape = JSONObject(args.toString())
        shape.remove("layerId")
        shape.remove("action")
        shape.put("id", args.optString("id").ifBlank { "shape-" + UUID.randomUUID().toString().take(8) })
        shape.put("type", args.optString("type", "rect"))
        shape.put("x", args.optDouble("x", 300.0))
        shape.put("y", args.optDouble("y", 250.0))
        shape.put("width", args.optDouble("width", 300.0))
        shape.put("height", args.optDouble("height", 200.0))
        shape.put("color", args.optString("color", "#F59E0B"))
        layer.optJSONArray("objects")?.put(shape)
        return ok("Shape added").put("object", shape).put("layerId", layerId)
    }

    private fun updateShape(args: JSONObject): JSONObject {
        val found = findShape(args.optString("objectId")) ?: return fail("Object not found")
        val fields = args.optJSONObject("fields") ?: JSONObject().put(args.optString("property"), args.opt("value"))
        pushUndo()
        val keys = fields.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            if (key != "id" && key != "layerId") found.put(key, fields.opt(key))
        }
        return ok("Shape updated").put("object", found)
    }

    private fun deleteShape(args: JSONObject): JSONObject {
        val id = args.optString("objectId")
        val layers = document.optJSONArray("layers") ?: return fail("No layers")
        for (layerIndex in 0 until layers.length()) {
            val layer = layers.optJSONObject(layerIndex) ?: continue
            val objects = layer.optJSONArray("objects") ?: continue
            val kept = JSONArray()
            var removed = false
            for (i in 0 until objects.length()) {
                val obj = objects.optJSONObject(i) ?: continue
                if (obj.optString("id") == id) removed = true else kept.put(obj)
            }
            if (removed) {
                pushUndo()
                layer.put("objects", kept)
                return ok("Shape deleted")
            }
        }
        return fail("Object not found")
    }

    private fun addKeyframe(args: JSONObject): JSONObject {
        val objectId = args.optString("objectId")
        val found = findShape(objectId) ?: return fail("Object not found")
        val property = args.optString("property")
        if (property.isBlank()) return fail("Property is required")
        val frame = args.optInt("frame", document.optInt("currentFrame", 0)).coerceAtLeast(0)
        val value = if (args.has("value")) args.opt("value") else found.opt(property)
        if (value == null) return fail("Keyframe value is required")
        pushUndo()
        val current = document.optJSONArray("keyframes") ?: JSONArray().also { document.put("keyframes", it) }
        val kept = JSONArray()
        for (i in 0 until current.length()) {
            val item = current.optJSONObject(i) ?: continue
            val same = item.optString("objectId") == objectId &&
                item.optString("property") == property && item.optInt("frame") == frame
            if (!same) kept.put(item)
        }
        val key = JSONObject().put("id", "key-" + UUID.randomUUID().toString().take(8))
            .put("objectId", objectId).put("property", property).put("frame", frame).put("value", value)
        kept.put(key)
        document.put("keyframes", kept)
        return ok("Keyframe added").put("keyframe", key)
    }

    private fun setFrame(args: JSONObject): JSONObject {
        val frame = args.optInt("frame", 0).coerceAtLeast(0)
        val maximum = document.optInt("durationFrames", 450).coerceAtLeast(1) - 1
        document.put("currentFrame", frame.coerceAtMost(maximum))
        return ok("Current frame changed").put("frame", document.optInt("currentFrame"))
    }

    private fun setToolLocked(args: JSONObject): JSONObject {
        val tool = args.optString("tool", "select")
        document.put("tool", tool)
        return ok("Tool selected").put("tool", tool)
    }

    private fun findLayer(id: String): JSONObject? {
        val layers = document.optJSONArray("layers") ?: return null
        for (i in 0 until layers.length()) {
            val layer = layers.optJSONObject(i) ?: continue
            if (layer.optString("id") == id) return layer
        }
        return null
    }

    private fun findShape(id: String): JSONObject? {
        val layers = document.optJSONArray("layers") ?: return null
        for (i in 0 until layers.length()) {
            val layer = layers.optJSONObject(i) ?: continue
            val objects = layer.optJSONArray("objects") ?: continue
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
        redoStack.addLast(document.toString())
        document = JSONObject(undoStack.removeLast())
        return ok("Undo complete")
    }

    private fun redo(): JSONObject {
        if (redoStack.isEmpty()) return fail("Nothing to redo")
        undoStack.addLast(document.toString())
        document = JSONObject(redoStack.removeLast())
        return ok("Redo complete")
    }

    private fun ok(message: String) = JSONObject().put("success", true).put("message", message)
    private fun fail(message: String) = JSONObject().put("success", false).put("error", message)
}
