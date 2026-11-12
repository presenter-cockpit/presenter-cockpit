package it.presenter.app.doc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SlidesTest {
    // Pages 0..6: slide "1" (0), slide "2" with three overlays (1,2,3), slide "3" (4), slide "4" with two (5,6).
    private val slides = Slides(listOf("1", "2", "2", "2", "3", "4", "4"), 7)

    @Test
    fun `groups from consecutive equal labels`() {
        assertTrue(slides.hasOverlays)
        assertEquals(1..3, slides.pagesOf(2))
        assertEquals(0..0, slides.pagesOf(0))
        assertEquals(5..6, slides.pagesOf(6))
    }

    @Test
    fun `slide index and slide count`() {
        assertEquals(4, slides.slideCount)
        assertEquals(listOf(0, 1, 1, 1, 2, 3, 3), (0 until 7).map(slides::slideOf))
        assertEquals(7, Slides.single(7).slideCount)
        assertEquals(6, Slides.single(7).slideOf(6))
    }

    @Test
    fun `forward to the first overlay, back to the last`() {
        assertEquals(1, slides.nextSlide(0))
        assertEquals(4, slides.nextSlide(2))
        assertNull(slides.nextSlide(5))
        assertEquals(3, slides.previousSlide(4))
        assertEquals(0, slides.previousSlide(3))
        assertNull(slides.previousSlide(0))
    }

    @Test
    fun `a tap shows the next overlay and stops at the last`() {
        assertEquals(2, slides.nextOverlay(1))
        assertEquals(3, slides.nextOverlay(2))
        assertNull(slides.nextOverlay(3))
        assertNull(slides.nextOverlay(0))
    }

    @Test
    fun `without labels every page is a slide`() {
        val s = Slides.single(3)
        assertFalse(s.hasOverlays)
        assertEquals(1, s.nextSlide(0))
        assertEquals(1, s.previousSlide(2))
        assertNull(s.nextOverlay(1))
    }

    @Test
    fun `labels shorter than the pages break nothing`() {
        val s = Slides(listOf("a", "a"), 4)
        assertEquals(0..1, s.pagesOf(1))
        assertEquals(2..2, s.pagesOf(2))
    }

    @Test
    fun `a section page with the same label is not an overlay`() {
        val goals = "Plan What we will see today: pots, soil and watering"
        val section = "Sowing and watering: a calendar for the balcony"
        val overlay = goals + " and the harvest"
        assertFalse(Slides.isContinuation(goals, section))
        assertTrue(Slides.isContinuation(goals, overlay))
        assertTrue(Slides.isContinuation("", section))
        val s = Slides(listOf("1", "5", "5"), 3) { it != 2 }
        assertEquals(2, s.nextSlide(1))
    }

    @Test
    fun `hidden slides are skipped going forward and back`() {
        // Slide "2" (pages 1-3) and slide "3" (page 4) hidden.
        val hidden = { p: Int -> p in 1..4 }
        assertEquals(5, slides.nextSlide(0, hidden))
        assertEquals(0, slides.previousSlide(5, hidden))
        assertNull(slides.nextSlide(5, hidden))
        // From a hidden slide (reached with a jump) the next visible one.
        assertEquals(5, slides.nextSlide(2, hidden))
        assertNull(slides.previousSlide(4) { true })
    }
}
