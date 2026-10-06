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
    )

    fun find(id: String): DevicePreset? = all.firstOrNull { it.id == id }
}
