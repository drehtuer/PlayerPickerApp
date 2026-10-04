package de.drehtuer.shotgun.draw

/**
 * The draw surface's decisions, lifted out of `DrawScreen` so a JVM test can
 * reach them. **Multi-touch cannot be injected under Robolectric**, so anything
 * left inside the `pointerInput` block is covered only on the phone; what is
 * here is covered on every build.
 */

/** The three pointer events the surface acts on. Everything else is ignored. */
enum class PointerAction { PRESS, MOVE, RELEASE }

/**
 * One pointer change, as plain data: the parts of a Compose
 * `PointerInputChange` that decide what happens to it.
 *
 * [consumed] means a child - the MODES link, the DETAILS button - has already
 * claimed the change.
 */
data class Contact(
    val id: Long,
    val x: Float,
    val y: Float,
    val pressed: Boolean,
    val consumed: Boolean,
)

/**
 * Feeds one pointer event into [engine], change by change, in order.
 *
 * After each change it calls back so the screen can publish the new state:
 * [onLanded] with the engine's effect for a press, [onMoved] for a move,
 * [onLifted] for a release. The callbacks run once per change that got through,
 * immediately after the engine saw it.
 */
fun routePointers(
    action: PointerAction,
    contacts: List<Contact>,
    engine: DrawEngine,
    now: Long,
    onLanded: (DrawEffect?) -> Unit,
    onMoved: () -> Unit,
    onLifted: () -> Unit,
) {
    when (action) {
        // A change consumed by a child - the MODES link, the DETAILS button -
        // is that child's business. Without this the press also lands a
        // finger, the chrome hides itself mid-tap, and the click never
        // completes.
        PointerAction.PRESS ->
            contacts.filter { it.pressed && !it.consumed }.forEach {
                onLanded(engine.onDown(it.id, it.x, it.y, now))
            }

        PointerAction.MOVE ->
            contacts.filter { !it.consumed }.forEach {
                engine.onMove(it.id, it.x, it.y)
                onMoved()
            }

        // Deliberately not filtered on `consumed`: a finger that is lifting
        // has to leave the engine whoever claimed the change, or it would stay
        // on the surface - and in the draw - with no hand on it.
        PointerAction.RELEASE ->
            contacts.filter { !it.pressed }.forEach {
                engine.onUp(it.id, now)
                onLifted()
            }
    }
}

/**
 * How strongly the edge glow shows: it grows with the countdown, dims to a
 * trace once a result is up, and is gone while the surface is bare.
 * [progress] is 0..1 through the countdown.
 */
fun edgeGlowAlpha(phase: DrawPhase, progress: Float): Float = when (phase) {
    DrawPhase.COUNTING -> 0.1f + progress * 0.4f
    DrawPhase.REVEALING, DrawPhase.REVEALED -> 0.08f
    DrawPhase.IDLE -> 0f
}

/** The accent frame around the surface, which shows only while counting. */
fun frameAlpha(phase: DrawPhase, progress: Float): Float =
    if (phase == DrawPhase.COUNTING) 0.2f + progress * 0.8f else 0f
