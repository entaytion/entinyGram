package desu.inugram.core.fileid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class FileIdTest {
    @Test
    fun parsesKnownFileIds() {
        for (vector in ALL_VECTORS) {
            assertEquals(vector.fileId, vector.location, parseFileId(vector.fileId))
        }
    }

    @Test
    fun serializesKnownFileIds() {
        for (vector in ALL_VECTORS) {
            assertEquals(vector.fileId, vector.reserialized, serializeFileId(vector.location))
        }
    }

    @Test
    fun reserializedFileIdsParseBack() {
        for (vector in ALL_VECTORS) {
            assertEquals(vector.fileId, vector.location, parseFileId(vector.reserialized))
        }
    }

    @Test
    fun serializesKnownUniqueFileIds() {
        for (vector in ALL_VECTORS) {
            assertEquals(vector.fileId, vector.uniqueFileId, serializeUniqueFileId(vector.location))
        }
    }

    @Test
    fun parsesUniqueIdsForDocuments() {
        assertEquals(
            ParsedUniqueFileId.Document(1282363671355326586L),
            parseUniqueFileId("AgADegAD997LEQ"),
        )
        assertEquals(
            ParsedUniqueFileId.Document(5213102278772264052L),
            parseUniqueFileId("AgADdAwAAueoWEg"),
        )
        assertEquals(
            ParsedUniqueFileId.Document(541175087705905756L),
            parseUniqueFileId("AgADXFoAAuCjggc"),
        )
    }

    @Test
    fun parsesUniqueIdsForThumbnails() {
        assertEquals(
            ParsedUniqueFileId.Photo(UniquePhotoLocation.Id(5213102278772264052L, 114)),
            parseUniqueFileId("AQADdAwAAueoWEhy"),
        )
    }

    @Test
    fun parsesUniqueIdsForProfilePictures() {
        assertEquals(
            ParsedUniqueFileId.Photo(UniquePhotoLocation.VolumeId(247538121L, 338431)),
            parseUniqueFileId("AQADySHBDgAE_ykFAAE"),
        )
        assertEquals(
            ParsedUniqueFileId.Photo(UniquePhotoLocation.VolumeId(247538121L, 338429)),
            parseUniqueFileId("AQADySHBDgAE_SkFAAE"),
        )
    }

    @Test
    fun rejectsUnsupportedVersion() {
        assertThrows(UnsupportedFileIdException::class.java) {
            parseFileId("CAADAQADegAD997LEUiQZafDlhIeAQ")
        }
    }

    @Test
    fun rejectsTruncatedFileId() {
        assertThrows(FileIdException::class.java) {
            parseFileId(byteArrayOf(PERSISTENT_ID_VERSION_OLD.toByte()))
        }
    }
}
