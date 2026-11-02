package com.printpro.app.print

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
import com.printpro.app.model.ColorMode
import com.printpro.app.model.DuplexMode
import com.printpro.app.model.Orientation
import com.printpro.app.model.PaperSize
import com.printpro.app.model.PrintSettings
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * Solution de secours : envoie la mise en page préparée au service d'impression Android
 * (HP Print Service Plugin, Mopria ou service par défaut).
 */
object SystemPrint {
    fun print(activity: Activity, pdf: File, jobName: String, s: PrintSettings) {
        val pm = activity.getSystemService(Context.PRINT_SERVICE) as PrintManager
        val base = when (s.paper) {
            PaperSize.A4 -> PrintAttributes.MediaSize.ISO_A4
            PaperSize.A3 -> PrintAttributes.MediaSize.ISO_A3
            PaperSize.A5 -> PrintAttributes.MediaSize.ISO_A5
            PaperSize.LETTER -> PrintAttributes.MediaSize.NA_LETTER
            PaperSize.LEGAL -> PrintAttributes.MediaSize.NA_LEGAL
            PaperSize.PHOTO_4X6 -> PrintAttributes.MediaSize.NA_INDEX_4X6
        }
        val media = if (s.orientation == Orientation.LANDSCAPE) base.asLandscape() else base.asPortrait()
        val attrs = PrintAttributes.Builder()
            .setMediaSize(media)
            .setColorMode(if (s.color == ColorMode.MONO) PrintAttributes.COLOR_MODE_MONOCHROME else PrintAttributes.COLOR_MODE_COLOR)
            .setDuplexMode(
                when (s.duplex) {
                    DuplexMode.LONG_EDGE -> PrintAttributes.DUPLEX_MODE_LONG_EDGE
                    DuplexMode.SHORT_EDGE -> PrintAttributes.DUPLEX_MODE_SHORT_EDGE
                    else -> PrintAttributes.DUPLEX_MODE_NONE
                }
            )
            .setMinMargins(PrintAttributes.Margins.NO_MARGINS)
            .build()
        pm.print(jobName, FileAdapter(pdf, jobName), attrs)
    }

    private class FileAdapter(private val file: File, private val name: String) : PrintDocumentAdapter() {
        override fun onLayout(
            oldAttributes: PrintAttributes?, newAttributes: PrintAttributes,
            cancellationSignal: CancellationSignal?, callback: LayoutResultCallback, extras: Bundle?,
        ) {
            if (cancellationSignal?.isCanceled == true) { callback.onLayoutCancelled(); return }
            val info = PrintDocumentInfo.Builder(name.substringBeforeLast('.').ifBlank { "document" } + ".pdf")
                .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                .build()
            callback.onLayoutFinished(info, oldAttributes != newAttributes)
        }

        override fun onWrite(
            pages: Array<out PageRange>, destination: ParcelFileDescriptor,
            cancellationSignal: CancellationSignal?, callback: WriteResultCallback,
        ) {
            try {
                FileInputStream(file).use { i -> FileOutputStream(destination.fileDescriptor).use { o -> i.copyTo(o) } }
                callback.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
            } catch (e: Exception) {
                callback.onWriteFailed(e.message)
            }
        }
    }
}
