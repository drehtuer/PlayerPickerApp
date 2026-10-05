package de.drehtuer.shotgun.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import de.drehtuer.shotgun.draw.DrawEngine
import de.drehtuer.shotgun.draw.DrawOutcome
import de.drehtuer.shotgun.draw.DrawPhase
import de.drehtuer.shotgun.ui.navigation.DrawMode
import de.drehtuer.shotgun.ui.theme.ShotgunTheme
import de.drehtuer.shotgun.ui.theme.ThemePreference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import kotlin.random.Random

/**
 * The draw surface running its own timers.
 *
 * Fingers cannot be injected here (see `build-environment.md`), so each test
 * hands [DrawSurface] an engine whose fingers are already down, armed with a
 * countdown that is due at once. From there the surface does the rest by
 * itself - the countdown's frame loop fires the draw, the reveal steps along,
 * a refusal shows and times out - on the compose test clock.
 */
@RunWith(RobolectricTestRunner::class)
class DrawSurfaceTest {

    @get:Rule
    val compose = createComposeRule()

    /**
     * Fingers down at time 0 with a 1ms countdown: due before any clock this
     * runs under, so the first frame draws.
     */
    private fun armed(mode: DrawMode, players: Int, teamCount: Int = 2, instant: Boolean = false) =
        DrawEngine(mode, teamCount, countdownMillis = 1, instantReveal = instant, random = Random(7)).apply {
            repeat(players) { onDown(it + 1L, 80f + it * 60f, 220f, now = 0) }
        }

    private val drawn = mutableListOf<DrawOutcome>()

    private fun show(engine: DrawEngine) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            ShotgunTheme(ThemePreference.DARK) {
                DrawSurface(
                    engine = engine,
                    haptics = false,
                    onBack = {},
                    onOpenResult = {},
                    onDrawComplete = { outcome, _, _ -> drawn += outcome },
                )
            }
        }
    }

    private fun ranksShown() = listOf("1", "2", "3").filter {
        compose.onAllNodesWithText(it).fetchSemanticsNodes().isNotEmpty()
    }

    @Test
    fun `the countdown fires the draw by itself and the result stays up`() {
        val engine = armed(DrawMode.STARTER, players = 2)
        show(engine)
        compose.mainClock.advanceTimeBy(100)

        assertEquals(1, drawn.size)
        assertEquals(DrawMode.STARTER, drawn.single().mode)
        assertEquals(DrawPhase.REVEALED, engine.phase)
        compose.onNodeWithTag(TAG_REVEAL_BAR).assertIsDisplayed()
    }

    /**
     * Suspense mode: the first rank shows with the draw, the rest one step
     * apart. Hands are still on the glass throughout, which is why the stepper
     * is keyed on the phase.
     */
    @Test
    fun `a staged reveal shows one more rank per step`() {
        val engine = armed(DrawMode.ORDER, players = 3)
        show(engine)
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeByFrame()

        assertEquals(DrawPhase.REVEALING, engine.phase)
        assertEquals(listOf("1"), ranksShown())
        assertEquals(0, compose.onAllNodesWithTag(TAG_REVEAL_BAR).fetchSemanticsNodes().size)

        compose.mainClock.advanceTimeBy(520)
        assertEquals(listOf("1", "2"), ranksShown())

        compose.mainClock.advanceTimeBy(520)
        assertEquals(listOf("1", "2", "3"), ranksShown())
        assertEquals(DrawPhase.REVEALED, engine.phase)
        compose.onNodeWithTag(TAG_REVEAL_BAR).assertIsDisplayed()
        assertEquals(1, drawn.size)
    }

    @Test
    fun `an instant reveal shows every rank with the draw`() {
        val engine = armed(DrawMode.ORDER, players = 3, instant = true)
        show(engine)
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeByFrame()

        assertEquals(listOf("1", "2", "3"), ranksShown())
    }

    /** Too few fingers for the teams: say so, record nothing, and clear the bar after a while. */
    @Test
    fun `a refused draw shows why, records nothing and clears itself`() {
        val engine = armed(DrawMode.TEAMS, players = 2, teamCount = 3)
        show(engine)
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeByFrame()

        compose.onNodeWithTag(TAG_REFUSAL).assertIsDisplayed()
        compose.onNodeWithText("2 fingers can't fill 3 teams").assertIsDisplayed()
        assertEquals(emptyList<DrawOutcome>(), drawn)
        assertEquals(DrawPhase.IDLE, engine.phase)

        compose.mainClock.advanceTimeBy(2_500)
        assertEquals(0, compose.onAllNodesWithTag(TAG_REFUSAL).fetchSemanticsNodes().size)
    }

    /**
     * The glow is drawn, not composed, so only pixels can show it - which
     * needs Robolectric's native graphics. It has to sit at the edges and
     * leave the middle of the surface, where the rings are, untouched.
     */
    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `the edge glow lights the edges and leaves the middle clear`() {
        compose.setContent {
            ShotgunTheme(ThemePreference.DARK) {
                Box(Modifier.fillMaxSize().testTag("host")) { EdgeGlow(alpha = 0.5f) }
            }
        }

        val bitmap = compose.onNodeWithTag("host").captureToImage().asAndroidBitmap()
        val middle = bitmap.getPixel(bitmap.width / 2, bitmap.height / 2)
        val edge = bitmap.getPixel(1, bitmap.height / 2)

        val corner = bitmap.getPixel(bitmap.width / 2, 1)
        val inner = bitmap.getPixel(bitmap.width / 2, bitmap.height / 3)

        // Whatever the window behind it is painted, the middle shows only that.
        assertEquals("the glow reached the middle", middle, inner)
        assertNotEquals("no glow at the side", middle, edge)
        assertNotEquals("no glow at the top", middle, corner)
    }
}
