package de.drehtuer.shotgun.ui.navigation

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import de.drehtuer.shotgun.data.settings.Settings
import de.drehtuer.shotgun.ui.theme.ShotgunTheme
import de.drehtuer.shotgun.ui.theme.ThemePreference
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The navigation graph, walked on the JVM.
 *
 * `NavigationStateTest` in `androidTest/` covers the same graph on a device;
 * this covers that the routes build and the screens behind them compose at all,
 * which is what a CI runner can check.
 */
@RunWith(RobolectricTestRunner::class)
class NavHostRenderTest {

    @get:Rule
    val compose = createComposeRule()

    private fun host(settings: Settings = Settings(), onExit: () -> Unit = {}) {
        compose.setContent {
            ShotgunTheme(ThemePreference.DARK) {
                ShotgunNavHost(
                    onThemePreferenceChange = {},
                    onHapticsChange = {},
                    onDimChange = {},
                    onCountdownStep = {},
                    onRevealTimingChange = {},
                    teamCount = 2,
                    onTeamCountChange = {},
                    settings = settings,
                    onDrawComplete = { _, _, _ -> },
                    winners = emptyList(),
                    latestDraw = null,
                    drawCount = 0,
                    onExit = onExit,
                )
            }
        }
    }

    @Test
    fun `settings is reachable from home and closes back to it`() {
        host()
        compose.onNodeWithText("SETTINGS").performClick()
        compose.onNodeWithText("HAPTICS").assertIsDisplayed()

        compose.onNodeWithText("DONE").performScrollTo().performClick()
        compose.onNodeWithText("One finger wins the draw").assertIsDisplayed()
    }

    @Test
    fun `leaving the draw surface returns to the modes`() {
        host()
        compose.onNodeWithText("Every finger gets a number").performClick()
        compose.onNodeWithText("← MODES").performClick()
        compose.onNodeWithText("One finger wins the draw").assertIsDisplayed()
    }
}
