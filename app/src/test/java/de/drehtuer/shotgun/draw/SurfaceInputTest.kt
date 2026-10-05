package de.drehtuer.shotgun.draw

import de.drehtuer.shotgun.ui.navigation.DrawMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * What the draw surface does with a pointer event, and how its glow and frame
 * follow the countdown - driven through a real [DrawEngine], with no pointers.
 */
class SurfaceInputTest {

    private fun engine(seconds: Int = 3) =
        DrawEngine(DrawMode.STARTER, teamCount = 2, countdownMillis = seconds * 1_000, instantReveal = false, random = Random(1))

    private fun down(id: Long, x: Float = 10f, y: Float = 20f, consumed: Boolean = false) =
        Contact(id, x, y, pressed = true, consumed = consumed)

    private fun up(id: Long, consumed: Boolean = false) =
        Contact(id, 0f, 0f, pressed = false, consumed = consumed)

    /** Records every callback, in the order it arrived. */
    private class Log {
        val calls = mutableListOf<String>()
        val effects = mutableListOf<DrawEffect?>()
    }

    private fun route(engine: DrawEngine, action: PointerAction, vararg contacts: Contact, now: Long = 0, log: Log = Log()): Log {
        routePointers(
            action = action,
            contacts = contacts.toList(),
            engine = engine,
            now = now,
            onLanded = { log.calls += "landed"; log.effects += it },
            onMoved = { log.calls += "moved" },
            onLifted = { log.calls += "lifted" },
        )
        return log
    }

    // ---- presses ------------------------------------------------------------

    @Test
    fun `a press lands a finger where it touched, and ticks`() {
        val e = engine()
        val log = route(e, PointerAction.PRESS, down(1, x = 40f, y = 90f))

        assertEquals(listOf(Finger(1, 40f, 90f)), e.fingers)
        assertEquals(listOf<DrawEffect?>(DrawEffect.FingerTick), log.effects)
    }

    /**
     * The bug this filter fixed: a tap on MODES or DETAILS also landed a
     * finger, so the chrome hid itself mid-tap and the click never completed.
     */
    @Test
    fun `a press a child has consumed lands no finger`() {
        val e = engine()
        val log = route(e, PointerAction.PRESS, down(1, consumed = true))

        assertTrue(e.fingers.isEmpty())
        assertTrue(log.calls.isEmpty())
    }

    /**
     * A press event lists every pointer that is down, not just the new one.
     * The fingers already there must not be counted again, or one hand would
     * restart the countdown each time another joined - which it should do only
     * once.
     */
    @Test
    fun `a press repeats the fingers already down without counting them twice`() {
        val e = engine()
        route(e, PointerAction.PRESS, down(1))
        val log = route(e, PointerAction.PRESS, down(1), down(2), now = 100)

        assertEquals(listOf(1L, 2L), e.fingers.map { it.id })
        assertEquals(listOf(null, DrawEffect.FingerTick), log.effects)
        assertEquals(DrawPhase.COUNTING, e.phase)
    }

    @Test
    fun `a change that is no longer pressed is not a press`() {
        val e = engine()
        val log = route(e, PointerAction.PRESS, up(1))

        assertTrue(e.fingers.isEmpty())
        assertTrue(log.calls.isEmpty())
    }

    @Test
    fun `the second finger down arms the countdown at the event's time`() {
        val e = engine(seconds = 3)
        route(e, PointerAction.PRESS, down(1), now = 0)
        route(e, PointerAction.PRESS, down(1), down(2), now = 500)

        assertNull(e.tick(now = 3_499))
        assertNotNull(e.tick(now = 3_500))
    }

    // ---- moves --------------------------------------------------------------

    @Test
    fun `a move repositions the finger and reports each one`() {
        val e = engine()
        route(e, PointerAction.PRESS, down(1), down(2))
        val log = route(
            e,
            PointerAction.MOVE,
            Contact(1, 50f, 60f, pressed = true, consumed = false),
            Contact(2, 70f, 80f, pressed = true, consumed = false),
        )

        assertEquals(listOf(Finger(1, 50f, 60f), Finger(2, 70f, 80f)), e.fingers)
        assertEquals(listOf("moved", "moved"), log.calls)
    }

