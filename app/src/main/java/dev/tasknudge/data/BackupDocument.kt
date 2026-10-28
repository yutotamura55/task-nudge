package dev.tasknudge.data

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import java.io.FileNotFoundException
import java.io.InputStream
import java.io.OutputStream

/** A file the user picked in the system file picker for one export or import. */
interface BackupDocument {
    fun openOutput(): OutputStream
    fun openInput(): InputStream
    /** Removes a partly written export; providers that cannot delete leave it. */
    fun delete()
}

/** Uses only the one-time grant from the picker; no persistable permission is taken. */
class ContentBackupDocument(private val resolver: ContentResolver, private val uri: Uri) : BackupDocument {
    // "wt" truncates, so a chosen existing file cannot keep old bytes after the new end.
    // Some providers support only "w"; leftover bytes then make the import refuse the file
    // as trailing data rather than restore something else.
    override fun openOutput(): OutputStream {
        val truncating = try { resolver.openOutputStream(uri, "wt") } catch (_: FileNotFoundException) { null }
            catch (_: IllegalArgumentException) { null } catch (_: UnsupportedOperationException) { null }
        return truncating ?: resolver.openOutputStream(uri, "w") ?: throw FileNotFoundException("No output stream")
    }
    override fun openInput(): InputStream =
        resolver.openInputStream(uri) ?: throw FileNotFoundException("No input stream")
    override fun delete() { DocumentsContract.deleteDocument(resolver, uri) }
}
