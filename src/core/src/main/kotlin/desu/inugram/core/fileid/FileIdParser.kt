package desu.inugram.core.fileid

import java.util.Base64

private val URL_DECODER: Base64.Decoder = Base64.getUrlDecoder()

fun parseFileId(fileId: String): FullRemoteFileLocation = parseFileId(decodeBase64(fileId))

fun parseFileId(fileId: ByteArray): FullRemoteFileLocation {
    if (fileId.isEmpty()) throw InvalidFileIdException("Empty file ID")

    return when (val version = fileId[fileId.size - 1].toInt() and 0xff) {
        PERSISTENT_ID_VERSION_OLD -> parsePersistentId(fileId.copyOfRange(0, fileId.size - 1), 0)
        PERSISTENT_ID_VERSION -> {
            if (fileId.size < 2) throw InvalidFileIdException("Truncated file ID")
            val subversion = fileId[fileId.size - 2].toInt() and 0xff
            parsePersistentId(fileId.copyOfRange(0, fileId.size - 2), subversion)
        }

        else -> throw UnsupportedFileIdException("Unsupported file ID version: $version")
    }
}

private fun parsePersistentId(binary: ByteArray, version: Int): FullRemoteFileLocation {
    if (version > CURRENT_VERSION) {
        throw UnsupportedFileIdException("Unsupported file ID v3 subversion: $version")
    }

    val decoded = decodeTelegramRle(binary)
    val reader = TlReader(decoded)

    var rawType = reader.readInt()
    val isWeb = rawType and WEB_LOCATION_FLAG != 0
    val hasFileReference = rawType and FILE_REFERENCE_FLAG != 0
    rawType = rawType and WEB_LOCATION_FLAG.inv() and FILE_REFERENCE_FLAG.inv()

    val fileType = FileType.fromId(rawType)
        ?: throw UnsupportedFileIdException("Unsupported file type: $rawType")

    val dcId = reader.readInt()

    var fileReference = if (hasFileReference) reader.readBytes() else null
    // tdlib marks an unusable file reference with a single '#' byte
    if (fileReference != null && fileReference.size == 1 && fileReference[0].toInt() == '#'.code) {
        fileReference = null
    }

    val location = if (isWeb) {
        RemoteFileLocation.Web(reader.readString(), reader.readLong())
    } else {
        when (fileType) {
            FileType.PHOTO,
            FileType.PROFILE_PHOTO,
            FileType.THUMBNAIL,
            FileType.ENCRYPTED_THUMBNAIL,
            FileType.WALLPAPER,
            -> parsePhotoFileLocation(reader, version).also { validatePhotoSource(it.source, fileType) }

            FileType.VIDEO,
            FileType.VOICE_NOTE,
            FileType.DOCUMENT,
            FileType.STICKER,
            FileType.AUDIO,
            FileType.ANIMATION,
            FileType.ENCRYPTED,
            FileType.VIDEO_NOTE,
            FileType.SECURE_RAW,
            FileType.SECURE,
            FileType.BACKGROUND,
            FileType.DOCUMENT_AS_FILE,
            -> RemoteFileLocation.Common(reader.readLong(), reader.readLong())

            FileType.TEMP -> throw UnsupportedFileIdException("Invalid file type: $fileType")
        }
    }

    return FullRemoteFileLocation(dcId, fileType, fileReference, location)
}

private fun validatePhotoSource(source: PhotoSizeSource, fileType: FileType) {
    when (source) {
        is PhotoSizeSource.Thumbnail -> {
            val validOwner = fileType == FileType.PHOTO ||
                fileType == FileType.THUMBNAIL ||
                fileType == FileType.ENCRYPTED_THUMBNAIL
            if (source.fileType != fileType || !validOwner) {
                throw InvalidFileIdException("Invalid FileType in PhotoRemoteFileLocation Thumbnail")
            }
        }

        is PhotoSizeSource.DialogPhoto, is PhotoSizeSource.DialogPhotoLegacy -> {
            if (fileType != FileType.PROFILE_PHOTO) {
                throw InvalidFileIdException("Invalid FileType in PhotoRemoteFileLocation DialogPhoto")
            }
        }

        is PhotoSizeSource.StickerSetThumbnail,
        is PhotoSizeSource.StickerSetThumbnailLegacy,
        is PhotoSizeSource.StickerSetThumbnailVersion,
        -> {
            if (fileType != FileType.THUMBNAIL) {
                throw InvalidFileIdException("Invalid FileType in PhotoRemoteFileLocation StickerSetThumbnail")
            }
        }

        else -> Unit
    }
}

