package desu.inugram.helpers.chat

import android.content.ContentResolver
import android.database.Cursor
import android.net.Uri
import android.provider.ContactsContract
import org.telegram.messenger.FileLog

object ContactLinkHelper {
    private val PROJECTION = arrayOf(
        ContactsContract.Data.DATA4,
        ContactsContract.Data.DATA1,
        ContactsContract.RawContacts.ACCOUNT_NAME,
        ContactsContract.Data.MIMETYPE,
    )

    // entiny: newer Android hides columns from a null projection, so ask for the ones we need by name
    @JvmStatic
    fun query(resolver: ContentResolver, uri: Uri?): Cursor? {
        if (uri == null) return null
        return try {
            resolver.query(uri, PROJECTION, null, null, null)
        } catch (e: Throwable) {
            FileLog.e(e)
            try {
                resolver.query(uri, null, null, null, null)
            } catch (e2: Throwable) {
                FileLog.e(e2)
                null
            }
        }
    }

    @JvmStatic
    fun userId(cursor: Cursor): Long {
        val primary = long(cursor, ContactsContract.Data.DATA4)
        return if (primary != 0L) primary else long(cursor, ContactsContract.Data.DATA1)
    }

    @JvmStatic
    fun string(cursor: Cursor, column: String): String? {
        val index = cursor.getColumnIndex(column)
        return if (index >= 0) cursor.getString(index) else null
    }

    private fun long(cursor: Cursor, column: String): Long {
        val index = cursor.getColumnIndex(column)
        return if (index >= 0) cursor.getLong(index) else 0L
    }
}
