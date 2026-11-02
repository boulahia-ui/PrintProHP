package com.printpro.app.pdf

import com.printpro.app.model.LayoutMode
import com.printpro.app.model.Orientation
import com.printpro.app.model.PrintSettings
import com.printpro.app.model.ScaleMode
import com.printpro.app.model.mm
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.multipdf.LayerUtility
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.graphics.color.PDColor
import com.tom_roush.pdfbox.pdmodel.graphics.color.PDDeviceGray
import com.tom_roush.pdfbox.pdmodel.graphics.form.PDFormXObject
import com.tom_roush.pdfbox.util.Matrix
import java.io.File
import kotlin.math.min

/**
 * Prépare la mise en page finale : N pages par feuille, répétition, marges,
 * échelle, orientation, format. Le contenu reste vectoriel (texte net).
 */
object Imposer {
    private const val CELL_GAP_MM = 3f

    fun sheetSize(s: PrintSettings): Pair<Float, Float> {
        val w = s.paper.widthPt
        val h = s.paper.heightPt
        return if (s.orientation == Orientation.LANDSCAPE) h to w else w to h
    }

    /** (colonnes, lignes) pour une feuille portrait ; inversé pour paysage. */
    fun grid(n: Int, landscapeSheet: Boolean): Pair<Int, Int> {
        val (c, r) = when (n) {
            1 -> 1 to 1; 2 -> 1 to 2; 4 -> 2 to 2; 6 -> 2 to 3; 9 -> 3 to 3; 16 -> 4 to 4
            else -> throw IllegalArgumentException("Pages par feuille non prises en charge : $n")
        }
        return if (landscapeSheet) r to c else c to r
    }

    /** @return le nombre de faces produites (pour une copie). */
    fun impose(source: File, s: PrintSettings, out: File): Int {
        return PDDocument.load(source).use { src ->
            val indices = PageRangeParser.parse(s.pageRange, src.numberOfPages)
            val n = s.pagesPerSheet
            val cells = if (n > 1 && s.layoutMode == LayoutMode.REPEAT) indices.flatMap { i -> List(n) { i } } else indices
            val sheets = cells.chunked(n)

            // La rotation /Rotate des pages est neutralisée puis appliquée par nos soins
            val rotations = HashMap<Int, Int>()
            for (i in indices.distinct()) {
                val page = src.getPage(i)
                rotations[i] = ((page.rotation % 360) + 360) % 360
                page.rotation = 0
            }

            PDDocument().use { dst ->
                val lu = LayerUtility(dst)
                val forms = HashMap<Int, PDFormXObject>()
                val (sw, sh) = sheetSize(s)
                val (cols, rows) = grid(n, sw > sh)
                val m = s.marginPt()
                val gap = if (n > 1) mm(CELL_GAP_MM) else 0f
                val cellW = (sw - 2 * m - gap * (cols - 1)) / cols
                val cellH = (sh - 2 * m - gap * (rows - 1)) / rows
                if (cellW < 20f || cellH < 20f) throw IllegalArgumentException("Marges trop grandes pour ce format")

                for (sheet in sheets) {
                    val page = PDPage(PDRectangle(sw, sh))
                    dst.addPage(page)
                    PDPageContentStream(dst, page).use { cs ->
                        sheet.forEachIndexed { k, idx ->
                            val col = k % cols
                            val row = k / cols
                            val x = m + col * (cellW + gap)
                            val y = sh - m - (row + 1) * cellH - row * gap // origine PDF en bas
                            val form = forms.getOrPut(idx) {
                                lu.importPageAsForm(src, idx).also { it.cosObject.removeItem(COSName.MATRIX) }
                            }
                            drawCell(cs, form, rotations[idx] ?: 0, x, y, cellW, cellH, s)
                            if (s.drawBorders) {
                                cs.saveGraphicsState()
                                cs.setStrokingColor(PDColor(floatArrayOf(0.55f), PDDeviceGray.INSTANCE))
                                cs.setLineWidth(0.6f)
                                cs.addRect(x, y, cellW, cellH)
                                cs.stroke()
                                cs.restoreGraphicsState()
                            }
                        }
                    }
                }
                dst.save(out)
                sheets.size
            }
        }
    }

