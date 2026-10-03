package com.threecushion.billiards.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.view.MotionEvent
import android.view.View
import com.threecushion.billiards.engine.Aim
import com.threecushion.billiards.engine.BallId
import com.threecushion.billiards.engine.Game
import com.threecushion.billiards.engine.GameMode
import com.threecushion.billiards.engine.Phase
import com.threecushion.billiards.engine.Replay
import com.threecushion.billiards.engine.Vec2
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The whole game screen: a stone score bar with abacus beads above and below,
 * and the table between them seen straight from above. The table never rotates;
 * you turn the phone around it, like walking around a real table.
 *
 * Controls (the finger rests on the cue, like a bridge hand):
 * 1. Touch the table behind the cue ball. The cue lies from the ball through your finger,
 *    so the ball will go the opposite way. Slide around the ball to aim.
 * 2. Pull the finger away from the ball: the cue draws back with it. The draw is the power.
 * 3. Keep holding and touch with a second finger: the big cue ball appears. Slide the
 *    second finger to move the tip. Lift it to keep that tip.
 * 4. Lift the first finger to shoot. Push back to where you started before lifting to cancel.
 */
class BilliardsView(context: Context) : View(context) {
    private var game = Game()
    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density

    // Shot setup.
    private var aimAngle = 0.0
    private var pullMeters = 0.0
    private val power get() = (pullMeters / MAX_PULL_METERS).coerceIn(0.0, 1.0)
    private var tipX = 0.0
    private var tipY = 0.0

    // Screens, options and animation.
    private var menuOpen = true
    private var menuMode = GameMode.THREE_CUSHION
    private val targetIndex = intArrayOf(0, 0)
    private var showPaths = true
    private var placing = false
    private var replayFrame = -1.0
    private var message = ""
    private var messageUntil = 0L
    private var lastFrameNanos = 0L

    /** Seconds left in the cue's forward stroke; the ball is struck when it reaches 0. */
    private var strokeLeft = -1.0
    private var strokePower = 0.0
    private var strokeFromMeters = 0.0

    // Layout (onSizeChanged).
    private var scale = 1f
    private val clothRect = RectF()
    private val railRect = RectF()
    private var railPx = 0f
    private val topBar = RectF()
    private val bottomBar = RectF()
    private val turnButton = RectF()
    private val placeButton = RectF()
    private val menuButton = RectF()
    private val tipButton = RectF()
    private val undoButton = RectF()
    private val replayButton = RectF()
    private val abacus = arrayOf(RectF(), RectF())
    private val menuItems = Array(5) { RectF() }
    private var speckles = FloatArray(0)

    // Touch state.
    private enum class Mode { NONE, CUE, TIP, TIP_BUTTON, BUTTON, ABACUS, PLACE }
    private var mode = Mode.NONE
    private var cuePointerId = -1
    private var cuePointerUp = false
    private var touchStartDist = 0.0
    private var aimLocked = false
    private var tipPointerId = -1
    private var tipLastX = 0f
    private var tipLastY = 0f
    private var pressedButton: RectF? = null
    private var abacusPlayer = 0
    private var abacusStartX = 0f
    private var abacusStartScore = 0
    private var placeBall: BallId? = null
    private var placeDX = 0.0
    private var placeDY = 0.0

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val path = Path()

    init {
        aimAtNearestRed()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val barH = h * 0.1f
        topBar.set(0f, 0f, w.toFloat(), barH)
        bottomBar.set(0f, h - barH, w.toFloat(), h.toFloat())

        // Table between the bars, as large as fits, keeping the 2:1 playing surface.
        val t = game.table
        val availH = h - 2 * barH
        railPx = availH * RAIL_FRAC
        scale = min((availH - 2 * railPx) / t.height, (w - 2 * railPx) / t.width).toFloat()
        val clothW = (t.width * scale).toFloat()
        val clothH = (t.height * scale).toFloat()
        val left = (w - clothW) / 2
        val top = barH + (availH - clothH) / 2
        clothRect.set(left, top, left + clothW, top + clothH)
        railRect.set(left - railPx, top - railPx, left + clothW + railPx, top + clothH + railPx)

        // Buttons sit at both ends of each bar, beads in between.
        val bh = barH * 0.74f
        val bw = bh * 1.75f
        val pad = (barH - bh) / 2
        val l0 = railRect.left.coerceAtMost(w * 0.04f)
        val r0 = railRect.right.coerceAtLeast(w * 0.96f)
        fun slot(bar: RectF, x: Float) = RectF(x, bar.top + pad, x + bw, bar.top + pad + bh)
        turnButton.set(slot(topBar, l0))
        menuButton.set(slot(topBar, r0 - bw))
        placeButton.set(slot(topBar, r0 - 2 * bw - pad * 2))
        tipButton.set(slot(bottomBar, l0))
        replayButton.set(slot(bottomBar, r0 - bw))
        undoButton.set(slot(bottomBar, r0 - 2 * bw - pad * 2))
        abacus[0].set(turnButton.right + pad * 3, topBar.top + pad, placeButton.left - pad * 3, topBar.bottom - pad)
        abacus[1].set(tipButton.right + pad * 3, bottomBar.top + pad, undoButton.left - pad * 3, bottomBar.bottom - pad)

        // Fixed specks for the stone texture of the bars.
        val rnd = java.util.Random(7)
        speckles = FloatArray(600) { rnd.nextFloat() }

        val itemW = min(w * 0.42f, dp(320f))
        val itemH = min(dp(44f), h * 0.11f)
        val gap = itemH * 0.22f
        val total = menuItems.size * itemH + (menuItems.size - 1) * gap
        var y = (h - total) / 2 + itemH * 0.4f
        for (item in menuItems) {
            item.set((w - itemW) / 2, y, (w + itemW) / 2, y + itemH)
            y += itemH + gap
        }
    }

