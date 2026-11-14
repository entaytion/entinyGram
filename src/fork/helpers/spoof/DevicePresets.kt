package desu.inugram.helpers.spoof

data class DevicePreset(
    val id: String,
    val name: String,
    val deviceModel: String,
    val systemVersion: String,
)

object DevicePresets {
    const val CUSTOM_ID = "custom"

    val all: List<DevicePreset> = listOf(
        DevicePreset("oneplus_13r", "OnePlus 13R", "OnePlusCPH2645", "SDK 35"),
        DevicePreset("pixel_9_pro", "Google Pixel 9 Pro", "GooglePixel 9 Pro", "SDK 35"),
        DevicePreset("galaxy_s24_ultra", "Samsung Galaxy S24 Ultra", "samsungSM-S928B", "SDK 35"),
        DevicePreset("xiaomi_14", "Xiaomi 14", "Xiaomi23127PN0CG", "SDK 35"),
        DevicePreset("nothing_phone_2a", "Nothing Phone (2a)", "NothingA142", "SDK 35"),
        DevicePreset("pixel_8", "Google Pixel 8", "GooglePixel 8", "SDK 35"),
        DevicePreset("pixel_8_pro", "Google Pixel 8 Pro", "GooglePixel 8 Pro", "SDK 35"),
        DevicePreset("pixel_9", "Google Pixel 9", "GooglePixel 9", "SDK 35"),
        DevicePreset("pixel_9_pro_xl", "Google Pixel 9 Pro XL", "GooglePixel 9 Pro XL", "SDK 35"),
        DevicePreset("pixel_10", "Google Pixel 10", "GooglePixel 10", "SDK 36"),
        DevicePreset("pixel_10_pro", "Google Pixel 10 Pro", "GooglePixel 10 Pro", "SDK 36"),
        DevicePreset("galaxy_s24", "Samsung Galaxy S24", "samsungSM-S921B", "SDK 35"),
        DevicePreset("galaxy_s24_plus", "Samsung Galaxy S24+", "samsungSM-S926B", "SDK 35"),
        DevicePreset("galaxy_s25", "Samsung Galaxy S25", "samsungSM-S931B", "SDK 35"),
        DevicePreset("galaxy_s25_plus", "Samsung Galaxy S25+", "samsungSM-S936B", "SDK 35"),
        DevicePreset("galaxy_s25_ultra", "Samsung Galaxy S25 Ultra", "samsungSM-S938B", "SDK 35"),
    )

    const val RANDOM_ID = "random"

    private val sessionPick: DevicePreset by lazy { all.random() }

    fun find(id: String): DevicePreset? =
        if (id == RANDOM_ID) sessionPick else all.firstOrNull { it.id == id }
}