    private fun drawCell(
        cs: PDPageContentStream, form: PDFormXObject, pageRotation: Int,
        x: Float, y: Float, cw: Float, ch: Float, s: PrintSettings,
    ) {
        val box = form.bBox
        val w = box.width
        val h = box.height
        fun dims(rot: Int) = if (rot % 180 == 0) w to h else h to w
        fun fitFor(rot: Int) = dims(rot).let { (rw, rh) -> min(cw / rw, ch / rh) }

        // Rotation automatique si la page tient mieux tournée dans sa case
        var rot = pageRotation
        val turned = (rot + 270) % 360
        if (fitFor(turned) > fitFor(rot) * 1.02f) rot = turned

        val (rw, rh) = dims(rot)
        val fit = min(cw / rw, ch / rh)
        val scale = when (s.effectiveScale) {
            ScaleMode.FIT -> fit
            ScaleMode.ACTUAL -> 1f
            ScaleMode.CUSTOM -> s.customScalePercent / 100f
        }
        val dw = rw * scale
        val dh = rh * scale
        val ox = x + (cw - dw) / 2
        val oy = y + (ch - dh) / 2

        cs.saveGraphicsState()
        cs.addRect(x, y, cw, ch)
        cs.clip()
        cs.transform(Matrix.getTranslateInstance(ox, oy))
        cs.transform(Matrix.getScaleInstance(scale, scale))
        cs.transform(rotationMatrix(rot, w, h))
        cs.transform(Matrix.getTranslateInstance(-box.lowerLeftX, -box.lowerLeftY))
        cs.drawForm(form)
        cs.restoreGraphicsState()
    }

    /** Rotation horaire d'un contenu w×h, ramené dans le quadrant positif. */
    private fun rotationMatrix(rot: Int, w: Float, h: Float): Matrix = when (rot) {
        90 -> Matrix(0f, -1f, 1f, 0f, 0f, w)
        180 -> Matrix(-1f, 0f, 0f, -1f, w, h)
        270 -> Matrix(0f, 1f, -1f, 0f, h, 0f)
        else -> Matrix()
    }
}

/** Construit un PDF de travail à partir des faces imposées (null = face blanche). */
object JobBuilder {
    fun build(imposed: File, order: List<Int?>, out: File) {
        PDDocument.load(imposed).use { src ->
            PDDocument().use { dst ->
                val lu = LayerUtility(dst)
                val forms = HashMap<Int, PDFormXObject>()
                val size = src.getPage(0).mediaBox
                for (i in order) {
                    val page = PDPage(PDRectangle(size.width, size.height))
                    dst.addPage(page)
                    if (i != null) PDPageContentStream(dst, page).use { cs ->
                        cs.drawForm(forms.getOrPut(i) { lu.importPageAsForm(src, i) })
                    }
                }
                dst.save(out)
            }
        }
    }
}

object JobPlanner {
    /** Copies assemblées ; en recto verso, chaque copie commence sur une nouvelle feuille. */
    fun collated(faces: Int, copies: Int, twoSided: Boolean): List<Int?> {
        val one: MutableList<Int?> = (0 until faces).map<Int, Int?> { it }.toMutableList()
        if (twoSided && faces % 2 == 1) one += null
        return (1..copies).flatMap { one }
    }

    /** Recto verso manuel : (rectos, versos). */
    fun manualDuplex(faces: Int, copies: Int, reverseBacks: Boolean): Pair<List<Int?>, List<Int?>> {
        val seq = collated(faces, copies, twoSided = true)
        val front = seq.filterIndexed { i, _ -> i % 2 == 0 }
        val back = seq.filterIndexed { i, _ -> i % 2 == 1 }
        return front to (if (reverseBacks) back.reversed() else back)
    }
}
