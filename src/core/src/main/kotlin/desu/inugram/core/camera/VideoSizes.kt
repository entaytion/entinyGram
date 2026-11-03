package desu.inugram.core.camera

import kotlin.math.abs

data class CamSize(val width: Int, val height: Int)

// порядок кандидатов размера записи — порт CameraXUtils.orderRoundVideoSizes из CherryGram (@arsLan4k1390)
object VideoSizes {
    const val ASPECT_TOLERANCE = 0.02f

    fun shortSide(size: CamSize): Int = minOf(size.width, size.height)

    fun aspectOf(size: CamSize): Float = maxOf(size.width, size.height).toFloat() / shortSide(size)

    fun matchesAspect(size: CamSize, aspect: Float): Boolean =
        abs(aspectOf(size) - aspect) <= aspect * ASPECT_TOLERANCE

    fun order(
        sizes: List<CamSize>,
        maxShortSide: Int,
        minShortSide: Int,
        targetShortSide: Int,
        aspect: Float,
    ): List<CamSize> {
        val fitting = mutableListOf<CamSize>()
        val larger = mutableListOf<CamSize>()
        val otherFitting = mutableListOf<CamSize>()
        val otherLarger = mutableListOf<CamSize>()
        val tooSmall = mutableListOf<CamSize>()
        for (size in sizes) {
            val side = shortSide(size)
            val matches = matchesAspect(size, aspect)
            when {
                side < minShortSide -> tooSmall += size
                side <= maxShortSide -> (if (matches) fitting else otherFitting) += size
                else -> (if (matches) larger else otherLarger) += size
            }
        }
        val bySizeThenArea = Comparator<CamSize> { left, right ->
            val bySide = shortSide(left).compareTo(shortSide(right))
            if (bySide != 0) bySide else area(left).compareTo(area(right))
        }
        val largestFirst = Comparator<CamSize> { left, right -> -bySizeThenArea.compare(left, right) }
        val nearestToTarget = Comparator<CamSize> { left, right ->
            val leftEnough = shortSide(left) >= targetShortSide
            val rightEnough = shortSide(right) >= targetShortSide
            if (leftEnough != rightEnough) return@Comparator if (leftEnough) -1 else 1
            val bySide = if (leftEnough) shortSide(left).compareTo(shortSide(right)) else shortSide(right).compareTo(shortSide(left))
            if (bySide != 0) bySide else area(left).compareTo(area(right))
        }
        val fittingOrder = if (targetShortSide > 0) nearestToTarget else largestFirst
        fitting.sortWith(fittingOrder)
        otherFitting.sortWith(fittingOrder)
        larger.sortWith(bySizeThenArea)
        otherLarger.sortWith(bySizeThenArea)
        tooSmall.sortWith(largestFirst)
        return buildList {
            addAll(fitting)
            addAll(larger)
            addAll(otherFitting)
            addAll(otherLarger)
            addAll(tooSmall)
        }
    }

    fun choose(
        sizes: List<CamSize>,
        maxShortSide: Int,
        minShortSide: Int,
        targetShortSide: Int,
        aspect: Float,
    ): CamSize? = order(sizes, maxShortSide, minShortSide, targetShortSide, aspect).firstOrNull()

    private fun area(size: CamSize): Long = size.width.toLong() * size.height
}
