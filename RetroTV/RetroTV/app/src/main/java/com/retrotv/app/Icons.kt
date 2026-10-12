package com.retrotv.app

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import kotlin.math.cos
import kotlin.math.sin

/** Simple line icons drawn in code (24x24 design grid), so the app needs no icon files. */
enum class Icon { HOME, GAMEPAD, PC, SETTINGS, PLUS, GAUGE, STAR, STAR_FILLED, PLAY, WIFI, TRASH, REFRESH, INFO, QR, TV, CHEVRON, LINK, IMAGE, FOLDER, SOUND }

class IconDrawable(private val icon: Icon, color: Int) : Drawable() {

    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = 2f
        this.color = color
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        this.color = color
    }

    fun setColor(c: Int) {
        stroke.color = c
        fill.color = c
        invalidateSelf()
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        val s = minOf(b.width(), b.height()) / 24f
        canvas.save()
        canvas.translate(b.left + (b.width() - 24 * s) / 2, b.top + (b.height() - 24 * s) / 2)
        canvas.scale(s, s)
        val p = Path()
        when (icon) {
            Icon.HOME -> {
                p.moveTo(3f, 11f); p.lineTo(12f, 3.5f); p.lineTo(21f, 11f)
                canvas.drawPath(p, stroke)
                canvas.drawRoundRect(RectF(5.5f, 9.5f, 18.5f, 20.5f), 1.5f, 1.5f, stroke)
                canvas.drawLine(10f, 20.5f, 10f, 15f, stroke); canvas.drawLine(10f, 15f, 14f, 15f, stroke); canvas.drawLine(14f, 15f, 14f, 20.5f, stroke)
            }
            Icon.GAMEPAD -> {
                canvas.drawRoundRect(RectF(2f, 7f, 22f, 18f), 5.5f, 5.5f, stroke)
                canvas.drawLine(5.5f, 12.5f, 9.5f, 12.5f, stroke); canvas.drawLine(7.5f, 10.5f, 7.5f, 14.5f, stroke)
                canvas.drawCircle(15.5f, 11.2f, 1.2f, fill); canvas.drawCircle(18f, 13.8f, 1.2f, fill)
            }
            Icon.PC -> {
                canvas.drawRoundRect(RectF(3f, 4f, 21f, 15.5f), 1.5f, 1.5f, stroke)
                canvas.drawLine(12f, 15.5f, 12f, 19.5f, stroke); canvas.drawLine(8f, 20f, 16f, 20f, stroke)
            }
            Icon.TV -> {
                canvas.drawRoundRect(RectF(2.5f, 5f, 21.5f, 17f), 1.5f, 1.5f, stroke)
                canvas.drawLine(7f, 20f, 17f, 20f, stroke)
            }
            Icon.SETTINGS -> {
                canvas.drawCircle(12f, 12f, 3f, stroke)
                canvas.drawCircle(12f, 12f, 6.8f, stroke)
                for (i in 0 until 8) {
                    val a = Math.toRadians(i * 45.0)
                    canvas.drawLine(12 + 7.3f * cos(a).toFloat(), 12 + 7.3f * sin(a).toFloat(),
                        12 + 9.8f * cos(a).toFloat(), 12 + 9.8f * sin(a).toFloat(), stroke.apply { strokeWidth = 2.6f })
                }
                stroke.strokeWidth = 2f
            }
            Icon.PLUS -> { canvas.drawLine(12f, 5f, 12f, 19f, stroke); canvas.drawLine(5f, 12f, 19f, 12f, stroke) }
            Icon.GAUGE -> {
                canvas.drawArc(RectF(3.5f, 5.5f, 20.5f, 22.5f), 160f, 220f, false, stroke)
                canvas.drawLine(12f, 14f, 16.5f, 9.5f, stroke)
                canvas.drawCircle(12f, 14f, 1.4f, fill)
            }
            Icon.STAR, Icon.STAR_FILLED -> {
                for (i in 0 until 10) {
                    val r = if (i % 2 == 0) 9.5f else 4.2f
                    val a = Math.toRadians(-90.0 + i * 36)
                    val x = 12 + r * cos(a).toFloat(); val y = 12.8f + r * sin(a).toFloat()
                    if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
                }
                p.close()
                canvas.drawPath(p, if (icon == Icon.STAR_FILLED) fill else stroke)
            }
            Icon.PLAY -> { p.moveTo(8f, 5f); p.lineTo(19f, 12f); p.lineTo(8f, 19f); p.close(); canvas.drawPath(p, fill) }
            Icon.WIFI -> {
                canvas.drawArc(RectF(2f, 5f, 22f, 25f), 225f, 90f, false, stroke)
                canvas.drawArc(RectF(5.5f, 8.5f, 18.5f, 21.5f), 225f, 90f, false, stroke)
                canvas.drawArc(RectF(9f, 12f, 15f, 18f), 225f, 90f, false, stroke)
                canvas.drawCircle(12f, 19f, 1.3f, fill)
            }
            Icon.TRASH -> {
                canvas.drawLine(4f, 6.5f, 20f, 6.5f, stroke); canvas.drawLine(9.5f, 6.5f, 10f, 4f, stroke)
                canvas.drawLine(10f, 4f, 14f, 4f, stroke); canvas.drawLine(14f, 4f, 14.5f, 6.5f, stroke)
                canvas.drawRoundRect(RectF(6.5f, 6.5f, 17.5f, 20.5f), 1.5f, 1.5f, stroke)
                canvas.drawLine(10f, 10f, 10f, 17f, stroke); canvas.drawLine(14f, 10f, 14f, 17f, stroke)
            }
            Icon.REFRESH -> {
                canvas.drawArc(RectF(4.5f, 4.5f, 19.5f, 19.5f), -60f, 300f, false, stroke)
                p.moveTo(19.8f, 4.5f); p.lineTo(19f, 9.5f); p.lineTo(14.2f, 8.4f)
                canvas.drawPath(p, stroke)
            }
            Icon.INFO -> {
                canvas.drawCircle(12f, 12f, 9f, stroke)
                canvas.drawLine(12f, 11f, 12f, 16.5f, stroke); canvas.drawCircle(12f, 7.8f, 1.2f, fill)
            }
            Icon.QR -> {
                for ((x, y) in listOf(4f to 4f, 14f to 4f, 4f to 14f)) {
                    canvas.drawRect(x, y, x + 6f, y + 6f, stroke)
                    canvas.drawRect(x + 2.2f, y + 2.2f, x + 3.8f, y + 3.8f, fill)
                }
                canvas.drawRect(14f, 14f, 16f, 16f, fill); canvas.drawRect(18f, 14f, 20f, 16f, fill)
                canvas.drawRect(16f, 16f, 18f, 18f, fill); canvas.drawRect(14f, 18f, 16f, 20f, fill); canvas.drawRect(18f, 18f, 20f, 20f, fill)
            }
            Icon.CHEVRON -> { p.moveTo(9f, 5f); p.lineTo(16f, 12f); p.lineTo(9f, 19f); canvas.drawPath(p, stroke) }
            Icon.LINK -> {
                canvas.drawRoundRect(RectF(2.5f, 8.5f, 13.5f, 15.5f), 3.5f, 3.5f, stroke)
                canvas.drawRoundRect(RectF(10.5f, 8.5f, 21.5f, 15.5f), 3.5f, 3.5f, stroke)
            }
            Icon.IMAGE -> {
                canvas.drawRoundRect(RectF(3f, 4.5f, 21f, 19.5f), 2f, 2f, stroke)
                p.moveTo(4f, 17f); p.lineTo(9.5f, 11.5f); p.lineTo(13f, 15f); p.lineTo(15.5f, 12.5f); p.lineTo(20f, 17f)
                canvas.drawPath(p, stroke); canvas.drawCircle(15.5f, 8.5f, 1.5f, fill)
            }
            Icon.SOUND -> {
                p.moveTo(3.5f, 9.5f); p.lineTo(7.5f, 9.5f); p.lineTo(12.5f, 5f); p.lineTo(12.5f, 19f)
                p.lineTo(7.5f, 14.5f); p.lineTo(3.5f, 14.5f); p.close()
                canvas.drawPath(p, stroke)
                canvas.drawArc(RectF(11f, 8f, 18f, 16f), -50f, 100f, false, stroke)
                canvas.drawArc(RectF(11f, 4.5f, 21.5f, 19.5f), -50f, 100f, false, stroke)
            }
            Icon.FOLDER -> {
                p.moveTo(3f, 7f); p.lineTo(3f, 19f); p.lineTo(21f, 19f); p.lineTo(21f, 8.5f); p.lineTo(11.5f, 8.5f)
                p.lineTo(9.5f, 5.5f); p.lineTo(3f, 5.5f); p.close()
                canvas.drawPath(p, stroke)
            }
        }
        canvas.restore()
    }

    override fun setAlpha(alpha: Int) {
        stroke.alpha = alpha
        fill.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        stroke.colorFilter = colorFilter
        fill.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}
