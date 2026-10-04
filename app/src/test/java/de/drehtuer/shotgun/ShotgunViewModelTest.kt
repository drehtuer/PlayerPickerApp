package de.drehtuer.shotgun

import de.drehtuer.shotgun.data.DrawHistory
import de.drehtuer.shotgun.data.DrawPoint
import de.drehtuer.shotgun.data.DrawRecord
import de.drehtuer.shotgun.data.settings.IsolatedSettingsStore
import de.drehtuer.shotgun.data.settings.RevealTiming
import de.drehtuer.shotgun.data.settings.SettingsRepository
import de.drehtuer.shotgun.draw.DrawOutcome
import de.drehtuer.shotgun.draw.Finger
import de.drehtuer.shotgun.ui.navigation.DrawMode
import de.drehtuer.shotgun.ui.theme.ThemePreference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The view model, with a fake history and a real settings store.
 *
 * The part worth testing here is [ShotgunViewModel.recordDraw]: it is the only
 * place that decides **what a draw meant** - who counts as having won in each
 * mode, and where the fingers were as a fraction of the surface. Both are
 * written to the database and can never be recomputed afterwards, so getting
 * either wrong corrupts the fairness field silently and permanently.
 */
@RunWith(RobolectricTestRunner::class)
class ShotgunViewModelTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var history: FakeHistory
    private lateinit var store: IsolatedSettingsStore
    private lateinit var repository: SettingsRepository
    private lateinit var viewModel: ShotgunViewModel

    @Before
    fun setUp() {
        // One scheduler for everything: `runTest` adopts the scheduler behind
        // `Dispatchers.Main`, and the settings store is given the same one, so
        // a write and the read waiting for it share a clock. The store the app
        // uses would run on `Dispatchers.IO` instead - real threads under a
        // virtual clock, which is what hung this class in CI once.
        val dispatcher = UnconfinedTestDispatcher(TestCoroutineScheduler())
        Dispatchers.setMain(dispatcher)
        history = FakeHistory()
        store = IsolatedSettingsStore(dispatcher, tempFolder.newFolder())
        repository = store.repository()
        viewModel = ShotgunViewModel(repository, history)
    }

    @After
    fun tearDown() {
        store.close()
        Dispatchers.resetMain()
    }

    // ---- what a draw meant ---------------------------------------------------

    @Test
    fun `positions are stored as a fraction of the surface they were drawn on`() = runTest {
        viewModel.recordDraw(starter(), surfaceWidth = 1_000f, surfaceHeight = 2_000f)

        val points = history.recorded.single().points
        assertEquals(listOf(0.25f, 0.75f), points.map { it.x })
        assertEquals(listOf(0.1f, 0.5f), points.map { it.y })
    }

    @Test
    fun `in starter mode only the winning finger won`() = runTest {
        viewModel.recordDraw(starter(), 1_000f, 2_000f)

        val points = history.recorded.single().points
        assertEquals(listOf(true, false), points.map { it.won })
    }

    @Test
    fun `in order mode rank one won, whatever order the fingers landed in`() = runTest {
        viewModel.recordDraw(
            outcome(
                mode = DrawMode.ORDER,
                fingers = listOf(Finger(1L, 250f, 200f), Finger(2L, 750f, 1_000f)),
                // The second finger to land drew rank 1.
                assignment = mapOf(1L to 2, 2L to 1),
                winnerId = 2L,
            ),
            1_000f,
            2_000f,
        )

        val points = history.recorded.single().points
        assertEquals(listOf(false, true), points.map { it.won })
        assertEquals(listOf(2, 1), points.map { it.assignment })
    }

    /** Teams has no single winner, so nothing is plotted on the fairness field. */
    @Test
    fun `in teams mode nobody won, and the team count is kept`() = runTest {
        viewModel.recordDraw(
            outcome(
                mode = DrawMode.TEAMS,
                teamCount = 2,
                fingers = listOf(Finger(1L, 250f, 200f), Finger(2L, 750f, 1_000f)),
                assignment = mapOf(1L to 0, 2L to 1),
            ),
            1_000f,
            2_000f,
        )

        val record = history.recorded.single()
        assertEquals(DrawMode.TEAMS, record.mode)
        assertEquals(2, record.teamCount)
        assertTrue(record.points.none { it.won })
    }

    @Test
    fun `a draw is stamped with a plausible time`() = runTest {
        val before = System.currentTimeMillis()
        viewModel.recordDraw(starter(), 1_000f, 2_000f)

        assertTrue(history.recorded.single().timestamp >= before)
    }

    // ---- state the screens read ---------------------------------------------

    @Test
    fun `the team count is floored at two and is not persisted`() = runTest {
        viewModel.setTeamCount(5)
        assertEquals(5, viewModel.teamCount.value)

        viewModel.setTeamCount(0)
        assertEquals(2, viewModel.teamCount.value)
    }

    /**
     * Awaited rather than read: these flows are shared with
     * `WhileSubscribed`, and the settings arrive from a real DataStore on its
     * own threads, so `value` immediately after a write is a race. `first`
     * subscribes and waits, which is also what a screen does.
     */
    @Test
    fun `the history's flows are exposed to the screens`() = runTest {
        val point = DrawPoint(x = 0.5f, y = 0.5f, won = true, assignment = null)
        val record = DrawRecord(DrawMode.STARTER, null, listOf(point), 1L)
        history.winnersFlow.value = listOf(point)
        history.latestFlow.value = record
        history.countFlow.value = 7

        assertEquals(listOf(point), viewModel.winners.first { it.isNotEmpty() })
        assertEquals(record, viewModel.latestDraw.first { it != null })
        assertEquals(7, viewModel.drawCount.first { it > 0 })
    }

    @Test
    fun `settings written through the view model are read back through it`() = runTest {
        viewModel.setThemePreference(ThemePreference.LIGHT)
        viewModel.setHaptics(false)
        viewModel.setDim(false)
        viewModel.setRevealTiming(RevealTiming.SUSPENSE)

        val settings = viewModel.settings.first {
            it.themePreference == ThemePreference.LIGHT &&
                !it.haptics &&
                !it.dim &&
                it.revealTiming == RevealTiming.SUSPENSE
        }
        assertEquals(ThemePreference.LIGHT, settings.themePreference)
        assertEquals(false, settings.haptics)
        assertEquals(false, settings.dim)
        assertEquals(RevealTiming.SUSPENSE, settings.revealTiming)
    }

    @Test
    fun `stepping the countdown moves it by half a second`() = runTest {
        repository.setCountdownMillis(2_000)
        viewModel.settings.first { it.countdownMillis == 2_000 }

        viewModel.stepCountdown(1)

        assertEquals(2_500, viewModel.settings.first { it.countdownMillis != 2_000 }.countdownMillis)
    }

    // ---- helpers ------------------------------------------------------------

    private fun starter() = outcome(
        mode = DrawMode.STARTER,
        fingers = listOf(Finger(1L, 250f, 200f), Finger(2L, 750f, 1_000f)),
        assignment = mapOf(1L to 1, 2L to 2),
    )

    private fun outcome(
        mode: DrawMode,
        fingers: List<Finger>,
        assignment: Map<Long, Int>,
        teamCount: Int? = null,
        winnerId: Long = fingers.first().id,
    ) = DrawOutcome(
        mode = mode,
        teamCount = teamCount,
        assignment = assignment,
        order = fingers.map { it.id },
        winnerId = winnerId,
        fingers = fingers,
    )

    private class FakeHistory : DrawHistory {
        val recorded = mutableListOf<DrawRecord>()
        val winnersFlow = MutableStateFlow<List<DrawPoint>>(emptyList())
        val latestFlow = MutableStateFlow<DrawRecord?>(null)
        val countFlow = MutableStateFlow(0)

        override suspend fun record(draw: DrawRecord) {
            recorded += draw
        }

        override fun winners(limit: Int): Flow<List<DrawPoint>> = winnersFlow
        override fun latest(): Flow<DrawRecord?> = latestFlow
        override fun count(): Flow<Int> = countFlow
        override suspend fun clear() = recorded.clear()
    }
}
