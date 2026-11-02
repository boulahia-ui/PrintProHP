package com.printpro.app.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.provider.OpenableColumns
import android.text.Layout
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.text.style.UnderlineSpan
import android.util.Xml
import androidx.exifinterface.media.ExifInterface
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.multipdf.PDFMergerUtility
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipInputStream
import kotlin.math.max
import kotlin.math.min

/**
 * Convertit les fichiers choisis (PDF, images, Word .docx) en un seul PDF source.
 * Plusieurs fichiers sont fusionnés dans l'ordre de sélection.
 */
class DocumentConverter(private val context: Context) {

    data class Converted(val file: File, val name: String, val pageCount: Int)

    private enum class Kind { PDF, IMAGE, DOCX, DOC, UNKNOWN }

    fun displayName(uri: Uri): String = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }.getOrNull() ?: uri.lastPathSegment ?: "document"

    private fun kindOf(uri: Uri): Kind {
        val mime = context.contentResolver.getType(uri)?.lowercase().orEmpty()
        val name = displayName(uri).lowercase()
        return when {
            mime == "application/pdf" || name.endsWith(".pdf") -> Kind.PDF
            mime.startsWith("image/") ||
                Regex(""".*\.(jpe?g|png|webp|bmp|gif|heic|heif)$""").matches(name) -> Kind.IMAGE
            mime == DOCX_MIME || name.endsWith(".docx") -> Kind.DOCX
            mime == "application/msword" || name.endsWith(".doc") -> Kind.DOC
            else -> Kind.UNKNOWN
        }
    }

    fun convert(uris: List<Uri>, workDir: File): Converted {
        require(uris.isNotEmpty())
        workDir.mkdirs()
        val stamp = System.nanoTime()
        val parts = mutableListOf<File>()
        var images = mutableListOf<Uri>()

        fun newPart() = File(workDir, "part_${stamp}_${parts.size}.pdf")
        fun flushImages() {
            if (images.isEmpty()) return
            val f = newPart(); imagesToPdf(images, f); parts += f
            images = mutableListOf()
        }

        try {
            for (uri in uris) {
                when (kindOf(uri)) {
                    Kind.IMAGE -> images += uri
                    Kind.PDF -> { flushImages(); val f = newPart(); copy(uri, f); parts += f }
                    Kind.DOCX -> { flushImages(); val f = newPart(); docxToPdf(uri, f); parts += f }
                    Kind.DOC -> throw IOException(
                        "Le format Word ancien (.doc) n'est pas pris en charge. " +
                            "Enregistrez le fichier en .docx ou en PDF."
                    )
                    Kind.UNKNOWN -> throw IOException("Format non pris en charge : ${displayName(uri)}")
                }
            }
            flushImages()

            val out = File(workDir, "source_$stamp.pdf")
            if (parts.size == 1) {
                parts[0].copyTo(out, overwrite = true)
            } else {
                val merger = PDFMergerUtility()
                parts.forEach { merger.addSource(it) }
                merger.destinationFileName = out.absolutePath
                merger.mergeDocuments(MemoryUsageSetting.setupMainMemoryOnly())
            }

            // Retire les restrictions des PDF chiffrés sans mot de passe d'ouverture
            var pages = 0
            var decrypted: File? = null
            try {
                PDDocument.load(out).use { d ->
                    pages = d.numberOfPages
                    if (d.isEncrypted) {
                        d.setAllSecurityToBeRemoved(true)
                        decrypted = File(workDir, "dec_$stamp.pdf").also { d.save(it) }
                    }
                    Unit
                }
            } catch (e: InvalidPasswordException) {
                throw IOException("Ce PDF est protégé par un mot de passe.")
            }
            decrypted?.let { it.copyTo(out, overwrite = true); it.delete() }
            if (pages == 0) throw IOException("Le document ne contient aucune page.")

            val name = if (uris.size == 1) displayName(uris[0]) else "${uris.size} fichiers"
            return Converted(out, name, pages)
        } finally {
            parts.forEach { it.delete() }
        }
    }

    private fun copy(uri: Uri, dest: File) {
        val input = context.contentResolver.openInputStream(uri)
            ?: throw IOException("Lecture impossible : ${displayName(uri)}")
        input.use { i -> dest.outputStream().use { o -> i.copyTo(o) } }
    }

    // ---------- Images → PDF (une image par page, orientation selon l'image) ----------

    private fun imagesToPdf(uris: List<Uri>, out: File) {
        val doc = PdfDocument()
        try {
            uris.forEachIndexed { i, uri ->
                val bmp = decodeBitmap(uri, 2400)
                    ?: throw IOException("Image illisible : ${displayName(uri)}")
                val landscape = bmp.width > bmp.height
                val pw = if (landscape) 842 else 595
                val ph = if (landscape) 595 else 842
                val page = doc.startPage(PdfDocument.PageInfo.Builder(pw, ph, i + 1).create())
                val s = min(pw.toFloat() / bmp.width, ph.toFloat() / bmp.height)
                val dw = bmp.width * s
                val dh = bmp.height * s
                val dst = RectF((pw - dw) / 2, (ph - dh) / 2, (pw + dw) / 2, (ph + dh) / 2)
                page.canvas.drawBitmap(bmp, null, dst, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
                doc.finishPage(page)
                bmp.recycle()
            }
            out.outputStream().use { doc.writeTo(it) }
        } finally {
            doc.close()
        }
    }

    private fun decodeBitmap(uri: Uri, maxDim: Int): Bitmap? {
        val cr = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / sample > maxDim) sample *= 2
        val bmp = cr.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: return null
        val rotation = cr.openInputStream(uri)?.use {
            runCatching { ExifInterface(it).rotationDegrees }.getOrDefault(0)
        } ?: 0
        if (rotation == 0) return bmp
        val m = Matrix().apply { postRotate(rotation.toFloat()) }
        val rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
        if (rotated !== bmp) bmp.recycle()
        return rotated
    }

    // ---------- Word .docx → PDF (texte, gras/italique/souligné, titres, listes, tableaux simples) ----------

    private fun docxToPdf(uri: Uri, out: File) {
        val input = context.contentResolver.openInputStream(uri)
            ?: throw IOException("Lecture impossible : ${displayName(uri)}")
        var sections: List<SpannableStringBuilder>? = null
        ZipInputStream(input).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (entry.name == "word/document.xml") { sections = DocxText.parse(zip); break }
                entry = zip.nextEntry
            }
        }
        val pages = sections ?: throw IOException("Fichier Word invalide ou endommagé.")
        renderText(pages, out)
    }

    private fun renderText(sections: List<CharSequence>, out: File) {
        val pageW = 595; val pageH = 842; val margin = 57f // ≈ 2 cm
        val contentW = (pageW - 2 * margin).toInt()
        val contentH = pageH - 2 * margin
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 11f; color = android.graphics.Color.BLACK }
        val doc = PdfDocument()
        var pageNum = 0
        try {
            val useful = sections.filter { it.isNotBlank() }.ifEmpty { listOf(" ") }
            for (text in useful) {
                val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, contentW)
                    .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                    .setLineSpacing(2f, 1.1f)
                    .setIncludePad(false)
                    .build()
                var first = 0
                while (first < layout.lineCount) {
                    val top = layout.getLineTop(first)
                    var last = first
                    while (last + 1 < layout.lineCount && layout.getLineBottom(last + 1) - top <= contentH) last++
                    val page = doc.startPage(PdfDocument.PageInfo.Builder(pageW, pageH, ++pageNum).create())
                    val c = page.canvas
                    c.save()
                    c.translate(margin, margin - top)
                    c.clipRect(0f, top.toFloat(), contentW.toFloat(), layout.getLineBottom(last).toFloat())
                    layout.draw(c)
                    c.restore()
                    doc.finishPage(page)
                    first = last + 1
                }
            }
            out.outputStream().use { doc.writeTo(it) }
        } finally {
            doc.close()
        }
    }

    companion object {
        const val DOCX_MIME = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
    }
}

