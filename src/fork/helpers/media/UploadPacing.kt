package desu.inugram.helpers.media

class UploadPacing(private val maxRequests: Int) {
    var limit = 1
        private set

    private var firstSampleRequest = 0

    fun onPartUploaded(request: Int, nextRequest: Int, elapsedMs: Long) {
        if (request < firstSampleRequest) return
        val newLimit = when {
            elapsedMs >= 1500L -> (limit / 2).coerceAtLeast(1)
            elapsedMs <= 750L -> (limit + 1).coerceAtMost(maxRequests)
            else -> limit
        }
        if (newLimit != limit) {
            limit = newLimit
            firstSampleRequest = nextRequest
        }
    }
}
