package com.threecushion.billiards.engine

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign
import kotlin.math.sqrt

/** 2D vector in table coordinates (meters). x runs along the long side, y along the short side (downward on screen). */
data class Vec2(val x: Double, val y: Double) {
    operator fun plus(o: Vec2) = Vec2(x + o.x, y + o.y)
    operator fun minus(o: Vec2) = Vec2(x - o.x, y - o.y)
    operator fun times(s: Double) = Vec2(x * s, y * s)
    operator fun unaryMinus() = Vec2(-x, -y)
    infix fun dot(o: Vec2) = x * o.x + y * o.y
    fun length() = sqrt(x * x + y * y)
    fun normalized(): Vec2 {
        val l = length()
        return if (l < 1e-12) ZERO else Vec2(x / l, y / l)
    }

    /** Rotated 90 degrees: on screen (y down) this is clockwise, i.e. the right-hand side when facing along this vector. */
    fun perp() = Vec2(-y, x)

    fun to3() = Vec3(x, y, 0.0)

    companion object {
        val ZERO = Vec2(0.0, 0.0)
    }
}

/** 3D vector. z points down into the cloth, so (x, y, z) is right-handed with y down on screen. */
data class Vec3(val x: Double, val y: Double, val z: Double) {
    operator fun plus(o: Vec3) = Vec3(x + o.x, y + o.y, z + o.z)
    operator fun minus(o: Vec3) = Vec3(x - o.x, y - o.y, z - o.z)
    operator fun times(s: Double) = Vec3(x * s, y * s, z * s)
    infix fun cross(o: Vec3) = Vec3(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x)
    fun xy() = Vec2(x, y)

    companion object {
        val ZERO = Vec3(0.0, 0.0, 0.0)
        val UP = Vec3(0.0, 0.0, -1.0)
    }
}

/** Match-size carom table: 2.84 m x 1.42 m playing surface, 61.5 mm balls. */
data class Table(
    val width: Double = 2.84,
    val height: Double = 1.42,
    val ballRadius: Double = 0.03075,
)

enum class BallId { WHITE, YELLOW, RED, RED2 }

class Ball(val id: BallId, var pos: Vec2) {
    var vel: Vec2 = Vec2.ZERO

    /** Angular velocity (rad/s). x/y components are top/back spin, z is side spin. */
    var spin: Vec3 = Vec3.ZERO

    val isMoving get() = vel.x != 0.0 || vel.y != 0.0 || spin.x != 0.0 || spin.y != 0.0 || spin.z != 0.0

    fun stop() {
        vel = Vec2.ZERO
        spin = Vec3.ZERO
    }
}

enum class Cushion { LEFT, RIGHT, TOP, BOTTOM }

sealed class PhysicsEvent {
    data class BallBall(val a: BallId, val b: BallId) : PhysicsEvent()
    data class BallCushion(val ball: BallId, val cushion: Cushion) : PhysicsEvent()
}

/**
 * Top-down carom physics with full ball spin.
 *
 * A struck ball first slides; cloth friction turns the slide into a natural roll, which is
 * where follow and draw come from. Side spin bends the rebound off cushions. Ball-ball
 * collisions only exchange the normal velocity, so each ball keeps its spin.
 */
class PhysicsWorld(val table: Table, val balls: List<Ball>) {
    private val g = 9.81
    var slidingFriction = 0.2
    var rollingDecel = 0.12
    var linearDrag = 0.22
    var spinFriction = 0.045
    var ballRestitution = 0.94
    var cushionRestitution = 0.78
    var cushionFriction = 0.1
    var cushionRollKept = 0.2

    private val stepDt = 1.0 / 1000.0
    private var accumulator = 0.0
    private val r get() = table.ballRadius

    /** I / (m R^2) for a solid sphere. */
    private val inertia = 0.4

    val isMoving get() = balls.any { it.isMoving }

    fun advance(dt: Double): List<PhysicsEvent> {
        val events = ArrayList<PhysicsEvent>()
        accumulator += dt
        while (accumulator >= stepDt) {
            accumulator -= stepDt
            step(stepDt, events)
        }
        return events
    }

