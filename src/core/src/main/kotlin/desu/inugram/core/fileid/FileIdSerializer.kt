package desu.inugram.core.fileid

import java.util.Base64

private val URL_ENCODER: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()

fun serializeFileId(location: FullRemoteFileLocation): String {
    var type = location.type.ordinal
    if (location.location is RemoteFileLocation.Web) type = type or WEB_LOCATION_FLAG
    if (location.fileReference != null) type = type or FILE_REFERENCE_FLAG

    val writer = TlWriter()
    writer.writeInt(type)
    writer.writeInt(location.dcId)
    if (location.fileReference != null) writer.writeBytes(location.fileReference)

    when (val loc = location.location) {
        is RemoteFileLocation.Web -> {
            writer.writeString(loc.url)
            writer.writeLong(loc.accessHash)
        }

        is RemoteFileLocation.Common -> {
            writer.writeLong(loc.id)
            writer.writeLong(loc.accessHash)
        }

        is RemoteFileLocation.Photo -> {
            writer.writeLong(loc.id)
            writer.writeLong(loc.accessHash)
            writePhotoSizeSource(writer, loc.source)
        }
    }

    val encoded = encodeTelegramRle(writer.toByteArray())
    return URL_ENCODER.encodeToString(encoded + byteArrayOf(CURRENT_VERSION.toByte(), PERSISTENT_ID_VERSION.toByte()))
}

private fun writePhotoSizeSource(writer: TlWriter, source: PhotoSizeSource) {
    when (source) {
        is PhotoSizeSource.Legacy -> {
            writer.writeInt(0)
            writer.writeLong(source.secret)
        }

        is PhotoSizeSource.Thumbnail -> {
            writer.writeInt(1)
            writer.writeInt(source.fileType.ordinal)
            writer.writeInt(source.thumbnailType.code)
        }

        is PhotoSizeSource.DialogPhoto -> {
            writer.writeInt(if (source.big) 3 else 2)
            writer.writeLong(source.id)
            writer.writeLong(source.accessHash)
        }

        is PhotoSizeSource.StickerSetThumbnail -> {
            writer.writeInt(4)
            writer.writeLong(source.id)
            writer.writeLong(source.accessHash)
        }

        is PhotoSizeSource.FullLegacy -> {
            writer.writeInt(5)
            writer.writeLong(source.volumeId)
            writer.writeLong(source.secret)
            writer.writeInt(source.localId)
        }

        is PhotoSizeSource.DialogPhotoLegacy -> {
            writer.writeInt(if (source.big) 7 else 6)
            writer.writeLong(source.id)
            writer.writeLong(source.accessHash)
            writer.writeLong(source.volumeId)
            writer.writeInt(source.localId)
        }

        is PhotoSizeSource.StickerSetThumbnailLegacy -> {
            writer.writeInt(8)
            writer.writeLong(source.id)
            writer.writeLong(source.accessHash)
            writer.writeLong(source.volumeId)
            writer.writeInt(source.localId)
        }

        is PhotoSizeSource.StickerSetThumbnailVersion -> {
            writer.writeInt(9)
            writer.writeLong(source.id)
            writer.writeLong(source.accessHash)
            writer.writeInt(source.version)
        }
    }
}

fun serializeUniqueFileId(location: FullRemoteFileLocation): String =
    serializeUniqueFileId(location.type, location.location)

fun serializeUniqueFileId(type: FileType, location: RemoteFileLocation): String {
    val uniqueType = if (location is RemoteFileLocation.Web) {
        UniqueFileIdType.WEB
    } else {
        when (type) {
            FileType.PHOTO,
            FileType.PROFILE_PHOTO,
            FileType.THUMBNAIL,
            FileType.ENCRYPTED_THUMBNAIL,
            FileType.WALLPAPER,
            -> UniqueFileIdType.PHOTO

            FileType.VIDEO,
            FileType.VOICE_NOTE,
            FileType.DOCUMENT,
            FileType.STICKER,
            FileType.AUDIO,
            FileType.ANIMATION,
            FileType.VIDEO_NOTE,
            FileType.BACKGROUND,
            FileType.DOCUMENT_AS_FILE,
            -> UniqueFileIdType.DOCUMENT

            FileType.SECURE_RAW, FileType.SECURE -> UniqueFileIdType.SECURE
            FileType.ENCRYPTED -> UniqueFileIdType.ENCRYPTED
            FileType.TEMP -> UniqueFileIdType.TEMP
        }
    }

    val writer = TlWriter()
    when (location) {
        is RemoteFileLocation.Web -> {
            writer.writeInt(uniqueType.ordinal)
            writer.writeString(location.url)
        }

        is RemoteFileLocation.Common -> {
            writer.writeInt(uniqueType.ordinal)
            writer.writeLong(location.id)
        }

        is RemoteFileLocation.Photo -> writeUniquePhoto(writer, uniqueType, location)
    }

    return URL_ENCODER.encodeToString(encodeTelegramRle(writer.toByteArray()))
}

private fun writeUniquePhoto(writer: TlWriter, uniqueType: UniqueFileIdType, location: RemoteFileLocation.Photo) {
    when (val source = location.source) {
        // tdlib does not implement these two, markers are mtcute-specific
        is PhotoSizeSource.Legacy -> {
            writer.writeInt(uniqueType.ordinal)
            writer.writeInt(100)
            writer.writeLong(source.secret)
        }

        is PhotoSizeSource.StickerSetThumbnail -> {
            writer.writeInt(uniqueType.ordinal)
            writer.writeInt(150)
            writer.writeLong(source.id)
            writer.writeLong(source.accessHash)
        }

        is PhotoSizeSource.DialogPhoto -> {
            writer.writeInt(uniqueType.ordinal)
            writer.writeLong(location.id)
            writer.writeByte(if (source.big) 1 else 0)
        }

        is PhotoSizeSource.Thumbnail -> {
            val code = source.thumbnailType.code
            val subType = when (code) {
                'a'.code -> 0
                'c'.code -> 1
                else -> code + 5
            }
            writer.writeInt(uniqueType.ordinal)
            writer.writeLong(location.id)
            writer.writeByte(subType)
        }

        is PhotoSizeSource.FullLegacy -> writeUniquePhotoVolume(writer, uniqueType, source.volumeId, source.localId)
        is PhotoSizeSource.DialogPhotoLegacy ->
            writeUniquePhotoVolume(writer, uniqueType, source.volumeId, source.localId)
        is PhotoSizeSource.StickerSetThumbnailLegacy ->
            writeUniquePhotoVolume(writer, uniqueType, source.volumeId, source.localId)

        is PhotoSizeSource.StickerSetThumbnailVersion -> {
            writer.writeInt(uniqueType.ordinal)
            writer.writeByte(2)
            writer.writeLong(source.id)
            writer.writeInt(source.version)
        }
    }
}

private fun writeUniquePhotoVolume(
    writer: TlWriter,
    uniqueType: UniqueFileIdType,
    volumeId: Long,
    localId: Int,
) {
    writer.writeInt(uniqueType.ordinal)
    writer.writeLong(volumeId)
    writer.writeInt(localId)
}
