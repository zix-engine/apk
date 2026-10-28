package com.roziq.zixtheserver

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View

/** Grafik garis mini (0..100) untuk riwayat CPU dan RAM. */
class SparkView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private var a: List<Int> = emptyList()
    private var b: List<Int> = emptyList()
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f
        color = context.zColor(R.attr.zStroke)
    }
    private val paintA = linePaint(context.zColor(R.attr.zAccent))
    private val paintB = linePaint(context.zColor(R.attr.zWarn))
    private val path = Path()

    private fun linePaint(c: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3.5f * resources.displayMetrics.density / 2f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = c
    }

    fun setData(cpu: List<Int>, ram: List<Int>) {
        a = cpu
        b = ram
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        for (g in 0..2) {
            val y = h * g / 2f
            canvas.drawLine(0f, y, w, y, gridPaint)
        }
        plot(canvas, a, paintA, w, h)
        plot(canvas, b, paintB, w, h)
    }

    private fun plot(canvas: Canvas, data: List<Int>, paint: Paint, w: Float, h: Float) {
        if (data.size < 2) return
        path.reset()
        val step = w / (MAX_POINTS - 1)
        val startX = w - step * (data.size - 1)
        data.forEachIndexed { i, v ->
            val x = startX + step * i
            val y = h - (v.coerceIn(0, 100) / 100f) * h
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        canvas.drawPath(path, paint)
    }

    companion object {
        const val MAX_POINTS = 30
    }
}
