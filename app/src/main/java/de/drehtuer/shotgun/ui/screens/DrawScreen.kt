package de.drehtuer.shotgun.ui.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.drehtuer.shotgun.data.settings.RevealTiming
import de.drehtuer.shotgun.data.settings.Settings
import de.drehtuer.shotgun.draw.DrawEffect
import de.drehtuer.shotgun.draw.DrawEngine
import de.drehtuer.shotgun.draw.DrawOutcome
import de.drehtuer.shotgun.draw.RingRole
import de.drehtuer.shotgun.draw.Contact
import de.drehtuer.shotgun.draw.PointerAction
import de.drehtuer.shotgun.draw.edgeGlowAlpha
import de.drehtuer.shotgun.draw.frameAlpha
import de.drehtuer.shotgun.draw.labelFitsAbove
import de.drehtuer.shotgun.draw.routePointers
import de.drehtuer.shotgun.draw.labelOffsetY
import de.drehtuer.shotgun.draw.ringSpec
import de.drehtuer.shotgun.draw.DrawPhase
import de.drehtuer.shotgun.draw.Finger
import de.drehtuer.shotgun.ui.components.Rule
import de.drehtuer.shotgun.ui.navigation.DrawMode
import de.drehtuer.shotgun.ui.theme.PPTheme
import de.drehtuer.shotgun.ui.util.Haptics
import de.drehtuer.shotgun.ui.util.KeepScreenOn
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/** Gap between one finger's reveal and the next, in suspense mode. */
private const val REVEAL_STEP_MILLIS = 500L

/** How long a refusal stays on screen. */
private const val REFUSAL_MILLIS = 2_400L


/** Test tags. Multi-touch cannot be exercised any other way without hardware. */
const val TAG_FINGER_RING = "finger-ring"
const val TAG_REVEAL_BAR = "reveal-bar"
const val TAG_REFUSAL = "refusal"

/**
 * The draw surface. Everyone puts a finger down, the edge glow counts, and the
 * result lands on the fingers themselves.
 *
 * The rules live in [DrawEngine], deliberately free of Android, so they can be
 * tested. This composable is the part that cannot be: pointer tracking,
 * drawing, and the two haptic strengths.
 */
