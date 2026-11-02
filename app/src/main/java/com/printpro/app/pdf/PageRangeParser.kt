package com.printpro.app.pdf

/**
 * Analyse une sélection du type « 1-5, 8, 10-12 ».
 * Vide = toutes les pages. « 10- » = de 10 à la fin. « 5-1 » = ordre inverse.
 * Retourne des indices base 0.
 */
object PageRangeParser {
    private val RANGE = Regex("""^(\d*)\s*-\s*(\d*)$""")

    fun parse(text: String, pageCount: Int): List<Int> {
        val t = text.trim()
        if (t.isEmpty()) return (0 until pageCount).toList()
        val result = mutableListOf<Int>()
        for (raw in t.split(',', ';')) {
            val part = raw.trim()
            if (part.isEmpty()) continue
            val m = RANGE.matchEntire(part)
            if (m != null) {
                val a = m.groupValues[1].ifEmpty { "1" }.toInt()
                val b = m.groupValues[2].ifEmpty { pageCount.toString() }.toInt()
                check(a, part, pageCount); check(b, part, pageCount)
                if (a <= b) for (i in a..b) result += i - 1 else for (i in a downTo b) result += i - 1
            } else {
                val n = part.toIntOrNull()
                    ?: throw IllegalArgumentException("Sélection invalide : « $part » (exemple : 1-5, 8, 10-12)")
                check(n, part, pageCount)
                result += n - 1
            }
        }
        if (result.isEmpty()) throw IllegalArgumentException("Aucune page sélectionnée")
        return result
    }

    private fun check(n: Int, part: String, count: Int) {
        if (n < 1 || n > count) throw IllegalArgumentException(
            "« $part » est hors du document ($count page${if (count > 1) "s" else ""})"
        )
    }
}
