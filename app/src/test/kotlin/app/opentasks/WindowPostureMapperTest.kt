package app.opentasks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WindowPostureMapperTest {
    @Test
    fun migrationPaneChoosesTheLargestUnoccludedPhysicalPane() {
        assertEquals(
            MigrationPane(0, 0, 1_000, 800),
            largestMigrationPane(1_000, 800, emptyList()),
        )
        assertEquals(
            MigrationPane(0, 0, 480, 800),
            largestMigrationPane(
                1_000,
                800,
                listOf(RawFold(480, 0, 40, 800, isSeparating = true)),
            ),
        )
        assertEquals(
            MigrationPane(0, 320, 480, 800),
            largestMigrationPane(
                1_000,
                800,
                listOf(
                    RawFold(480, 0, 40, 800, isSeparating = true),
                    RawFold(0, 300, 1_000, 20, isSeparating = true),
                ),
            ),
        )
        assertEquals(
            MigrationPane(0, 0, 500, 800),
            largestMigrationPane(
                1_000,
                800,
                listOf(RawFold(500, 0, 0, 800, isSeparating = true)),
            ),
        )
        assertEquals(
            MigrationPane(0, 0, 1_000, 800),
            largestMigrationPane(
                1_000,
                800,
                listOf(RawFold(480, 0, 40, 800, isSeparating = false)),
            ),
        )
    }

    @Test
    fun tallFoldBecomesVerticalFoldLineInDp() {
        val posture = WindowPostureMapper.map(
            widthDp = 840,
            heightDp = 900,
            density = 2.0f,
            folds = listOf(
                RawFold(
                    leftPx = 840,
                    topPx = 0,
                    widthPx = 48,
                    heightPx = 1_800,
                    isSeparating = true,
                ),
            ),
        )

        val fold = posture.foldLines.single()
        assertEquals(FoldOrientation.VERTICAL, fold.orientation)
        assertEquals(420, fold.positionDp)
        assertEquals(24, fold.occludedWidthDp)
        assertTrue(fold.isSeparating)
    }

    @Test
    fun wideFoldBecomesHorizontalFoldLineInDp() {
        val posture = WindowPostureMapper.map(
            widthDp = 900,
            heightDp = 840,
            density = 2.0f,
            folds = listOf(
                RawFold(
                    leftPx = 0,
                    topPx = 840,
                    widthPx = 1_800,
                    heightPx = 40,
                    isSeparating = true,
                ),
            ),
        )

        val fold = posture.foldLines.single()
        assertEquals(FoldOrientation.HORIZONTAL, fold.orientation)
        assertEquals(420, fold.positionDp)
        assertEquals(20, fold.occludedWidthDp)
    }

    @Test
    fun zeroWidthHingeIsPreservedAsZeroOcclusion() {
        val posture = WindowPostureMapper.map(
            widthDp = 840,
            heightDp = 900,
            density = 2.0f,
            folds = listOf(
                RawFold(
                    leftPx = 840,
                    topPx = 0,
                    widthPx = 0,
                    heightPx = 1_800,
                    isSeparating = true,
                ),
            ),
        )

        assertEquals(0, posture.foldLines.single().occludedWidthDp)
        assertEquals(420, posture.foldLines.single().positionDp)
    }

    @Test
    fun windowSizeIsCarriedThrough() {
        val posture = WindowPostureMapper.map(
            widthDp = 330,
            heightDp = 700,
            density = 3.0f,
            folds = emptyList(),
        )

        assertEquals(330, posture.widthDp)
        assertEquals(700, posture.heightDp)
        assertTrue(posture.foldLines.isEmpty())
    }
}
