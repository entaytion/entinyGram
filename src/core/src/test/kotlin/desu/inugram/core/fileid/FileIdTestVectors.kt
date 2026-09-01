package desu.inugram.core.fileid

// test vectors from https://github.com/mtcute/mtcute/tree/master/packages/file-id, MIT license.

internal fun decodeHex(hex: String): ByteArray =
    ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

internal fun encodeHex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

internal data class Vector(
    val fileId: String,
    val location: FullRemoteFileLocation,
    val uniqueFileId: String,
    val reserialized: String,
)

internal val STICKER = Vector(
    "CAACAgIAAxkBAAEJny9gituz1_V_uSKBUuG_nhtzEtFOeQACXFoAAuCjggfYjw_KAAGSnkgfBA",
    FullRemoteFileLocation(
        dcId = 2,
        type = FileType.STICKER,
        fileReference = decodeHex("0100099f2f608adbb3d7f57fb9228152e1bf9e1b7312d14e79"),
        location = RemoteFileLocation.Common(
            id = 541175087705905756L,
            accessHash = 5232780349138767832L,
        ),
    ),
    "AgADXFoAAuCjggc",
    "CAACAgIAAxkBAAEJny9gituz1_V_uSKBUuG_nhtzEtFOeQACXFoAAuCjggfYjw_KAAGSnkg6BA",
)

internal val DOCUMENT = Vector(
    "BQACAgIAAxkBAAEJnzNgit00IDsKd07OdSeanwz8osecYAACdAwAAueoWEicaPvNdOYEwB8E",
    FullRemoteFileLocation(
        dcId = 2,
        type = FileType.DOCUMENT,
        fileReference = decodeHex("0100099f33608add34203b0a774ece75279a9f0cfca2c79c60"),
        location = RemoteFileLocation.Common(
            id = 5213102278772264052L,
            accessHash = -4610306729174144868L,
        ),
    ),
    "AgADdAwAAueoWEg",
    "BQACAgIAAxkBAAEJnzNgit00IDsKd07OdSeanwz8osecYAACdAwAAueoWEicaPvNdOYEwDoE",
)

internal val THUMBNAIL = Vector(
    "AAMCAgADGQEAAQmfL2CK27PX9X-5IoFS4b-eG3MS0U55AAJcWgAC4KOCB9iPD8oAAZKeSK1c8w4ABAEAB20AA1kCAAIfBA",
    FullRemoteFileLocation(
        dcId = 2,
        type = FileType.THUMBNAIL,
        fileReference = decodeHex("0100099f2f608adbb3d7f57fb9228152e1bf9e1b7312d14e79"),
        location = RemoteFileLocation.Photo(
            id = 541175087705905756L,
            accessHash = 5232780349138767832L,
            source = PhotoSizeSource.Thumbnail(FileType.THUMBNAIL, 'm'),
        ),
    ),
    "AQADXFoAAuCjggdy",
    "AAMCAgADGQEAAQmfL2CK27PX9X-5IoFS4b-eG3MS0U55AAJcWgAC4KOCB9iPD8oAAZKeSAEAB20AAzoE",
)

internal val DOCUMENT_THUMBNAIL = Vector(
    "AAMCAgADGQEAAQmfM2CK3TQgOwp3Ts51J5qfDPyix5xgAAJ0DAAC56hYSJxo-8105gTAT_bYoy4AAwEAB20AA0JBAAIfBA",
    FullRemoteFileLocation(
        dcId = 2,
        type = FileType.THUMBNAIL,
        fileReference = decodeHex("0100099f33608add34203b0a774ece75279a9f0cfca2c79c60"),
        location = RemoteFileLocation.Photo(
            id = 5213102278772264052L,
            accessHash = -4610306729174144868L,
            source = PhotoSizeSource.Thumbnail(FileType.THUMBNAIL, 'm'),
        ),
    ),
    "AQADdAwAAueoWEhy",
    "AAMCAgADGQEAAQmfM2CK3TQgOwp3Ts51J5qfDPyix5xgAAJ0DAAC56hYSJxo-8105gTAAQAHbQADOgQ",
)

