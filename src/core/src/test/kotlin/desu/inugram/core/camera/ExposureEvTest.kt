package desu.inugram.core.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExposureEvTest {

    @Test
    fun zero_ev_is_zero_steps() {
        assertEquals(0, ExposureEv.evToSteps(0f, 1, 6, -18, 18))
    }

    @Test
    fun exact_multiples_of_step() {
        assertEquals(6, ExposureEv.evToSteps(1f, 1, 6, -18, 18))
        assertEquals(-12, ExposureEv.evToSteps(-2f, 1, 6, -18, 18))
    }

    // округление к «большему по модулю», а не к плюс-бесконечности: слайдер не должен
    // тяготеть к свету при одинаковых -0.25 EV и +0.25 EV
    @Test
    fun rounds_half_away_from_zero() {
        assertEquals(2, ExposureEv.evToSteps(0.25f, 1, 6, -18, 18))
        assertEquals(-2, ExposureEv.evToSteps(-0.25f, 1, 6, -18, 18))
    }

    @Test
    fun clamps_to_device_range() {
        assertEquals(12, ExposureEv.evToSteps(4f, 1, 6, -12, 12))
        assertEquals(-12, ExposureEv.evToSteps(-4f, 1, 6, -12, 12))
    }

    @Test
    fun clamps_asymmetric_range() {
        assertEquals(12, ExposureEv.evToSteps(4f, 1, 6, -6, 12))
        assertEquals(-6, ExposureEv.evToSteps(-4f, 1, 6, -6, 12))
    }

    @Test
    fun typical_third_step_span() {
        assertEquals(9, ExposureEv.evToSteps(3f, 1, 3, -9, 9))
        assertEquals(9, ExposureEv.evToSteps(3.5f, 1, 3, -9, 9))
    }

    // HAL без шага компенсации = никакого управления экспозицией, а не «1 EV за деление»
    @Test
    fun unsupported_step_means_no_control() {
        assertEquals(0, ExposureEv.evToSteps(2f, 0, 0, -12, 12))
        assertEquals(0, ExposureEv.evToSteps(2f, 1, 0, -12, 12))
        assertEquals(0, ExposureEv.evToSteps(2f, 0, 1, -12, 12))
    }

    @Test
    fun steps_to_ev_is_exact() {
        assertEquals(2f, ExposureEv.stepsToEv(3, 2, 3), 0f)
        assertEquals(-1f, ExposureEv.stepsToEv(-6, 1, 6), 0f)
        assertEquals(0f, ExposureEv.stepsToEv(0, 1, 6), 0f)
    }

    @Test
    fun steps_to_ev_on_unsupported_step_is_zero() {
        assertEquals(0f, ExposureEv.stepsToEv(5, 0, 0), 0f)
        assertEquals(0f, ExposureEv.stepsToEv(5, 1, 0), 0f)
    }

    // значение, показанное в UI, должно конвертироваться обратно в тот же raw: иначе
    // ползунок «едет» при каждом переоткрытии камеры
    // UI показывает фиксированные стопы EV; показывать можно только ту, которую HAL умеет ровно
    @Test
    fun representable_requires_exact_match() {
        assertTrue(ExposureEv.isRepresentable(1f, 1, 6, -18, 18))
        assertTrue(ExposureEv.isRepresentable(-2f, 2, 3, -6, 6))
        assertFalse(ExposureEv.isRepresentable(1f, 2, 3, -6, 6))
    }

    @Test
    fun representable_respects_range_bounds() {
        assertTrue(ExposureEv.isRepresentable(2f, 1, 6, -18, 12))
        assertFalse(ExposureEv.isRepresentable(4f, 1, 6, -18, 12))
        assertFalse(ExposureEv.isRepresentable(-2f, 1, 6, -6, 12))
    }

    @Test
    fun zero_is_the_only_level_when_device_has_no_control() {
        assertTrue(ExposureEv.isRepresentable(0f, 0, 0, 0, 0))
        assertFalse(ExposureEv.isRepresentable(1f, 0, 0, -18, 18))
        assertFalse(ExposureEv.isRepresentable(0f, 1, 6, 1, 12))
    }

    @Test
    fun snapping_is_idempotent() {
        for (ev in floatArrayOf(-1.1f, -0.25f, 0.7f, 2.4f)) {
            val snappedOnce = ExposureEv.evToSteps(ev, 1, 6, -18, 18)
            val reread = ExposureEv.stepsToEv(snappedOnce, 1, 6)
            assertEquals(snappedOnce, ExposureEv.evToSteps(reread, 1, 6, -18, 18))
        }
    }
}
