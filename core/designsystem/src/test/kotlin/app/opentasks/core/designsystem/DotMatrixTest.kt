package app.opentasks.core.designsystem

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class DotMatrixTest {
    @Test
    fun unitCountsAndProportionsProduceBoundedVisibleDots() {
        assertEquals(DotRunLayout(0, 0), dotRunLayout(0f, 0L, 24))
        assertEquals(DotRunLayout(3, 3), dotRunLayout(0.6f, 3L, 24))
        assertEquals(DotRunLayout(24, 12), dotRunLayout(0.5f, -1L, 24))
        assertEquals(DotRunLayout(24, 12), dotRunLayout(0.5f, 25L, 24))
        assertEquals(DotRunLayout(24, 24), dotRunLayout(0.5f, 24L, 24))
        assertEquals(DotRunLayout(24, 1), dotRunLayout(0.01f, null, 24))
        assertEquals(DotRunLayout(24, 12), dotRunLayout(0.5f, null, 24))
        assertEquals(DotRunLayout(24, 24), dotRunLayout(2f, null, 24))
        assertEquals(DotRunLayout(24, 0), dotRunLayout(Float.NaN, null, 24))
        assertEquals(DotRunLayout(24, 0), dotRunLayout(-1f, null, 24))
    }

    @Test
    fun columnsNormaliseAndKeepPositiveValuesVisible() {
        assertEquals(
            listOf(0, 1, 6, 12),
            dottedColumnHeights(listOf(0f, 1f, 6f, 12f), 12),
        )
        assertEquals(listOf(0, 0), dottedColumnHeights(listOf(0f, 0f), 12))
        assertEquals(
            listOf(0, 0, 0),
            dottedColumnHeights(listOf(-1f, Float.NaN, Float.POSITIVE_INFINITY), 12),
        )
        assertEquals(
            listOf(0, 0, 0, 12),
            dottedColumnHeights(listOf(-1f, Float.NaN, Float.POSITIVE_INFINITY, 12f), 12),
        )
    }

    @Test
    fun nonPositiveLimitsFailFast() {
        assertThrows(IllegalArgumentException::class.java) {
            dotRunLayout(0.5f, null, 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            dottedColumnHeights(listOf(1f), 0)
        }
    }
}
