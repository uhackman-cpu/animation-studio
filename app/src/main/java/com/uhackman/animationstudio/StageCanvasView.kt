package com.uhackman.animationstudio

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import org.json.JSONObject
import java.util.Locale

class StageCanvasView(context: Context, private val controller: EditorController) : View(context) {
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2f }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x223C4659; strokeWidth = 1f }
    private var startX = 0f
    private var startY = 0f
    private var endX = 0f
    private var endY = 0f
    private var drawing = false

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
        val divisions = 12
        for (i in 1 until divisions) {
            val x = virtualWidth * i / divisions
            val y = virtualHeight * i / divisions
            canvas.drawLine(x, 0f, x, virtualHeight, gridPaint)
            canvas.drawLine(0f, y, virtualWidth, y, gridPaint)
        }
        val layers = state.optJSONArray("layers")
        if (layers != null) {
            for (i in 0 until layers.length()) {
                val layer = layers.optJSONObject(i) ?: continue
                if (!layer.optBoolean("visible", true)) continue
                val objects = controller.renderedObjects(layer)
                for (j in 0 until objects.length()) drawObject(canvas, objects.optJSONObject(j) ?: continue, scale)
            }
        }
        if (drawing) {
            strokePaint.color = Color.rgb(245, 158, 11)
            strokePaint.strokeWidth = 5f / scale
            if (state.optString("tool") == "line" || state.optString("tool") == "draw") {
                canvas.drawLine(startX, startY, endX, endY, strokePaint)
            } else {
                canvas.drawRect(minOf(startX, endX), minOf(startY, endY),
                    maxOf(startX, endX), maxOf(startY, endY), strokePaint)
            }
        }
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
        strokePaint.strokeWidth = shape.optDouble("strokeWidth", 5.0).toFloat() / scale
        val bounds = RectF(x, y, x + w, y + h)
        when (shape.optString("type", "rect").lowercase(Locale.US)) {
            "circle", "ellipse" -> canvas.drawOval(bounds, fillPaint)
            "line", "stroke" -> canvas.drawLine(x, y, x + w, y + h, strokePaint)
            "text" -> {
                fillPaint.textSize = shape.optDouble("fontSize", 64.0).toFloat()
                canvas.drawText(shape.optString("text", "Text"), x, y + fillPaint.textSize, fillPaint)
            }
            else -> canvas.drawRect(bounds, fillPaint)
        }
    }

    private fun virtualPoint(rawX: Float, rawY: Float): Pair<Float, Float> {
        val state = controller.state()
        val virtualWidth = state.optInt("width", 1920).toFloat().coerceAtLeast(1f)
        val virtualHeight = state.optInt("height", 1080).toFloat().coerceAtLeast(1f)
        val scale = minOf(width / virtualWidth, height / virtualHeight).coerceAtLeast(0.01f)
        val left = (width - virtualWidth * scale) / 2f
        val top = (height - virtualHeight * scale) / 2f
        return (((rawX - left) / scale).coerceIn(0f, virtualWidth)) to
            (((rawY - top) / scale).coerceIn(0f, virtualHeight))
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val tool = controller.state().optString("tool", "select")
        if (tool != "rect" && tool != "line" && tool != "draw") return false
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                val point = virtualPoint(event.x, event.y)
                startX = point.first; startY = point.second
                endX = startX; endY = startY
                drawing = true
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val point = virtualPoint(event.x, event.y)
                endX = point.first; endY = point.second
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP -> {
                val point = virtualPoint(event.x, event.y)
                endX = point.first; endY = point.second
                if (tool == "line" || tool == "draw") {
                    controller.execute("add_shape", JSONObject().put("type", "line")
                        .put("x", startX).put("y", startY)
                        .put("width", endX - startX).put("height", endY - startY)
                        .put("color", "#F59E0B"))
                } else {
                    controller.execute("add_shape", JSONObject().put("type", "rect")
                        .put("x", minOf(startX, endX)).put("y", minOf(startY, endY))
                        .put("width", kotlin.math.abs(endX - startX).coerceAtLeast(2f))
                        .put("height", kotlin.math.abs(endY - startY).coerceAtLeast(2f))
                        .put("color", "#F59E0B"))
                }
                drawing = false
                invalidate()
                return true
            }
            MotionEvent.ACTION_CANCEL -> { drawing = false; invalidate(); return true }
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
