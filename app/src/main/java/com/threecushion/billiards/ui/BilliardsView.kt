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
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The whole game screen.
 *
 * Controls:
 * - Rub a finger anywhere on the table: the cue turns around the cue ball (fine aim).
 * - Touch the cue stick and pull it back: the further you pull, the harder the shot.
 *   Let go (or push forward) to shoot.
 * - Hold one finger down and move a second finger: a big cue ball appears and the second
 *   finger moves the tip position (english, follow, draw).
 * - The small cue ball at the bottom opens the same tip view for one-finger use.
 */
class BilliardsView(context: Context) : View(context) {
    private var game = Game()
    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density

    // Shot setup.
    private var aimAngle = 0.0
    private var power = 0.0
    private var peakPower = 0.0
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
    private enum class Mode { NONE, ROTATE, STROKE, TIP, TIP_WAIT, BUTTON }
    private var mode = Mode.NONE
    private var lastTouchAngle = 0.0
    private var strokeStartX = 0f
    private var strokeStartY = 0f
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
        val rail = 0.075
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
        if (game.phase == Phase.ROLLING) {
            game.update(dt)
            if (game.phase != Phase.ROLLING) onShotFinished()
        }
        if (replayFrame >= 0) {
            replayFrame += dt / Replay.FRAME_DT
            val frames = game.replay?.frames?.size ?: 0
            if (replayFrame >= frames + 60) replayFrame = -1.0
        }

        canvas.drawColor(Color.rgb(16, 16, 20))
        drawTable(canvas)
        drawBars(canvas)
        val replaying = replayFrame >= 0
        if (replaying) drawReplay(canvas) else {
            if (game.phase == Phase.AIMING && showGuide && !menuOpen) drawGuide(canvas)
            game.balls.forEach { drawBall(canvas, it.id, it.pos) }
            if (game.phase == Phase.AIMING && !menuOpen) drawCue(canvas)
        }
        drawMessage(canvas)
        if (mode == Mode.TIP || mode == Mode.TIP_WAIT || tipPinned) drawTipOverlay(canvas)
        if (game.phase == Phase.GAME_OVER && !menuOpen) drawGameOver(canvas)
        if (menuOpen) drawMenu(canvas)

