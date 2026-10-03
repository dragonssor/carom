package com.threecushion.billiards.engine

/** Outcome of one shot. */
data class ShotResult(
    val scored: Boolean,
    /** The shooter touched the opponent's ball (4-ball); costs a point and the turn. */
    val foul: Boolean,
    /** Balls the cue ball touched, in order. */
    val objectsHit: List<BallId>,
    /** Cue ball cushions before it touched the second object ball (or in total, if it never did). */
    val cushionsBeforeSecond: Int,
)

interface Referee {
    fun onEvent(e: PhysicsEvent)
    fun result(): ShotResult
}

/** Base for both games: tracks what the cue ball touched and how many cushions it hit along the way. */
abstract class CueBallTracker(protected val cue: BallId) : Referee {
    protected val hits = ArrayList<BallId>()
    protected var cushions = 0
    protected var cushionsAtSecond: Int? = null

    override fun onEvent(e: PhysicsEvent) {
        when (e) {
            is PhysicsEvent.BallCushion -> if (e.ball == cue && cushionsAtSecond == null) cushions++
            is PhysicsEvent.BallBall -> {
                val other = when (cue) {
                    e.a -> e.b
                    e.b -> e.a
                    else -> return
                }
                if (other !in hits) {
                    hits += other
                    if (hits.size == 2) cushionsAtSecond = cushions
                }
            }
        }
    }
}

/**
 * 3-cushion: the cue ball must touch both object balls and touch at least three cushions
 * before it touches the second one.
 */
class ThreeCushionReferee(cue: BallId) : CueBallTracker(cue) {
    override fun result(): ShotResult {
        val atSecond = cushionsAtSecond
        return ShotResult(
            scored = atSecond != null && atSecond >= 3,
            foul = false,
            objectsHit = hits.toList(),
            cushionsBeforeSecond = atSecond ?: cushions,
        )
    }
}

/** Korean 4-ball: touch both red balls without touching the opponent's ball. */
class FourBallReferee(cue: BallId, private val opponent: BallId) : CueBallTracker(cue) {
    override fun result(): ShotResult {
        val foul = opponent in hits
        return ShotResult(
            scored = !foul && BallId.RED in hits && BallId.RED2 in hits,
            foul = foul,
            objectsHit = hits.toList(),
            cushionsBeforeSecond = cushionsAtSecond ?: cushions,
        )
    }
}
