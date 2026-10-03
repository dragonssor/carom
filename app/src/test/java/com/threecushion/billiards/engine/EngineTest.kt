package com.threecushion.billiards.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

class RefereeTest {
    private fun cushion() = PhysicsEvent.BallCushion(BallId.WHITE, Cushion.TOP)
    private fun hit(b: BallId) = PhysicsEvent.BallBall(BallId.WHITE, b)

    private fun three(vararg events: PhysicsEvent) = ThreeCushionReferee(BallId.WHITE).apply { events.forEach(::onEvent) }.result()
    private fun four(vararg events: PhysicsEvent) = FourBallReferee(BallId.WHITE, BallId.YELLOW).apply { events.forEach(::onEvent) }.result()

    @Test fun threeCushionsBeforeSecondBallScores() {
        val r = three(hit(BallId.RED), cushion(), cushion(), cushion(), hit(BallId.YELLOW))
        assertTrue(r.scored)
        assertEquals(3, r.cushionsBeforeSecond)
    }

    @Test fun cushionsFirstThenBothBallsScores() {
        assertTrue(three(cushion(), cushion(), cushion(), hit(BallId.YELLOW), hit(BallId.RED)).scored)
    }

    @Test fun twoCushionsIsNotEnough() {
        assertFalse(three(hit(BallId.RED), cushion(), cushion(), hit(BallId.YELLOW), cushion()).scored)
    }

    @Test fun cushionsAfterSecondBallDoNotCount() {
        val r = three(hit(BallId.RED), hit(BallId.YELLOW), cushion(), cushion(), cushion())
        assertFalse(r.scored)
        assertEquals(0, r.cushionsBeforeSecond)
    }

    @Test fun hittingSameBallTwiceIsNotTwoBalls() {
        assertFalse(three(hit(BallId.RED), cushion(), cushion(), cushion(), hit(BallId.RED)).scored)
    }

    @Test fun objectBallEventsAreIgnored() {
        val r = three(
            hit(BallId.RED),
            PhysicsEvent.BallBall(BallId.RED, BallId.YELLOW),
            PhysicsEvent.BallCushion(BallId.RED, Cushion.LEFT),
            cushion(), cushion(),
            hit(BallId.YELLOW),
        )
        assertFalse(r.scored)
        assertEquals(2, r.cushionsBeforeSecond)
    }

    @Test fun fourBallBothRedsScoresWithoutCushions() {
        val r = four(hit(BallId.RED), hit(BallId.RED2))
        assertTrue(r.scored)
        assertFalse(r.foul)
    }

    @Test fun fourBallTouchingOpponentIsFoulEvenAfterBothReds() {
        val r = four(hit(BallId.RED), hit(BallId.RED2), hit(BallId.YELLOW))
        assertFalse(r.scored)
        assertTrue(r.foul)
    }

    @Test fun fourBallOneRedIsAMiss() {
        val r = four(hit(BallId.RED), cushion(), hit(BallId.RED))
        assertFalse(r.scored)
        assertFalse(r.foul)
    }
}

class PhysicsTest {
    private val table = Table()
    private val r = table.ballRadius

    private fun shoot(b: Ball, dir: Vec2, speed: Double, tipX: Double = 0.0, tipY: Double = 0.0) {
        val (v, w) = PhysicsWorld.strike(r, dir, speed, tipX, tipY)
        b.vel = v
        b.spin = w
    }

    @Test fun ballSlowsDownAndStops() {
        val b = Ball(BallId.WHITE, Vec2(0.5, 0.71))
        shoot(b, Vec2(1.0, 0.0), 1.0)
        PhysicsWorld(table, listOf(b)).runToRest()
        assertFalse(b.isMoving)
        assertTrue("traveled ${b.pos.x - 0.5} m", b.pos.x - 0.5 in 0.5..2.2)
    }

