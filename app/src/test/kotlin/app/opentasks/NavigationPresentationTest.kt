package app.opentasks

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationPresentationTest {
    @Test
    fun navigationLabelsRemainVisibleAtSupportedStandardScales() {
        assertTrue(shouldShowNavigationLabels(fontScale = 1f))
        assertTrue(shouldShowNavigationLabels(fontScale = 1.3f))
    }

    @Test
    fun navigationLabelsCollapseBeforeTheyWrapAtLargeTextScales() {
        assertFalse(shouldShowNavigationLabels(fontScale = 1.5f))
        assertFalse(shouldShowNavigationLabels(fontScale = 2f))
    }
}
