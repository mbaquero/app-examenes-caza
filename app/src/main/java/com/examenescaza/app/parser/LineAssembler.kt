package com.examenescaza.app.parser

/**
 * Groups glyphs into visual lines. Glyphs whose baselines are within a fraction of the font
 * size belong to the same line (bold runs are often rendered on a slightly shifted baseline).
 */
object LineAssembler {

    fun assemble(glyphs: List<Glyph>, pageWidths: Map<Int, Float>): List<StyledLine> {
        val result = ArrayList<StyledLine>()
        glyphs.groupBy { it.page }.toSortedMap().forEach { (page, pageGlyphs) ->
            val sorted = pageGlyphs.sortedBy { it.baseline }
            val groups = ArrayList<MutableList<Glyph>>()
            var groupBaseline = Float.NaN
            for (g in sorted) {
                val tolerance = (g.fontSize * 0.45f).coerceAtLeast(1f)
                if (groups.isNotEmpty() && kotlin.math.abs(g.baseline - groupBaseline) <= tolerance) {
                    groups.last().add(g)
                } else {
                    groups.add(mutableListOf(g))
                    groupBaseline = g.baseline
                }
            }
            val width = pageWidths[page] ?: 0f
            for (group in groups) result += buildLine(page, group.sortedBy { it.x }, width)
        }
        return result
    }

    private fun buildLine(page: Int, glyphs: List<Glyph>, pageWidth: Float): StyledLine {
        val sb = StringBuilder()
        val mask = ArrayList<Boolean>()
        var prev: Glyph? = null
        fun append(s: String, bold: Boolean) {
            for (ch in s) {
                val c = if (ch == ' ' || ch == '\t') ' ' else ch
                if (c == ' ' && (sb.isEmpty() || sb.last() == ' ')) continue
                sb.append(c); mask.add(bold)
            }
        }
        for (g in glyphs) {
            val p = prev
            if (p != null) {
                val gap = g.x - (p.x + p.width)
                if (gap > g.fontSize * 0.2f) append(" ", p.bold && g.bold)
            }
            append(TextNormalizer.normalizeGlyph(g.text), g.bold)
            prev = g
        }
        val left = glyphs.firstOrNull()?.x ?: 0f
        val right = glyphs.maxOfOrNull { it.x + it.width } ?: 0f
        val size = glyphs.groupingBy { it.fontSize }.eachCount().maxByOrNull { it.value }?.key ?: 0f
        val baseline = glyphs.minOfOrNull { it.baseline } ?: 0f
        return StyledLine(page, sb.toString(), mask.toBooleanArray(), left, right, pageWidth, size, baseline)
    }
}
