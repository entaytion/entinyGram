package desu.inugram.core.camera

import kotlin.math.ceil
import kotlin.math.floor

// округление строго от нуля: Math.round(-1.5) = -1, из-за чего слайдер экспозиции тяготеет к свету
object ExposureEv {
    fun evToSteps(ev: Float, stepNumerator: Int, stepDenominator: Int, minStep: Int, maxStep: Int): Int {
        if (stepNumerator <= 0 || stepDenominator <= 0) return 0
        val steps = ev.toDouble() * stepDenominator / stepNumerator
        return roundHalfAwayFromZero(steps).coerceIn(minStep, maxStep)
    }

    fun stepsToEv(step: Int, stepNumerator: Int, stepDenominator: Int): Float {
        if (stepNumerator <= 0 || stepDenominator <= 0) return 0f
        return step.toFloat() * stepNumerator.toFloat() / stepDenominator.toFloat()
    }

    private fun roundHalfAwayFromZero(value: Double): Int =
        (if (value >= 0.0) floor(value + 0.5) else ceil(value - 0.5)).toInt()
}