/** Lecture simplifiée de word/document.xml. Retourne une section par saut de page explicite. */
internal object DocxText {
    fun parse(input: InputStream): List<SpannableStringBuilder> {
        val p = Xml.newPullParser()
        p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        p.setInput(input, "UTF-8")

        val sections = mutableListOf<SpannableStringBuilder>()
        var sb = SpannableStringBuilder()
        var bold = false; var italic = false; var underline = false
        var inText = false; var inRunProps = false; var inParaProps = false
        var paraStart = 0; var heading = 0; var listItem = false
        var tableDepth = 0; var cellIndex = 0

        var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            when (ev) {
                XmlPullParser.START_TAG -> when (p.name) {
                    "w:p" -> { paraStart = sb.length; heading = 0; listItem = false }
                    "w:pPr" -> inParaProps = true
                    "w:pStyle" -> heading = headingLevel(p.getAttributeValue(null, "w:val"))
                    "w:numPr" -> listItem = true
                    "w:r" -> { bold = false; italic = false; underline = false }
                    "w:rPr" -> inRunProps = true
                    "w:b" -> if (inRunProps) bold = isOn(p)
                    "w:i" -> if (inRunProps) italic = isOn(p)
                    "w:u" -> if (inRunProps) underline = p.getAttributeValue(null, "w:val") != "none"
                    "w:t" -> inText = true
                    "w:tab" -> if (!inParaProps) sb.append('\t')
                    "w:br", "w:cr" -> {
                        if (p.getAttributeValue(null, "w:type") == "page") {
                            sections += sb; sb = SpannableStringBuilder(); paraStart = 0
                        } else sb.append('\n')
                    }
                    "w:tbl" -> tableDepth++
                    "w:tr" -> cellIndex = 0
                    "w:tc" -> { if (cellIndex > 0) sb.append("   │   "); cellIndex++ }
                }
                XmlPullParser.TEXT -> if (inText) {
                    val start = sb.length
                    sb.append(p.text)
                    val end = sb.length
                    if (end > start) {
                        val style = when {
                            bold && italic -> Typeface.BOLD_ITALIC
                            bold -> Typeface.BOLD
                            italic -> Typeface.ITALIC
                            else -> -1
                        }
                        if (style >= 0) sb.setSpan(StyleSpan(style), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                        if (underline) sb.setSpan(UnderlineSpan(), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                }
                XmlPullParser.END_TAG -> when (p.name) {
                    "w:t" -> inText = false
                    "w:rPr" -> inRunProps = false
                    "w:pPr" -> inParaProps = false
                    "w:p" -> {
                        if (paraStart > sb.length) paraStart = sb.length
                        if (listItem) sb.insert(paraStart, "•  ")
                        if (heading > 0 && sb.length > paraStart) {
                            val size = when (heading) { 1 -> 1.6f; 2 -> 1.35f; 3 -> 1.2f; else -> 1.1f }
                            sb.setSpan(RelativeSizeSpan(size), paraStart, sb.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                            sb.setSpan(StyleSpan(Typeface.BOLD), paraStart, sb.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                        }
                        sb.append(if (tableDepth > 0) " " else "\n")
                    }
                    "w:tr" -> sb.append('\n')
                    "w:tbl" -> { tableDepth--; sb.append('\n') }
                }
            }
            ev = p.next()
        }
        sections += sb
        return sections
    }

    private fun isOn(p: XmlPullParser): Boolean {
        val v = p.getAttributeValue(null, "w:val") ?: return true
        return v !in setOf("0", "false", "off", "none")
    }

    private fun headingLevel(value: String?): Int {
        val s = value?.lowercase() ?: return 0
        return when {
            s == "title" || s == "titre" -> 1
            s.startsWith("heading") || s.startsWith("titre") ->
                s.filter { it.isDigit() }.toIntOrNull()?.coerceIn(1, 6) ?: 1
            else -> 0
        }
    }
}
