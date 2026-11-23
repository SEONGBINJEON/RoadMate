package com.roadmate.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import com.roadmate.app.core.DrowsyMap

/**
 * 졸음 지도를 고속도로 한 줄 그림으로 보여주는 화면.
 * 가로선 = 노선, 빨간 띠 = 졸음 다발 구간(굵을수록 신호가 많음), 파란 점 = 내 차 위치.
 */
class DrowsyMapView(context: Context, private val map: DrowsyMap) : View(context) {

    /** 내 차의 노선 위 위치(km). 바꾸면 다시 그린다. */
    var carKm: Int = 0
        set(value) { field = value.coerceIn(0, map.routeLengthKm); invalidate() }

    private val road = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#3A4552"); strokeWidth = 28f; strokeCap = Paint.Cap.ROUND }
    private val spot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#FF5A5F") }
    private val car = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#4DA3FF") }
    private val carRing = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 6f }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#B8C4CC"); textSize = 30f; textAlign = Paint.Align.CENTER }
    private val spotLabel = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#FFB3B5"); textSize = 30f; textAlign = Paint.Align.CENTER }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, 300)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val left = 60f
        val right = width - 60f
        val y = height / 2f
        fun x(km: Int) = left + (right - left) * km / map.routeLengthKm

        canvas.drawLine(left, y, right, y, road)

        val maxReports = map.hotspots.maxOf { it.reports }.toFloat()
        map.hotspots.forEach { h ->
            val thickness = 24f + 36f * (h.reports / maxReports)
            canvas.drawRoundRect(RectF(x(h.startKm), y - thickness / 2, x(h.endKm), y + thickness / 2), 12f, 12f, spot)
            canvas.drawText(h.name, (x(h.startKm) + x(h.endKm)) / 2, y - 50f, spotLabel)
            canvas.drawText("졸음 ${h.reports}회", (x(h.startKm) + x(h.endKm)) / 2, y + 70f, spotLabel)
        }

        canvas.drawText("서울", left, y + 110f, label)
        canvas.drawText("대전", right, y + 110f, label)

        val cx = x(carKm)
        canvas.drawCircle(cx, y, 22f, car)
        canvas.drawCircle(cx, y, 22f, carRing)
        canvas.drawText("내 차 ${carKm}km", cx, y - 100f, label)
    }
}
