package cn.xiaotao29.HighSpeedIEM.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class AdaptiveLayoutTest {
    @Test fun savedMeasurementsSurviveOnlyForTheSameDeviceList() {
        val keys = listOf<Any>("i8:0", "i8:1")
        val heights = intArrayOf(90, 200, 200)
        assertSame(heights, restoreChannelHeights(keys, heights, keys))
        // Return to a page after USB insertion/removal or channel count change.
        assertNull(restoreChannelHeights(keys, heights, keys + "i12:0"))
        assertNull(restoreChannelHeights(keys, heights, keys.take(1)))
    }

    @Test fun replacedOrReorderedDevicesDoNotReuseOtherCardMeasurements() {
        val keys = listOf<Any>("i8:0", "i12:0")
        val heights = intArrayOf(90, 200, 400)
        assertNull(restoreChannelHeights(keys, heights, keys.reversed()))
        assertNull(restoreChannelHeights(keys, heights, listOf("i8:0", "i14:0")))
    }

    @Test fun malformedOrLegacySavedMeasurementsAreDiscarded() {
        val keys = listOf<Any>("i8:0", "i8:1")
        assertNull(restoreChannelHeights(keys, intArrayOf(90, 200), keys))
        assertNull(restoreChannelHeights(null, intArrayOf(90, 200, 200), keys))
        assertNull(restoreChannelHeights(keys, null, keys))
    }

    @Test fun compactBoundaryHasPriorityInEitherOrientation() {
        assertEquals(AppLayoutMode.Compact, appLayoutMode(2400, 600, 0))
        assertEquals(AppLayoutMode.Compact, appLayoutMode(600, 2400, 0))
        assertEquals(AppLayoutMode.Landscape, appLayoutMode(2400, 601, 0))
        assertEquals(AppLayoutMode.Portrait, appLayoutMode(601, 2400, 0))
    }

    @Test fun watchAndResizedWindowsUseTheAvailableWindow() {
        // UI_MODE_TYPE_WATCH is 6. Night mode does not change the type decision.
        assertEquals(AppLayoutMode.Compact, appLayoutMode(2400, 1080, 0x26))
        assertEquals(AppLayoutMode.Portrait, appLayoutMode(900, 1200, 0x21))
        assertEquals(AppLayoutMode.Landscape, appLayoutMode(1200, 900, 0x21))
        assertEquals(AppLayoutMode.Portrait, appLayoutMode(900, 900, 0))
    }

    @Test fun unmeasuredWindowDoesNotSpuriouslyEnterCompactMode() {
        assertEquals(AppLayoutMode.Portrait, appLayoutMode(0, 0, 0))
    }
}