internal val PROFILE_PHOTO_BIG = Vector(
    "AQADAgATqfDdly4AAwMAA4siCOX_____AAhKowIAAR4E",
    FullRemoteFileLocation(
        dcId = 2,
        type = FileType.PROFILE_PHOTO,
        fileReference = null,
        location = RemoteFileLocation.Photo(
            id = 0L,
            accessHash = 0L,
            source = PhotoSizeSource.DialogPhotoLegacy(
                big = true,
                id = -452451701L,
                accessHash = 0L,
                volumeId = 200116400297L,
                localId = 172874,
            ),
        ),
    ),
    "AQADqfDdly4AA0qjAgAB",
    "AQADAgATBwADiyII5f____8ACKnw3ZcuAANKowIAAToE",
)

internal val PROFILE_PHOTO_SMALL = Vector(
    "AQADAgATqfDdly4AAwIAA4siCOX_____AAhIowIAAR4E",
    FullRemoteFileLocation(
        dcId = 2,
        type = FileType.PROFILE_PHOTO,
        fileReference = null,
        location = RemoteFileLocation.Photo(
            id = 0L,
            accessHash = 0L,
            source = PhotoSizeSource.DialogPhotoLegacy(
                big = false,
                id = -452451701L,
                accessHash = 0L,
                volumeId = 200116400297L,
                localId = 172872,
            ),
        ),
    ),
    "AQADqfDdly4AA0ijAgAB",
    "AQADAgATBgADiyII5f____8ACKnw3ZcuAANIowIAAToE",
)

internal val CHANNEL_PHOTO_BIG = Vector(
    "AQADAgATySHBDgAEAwAD0npI3Bb___-wfxjpg7QCPf8pBQABHwQ",
    FullRemoteFileLocation(
        dcId = 2,
        type = FileType.PROFILE_PHOTO,
        fileReference = null,
        location = RemoteFileLocation.Photo(
            id = 0L,
            accessHash = 0L,
            source = PhotoSizeSource.DialogPhotoLegacy(
                big = true,
                id = -1001326609710L,
                accessHash = 4396274664911437744L,
                volumeId = 247538121L,
                localId = 338431,
            ),
        ),
    ),
    "AQADySHBDgAE_ykFAAE",
    "AQADAgATBwAD0npI3Bb___-wfxjpg7QCPckhwQ4ABP8pBQABOgQ",
)

internal val CHANNEL_PHOTO_SMALL = Vector(
    "AQADAgATySHBDgAEAgAD0npI3Bb___-wfxjpg7QCPf0pBQABHwQ",
    FullRemoteFileLocation(
        dcId = 2,
        type = FileType.PROFILE_PHOTO,
        fileReference = null,
        location = RemoteFileLocation.Photo(
            id = 0L,
            accessHash = 0L,
            source = PhotoSizeSource.DialogPhotoLegacy(
                big = false,
                id = -1001326609710L,
                accessHash = 4396274664911437744L,
                volumeId = 247538121L,
                localId = 338429,
            ),
        ),
    ),
    "AQADySHBDgAE_SkFAAE",
    "AQADAgATBgAD0npI3Bb___-wfxjpg7QCPckhwQ4ABP0pBQABOgQ",
)

internal val OLD_STICKER = Vector(
    "CAADAQADegAD997LEUiQZafDlhIeAg",
    FullRemoteFileLocation(
        dcId = 1,
        type = FileType.STICKER,
        fileReference = null,
        location = RemoteFileLocation.Common(
            id = 1282363671355326586L,
            accessHash = 2166960137789870152L,
        ),
    ),
    "AgADegAD997LEQ",
    "CAADAQADegAD997LEUiQZafDlhIeOgQ",
)

internal val ALL_VECTORS = listOf(
    STICKER,
    DOCUMENT,
    THUMBNAIL,
    DOCUMENT_THUMBNAIL,
    PROFILE_PHOTO_BIG,
    PROFILE_PHOTO_SMALL,
    CHANNEL_PHOTO_BIG,
    CHANNEL_PHOTO_SMALL,
    OLD_STICKER,
)
