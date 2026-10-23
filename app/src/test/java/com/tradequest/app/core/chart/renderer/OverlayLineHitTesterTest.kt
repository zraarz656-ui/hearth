package com.tradequest.app.core.chart.renderer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Phase 10 order-line hit testing (Step 10.4).
 *
 * # What is being verified
 *
 * That a touch picks the line the trader aimed at: the nearest one within
 * tolerance, the stop when two are equidistant, nothing when the touch is in empty
 * space, and nothing on a preview bracket. The last two matter as much as the
 * first — a drag that grabs a line from far away, or one that grabs a level that
 * cannot be traded, would move something the trader never touched.
 */
class OverlayLineHitTesterTest {

    private fun group(
        id: String,
        entry: Int? = null,
        stop: Int? = null,
        target: Int? = null,
        entryDraggable: Boolean = false,
        isPreview: Boolean = false,
    ): OverlayGroup = OverlayGroup(
        id = id,
        isBuy = true,
        isPreview = isPreview,
        lines = buildList {
            entry?.let { add(OverlayLine(OverlayLineKind.ENTRY, it, isDraggable = entryDraggable)) }
            stop?.let { add(OverlayLine(OverlayLineKind.STOP, it, isDraggable = true)) }
            target?.let { add(OverlayLine(OverlayLineKind.TARGET, it, isDraggable = true)) }
        },
    )

    @Test
    fun `a touch on a stop finds that stop`() {
        val hit = OverlayLineHitTester.hit(
            overlays = listOf(group("p1", entry = 2_000_000, stop = 1_995_000, target = 2_010_000)),
            tapPriceUnits = 1_995_000,
            toleranceUnits = 1_000,
        )
        assertEquals("p1", hit?.overlayId)
        assertEquals(OverlayLineKind.STOP, hit?.kind)
        assertEquals(1_995_000, hit?.priceUnits)
    }

    @Test
    fun `a touch in empty space finds nothing`() {
        val hit = OverlayLineHitTester.hit(
            overlays = listOf(group("p1", entry = 2_000_000, stop = 1_995_000, target = 2_010_000)),
            tapPriceUnits = 2_005_000,
            toleranceUnits = 1_000,
        )
        assertNull(hit)
    }

    @Test
    fun `a touch just inside tolerance finds the line`() {
        val hit = OverlayLineHitTester.hit(
            overlays = listOf(group("p1", stop = 1_995_000)),
            tapPriceUnits = 1_995_900,
            toleranceUnits = 1_000,
        )
        assertEquals(OverlayLineKind.STOP, hit?.kind)
    }

    @Test
    fun `a touch just outside tolerance finds nothing`() {
        val hit = OverlayLineHitTester.hit(
            overlays = listOf(group("p1", stop = 1_995_000)),
            tapPriceUnits = 1_996_100,
            toleranceUnits = 1_000,
        )
        assertNull(hit)
    }

    @Test
    fun `the nearer of two lines wins`() {
        val hit = OverlayLineHitTester.hit(
            overlays = listOf(group("p1", entry = 2_000_000, stop = 1_995_000)),
            tapPriceUnits = 1_996_000,
            toleranceUnits = 5_000,
        )
        assertEquals(OverlayLineKind.STOP, hit?.kind)
    }

    @Test
    fun `a non-draggable entry line is never hit`() {
        val hit = OverlayLineHitTester.hit(
            overlays = listOf(group("p1", entry = 2_000_000)),
            tapPriceUnits = 2_000_000,
            toleranceUnits = 5_000,
        )
        assertNull(hit)
    }

    @Test
    fun `a draggable entry line is hit, as on a pending order`() {
        val hit = OverlayLineHitTester.hit(
            overlays = listOf(group("o1", entry = 2_000_000, entryDraggable = true)),
            tapPriceUnits = 2_000_000,
            toleranceUnits = 5_000,
        )
        assertEquals(OverlayLineKind.ENTRY, hit?.kind)
    }

    @Test
    fun `a stop wins a tie against a target`() {
        // Equidistant stop and target. The stop is the safer reading and the stable
        // one, so it is chosen regardless of list order.
        val stopFirst = OverlayLineHitTester.hit(
            overlays = listOf(group("p1", stop = 1_999_000, target = 2_001_000)),
            tapPriceUnits = 2_000_000,
            toleranceUnits = 5_000,
        )
        assertEquals(OverlayLineKind.STOP, stopFirst?.kind)

        // The same lines listed the other way round must give the same answer.
        val targetFirst = OverlayLineHitTester.hit(
            overlays = listOf(
                OverlayGroup(
                    id = "p1",
                    isBuy = true,
                    lines = listOf(
                        OverlayLine(OverlayLineKind.TARGET, 2_001_000, isDraggable = true),
                        OverlayLine(OverlayLineKind.STOP, 1_999_000, isDraggable = true),
                    ),
                ),
            ),
            tapPriceUnits = 2_000_000,
            toleranceUnits = 5_000,
        )
        assertEquals(OverlayLineKind.STOP, targetFirst?.kind)
    }

    @Test
    fun `the nearer overlay's line wins across overlays`() {
        val hit = OverlayLineHitTester.hit(
            overlays = listOf(
                group("p1", stop = 1_990_000),
                group("p2", stop = 1_994_000),
            ),
            tapPriceUnits = 1_994_500,
            toleranceUnits = 10_000,
        )
        assertEquals("p2", hit?.overlayId)
    }

    @Test
    fun `a preview bracket is never hit`() {
        val hit = OverlayLineHitTester.hit(
            overlays = listOf(group("preview", stop = 1_995_000, isPreview = true)),
            tapPriceUnits = 1_995_000,
            toleranceUnits = 5_000,
        )
        assertNull(hit)
    }

    @Test
    fun `a zero tolerance matches nothing`() {
        val hit = OverlayLineHitTester.hit(
            overlays = listOf(group("p1", stop = 1_995_000)),
            tapPriceUnits = 1_995_000,
            toleranceUnits = 0,
        )
        assertNull(hit)
    }

    @Test
    fun `no overlays yields no hit`() {
        assertNull(OverlayLineHitTester.hit(emptyList(), 2_000_000, 1_000))
    }
}