    /** A hand on glass jitters constantly; that must not restart the countdown. */
    @Test
    fun `moving does not put time back on the countdown`() {
        val e = engine(seconds = 3)
        route(e, PointerAction.PRESS, down(1), down(2), now = 0)
        repeat(50) { route(e, PointerAction.MOVE, down(1, x = it.toFloat())) }

        assertNotNull(e.tick(now = 3_000))
    }

    @Test
    fun `a move a child has consumed leaves the finger where it was`() {
        val e = engine()
        route(e, PointerAction.PRESS, down(1, x = 10f, y = 20f))
        val log = route(e, PointerAction.MOVE, down(1, x = 99f, y = 99f, consumed = true))

        assertEquals(listOf(Finger(1, 10f, 20f)), e.fingers)
        assertTrue(log.calls.isEmpty())
    }

    // ---- releases -----------------------------------------------------------

    /**
     * Unlike a press, a release is not filtered on consumption: whoever claimed
     * the change, the hand has gone, and a finger left behind would stay in the
     * draw with nobody on it.
     */
    @Test
    fun `a release lifts the finger even when a child consumed it`() {
        val e = engine()
        route(e, PointerAction.PRESS, down(1), down(2))
        val log = route(e, PointerAction.RELEASE, up(1, consumed = true), down(2))

        assertEquals(listOf(2L), e.fingers.map { it.id })
        assertEquals(listOf("lifted"), log.calls)
        assertEquals(DrawPhase.IDLE, e.phase)
    }

    /** The screen publishes after each lift, so it must see the engine already updated. */
    @Test
    fun `each lift is reported after the engine has let the finger go`() {
        val e = engine()
        route(e, PointerAction.PRESS, down(1), down(2))
        val seen = mutableListOf<Int>()
        routePointers(
            action = PointerAction.RELEASE,
            contacts = listOf(up(1), up(2)),
            engine = e,
            now = 0,
            onLanded = {},
            onMoved = {},
            onLifted = { seen += e.fingers.size },
        )

        assertEquals(listOf(1, 0), seen)
    }

    // ---- glow and frame -----------------------------------------------------

    @Test
    fun `the bare surface has no glow and no frame`() {
        assertEquals(0f, edgeGlowAlpha(DrawPhase.IDLE, progress = 0.7f))
        assertEquals(0f, frameAlpha(DrawPhase.IDLE, progress = 0.7f))
    }

    /** The edge glow is the countdown: it has to visibly grow as time runs out. */
    @Test
    fun `the glow and the frame grow as the countdown runs`() {
        val glow = listOf(0f, 0.5f, 1f).map { edgeGlowAlpha(DrawPhase.COUNTING, it) }
        val frame = listOf(0f, 0.5f, 1f).map { frameAlpha(DrawPhase.COUNTING, it) }

        assertTrue("glow $glow", glow[0] > 0f && glow[0] < glow[1] && glow[1] < glow[2])
        assertTrue("frame $frame", frame[0] > 0f && frame[0] < frame[1] && frame[1] < frame[2])
        assertTrue(glow[2] <= 1f && frame[2] <= 1f)
    }

    /** Once a result is up the glow stays as a trace, fainter than any countdown. */
    @Test
    fun `after the draw the glow fades to a trace and the frame goes`() {
        for (phase in listOf(DrawPhase.REVEALING, DrawPhase.REVEALED)) {
            val trace = edgeGlowAlpha(phase, progress = 1f)
            assertTrue("$phase glow $trace", trace > 0f && trace < edgeGlowAlpha(DrawPhase.COUNTING, 0f))
            assertEquals(0f, frameAlpha(phase, progress = 1f))
        }
    }
}
