package de.drehtuer.shotgun.data.settings

import de.drehtuer.shotgun.ui.theme.ThemePreference
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The settings, against a real DataStore.
 *
 * `SettingsTest` covers the mapping - what a stored preference means - without
 * ever writing one. This covers the half that only a store can answer: that a
 * write lands, that it is read back as itself, and that the countdown's
 * read-modify-write really is one transaction.
 *
 * Every test writes what it needs before reading it. That used to be a
 * requirement rather than a habit - the process-wide store outlived the test
 * method and carried its values into the next one. Each test now gets its own
 * store, so the habit is only good manners.
 */
@RunWith(RobolectricTestRunner::class)
class SettingsRepositoryTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var store: IsolatedSettingsStore
    private lateinit var repository: SettingsRepository

    @Before
    fun setUp() {
        store = IsolatedSettingsStore(UnconfinedTestDispatcher(), tempFolder.newFolder())
        repository = store.repository()
    }

    @After
    fun tearDown() = store.close()

    @Test
    fun `every setting is read back as it was written`() = runTest {
        repository.setThemePreference(ThemePreference.DARK)
        repository.setHaptics(false)
        repository.setDim(false)
        repository.setCountdownMillis(5_000)
        repository.setRevealTiming(RevealTiming.SUSPENSE)

        val settings = repository.settings.first()
        assertEquals(ThemePreference.DARK, settings.themePreference)
        assertEquals(false, settings.haptics)
        assertEquals(false, settings.dim)
        assertEquals(5_000, settings.countdownMillis)
        assertEquals(RevealTiming.SUSPENSE, settings.revealTiming)
    }

    @Test
    fun `a setting switched back on is stored, not just cleared`() = runTest {
        repository.setHaptics(false)
        repository.setHaptics(true)
        repository.setDim(false)

        val settings = repository.settings.first()
        assertEquals(true, settings.haptics)
        assertEquals(false, settings.dim)
    }

    @Test
    fun `stepping moves the countdown by half seconds`() = runTest {
        repository.setCountdownMillis(2_000)
        repository.stepCountdown(1)

        assertEquals(2_500, repository.settings.first().countdownMillis)

        repository.stepCountdown(-2)
        assertEquals(1_500, repository.settings.first().countdownMillis)
    }

    /**
     * The bug this guards: writing "current + one step" read the value
     * asynchronously, so two quick taps both read the old number and the second
     * was silently lost. Stepping inside the edit block is what fixes it, and
     * consecutive steps landing is the observable difference.
     */
    @Test
    fun `steps in quick succession all land`() = runTest {
        repository.setCountdownMillis(1_000)
        repeat(4) { repository.stepCountdown(1) }

        assertEquals(3_000, repository.settings.first().countdownMillis)
    }

    @Test
    fun `stepping down stops at the floor rather than going negative`() = runTest {
        repository.setCountdownMillis(1_000)
        repository.stepCountdown(-10)

        assertEquals(Settings.MIN_COUNTDOWN_MILLIS, repository.settings.first().countdownMillis)
    }
}
