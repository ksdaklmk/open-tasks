package app.opentasks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QuickAddPrefillTest {

    @Test
    fun firstLineTrimmedAndBounded() {
        assertEquals("Buy milk", quickAddPrefill("  Buy milk  \nsecond line"))
        assertEquals(240, quickAddPrefill("x".repeat(500))!!.length)
        assertNull(quickAddPrefill("\nBuy milk"))
        assertNull(quickAddPrefill("   \n \n"))
        assertNull(quickAddPrefill(null))
    }
}