private fun parsePhotoFileLocation(reader: TlReader, version: Int): RemoteFileLocation.Photo {
    val id = reader.readLong()
    val accessHash = reader.readLong()

    if (version >= 32) {
        return RemoteFileLocation.Photo(id, accessHash, parsePhotoSizeSource(reader))
    }

    val volumeId = reader.readLong()
    var localId = 0
    var source = if (version >= 22) {
        parsePhotoSizeSource(reader).also { localId = reader.readInt() }
    } else {
        val secret = reader.readLong()
        PhotoSizeSource.FullLegacy(volumeId, secret, reader.readInt())
    }

    source = when (source) {
        is PhotoSizeSource.Legacy -> {
            val secret = reader.readLong()
            PhotoSizeSource.FullLegacy(volumeId, secret, reader.readInt())
        }

        is PhotoSizeSource.FullLegacy, is PhotoSizeSource.Thumbnail -> source

        is PhotoSizeSource.DialogPhoto -> PhotoSizeSource.DialogPhotoLegacy(
            source.big,
            source.id,
            source.accessHash,
            volumeId,
            localId,
        )

        is PhotoSizeSource.StickerSetThumbnail -> PhotoSizeSource.StickerSetThumbnailLegacy(
            source.id,
            source.accessHash,
            volumeId,
            localId,
        )

        else -> throw InvalidFileIdException("Invalid PhotoSizeSource in legacy PhotoRemoteFileLocation")
    }

    return RemoteFileLocation.Photo(id, accessHash, source)
}

private fun parsePhotoSizeSource(reader: TlReader): PhotoSizeSource {
    return when (val variant = reader.readInt()) {
        0 -> PhotoSizeSource.Legacy(reader.readLong())

        1 -> {
            val rawFileType = reader.readInt()
            val fileType = FileType.fromId(rawFileType)
                ?: throw UnsupportedFileIdException("Unsupported file type: $rawFileType")
            val thumbnailType = reader.readInt()
            if (thumbnailType < 0 || thumbnailType > 255) {
                throw InvalidFileIdException("Wrong thumbnail type: $thumbnailType")
            }
            PhotoSizeSource.Thumbnail(fileType, thumbnailType.toChar())
        }

        2, 3 -> PhotoSizeSource.DialogPhoto(variant == 3, reader.readLong(), reader.readLong())

        4 -> PhotoSizeSource.StickerSetThumbnail(reader.readLong(), reader.readLong())

        5 -> PhotoSizeSource.FullLegacy(reader.readLong(), reader.readLong(), reader.readInt())
            .also { if (it.localId < 0) throw InvalidFileIdException("Wrong local_id (< 0)") }

        6, 7 -> PhotoSizeSource.DialogPhotoLegacy(
            variant == 7,
            reader.readLong(),
            reader.readLong(),
            reader.readLong(),
            reader.readInt(),
        ).also { if (it.localId < 0) throw InvalidFileIdException("Wrong local_id (< 0)") }

        8 -> PhotoSizeSource.StickerSetThumbnailLegacy(
            reader.readLong(),
            reader.readLong(),
            reader.readLong(),
            reader.readInt(),
        ).also { if (it.localId < 0) throw InvalidFileIdException("Wrong local_id (< 0)") }

        9 -> PhotoSizeSource.StickerSetThumbnailVersion(reader.readLong(), reader.readLong(), reader.readInt())

        else -> throw UnsupportedFileIdException("Unsupported photo size source: $variant")
    }
}

fun parseUniqueFileId(fileId: String): ParsedUniqueFileId = parseUniqueFileId(decodeBase64(fileId))

fun parseUniqueFileId(fileId: ByteArray): ParsedUniqueFileId {
    val binary = decodeTelegramRle(fileId)
    val reader = TlReader(binary)
    val rawType = reader.readInt()
    val type = UniqueFileIdType.fromId(rawType)
        ?: throw UnsupportedFileIdException("Unsupported unique file ID type: $rawType")

    return when (type) {
        UniqueFileIdType.WEB -> ParsedUniqueFileId.Web(reader.readString())
        UniqueFileIdType.PHOTO -> ParsedUniqueFileId.Photo(parseUniquePhotoLocation(reader))
        UniqueFileIdType.DOCUMENT -> ParsedUniqueFileId.Document(reader.readLong())
        UniqueFileIdType.SECURE -> ParsedUniqueFileId.Secure(reader.readLong())
        UniqueFileIdType.ENCRYPTED -> ParsedUniqueFileId.Encrypted(reader.readLong())
        UniqueFileIdType.TEMP -> ParsedUniqueFileId.Temp(reader.readLong())
    }
}

private fun parseUniquePhotoLocation(reader: TlReader): UniquePhotoLocation {
    when (reader.remaining) {
        13 -> if (reader.peekByte() == 2) {
            reader.pos += 1
            return UniquePhotoLocation.StickerSetVersion(reader.readLong(), reader.readInt())
        }

        9 -> {
            val id = reader.readLong()
            return UniquePhotoLocation.Id(id, reader.peekByte())
        }

        12 -> {
            if (reader.readInt() == 100) return UniquePhotoLocation.Legacy(reader.readLong())
            reader.pos -= 4
            return UniquePhotoLocation.VolumeId(reader.readLong(), reader.readInt())
        }

        20 -> {
            val marker = reader.readInt()
            if (marker == 150) {
                return UniquePhotoLocation.StickerSet(reader.readLong(), reader.readLong())
            }
            throw InvalidFileIdException("Unexpected photo unique file ID marker: $marker")
        }
    }

    throw InvalidFileIdException("Unexpected photo unique file ID size: ${reader.remaining}")
}

private fun decodeBase64(value: String): ByteArray = try {
    URL_DECODER.decode(value)
} catch (e: IllegalArgumentException) {
    throw InvalidFileIdException("Malformed base64 in file ID: ${e.message}")
}
