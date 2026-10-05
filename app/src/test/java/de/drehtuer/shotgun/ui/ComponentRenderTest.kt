package de.drehtuer.shotgun.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import de.drehtuer.shotgun.ui.components.ModeMotif
import de.drehtuer.shotgun.ui.components.NotBuiltYet
import de.drehtuer.shotgun.ui.components.Stepper
import de.drehtuer.shotgun.ui.components.Rule
import de.drehtuer.shotgun.ui.components.ScreenHeader
import de.drehtuer.shotgun.ui.components.ShotgunWordmark
import de.drehtuer.shotgun.ui.navigation.DrawMode
import de.drehtuer.shotgun.ui.theme.ShotgunTheme
import de.drehtuer.shotgun.ui.theme.ThemePreference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule as JUnitRule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The shared chrome: the rules, the header and the identity mark.
 *
 * **This test lives one package up from what it tests, deliberately.** The app
 * has a `Rule()` composable, and inside `ui.components` that name also has to
 * serve as JUnit's `@Rule` - which does not fail with an error but crashes the
 * Kotlin backend outright ("Exception during IR lowering", an NPE in
 * `JvmAnnotationImplementationTransformer`, naming only the file). The import
 * alias below is the second half of the fix; either alone is enough, and both
 * together mean nobody has to rediscover it.
 */
@RunWith(RobolectricTestRunner::class)
class ComponentRenderTest {

    @get:JUnitRule
    val compose = createComposeRule()

    @Test
    fun `the screen header shows its title and fires its action`() {
        var acted = false
        compose.setContent {
            ShotgunTheme(ThemePreference.DARK) {
                ScreenHeader(title = "SETTINGS", actionLabel = "DONE", onAction = { acted = true })
            }
        }

        compose.onNodeWithText("SETTINGS").assertIsDisplayed()
        compose.onNodeWithText("DONE").performClick()
        compose.runOnIdle { assertTrue("DONE did not fire", acted) }
    }

    @Test
    fun `a rule and the wordmark render in the dark palette`() {
        compose.setContent {
            ShotgunTheme(ThemePreference.DARK) {
                Column {
                    Rule()
                    ShotgunWordmark()
                }
            }
        }

        compose.onNodeWithText("SHOTGUN!").assertIsDisplayed()
    }

    /** The mark is drawn rather than an asset, so both palettes are exercised. */
    @Test
    fun `a rule and the wordmark render in the light palette`() {
        compose.setContent {
            ShotgunTheme(ThemePreference.LIGHT) {
                Column {
                    Rule()
                    ShotgunWordmark()
                }
            }
        }

        compose.onNodeWithText("SHOTGUN!").assertIsDisplayed()
    }

    @Test
    fun `an unbuilt screen says so, and says what it is waiting on`() {
        compose.setContent {
            ShotgunTheme(ThemePreference.DARK) { NotBuiltYet(note = "Waiting on the colour list") }
        }

        compose.onNodeWithText("Waiting on the colour list").assertIsDisplayed()
    }

    // ---- the stepper --------------------------------------------------------

    /**
     * The minus is greyed at the floor rather than left looking live: a control
     * that does nothing when tapped is the bug this shipped with once already.
     */
    @Test
    fun `a stepper at its floor has no working decrement`() {
        var steps = 0
        compose.setContent {
            ShotgunTheme(ThemePreference.DARK) {
                Stepper(
                    label = "TEAMS",
                    value = "2",
                    onDecrement = null,
                    onIncrement = { steps++ },
                )
            }
        }

        compose.onNodeWithContentDescription("decrease TEAMS").performClick()
        compose.runOnIdle { assertEquals(0, steps) }

        compose.onNodeWithContentDescription("increase TEAMS").performClick()
        compose.runOnIdle { assertEquals(1, steps) }
    }

    @Test
    fun `a stepper away from its floor moves in both directions`() {
        var steps = 0
        compose.setContent {
            ShotgunTheme(ThemePreference.DARK) {
                Stepper(
                    label = "SECONDS",
                    value = "3.5s",
                    onDecrement = { steps-- },
                    onIncrement = { steps++ },
                    decrementLabel = "shorter countdown",
                    incrementLabel = "longer countdown",
                )
            }
        }

        compose.onNodeWithText("3.5s").assertIsDisplayed()
        compose.onNodeWithContentDescription("longer countdown").performClick()
        compose.onNodeWithContentDescription("shorter countdown").performClick()
        compose.onNodeWithContentDescription("shorter countdown").performClick()
        compose.runOnIdle { assertEquals(-1, steps) }
    }

    // ---- the mode motifs ----------------------------------------------------

    /** Each motif previews what its mode does to the fingers on the glass. */
    @Test
    fun `the order motif numbers every finger`() {
        compose.setContent {
            ShotgunTheme(ThemePreference.DARK) { ModeMotif(DrawMode.ORDER) }
        }

        listOf("1", "2", "3", "4").forEach {
            compose.onNodeWithText(it).assertIsDisplayed()
        }
    }

    @Test
    fun `the teams motif shows uneven teams on purpose`() {
        compose.setContent {
            ShotgunTheme(ThemePreference.DARK) { ModeMotif(DrawMode.TEAMS) }
        }

        // A, B, A, C - two in A, one each in B and C.
        compose.onAllNodesWithText("A").assertCountEquals(2)
        compose.onNodeWithText("B").assertIsDisplayed()
        compose.onNodeWithText("C").assertIsDisplayed()
    }

    @Test
    fun `the starter motif claims one finger and numbers none`() {
        compose.setContent {
            ShotgunTheme(ThemePreference.DARK) { ModeMotif(DrawMode.STARTER) }
        }

        assertEquals(0, compose.onAllNodesWithText("1").fetchSemanticsNodes().size)
    }
}
