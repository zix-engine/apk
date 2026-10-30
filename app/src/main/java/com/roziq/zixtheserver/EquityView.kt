package com.roziq.zixtheserver

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View

/** Grafik equity curve (kumulatif R) dengan skala otomatis dan garis nol. */
class EquityView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private var data: List<Double> = emptyList()
    private val density = resources.displayMetrics.density
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.2f * density
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
        color = context.zColor(R.attr.zAccent)
    }
    private val zero = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f * density
        color = context.zColor(R.attr.zStroke)
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 10f * density
        color = context.zColor(R.attr.zTextDim)
    }
    private val path = Path()

    fun setData(values: List<Double>) {
        data = values
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        if (data.size < 2) {
            canvas.drawText("—", w / 2f, h / 2f, text)
            return
        }
        val pad = 6f * density
        var lo = minOf(0.0, data.min())
        var hi = maxOf(0.0, data.max())
        if (hi - lo < 1e-9) {
            hi += 1.0
            lo -= 1.0
        }
        fun y(v: Double) = (pad + (hi - v) / (hi - lo) * (h - 2 * pad)).toFloat()
        fun x(i: Int) = pad + i.toFloat() / (data.size - 1) * (w - 2 * pad)
        val y0 = y(0.0)
        canvas.drawLine(0f, y0, w, y0, zero)
        path.reset()
        data.forEachIndexed { i, v -> if (i == 0) path.moveTo(x(i), y(v)) else path.lineTo(x(i), y(v)) }
        canvas.drawPath(path, line)
        canvas.drawText(String.format("%+.1fR", hi), pad, pad + text.textSize, text)
        canvas.drawText(String.format("%+.1fR", lo), pad, h - pad, text)
    }
}