    @Test fun fullPowerShotEndsInReasonableTime() {
        val b = Ball(BallId.WHITE, Vec2(0.5, 0.5))
        shoot(b, Vec2(1.0, 0.37).normalized(), Game.MAX_SPEED)
        val w = PhysicsWorld(table, listOf(b))
        var seconds = 0.0
        val events = w.runToRest { seconds = it }
        assertTrue("took $seconds s", seconds in 5.0..30.0)
        val cushions = events.count { it is PhysicsEvent.BallCushion }
        assertTrue("$cushions cushions", cushions in 5..25)
    }

    @Test fun centerHitTopspinIsNaturalRoll() {
        // Striking 2/5 of the radius above center rolls the ball with no slip at all.
        val (v, w) = PhysicsWorld.strike(r, Vec2(1.0, 0.0), 2.0, 0.0, 0.4)
        val slip = (v.to3() + (w cross Vec3(0.0, 0.0, r))).xy()
        assertEquals(0.0, slip.length(), 0.05)
    }

    @Test fun cushionReflectsAngleWithoutSpin() {
        val b = Ball(BallId.WHITE, Vec2(1.0, 0.1)).apply { vel = Vec2(1.0, -1.0); spin = Vec3(-1.0 / r, -1.0 / r, 0.0) }
        val w = PhysicsWorld(table, listOf(b))
        val events = ArrayList<PhysicsEvent>()
        while (events.none { it is PhysicsEvent.BallCushion }) events += w.advance(0.001)
        assertEquals(PhysicsEvent.BallCushion(BallId.WHITE, Cushion.TOP), events.first { it is PhysicsEvent.BallCushion })
        assertTrue(b.vel.y > 0)
        assertTrue("vy=${b.vel.y}", b.vel.y in 0.7..w.cushionRestitution)
        assertTrue("vx=${b.vel.x}", b.vel.x in 0.75..1.0)
    }

    private fun railRebound(tipX: Double): Vec2 {
        val b = Ball(BallId.WHITE, Vec2(2.5, 0.71))
        shoot(b, Vec2(1.0, 0.0), 2.0, tipX = tipX)
        val w = PhysicsWorld(table, listOf(b))
        while (w.advance(0.001).isEmpty()) Unit
        return b.vel
    }

    @Test fun sideSpinBendsCushionRebound() {
        val right = railRebound(0.5)
        val left = railRebound(-0.5)
        val none = railRebound(0.0)
        assertTrue(right.x < 0 && left.x < 0)
        assertEquals(0.0, none.y, 1e-6)
        assertTrue("right=$right", abs(right.y) > 0.1)
        assertEquals(-right.y, left.y, 1e-6)
    }

    /** Cue ball velocity shortly after a straight shot into a ball (before anything comes back off a rail). */
    private fun cueVelocityAfterStraightShot(tipY: Double): Vec2 {
        val cue = Ball(BallId.WHITE, Vec2(0.6, 0.71))
        val obj = Ball(BallId.RED, Vec2(0.9, 0.71))
        shoot(cue, Vec2(1.0, 0.0), 2.5, tipY = tipY)
        PhysicsWorld(table, listOf(cue, obj)).apply { ballRestitution = 1.0 }.advance(0.5)
        return cue.vel
    }

    @Test fun drawShotComesBack() {
        val v = cueVelocityAfterStraightShot(-0.6)
        assertTrue("v=$v", v.x < -0.3)
    }

    @Test fun followShotGoesThrough() {
        val v = cueVelocityAfterStraightShot(0.6)
        assertTrue("v=$v", v.x > 0.3)
    }

    @Test fun centerHitCloseUpStopsDead() {
        val cue = Ball(BallId.WHITE, Vec2(0.6, 0.71))
        val obj = Ball(BallId.RED, Vec2(0.7, 0.71))
        shoot(cue, Vec2(1.0, 0.0), 3.0)
        PhysicsWorld(table, listOf(cue, obj)).apply { ballRestitution = 1.0 }.advance(0.5)
        assertTrue("v=${cue.vel}", cue.vel.length() < 0.3)
    }

