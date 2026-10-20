package com.examenescaza.app

import android.content.Context
import com.examenescaza.app.data.ImportPreview
import com.examenescaza.app.parser.DocumentParser
import com.examenescaza.app.parser.ParserInput
import com.examenescaza.app.parser.StyledLine
import com.examenescaza.app.pdf.ExtractedPdf
import java.io.File

/** Synthetic study material for tests only; never shipped with the app. */
object TestContent {
    fun preview(context: Context, perTopic: Int = 6, variant: String = "", hash: String = "hash-$variant"): ImportPreview {
        val lines = ArrayList<StyledLine>()
        fun l(m: String, left: Float = 32f) = lines.add(StyledLine.markup(m, left = left))
        l("**Prueba teórica:**")
        l("Será positiva si se contesta correctamente al menos a 3 de las 4 preguntas, con un tiempo máximo de 5 minutos.")
        l("Del TEMA I: 2 preguntas.")
        l("Del TEMA II: 2 preguntas.")
        l("**Tema 1:**"); l("·      Funcionamiento de las armas.")
        l("**Tema 2:**"); l("·      Categoría de las armas.")
        l("**Tema 1:**", left = 500f); l("**FUNCIONAMIENTO DE LAS ARMAS:**", left = 240f)
        l("El **cañón** conduce y estabiliza el proyectil.")
        l("**Tema 2:**", left = 500f); l("Las escopetas son armas de la tercera categoría.")
        for (t in listOf("I", "II")) {
            l("**TEMA $t**", left = 286f)
            for (n in 1..perTopic) {
                l(". Pregunta $n del tema $t $variant sobre el cañón y las escopetas")
                l("A).- Respuesta uno $t$n"); l("B).- **Respuesta dos $t$n**"); l("C).- Respuesta tres $t$n")
            }
        }
        val doc = DocumentParser().parse(ParserInput(lines))
        val dir = File(context.filesDir, "images/test").apply { mkdirs() }
        return ImportPreview(ExtractedPdf(ParserInput(lines), hash, "temario$variant.pdf", dir), doc)
    }
}
