package de.drehtuer.shotgun.ui.screens

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import de.drehtuer.shotgun.data.DrawPoint
import de.drehtuer.shotgun.data.DrawRecord
import de.drehtuer.shotgun.ui.navigation.DrawMode
import de.drehtuer.shotgun.ui.theme.PPColors
import de.drehtuer.shotgun.ui.theme.PPTheme
import de.drehtuer.shotgun.ui.theme.ShotgunTheme
import de.drehtuer.shotgun.ui.theme.ThemePreference
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs

/**
 * The fairness field as it reaches the screen.
 *
 * `HeatFieldTest` proves the maths. This proves the rest of the path the
 * screen's claim depends on: that the raster computed off the main thread
 * actually gets drawn, stretched over the whole panel, and **the right way
 * round** - a field transposed or flipped on its way to the canvas would put
 * heat where nobody has won and still look perfectly plausible.
 *
 * Native graphics, because Robolectric's legacy mode never runs a draw pass.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FairnessFieldPixelsTest {

    @get:Rule
    val compose = createComposeRule()

    private fun field(): PixelMap =
        compose.onNodeWithTag(TAG_FAIRNESS_FIELD).captureToImage().toPixelMap()

    private fun PixelMap.at(x: Float, y: Float) = this[(x * width).toInt(), (y * height).toInt()]

    private fun near(a: Color, b: Color, tolerance: Float) =
        abs(a.red - b.red) < tolerance &&
            abs(a.green - b.green) < tolerance &&
            abs(a.blue - b.blue) < tolerance

    @Test
    fun `winners clustered top left heat the top left and nowhere else`() {
        lateinit var colors: PPColors
        val cluster = List(30) { DrawPoint(x = 0.25f, y = 0.25f, won = true, assignment = null) }
        compose.setContent {
            ShotgunTheme(ThemePreference.DARK) {
                colors = PPTheme.colors
                ResultScreen(winners = cluster, latest = null, totalDraws = 30, onClose = {})
            }
        }
        val cold = colors.heatRamp.first().color
        val hot = colors.heatRamp.last().color

        // The raster is built on Dispatchers.Default, so it lands a little after
        // the first frame; until then the panel is plain surface.
        compose.waitUntil(timeoutMillis = 10_000) { !near(field().at(0.25f, 0.25f), cold, 0.03f) }
        val pixels = field()

        assertTrue("the cluster is not hot", near(pixels.at(0.25f, 0.25f), hot, 0.06f))
        // Mirrored in x, in y, and in both: each is far beyond the kernel.
        assertTrue("x is flipped", near(pixels.at(0.75f, 0.25f), cold, 0.02f))
        assertTrue("y is flipped", near(pixels.at(0.25f, 0.7f), cold, 0.02f))
        assertTrue("both are flipped", near(pixels.at(0.75f, 0.7f), cold, 0.02f))
    }

    /**
     * `assignment` is nullable in the database, so a stored record can carry a
     * finger with none. It still has to be plotted rather than dropped - in
     * teams it falls back to the first team, in order it goes unnumbered.
     */
    @Test
    fun `a last draw with unassigned fingers still plots them`() {
        val record = DrawRecord(
            mode = DrawMode.TEAMS,
            teamCount = 2,
            timestamp = 0L,
            points = listOf(
                DrawPoint(x = 0.3f, y = 0.3f, won = false, assignment = null),
                DrawPoint(x = 0.7f, y = 0.6f, won = false, assignment = 1),
            ),
        )
        compose.setContent {
            ShotgunTheme(ThemePreference.LIGHT) {
                ResultScreen(winners = emptyList(), latest = record, totalDraws = 1, onClose = {})
            }
        }

        compose.onNodeWithText("A").assertIsDisplayed()
        compose.onNodeWithText("B").assertIsDisplayed()
    }
}
