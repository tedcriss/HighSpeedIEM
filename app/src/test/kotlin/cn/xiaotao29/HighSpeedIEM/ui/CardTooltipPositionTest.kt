package cn.xiaotao29.HighSpeedIEM.ui

import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CardTooltipPositionTest {
    private val viewport = IntRect(120, 24, 1000, 480)
    private fun popup(card: IntRect, size: IntSize = IntSize(300, 260), side: Boolean = true,
        direction: LayoutDirection = LayoutDirection.Ltr): IntRect {
        // Capability's small button anchor deliberately sits inside the protected card.
        val anchor = IntRect(card.left + 20, card.top + 50, card.left + 100, card.top + 90)
        val offset = CardTooltipPositionProvider(viewport, card, 8, side)
            .calculatePosition(anchor, IntSize(1080, 500), direction, size)
        return IntRect(offset, size).also {
            assertTrue("outside safe viewport: $it", viewport.contains(it.topLeft))
            assertTrue(it.right <= viewport.right && it.bottom <= viewport.bottom)
        }
    }
    @Test fun bothLandscapeColumnsKeepTheirCardUncovered() {
        for (card in listOf(IntRect(140, 80, 530, 440), IntRect(550, 80, 980, 440))) {
            assertFalse(popup(card).overlaps(card))
        }
    }
    @Test fun verticalFallbackBeatsClampingASidePopupOverTheCard() {
        val card = IntRect(140, 40, 980, 130)
        val rect = popup(card, IntSize(400, 260))
        assertFalse(rect.overlaps(card))
        assertEquals(138, rect.top)
    }
    @Test fun portraitPrefersAboveWhenItFits() {
        val card = IntRect(300, 320, 700, 450)
        assertEquals(52, popup(card, side = false).top)
    }
    @Test fun unavoidableOverlapLeavesTheLargestVisibleCardArea() {
        val card = IntRect(140, 40, 980, 450)
        val rect = popup(card, IntSize(400, 260))
        val intersection = rect.intersect(card)
        assertTrue(intersection.width * intersection.height < card.width * card.height / 2)
        assertEquals(120, rect.left)
        assertEquals(220, rect.top)
    }
    @Test fun rtlUsesTheLogicalEndWhenBothSidesFit() {
        val card = IntRect(450, 100, 650, 350)
        assertEquals(658, popup(card, IntSize(200, 200)).left)
        assertEquals(242, popup(card, IntSize(200, 200), direction = LayoutDirection.Rtl).left)
    }
    @Test fun partlyScrolledCardUsesItsVisibleArea() {
        val card = IntRect(140, -160, 530, 350)
        assertFalse(popup(card).overlaps(card.intersect(viewport)))
    }
}