    private fun sx(x: Double) = clothRect.left + (x * scale).toFloat()
    private fun sy(y: Double) = clothRect.top + (y * scale).toFloat()
    private val aimDir get() = Vec2(cos(aimAngle), sin(aimAngle))
    private val ballR get() = (game.table.ballRadius * scale).toFloat()

    private fun aimAtNearestRed() {
        val c = game.cueBall.pos
        val reds = game.balls.filter { it.id == BallId.RED || it.id == BallId.RED2 }
        val target = reds.minByOrNull { (it.pos - c).length() } ?: return
        aimAngle = atan2(target.pos.y - c.y, target.pos.x - c.x)
    }

    // ---------------------------------------------------------------- drawing

    override fun onDraw(canvas: Canvas) {
        val now = System.nanoTime()
        val dt = if (lastFrameNanos == 0L) 0.0 else min((now - lastFrameNanos) / 1e9, 0.05)
        lastFrameNanos = now
        if (strokeLeft >= 0) {
            strokeLeft -= dt
            if (strokeLeft < 0) strike()
        }
        if (game.phase == Phase.ROLLING) {
            game.update(dt)
            if (game.phase != Phase.ROLLING) onShotFinished()
        }
        if (replayFrame >= 0) {
            replayFrame += dt / Replay.FRAME_DT
            val frames = game.replay?.frames?.size ?: 0
            if (replayFrame >= frames + 60) replayFrame = -1.0
        }

        canvas.drawColor(Color.rgb(18, 14, 12))
        drawTable(canvas)
        drawBar(canvas, topBar)
        drawBar(canvas, bottomBar)
        drawControls(canvas)

        val replaying = replayFrame >= 0
        val aiming = game.phase == Phase.AIMING && !menuOpen && !placing
        if (replaying) {
            drawReplay(canvas)
        } else {
            if (showPaths && game.phase == Phase.AIMING) drawPaths(canvas, game.replay?.frames?.size ?: 0)
            if (aiming) drawAimDots(canvas)
            if (aiming || strokeLeft >= 0) drawCue(canvas, shadowOnly = true)
            game.balls.forEach { b -> Shading.ballShadow(canvas, paint, sx(b.pos.x), sy(b.pos.y), ballR) }
            game.balls.forEach { b -> Shading.ball(canvas, paint, sx(b.pos.x), sy(b.pos.y), ballR, ballColor(b.id), shadow = false) }
            if (aiming || strokeLeft >= 0) drawCue(canvas, shadowOnly = false)
        }
        if (mode == Mode.TIP || mode == Mode.TIP_BUTTON) drawTipView(canvas)
        if (placing) drawHint(canvas, "공을 끌어서 옮기세요 · 위쪽 버튼을 다시 누르면 끝")
        drawMessage(canvas)
        if (game.phase == Phase.GAME_OVER && !menuOpen) drawGameOver(canvas)
        if (menuOpen) drawMenu(canvas)

        if (game.phase == Phase.ROLLING || replaying || strokeLeft >= 0 || System.currentTimeMillis() < messageUntil) {
            postInvalidateOnAnimation()
        } else {
            lastFrameNanos = 0L
        }
    }

    private val threeCushion get() = game.mode == GameMode.THREE_CUSHION

    // Two looks, as in the reference: blue cloth with a black rail and brass caps for 3구,
    // green cloth with a mahogany rail and silver caps for 4구.
    private fun clothColor() = if (threeCushion) Color.rgb(92, 174, 250) else Color.rgb(86, 172, 116)
    private fun railColor() = if (threeCushion) Color.rgb(36, 24, 20) else Color.rgb(92, 32, 12)
    private fun capColor() = if (threeCushion) Color.rgb(220, 146, 64) else Color.rgb(200, 200, 196)
    private fun beadRed() = if (threeCushion) Color.rgb(128, 34, 26) else Color.rgb(214, 34, 30)

