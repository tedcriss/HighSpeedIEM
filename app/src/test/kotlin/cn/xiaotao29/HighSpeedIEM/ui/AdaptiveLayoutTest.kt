package cn.xiaotao29.HighSpeedIEM.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class AdaptiveLayoutTest {
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
