package de.drehtuer.shotgun.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import de.drehtuer.shotgun.data.DrawPoint
import de.drehtuer.shotgun.data.DrawRecord
import de.drehtuer.shotgun.ui.navigation.DrawMode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The draw history, against a real database.
 *
 * Room generates the DAO, so what is under test here is not Kotlin anyone
 * wrote: it is the **SQL and the schema**. The winners query joins two tables
 * and orders by a column in the other one, pruning leans on a cascade, and the
 * mode is stored as text - none of which a compiler checks, and all of which
 * the fairness field depends on being right.
 */
@RunWith(RobolectricTestRunner::class)
class RoomDrawHistoryTest {

    private lateinit var db: ShotgunDatabase
    private lateinit var dao: DrawDao
    private lateinit var history: RoomDrawHistory

    @Before
    fun open() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, ShotgunDatabase::class.java).build()
        dao = db.drawDao()
        history = RoomDrawHistory(dao)
    }

    @After
    fun close() = db.close()

    private fun draw(
        mode: DrawMode = DrawMode.STARTER,
        teamCount: Int? = null,
        timestamp: Long = 1_000L,
        points: List<DrawPoint> = listOf(
            DrawPoint(x = 0.25f, y = 0.5f, won = true, assignment = null),
            DrawPoint(x = 0.75f, y = 0.5f, won = false, assignment = null),
        ),
    ) = DrawRecord(mode = mode, teamCount = teamCount, points = points, timestamp = timestamp)

    @Test
    fun `a recorded draw comes back whole`() = runTest {
        history.record(draw())

        assertEquals(1, history.count().first())
        val latest = history.latest().first()!!
        assertEquals(DrawMode.STARTER, latest.mode)
        assertEquals(null, latest.teamCount)
        assertEquals(1_000L, latest.timestamp)
        assertEquals(2, latest.points.size)
        // Normalised positions must survive the round trip unrounded: the
        // fairness field plots them directly.
        assertEquals(0.25f, latest.points.first { it.won }.x)
    }

    @Test
    fun `only the winning finger is a winner`() = runTest {
        history.record(draw())

        val winners = history.winners().first()
        assertEquals(1, winners.size)
        assertTrue(winners.single().won)
        assertEquals(0.25f, winners.single().x)
    }

    @Test
    fun `winners come back newest first, however the rows were inserted`() = runTest {
        // Inserted oldest last, so row order and timestamp order disagree -
        // which is the whole point of ordering by the joined draw's timestamp.
        history.record(draw(timestamp = 2_000, points = listOf(win(0.2f))))
        history.record(draw(timestamp = 3_000, points = listOf(win(0.3f))))
        history.record(draw(timestamp = 1_000, points = listOf(win(0.1f))))

        assertEquals(listOf(0.3f, 0.2f, 0.1f), history.winners().first().map { it.x })
    }

    @Test
    fun `the winners limit caps how much history the field plots`() = runTest {
        repeat(5) { history.record(draw(timestamp = it.toLong(), points = listOf(win(it / 10f)))) }

        assertEquals(2, history.winners(limit = 2).first().size)
        assertEquals(5, history.winnersNow().size)
    }

    @Test
    fun `a teams draw keeps its team count and has no winner to plot`() = runTest {
        history.record(
            draw(
                mode = DrawMode.TEAMS,
                teamCount = 3,
                points = listOf(
                    DrawPoint(x = 0.1f, y = 0.1f, won = false, assignment = 0),
                    DrawPoint(x = 0.2f, y = 0.2f, won = false, assignment = 1),
                ),
            )
        )

        val latest = history.latest().first()!!
        assertEquals(DrawMode.TEAMS, latest.mode)
        assertEquals(3, latest.teamCount)
        assertEquals(listOf(0, 1), latest.points.mapNotNull { it.assignment }.sorted())
        assertEquals(emptyList<DrawPoint>(), history.winners().first())
    }

    /**
     * A row written by a newer build that was then rolled back. Falling back to
     * a known mode beats crashing on the way into the result screen.
     */
    @Test
    fun `a mode this build does not know reads as starter`() = runTest {
        dao.insertDraw(DrawEntity(mode = "COLOURS", teamCount = null, timestamp = 5_000))

        assertEquals(DrawMode.STARTER, history.latest().first()!!.mode)
    }

    @Test
    fun `pruning drops the oldest draws and takes their points with them`() = runTest {
        val oldest = dao.insertDraw(DrawEntity(mode = "STARTER", teamCount = null, timestamp = 1))
        dao.insertPoints(listOf(DrawPointEntity(drawId = oldest, x = 0f, y = 0f, won = true, assignment = null)))

        // keep = 1, so recording one more must evict the first outright.
        dao.insertComplete(
            draw = DrawEntity(mode = "STARTER", teamCount = null, timestamp = 2),
            points = { id -> listOf(DrawPointEntity(drawId = id, x = 1f, y = 1f, won = true, assignment = null)) },
            keep = 1,
        )

        assertEquals(1, history.count().first())
        // The cascade is what keeps orphaned points from accumulating forever.
        assertEquals(emptyList<DrawPointEntity>(), dao.pointsFor(oldest))
        assertEquals(1f, history.winners().first().single().x)
    }

    @Test
    fun `clearing empties the history`() = runTest {
        history.record(draw())
        history.clear()

        assertEquals(0, history.count().first())
        assertNull(history.latest().first())
        assertEquals(emptyList<DrawPoint>(), history.winners().first())
    }

    private fun win(x: Float) = DrawPoint(x = x, y = 0.5f, won = true, assignment = null)
}