    private fun drawTable(canvas: Canvas) {
        val cloth = clothColor()
        val rail = railRect
        val c = railPx

        // Mahogany rail, lit along the outer edge and dark toward the cushion.
        val wood = railColor()
        paint.style = Paint.Style.FILL
        paint.color = Shading.darker(wood, 0.35f)
        canvas.drawRect(rail, paint)
        fun railSide(l: Float, t: Float, r: Float, b: Float, x0: Float, y0: Float, x1: Float, y1: Float) {
            paint.shader = LinearGradient(x0, y0, x1, y1, intArrayOf(Shading.lighter(wood, 0.16f), wood, Shading.darker(wood, 0.45f)), floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
            canvas.drawRect(l, t, r, b, paint)
        }
        railSide(rail.left, rail.top, rail.right, clothRect.top, 0f, rail.top, 0f, clothRect.top)
        railSide(rail.left, clothRect.bottom, rail.right, rail.bottom, 0f, rail.bottom, 0f, clothRect.bottom)
        railSide(rail.left, clothRect.top, clothRect.left, clothRect.bottom, rail.left, 0f, clothRect.left, 0f)
        railSide(clothRect.right, clothRect.top, rail.right, clothRect.bottom, rail.right, 0f, clothRect.right, 0f)
        paint.shader = null

        // Wood grain: faint wavy streaks along each rail.
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = max(1f, c * 0.04f)
        for (i in 0 until 9) {
            val f = (i + 0.5f) / 9f
            paint.color = if (i % 2 == 0) Color.argb(40, 0, 0, 0) else Color.argb(26, 255, 190, 140)
            path.reset()
            var x = rail.left + c
            path.moveTo(x, rail.top + c * f)
            while (x < rail.right - c) {
                x += c * 1.5f
                path.lineTo(x, rail.top + c * (f + 0.05f * sin(x * 0.05f + i)))
            }
            canvas.drawPath(path, paint)
            path.reset()
            x = rail.left + c
            path.moveTo(x, rail.bottom - c * f)
            while (x < rail.right - c) {
                x += c * 1.5f
                path.lineTo(x, rail.bottom - c * (f + 0.05f * sin(x * 0.04f + i * 2)))
            }
            canvas.drawPath(path, paint)
        }
        // Outer bevel highlight.
        paint.strokeWidth = max(1f, c * 0.06f)
        paint.color = Color.argb(70, 255, 210, 170)
        canvas.drawRect(rail.left + paint.strokeWidth, rail.top + paint.strokeWidth, rail.right - paint.strokeWidth, rail.bottom - paint.strokeWidth, paint)
        paint.style = Paint.Style.FILL

        // Cloth under a lamp.
        paint.shader = RadialGradient(
            clothRect.centerX(), clothRect.centerY(), clothRect.width() * 0.62f,
            intArrayOf(Shading.lighter(cloth, 0.1f), cloth, Shading.darker(cloth, 0.12f)),
            floatArrayOf(0f, 0.6f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawRect(clothRect, paint)
        paint.shader = null

        // Cushion nose: a lighter band of cloth along each rail with a thin dark edge.
        val cw = ballR * 0.45f
        paint.color = Shading.lighter(cloth, 0.22f)
        canvas.drawRect(clothRect.left, clothRect.top, clothRect.right, clothRect.top + cw, paint)
        canvas.drawRect(clothRect.left, clothRect.bottom - cw, clothRect.right, clothRect.bottom, paint)
        canvas.drawRect(clothRect.left, clothRect.top, clothRect.left + cw, clothRect.bottom, paint)
        canvas.drawRect(clothRect.right - cw, clothRect.top, clothRect.right, clothRect.bottom, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = max(1f, cw * 0.3f)
        paint.color = Color.argb(120, 0, 0, 0)
        canvas.drawRect(clothRect, paint)
        paint.style = Paint.Style.FILL

        // Diamonds: small white dots in the middle of the rails.
        val d = max(1.2f, c * 0.07f)
        paint.color = Color.rgb(236, 232, 222)
        for (i in 1..7) {
            val x = clothRect.left + clothRect.width() * i / 8
            canvas.drawCircle(x, rail.top + c * 0.5f, d, paint)
            canvas.drawCircle(x, rail.bottom - c * 0.5f, d, paint)
        }
        for (i in 1..3) {
            val y = clothRect.top + clothRect.height() * i / 4
            canvas.drawCircle(rail.left + c * 0.5f, y, d, paint)
            canvas.drawCircle(rail.right - c * 0.5f, y, d, paint)
        }

        // Metal corner caps.
        val metal = capColor()
        corner(canvas, rail.left, rail.top, 1f, 1f, metal)
        corner(canvas, rail.right, rail.top, -1f, 1f, metal)
        corner(canvas, rail.left, rail.bottom, 1f, -1f, metal)
        corner(canvas, rail.right, rail.bottom, -1f, -1f, metal)
    }

    /** A corner cap: an L-shaped plate wrapped around the rail corner, shaded like rounded metal. */
    private fun corner(canvas: Canvas, x: Float, y: Float, sx: Float, sy: Float, color: Int) {
        val c = railPx
        val a = c * 1.25f
        // Flat along the two outer edges, a rounded knob toward the cloth.
        path.reset()
        path.moveTo(x, y)
        val ox = c * 0.32f
        val rad = c * 0.88f
        val a0 = -0.12 * PI
        val a1 = 0.62 * PI
        for (k in 0..12) {
            val ang = a0 + (a1 - a0) * k / 12
            path.lineTo(x + sx * (ox + rad * cos(ang).toFloat()), y + sy * (ox + rad * sin(ang).toFloat()))
        }
        path.close()
        paint.style = Paint.Style.FILL
        paint.shader = RadialGradient(x + sx * c * 0.3f, y + sy * c * 0.3f, a * 1.1f, intArrayOf(Shading.lighter(color, 0.6f), color, Shading.darker(color, 0.45f)), floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
        canvas.drawPath(path, paint)
        paint.shader = null
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = max(1f, c * 0.05f)
        paint.color = Color.argb(110, 0, 0, 0)
        canvas.drawPath(path, paint)
        paint.style = Paint.Style.FILL
    }

    /** Stone-textured bar across the whole screen. */
    private fun drawBar(canvas: Canvas, bar: RectF) {
        paint.style = Paint.Style.FILL
        paint.shader = LinearGradient(0f, bar.top, 0f, bar.bottom, intArrayOf(Color.rgb(196, 188, 170), Color.rgb(170, 160, 142), Color.rgb(140, 130, 112)), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        canvas.drawRect(bar, paint)
        paint.shader = null
        var i = 0
        while (i + 2 < speckles.size) {
            val x = bar.left + speckles[i] * bar.width()
            val y = bar.top + speckles[i + 1] * bar.height()
            paint.color = if (speckles[i + 2] > 0.5f) Color.argb(26, 60, 50, 40) else Color.argb(36, 255, 255, 255)
            canvas.drawCircle(x, y, bar.height() * (0.01f + speckles[i + 2] * 0.025f), paint)
            i += 3
        }
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = max(1.5f, bar.height() * 0.04f)
        paint.color = Color.argb(110, 40, 32, 24)
        val inset = paint.strokeWidth / 2
        canvas.drawRect(bar.left + inset, bar.top + inset, bar.right - inset, bar.bottom - inset, paint)
        paint.style = Paint.Style.FILL
    }

    private fun drawControls(canvas: Canvas) {
        drawButton(canvas, turnButton) {
            val r = it.height() * 0.3f
            Shading.ball(canvas, paint, it.centerX(), it.centerY(), r, ballColor(game.cueBallId), shadow = false)
        }
        drawButton(canvas, placeButton, active = placing) { iconRack(canvas, it) }
        drawButton(canvas, menuButton) { iconMenu(canvas, it) }
        drawButton(canvas, tipButton, active = mode == Mode.TIP_BUTTON) { iconTip(canvas, it) }
        drawButton(canvas, undoButton, enabled = game.canUndo) { iconUndo(canvas, it) }
        drawButton(canvas, replayButton, enabled = game.replay != null && game.phase != Phase.ROLLING) { iconCamera(canvas, it) }
        drawAbacus(canvas, abacus[0], game.scores[0])
        drawAbacus(canvas, abacus[1], game.scores[1])
    }

    private fun drawButton(canvas: Canvas, rect: RectF, enabled: Boolean = true, active: Boolean = false, icon: (RectF) -> Unit) {
        val pressed = pressedButton === rect || active
        val base = if (pressed) Color.rgb(128, 118, 102) else Color.rgb(166, 156, 138)
        val corner = rect.height() * 0.22f
        paint.style = Paint.Style.FILL
        paint.color = Color.argb(70, 0, 0, 0)
        canvas.drawRoundRect(RectF(rect.left, rect.top + 1.5f, rect.right, rect.bottom + 1.5f), corner, corner, paint)
        paint.shader = LinearGradient(0f, rect.top, 0f, rect.bottom, intArrayOf(Shading.lighter(base, if (pressed) 0f else 0.2f), Shading.darker(base, 0.12f)), null, Shader.TileMode.CLAMP)
        canvas.drawRoundRect(rect, corner, corner, paint)
        paint.shader = null
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1f
        paint.color = Color.argb(60, 0, 0, 0)
        canvas.drawRoundRect(rect, corner, corner, paint)
        paint.style = Paint.Style.FILL
        iconColor = if (enabled) Color.rgb(58, 52, 46) else Color.argb(90, 58, 52, 46)
        icon(rect)
    }

    private var iconColor = Color.BLACK

    private fun iconStroke(rect: RectF) {
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = rect.height() * 0.08f
        paint.strokeCap = Paint.Cap.ROUND
        paint.color = iconColor
    }

    private fun iconDone() {
        paint.style = Paint.Style.FILL
        paint.strokeCap = Paint.Cap.BUTT
    }

    private fun iconMenu(canvas: Canvas, r: RectF) {
        iconStroke(r)
        val w = r.height() * 0.32f
        for (k in -1..1) {
            val y = r.centerY() + k * r.height() * 0.17f
            canvas.drawLine(r.centerX() - w, y, r.centerX() + w, y, paint)
        }
        iconDone()
    }

    /** Ball-placement icon: a ball resting on a cue rest. */
    private fun iconRack(canvas: Canvas, r: RectF) {
        iconStroke(r)
        val h = r.height()
        val base = r.centerY() + h * 0.2f
        canvas.drawLine(r.centerX() - h * 0.42f, base, r.centerX() + h * 0.42f, base, paint)
        iconDone()
        paint.color = iconColor
        path.reset()
        path.moveTo(r.centerX() - h * 0.2f, base)
        path.lineTo(r.centerX(), base - h * 0.22f)
        path.lineTo(r.centerX() + h * 0.2f, base)
        path.close()
        canvas.drawPath(path, paint)
        canvas.drawCircle(r.centerX(), base - h * 0.3f, h * 0.1f, paint)
    }

    /** Tip icon: the current tip shown on a small ball. */
    private fun iconTip(canvas: Canvas, r: RectF) {
        val rr = r.height() * 0.3f
        Shading.ball(canvas, paint, r.centerX(), r.centerY(), rr, Color.rgb(246, 244, 236), shadow = false)
        paint.color = Color.rgb(200, 30, 40)
        canvas.drawCircle(r.centerX() + (tipX * rr).toFloat(), r.centerY() - (tipY * rr).toFloat(), rr * 0.26f, paint)
    }

    private fun iconUndo(canvas: Canvas, r: RectF) {
        iconStroke(r)
        val rad = r.height() * 0.24f
        path.reset()
        val start = -PI * 0.15
        val sweep = PI * 1.55
        for (k in 0..20) {
            val a = start - sweep * k / 20
            val x = r.centerX() + (cos(a) * rad).toFloat()
            val y = r.centerY() + (sin(a) * rad).toFloat()
            if (k == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        canvas.drawPath(path, paint)
        iconDone()
        // Arrow head at the start of the arc, pointing clockwise back.
        val a = start
        val hx = r.centerX() + (cos(a) * rad).toFloat()
        val hy = r.centerY() + (sin(a) * rad).toFloat()
        val s = r.height() * 0.13f
        paint.color = iconColor
        path.reset()
        path.moveTo(hx - s, hy - s * 0.2f)
        path.lineTo(hx + s, hy - s * 0.2f)
        path.lineTo(hx, hy + s)
        path.close()
        canvas.drawPath(path, paint)
    }

    private fun iconCamera(canvas: Canvas, r: RectF) {
        val h = r.height()
        val cx = r.centerX() - h * 0.06f
        val cy = r.centerY() + h * 0.07f
        paint.color = iconColor
        canvas.drawRoundRect(RectF(cx - h * 0.26f, cy - h * 0.13f, cx + h * 0.16f, cy + h * 0.16f), h * 0.04f, h * 0.04f, paint)
        canvas.drawCircle(cx - h * 0.14f, cy - h * 0.22f, h * 0.1f, paint)
        canvas.drawCircle(cx + h * 0.06f, cy - h * 0.22f, h * 0.1f, paint)
        path.reset()
        path.moveTo(cx + h * 0.16f, cy)
        path.lineTo(cx + h * 0.34f, cy - h * 0.12f)
        path.lineTo(cx + h * 0.34f, cy + h * 0.14f)
        path.close()
        canvas.drawPath(path, paint)
    }

    private fun beadCount() = if (game.targetScore > 30) 50 else BEADS

    private fun beadPitch(rect: RectF) = min(rect.height() * 0.42f, rect.width() / (beadCount() * 1.3f))

    /** Abacus score counter: scored beads are slid to the right, past the gap. Groups of ten alternate red and white. */
    private fun drawAbacus(canvas: Canvas, rect: RectF, score: Int) {
        val n = beadCount()
        val pitch = beadPitch(rect)
        val wireY = rect.centerY()
        // Wire and its two end posts.
        paint.style = Paint.Style.FILL
        paint.shader = LinearGradient(0f, wireY - 2f, 0f, wireY + 2f, intArrayOf(Color.rgb(230, 230, 226), Color.rgb(120, 118, 112)), null, Shader.TileMode.CLAMP)
        canvas.drawRect(rect.left, wireY - rect.height() * 0.03f - 1f, rect.right, wireY + rect.height() * 0.03f + 1f, paint)
        paint.shader = null
        paint.color = Color.rgb(96, 88, 78)
        canvas.drawRect(rect.left - 2f, rect.top + rect.height() * 0.1f, rect.left + 1f, rect.bottom - rect.height() * 0.1f, paint)
        canvas.drawRect(rect.right - 1f, rect.top + rect.height() * 0.1f, rect.right + 2f, rect.bottom - rect.height() * 0.1f, paint)
        val s = score.coerceIn(0, n)
        val bw = pitch * 0.92f
        for (i in 0 until n) {
            val x = if (i < n - s) rect.left + 2f + i * pitch else rect.right - 2f - (n - i) * pitch
            val color = if ((i / 10) % 2 == 0) beadRed() else Color.rgb(244, 240, 232)
            Shading.bead(canvas, paint, RectF(x, rect.top, x + bw, rect.bottom), color)
        }
    }

    private fun ballColor(id: BallId) = when (id) {
        BallId.WHITE -> Color.rgb(248, 248, 244)
        BallId.YELLOW -> Color.rgb(250, 196, 30)
        BallId.RED, BallId.RED2 -> Color.rgb(226, 30, 26)
    }

    /** Faint dotted line from the cue ball along the aim, up to the first ball or cushion. */
    private fun drawAimDots(canvas: Canvas) {
        val pred = Aim.predict(game.table, game.balls, game.cueBallId, aimDir)
        val c = game.cueBall.pos
        val len = (pred.contact - c).length()
        val step = game.table.ballRadius * 0.9
        paint.style = Paint.Style.FILL
        paint.color = Color.argb(150, 255, 255, 255)
        val dot = max(1f, ballR * 0.09f)
        var t = game.table.ballRadius * 2
        while (t < len) {
            val p = c + aimDir * t
            canvas.drawCircle(sx(p.x), sy(p.y), dot, paint)
            t += step
        }
    }

    /** Thin lines along where each ball went in the last shot, up to frame [upTo]. */
    private fun drawPaths(canvas: Canvas, upTo: Int) {
        val replay = game.replay ?: return
        if (replay.frames.isEmpty()) return
        val last = min(upTo, replay.frames.size - 1)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = max(1f, ballR * 0.16f)
        replay.ballIds.forEachIndexed { b, id ->
            path.reset()
            var moved = false
            for (f in 0..last) {
                val p = replay.frames[f][b]
                if (f == 0) path.moveTo(sx(p.x), sy(p.y)) else path.lineTo(sx(p.x), sy(p.y))
                if (!moved && f > 0 && p != replay.frames[0][b]) moved = true
            }
            if (moved) {
                val col = ballColor(id)
                paint.color = Color.argb(200, Color.red(col), Color.green(col), Color.blue(col))
                canvas.drawPath(path, paint)
            }
        }
        paint.style = Paint.Style.FILL
    }

    /** Distance from the ball center back to the cue tip, in meters. */
    private fun cueGapMeters(): Double {
        val r = game.table.ballRadius
        val rest = r * 1.6
        if (strokeLeft >= 0) {
            val t = (strokeLeft / STROKE_SECONDS).coerceIn(0.0, 1.0)
            return r + (rest - r + strokeFromMeters) * t
        }
        return rest + pullMeters
    }

    private fun drawCue(canvas: Canvas, shadowOnly: Boolean) {
        val c = game.cueBall.pos
        val back = -aimDir
        val tip = c + back * cueGapMeters()
        val joint = tip + back * 0.78
        val butt = tip + back * 1.45
        val w0 = ballR * 0.36f
        val w1 = ballR * 0.62f
        if (shadowOnly) {
            paint.style = Paint.Style.STROKE
            paint.strokeCap = Paint.Cap.ROUND
            paint.strokeWidth = w1
            paint.color = Color.argb(55, 0, 0, 0)
            val o = ballR * 0.7f
            canvas.drawLine(sx(tip.x) + o, sy(tip.y) + o * 1.3f, sx(butt.x) + o, sy(butt.y) + o * 1.3f, paint)
            paint.strokeCap = Paint.Cap.BUTT
            paint.style = Paint.Style.FILL
            return
        }
        // Tapered maple shaft in a few steps, then the dark joint and butt.
        val steps = 6
        for (k in 0 until steps) {
            val a = tip + back * (0.78 * k / steps)
            val b = tip + back * (0.78 * (k + 1) / steps)
            val wd = w0 + (w1 * 0.85f - w0) * k / steps
            Shading.cylinder(canvas, paint, sx(a.x), sy(a.y), sx(b.x), sy(b.y), wd, Color.rgb(222, 200, 150))
        }
        val wrap = tip + back * 1.05
        Shading.cylinder(canvas, paint, sx(joint.x), sy(joint.y), sx(wrap.x), sy(wrap.y), w1 * 0.9f, Color.rgb(46, 40, 36))
        Shading.cylinder(canvas, paint, sx(wrap.x), sy(wrap.y), sx(butt.x), sy(butt.y), w1, Color.rgb(30, 26, 24))
        val ring = tip + back * 0.8
        Shading.cylinder(canvas, paint, sx(joint.x), sy(joint.y), sx(ring.x), sy(ring.y), w1 * 0.9f, Color.rgb(200, 190, 170))
        val ferrule = tip + back * 0.02
        Shading.cylinder(canvas, paint, sx(tip.x), sy(tip.y), sx(ferrule.x), sy(ferrule.y), w0, Color.rgb(242, 240, 232))
        val leather = tip + back * 0.006
        Shading.cylinder(canvas, paint, sx(tip.x), sy(tip.y), sx(leather.x), sy(leather.y), w0, Color.rgb(60, 110, 180))
    }

    private fun drawReplay(canvas: Canvas) {
        val replay = game.replay ?: return
        val idx = min(replayFrame.toInt(), replay.frames.size - 1)
        drawPaths(canvas, idx)
        val pos = replay.frames[idx]
        replay.ballIds.forEachIndexed { b, _ -> Shading.ballShadow(canvas, paint, sx(pos[b].x), sy(pos[b].y), ballR) }
        replay.ballIds.forEachIndexed { b, id -> Shading.ball(canvas, paint, sx(pos[b].x), sy(pos[b].y), ballR, ballColor(id), shadow = false) }
        drawHint(canvas, "리플레이 · 탭하면 닫기")
    }

    private fun drawHint(canvas: Canvas, s: String) {
        text.textAlign = Paint.Align.CENTER
        text.textSize = clothRect.height() * 0.045f
        text.color = Color.argb(200, 255, 255, 255)
        canvas.drawText(s, clothRect.centerX(), clothRect.bottom - clothRect.height() * 0.05f, text)
        text.color = Color.WHITE
    }

    private fun drawMessage(canvas: Canvas) {
        if (System.currentTimeMillis() >= messageUntil || message.isEmpty()) return
        text.textAlign = Paint.Align.CENTER
        text.textSize = clothRect.height() * 0.07f
        val w = text.measureText(message) + text.textSize * 1.4f
        val cy = clothRect.top + clothRect.height() * 0.22f
        paint.color = Color.argb(110, 0, 0, 0)
        canvas.drawRoundRect(RectF(clothRect.centerX() - w / 2, cy - text.textSize * 1.1f, clothRect.centerX() + w / 2, cy + text.textSize * 0.45f), text.textSize * 0.4f, text.textSize * 0.4f, paint)
        canvas.drawText(message, clothRect.centerX(), cy, text)
    }

    // ---------------------------------------------------------------- tip view

    private val tipCenterX get() = clothRect.centerX()
    private val tipCenterY get() = clothRect.centerY()
    private val tipRadius get() = clothRect.height() * 0.3f

    /** "Up" in the tip view is the aim direction: you look at the ball from behind the cue. */
    private fun tipAxes(): Pair<Vec2, Vec2> {
        val up = aimDir
        val right = Vec2(-up.y, up.x)
        return right to up
    }

    /**
     * The big cue ball seen from behind the cue, over a perspective floor of dots,
     * with two lines across the whole table through the tip.
     */
    private fun drawTipView(canvas: Canvas) {
        paint.style = Paint.Style.FILL
        paint.color = Color.argb(60, 0, 0, 0)
        canvas.drawRect(clothRect, paint)
        drawMeshFloor(canvas)

        val r = tipRadius
        Shading.ball(canvas, paint, tipCenterX, tipCenterY, r, ballColor(game.cueBallId))
        val (right, up) = tipAxes()
        val px = tipCenterX + ((right.x * tipX + up.x * tipY) * r).toFloat()
        val py = tipCenterY + ((right.y * tipX + up.y * tipY) * r).toFloat()

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = max(1.5f, ballR * 0.16f)
        paint.color = Color.argb(220, 220, 255, 220)
        crossLine(canvas, px, py, up.x.toFloat(), up.y.toFloat())
        crossLine(canvas, px, py, right.x.toFloat(), right.y.toFloat())
        paint.style = Paint.Style.FILL
        paint.color = Color.argb(230, 40, 90, 170)
        canvas.drawCircle(px, py, max(3f, ballR * 0.35f), paint)
    }

    /** A line through (x, y) along (dx, dy), cut to the playing surface. */
    private fun crossLine(canvas: Canvas, x: Float, y: Float, dx: Float, dy: Float) {
        var t0 = -1e6f
        var t1 = 1e6f
        fun clip(p: Float, d: Float, lo: Float, hi: Float) {
            if (kotlin.math.abs(d) < 1e-6f) return
            val a = (lo - p) / d
            val b = (hi - p) / d
            t0 = max(t0, min(a, b))
            t1 = min(t1, max(a, b))
        }
        clip(x, dx, railRect.left, railRect.right)
        clip(y, dy, clothRect.top, clothRect.bottom)
        if (t0 < t1) canvas.drawLine(x + dx * t0, y + dy * t0, x + dx * t1, y + dy * t1, paint)
    }

    /** Rows of dots shrinking toward a horizon at the middle of the table: a floor seen in perspective. */
    private fun drawMeshFloor(canvas: Canvas) {
        val horizon = clothRect.top + clothRect.height() * 0.42f
        val depthScale = clothRect.height() * 0.6f
        paint.style = Paint.Style.FILL
        var z = 1f
        while (z < 14f) {
            val y = horizon + depthScale / z
            if (y < clothRect.bottom) {
                val spacing = clothRect.height() * 0.09f / z
                val dot = max(0.7f, spacing * 0.2f)
                paint.color = Color.argb((80 / sqrt(z)).toInt(), 0, 20, 10)
                var x = clothRect.centerX() - (((clothRect.centerX() - clothRect.left) / spacing).toInt()) * spacing
                while (x < clothRect.right) {
                    canvas.drawCircle(x, y, dot, paint)
                    x += spacing
                }
            }
            z *= 1.06f
        }
    }

    private fun drawGameOver(canvas: Canvas) {
        paint.color = Color.argb(150, 0, 0, 0)
        canvas.drawRect(clothRect, paint)
        text.textAlign = Paint.Align.CENTER
        text.textSize = clothRect.height() * 0.09f
        val name = if (game.winner == 0) "흰 공" else "노란 공"
        canvas.drawText("$name 승리!", clothRect.centerX(), clothRect.centerY(), text)
        text.textSize = clothRect.height() * 0.045f
        canvas.drawText("탭하면 메뉴", clothRect.centerX(), clothRect.centerY() + text.textSize * 2.2f, text)
    }

    private fun menuLabels() = listOf(
        (if (menuMode == GameMode.THREE_CUSHION) "● " else "") + "3구 (3쿠션)",
        (if (menuMode == GameMode.FOUR_BALL) "● " else "") + "4구",
        "목표 ${menuMode.targets[targetIndex[menuMode.ordinal]]}점",
        "이동 경로 " + if (showPaths) "표시" else "숨김",
        "게임 시작",
    )

    private fun drawMenu(canvas: Canvas) {
        paint.style = Paint.Style.FILL
        paint.color = Color.argb(190, 8, 8, 10)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        val itemH = menuItems[0].height()
        text.textAlign = Paint.Align.CENTER
        text.textSize = itemH * 0.3f
        text.color = Color.argb(190, 255, 255, 255)
        canvas.drawText("공 뒤를 누르고 → 뒤로 당겨 힘 → 두 번째 손가락으로 당점 → 떼면 샷", width / 2f, menuItems[0].top - itemH * 0.45f, text)
        text.color = Color.WHITE
        menuLabels().forEachIndexed { i, label ->
            val rect = menuItems[i]
            val base = when {
                i == menuItems.size - 1 -> Color.rgb(64, 120, 72)
                else -> Color.rgb(176, 166, 148)
            }
            val c = if (pressedButton === rect) Shading.darker(base, 0.2f) else base
            paint.shader = LinearGradient(0f, rect.top, 0f, rect.bottom, intArrayOf(Shading.lighter(c, 0.18f), Shading.darker(c, 0.12f)), null, Shader.TileMode.CLAMP)
            canvas.drawRoundRect(rect, itemH * 0.2f, itemH * 0.2f, paint)
            paint.shader = null
            text.textSize = itemH * 0.4f
            text.color = if (i == menuItems.size - 1) Color.WHITE else Color.rgb(48, 42, 36)
            canvas.drawText(label, rect.centerX(), rect.centerY() + text.textSize * 0.36f, text)
        }
        text.color = Color.WHITE
    }

    // ---------------------------------------------------------------- game flow

    private fun onShotFinished() {
        val r = game.lastResult ?: return
        message = when {
            game.phase == Phase.GAME_OVER -> ""
            r.scored -> "득점"
            r.foul -> "파울 -1"
            else -> ""
        }
        messageUntil = if (message.isEmpty()) 0L else System.currentTimeMillis() + 1200
        tipX = 0.0
        tipY = 0.0
    }

    /** Start the cue's forward stroke; the ball is struck at the end of it. */
    private fun release() {
        if (game.phase != Phase.AIMING || power < MIN_POWER) {
            pullMeters = 0.0
            return
        }
        strokePower = power
        strokeFromMeters = pullMeters
        strokeLeft = STROKE_SECONDS
        pullMeters = 0.0
        lastFrameNanos = 0L
    }

    private fun strike() {
        strokeLeft = -1.0
        game.shoot(aimDir, strokePower, tipX, tipY)
        lastFrameNanos = 0L
    }

    private fun startGame() {
        game = Game(menuMode, menuMode.targets[targetIndex[menuMode.ordinal]])
        menuOpen = false
        placing = false
        tipX = 0.0
        tipY = 0.0
        replayFrame = -1.0
        aimAtNearestRed()
    }

    private fun onButton(rect: RectF) {
        when {
            rect === menuButton -> { menuOpen = true; menuMode = game.mode }
            rect === placeButton -> if (game.phase == Phase.AIMING) placing = !placing
            rect === turnButton -> game.switchTurn()
            rect === undoButton -> if (game.undo()) replayFrame = -1.0
            rect === replayButton -> if (game.replay != null && game.phase != Phase.ROLLING) replayFrame = 0.0
            rect === menuItems[0] -> menuMode = GameMode.THREE_CUSHION
            rect === menuItems[1] -> menuMode = GameMode.FOUR_BALL
            rect === menuItems[2] -> targetIndex[menuMode.ordinal] = (targetIndex[menuMode.ordinal] + 1) % menuMode.targets.size
            rect === menuItems[3] -> showPaths = !showPaths
            rect === menuItems[4] -> startGame()
        }
    }

    // ---------------------------------------------------------------- touch

    private fun buttonAt(x: Float, y: Float): RectF? {
        val candidates = if (menuOpen) menuItems.toList() else listOf(menuButton, placeButton, turnButton, undoButton, replayButton)
        return candidates.firstOrNull { it.contains(x, y) }
    }

    private fun clampTip() {
        val d = sqrt(tipX * tipX + tipY * tipY)
        if (d > TIP_LIMIT) {
            tipX *= TIP_LIMIT / d
            tipY *= TIP_LIMIT / d
        }
    }

    /** Distance from the cue ball to a screen point, in table meters. */
    private fun fingerDistance(x: Float, y: Float): Double {
        val c = game.cueBall.pos
        return hypot((x - sx(c.x)).toDouble(), (y - sy(c.y)).toDouble()) / scale
    }

    /** The cue lies from the ball through the finger, so the ball goes the other way. */
    private fun aimFromFinger(x: Float, y: Float) {
        val c = game.cueBall.pos
        val dx = sx(c.x) - x
        val dy = sy(c.y) - y
        if (hypot(dx, dy) > ballR * 1.2f) aimAngle = atan2(dy.toDouble(), dx.toDouble())
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val i = e.actionIndex
        val x = e.getX(i)
        val y = e.getY(i)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> onFirstDown(e.getPointerId(i), x, y)
            MotionEvent.ACTION_POINTER_DOWN -> {
                // A second finger while drawing the cue opens the tip view; the draw is held.
                if (mode == Mode.CUE) {
                    mode = Mode.TIP
                    tipPointerId = e.getPointerId(i)
                    tipLastX = x
                    tipLastY = y
                }
            }
            MotionEvent.ACTION_MOVE -> onMove(e)
            MotionEvent.ACTION_POINTER_UP -> {
                val id = e.getPointerId(i)
                if (mode == Mode.TIP && id == tipPointerId) {
                    if (cuePointerUp) {
                        mode = Mode.NONE
                        release()
                    } else {
                        mode = Mode.CUE
                        // Re-base so the held draw does not jump if the first finger drifted.
                        val ci = e.findPointerIndex(cuePointerId)
                        if (ci >= 0) touchStartDist = fingerDistance(e.getX(ci), e.getY(ci)) - pullMeters
                    }
                } else if (mode == Mode.TIP && id == cuePointerId) {
                    // First finger lifted before the second: shoot once the second one lifts too.
                    cuePointerUp = true
                }
            }
            MotionEvent.ACTION_UP -> onLastUp(x, y)
            MotionEvent.ACTION_CANCEL -> {
                mode = Mode.NONE
                pullMeters = 0.0
                pressedButton = null
            }
        }
        invalidate()
        return true
    }

    private fun onFirstDown(id: Int, x: Float, y: Float) {
        buttonAt(x, y)?.let {
            pressedButton = it
            mode = Mode.BUTTON
            return
        }
        if (menuOpen || strokeLeft >= 0) return
        if (replayFrame >= 0) {
            replayFrame = -1.0
            return
        }
        if (game.phase == Phase.GAME_OVER) {
            menuOpen = true
            menuMode = game.mode
            return
        }
        for (p in 0..1) {
            if (abacus[p].contains(x, y)) {
                mode = Mode.ABACUS
                abacusPlayer = p
                abacusStartX = x
                abacusStartScore = game.scores[p]
                return
            }
        }
        if (game.phase != Phase.AIMING) return
        if (tipButton.contains(x, y)) {
            // Holding the tip button shows the big ball; slide over it to set the tip.
            mode = Mode.TIP_BUTTON
            pressedButton = tipButton
            tipPointerId = id
            tipLastX = x
            tipLastY = y
            return
        }
        if (placing) {
            val hit = game.balls.minByOrNull { hypot(sx(it.pos.x) - x, sy(it.pos.y) - y) }
            if (hit != null && hypot(sx(hit.pos.x) - x, sy(hit.pos.y) - y) < ballR * 3f) {
                mode = Mode.PLACE
                placeBall = hit.id
                placeDX = hit.pos.x - (x - clothRect.left) / scale
                placeDY = hit.pos.y - (y - clothRect.top) / scale
            }
            return
        }
        if (!clothRect.contains(x, y) && !railRect.contains(x, y)) return
        mode = Mode.CUE
        cuePointerId = id
        cuePointerUp = false
        aimLocked = false
        aimFromFinger(x, y)
        touchStartDist = fingerDistance(x, y)
        pullMeters = 0.0
    }

    private fun onMove(e: MotionEvent) {
        when (mode) {
            Mode.CUE -> {
                val i = e.findPointerIndex(cuePointerId)
                if (i < 0) return
                val x = e.getX(i)
                val y = e.getY(i)
                if (!aimLocked) aimFromFinger(x, y)
                val d = fingerDistance(x, y)
                // Before the draw starts, moving closer just moves the starting point.
                if (!aimLocked && d < touchStartDist) touchStartDist = d
                pullMeters = (d - touchStartDist).coerceIn(0.0, MAX_PULL_METERS)
                if (pullMeters > LOCK_METERS) aimLocked = true else if (pullMeters < LOCK_METERS / 2) aimLocked = false
            }
            Mode.TIP, Mode.TIP_BUTTON -> {
                val i = e.findPointerIndex(tipPointerId)
                if (i < 0) return
                val tx = e.getX(i)
                val ty = e.getY(i)
                val (right, up) = tipAxes()
                val dx = (tx - tipLastX).toDouble()
                val dy = (ty - tipLastY).toDouble()
                tipX += (dx * right.x + dy * right.y) / tipRadius * TIP_GAIN
                tipY += (dx * up.x + dy * up.y) / tipRadius * TIP_GAIN
                clampTip()
                tipLastX = tx
                tipLastY = ty
            }
            Mode.ABACUS -> {
                val rect = abacus[abacusPlayer]
                val steps = ((e.getX(0) - abacusStartX) / beadPitch(rect)).roundToInt()
                game.scores[abacusPlayer] = (abacusStartScore + steps).coerceIn(0, beadCount())
            }
            Mode.PLACE -> {
                val b = game.balls.firstOrNull { it.id == placeBall } ?: return
                val r = game.table.ballRadius
                val nx = ((e.getX(0) - clothRect.left) / scale + placeDX).coerceIn(r, game.table.width - r)
                val ny = ((e.getY(0) - clothRect.top) / scale + placeDY).coerceIn(r, game.table.height - r)
                val target = Vec2(nx, ny)
                if (game.balls.none { it !== b && (it.pos - target).length() < 2 * r }) b.pos = target
            }
            else -> Unit
        }
    }

    private fun onLastUp(x: Float, y: Float) {
        when (mode) {
            Mode.BUTTON -> {
                val b = pressedButton
                if (b != null && b.contains(x, y)) onButton(b)
            }
            Mode.CUE, Mode.TIP -> release()
            else -> Unit
        }
        mode = Mode.NONE
        pullMeters = 0.0
        pressedButton = null
        placeBall = null
    }

    companion object {
        /** How far the cue can be drawn back, in table meters; full draw is full power. */
        const val MAX_PULL_METERS = 0.5
        const val MIN_POWER = 0.03
        /** Once the cue is drawn this far, sideways finger movement no longer turns it. */
        const val LOCK_METERS = 0.015
        const val STROKE_SECONDS = 0.09
        const val TIP_GAIN = 0.8
        const val TIP_LIMIT = 0.7
        const val RAIL_FRAC = 0.065f
        const val BEADS = 30
    }
}