        if (game.phase == Phase.ROLLING || replaying || System.currentTimeMillis() < messageUntil) {
            postInvalidateOnAnimation()
        } else {
            lastFrameNanos = 0L
        }
    }

    private fun clothColor() =
        if (game.mode == GameMode.THREE_CUSHION) Color.rgb(46, 128, 214) else Color.rgb(52, 158, 86)

    private fun drawTable(canvas: Canvas) {
        paint.style = Paint.Style.FILL
        paint.color = Color.rgb(84, 40, 22)
        canvas.drawRoundRect(railRect, dp(10f), dp(10f), paint)
        // Cushion strip, a little darker than the cloth.
        paint.color = darker(clothColor(), 0.75f)
        val c = dp(5f)
        canvas.drawRect(clothRect.left - c, clothRect.top - c, clothRect.right + c, clothRect.bottom + c, paint)
        paint.color = clothColor()
        canvas.drawRect(clothRect, paint)
        paint.color = Color.rgb(240, 230, 205)
        val d = dp(2.2f)
        val midTop = (railRect.top + clothRect.top) / 2 - c / 2
        val midBottom = (railRect.bottom + clothRect.bottom) / 2 + c / 2
        val midLeft = (railRect.left + clothRect.left) / 2 - c / 2
        val midRight = (railRect.right + clothRect.right) / 2 + c / 2
        for (i in 1..7) {
            val x = clothRect.left + clothRect.width() * i / 8
            canvas.drawCircle(x, midTop, d, paint)
            canvas.drawCircle(x, midBottom, d, paint)
        }
        for (i in 1..3) {
            val y = clothRect.top + clothRect.height() * i / 4
            canvas.drawCircle(midLeft, y, d, paint)
            canvas.drawCircle(midRight, y, d, paint)
        }
    }

    private fun drawBars(canvas: Canvas) {
        paint.style = Paint.Style.FILL
        paint.color = Color.rgb(196, 182, 160)
        canvas.drawRoundRect(topBar, dp(6f), dp(6f), paint)
        canvas.drawRoundRect(bottomBar, dp(6f), dp(6f), paint)

        drawPlayerIcon(canvas, p1Icon, BallId.WHITE, game.currentPlayer == 0)
        drawPlayerIcon(canvas, p2Icon, BallId.YELLOW, game.currentPlayer == 1)
        drawBeads(canvas, p1Beads, game.scores[0])
        drawBeads(canvas, p2Beads, game.scores[1])

        drawButton(canvas, menuButton, "메뉴")
        drawButton(canvas, undoButton, "되돌리기", enabled = game.canUndo)
        drawButton(canvas, replayButton, "리플레이", enabled = game.replay != null && game.phase != Phase.ROLLING)
        // Mini cue ball showing the current tip position.
        val r = tipButton.height() / 2
        paint.color = Color.rgb(250, 250, 245)
        canvas.drawCircle(tipButton.centerX(), tipButton.centerY(), r, paint)
        paint.color = Color.rgb(200, 30, 40)
        canvas.drawCircle(tipButton.centerX() + (tipX * r).toFloat(), tipButton.centerY() - (tipY * r).toFloat(), r * 0.22f, paint)
    }

    private fun drawPlayerIcon(canvas: Canvas, rect: RectF, id: BallId, active: Boolean) {
        val r = rect.height() / 2
        if (active) {
            paint.color = Color.argb(160, 255, 220, 80)
            canvas.drawCircle(rect.centerX(), rect.centerY(), r * 1.15f, paint)
        }
        paint.color = ballColor(id)
        canvas.drawCircle(rect.centerX(), rect.centerY(), r * 0.8f, paint)
    }

    /** Abacus-style score beads, like the counters above a real billiards table. */
    private fun drawBeads(canvas: Canvas, rect: RectF, score: Int) {
        paint.color = Color.rgb(70, 60, 50)
        val wireY = rect.centerY()
        canvas.drawRect(rect.left, wireY - dp(1f), rect.right, wireY + dp(1f), paint)
        val n = game.targetScore
        val beadW = min(dp(9f), rect.width() / (n + 6))
        val gap = beadW * 0.12f
        // Scored beads slide to the right, the rest wait on the left.
        for (i in 0 until n) {
            val scored = i >= n - score
            val x = if (scored) rect.right - (n - i) * (beadW + gap) else rect.left + i * (beadW + gap)
            paint.color = if (scored) Color.rgb(205, 35, 45) else Color.rgb(245, 240, 230)
            val group = (i / 5) % 2 == 1
            if (!scored && group) paint.color = Color.rgb(225, 215, 200)
            canvas.drawRoundRect(RectF(x, rect.top, x + beadW, rect.bottom), beadW / 2, beadW / 2, paint)
        }
    }

    private fun drawButton(canvas: Canvas, rect: RectF, label: String, enabled: Boolean = true) {
        paint.color = if (pressedButton === rect) Color.rgb(120, 100, 80) else Color.rgb(150, 132, 110)
        canvas.drawRoundRect(rect, dp(5f), dp(5f), paint)
        text.textAlign = Paint.Align.CENTER
        text.textSize = rect.height() * 0.38f
        text.color = if (enabled) Color.WHITE else Color.argb(110, 255, 255, 255)
        canvas.drawText(label, rect.centerX(), rect.centerY() + text.textSize * 0.36f, text)
        text.color = Color.WHITE
    }

    private fun ballColor(id: BallId) = when (id) {
        BallId.WHITE -> Color.rgb(248, 248, 242)
        BallId.YELLOW -> Color.rgb(252, 196, 30)
        BallId.RED, BallId.RED2 -> Color.rgb(214, 28, 38)
    }

    private fun drawBall(canvas: Canvas, id: BallId, pos: Vec2) {
        val r = (game.table.ballRadius * scale).toFloat()
        val x = sx(pos.x)
        val y = sy(pos.y)
        paint.style = Paint.Style.FILL
        paint.color = Color.argb(70, 0, 0, 0)
        canvas.drawCircle(x + r * 0.2f, y + r * 0.25f, r, paint)
        paint.color = ballColor(id)
        canvas.drawCircle(x, y, r, paint)
        paint.color = Color.argb(150, 255, 255, 255)
        canvas.drawCircle(x - r * 0.35f, y - r * 0.35f, r * 0.28f, paint)
    }

    private fun drawGuide(canvas: Canvas) {
        val pred = Aim.predict(game.table, game.balls, game.cueBallId, aimDir)
        val c = game.cueBall.pos
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(1f)
        paint.color = Color.argb(150, 255, 255, 255)
        canvas.drawLine(sx(c.x), sy(c.y), sx(pred.contact.x), sy(pred.contact.y), paint)
        val r = (game.table.ballRadius * scale).toFloat()
        if (pred.hitBall != null) {
            canvas.drawCircle(sx(pred.contact.x), sy(pred.contact.y), r, paint)
        } else if (pred.reflected != null) {
            val end = pred.contact + pred.reflected * 0.25
            paint.color = Color.argb(80, 255, 255, 255)
            canvas.drawLine(sx(pred.contact.x), sy(pred.contact.y), sx(end.x), sy(end.y), paint)
        }
        paint.style = Paint.Style.FILL
    }

    /** Cue stick: tip [gap] behind the ball, pulled further back by the current power. */
    private fun cueSegment(): Pair<Vec2, Vec2> {
        val c = game.cueBall.pos
        val back = -aimDir
        val gap = game.table.ballRadius * 1.6 + power * MAX_PULL_METERS
        val tip = c + back * gap
        return tip to tip + back * 1.45
    }

    private fun drawCue(canvas: Canvas) {
        val (tip, butt) = cueSegment()
        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.ROUND
        val back = -aimDir
        // Shaft (light), butt (dark), ferrule and tip.
        val wrap = tip + back * 0.9
        paint.strokeWidth = dp(4.5f)
        paint.color = Color.rgb(222, 196, 150)
        canvas.drawLine(sx(tip.x), sy(tip.y), sx(wrap.x), sy(wrap.y), paint)
        paint.strokeWidth = dp(6f)
        paint.color = Color.rgb(60, 36, 24)
        canvas.drawLine(sx(wrap.x), sy(wrap.y), sx(butt.x), sy(butt.y), paint)
        paint.strokeWidth = dp(4.5f)
        paint.color = Color.rgb(250, 250, 250)
        val ferrule = tip + back * 0.025
        canvas.drawLine(sx(tip.x), sy(tip.y), sx(ferrule.x), sy(ferrule.y), paint)
        paint.color = Color.rgb(50, 110, 190)
        val tipEnd = tip + back * 0.006
        canvas.drawLine(sx(tip.x), sy(tip.y), sx(tipEnd.x), sy(tipEnd.y), paint)
        paint.strokeCap = Paint.Cap.BUTT
        paint.style = Paint.Style.FILL
        if (mode == Mode.STROKE && power > 0) {
            text.textAlign = Paint.Align.CENTER
            text.textSize = dp(14f)
            canvas.drawText("힘 ${(power * 100).toInt()}%", clothRect.centerX(), clothRect.top + dp(22f), text)
        }
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
            paint.color = if (id == BallId.WHITE) Color.argb(220, 255, 255, 255) else ballColor(id)
            canvas.drawPath(path, paint)
        }
        paint.style = Paint.Style.FILL
        replay.ballIds.forEachIndexed { b, id -> drawBall(canvas, id, replay.frames[idx][b]) }
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
        paint.color = ballColor(game.cueBallId)
        canvas.drawCircle(tipCenterX, tipCenterY, r, paint)
        paint.color = Color.argb(90, 255, 255, 255)
        canvas.drawCircle(tipCenterX - r * 0.35f, tipCenterY - r * 0.35f, r * 0.25f, paint)
        // Miscue limit.
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
        paint.color = Color.rgb(50, 110, 190)
        canvas.drawCircle(px, py, dp(9f), paint)
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
        canvas.drawText("당구", width / 2f, menuItems[0].top - dp(18f), text)
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

    private fun shoot(p: Double) {
        if (game.phase != Phase.AIMING || p < 0.02) return
        game.shoot(aimDir, p, tipX, tipY)
        power = 0.0
        peakPower = 0.0
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

    private fun angleAroundCue(x: Float, y: Float): Double {
        val c = game.cueBall.pos
        return atan2((y - sy(c.y)).toDouble(), (x - sx(c.x)).toDouble())
    }

    private fun nearCue(x: Float, y: Float): Boolean {
        val (tip, butt) = cueSegment()
        val ax = sx(tip.x)
        val ay = sy(tip.y)
        val bx = sx(butt.x)
        val by = sy(butt.y)
        val dx = bx - ax
        val dy = by - ay
        val len2 = dx * dx + dy * dy
        val t = (((x - ax) * dx + (y - ay) * dy) / len2).coerceIn(0f, 1f)
        return hypot(x - (ax + t * dx), y - (ay + t * dy)) < dp(30f)
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
        val x = e.getX(e.actionIndex)
        val y = e.getY(e.actionIndex)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> onFirstDown(x, y)
            MotionEvent.ACTION_POINTER_DOWN -> {
                // A second finger switches to tip control, whatever the first finger was doing.
                if (!menuOpen && game.phase == Phase.AIMING && mode != Mode.BUTTON && replayFrame < 0) {
                    if (mode == Mode.STROKE) power = 0.0
                    mode = Mode.TIP
                    tipPointerId = e.getPointerId(e.actionIndex)
                    tipLastX = x
                    tipLastY = y
                }
            }
            MotionEvent.ACTION_MOVE -> onMove(e)
            MotionEvent.ACTION_POINTER_UP -> {
                if (mode == Mode.TIP && e.getPointerId(e.actionIndex) == tipPointerId) mode = Mode.TIP_WAIT
            }
            MotionEvent.ACTION_UP -> onLastUp(x, y)
            MotionEvent.ACTION_CANCEL -> {
                mode = Mode.NONE
                power = 0.0
                pressedButton = null
            }
        }
        invalidate()
        return true
    }

    private fun onFirstDown(x: Float, y: Float) {
        buttonAt(x, y)?.let {
            pressedButton = it
            mode = Mode.BUTTON
            return
        }
        if (menuOpen) return
        if (replayFrame >= 0) {
            replayFrame = -1.0
            mode = Mode.NONE
            return
        }
        if (game.phase == Phase.GAME_OVER) {
            menuOpen = true
            mode = Mode.NONE
            return
        }
        if (game.phase != Phase.AIMING) return
        if (tipPinned) {
            val inside = hypot(x - tipCenterX, y - tipCenterY) <= tipRadius
            if (inside) {
                setTipFromPoint(x, y)
                mode = Mode.TIP_WAIT
            } else {
                tipPinned = false
                mode = Mode.NONE
            }
            return
        }
        if (nearCue(x, y)) {
            mode = Mode.STROKE
            strokeStartX = x
            strokeStartY = y
            power = 0.0
            peakPower = 0.0
        } else {
            mode = Mode.ROTATE
            lastTouchAngle = angleAroundCue(x, y)
        }
    }

    private fun onMove(e: MotionEvent) {
        val x = e.getX(0)
        val y = e.getY(0)
        when (mode) {
            Mode.ROTATE -> {
                val a = angleAroundCue(x, y)
                var d = a - lastTouchAngle
                if (d > PI) d -= 2 * PI
                if (d < -PI) d += 2 * PI
                aimAngle += d * ROTATE_GAIN
                lastTouchAngle = a
            }
            Mode.STROKE -> {
                // Pull distance along the cue, away from the ball.
                val back = -aimDir
                val pulled = (x - strokeStartX) * back.x + (y - strokeStartY) * back.y
                val maxPull = width * 0.3
                power = (pulled / maxPull).coerceIn(0.0, 1.0)
                peakPower = max(peakPower, power)
                // Pushing forward again after a pull is a stroke.
                if (peakPower > 0.05 && power < peakPower * 0.4) {
                    val p = peakPower
                    mode = Mode.NONE
                    shoot(p)
                }
            }
            Mode.TIP -> {
                val i = e.findPointerIndex(tipPointerId)
                if (i >= 0) {
                    val tx = e.getX(i)
                    val ty = e.getY(i)
                    tipX += (tx - tipLastX) / tipRadius * TIP_GAIN
                    tipY -= (ty - tipLastY) / tipRadius * TIP_GAIN
                    clampTip()
                    tipLastX = tx
                    tipLastY = ty
                }
            }
            Mode.TIP_WAIT -> if (tipPinned && hypot(x - tipCenterX, y - tipCenterY) <= tipRadius * 1.1f) setTipFromPoint(x, y)
            else -> Unit
        }
    }

    private fun onLastUp(x: Float, y: Float) {
        when (mode) {
            Mode.BUTTON -> {
                val b = pressedButton
                if (b != null && b.contains(x, y)) onButton(b)
            }
            Mode.STROKE -> shoot(power)
            else -> Unit
        }
        mode = Mode.NONE
        power = 0.0
        pressedButton = null
    }

    companion object {
        /** How far back the drawn cue moves at full power, in meters. */
        const val MAX_PULL_METERS = 0.3
        const val ROTATE_GAIN = 0.35
        const val TIP_GAIN = 0.8
        const val TIP_LIMIT = 0.7

        private fun darker(color: Int, f: Float) =
            Color.rgb((Color.red(color) * f).toInt(), (Color.green(color) * f).toInt(), (Color.blue(color) * f).toInt())
    }
}
