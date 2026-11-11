package desu.inugram.core.camera

import kotlin.math.abs

data class FrameSize(val width: Int, val height: Int) {
    val shortEdge: Int get() = if (width < height) width else height
    val longEdge: Int get() = if (width < height) height else width
    val pixels: Long get() = width.toLong() * height.toLong()
    val ratio: Float get() = if (shortEdge <= 0) 0f else longEdge.toFloat() / shortEdge
}

class RecordingSizePicker(
    private val ratio: Float,
    private val floor: Int,
    private val ceiling: Int,
    private val target: Int? = null,
    private val ratioSlack: Float = 0.02f,
) {
    fun fitsRatio(size: FrameSize): Boolean =
        size.shortEdge > 0 && ratio > 0f && abs(size.ratio / ratio - 1f) <= ratioSlack

    fun rank(sizes: Collection<FrameSize>): List<FrameSize> =
        sizes.filter { it.shortEdge > 0 }.sortedWith(order)

    fun pick(sizes: Collection<FrameSize>): FrameSize? =
        sizes.filter { it.shortEdge > 0 }.minWithOrNull(order)

    private enum class Band { RATIO_FIT, RATIO_BIG, OTHER_FIT, OTHER_BIG, SMALL }

    private fun bandOf(size: FrameSize): Band {
        val edge = size.shortEdge
        if (edge < floor) return Band.SMALL
        val big = edge > ceiling
        return if (fitsRatio(size)) {
            if (big) Band.RATIO_BIG else Band.RATIO_FIT
        } else {
            if (big) Band.OTHER_BIG else Band.OTHER_FIT
        }
    }

    private fun edgeScore(band: Band, edge: Int): Long = when (band) {
        Band.RATIO_BIG, Band.OTHER_BIG -> edge.toLong()
        Band.SMALL -> -edge.toLong()
        Band.RATIO_FIT, Band.OTHER_FIT -> {
            val goal = target
            when {
                goal == null || goal <= 0 -> -edge.toLong()
                edge >= goal -> edge.toLong()
                else -> Int.MAX_VALUE.toLong() - edge
            }
        }
    }

    private val order: Comparator<FrameSize> = Comparator { a, b ->
        val bandA = bandOf(a)
        val bandB = bandOf(b)
        when {
            bandA != bandB -> bandA.compareTo(bandB)
            else -> {
                val byEdge = edgeScore(bandA, a.shortEdge).compareTo(edgeScore(bandB, b.shortEdge))
                if (byEdge != 0) byEdge else a.pixels.compareTo(b.pixels)
            }
        }
    }
}
