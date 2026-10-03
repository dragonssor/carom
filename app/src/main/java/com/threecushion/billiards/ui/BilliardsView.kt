package com.threecushion.billiards.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import com.threecushion.billiards.engine.Aim
import com.threecushion.billiards.engine.BallId
import com.threecushion.billiards.engine.Game
import com.threecushion.billiards.engine.GameMode
import com.threecushion.billiards.engine.Phase
import com.threecushion.billiards.engine.Replay
import com.threecushion.billiards.engine.Vec2
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The whole game screen. The table never rotates: you turn the phone around it,
 * like walking around a real table.
 *
 * Controls:
 * 1. Touch the spot you want to hit toward with the first finger.
 * 2. Pull that finger back: the cue pulls back with it, and the distance is the power.
 * 3. While still holding, touch with a second finger and move it to set the tip
 *    position (english, follow, draw). Lift the second finger to keep it.
 * 4. Lift the first finger to shoot. Slide back to the start before lifting to cancel.
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
    private var showGuide = true

    // Screens and animation.
    private var menuOpen = true
    private var menuMode = GameMode.THREE_CUSHION
    private var targetIndex = intArrayOf(0, 0)
    private var tipPinned = false
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
    private val topBar = RectF()
    private val bottomBar = RectF()
    private val p1Beads = RectF()
    private val p2Beads = RectF()
    private val p1Icon = RectF()
    private val p2Icon = RectF()
    private val menuButton = RectF()
    private val tipButton = RectF()
    private val undoButton = RectF()
    private val replayButton = RectF()
    private val menuItems = Array(5) { RectF() }

    // Touch state.
    private enum class Mode { NONE, AIM, TIP, BUTTON, PINNED_TIP }
    private var mode = Mode.NONE
    private var aimPointerId = -1
    private var aimPointerUp = false
    private var anchorX = 0f
    private var anchorY = 0f
    private var targetX = 0f
    private var targetY = 0f
    private var tipPointerId = -1
    private var tipLastX = 0f
    private var tipLastY = 0f
    private var pressedButton: RectF? = null

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val path = Path()

    init {
        aimAtNearestRed()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val barH = dp(34f)
        val margin = dp(6f)
        val t = game.table
        val rail = 0.085
        val availW = w - 2 * margin
        val availH = h - 2 * barH - 2 * margin
        scale = min(availW / (t.width + 2 * rail), availH / (t.height + 2 * rail)).toFloat()
        val railW = ((t.width + 2 * rail) * scale).toFloat()
        val railH = ((t.height + 2 * rail) * scale).toFloat()
        val left = (w - railW) / 2
        val top = margin + barH + (availH - railH) / 2
        railRect.set(left, top, left + railW, top + railH)
        val rp = (rail * scale).toFloat()
        clothRect.set(left + rp, top + rp, left + railW - rp, top + railH - rp)

        topBar.set(left, top - barH, left + railW, top)
        bottomBar.set(left, top + railH, left + railW, top + railH + barH)
        val icon = barH * 0.8f
        val pad = (barH - icon) / 2
        p1Icon.set(topBar.left + pad, topBar.top + pad, topBar.left + pad + icon, topBar.bottom - pad)
        p2Icon.set(bottomBar.left + pad, bottomBar.top + pad, bottomBar.left + pad + icon, bottomBar.bottom - pad)
        menuButton.set(topBar.right - pad - icon * 1.6f, topBar.top + pad, topBar.right - pad, topBar.bottom - pad)
        replayButton.set(bottomBar.right - pad - icon * 2.4f, bottomBar.top + pad, bottomBar.right - pad, bottomBar.bottom - pad)
        undoButton.set(replayButton.left - pad - icon * 2.4f, replayButton.top, replayButton.left - pad, replayButton.bottom)
        tipButton.set(undoButton.left - pad - icon, undoButton.top, undoButton.left - pad, undoButton.bottom)
        p1Beads.set(p1Icon.right + pad * 3, topBar.top + pad, menuButton.left - pad * 3, topBar.bottom - pad)
        p2Beads.set(p2Icon.right + pad * 3, bottomBar.top + pad, tipButton.left - pad * 3, bottomBar.bottom - pad)

        val itemW = min(w * 0.5f, dp(300f))
        val itemH = dp(44f)
        val gap = dp(10f)
        val total = menuItems.size * itemH + (menuItems.size - 1) * gap
        var y = (h - total) / 2 + dp(14f)
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

        canvas.drawColor(Color.rgb(14, 14, 18))
        drawTable(canvas)
        drawBars(canvas)
        val replaying = replayFrame >= 0
        val aiming = game.phase == Phase.AIMING && !menuOpen
        if (replaying) drawReplay(canvas) else {
            if (aiming && showGuide) drawGuide(canvas)
            if (aiming && mode == Mode.AIM || mode == Mode.TIP) drawTarget(canvas)
            if (aiming) drawCue(canvas, shadowOnly = true)
            game.balls.forEach { b -> Shading.ballShadow(canvas, paint, sx(b.pos.x), sy(b.pos.y), ballR) }
            game.balls.forEach { b -> Shading.ball(canvas, paint, sx(b.pos.x), sy(b.pos.y), ballR, ballColor(b.id), shadow = false) }
            if (aiming) drawCue(canvas, shadowOnly = false)
            if (aiming && (mode == Mode.AIM || mode == Mode.TIP) && power > 0) drawPowerGauge(canvas)
        }
        drawMessage(canvas)
        if (mode == Mode.TIP || tipPinned) drawTipOverlay(canvas)
        if (game.phase == Phase.GAME_OVER && !menuOpen) drawGameOver(canvas)
        if (menuOpen) drawMenu(canvas)

        if (game.phase == Phase.ROLLING || replaying || strokeLeft >= 0 || System.currentTimeMillis() < messageUntil) {
            postInvalidateOnAnimation()
        } else {
            lastFrameNanos = 0L
        }
    }

    private fun clothColor() =
        if (game.mode == GameMode.THREE_CUSHION) Color.rgb(36, 112, 196) else Color.rgb(40, 140, 72)

    private fun drawTable(canvas: Canvas) {
        // Drop shadow of the whole table.
        paint.style = Paint.Style.FILL
        paint.color = Color.argb(90, 0, 0, 0)
        canvas.drawRoundRect(RectF(railRect.left + dp(4f), railRect.top + dp(6f), railRect.right + dp(4f), railRect.bottom + dp(6f)), dp(12f), dp(12f), paint)
        val cushion = dp(6f)
        Shading.rail(canvas, paint, railRect, RectF(clothRect.left - cushion, clothRect.top - cushion, clothRect.right + cushion, clothRect.bottom + cushion), dp(12f))
        Shading.cloth(canvas, paint, clothRect, clothColor())
        Shading.cushions(canvas, paint, clothRect, cushion, clothColor())
        val d = dp(2.6f)
        val midTop = (railRect.top + clothRect.top - cushion) / 2
        val midBottom = (railRect.bottom + clothRect.bottom + cushion) / 2
        val midLeft = (railRect.left + clothRect.left - cushion) / 2
        val midRight = (railRect.right + clothRect.right + cushion) / 2
        for (i in 1..7) {
            val x = clothRect.left + clothRect.width() * i / 8
            Shading.diamond(canvas, paint, x, midTop, d)
            Shading.diamond(canvas, paint, x, midBottom, d)
        }
        for (i in 1..3) {
            val y = clothRect.top + clothRect.height() * i / 4
            Shading.diamond(canvas, paint, midLeft, y, d)
            Shading.diamond(canvas, paint, midRight, y, d)
        }
    }

    private fun drawBars(canvas: Canvas) {
        paint.style = Paint.Style.FILL
        for (bar in listOf(topBar, bottomBar)) {
            paint.shader = android.graphics.LinearGradient(0f, bar.top, 0f, bar.bottom, intArrayOf(Color.rgb(214, 202, 182), Color.rgb(170, 156, 134)), null, android.graphics.Shader.TileMode.CLAMP)
            canvas.drawRoundRect(bar, dp(6f), dp(6f), paint)
        }
        paint.shader = null

        drawPlayerIcon(canvas, p1Icon, BallId.WHITE, game.currentPlayer == 0)
        drawPlayerIcon(canvas, p2Icon, BallId.YELLOW, game.currentPlayer == 1)
        drawBeads(canvas, p1Beads, game.scores[0])
        drawBeads(canvas, p2Beads, game.scores[1])

        drawButton(canvas, menuButton, "메뉴")
        drawButton(canvas, undoButton, "되돌리기", enabled = game.canUndo)
        drawButton(canvas, replayButton, "리플레이", enabled = game.replay != null && game.phase != Phase.ROLLING)
        val r = tipButton.height() / 2
        Shading.ball(canvas, paint, tipButton.centerX(), tipButton.centerY(), r, Color.rgb(248, 248, 242), shadow = false)
        paint.color = Color.rgb(200, 30, 40)
        canvas.drawCircle(tipButton.centerX() + (tipX * r).toFloat(), tipButton.centerY() - (tipY * r).toFloat(), r * 0.22f, paint)
    }

    private fun drawPlayerIcon(canvas: Canvas, rect: RectF, id: BallId, active: Boolean) {
        val r = rect.height() / 2
        if (active) {
            paint.color = Color.argb(170, 255, 214, 70)
            canvas.drawCircle(rect.centerX(), rect.centerY(), r * 1.12f, paint)
        }
        Shading.ball(canvas, paint, rect.centerX(), rect.centerY(), r * 0.8f, ballColor(id), shadow = false)
    }

    /** Abacus-style score beads, like the counters above a real billiards table. */
    private fun drawBeads(canvas: Canvas, rect: RectF, score: Int) {
        paint.color = Color.rgb(80, 70, 58)
        val wireY = rect.centerY()
        canvas.drawRect(rect.left, wireY - dp(1f), rect.right, wireY + dp(1f), paint)
        val n = game.targetScore
        val beadW = min(dp(9f), rect.width() / (n + 6))
        val gap = beadW * 0.12f
        for (i in 0 until n) {
            val scored = i >= n - score
            val x = if (scored) rect.right - (n - i) * (beadW + gap) else rect.left + i * (beadW + gap)
            val color = when {
                scored -> Color.rgb(205, 35, 45)
                (i / 5) % 2 == 1 -> Color.rgb(222, 212, 196)
                else -> Color.rgb(245, 240, 230)
            }
            Shading.bead(canvas, paint, RectF(x, rect.top, x + beadW, rect.bottom), color)
        }
    }

    private fun drawButton(canvas: Canvas, rect: RectF, label: String, enabled: Boolean = true) {
        val base = if (pressedButton === rect) Color.rgb(110, 92, 72) else Color.rgb(140, 122, 100)
        paint.shader = android.graphics.LinearGradient(0f, rect.top, 0f, rect.bottom, intArrayOf(Shading.lighter(base, 0.15f), Shading.darker(base, 0.15f)), null, android.graphics.Shader.TileMode.CLAMP)
        canvas.drawRoundRect(rect, dp(5f), dp(5f), paint)
        paint.shader = null
        text.textAlign = Paint.Align.CENTER
        text.textSize = rect.height() * 0.38f
        text.color = if (enabled) Color.WHITE else Color.argb(110, 255, 255, 255)
        canvas.drawText(label, rect.centerX(), rect.centerY() + text.textSize * 0.36f, text)
        text.color = Color.WHITE
    }

    private fun ballColor(id: BallId) = when (id) {
        BallId.WHITE -> Color.rgb(246, 244, 236)
        BallId.YELLOW -> Color.rgb(250, 190, 24)
        BallId.RED, BallId.RED2 -> Color.rgb(206, 22, 32)
    }

    private fun drawGuide(canvas: Canvas) {
        val pred = Aim.predict(game.table, game.balls, game.cueBallId, aimDir)
        val c = game.cueBall.pos
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(1f)
        paint.color = Color.argb(140, 255, 255, 255)
        canvas.drawLine(sx(c.x), sy(c.y), sx(pred.contact.x), sy(pred.contact.y), paint)
        if (pred.hitBall != null) {
            canvas.drawCircle(sx(pred.contact.x), sy(pred.contact.y), ballR, paint)
        } else if (pred.reflected != null) {
            val end = pred.contact + pred.reflected * 0.25
            paint.color = Color.argb(70, 255, 255, 255)
            canvas.drawLine(sx(pred.contact.x), sy(pred.contact.y), sx(end.x), sy(end.y), paint)
        }
        paint.style = Paint.Style.FILL
    }

    /** Ring where the first finger touched: the direction the ball will go. */
    private fun drawTarget(canvas: Canvas) {
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(1.5f)
        paint.color = Color.argb(200, 255, 240, 120)
        canvas.drawCircle(targetX, targetY, dp(14f), paint)
        canvas.drawLine(targetX - dp(20f), targetY, targetX + dp(20f), targetY, paint)
        canvas.drawLine(targetX, targetY - dp(20f), targetX, targetY + dp(20f), paint)
        paint.style = Paint.Style.FILL
    }

    /** Distance from the ball center back to the cue tip, in meters. */
    private fun cueGapMeters(): Double {
        val base = game.table.ballRadius * 1.5
        if (strokeLeft >= 0) {
            val t = (strokeLeft / STROKE_SECONDS).coerceIn(0.0, 1.0)
            return game.table.ballRadius + (base - game.table.ballRadius + strokeFromMeters) * t
        }
        return base + pullMeters
    }

    private fun drawCue(canvas: Canvas, shadowOnly: Boolean) {
        val c = game.cueBall.pos
        val back = -aimDir
        val tip = c + back * cueGapMeters()
        val butt = tip + back * 1.45
        val wrapStart = tip + back * 0.85
        val wrapEnd = tip + back * 1.15
        if (shadowOnly) {
            paint.style = Paint.Style.STROKE
            paint.strokeCap = Paint.Cap.ROUND
            paint.strokeWidth = dp(5.5f)
            paint.color = Color.argb(60, 0, 0, 0)
            val o = dp(7f)
            canvas.drawLine(sx(tip.x) + o, sy(tip.y) + o * 1.4f, sx(butt.x) + o, sy(butt.y) + o * 1.4f, paint)
            paint.strokeCap = Paint.Cap.BUTT
            paint.style = Paint.Style.FILL
            return
        }
        paint.strokeCap = Paint.Cap.ROUND
        Shading.cylinder(canvas, paint, sx(tip.x), sy(tip.y), sx(wrapStart.x), sy(wrapStart.y), dp(4.5f), Color.rgb(214, 180, 128))
        Shading.cylinder(canvas, paint, sx(wrapStart.x), sy(wrapStart.y), sx(wrapEnd.x), sy(wrapEnd.y), dp(5.6f), Color.rgb(40, 40, 46))
        Shading.cylinder(canvas, paint, sx(wrapEnd.x), sy(wrapEnd.y), sx(butt.x), sy(butt.y), dp(6.4f), Color.rgb(92, 40, 22))
        val ferrule = tip + back * 0.022
        Shading.cylinder(canvas, paint, sx(tip.x), sy(tip.y), sx(ferrule.x), sy(ferrule.y), dp(4.5f), Color.rgb(238, 236, 228))
        val tipEnd = tip + back * 0.005
        Shading.cylinder(canvas, paint, sx(tip.x), sy(tip.y), sx(tipEnd.x), sy(tipEnd.y), dp(4.5f), Color.rgb(46, 100, 176))
        paint.strokeCap = Paint.Cap.BUTT
    }

    private fun drawPowerGauge(canvas: Canvas) {
        val w = dp(160f)
        val h = dp(10f)
        val x = clothRect.centerX() - w / 2
        val y = clothRect.top + dp(14f)
        paint.color = Color.argb(120, 0, 0, 0)
        canvas.drawRoundRect(RectF(x - dp(3f), y - dp(3f), x + w + dp(3f), y + h + dp(3f)), dp(6f), dp(6f), paint)
        val p = power.toFloat()
        paint.color = Shading.mix(Color.rgb(90, 210, 90), Color.rgb(235, 60, 40), p)
        canvas.drawRoundRect(RectF(x, y, x + w * p, y + h), dp(5f), dp(5f), paint)
        text.textAlign = Paint.Align.CENTER
        text.textSize = dp(12f)
        canvas.drawText("힘 ${(p * 100).toInt()}%", clothRect.centerX(), y + h + dp(16f), text)
    }

    private fun drawReplay(canvas: Canvas) {
        val replay = game.replay ?: return
        val idx = min(replayFrame.toInt(), replay.frames.size - 1)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(1.5f)
        replay.ballIds.forEachIndexed { b, id ->
            path.reset()
            for (f in 0..idx) {
                val p = replay.frames[f][b]
                if (f == 0) path.moveTo(sx(p.x), sy(p.y)) else path.lineTo(sx(p.x), sy(p.y))
            }
            paint.color = ballColor(id)
            canvas.drawPath(path, paint)
        }
        paint.style = Paint.Style.FILL
        replay.ballIds.forEachIndexed { b, id ->
            val p = replay.frames[idx][b]
            Shading.ball(canvas, paint, sx(p.x), sy(p.y), ballR, ballColor(id))
        }
        text.textAlign = Paint.Align.CENTER
        text.textSize = dp(13f)
        canvas.drawText("리플레이 · 탭하면 닫기", clothRect.centerX(), clothRect.bottom - dp(10f), text)
    }

    private fun drawMessage(canvas: Canvas) {
        if (System.currentTimeMillis() >= messageUntil || message.isEmpty()) return
        text.textAlign = Paint.Align.CENTER
        text.textSize = dp(26f)
        paint.color = Color.argb(120, 0, 0, 0)
        val w = text.measureText(message) + dp(32f)
        val cy = clothRect.top + clothRect.height() * 0.3f
        canvas.drawRoundRect(RectF(clothRect.centerX() - w / 2, cy - dp(30f), clothRect.centerX() + w / 2, cy + dp(14f)), dp(10f), dp(10f), paint)
        canvas.drawText(message, clothRect.centerX(), cy, text)
    }

    private val tipCenterX get() = clothRect.centerX()
    private val tipCenterY get() = clothRect.centerY()
    private val tipRadius get() = clothRect.height() * 0.36f

    /** Big cue ball with crosshairs through the tip position. */
    private fun drawTipOverlay(canvas: Canvas) {
        paint.color = Color.argb(150, 0, 0, 0)
        canvas.drawRect(clothRect, paint)
        val r = tipRadius
        Shading.ball(canvas, paint, tipCenterX, tipCenterY, r, ballColor(game.cueBallId))
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(1f)
        paint.color = Color.argb(90, 0, 0, 0)
        canvas.drawCircle(tipCenterX, tipCenterY, r * TIP_LIMIT.toFloat(), paint)
        val px = tipCenterX + (tipX * r).toFloat()
        val py = tipCenterY - (tipY * r).toFloat()
        paint.color = Color.argb(200, 120, 255, 160)
        paint.strokeWidth = dp(1.5f)
        canvas.drawLine(clothRect.left, py, clothRect.right, py, paint)
        canvas.drawLine(px, clothRect.top, px, clothRect.bottom, paint)
        paint.style = Paint.Style.FILL
        Shading.ball(canvas, paint, px, py, dp(9f), Color.rgb(46, 100, 176), shadow = false)
        text.textAlign = Paint.Align.LEFT
        text.textSize = dp(13f)
        val side = when {
            tipX > 0.05 -> "오른쪽 ${(tipX * 100).toInt()}"
            tipX < -0.05 -> "왼쪽 ${(-tipX * 100).toInt()}"
            else -> "가운데"
        }
        val vert = when {
            tipY > 0.05 -> "밀어치기 ${(tipY * 100).toInt()}"
            tipY < -0.05 -> "끌어치기 ${(-tipY * 100).toInt()}"
            else -> "중단"
        }
        canvas.drawText("당점: $side · $vert", clothRect.left + dp(10f), clothRect.top + dp(20f), text)
        if (tipPinned) canvas.drawText("공 위를 끌어 당점 이동 · 바깥을 탭하면 닫기", clothRect.left + dp(10f), clothRect.bottom - dp(10f), text)
    }

    private fun drawGameOver(canvas: Canvas) {
        paint.color = Color.argb(160, 0, 0, 0)
        canvas.drawRect(clothRect, paint)
        text.textAlign = Paint.Align.CENTER
        text.textSize = dp(30f)
        val name = if (game.winner == 0) "흰 공" else "노란 공"
        canvas.drawText("$name 승리!", clothRect.centerX(), clothRect.centerY(), text)
        text.textSize = dp(14f)
        canvas.drawText("탭하면 메뉴", clothRect.centerX(), clothRect.centerY() + dp(30f), text)
    }

    private fun menuLabels() = listOf(
        (if (menuMode == GameMode.THREE_CUSHION) "● " else "") + "3구 (3쿠션)",
        (if (menuMode == GameMode.FOUR_BALL) "● " else "") + "4구",
        "목표 ${menuMode.targets[targetIndex[menuMode.ordinal]]}점 (탭하여 변경)",
        "안내선 " + if (showGuide) "켜짐" else "꺼짐",
        "게임 시작",
    )

    private fun drawMenu(canvas: Canvas) {
        paint.color = Color.argb(200, 10, 10, 14)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        text.textAlign = Paint.Align.CENTER
        text.textSize = dp(22f)
        canvas.drawText("당구", width / 2f, menuItems[0].top - dp(30f), text)
        menuLabels().forEachIndexed { i, label ->
            val rect = menuItems[i]
            paint.color = when {
                pressedButton === rect -> Color.rgb(90, 90, 100)
                i == menuItems.size - 1 -> Color.rgb(40, 120, 70)
                else -> Color.rgb(55, 55, 64)
            }
            canvas.drawRoundRect(rect, dp(8f), dp(8f), paint)
            text.textSize = dp(16f)
            canvas.drawText(label, rect.centerX(), rect.centerY() + dp(6f), text)
        }
        text.textSize = dp(12f)
        text.color = Color.argb(180, 255, 255, 255)
        canvas.drawText("치고 싶은 곳을 누르고 → 뒤로 당겨 힘 조절 → 두 번째 손가락으로 당점 → 손을 떼면 샷", width / 2f, menuItems[0].top - dp(9f), text)
        text.color = Color.WHITE
    }

    // ---------------------------------------------------------------- game flow

    private fun onShotFinished() {
        val r = game.lastResult ?: return
        message = when {
            game.phase == Phase.GAME_OVER -> ""
            r.scored -> "득점!"
            r.foul -> "파울 (상대 공) -1"
            r.objectsHit.isEmpty() -> "헛스리"
            game.mode == GameMode.THREE_CUSHION && r.objectsHit.size == 2 -> "쿠션 ${r.cushionsBeforeSecond}개 · 실패"
            else -> "실패"
        }
        messageUntil = System.currentTimeMillis() + 1400
        tipX = 0.0
        tipY = 0.0
        aimAtNearestRed()
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
        tipX = 0.0
        tipY = 0.0
        replayFrame = -1.0
        aimAtNearestRed()
    }

    private fun onButton(rect: RectF) {
        when {
            rect === menuButton -> menuOpen = true
            rect === undoButton -> if (game.undo()) { replayFrame = -1.0; aimAtNearestRed() }
            rect === replayButton -> if (game.replay != null && game.phase != Phase.ROLLING) replayFrame = 0.0
            rect === tipButton -> tipPinned = !tipPinned
            rect === menuItems[0] -> menuMode = GameMode.THREE_CUSHION
            rect === menuItems[1] -> menuMode = GameMode.FOUR_BALL
            rect === menuItems[2] -> targetIndex[menuMode.ordinal] = (targetIndex[menuMode.ordinal] + 1) % menuMode.targets.size
            rect === menuItems[3] -> showGuide = !showGuide
            rect === menuItems[4] -> startGame()
        }
    }

    // ---------------------------------------------------------------- touch

    private fun buttonAt(x: Float, y: Float): RectF? {
        val candidates = if (menuOpen) menuItems.toList() else listOf(menuButton, undoButton, replayButton, tipButton)
        return candidates.firstOrNull { it.contains(x, y) }
    }

    private fun setTipFromPoint(x: Float, y: Float) {
        tipX = ((x - tipCenterX) / tipRadius).toDouble()
        tipY = (-(y - tipCenterY) / tipRadius).toDouble()
        clampTip()
    }

    private fun clampTip() {
        val d = sqrt(tipX * tipX + tipY * tipY)
        if (d > TIP_LIMIT) {
            tipX *= TIP_LIMIT / d
            tipY *= TIP_LIMIT / d
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val i = e.actionIndex
        val x = e.getX(i)
        val y = e.getY(i)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> onFirstDown(e.getPointerId(i), x, y)
            MotionEvent.ACTION_POINTER_DOWN -> {
                // A second finger while aiming adjusts the tip; the pull is held where it is.
                if (mode == Mode.AIM) {
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
                    mode = if (aimPointerUp) Mode.NONE else Mode.AIM
                    // Re-anchor so the held pull does not jump if the first finger drifted.
                    val ai = e.findPointerIndex(aimPointerId)
                    if (ai >= 0) {
                        val back = -aimDir
                        anchorX = e.getX(ai) - (back.x * pullMeters * scale).toFloat()
                        anchorY = e.getY(ai) - (back.y * pullMeters * scale).toFloat()
                    }
                    if (aimPointerUp) release()
                } else if (id == aimPointerId && mode == Mode.TIP) {
                    // First finger lifted before the second: shoot once the second one lifts too.
                    aimPointerUp = true
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
            return
        }
        if (game.phase != Phase.AIMING) return
        if (tipPinned) {
            if (hypot(x - tipCenterX, y - tipCenterY) <= tipRadius) {
                setTipFromPoint(x, y)
                mode = Mode.PINNED_TIP
            } else {
                tipPinned = false
            }
            return
        }
        // Aim at the touched spot (unless it is right on the cue ball).
        val c = game.cueBall.pos
        val dx = x - sx(c.x)
        val dy = y - sy(c.y)
        if (hypot(dx, dy) > ballR * 1.5f) aimAngle = atan2(dy.toDouble(), dx.toDouble())
        mode = Mode.AIM
        aimPointerId = id
        aimPointerUp = false
        anchorX = x
        anchorY = y
        targetX = x
        targetY = y
        pullMeters = 0.0
    }

    private fun onMove(e: MotionEvent) {
        when (mode) {
            Mode.AIM -> {
                val i = e.findPointerIndex(aimPointerId)
                if (i < 0) return
                // Only the movement straight back along the cue counts, one to one, like drawing a real cue.
                val back = -aimDir
                val pulledPx = (e.getX(i) - anchorX) * back.x + (e.getY(i) - anchorY) * back.y
                pullMeters = (pulledPx / scale).coerceIn(0.0, MAX_PULL_METERS)
            }
            Mode.TIP -> {
                val i = e.findPointerIndex(tipPointerId)
                if (i < 0) return
                val tx = e.getX(i)
                val ty = e.getY(i)
                tipX += (tx - tipLastX) / tipRadius * TIP_GAIN
                tipY -= (ty - tipLastY) / tipRadius * TIP_GAIN
                clampTip()
                tipLastX = tx
                tipLastY = ty
            }
            Mode.PINNED_TIP -> {
                val x = e.getX(0)
                val y = e.getY(0)
                if (hypot(x - tipCenterX, y - tipCenterY) <= tipRadius * 1.1f) setTipFromPoint(x, y)
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
            Mode.AIM -> release()
            Mode.TIP -> release()
            else -> Unit
        }
        mode = Mode.NONE
        pullMeters = 0.0
        pressedButton = null
    }

    companion object {
        /** How far the cue can be drawn back, in table meters; full pull is full power. */
        const val MAX_PULL_METERS = 0.5
        const val MIN_POWER = 0.03
        const val STROKE_SECONDS = 0.09
        const val TIP_GAIN = 0.8
        const val TIP_LIMIT = 0.7
    }
}
