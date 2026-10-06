package desu.inugram.helpers.feed

sealed class FeedScope {
    object Global : FeedScope()
}

object FeedChannelSet {
    @Volatile
    var generation: Int = 0
        private set

    @Synchronized
    fun invalidate() {
        generation++
    }
}
