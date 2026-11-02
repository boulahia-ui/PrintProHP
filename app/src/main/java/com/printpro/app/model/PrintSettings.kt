package com.printpro.app.model

/** Dimensions en points PDF (1 pt = 1/72 pouce) et mot-clé IPP correspondant. */
enum class PaperSize(val label: String, val widthPt: Float, val heightPt: Float, val ippKeyword: String) {
    A4("A4", 595.28f, 841.89f, "iso_a4_210x297mm"),
    A3("A3", 841.89f, 1190.55f, "iso_a3_297x420mm"),
    A5("A5", 419.53f, 595.28f, "iso_a5_148x210mm"),
    LETTER("Letter", 612f, 792f, "na_letter_8.5x11in"),
    LEGAL("Legal", 612f, 1008f, "na_legal_8.5x14in"),
    PHOTO_4X6("Photo 10×15", 288f, 432f, "na_index-4x6_4x6in"),
}

enum class Orientation(val label: String) { PORTRAIT("Portrait"), LANDSCAPE("Paysage") }

enum class MarginMode(val label: String) {
    NORMAL("Normales"), REDUCED("Réduites"), NONE("Aucune"), CUSTOM("Personnalisées")
}

enum class ScaleMode(val label: String) {
    FIT("Ajuster à la page"), ACTUAL("Taille réelle"), CUSTOM("Personnalisée")
}

enum class DuplexMode(val label: String, val ippSides: String) {
    SIMPLEX("Recto seul", "one-sided"),
    LONG_EDGE("Recto verso auto · bord long", "two-sided-long-edge"),
    SHORT_EDGE("Recto verso auto · bord court", "two-sided-short-edge"),
    MANUAL("Recto verso manuel", "one-sided");

    val isAuto: Boolean get() = this == LONG_EDGE || this == SHORT_EDGE
}

enum class ColorMode(val label: String, val ipp: String) {
    COLOR("Couleur", "color"), MONO("Noir et blanc", "monochrome")
}

/** Valeurs IPP print-quality : 3 = brouillon, 4 = normale, 5 = haute. */
enum class Quality(val label: String, val ipp: Int) {
    DRAFT("Brouillon", 3), NORMAL("Normale", 4), HIGH("Haute", 5)
}

enum class LayoutMode(val label: String) {
    DISTINCT("Pages différentes"), REPEAT("Répéter la même page")
}

val PAGES_PER_SHEET = listOf(1, 2, 4, 6, 9, 16)

data class PrintSettings(
    val pagesPerSheet: Int = 1,
    val layoutMode: LayoutMode = LayoutMode.DISTINCT,
    val copies: Int = 1,
    val duplex: DuplexMode = DuplexMode.SIMPLEX,
    val reverseBackSides: Boolean = false,
    val paper: PaperSize = PaperSize.A4,
    val orientation: Orientation = Orientation.PORTRAIT,
    val margins: MarginMode = MarginMode.NORMAL,
    val customMarginMm: Float = 10f,
    val scale: ScaleMode = ScaleMode.FIT,
    val customScalePercent: Int = 100,
    val color: ColorMode = ColorMode.COLOR,
    val quality: Quality = Quality.NORMAL,
    val pageRange: String = "",
    val drawBorders: Boolean = false,
) {
    fun marginPt(): Float = when (margins) {
        MarginMode.NORMAL -> mm(12.7f)
        MarginMode.REDUCED -> mm(5f)
        MarginMode.NONE -> 0f
        MarginMode.CUSTOM -> mm(customMarginMm)
    }

    /** Avec plusieurs pages par feuille, chaque page est toujours ajustée à sa case. */
    val effectiveScale: ScaleMode get() = if (pagesPerSheet > 1) ScaleMode.FIT else scale
}

fun mm(v: Float): Float = v * 72f / 25.4f