    @Test fun cutShotSendsBallsApart() {
        val a = Ball(BallId.WHITE, Vec2(1.0, 0.71)).apply { vel = Vec2(2.0, 0.0); spin = Vec3(0.0, -2.0 / r, 0.0) }
        val b = Ball(BallId.RED, Vec2(1.3, 0.71 + 0.03))
        val w = PhysicsWorld(table, listOf(a, b)).apply { ballRestitution = 1.0 }
        while (w.advance(0.001).isEmpty()) Unit
        assertEquals(0.0, a.vel dot b.vel, 0.05)
    }

    @Test fun aimPredictsGhostBallAndCushion() {
        val a = Ball(BallId.WHITE, Vec2(1.0, 0.71))
        val b = Ball(BallId.RED, Vec2(2.0, 0.71))
        val toBall = Aim.predict(table, listOf(a, b), BallId.WHITE, Vec2(1.0, 0.0))
        assertEquals(BallId.RED, toBall.hitBall)
        assertEquals(2.0 - 2 * r, toBall.contact.x, 1e-6)

        val toRail = Aim.predict(table, listOf(a, b), BallId.WHITE, Vec2(0.0, -1.0))
        assertEquals(null, toRail.hitBall)
        assertEquals(r, toRail.contact.y, 1e-6)
        assertEquals(Vec2(0.0, 1.0), toRail.reflected)
    }
}

class GameTest {
    private fun dir(angle: Double) = Vec2(cos(angle), sin(angle))

    private fun findScoringShot(mode: GameMode): Double? {
        for (power in listOf(0.4, 0.55, 0.7, 0.85)) {
            for (i in 0 until 720) {
                val g = Game(mode)
                val angle = i * 2 * PI / 720
                g.shoot(dir(angle), power)
                g.settle()
                if (g.lastResult!!.scored) {
                    assertEquals(0, g.currentPlayer)
                    assertEquals(1, g.scores[0])
                    return angle
                }
            }
        }
        return null
    }

    @Test fun missPassesTurnToYellow() {
        val g = Game()
        val white = g.cueBall.pos
        val red = g.ball(BallId.RED).pos
        g.shoot(red - white, 0.3)
        g.settle()
        assertFalse(g.lastResult!!.scored)
        assertEquals(1, g.currentPlayer)
        assertEquals(BallId.YELLOW, g.cueBallId)
    }

    @Test fun threeCushionOpeningHasAScoringShot() {
        assertNotNull(findScoringShot(GameMode.THREE_CUSHION))
    }

    @Test fun fourBallOpeningHasAScoringShot() {
        assertNotNull(findScoringShot(GameMode.FOUR_BALL))
    }

    @Test fun fourBallFoulCostsAPoint() {
        val g = Game(GameMode.FOUR_BALL)
        g.scores[0] = 3
        val white = g.cueBall.pos
        g.shoot(g.ball(BallId.YELLOW).pos - white, 0.3)
        g.settle()
        assertTrue(g.lastResult!!.foul)
        assertEquals(2, g.scores[0])
        assertEquals(1, g.currentPlayer)
    }

    @Test fun undoRestoresBallsScoreAndTurn() {
        val g = Game()
        val before = g.balls.map { it.pos }
        g.shoot(Vec2(1.0, 0.2), 0.6)
        g.settle()
        assertTrue(g.undo())
        assertEquals(before, g.balls.map { it.pos })
        assertEquals(0, g.currentPlayer)
        assertFalse(g.canUndo)
    }

    @Test fun replayRecordsTheWholeShot() {
        val g = Game()
        g.shoot(Vec2(1.0, 0.3), 0.6)
        g.settle()
        val frames = g.replay!!.frames
        assertTrue(frames.size > 60)
        assertEquals(g.balls.map { it.pos }, frames.last())
    }

    @Test fun reachingTargetEndsGame() {
        val angle = findScoringShot(GameMode.THREE_CUSHION)!!
        val g = Game(GameMode.THREE_CUSHION, targetScore = 1)
        for (power in listOf(0.4, 0.55, 0.7, 0.85)) {
            g.reset()
            g.shoot(dir(angle), power)
            g.settle()
            if (g.lastResult!!.scored) break
        }
        assertEquals(Phase.GAME_OVER, g.phase)
        assertEquals(0, g.winner)
    }
}
