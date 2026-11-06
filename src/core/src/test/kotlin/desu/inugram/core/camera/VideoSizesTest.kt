package desu.inugram.core.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoSizesTest {

    private val sixteenNine = 16f / 9f
    private val fourThree = 4f / 3f

    private val uhd = CamSize(3840, 2160)
    private val fhd = CamSize(1920, 1080)
    private val hd = CamSize(1280, 720)
    private val sd = CamSize(640, 480)
    private val tiny = CamSize(320, 240)
    private val square = CamSize(1080, 1080)
    private val portraitFhd = CamSize(1080, 1920)
    private val threeTwo = CamSize(1440, 960)

    @Test
    fun on_aspect_beats_off_aspect_within_limits() {
        val result = VideoSizes.order(listOf(square, fhd), maxShortSide = 1088, minShortSide = 480, targetShortSide = 720, aspect = sixteenNine)
        assertEquals(listOf(fhd, square), result)
    }

    @Test
    fun oversized_sinks_below_fitting() {
        val result = VideoSizes.order(listOf(uhd, fhd), maxShortSide = 1088, minShortSide = 480, targetShortSide = 1080, aspect = sixteenNine)
        assertEquals(listOf(fhd, uhd), result)
    }

    @Test
    fun too_small_goes_last() {
        val result = VideoSizes.order(listOf(tiny, sd, fhd), maxShortSide = 1088, minShortSide = 480, targetShortSide = 720, aspect = fourThree)
        assertEquals(listOf(sd, fhd, tiny), result)
    }

    @Test
    fun nearest_to_target_wins_within_fitting() {
        val result = VideoSizes.order(listOf(fhd, sd, hd), maxShortSide = 1088, minShortSide = 480, targetShortSide = 720, aspect = sixteenNine)
        assertEquals(listOf(hd, fhd, sd), result)
    }

    // targetShortSide <= 0 означает «без цели» — тогда в пределах лимита берём максимальный
    @Test
    fun no_target_prefers_largest() {
        val result = VideoSizes.order(listOf(sd, fhd, hd), maxShortSide = 1088, minShortSide = 480, targetShortSide = 0, aspect = sixteenNine)
        assertEquals(listOf(fhd, hd, sd), result)
    }

    @Test
    fun same_short_side_breaks_tie_by_area() {
        val wideish = CamSize(1280, 1080)
        val result = VideoSizes.order(listOf(square, wideish), maxShortSide = 1088, minShortSide = 480, targetShortSide = 0, aspect = sixteenNine)
        assertEquals(listOf(wideish, square), result)
    }

    @Test
    fun aspect_check_ignores_sensor_orientation() {
        assertTrue(VideoSizes.matchesAspect(portraitFhd, sixteenNine))
        val result = VideoSizes.order(listOf(square, portraitFhd), maxShortSide = 1088, minShortSide = 480, targetShortSide = 1080, aspect = sixteenNine)
        assertEquals(listOf(portraitFhd, square), result)
    }

    @Test
    fun tolerance_accepts_rounded_panel_size() {
        assertTrue(VideoSizes.matchesAspect(CamSize(1920, 1088), sixteenNine))
        assertFalse(VideoSizes.matchesAspect(threeTwo, fourThree))
        assertFalse(VideoSizes.matchesAspect(threeTwo, sixteenNine))
    }

    @Test
    fun empty_input_has_no_choice() {
        assertEquals(emptyList<CamSize>(), VideoSizes.order(emptyList(), 1088, 480, 720, sixteenNine))
        assertNull(VideoSizes.choose(emptyList(), 1088, 480, 720, sixteenNine))
    }

    @Test
    fun choose_returns_the_best_preset() {
        val hal = listOf(uhd, fhd, hd, sd, tiny, CamSize(2048, 1080), CamSize(1440, 1080))
        assertEquals(hd, VideoSizes.choose(hal, maxShortSide = 1088, minShortSide = 480, targetShortSide = 720, aspect = sixteenNine))
        assertEquals(fhd, VideoSizes.choose(hal, maxShortSide = 1088, minShortSide = 480, targetShortSide = 1080, aspect = sixteenNine))
    }

    // если ничего не влезает в лимит, берём наименьший из «больших», а не мусор из too_small
    @Test
    fun choose_falls_back_to_smallest_oversize_before_garbage() {
        val result = VideoSizes.choose(listOf(tiny, uhd), maxShortSide = 1088, minShortSide = 480, targetShortSide = 720, aspect = sixteenNine)
        assertEquals(uhd, result)
    }

    // размер не по аспекту, но в лимите, всё равно хуже «большого» с правильным аспектом
    @Test
    fun oversize_on_aspect_beats_fitting_off_aspect() {
        val result = VideoSizes.order(listOf(square, uhd), maxShortSide = 1088, minShortSide = 480, targetShortSide = 720, aspect = sixteenNine)
        assertEquals(listOf(uhd, square), result)
    }

    // ниже минимума — но ближе к usable идём в первую очередь
    @Test
    fun too_small_bucket_is_ordered_largest_first() {
        val qvga = CamSize(640, 360)
        val result = VideoSizes.order(listOf(tiny, qvga), maxShortSide = 1088, minShortSide = 480, targetShortSide = 720, aspect = sixteenNine)
        assertEquals(listOf(qvga, tiny), result)
    }

    // ниже цели, но не «слишком мало» — всё равно хуже, чем любой дотянувший до цели
    @Test
    fun below_target_sorts_after_enough_descending() {
        val sub720 = CamSize(1024, 576)
        val half = CamSize(896, 504)
        val result = VideoSizes.order(listOf(half, sub720, hd), maxShortSide = 1088, minShortSide = 480, targetShortSide = 720, aspect = sixteenNine)
        assertEquals(listOf(hd, sub720, half), result)
    }

    @Test
    fun short_side_and_aspect_are_rotation_invariant() {
        assertEquals(1080, VideoSizes.shortSide(portraitFhd))
        assertEquals(1080, VideoSizes.shortSide(fhd))
        assertEquals(VideoSizes.aspectOf(fhd), VideoSizes.aspectOf(portraitFhd), 1e-4f)
        assertEquals(sixteenNine, VideoSizes.aspectOf(portraitFhd), 1e-4f)
    }
}
