package com.threecushion.billiards.ui

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader

/**
 * 2.5D drawing helpers: a fixed lamp above and to the upper left of the table,
 * so every ball gets the same highlight and a soft shadow down and to the right.
 */
object Shading {
    private val clear = Color.argb(0, 0, 0, 0)

    fun mix(c: Int, other: Int, t: Float): Int {
        fun ch(a: Int, b: Int) = (a + (b - a) * t).toInt().coerceIn(0, 255)
        return Color.rgb(ch(Color.red(c), Color.red(other)), ch(Color.green(c), Color.green(other)), ch(Color.blue(c), Color.blue(other)))
    }

    fun lighter(c: Int, t: Float) = mix(c, Color.WHITE, t)
    fun darker(c: Int, t: Float) = mix(c, Color.rgb(0, 0, 0), t)

    fun ballShadow(canvas: Canvas, paint: Paint, x: Float, y: Float, r: Float) {
        paint.style = Paint.Style.FILL
        val cx = x + r * 0.3f
        val cy = y + r * 0.42f
        paint.shader = RadialGradient(cx, cy, r * 1.35f, intArrayOf(Color.argb(120, 0, 0, 0), Color.argb(60, 0, 0, 0), clear), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        canvas.drawCircle(cx, cy, r * 1.35f, paint)
        paint.shader = null
    }

    /** A lit sphere: bright toward the lamp, dark rim on the far side, a sharp specular spot and a faint bounce light. */
    fun ball(canvas: Canvas, paint: Paint, x: Float, y: Float, r: Float, color: Int, shadow: Boolean = true) {
        if (shadow) ballShadow(canvas, paint, x, y, r)
        paint.style = Paint.Style.FILL
        paint.shader = RadialGradient(
            x - r * 0.38f, y - r * 0.42f, r * 1.75f,
            intArrayOf(lighter(color, 0.55f), lighter(color, 0.12f), color, darker(color, 0.55f)),
            floatArrayOf(0f, 0.25f, 0.55f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawCircle(x, y, r, paint)
        // Light bounced off the cloth on the lower right edge.
        paint.shader = RadialGradient(x + r * 0.55f, y + r * 0.6f, r * 0.7f, intArrayOf(Color.argb(55, 255, 255, 255), clear), null, Shader.TileMode.CLAMP)
        canvas.drawCircle(x, y, r, paint)
        paint.shader = RadialGradient(x - r * 0.36f, y - r * 0.4f, r * 0.34f, intArrayOf(Color.argb(240, 255, 255, 255), Color.argb(90, 255, 255, 255), clear), floatArrayOf(0f, 0.4f, 1f), Shader.TileMode.CLAMP)
        canvas.drawCircle(x - r * 0.36f, y - r * 0.4f, r * 0.34f, paint)
        paint.shader = null
    }

    /** Cloth under a lamp: brighter in the middle, falling off toward the cushions. */
    fun cloth(canvas: Canvas, paint: Paint, rect: RectF, color: Int) {
        paint.style = Paint.Style.FILL
        paint.shader = RadialGradient(
            rect.centerX(), rect.centerY(), rect.width() * 0.62f,
            intArrayOf(lighter(color, 0.12f), color, darker(color, 0.3f)),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawRect(rect, paint)
        paint.shader = null
    }

    /** Rubber cushions: lit on top, shadowed at the nose where they meet the cloth. */
    fun cushions(canvas: Canvas, paint: Paint, cloth: RectF, width: Float, color: Int) {
        paint.style = Paint.Style.FILL
        val top = lighter(color, 0.1f)
        val nose = darker(color, 0.45f)
        fun side(l: Float, t: Float, r: Float, b: Float, x0: Float, y0: Float, x1: Float, y1: Float) {
            paint.shader = LinearGradient(x0, y0, x1, y1, intArrayOf(top, nose), null, Shader.TileMode.CLAMP)
            canvas.drawRect(l, t, r, b, paint)
        }
        side(cloth.left - width, cloth.top - width, cloth.right + width, cloth.top, 0f, cloth.top - width, 0f, cloth.top)
        side(cloth.left - width, cloth.bottom, cloth.right + width, cloth.bottom + width, 0f, cloth.bottom + width, 0f, cloth.bottom)
        side(cloth.left - width, cloth.top, cloth.left, cloth.bottom, cloth.left - width, 0f, cloth.left, 0f)
        side(cloth.right, cloth.top, cloth.right + width, cloth.bottom, cloth.right + width, 0f, cloth.right, 0f)
        paint.shader = null
        // Shadow the cushions throw onto the cloth.
        val s = width * 0.9f
        val shade = Color.argb(70, 0, 0, 0)
        paint.shader = LinearGradient(0f, cloth.top, 0f, cloth.top + s, intArrayOf(shade, clear), null, Shader.TileMode.CLAMP)
        canvas.drawRect(cloth.left, cloth.top, cloth.right, cloth.top + s, paint)
        paint.shader = LinearGradient(cloth.left, 0f, cloth.left + s, 0f, intArrayOf(shade, clear), null, Shader.TileMode.CLAMP)
        canvas.drawRect(cloth.left, cloth.top, cloth.left + s, cloth.bottom, paint)
        paint.shader = null
    }

    /** Wooden rail with a rounded top: highlight along the middle, darker toward both edges. */
    fun rail(canvas: Canvas, paint: Paint, outer: RectF, inner: RectF, corner: Float) {
        val wood = Color.rgb(104, 52, 26)
        paint.style = Paint.Style.FILL
        paint.shader = LinearGradient(0f, outer.top, 0f, outer.bottom, intArrayOf(lighter(wood, 0.18f), wood, darker(wood, 0.35f)), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
        canvas.drawRoundRect(outer, corner, corner, paint)
        paint.shader = null
        // Bevels: a light line on the outer top edge and a dark lip on the inner edge.
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = corner * 0.18f
        paint.color = Color.argb(70, 255, 230, 200)
        val o = paint.strokeWidth
        canvas.drawRoundRect(RectF(outer.left + o, outer.top + o, outer.right - o, outer.bottom - o), corner, corner, paint)
        paint.color = Color.argb(110, 0, 0, 0)
        canvas.drawRect(inner, paint)
        // A few grain lines along the long rails.
        paint.strokeWidth = corner * 0.06f
        paint.color = Color.argb(28, 0, 0, 0)
        val railTop = inner.top - outer.top
        for (i in 1..3) {
            val dy = railTop * i / 4f
            canvas.drawLine(outer.left + corner, outer.top + dy, outer.right - corner, outer.top + dy + corner * 0.1f * i, paint)
            canvas.drawLine(outer.left + corner, outer.bottom - dy, outer.right - corner, outer.bottom - dy - corner * 0.1f * i, paint)
        }
        paint.style = Paint.Style.FILL
    }

    fun diamond(canvas: Canvas, paint: Paint, x: Float, y: Float, r: Float) {
        paint.style = Paint.Style.FILL
        paint.shader = RadialGradient(x - r * 0.3f, y - r * 0.3f, r * 1.4f, intArrayOf(Color.WHITE, Color.rgb(220, 210, 190), Color.rgb(150, 140, 120)), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
        canvas.drawCircle(x, y, r, paint)
        paint.shader = null
    }

    /** A round stick from (x0, y0) to (x1, y1), shaded across its width like a cylinder. */
    fun cylinder(canvas: Canvas, paint: Paint, x0: Float, y0: Float, x1: Float, y1: Float, width: Float, color: Int) {
        val dx = x1 - x0
        val dy = y1 - y0
        val len = kotlin.math.sqrt(dx * dx + dy * dy).coerceAtLeast(1e-3f)
        // Normal pointing toward the lamp (up and left).
        var nx = -dy / len
        var ny = dx / len
        if (nx + ny > 0) {
            nx = -nx
            ny = -ny
        }
        val h = width / 2
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = width
        paint.shader = LinearGradient(
            x0 + nx * h, y0 + ny * h, x0 - nx * h, y0 - ny * h,
            intArrayOf(lighter(color, 0.2f), lighter(color, 0.55f), color, darker(color, 0.5f)),
            floatArrayOf(0f, 0.25f, 0.55f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawLine(x0, y0, x1, y1, paint)
        paint.shader = null
        paint.style = Paint.Style.FILL
    }

    fun bead(canvas: Canvas, paint: Paint, rect: RectF, color: Int) {
        paint.style = Paint.Style.FILL
        paint.shader = LinearGradient(rect.left, 0f, rect.right, 0f, intArrayOf(lighter(color, 0.45f), color, darker(color, 0.45f)), floatArrayOf(0f, 0.4f, 1f), Shader.TileMode.CLAMP)
        canvas.drawRoundRect(rect, rect.width() / 2, rect.width() / 2, paint)
        paint.shader = null
    }
}
