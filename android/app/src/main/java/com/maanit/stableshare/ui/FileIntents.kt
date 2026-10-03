package com.maanit.stableshare.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import com.maanit.stableshare.data.db.TransferEntity
import java.io.File
import java.util.Locale

/**
 * "Open file" and "Share file" for completed downloads. The file goes out as a FileProvider
 * content:// URI with a read grant, through the system chooser (which also handles "no app").
 */
object FileIntents {

    private fun contentUri(context: Context, row: TransferEntity): Uri? {
        val path = Uri.parse(row.localUri).path ?: return null
        val file = File(path)
        if (!file.isFile) return null
        return FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    }

    private fun mimeOf(row: TransferEntity): String {
        row.mimeType?.let { return it }
        val ext = row.fileName.substringAfterLast('.', "").lowercase(Locale.ROOT)
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
    }

    fun open(context: Context, row: TransferEntity) {
        val uri = contentUri(context, row) ?: return
        val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mimeOf(row)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        start(context, Intent.createChooser(view, null))
    }

    fun share(context: Context, row: TransferEntity) {
        val uri = contentUri(context, row) ?: return
        val send = Intent(Intent.ACTION_SEND).setType(mimeOf(row)).putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        start(context, Intent.createChooser(send, null))
    }

    private fun start(context: Context, intent: Intent) {
        try {
            context.startActivity(intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        } catch (_: ActivityNotFoundException) {
            // The chooser itself tells the user when nothing can handle the file.
        }
    }
}
