package com.uhackman.animationstudio

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

class StageCanvasView(context: Context, private val controller: EditorController) : View(context) {
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2f }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x223C4659; strokeWidth = 1f }
    private val selectionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFC14D.toInt(); style = Paint.Style.STROKE; strokeWidth = 3f
    }

    private var startX = 0f
    private var startY = 0f
    private var endX = 0f
    private var endY = 0f
    private var drawing = false
    private var selectedObjectId: String? = null
    private var movingObjectId: String? = null
    private var moveOffsetX = 0f
    private var moveOffsetY = 0f
    private var moving = false
    private val strokePoints = JSONArray()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val state = controller.state()
        val virtualWidth = state.optInt("width", 1920).toFloat().coerceAtLeast(1f)
        val virtualHeight = state.optInt("height", 1080).toFloat().coerceAtLeast(1f)
        val scale = minOf(width / virtualWidth, height / virtualHeight).coerceAtLeast(0.01f)
        val stageWidth = virtualWidth * scale
        val stageHeight = virtualHeight * scale
        val left = (width - stageWidth) / 2f
        val top = (height - stageHeight) / 2f

        canvas.drawColor(0xFF111722.toInt())
        canvas.save()
        canvas.translate(left, top)
        canvas.scale(scale, scale)

        fillPaint.color = 0xFF1B2432.toInt()
        canvas.drawRect(0f, 0f, virtualWidth, virtualHeight, fillPaint)
        strokePaint.color = 0xFF53647D.toInt()
        strokePaint.strokeWidth = 3f / scale
        canvas.drawRect(0f, 0f, virtualWidth, virtualHeight, strokePaint)

        for (i in 1 until 12) {
            val x = virtualWidth * i / 12f
            val y = virtualHeight * i / 12f
            canvas.drawLine(x, 0f, x, virtualHeight, gridPaint)
            canvas.drawLine(0f, y, virtualWidth, y, gridPaint)
        }

        val layers = state.optJSONArray("layers")
        if (layers != null) {
            for (i in 0 until layers.length()) {
                val layer = layers.optJSONObject(i) ?: continue
                if (!layer.optBoolean("visible", true)) continue
                val objects = controller.renderedObjects(layer)
                for (j in 0 until objects.length()) {
                    val obj = objects.optJSONObject(j) ?: continue
                    drawObject(canvas, obj, scale)
                    if (obj.optString("id") == selectedObjectId) drawSelection(canvas, obj, scale)
                }
            }
        }

        if (drawing) drawDraft(canvas, state.optString("tool"), scale)
        canvas.restore()
    }

    private fun drawObject(canvas: Canvas, shape: JSONObject, scale: Float) {
        val x = shape.optDouble("x", 0.0).toFloat()
        val y = shape.optDouble("y", 0.0).toFloat()
        val w = shape.optDouble("width", 0.0).toFloat()
        val h = shape.optDouble("height", 0.0).toFloat()
        val color = try { Color.parseColor(shape.optString("color", "#F59E0B")) }
            catch (_: Exception) { Color.rgb(245, 158, 11) }
        fillPaint.color = color
        strokePaint.color = color
        strokePaint.strokeWidth = shape.optDouble("strokeWidth", 5.0).toFloat().coerceAtLeast(1f) / scale

        when (shape.optString("type", "rect").lowercase(Locale.US)) {
            "circle", "ellipse" -> canvas.drawOval(RectF(x, y, x + w, y + h), fillPaint)
            "line" -> canvas.drawLine(x, y, x + w, y + h, strokePaint)
            "stroke", "brush" -> drawPolyline(canvas, shape.optJSONArray("points"), strokePaint)
            "text" -> {
                fillPaint.textSize = shape.optDouble("fontSize", 64.0).toFloat()
                canvas.drawText(shape.optString("text", "Text"), x, y + fillPaint.textSize, fillPaint)
            }
            else -> canvas.drawRect(x, y, x + w, y + h, fillPaint)
        }
    }

    private fun drawPolyline(canvas: Canvas, points: JSONArray?, paint: Paint) {
        if (points == null || points.length() == 0) return
        val path = Path()
        for (i in 0 until points.length()) {
            val point = points.optJSONObject(i) ?: continue
            val x = point.optDouble("x").toFloat()
            val y = point.optDouble("y").toFloat()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        canvas.drawPath(path, paint.apply { strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND })
        if (points.length() == 1) {
            val point = points.optJSONObject(0) ?: return
            canvas.drawCircle(point.optDouble("x").toFloat(), point.optDouble("y").toFloat(),
                paint.strokeWidth / 2f, fillPaint.apply { color = paint.color })
        }
    }

    private fun drawSelection(canvas: Canvas, shape: JSONObject, scale: Float) {
        val x = shape.optDouble("x").toFloat()
        val y = shape.optDouble("y").toFloat()
        val w = shape.optDouble("width").toFloat()
        val h = shape.optDouble("height").toFloat()
        val pad = 8f / scale
        selectionPaint.strokeWidth = 2f / scale
        val bounds = RectF(x - pad, y - pad, x + w + pad, y + h + pad)
        canvas.drawRect(bounds, selectionPaint)
        val handle = 7f / scale
        fillPaint.color = Color.WHITE
        canvas.drawRect(bounds.left - handle, bounds.top - handle, bounds.left + handle, bounds.top + handle, fillPaint)
        canvas.drawRect(bounds.right - handle, bounds.bottom - handle, bounds.right + handle, bounds.bottom + handle, fillPaint)
    }

    private fun drawDraft(canvas: Canvas, tool: String, scale: Float) {
        strokePaint.color = Color.rgb(245, 158, 11)
        strokePaint.strokeWidth = if (tool == "brush") 12f else 4f
        strokePaint.strokeCap = Paint.Cap.ROUND
        when (tool) {
            "line" -> canvas.drawLine(startX, startY, endX, endY, strokePaint)
            "draw", "brush" -> drawPolyline(canvas, strokePoints, strokePaint)
            else -> canvas.drawRect(minOf(startX, endX), minOf(startY, endY),
                maxOf(startX, endX), maxOf(startY, endY), strokePaint)
        }
    }

    private fun virtualPoint(rawX: Float, rawY: Float): Pair<Float, Float> {
        val state = controller.state()
        val vw = state.optInt("width", 1920).toFloat().coerceAtLeast(1f)
        val vh = state.optInt("height", 1080).toFloat().coerceAtLeast(1f)
        val scale = minOf(width / vw, height / vh).coerceAtLeast(0.01f)
        val left = (width - vw * scale) / 2f
        val top = (height - vh * scale) / 2f
        return ((rawX - left) / scale).coerceIn(0f, vw) to ((rawY - top) / scale).coerceIn(0f, vh)
    }

    private fun hitTest(x: Float, y: Float): JSONObject? {
        val layers = controller.state().optJSONArray("layers") ?: return null
        for (li in layers.length() - 1 downTo 0) {
            val layer = layers.optJSONObject(li) ?: continue
            if (!layer.optBoolean("visible", true) || layer.optBoolean("locked", false)) continue
            val objects = controller.renderedObjects(layer)
            for (oi in objects.length() - 1 downTo 0) {
                val obj = objects.optJSONObject(oi) ?: continue
                val ox = obj.optDouble("x").toFloat()
                val oy = obj.optDouble("y").toFloat()
                val ow = obj.optDouble("width").toFloat()
                val oh = obj.optDouble("height").toFloat()
                val margin = maxOf(18f, obj.optDouble("strokeWidth", 4.0).toFloat())
                if (x >= minOf(ox, ox + ow) - margin && x <= maxOf(ox, ox + ow) + margin &&
                    y >= minOf(oy, oy + oh) - margin && y <= maxOf(oy, oy + oh) + margin) return obj
            }
        }
        return null
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val tool = controller.state().optString("tool", "select")
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                val p = virtualPoint(event.x, event.y)
                startX = p.first; startY = p.second; endX = startX; endY = startY
                when (tool) {
                    "select" -> {
                        val hit = hitTest(startX, startY)
                        selectedObjectId = hit?.optString("id")
                        movingObjectId = selectedObjectId
                        moving = hit != null
                        if (hit != null) {
                            moveOffsetX = startX - hit.optDouble("x").toFloat()
                            moveOffsetY = startY - hit.optDouble("y").toFloat()
                            controller.execute("begin_shape_move", JSONObject().put("objectId", movingObjectId))
                        }
                        invalidate()
                        return true
                    }
                    "eraser" -> {
                        val hit = hitTest(startX, startY)
                        if (hit != null) {
                            controller.execute("delete_shape", JSONObject().put("objectId", hit.optString("id")))
                            selectedObjectId = null
                        }
                        invalidate()
                        return true
                    }
                    "rect", "line", "draw", "brush" -> {
                        drawing = true
                        strokePoints.put(JSONObject().put("x", startX).put("y", startY))
                        invalidate()
                        return true
                    }
                }
                return false
            }
            MotionEvent.ACTION_MOVE -> {
                val p = virtualPoint(event.x, event.y)
                endX = p.first; endY = p.second
                if (tool == "select" && moving && movingObjectId != null) {
                    controller.execute("move_shape", JSONObject().put("objectId", movingObjectId)
                        .put("x", endX - moveOffsetX).put("y", endY - moveOffsetY))
                } else if (drawing) {
                    if (tool == "draw" || tool == "brush") {
                        strokePoints.put(JSONObject().put("x", endX).put("y", endY))
                    }
                }
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP -> {
                val p = virtualPoint(event.x, event.y)
                endX = p.first; endY = p.second
                when {
                    tool == "select" -> {
                        if (moving && movingObjectId != null) {
                            controller.execute("move_shape", JSONObject().put("objectId", movingObjectId)
                                .put("x", endX - moveOffsetX).put("y", endY - moveOffsetY))
                        }
                        moving = false; movingObjectId = null; invalidate(); return true
                    }
                    tool == "rect" -> controller.execute("add_shape", JSONObject().put("type", "rect")
                        .put("x", minOf(startX, endX)).put("y", minOf(startY, endY))
                        .put("width", kotlin.math.abs(endX - startX).coerceAtLeast(2f))
                        .put("height", kotlin.math.abs(endY - startY).coerceAtLeast(2f)).put("color", "#F59E0B"))
                    tool == "line" -> controller.execute("add_shape", JSONObject().put("type", "line")
                        .put("x", startX).put("y", startY).put("width", endX - startX)
                        .put("height", endY - startY).put("color", "#F59E0B"))
                    tool == "draw" || tool == "brush" -> {
                        if (strokePoints.length() > 0) controller.execute("add_shape", JSONObject()
                            .put("type", "stroke").put("x", minOf(startX, endX)).put("y", minOf(startY, endY))
                            .put("width", kotlin.math.abs(endX - startX)).put("height", kotlin.math.abs(endY - startY))
                            .put("strokeWidth", if (tool == "brush") 14 else 5).put("points", JSONArray(strokePoints.toString()))
                            .put("color", "#F59E0B"))
                    }
                }
                drawing = false; strokePoints.length().let { while (strokePoints.length() > 0) strokePoints.remove(strokePoints.length() - 1) }
                invalidate()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                drawing = false; moving = false; movingObjectId = null
                while (strokePoints.length() > 0) strokePoints.remove(strokePoints.length() - 1)
                invalidate(); return true
            }
        }
        return true
    }

    fun captureBase64(): String {
        val bitmap = android.graphics.Bitmap.createBitmap(width.coerceAtLeast(1),
            height.coerceAtLeast(1), android.graphics.Bitmap.Config.ARGB_8888)
        draw(Canvas(bitmap))
        val output = java.io.ByteArrayOutputStream()
        bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output)
        return android.util.Base64.encodeToString(output.toByteArray(), android.util.Base64.NO_WRAP)
    }
}
