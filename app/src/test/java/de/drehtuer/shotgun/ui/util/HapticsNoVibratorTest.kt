package de.drehtuer.shotgun.ui.util

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import de.drehtuer.shotgun.ui.navigation.DrawMode
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Haptics on hardware that has nothing to buzz.
 *
 * Tablets and some cheap phones expose no vibrator service at all. The tick
 * fires on every finger that lands, so a lookup that threw there would take
 * the draw surface down with it rather than just staying quiet.
 */
@RunWith(RobolectricTestRunner::class)
class HapticsNoVibratorTest {

    /** A device with no system services to hand out, and a count of the asks. */
    private class Bare(base: Context) : ContextWrapper(base) {
        val asked = mutableListOf<String>()
        override fun getSystemService(name: String): Any? {
            asked += name
            return null
        }
    }

    @Test
    fun `with no vibrator the tick and every result buzz pass silently`() {
        val bare = Bare(ApplicationProvider.getApplicationContext())

        Haptics.tick(bare, enabled = true)
        DrawMode.entries.forEach { Haptics.result(bare, enabled = true, mode = it) }

        // It looked every time - nothing cached a missing vibrator - and
        // carried on each time it found none.
        assertEquals(1 + DrawMode.entries.size, bare.asked.size)
    }

    @Test
    fun `with haptics off the vibrator is not even looked up`() {
        val bare = Bare(ApplicationProvider.getApplicationContext())

        Haptics.tick(bare, enabled = false)
        Haptics.result(bare, enabled = false, mode = DrawMode.ORDER)

        assertEquals(emptyList<String>(), bare.asked)
    }
}
