package de.drehtuer.shotgun.ui.theme

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.view.WindowCompat
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The theme setting against the system's.
 *
 * The app's palette can be chosen independently of the system's dark mode, so
 * two things have to follow the *app*, not `uiMode`: the palette itself, and
 * the system bar icons - which otherwise end up dark on a dark ground, or
 * light on a light one. SYSTEM is the one choice that defers to the phone.
 */
@RunWith(RobolectricTestRunner::class)
class SystemBarsTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    /** Renders the theme and reports (palette is dark, bar icons are light). */
    private fun render(preference: ThemePreference): Pair<Boolean, Boolean> {
        var dark: Boolean? = null
        compose.setContent { ShotgunTheme(preference) { dark = PPTheme.colors.isDark } }
        return compose.runOnIdle {
            val window = compose.activity.window
            val bars = WindowCompat.getInsetsController(window, window.decorView)
            assertEquals(
                "status and navigation bars disagree",
                bars.isAppearanceLightStatusBars,
                bars.isAppearanceLightNavigationBars,
            )
            dark!! to bars.isAppearanceLightStatusBars
        }
    }

    @Test
    @Config(qualifiers = "notnight")
    fun `dark on a light phone is a dark palette with light bar icons`() {
        assertEquals(true to false, render(ThemePreference.DARK))
    }

    @Test
    @Config(qualifiers = "night")
    fun `light on a dark phone is a light palette with dark bar icons`() {
        assertEquals(false to true, render(ThemePreference.LIGHT))
    }

    @Test
    @Config(qualifiers = "night")
    fun `system follows a dark phone`() {
        assertEquals(true to false, render(ThemePreference.SYSTEM))
    }

    @Test
    @Config(qualifiers = "notnight")
    fun `system follows a light phone`() {
        assertEquals(false to true, render(ThemePreference.SYSTEM))
    }
}