@Composable
fun DrawScreen(
    mode: DrawMode,
    teamCount: Int,
    settings: Settings,
    onBack: () -> Unit,
    onOpenResult: () -> Unit,
    onDrawComplete: (DrawOutcome, Float, Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Players hold still through the countdown, which produces no touch events
    // for Android's idle timer to see.
    KeepScreenOn()

    val engine = remember(mode, teamCount, settings.countdownMillis, settings.revealTiming) {
        DrawEngine(
            mode = mode,
            teamCount = teamCount,
            countdownMillis = settings.countdownMillis,
            instantReveal = settings.revealTiming == RevealTiming.INSTANT,
        )
    }
    DrawSurface(engine, settings.haptics, onBack, onOpenResult, onDrawComplete, modifier)
}

/**
 * The surface itself, around an [engine] it is handed rather than one it
 * builds. That is so a test can hand it an engine with fingers already down:
 * pointers cannot be injected on the JVM, but the countdown, the staged reveal
 * and the refusal timeout run on their own once the engine is in that state.
 */
@Composable
internal fun DrawSurface(
    engine: DrawEngine,
    haptics: Boolean,
    onBack: () -> Unit,
    onOpenResult: () -> Unit,
    onDrawComplete: (DrawOutcome, Float, Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val mode = engine.mode
    val teamCount = engine.teamCount

    // The engine is a plain object, so a counter is what tells Compose its
    // state moved. This one covers *positions* only.
    var revision by remember { mutableIntStateOf(0) }

    // Phase is tracked separately because the timers key off it. Keying them off
    // the revision counter instead is what broke the countdown and the reveal:
    // a hand resting on glass jitters constantly, every move bumped the counter,
    // and both timers were cancelled and restarted before they could finish -
    // so nothing resolved until the fingers came off.
    var phaseState by remember { mutableStateOf(engine.phase) }
    var progress by remember { mutableFloatStateOf(0f) }
    var refusal by remember { mutableStateOf<String?>(null) }
    var surface by remember { mutableStateOf(0f to 0f) }

    /** Publishes the engine's state to Compose after a mutation. */
    fun sync() {
        phaseState = engine.phase
        revision++
    }

    fun handle(effect: DrawEffect?) {
        when (effect) {
            is DrawEffect.FingerTick -> Haptics.tick(context, haptics)
            is DrawEffect.Refused -> refusal = effect.message
            is DrawEffect.Drawn -> {
                Haptics.result(context, haptics, effect.outcome.mode)
                onDrawComplete(effect.outcome, surface.first, surface.second)
            }
            null -> Unit
        }
        sync()
    }

    // Drives the countdown and the glow. Keyed on the phase, so a moving finger
    // does not restart it.
    LaunchedEffect(phaseState) {
        if (phaseState != DrawPhase.COUNTING) return@LaunchedEffect
        while (engine.phase == DrawPhase.COUNTING) {
            withFrameMillis {
                progress = engine.progress(System.currentTimeMillis())
                engine.tick(System.currentTimeMillis())?.let { handle(it) }
            }
        }
    }

    // Walks the reveal along, one finger per step. Keyed on the phase: hands are
    // still on the glass through this, and their jitter must not restart it.
    LaunchedEffect(phaseState) {
        if (phaseState != DrawPhase.REVEALING) return@LaunchedEffect
        while (engine.phase == DrawPhase.REVEALING) {
            delay(REVEAL_STEP_MILLIS)
            engine.revealNext()
            sync()
        }
    }

    LaunchedEffect(refusal) {
        if (refusal != null) {
            delay(REFUSAL_MILLIS)
            refusal = null
        }
    }

    @Suppress("UNUSED_EXPRESSION") revision // read so recomposition tracks it

    val phase = phaseState
    val fingers = engine.fingers
    val outcome = engine.outcome

    val glow = edgeGlowAlpha(phase, progress)
    val frame = frameAlpha(phase, progress)
    // Chrome hides while hands are on the glass, and comes back to let you
    // leave once they are off - including with a result still showing.
    val chrome by animateFloatAsState(if (fingers.isEmpty()) 1f else 0f, label = "chrome")

    Box(
        modifier
            .fillMaxSize()
            .background(PPTheme.colors.bg)
            .pointerInput(engine) {
                surface = size.width.toFloat() to size.height.toFloat()
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        val now = System.currentTimeMillis()
                        // Which changes count, and what each does to the
                        // engine, is decided in `routePointers` - pure, and
                        // unit-tested. This only maps Compose's types onto it.
                        val action = when (event.type) {
                            PointerEventType.Press -> PointerAction.PRESS
                            PointerEventType.Move -> PointerAction.MOVE
                            PointerEventType.Release -> PointerAction.RELEASE
                            else -> continue
                        }
                        routePointers(
                            action = action,
                            contacts = event.changes.map {
                                Contact(it.id.value, it.position.x, it.position.y, it.pressed, it.isConsumed)
                            },
                            engine = engine,
                            now = now,
                            onLanded = { handle(it) },
                            onMoved = { revision++ },
                            onLifted = { sync() },
                        )
                    }
                }
            },
    ) {
        EdgeGlow(glow)

        if (frame > 0f) {
            Box(
                Modifier
                    .fillMaxSize()
                    .border(PPTheme.dimens.rule, PPTheme.colors.accent.copy(alpha = frame)),
            )
        }

        // Once revealed, the rings are drawn from the recorded outcome rather
        // than from live pointers: you have to lift your hand to see what is
        // under it, and that must not erase the answer.
        val settled = phase == DrawPhase.REVEALED || phase == DrawPhase.REVEALING
        val shown = if (settled && outcome != null) outcome.fingers else fingers
        // A ring outlives the finger that drew it, so some of these no longer
        // have a hand on them. Those are the ones that can hold their label
        // inside the ring - see FingerRing.
        val stillDown = fingers.mapTo(mutableSetOf()) { it.id }
        shown.forEach { finger ->
            FingerRing(
                finger,
                outcome,
                phase,
                revealed = engine.isRevealed(finger.id),
                lifted = settled && finger.id !in stillDown,
            )
        }

        // Chrome: fades out the moment the first finger lands.
        Column(Modifier.alpha(chrome)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = "← MODES",
                    style = PPTheme.typography.micro,
                    color = PPTheme.colors.dim,
                    modifier = Modifier
                        .clickable(enabled = fingers.isEmpty(), onClick = onBack)
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                )
                Text(
                    text = mode.label(teamCount),
                    style = PPTheme.typography.micro,
                    color = PPTheme.colors.accent,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                )
            }
            Rule()
        }

        if (fingers.size < DrawEngine.MIN_PLAYERS && phase == DrawPhase.IDLE) {
            Hint(fingers.size, Modifier.align(Alignment.CenterStart))
        }

        refusal?.let { message ->
            Refusal(message, Modifier.align(Alignment.BottomCenter).testTag(TAG_REFUSAL))
        }

        if (phase == DrawPhase.REVEALED) {
            RevealBar(onOpenResult, Modifier.align(Alignment.BottomCenter).testTag(TAG_REVEAL_BAR))
        }
    }
}