    fun runToRest(maxSeconds: Double = 120.0, onStep: ((Double) -> Unit)? = null): List<PhysicsEvent> {
        val events = ArrayList<PhysicsEvent>()
        var t = 0.0
        while (isMoving && t < maxSeconds) {
            step(stepDt, events)
            t += stepDt
            onStep?.invoke(t)
        }
        accumulator = 0.0
        return events
    }

    /** Velocity of the ball surface where it touches the cloth, relative to the cloth. */
    private fun clothSlip(b: Ball): Vec2 = (b.vel.to3() + (b.spin cross Vec3(0.0, 0.0, r))).xy()

    private fun step(dt: Double, events: MutableList<PhysicsEvent>) {
        for (b in balls) {
            if (!b.isMoving) continue
            b.pos = b.pos + b.vel * dt
            applyCloth(b, dt)
        }
        for (b in balls) collideCushions(b, events)
        for (i in balls.indices) for (j in i + 1 until balls.size) collideBalls(balls[i], balls[j], events)
    }

    private fun applyCloth(b: Ball, dt: Double) {
        val slip = clothSlip(b)
        val slipSpeed = slip.length()
        // Slip shrinks 3.5x faster than the velocity changes (solid sphere), so this is the full stop amount.
        val maxImpulse = slipSpeed / (1 + 1 / inertia)
        val frictionImpulse = slidingFriction * g * dt
        if (slipSpeed > 1e-6) {
            val j = min(frictionImpulse, maxImpulse)
            val dir = slip * (-1.0 / slipSpeed)
            applyClothImpulse(b, dir * j)
        }
        if (slipSpeed <= 1e-6 || frictionImpulse >= maxImpulse) {
            // Rolling: resistance slows the ball and the roll stays matched to the velocity.
            val speed = b.vel.length()
            val newSpeed = speed - (rollingDecel + linearDrag * speed) * dt
            b.vel = if (newSpeed <= 0.004) Vec2.ZERO else b.vel * (newSpeed / speed)
            b.spin = Vec3(b.vel.y / r, -b.vel.x / r, b.spin.z)
        }
        // Side spin is slowed by the small contact patch on the cloth.
        val dz = 2.5 * spinFriction * g / r * dt
        b.spin = Vec3(b.spin.x, b.spin.y, if (abs(b.spin.z) <= dz) 0.0 else b.spin.z - sign(b.spin.z) * dz)
        if (b.vel.length() < 0.004 && clothSlip(b).length() < 0.004) b.stop()
    }

    /** Applies a horizontal impulse (per unit mass) at the cloth contact point. */
    private fun applyClothImpulse(b: Ball, j: Vec2) {
        b.vel = b.vel + j
        val torque = Vec3(0.0, 0.0, r) cross j.to3()
        b.spin = b.spin + torque * (1 / (inertia * r * r))
    }

    private fun collideCushions(b: Ball, events: MutableList<PhysicsEvent>) {
        if (b.pos.x < r && b.vel.x < 0) bounce(b, Vec2(-1.0, 0.0), Cushion.LEFT, events)
        else if (b.pos.x > table.width - r && b.vel.x > 0) bounce(b, Vec2(1.0, 0.0), Cushion.RIGHT, events)
        if (b.pos.y < r && b.vel.y < 0) bounce(b, Vec2(0.0, -1.0), Cushion.TOP, events)
        else if (b.pos.y > table.height - r && b.vel.y > 0) bounce(b, Vec2(0.0, 1.0), Cushion.BOTTOM, events)
        b.pos = Vec2(b.pos.x.coerceIn(r, table.width - r), b.pos.y.coerceIn(r, table.height - r))
    }

