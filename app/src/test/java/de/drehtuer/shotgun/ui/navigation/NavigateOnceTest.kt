package de.drehtuer.shotgun.ui.navigation

import androidx.activity.ComponentActivity
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import de.drehtuer.shotgun.data.settings.Settings
import de.drehtuer.shotgun.ui.theme.ShotgunTheme
import de.drehtuer.shotgun.ui.theme.ThemePreference
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Forward navigation only from a screen that is actually resumed.
 *
 * Two taps landing before the first navigation has settled would otherwise
 * stack the same screen twice - and then one back press reveals a copy of the
 * screen you just left instead of the modes.
 */
@RunWith(RobolectricTestRunner::class)
class NavigateOnceTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var controller: NavHostController

    private fun host() {
        compose.setContent {
            val nav = rememberNavController()
            SideEffect { controller = nav }
            ShotgunTheme(ThemePreference.DARK) {
                ShotgunNavHost(
                    navController = nav,
                    onThemePreferenceChange = {},
                    onHapticsChange = {},
                    onDimChange = {},
                    onCountdownStep = {},
                    onRevealTimingChange = {},
                    teamCount = 3,
                    onTeamCountChange = {},
                    settings = Settings(),
                    onDrawComplete = { _, _, _ -> },
                    winners = emptyList(),
                    latestDraw = null,
                    drawCount = 0,
                    onExit = {},
                )
            }
        }
    }

    private fun routes() = controller.currentBackStack.value.mapNotNull { it.destination.route }

    @Test
    fun `a double tap on settings opens it once`() {
        host()
        val click = compose.onNodeWithText("SETTINGS").fetchSemanticsNode()
            .config[SemanticsActions.OnClick].action!!

        // Both taps inside one frame: the second arrives while home is leaving.
        compose.runOnUiThread {
            click()
            click()
        }
        compose.waitForIdle()

        assertEquals(1, routes().count { it == Destination.Settings.route })

        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
        compose.onNodeWithText("One finger wins the draw").assertIsDisplayed()
    }
}
