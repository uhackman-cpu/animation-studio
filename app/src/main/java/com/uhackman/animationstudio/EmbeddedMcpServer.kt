package com.uhackman.animationstudio

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class EmbeddedMcpServer(
    private val controller: EditorController,
    private val screenshotProvider: () -> String?
) {
    companion object { const val PORT = 8765 }

    @Volatile private var running = false
    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null
    private val clients: ExecutorService = Executors.newCachedThreadPool()

    fun start() {
        if (running) return
        running = true
        acceptThread = Thread({
            try {
                val socketServer = ServerSocket()
                socketServer.reuseAddress = true
                socketServer.bind(InetSocketAddress("0.0.0.0", PORT))
                serverSocket = socketServer
                while (running) {
                    val client = socketServer.accept()
                    clients.execute { handleClient(client) }
                }
            } catch (_: Exception) {
                running = false
            }
        }, "AnimationStudio-MCP-Accept").apply { isDaemon = true; start() }
    }

    fun stop() {
        running = false
        try { serverSocket?.close() } catch (_: Exception) { }
        clients.shutdownNow()
    }

    private fun handleClient(socket: Socket) {
        socket.use { client ->
            try {
                client.soTimeout = 15000
                val input = BufferedInputStream(client.getInputStream())
                val requestLine = readLine(input) ?: return
                val parts = requestLine.split(" ")
                if (parts.size < 2) return
                val method = parts[0]
                val path = parts[1].substringBefore("?")
                val headers = HashMap<String, String>()
                while (true) {
                    val line = readLine(input) ?: break
                    if (line.isEmpty()) break
                    val separator = line.indexOf(':')
                    if (separator > 0) headers[line.substring(0, separator).trim().lowercase()] =
                        line.substring(separator + 1).trim()
                }
                if (method == "OPTIONS") {
                    writeResponse(client, 204, "")
                    return
                }
                val length = (headers["content-length"] ?: "0").toIntOrNull()?.coerceIn(0, 2_000_000) ?: 0
                val bytes = ByteArray(length)
                var offset = 0
                while (offset < length) {
                    val read = input.read(bytes, offset, length - offset)
                    if (read <= 0) break
                    offset += read
                }
                val body = String(bytes, 0, offset, StandardCharsets.UTF_8)
                val request = if (body.isNotBlank()) JSONObject(body) else JSONObject()

                when {
                    method == "GET" && path == "/health" ->
                        writeJson(client, JSONObject().put("ok", true).put("service", "animation-studio").put("port", PORT))
                    method == "GET" && path == "/api/v1/state" ->
                        writeJson(client, controller.state())
                    method == "GET" && path == "/api/v1/tools" ->
                        writeJson(client, toolsList())
                    method == "GET" && path == "/api/v1/screenshot" -> {
                        val image = screenshotProvider()
                        writeJson(client, JSONObject().put("success", image != null).put("mimeType", "image/png").put("base64", image ?: ""))
                    }
                    method == "POST" && path == "/mcp" ->
                        writeJson(client, handleMcp(request))
                    method == "POST" && path == "/api/v1/command" -> {
                        val action = request.optString("action")
                        writeJson(client, controller.execute(action, request.optJSONObject("args") ?: request))
                    }
                    method == "POST" && path.startsWith("/api/v1/") -> {
                        val command = path.removePrefix("/api/v1/").replace('-', '_')
                        writeJson(client, controller.execute(command, request))
                    }
                    else -> writeResponse(client, 404, JSONObject().put("success", false).put("error", "Route not found").toString())
                }
            } catch (exception: Exception) {
                try {
                    writeResponse(client, 400, JSONObject().put("success", false)
                        .put("error", exception.message ?: "Bad request").toString())
                } catch (_: Exception) { }
            }
        }
    }

    private fun handleMcp(request: JSONObject): JSONObject {
        val id = request.opt("id")
        val method = request.optString("method")
        val params = request.optJSONObject("params") ?: JSONObject()
        val result = when (method) {
            "initialize" -> JSONObject()
                .put("protocolVersion", "2024-11-05")
                .put("capabilities", JSONObject().put("tools", JSONObject()))
                .put("serverInfo", JSONObject().put("name", "animation-studio").put("version", "0.2.0"))
            "notifications/initialized", "ping" -> JSONObject()
            "tools/list" -> toolsList()
            "tools/call" -> {
                val name = params.optString("name")
                val args = params.optJSONObject("arguments") ?: JSONObject()
                val output = if (name == "get_screenshot") {
                    val encoded = screenshotProvider()
                    JSONObject().put("success", encoded != null).put("mimeType", "image/png").put("base64", encoded ?: "")
                } else controller.execute(name, args)
                JSONObject()
                    .put("content", JSONArray().put(JSONObject().put("type", "text").put("text", output.toString())))
                    .put("isError", !output.optBoolean("success", true))
            }
            else -> return JSONObject().put("jsonrpc", "2.0").put("id", id ?: JSONObject.NULL)
                .put("error", JSONObject().put("code", -32601).put("message", "Method not found: " + method))
        }
        return JSONObject().put("jsonrpc", "2.0").put("id", id ?: JSONObject.NULL).put("result", result)
    }

    private fun toolsList(): JSONObject {
        val names = listOf(
            "get_editor_state", "create_project", "save_project", "load_project",
            "add_layer", "update_layer", "delete_layer", "add_shape", "update_shape",
            "delete_shape", "add_keyframe", "set_frame", "set_tool", "play", "stop",
            "undo", "redo", "get_screenshot", "begin_shape_move", "move_shape", "set_active_layer", "run_self_tests"
        )
        val descriptions = mapOf(
            "get_editor_state" to "Read current project, layers, objects, timeline and playback state.",
            "create_project" to "Create a new animation project with canvas size, FPS and duration.",
            "save_project" to "Save the current project JSON inside the app.",
            "load_project" to "Load a saved project by name.",
            "add_layer" to "Create a layer and make it active.",
            "update_layer" to "Change a layer name, visibility or lock state.",
            "delete_layer" to "Delete a layer.",
            "add_shape" to "Add a rectangle, ellipse, line or text object to a layer.",
            "update_shape" to "Update object properties such as x, y, width, height, color or text.",
            "delete_shape" to "Delete an object by objectId.",
            "add_keyframe" to "Add a property keyframe for an object at a frame number.",
            "set_frame" to "Move the playhead to a frame.",
            "set_tool" to "Set the canvas tool: select, rect, line or draw.",
            "play" to "Start timeline playback.",
            "stop" to "Stop timeline playback.",
            "undo" to "Undo the last project edit.",
            "redo" to "Redo the last undone project edit.",
            "get_screenshot" to "Capture the current canvas as a base64 PNG.",
            "begin_shape_move" to "Start one undoable object drag.",
            "move_shape" to "Move an object while dragging.",
            "set_active_layer" to "Choose the active drawing layer.",
            "run_self_tests" to "Run built-in editor self-tests without changing the active project."
        )
        val tools = JSONArray()
        for (name in names) {
            val properties = JSONObject()
            val schema = JSONObject().put("type", "object")
                .put("properties", properties).put("additionalProperties", true)
            when (name) {
                "create_project" -> {
                    properties.put("name", field("string", "Project name"))
                    properties.put("width", field("integer", "Canvas width, normally 1920"))
                    properties.put("height", field("integer", "Canvas height, normally 1080"))
                    properties.put("fps", field("integer", "Frames per second"))
                    properties.put("durationFrames", field("integer", "Timeline length in frames"))
                }
                "save_project", "load_project" -> properties.put("name", field("string", "Project file name"))
                "add_layer" -> properties.put("name", field("string", "Layer name"))
                "update_layer" -> {
                    properties.put("layerId", field("string", "Layer ID"))
                    properties.put("fields", field("object", "Fields to update"))
                }
                "delete_layer" -> properties.put("layerId", field("string", "Layer ID"))
                "add_shape" -> {
                    properties.put("layerId", field("string", "Target layer ID"))
                    properties.put("type", field("string", "rect, ellipse, line or text"))
                    properties.put("x", field("number", "X coordinate in project pixels"))
                    properties.put("y", field("number", "Y coordinate in project pixels"))
                    properties.put("width", field("number", "Object width"))
                    properties.put("height", field("number", "Object height"))
                    properties.put("color", field("string", "CSS hex color"))
                    properties.put("text", field("string", "Text content for text objects"))
                }
                "update_shape" -> {
                    properties.put("objectId", field("string", "Object ID"))
                    properties.put("fields", field("object", "Object properties to update"))
                    properties.put("property", field("string", "Single property to update"))
                    properties.put("value", field("number", "Value for property"))
                }
                "delete_shape", "begin_shape_move" -> properties.put("objectId", field("string", "Object ID"))
                "move_shape" -> {
                    properties.put("objectId", field("string", "Object ID"))
                    properties.put("x", field("number", "New X position"))
                    properties.put("y", field("number", "New Y position"))
                }
                "set_active_layer" -> properties.put("layerId", field("string", "Layer ID"))
                "add_keyframe" -> {
                    properties.put("objectId", field("string", "Object ID"))
                    properties.put("property", field("string", "Animated property"))
                    properties.put("frame", field("integer", "Frame number"))
                    properties.put("value", field("number", "Property value"))
                }
                "set_frame" -> properties.put("frame", field("integer", "Frame number"))
                "set_tool" -> properties.put("tool", field("string", "select, rect, line, draw, brush or eraser"))
            }
            tools.put(JSONObject().put("name", name)
                .put("description", descriptions[name] ?: name).put("inputSchema", schema))
        }
        return JSONObject().put("tools", tools)
    }

    private fun setActiveLayer(args: JSONObject): JSONObject {
        val id = args.optString("layerId")
        if (findLayerForActive(id) == null) return JSONObject().put("success", false).put("error", "Layer not found")
        controllerSetActiveLayer(id)
        return JSONObject().put("success", true).put("message", "Active layer changed").put("layerId", id)
    }

    private fun findLayerForActive(id: String): Boolean {
        val layers = controller.state().optJSONArray("layers") ?: return false
        for (i in 0 until layers.length()) if (layers.optJSONObject(i)?.optString("id") == id) return true
        return false
    }

    private fun controllerSetActiveLayer(id: String) {
        controller.execute("set_active_layer", JSONObject().put("layerId", id))
    }

    private fun field(type: String, description: String) =
        JSONObject().put("type", type).put("description", description)

    private fun readLine(input: BufferedInputStream): String? {
        val output = ByteArrayOutputStream()
        while (true) {
            val value = input.read()
            if (value == -1) return if (output.size() == 0) null else output.toString("UTF-8")
            if (value == 10) break
            if (value != 13) output.write(value)
        }
        return output.toString("UTF-8")
    }

    private fun writeJson(socket: Socket, json: JSONObject) = writeResponse(socket, 200, json.toString())

    private fun writeResponse(socket: Socket, status: Int, body: String) {
        val phrase = when (status) {
            200 -> "OK"
            204 -> "No Content"
            404 -> "Not Found"
            else -> "Bad Request"
        }
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        val headers = "HTTP/1.1 " + status + " " + phrase + "\r\n" +
            "Content-Type: application/json; charset=utf-8\r\n" +
            "Content-Length: " + bytes.size + "\r\n" +
            "Access-Control-Allow-Origin: *\r\n" +
            "Access-Control-Allow-Headers: Content-Type, Authorization\r\n" +
            "Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n" +
            "Connection: close\r\n\r\n"
        val output = socket.getOutputStream()
        output.write(headers.toByteArray(StandardCharsets.UTF_8))
        output.write(bytes)
        output.flush()
    }
}
