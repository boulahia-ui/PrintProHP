package com.printpro.app.pdf

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

/** Rendu des faces imposées pour l'aperçu (PdfRenderer n'est pas thread-safe : accès sérialisé). */
class PreviewRenderer {
    private val mutex = Mutex()
    private var fd: ParcelFileDescriptor? = null
    private var renderer: PdfRenderer? = null
    private var file: File? = null

    suspend fun open(newFile: File) {
        mutex.withLock {
            withContext(Dispatchers.IO) {
                closeInternal(deleteFile = true)
                val d = ParcelFileDescriptor.open(newFile, ParcelFileDescriptor.MODE_READ_ONLY)
                fd = d
                renderer = PdfRenderer(d)
                file = newFile
            }
        }
    }

    suspend fun render(index: Int, widthPx: Int): Bitmap? = mutex.withLock {
        withContext(Dispatchers.IO) {
            val r = renderer ?: return@withContext null
            if (index !in 0 until r.pageCount) return@withContext null
            val page = r.openPage(index)
            try {
                val h = (widthPx.toFloat() * page.height / page.width).roundToInt().coerceAtLeast(1)
                Bitmap.createBitmap(widthPx, h, Bitmap.Config.ARGB_8888).also {
                    it.eraseColor(Color.WHITE)
                    page.render(it, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                }
            } finally {
                page.close()
            }
        }
    }

    fun close() = closeInternal(deleteFile = false)

    private fun closeInternal(deleteFile: Boolean) {
        runCatching { renderer?.close() }
        runCatching { fd?.close() }
        if (deleteFile) file?.delete()
        renderer = null; fd = null; file = null
    }
}
