package de.drehtuer.shotgun.draw

import de.drehtuer.shotgun.ui.navigation.DrawMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class DrawEngineTest {

    private fun engine(
        mode: DrawMode = DrawMode.STARTER,
        teams: Int = 3,
        seconds: Int = 3,
        instant: Boolean = false,
        seed: Int = 1,
    ) = DrawEngine(mode, teams, seconds * 1_000, instant, Random(seed))

    // ---- arming and the countdown ------------------------------------------

    @Test
    fun `one finger does not arm anything`() {
        val e = engine()
        e.onDown(1, 0f, 0f, now = 0)
        assertEquals(DrawPhase.IDLE, e.phase)
        assertNull(e.tick(now = 10_000))
    }

    @Test
    fun `the second finger arms the countdown`() {
        val e = engine(seconds = 3)
        e.onDown(1, 0f, 0f, now = 0)
        e.onDown(2, 0f, 0f, now = 100)
        assertEquals(DrawPhase.COUNTING, e.phase)
        assertNull(e.tick(now = 3_000))              // armed at 100, due at 3100
        assertNotNull(e.tick(now = 3_100))
    }

    /**
     * The countdown is a settling time, not a fixed delay: a finger joining
     * puts the whole countdown back on the clock, so nobody is caught out by a
     * draw firing as they reach in.
     */
    @Test
    fun `a finger joining restarts the countdown in full`() {
        val e = engine(seconds = 3)
        e.onDown(1, 0f, 0f, now = 0)
        e.onDown(2, 0f, 0f, now = 0)                 // due at 3000
        e.onDown(3, 0f, 0f, now = 2_000)             // restarts: due at 5000
        assertNull(e.tick(now = 4_999))
        assertNotNull(e.tick(now = 5_000))
    }

    /** Leaving is a change too, and earns the same settling time. */
    @Test
    fun `a finger leaving restarts the countdown in full`() {
        val e = engine(seconds = 3)
        (1L..3L).forEach { e.onDown(it, 0f, 0f, now = 0) }   // due at 3000
        e.onUp(3, now = 2_000)                               // restarts: due at 5000
        assertEquals(DrawPhase.COUNTING, e.phase)
        assertNull(e.tick(now = 4_999))
        assertNotNull(e.tick(now = 5_000))
    }

    /** Repositioning is not a change in the count, so it must not restart it. */
    @Test
    fun `moving a finger does not restart the countdown`() {
        val e = engine(seconds = 3)
        e.onDown(1, 0f, 0f, now = 0)
        e.onDown(2, 0f, 0f, now = 0)                 // due at 3000
        repeat(20) { e.onMove(1, it.toFloat(), it.toFloat()) }
        assertNotNull(e.tick(now = 3_000))
    }

    @Test
    fun `dropping below two fingers cancels the countdown`() {
        val e = engine()
        e.onDown(1, 0f, 0f, now = 0)
        e.onDown(2, 0f, 0f, now = 0)
        e.onUp(2, now = 100)
        assertEquals(DrawPhase.IDLE, e.phase)
        assertNull(e.tick(now = 10_000))
    }

    @Test
    fun `progress runs zero to one across the countdown`() {
        val e = engine(seconds = 2)
        e.onDown(1, 0f, 0f, now = 0)
        e.onDown(2, 0f, 0f, now = 0)
        assertEquals(0f, e.progress(0), 1e-4f)
        assertEquals(0.5f, e.progress(1_000), 1e-4f)
        assertEquals(1f, e.progress(2_000), 1e-4f)
        assertEquals(1f, e.progress(9_999), 1e-4f)   // never overshoots
    }

    // ---- touch rules --------------------------------------------------------

    /** The rule the hint text teaches: moving is not joining. */
    @Test
    fun `moving a finger repositions it and never adds a player`() {
        val e = engine()
        e.onDown(1, 10f, 10f, now = 0)
        e.onMove(1, 50f, 60f)
        assertEquals(1, e.fingers.size)
        assertEquals(50f, e.fingers.first().x, 1e-4f)
        assertEquals(60f, e.fingers.first().y, 1e-4f)
    }

    @Test
    fun `the same pointer landing twice is ignored`() {
        val e = engine()
        e.onDown(1, 0f, 0f, now = 0)
        assertNull(e.onDown(1, 5f, 5f, now = 10))
        assertEquals(1, e.fingers.size)
    }

    /** A latecomer must not join a draw that has already been decided. */
    @Test
    fun `a finger landing while the result is still held is ignored`() {
        val e = engine(instant = true)
        e.onDown(1, 0f, 0f, now = 0)
        e.onDown(2, 0f, 0f, now = 0)
        e.tick(now = 5_000)
        assertEquals(DrawPhase.REVEALED, e.phase)
        assertNull(e.onDown(3, 0f, 0f, now = 5_100))
        assertEquals(2, e.fingers.size)
    }

    /**
     * You have to lift your hand to see what is under it, so the result has to
     * outlive the fingers that produced it.
     */
    @Test
    fun `the result survives every hand leaving the glass`() {
        val e = engine(instant = true)
        e.onDown(1, 0f, 0f, now = 0)
        e.onDown(2, 0f, 0f, now = 0)
        e.tick(now = 5_000)
        val drawn = e.outcome
        e.onUp(1, now = 10)
        e.onUp(2, now = 20)
        assertEquals(DrawPhase.REVEALED, e.phase)
        assertEquals(drawn, e.outcome)
        assertEquals(2, drawn?.fingers?.size)
    }

    /** Once everyone has lifted, the round is over and a press starts a new one. */
    @Test
    fun `a press after everyone has lifted starts a fresh draw`() {
        val e = engine(instant = true)
        e.onDown(1, 0f, 0f, now = 0)
        e.onDown(2, 0f, 0f, now = 0)
        e.tick(now = 5_000)
        e.onUp(1, now = 10); e.onUp(2, now = 20)

        assertEquals(DrawEffect.FingerTick, e.onDown(3, 10f, 10f, now = 6_000))
        assertEquals(DrawPhase.IDLE, e.phase)
        assertNull(e.outcome)
        assertEquals(1, e.fingers.size)
    }

    @Test
    fun `clearing discards a shown result`() {
        val e = engine(instant = true)
        e.onDown(1, 0f, 0f, now = 0)
        e.onDown(2, 0f, 0f, now = 0)
        e.tick(now = 5_000)
        e.clear()
        assertEquals(DrawPhase.IDLE, e.phase)
        assertNull(e.outcome)
    }

    // ---- drawing ------------------------------------------------------------

    @Test
    fun `starter picks exactly one winner from the fingers present`() {
        val e = engine(mode = DrawMode.STARTER)
        listOf(1L, 2L, 3L, 4L).forEach { e.onDown(it, 0f, 0f, now = 0) }
        val effect = e.tick(now = 100_000) as DrawEffect.Drawn
        assertTrue(effect.outcome.winnerId in listOf(1L, 2L, 3L, 4L))
        assertEquals(4, effect.outcome.assignment.size)
    }

    @Test
    fun `order gives every finger a distinct rank from one upwards`() {
        val e = engine(mode = DrawMode.ORDER)
        listOf(1L, 2L, 3L, 4L, 5L).forEach { e.onDown(it, 0f, 0f, now = 0) }
        val outcome = (e.tick(now = 100_000) as DrawEffect.Drawn).outcome
        assertEquals(listOf(1, 2, 3, 4, 5), outcome.assignment.values.sorted())
        assertEquals(1, outcome.assignment[outcome.winnerId])
    }

    @Test
    fun `teams refuses when there are fewer fingers than teams`() {
        val e = engine(mode = DrawMode.TEAMS, teams = 5)
        e.onDown(1, 0f, 0f, now = 0)
        e.onDown(2, 0f, 0f, now = 0)
        val effect = e.tick(now = 100_000)
        assertTrue(effect is DrawEffect.Refused)
        assertEquals("2 fingers can't fill 5 teams", (effect as DrawEffect.Refused).message)
        assertEquals(DrawPhase.IDLE, e.phase)
        assertNull(e.outcome)
    }

    /**
     * Suspense no longer delays the whole result - it walks it out one finger at
     * a time, starting immediately.
     */
    @Test
    fun `suspense reveals the fingers one at a time, first one at once`() {
        val e = engine(mode = DrawMode.ORDER, instant = false)
        (1L..4L).forEach { e.onDown(it, 0f, 0f, now = 0) }
        val order = (e.tick(now = 100_000) as DrawEffect.Drawn).outcome.order

        assertEquals(DrawPhase.REVEALING, e.phase)
        assertTrue(e.isRevealed(order[0]))
        assertTrue(!e.isRevealed(order[1]))

        e.revealNext()
        assertTrue(e.isRevealed(order[1]))
        assertTrue(!e.isRevealed(order[2]))

        e.revealNext(); e.revealNext()
        assertEquals(DrawPhase.REVEALED, e.phase)
        assertTrue(order.all(e::isRevealed))
    }

    /** Ranks come out 1, 2, 3 - the reveal follows the draw order. */
    @Test
    fun `the reveal order is the rank order`() {
        val e = engine(mode = DrawMode.ORDER, instant = false)
        (1L..5L).forEach { e.onDown(it, 0f, 0f, now = 0) }
        val outcome = (e.tick(now = 100_000) as DrawEffect.Drawn).outcome
        assertEquals(
            listOf(1, 2, 3, 4, 5),
            outcome.order.map { outcome.assignment.getValue(it) },
        )
    }

    /**
     * Teams are dealt round robin, so walking the reveal along the draw order
     * steps between teams on every step, as asked for.
     */
    @Test
    fun `the teams reveal steps between teams`() {
        val e = engine(mode = DrawMode.TEAMS, teams = 3, instant = false)
        (1L..6L).forEach { e.onDown(it, 0f, 0f, now = 0) }
        val outcome = (e.tick(now = 100_000) as DrawEffect.Drawn).outcome
        val teams = outcome.order.map { outcome.assignment.getValue(it) }
        assertEquals(listOf(0, 1, 2, 0, 1, 2), teams)
    }

    /** A single winner has nothing to stagger. */
    @Test
    fun `starter never stages its reveal`() {
        val e = engine(mode = DrawMode.STARTER, instant = false)
        (1L..4L).forEach { e.onDown(it, 0f, 0f, now = 0) }
        val outcome = (e.tick(now = 100_000) as DrawEffect.Drawn).outcome
        assertEquals(DrawPhase.REVEALED, e.phase)
        assertTrue(outcome.order.all(e::isRevealed))
    }

    @Test
    fun `instant shows everyone at once`() {
        val e = engine(mode = DrawMode.ORDER, instant = true)
        (1L..4L).forEach { e.onDown(it, 0f, 0f, now = 0) }
        val outcome = (e.tick(now = 100_000) as DrawEffect.Drawn).outcome
        assertEquals(DrawPhase.REVEALED, e.phase)
        assertTrue(outcome.order.all(e::isRevealed))
    }

    /**
     * The fairness claim the result screen makes. Every finger must win about
     * as often as any other, whatever order they landed in.
     */
    @Test
    fun `over many draws every position wins about equally often`() {
        val wins = IntArray(5)
        repeat(10_000) { seed ->
                val e = DrawEngine(DrawMode.STARTER, 3, 3_000, true, Random(seed))
            (0L..4L).forEach { e.onDown(it, 0f, 0f, now = 0) }
            val outcome = (e.tick(now = 100_000) as DrawEffect.Drawn).outcome
            wins[outcome.winnerId.toInt()]++
        }
        val expected = 10_000 / 5
        wins.forEachIndexed { i, count ->
            assertTrue(
                "finger $i won $count times, expected about $expected",
                count > expected * 0.85 && count < expected * 1.15,
            )
        }
    }

    // ---- how many fingers ---------------------------------------------------

    // There is deliberately no cap on players. The limit is the touchscreen:
    // Android reports a device-dependent maximum number of simultaneous
    // pointers, commonly ten, and the engine simply draws for whatever it is
    // given. These pin the behaviour at a full pair of hands, which is the most
    // any real group can put on one phone.

    @Test
    fun `ten fingers all get a distinct rank`() {
        val e = engine(mode = DrawMode.ORDER, instant = true)
        repeat(10) { e.onDown(it.toLong(), it * 10f, 0f, now = 0) }
        val drawn = e.tick(now = 10_000) as DrawEffect.Drawn

        assertEquals(10, drawn.outcome.order.size)
        assertEquals(10, drawn.outcome.order.toSet().size)
        assertEquals((1..10).toSet(), drawn.outcome.assignment.values.toSet())
    }

    @Test
    fun `ten fingers across three teams are dealt round robin`() {
        val e = engine(mode = DrawMode.TEAMS, teams = 3, instant = true)
        repeat(10) { e.onDown(it.toLong(), it * 10f, 0f, now = 0) }
        val drawn = e.tick(now = 10_000) as DrawEffect.Drawn

        // Ten into three: sizes differ by at most one, and nobody is left out.
        val sizes = drawn.outcome.assignment.values.groupingBy { it }.eachCount()
        assertEquals(setOf(0, 1, 2), sizes.keys)
        assertEquals(10, sizes.values.sum())
        assertEquals(1, sizes.values.max() - sizes.values.min())
    }

    @Test
    fun `a finger lifted from a large group does not disturb the rest`() {
        val e = engine(mode = DrawMode.ORDER, instant = true)
        repeat(10) { e.onDown(it.toLong(), it * 10f, 0f, now = 0) }
        e.onUp(4, now = 100)
        val drawn = e.tick(now = 10_000) as DrawEffect.Drawn

        assertEquals(9, drawn.outcome.order.size)
        assertTrue(4L !in drawn.outcome.order)
    }

    // ---- guards: the paths taken when there is nothing to act on ------------

    /**
     * These are the early returns. They are cheap to get wrong and invisible
     * when they are - a stray pointer id from the framework, or a timer tick
     * arriving one frame after the phase moved on, must do nothing at all.
     */

    @Test
    fun `nothing is revealed before a draw has happened`() {
        val e = engine()
        assertEquals(false, e.isRevealed(1))
    }

    @Test
    fun `a finger that was not in the draw is never revealed`() {
        val e = engine(mode = DrawMode.ORDER, instant = true)
        e.onDown(1, 0f, 0f, now = 0)
        e.onDown(2, 0f, 0f, now = 0)
        e.tick(now = 10_000)
        assertEquals(true, e.isRevealed(1))
        assertEquals(false, e.isRevealed(99))
    }

    @Test
    fun `progress is zero when idle and full once drawn`() {
        val e = engine(mode = DrawMode.ORDER, instant = true)
        assertEquals(0f, e.progress(now = 0), 1e-4f)
        e.onDown(1, 0f, 0f, now = 0)
        assertEquals(0f, e.progress(now = 0), 1e-4f)   // one finger: still idle
        e.onDown(2, 0f, 0f, now = 0)
        e.tick(now = 10_000)
        assertEquals(1f, e.progress(now = 10_000), 1e-4f)
    }

    @Test
    fun `a latecomer cannot join a draw that is still revealing`() {
        val e = engine(mode = DrawMode.ORDER, instant = false)
        e.onDown(1, 0f, 0f, now = 0)
        e.onDown(2, 0f, 0f, now = 0)
        e.tick(now = 10_000)
        assertEquals(DrawPhase.REVEALING, e.phase)

        assertNull(e.onDown(3, 0f, 0f, now = 10_100))
        assertEquals(2, e.fingers.size)
    }

    @Test
    fun `moving a finger that is not down does nothing`() {
        val e = engine()
        e.onDown(1, 10f, 10f, now = 0)
        e.onMove(99, 500f, 500f)
        assertEquals(listOf(10f to 10f), e.fingers.map { it.x to it.y })
    }

    @Test
    fun `lifting a finger that is not down does nothing`() {
        val e = engine(seconds = 3)
        e.onDown(1, 0f, 0f, now = 0)
        e.onDown(2, 0f, 0f, now = 0)
        e.onUp(99, now = 100)
        assertEquals(2, e.fingers.size)
        assertEquals(DrawPhase.COUNTING, e.phase)
    }

    @Test
    fun `lifting the only finger leaves the surface idle`() {
        val e = engine()
        e.onDown(1, 0f, 0f, now = 0)
        e.onUp(1, now = 100)
        assertEquals(DrawPhase.IDLE, e.phase)
        assertEquals(0, e.fingers.size)
    }

    @Test
    fun `revealing more does nothing when nothing is being revealed`() {
        val e = engine(mode = DrawMode.ORDER, instant = true)
        e.revealNext()                                  // no outcome at all
        assertEquals(0, e.revealedCount)

        e.onDown(1, 0f, 0f, now = 0)
        e.onDown(2, 0f, 0f, now = 0)
        e.tick(now = 10_000)                            // instant: straight to REVEALED
        assertEquals(DrawPhase.REVEALED, e.phase)
        val shown = e.revealedCount
        e.revealNext()
        assertEquals(shown, e.revealedCount)
    }

    @Test
    fun `a staged reveal ends at revealed and then stops counting`() {
        val e = engine(mode = DrawMode.ORDER, instant = false)
        e.onDown(1, 0f, 0f, now = 0)
        e.onDown(2, 0f, 0f, now = 0)
        e.tick(now = 10_000)
        assertEquals(1, e.revealedCount)                // the first is free

        e.revealNext()
        assertEquals(DrawPhase.REVEALED, e.phase)
        assertEquals(2, e.revealedCount)

        e.revealNext()                                  // past the end
        assertEquals(2, e.revealedCount)
    }
}
