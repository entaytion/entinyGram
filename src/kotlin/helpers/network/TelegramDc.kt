package desu.inugram.helpers.network

object TelegramDc {
    class Info(val id: Int, val name: String, val location: String, val ip: String)

    val ALL = listOf(
        Info(1, "Pluto", "Miami", "149.154.175.53"),
        Info(2, "Venus", "Amsterdam", "149.154.167.51"),
        Info(3, "Aurora", "Miami", "149.154.175.100"),
        Info(4, "Vesta", "Amsterdam", "149.154.167.91"),
        Info(5, "Flora", "Singapore", "91.108.56.130"),
    )

    fun ip(dc: Int): String? = ALL.firstOrNull { it.id == dc }?.ip
    fun find(dc: Int): Info? = ALL.firstOrNull { it.id == dc }
}
