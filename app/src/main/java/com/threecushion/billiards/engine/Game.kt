package com.threecushion.billiards.engine

import kotlin.math.max

enum class GameMode(val label: String, val ballIds: List<BallId>, val targets: List<Int>) {
    THREE_CUSHION("3구", listOf(BallId.WHITE, BallId.YELLOW, BallId.RED), listOf(10, 15, 20, 30)),
    FOUR_BALL("4구", listOf(BallId.WHITE, BallId.YELLOW, BallId.RED, BallId.RED2), listOf(10, 20, 30, 50)),
}

enum class Phase { AIMING, ROLLING, GAME_OVER }

/** Ball positions of one shot, sampled at [Replay.FRAME_DT], for replay and path drawing. */
class Replay(val ballIds: List<BallId>) {
    val frames = ArrayList<List<Vec2>>()

    companion object {
        const val FRAME_DT = 1.0 / 60.0
    }
}

/** Two players on one device. Player 0 shoots the white ball, player 1 the yellow. */
class Game(val mode: GameMode = GameMode.THREE_CUSHION, val targetScore: Int = mode.targets.first()) {
    val table = Table()
    val balls = mode.ballIds.map { Ball(it, Vec2.ZERO) }
    val world = PhysicsWorld(table, balls)

    var currentPlayer = 0
        private set
    val scores = IntArray(2)
    var innings = 1
        private set
    var phase = Phase.AIMING
        private set
    var lastResult: ShotResult? = null
        private set
    var winner: Int? = null
        private set

    /** Positions of the last finished (or current) shot. */
    var replay: Replay? = null
        private set

    private var referee: Referee? = null
    private var frameClock = 0.0
    private val history = ArrayList<Snapshot>()

    private class Snapshot(
        val positions: List<Vec2>,
        val scores: IntArray,
        val player: Int,
        val innings: Int,
        val lastResult: ShotResult?,
    )

    val cueBallId get() = if (currentPlayer == 0) BallId.WHITE else BallId.YELLOW
    val opponentBallId get() = if (currentPlayer == 0) BallId.YELLOW else BallId.WHITE
    val cueBall get() = ball(cueBallId)
    val canUndo get() = history.isNotEmpty() && phase != Phase.ROLLING

    init {
        reset()
    }

    fun ball(id: BallId) = balls.first { it.id == id }

    /** Hand the turn to the other player without shooting (the turn button on the score bar). */
    fun switchTurn() {
        if (phase == Phase.AIMING) currentPlayer = 1 - currentPlayer
    }

    fun reset() {
        val w = table.width
        val h = table.height
        for (b in balls) {
            b.stop()
            b.pos = when (b.id) {
                BallId.RED -> Vec2(w * 0.75, h / 2)
                BallId.RED2 -> Vec2(w * 0.25, h / 2)
                BallId.WHITE ->
                    if (mode == GameMode.FOUR_BALL) Vec2(w * 0.25 - 0.18, h / 2 + 0.1525) else Vec2(w * 0.25, h / 2 + 0.1525)
                BallId.YELLOW ->
                    if (mode == GameMode.FOUR_BALL) Vec2(w * 0.25 - 0.18, h / 2 - 0.1525) else Vec2(w * 0.25, h / 2)
            }
        }
        currentPlayer = 0
        scores.fill(0)
        innings = 1
        phase = Phase.AIMING
        lastResult = null
        winner = null
        replay = null
        history.clear()
    }

    /**
     * Strike the cue ball.
     * @param dir stroke direction in table coordinates
     * @param power 0..1
     * @param tipX horizontal tip offset (fraction of radius, right positive)
     * @param tipY vertical tip offset (fraction of radius, up positive)
     */
    fun shoot(dir: Vec2, power: Double, tipX: Double = 0.0, tipY: Double = 0.0) {
        if (phase != Phase.AIMING) return
        history += Snapshot(balls.map { it.pos }, scores.copyOf(), currentPlayer, innings, lastResult)
        val p = power.coerceIn(0.0, 1.0)
        val speed = MIN_SPEED + (MAX_SPEED - MIN_SPEED) * p * p
        val (v, w) = PhysicsWorld.strike(table.ballRadius, dir, speed, tipX, tipY)
        cueBall.vel = v
        cueBall.spin = w
        referee = when (mode) {
            GameMode.THREE_CUSHION -> ThreeCushionReferee(cueBallId)
            GameMode.FOUR_BALL -> FourBallReferee(cueBallId, opponentBallId)
        }
        replay = Replay(mode.ballIds).also { it.frames += balls.map { b -> b.pos } }
        frameClock = 0.0
        lastResult = null
        phase = Phase.ROLLING
    }

    /** Advance the simulation by [dt] seconds of real time. */
    fun update(dt: Double) {
        if (phase != Phase.ROLLING) return
        val ref = referee ?: return
        frameClock += dt
        while (frameClock >= Replay.FRAME_DT && phase == Phase.ROLLING) {
            frameClock -= Replay.FRAME_DT
            world.advance(Replay.FRAME_DT).forEach(ref::onEvent)
            replay?.frames?.add(balls.map { it.pos })
            if (!world.isMoving) finishShot(ref)
        }
    }

    /** Run the current shot to the end instantly (used by tests). */
    fun settle() {
        var guard = 0
        while (phase == Phase.ROLLING && guard++ < 60 * 120) update(Replay.FRAME_DT)
    }

    /** Put the balls, score and turn back to how they were before the last shot. */
    fun undo(): Boolean {
        if (!canUndo) return false
        val s = history.removeAt(history.size - 1)
        balls.forEachIndexed { i, b -> b.stop(); b.pos = s.positions[i] }
        s.scores.copyInto(scores)
        currentPlayer = s.player
        innings = s.innings
        lastResult = s.lastResult
        winner = null
        replay = null
        phase = Phase.AIMING
        return true
    }

    private fun finishShot(ref: Referee) {
        balls.forEach { it.stop() }
        val result = ref.result()
        lastResult = result
        referee = null
        when {
            result.scored -> {
                scores[currentPlayer]++
                if (scores[currentPlayer] >= targetScore) {
                    winner = currentPlayer
                    phase = Phase.GAME_OVER
                    return
                }
            }
            else -> {
                if (result.foul) scores[currentPlayer] = max(0, scores[currentPlayer] - 1)
                if (currentPlayer == 1) innings++
                currentPlayer = 1 - currentPlayer
            }
        }
        phase = Phase.AIMING
    }

    companion object {
        const val MIN_SPEED = 0.3
        const val MAX_SPEED = 6.5
    }
}