    /** [n] points from the ball center toward the cushion. */
    private fun bounce(b: Ball, n: Vec2, cushion: Cushion, events: MutableList<PhysicsEvent>) {
        val vn = b.vel dot n
        val normalImpulse = (1 + cushionRestitution) * vn
        b.vel = b.vel - n * normalImpulse
        // Friction at the cushion contact acts against the slip there, which is what side spin creates.
        val contact = (n * r).to3()
        val slip = (b.vel.to3() + (b.spin cross contact)).xy()
        val t = n.perp()
        val slipT = slip dot t
        val jt = -sign(slipT) * min(cushionFriction * normalImpulse, abs(slipT) / (1 + 1 / inertia))
        val j = t * jt
        b.vel = b.vel + j
        b.spin = b.spin + (contact cross j.to3()) * (1 / (inertia * r * r))
        // The cushion nose sits above the center and kills most of the roll toward the cushion,
        // while the roll along the cushion survives.
        val roll = Vec2(b.spin.x, b.spin.y)
        val along = n * (roll dot n)
        val toward = (roll - along) * cushionRollKept
        b.spin = Vec3(along.x + toward.x, along.y + toward.y, b.spin.z)
        events += PhysicsEvent.BallCushion(b.id, cushion)
    }

    private fun collideBalls(a: Ball, b: Ball, events: MutableList<PhysicsEvent>) {
        val d = b.pos - a.pos
        val dist = d.length()
        val minDist = 2 * r
        if (dist >= minDist || dist < 1e-9) return
        val n = d * (1.0 / dist)
        val overlap = (minDist - dist) / 2
        a.pos = a.pos - n * overlap
        b.pos = b.pos + n * overlap
        val approach = (a.vel - b.vel) dot n
        if (approach <= 0) return
        val j = approach * (1 + ballRestitution) / 2
        a.vel = a.vel - n * j
        b.vel = b.vel + n * j
        events += PhysicsEvent.BallBall(a.id, b.id)
    }

    companion object {
        /**
         * Velocity and spin a cue gives a ball.
         * @param dir unit direction of the stroke
         * @param tipX horizontal tip offset as a fraction of the radius, right positive
         * @param tipY vertical tip offset as a fraction of the radius, up positive
         */
        fun strike(radius: Double, dir: Vec2, speed: Double, tipX: Double, tipY: Double): Pair<Vec2, Vec3> {
            val d = dir.normalized()
            val v = d * speed
            val right = d.to3() cross Vec3.UP
            val depth = sqrt(max(0.0, 1 - tipX * tipX - tipY * tipY))
            val hit = (d.to3() * (-depth) + right * tipX + Vec3.UP * tipY) * radius
            val spin = (hit cross v.to3()) * (1 / (0.4 * radius * radius))
            return v to Vec3(spin.x, spin.y, spin.z)
        }
    }
}

/** Result of casting the aim line from the cue ball. */
data class AimPrediction(val contact: Vec2, val hitBall: BallId?, val reflected: Vec2?)

object Aim {
    /** Straight-line cast (ignores friction and spin) used for the on-screen guide. */
    fun predict(table: Table, balls: List<Ball>, cue: BallId, dir: Vec2): AimPrediction {
        val p = balls.first { it.id == cue }.pos
        val d = dir.normalized()
        val r = table.ballRadius
        var bestT = Double.MAX_VALUE
        var hit: BallId? = null
        for (o in balls) {
            if (o.id == cue) continue
            val m = p - o.pos
            val bq = m dot d
            val c = (m dot m) - 4 * r * r
            val disc = bq * bq - c
            if (disc < 0) continue
            val t = -bq - sqrt(disc)
            if (t > 0 && t < bestT) {
                bestT = t
                hit = o.id
            }
        }
        val tx = when {
            d.x > 1e-9 -> (table.width - r - p.x) / d.x
            d.x < -1e-9 -> (r - p.x) / d.x
            else -> Double.MAX_VALUE
        }
        val ty = when {
            d.y > 1e-9 -> (table.height - r - p.y) / d.y
            d.y < -1e-9 -> (r - p.y) / d.y
            else -> Double.MAX_VALUE
        }
        val wallT = max(min(tx, ty), 0.0)
        return if (hit != null && bestT <= wallT) {
            AimPrediction(p + d * bestT, hit, null)
        } else {
            AimPrediction(p + d * wallT, null, if (tx <= ty) Vec2(-d.x, d.y) else Vec2(d.x, -d.y))
        }
    }
}