/**
 * The surface's own pieces are `internal` rather than private for one reason:
 * **multi-touch cannot be injected on the JVM**, so a test can never drive the
 * surface into the states that draw them. Rendering each piece directly is the
 * only way they are covered anywhere but the phone. Nothing outside this module
 * calls them.
 */

/**
 * The accent glow that grows inward from every edge as the countdown runs.
 *
 * Drawn as one blurred stroke around the border rather than four edge
 * gradients. Four gradients overlap at the corners and make them visibly
 * brighter than the sides, which reads as a rendering fault rather than a
 * countdown - it was the first thing noticed on a real screen.
 */
@Composable
internal fun EdgeGlow(alpha: Float) {
    if (alpha <= 0f) return
    val accent = PPTheme.colors.accent
    Canvas(Modifier.fillMaxSize()) {
        val depth = 56.dp.toPx()
        val paint = android.graphics.Paint().apply {
            isAntiAlias = true
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = depth
            color = accent.copy(alpha = alpha).toArgb()
            maskFilter = android.graphics.BlurMaskFilter(
                depth / 2f,
                android.graphics.BlurMaskFilter.Blur.NORMAL,
            )
        }
        drawIntoCanvas { canvas ->
            canvas.save()
            // Clip so only the inward half of the stroke shows: the glow sits
            // inside the screen, as an inset shadow does.
            canvas.clipRect(0f, 0f, size.width, size.height)
            canvas.nativeCanvas.drawRect(0f, 0f, size.width, size.height, paint)
            canvas.restore()
        }
    }
}

/**
 * One finger. Idle it is a bare ring; after the draw it carries whatever the
 * mode gives it - a bloom for the winner, a rank, or a team letter.
 *
 * Two things here exist because of how a hand actually sits on glass:
 *
 * - **The label sits beside the ring while the finger is down, and slides into
 *   it once the finger lifts.** A fingertip lands on the ring's centre, so a
 *   centred label would be under the very finger it belongs to; it is drawn
 *   above instead, flipping below near the top edge where there is no room.
 *   Beside is only a guess at free glass, though - the app knows a contact
 *   point, not where the hand is - so once the hand comes off, the label moves
 *   to the ring's centre, which is on screen by construction and covered by
 *   nothing.
 * - **The ring thickens on reveal.** The colour is the answer in teams mode,
 *   and a 4dp band around a fingertip is not enough of it to read at a glance.
 */
