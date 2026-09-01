package desu.inugram.core.fileid

open class FileIdException(message: String) : RuntimeException(message)

class UnsupportedFileIdException(message: String) : FileIdException(message)

class InvalidFileIdException(message: String) : FileIdException(message)

enum class FileType {
    THUMBNAIL,
    PROFILE_PHOTO,
    PHOTO,
    VOICE_NOTE,
    VIDEO,
    DOCUMENT,
    ENCRYPTED,
    TEMP,
    STICKER,
    AUDIO,
    ANIMATION,
    ENCRYPTED_THUMBNAIL,
    WALLPAPER,
    VIDEO_NOTE,
    SECURE_RAW,
    SECURE,
    BACKGROUND,
    DOCUMENT_AS_FILE,
    ;

    companion object {
        fun fromId(id: Int): FileType? = entries.getOrNull(id)
    }
}

enum class UniqueFileIdType {
    WEB,
    PHOTO,
    DOCUMENT,
    SECURE,
    ENCRYPTED,
    TEMP,
    ;

    companion object {
        fun fromId(id: Int): UniqueFileIdType? = entries.getOrNull(id)
    }
}

sealed class PhotoSizeSource {
    data class Legacy(val secret: Long) : PhotoSizeSource()

    data class Thumbnail(val fileType: FileType, val thumbnailType: Char) : PhotoSizeSource()

    data class DialogPhoto(val big: Boolean, val id: Long, val accessHash: Long) : PhotoSizeSource()

    data class StickerSetThumbnail(val id: Long, val accessHash: Long) : PhotoSizeSource()

    data class FullLegacy(val volumeId: Long, val secret: Long, val localId: Int) : PhotoSizeSource()

    data class DialogPhotoLegacy(
        val big: Boolean,
        val id: Long,
        val accessHash: Long,
        val volumeId: Long,
        val localId: Int,
    ) : PhotoSizeSource()

    data class StickerSetThumbnailLegacy(
        val id: Long,
        val accessHash: Long,
        val volumeId: Long,
        val localId: Int,
    ) : PhotoSizeSource()

    data class StickerSetThumbnailVersion(
        val id: Long,
        val accessHash: Long,
        val version: Int,
    ) : PhotoSizeSource()
}

sealed class RemoteFileLocation {
    data class Web(val url: String, val accessHash: Long) : RemoteFileLocation()

    data class Photo(val id: Long, val accessHash: Long, val source: PhotoSizeSource) : RemoteFileLocation()

    data class Common(val id: Long, val accessHash: Long) : RemoteFileLocation()
}

class FullRemoteFileLocation(
    val dcId: Int,
    val type: FileType,
    val fileReference: ByteArray?,
    val location: RemoteFileLocation,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is FullRemoteFileLocation) return false
        return dcId == other.dcId &&
            type == other.type &&
            location == other.location &&
            fileReference.contentEquals(other.fileReference)
    }

    override fun hashCode(): Int {
        var result = dcId
        result = 31 * result + type.hashCode()
        result = 31 * result + location.hashCode()
        result = 31 * result + (fileReference?.contentHashCode() ?: 0)
        return result
    }

    override fun toString(): String {
        val reference = fileReference?.joinToString("") { "%02x".format(it) }
        return "FullRemoteFileLocation(dcId=$dcId, type=$type, fileReference=$reference, location=$location)"
    }
}

sealed class UniquePhotoLocation {
    data class Id(val id: Long, val subType: Int) : UniquePhotoLocation()

    data class Legacy(val secret: Long) : UniquePhotoLocation()

    data class VolumeId(val volumeId: Long, val localId: Int) : UniquePhotoLocation()

    data class StickerSet(val stickerSetId: Long, val stickerSetAccessHash: Long) : UniquePhotoLocation()

    data class StickerSetVersion(val stickerSetId: Long, val stickerSetVersion: Int) : UniquePhotoLocation()
}

sealed class ParsedUniqueFileId {
    data class Web(val url: String) : ParsedUniqueFileId()

    data class Photo(val location: UniquePhotoLocation) : ParsedUniqueFileId()

    data class Document(val id: Long) : ParsedUniqueFileId()

    data class Secure(val id: Long) : ParsedUniqueFileId()

    data class Encrypted(val id: Long) : ParsedUniqueFileId()

    data class Temp(val id: Long) : ParsedUniqueFileId()
}

internal const val PERSISTENT_ID_VERSION_OLD = 2
internal const val PERSISTENT_ID_VERSION = 4
internal const val CURRENT_VERSION = 58
internal const val WEB_LOCATION_FLAG = 1 shl 24
internal const val FILE_REFERENCE_FLAG = 1 shl 25
