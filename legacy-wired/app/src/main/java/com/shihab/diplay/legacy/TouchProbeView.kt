// SPDX-License-Identifier: GPL-3.0-only
package com.shihab.diplay.legacy

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View
import com.shilapi.xcertplay.airplay.AirPlayContact
import java.util.Locale

internal object TouchMapper {
    fun contacts(event: MotionEvent, width: Int, height: Int): List<AirPlayContact> {
        if (width <= 0 || height <= 0) return emptyList()
        return (0 until minOf(event.pointerCount, 2)).map { index ->
            val released = event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL ||
                (event.actionMasked == MotionEvent.ACTION_POINTER_UP && event.actionIndex == index)
            AirPlayContact(event.getPointerId(index), (event.getX(index) / width).toDouble().coerceIn(0.0, 1.0),
                (event.getY(index) / height).toDouble().coerceIn(0.0, 1.0), !released)
        }
    }
}

/** Uses the same normalized contacts as the live CarPlay touch path. */
internal class TouchProbeView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var contacts = emptyList<AirPlayContact>()
    private var events = 0
    private var releases = 0
    private var maximum = 0
    fun reset() { contacts = emptyList(); events = 0; releases = 0; maximum = 0; invalidate() }
    fun summary() = "触控自测：事件=$events，最大有效触点=$maximum，抬起/取消触点=$releases；请确认坐标、拖动和双指反馈"
    override fun onTouchEvent(event: MotionEvent): Boolean {
        contacts = TouchMapper.contacts(event, width, height)
        events++
        maximum = maxOf(maximum, contacts.count { it.down })
        releases += contacts.count { !it.down }
        invalidate()
        return true
    }
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val scale = minOf(MeasureSpec.getSize(widthMeasureSpec) / 800.0, MeasureSpec.getSize(heightMeasureSpec) / 480.0)
        setMeasuredDimension((800 * scale).toInt(), (480 * scale).toInt())
    }
    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Color.rgb(16, 22, 30))
        paint.color = Color.DKGRAY; paint.strokeWidth = 1f
        for (i in 1..9) {
            canvas.drawLine(width * i / 10f, 0f, width * i / 10f, height.toFloat(), paint)
            canvas.drawLine(0f, height * i / 10f, width.toFloat(), height * i / 10f, paint)
        }
        paint.color = Color.WHITE; paint.textSize = 18 * resources.displayMetrics.scaledDensity
        canvas.drawText("单击、拖动、双指；用菜单中的触控测试或停止按钮结束", 16f, 45f, paint)
        contacts.forEachIndexed { index, contact ->
            paint.color = if (index == 0) Color.CYAN else Color.YELLOW
            paint.style = if (contact.down) Paint.Style.FILL else Paint.Style.STROKE
            paint.strokeWidth = 4f
            canvas.drawCircle((contact.x * width).toFloat(), (contact.y * height).toFloat(), 24f, paint)
            paint.style = Paint.Style.FILL
            val text = String.format(Locale.US, "id=%d x=%.3f y=%.3f %s", contact.id, contact.x, contact.y, if (contact.down) "DOWN" else "UP")
            canvas.drawText(text, 16f, 80f + index * 30 * resources.displayMetrics.scaledDensity, paint)
        }
    }
}
