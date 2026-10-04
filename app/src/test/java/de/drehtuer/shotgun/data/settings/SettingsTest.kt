package de.drehtuer.shotgun.data.settings

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import de.drehtuer.shotgun.ui.theme.ThemePreference

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Stored settings are read defensively. A value written by a newer build, or a
 * corrupted one, must not stop the app launching - these pin that down.
 */
class SettingsTest {

    @Test
    fun `an empty store yields the defaults`() {
        val s = preferencesOf().toSettings()
        assertEquals(ThemePreference.SYSTEM, s.themePreference)
        assertEquals(true, s.haptics)
        assertEquals(true, s.dim)
        assertEquals(3_500, s.countdownMillis)
        assertEquals(RevealTiming.INSTANT, s.revealTiming)
    }

    /** The countdown moved from whole seconds to millis when it gained halves. */
    @Test
    fun `a countdown stored under the old seconds key still applies`() {
        val s = preferencesOf(intPreferencesKey("countdown_seconds") to 7).toSettings()
        assertEquals(7_000, s.countdownMillis)
    }

    @Test
    fun `the millis key wins over the old seconds key`() {
        val s = preferencesOf(
            intPreferencesKey("countdown_seconds") to 7,
            intPreferencesKey("countdown_millis") to 2_500,
        ).toSettings()
        assertEquals(2_500, s.countdownMillis)
    }

    @Test
    fun `whole seconds lose the decimal, halves keep it`() {
        assertEquals("2s", formatCountdown(2_000))
        assertEquals("2.5s", formatCountdown(2_500))
        assertEquals("0.5s", formatCountdown(500))
        assertEquals("10s", formatCountdown(10_000))
    }

    @Test
    fun `stored values are read back`() {
        val s = preferencesOf(
            stringPreferencesKey("theme_preference") to ThemePreference.DARK.name,
            stringPreferencesKey("reveal_timing") to RevealTiming.SUSPENSE.name,
            intPreferencesKey("countdown_millis") to 3_500,
        ).toSettings()
        assertEquals(ThemePreference.DARK, s.themePreference)
        assertEquals(RevealTiming.SUSPENSE, s.revealTiming)
        assertEquals(3_500, s.countdownMillis)
    }

    /** A theme written by a newer build must fall back, not crash on launch. */
    @Test
    fun `an unknown enum value falls back to the default`() {
        val s = preferencesOf(
            stringPreferencesKey("theme_preference") to "MIDNIGHT",
            stringPreferencesKey("reveal_timing") to "DRAMATIC",
        ).toSettings()
        assertEquals(ThemePreference.SYSTEM, s.themePreference)
        assertEquals(RevealTiming.INSTANT, s.revealTiming)
    }

    @Test
    fun `countdown is clamped to the floor`() {
        assertEquals(500, preferencesOf(intPreferencesKey("countdown_millis") to 0).toSettings().countdownMillis)
        assertEquals(500, preferencesOf(intPreferencesKey("countdown_millis") to -5).toSettings().countdownMillis)
    }

    // ---- defensive reads: a value present but unusable ----------------------

    /** Absent falls back to the default; stored `false` must not look absent. */
    @Test
    fun `haptics and dim switched off are read back as off`() {
        val s = preferencesOf(
            booleanPreferencesKey("haptics") to false,
            booleanPreferencesKey("dim") to false,
        ).toSettings()
        assertEquals(false, s.haptics)
        assertEquals(false, s.dim)
    }

    // ---- stepping, including the migration ---------------------------------

    @Test
    fun `stepping with nothing stored starts from the default`() {
        assertEquals(
            Settings.DEFAULT_COUNTDOWN_MILLIS + Settings.COUNTDOWN_STEP_MILLIS,
            steppedCountdown(stored = null, legacySeconds = null, steps = 1),
        )
    }

    /**
     * The migration path: someone upgrading has only the old whole-seconds key,
     * and their setting has to survive the first step rather than silently
     * reverting to the default.
     */
    @Test
    fun `stepping migrates the old seconds key rather than losing it`() {
        assertEquals(
            4_500,
            steppedCountdown(stored = null, legacySeconds = 4, steps = 1),
        )
    }

    @Test
    fun `stepping prefers the millis key over the old seconds key`() {
        assertEquals(
            2_000,
            steppedCountdown(stored = 2_500, legacySeconds = 9, steps = -1),
        )
    }

    @Test
    fun `stepping down cannot go below the floor`() {
        assertEquals(
            Settings.MIN_COUNTDOWN_MILLIS,
            steppedCountdown(stored = Settings.MIN_COUNTDOWN_MILLIS, legacySeconds = null, steps = -4),
        )
    }
}