@Composable
internal fun FingerRing(
    finger: Finger,
    outcome: DrawOutcome?,
    phase: DrawPhase,
    revealed: Boolean,
    lifted: Boolean,
) {
    val colors = PPTheme.colors
    val diameter = PPTheme.dimens.ringDiameter

    // What the ring should look like is decided in `ringSpec`, which is pure
    // and unit-tested; everything below only turns roles into colours.
    val spec = ringSpec(finger.id, outcome, phase, revealed)
    val team = spec.teamIndex?.let { colors.teamFills[it % colors.teamFills.size] }

    val ring = when (spec.role) {
        RingRole.IDLE -> colors.ink
        RingRole.PENDING, RingRole.WINNER, RingRole.RANKED -> colors.accent
        RingRole.LOSER -> colors.line
        RingRole.TEAM -> team?.bg ?: colors.ink
    }
    val labelColor = when (spec.role) {
        RingRole.TEAM -> team?.bg ?: colors.ink
        else -> colors.accent
    }
    val fill = when {
        !spec.filled -> Color.Transparent
        spec.role == RingRole.TEAM -> (team?.bg ?: colors.ink).copy(alpha = 0.25f)
        else -> colors.accentSoft
    }

    val alpha = spec.alpha
    val scale = spec.scale
    val label = spec.label
    val sub = spec.sub
    val labelSize = spec.labelSp.sp

    val density = LocalDensity.current
    val radiusPx = with(density) { diameter.toPx() } / 2f
    val strokeWidth = if (spec.answered) 10.dp else 4.dp

    Box(
        modifier = Modifier
            .offset {
                IntOffset(
                    (finger.x - radiusPx * scale).roundToInt(),
                    (finger.y - radiusPx * scale).roundToInt(),
                )
            }
            .size(diameter * scale)
            .testTag(TAG_FINGER_RING)
            .alpha(alpha)
            .background(fill, CircleShape)
            .border(strokeWidth, ring, CircleShape),
    )

    // Beside the ring while a hand is on the glass - above it, or below when
    // the finger is too near the top edge for that - and inside the ring once
    // the finger lifts, where nothing covers it and it cannot fall off screen.
    if (label != null) {
        val labelHeight = 64.dp
        val gap = 10.dp
        val above = labelFitsAbove(
            fingerY = finger.y,
            radiusPx = radiusPx,
            labelHeightPx = with(density) { labelHeight.toPx() },
            gapPx = with(density) { gap.toPx() },
        )
        val labelWidth = diameter
        val slide by animateFloatAsState(
            targetValue = if (lifted) 1f else 0f,
            animationSpec = tween(durationMillis = 240),
            label = "labelSlide",
        )
        Column(
            modifier = Modifier
                .offset {
                    IntOffset(
                        (finger.x - labelWidth.toPx() / 2f).roundToInt(),
                        (
                            finger.y + labelOffsetY(
                                radiusPx = radiusPx,
                                scale = scale,
                                labelHeightPx = labelHeight.toPx(),
                                gapPx = gap.toPx(),
                                above = above,
                                slide = slide,
                            )
                            ).roundToInt(),
                    )
                }
                .size(width = labelWidth, height = labelHeight)
                .alpha(alpha),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = label,
                color = labelColor,
                fontSize = labelSize,
                fontWeight = FontWeight.ExtraBold,
            )
            sub?.let {
                Text(
                    text = it,
                    color = labelColor,
                    style = PPTheme.typography.microWide,
                )
            }
        }
    }
}

@Composable
internal fun Hint(fingerCount: Int, modifier: Modifier = Modifier) {
    val (title, subtitle) = if (fingerCount == 0) {
        "EVERYONE, ONE FINGER DOWN." to
            "The edge glow is the countdown, and it restarts whenever someone joins or leaves. Drag to reposition; a moving finger is not a new player."
    } else {
        "ONE MORE FINGER." to "At least two players are needed to draw."
    }
    Column(modifier.padding(horizontal = 24.dp)) {
        Text(title, style = PPTheme.typography.hintTitle, color = PPTheme.colors.ink)
        Text(
            text = subtitle,
            style = PPTheme.typography.body,
            color = PPTheme.colors.dim,
            modifier = Modifier
                .padding(top = 10.dp)
                .widthIn(max = 300.dp),
        )
    }
}

/** The accent bar that says why a draw could not run. */
@Composable
internal fun Refusal(message: String, modifier: Modifier = Modifier) {
    Column(
        modifier
            .fillMaxWidth()
            .background(PPTheme.colors.accent)
            .padding(16.dp),
    ) {
        Text(
            text = "CAN'T DRAW",
            style = PPTheme.typography.microWide,
            color = PPTheme.colors.accentInk.copy(alpha = 0.75f),
        )
        Text(
            text = message,
            style = PPTheme.typography.sectionTitle,
            color = PPTheme.colors.accentInk,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
internal fun RevealBar(onOpenResult: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth()) {
        Rule()
        Row(
            Modifier
                .fillMaxWidth()
                .background(PPTheme.colors.bg),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Spacer(Modifier.weight(1f))
            Text(
                text = "DETAILS",
                style = PPTheme.typography.body.copy(fontWeight = FontWeight.ExtraBold),
                color = PPTheme.colors.accentInk,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .background(PPTheme.colors.accent)
                    .clickable(onClick = onOpenResult)
                    .padding(horizontal = 20.dp, vertical = 16.dp),
            )
        }
    }
}

/** The label shown top-right while the surface is still bare. */
internal fun DrawMode.label(teamCount: Int): String = when (this) {
    DrawMode.STARTER -> "STARTING PLAYER"
    DrawMode.ORDER -> "PLAYER ORDER"
    DrawMode.TEAMS -> "$teamCount TEAMS"
}
