package de.drehtuer.shotgun.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import de.drehtuer.shotgun.ui.components.ShotgunMark
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
 * The mark as drawn, read back pixel by pixel.
 *
 * The identity spec's mark is four fingers on the glass with **one** called:
 * one solid dot in accent, three hollow rings in dim. It is drawn on a canvas
 * rather than shipped as an asset, so only the pixels can show that the right
 * dot is the claimed one and the others are rings rather than discs.
 *
 * Native graphics, because Robolectric's legacy mode never runs a draw pass.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MarkPixelsTest {

    @get:Rule
    val compose = createComposeRule()

    /** Dot centres from `design/Shotgun Logo.dc.html`, the claimed one first. */
    private val claimed = 0.19f to 0.44f
    private val missed = listOf(0.44f to 0.19f, 0.79f to 0.33f, 0.56f to 0.74f)

    @Test
    fun `the dark mark claims exactly one finger and rings the other three`() =
        assertMark(ThemePreference.DARK)

    @Test
    fun `the light mark claims exactly one finger and rings the other three`() =
        assertMark(ThemePreference.LIGHT)

    private fun assertMark(preference: ThemePreference) {
        lateinit var colors: PPColors
        compose.setContent {
            ShotgunTheme(preference) {
                colors = PPTheme.colors
                Box(Modifier.background(PPTheme.colors.bg)) {
                    ShotgunMark(Modifier.testTag("mark"), size = 200.dp)
                }
            }
        }

        val pixels = compose.onNodeWithTag("mark").captureToImage().toPixelMap()
        val side = pixels.width
        fun at(x: Float, y: Float) = pixels[(x * side).toInt(), (y * side).toInt()]

        assertNear("claimed dot", colors.accent, at(claimed.first, claimed.second))

        // A ring of the smallest unclaimed dot's geometry: the stroke is centred
        // this far out from the dot's centre, as a fraction of the side.
        val ringOffset = 0.25f * 0.84f / 2f - 0.0365f
        missed.forEach { (x, y) ->
            assertNear("hollow centre at $x,$y", colors.bg, at(x, y))
            assertNear("ring at $x,$y", colors.dim, at(x + ringOffset, y))
        }
    }

    private fun assertNear(what: String, expected: Color, actual: Color) {
        val close = abs(expected.red - actual.red) < 0.03f &&
            abs(expected.green - actual.green) < 0.03f &&
            abs(expected.blue - actual.blue) < 0.03f
        assertTrue("$what: expected $expected, was $actual", close)
    }
}
