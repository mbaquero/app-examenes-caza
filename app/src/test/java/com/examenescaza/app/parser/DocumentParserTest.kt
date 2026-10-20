package com.examenescaza.app.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Synthetic documents that mimic the structure of the study guide. */
class DocumentParserTest {

    private fun line(markup: String, page: Int = 1, left: Float = 32f, right: Float = 300f, size: Float = 7.3f) =
        StyledLine.markup(markup, page = page, left = left, right = right, fontSize = size)

    private fun parse(vararg lines: StyledLine, ocrPages: Set<Int> = emptySet()) =
        DocumentParser().parse(ParserInput(lines.toList(), ocrPages = ocrPages))

    private val intro = arrayOf(
        line("**Prueba teórica:**"),
        line("La evaluación será positiva si el interesado ha contestado correctamente al menos a 2 de las 3 preguntas, con un tiempo máximo para contestar de 5 minutos."),
        line("El cuestionario general tiene un total de 3 preguntas."),
        line("Del TEMA I: 2 preguntas."),
        line("Del TEMA II: 1 preguntas."),
        line("**Tema 1:**"),
        line("·      Funcionamiento de las armas."),
        line("**Tema 2:**"),
        line("·      Categoría de las armas."),
    )

    private val theory = arrayOf(
        line("**Tema 1:**", left = 500f),
        line("**FUNCIONAMIENTO DE LAS ARMAS:**", left = 246f),
        line("**Cañón:** elemento de un arma de fuego que conduce y estabiliza el proyectil."),
        line("**Tema 2:**", left = 276f, size = 12f),
        line("**1.ª categoría**:"),
        line("Armas de fuego cortas: Comprende las pistolas y revólveres."),
        line("++++++++++++++++++++"),
    )

    private val questions = arrayOf(
        line("**TEMA I**", left = 286f),
        line("**FUNCIONAMIENTO DE LAS ARMAS**", left = 240f),
        line(". ¿Qué elemento conduce y estabiliza el proyectil?."),
        line("A).- El disparador."),
        line("B).- **El cañón.**"),
        line("C).- El estabilizador."),
        line(". La culata de un arma sirve para:"),
        line("A).- Instalar en ella el punto de mira."),
        line("B)**.- Apoyar el arma sobre el hombro para**"),
        line("**efectuar el disparo.**"),
        line("C).- Las armas no tienen culata."),
        line("**TEMA II**", left = 286f),
        line("¿Se pueden utilizar miras telescópicas?"),
        line("A).- **S**í."),
        line("B).- No."),
        line("C).- Está prohibid**o.**"),
    )

    @Test
    fun identifiesTopicsAndTheirHierarchy() {
        val doc = parse(*intro, *theory, *questions)
        assertEquals(listOf("INTRO", "T1", "T2"), doc.topics.map { it.key })
        val t1 = doc.topics.first { it.key == "T1" }
        assertEquals("Funcionamiento de las armas", t1.title)
        assertEquals("I", t1.code)
        assertEquals(BlockType.HEADING, t1.blocks.first().type)
        // Bold key concept preserved as a highlighted span.
        val def = t1.blocks.first { it.text.text.startsWith("Cañón") }
        assertEquals(listOf(0 until 6), def.text.boldRanges())
        val t2 = doc.topics.first { it.key == "T2" }
        assertTrue(t2.blocks.any { it.type == BlockType.HEADING && it.text.text.startsWith("1.ª categoría") })
        // The separator is not content.
        assertFalse(t2.blocks.any { it.text.text.startsWith("+++") })
    }

    @Test
    fun associatesQuestionsWithTopics() {
        val doc = parse(*intro, *theory, *questions)
        assertEquals(listOf("T1", "T1", "T2"), doc.questions.map { it.topicKey })
        assertEquals(listOf(1, 2, 1), doc.questions.map { it.numberInTopic })
        assertEquals("¿Qué elemento conduce y estabiliza el proyectil?.", doc.questions[0].text)
    }

    @Test
    fun detectsBoldAnswersIncludingWrappedAndOffsetBold() {
        val doc = parse(*intro, *theory, *questions)
        assertEquals(listOf(1, 1, 0), doc.questions.map { it.correctIndex })
        assertEquals("Apoyar el arma sobre el hombro para efectuar el disparo.", doc.questions[1].options[1].text)
        assertTrue(doc.questions.all { it.issues.isEmpty() })
    }

    @Test
    fun neverGuessesWhenNoOptionIsBold() {
        val doc = parse(
            line("**TEMA I**", left = 286f),
            line(". Pregunta sin marcar"),
            line("A).- Uno"), line("B).- Dos"), line("C).- Tres"),
        )
        val q = doc.questions.single()
        assertNull(q.correctIndex)
        assertTrue(QuestionIssue.NO_CORRECT_ANSWER in q.issues)
    }

    @Test
    fun flagsAmbiguousBold() {
        val doc = parse(
            line("**TEMA I**", left = 286f),
            line(". Pregunta ambigua"),
            line("A).- **Uno entero**"), line("B).- **Dos entero**"), line("C).- Tres"),
        )
        val q = doc.questions.single()
        assertNull(q.correctIndex)
        assertTrue(QuestionIssue.MULTIPLE_CORRECT_CANDIDATES in q.issues)
    }

    @Test
    fun flagsMissingOptions() {
        val doc = parse(
            line("**TEMA I**", left = 286f),
            line(". Pregunta incompleta"),
            line("A).- **Uno**"), line("B).- Dos"),
            line(". Siguiente pregunta"),
            line("A).- Uno"), line("B).- **Dos**"), line("C).- Tres"),
        )
        assertEquals(2, doc.questions.size)
        assertTrue(QuestionIssue.TOO_FEW_OPTIONS in doc.questions[0].issues)
        assertEquals(1, doc.questions[1].correctIndex)
    }

    @Test
    fun ocrPagesRequireManualAnswer() {
        val doc = parse(
            line("**TEMA I**", left = 286f, page = 1),
            line(". Pregunta escaneada", page = 2),
            line("A).- Uno", page = 2), line("B).- Dos", page = 2), line("C).- Tres", page = 2),
            ocrPages = setOf(2),
        )
        val q = doc.questions.single()
        assertNull(q.correctIndex)
        assertTrue(QuestionIssue.NO_STYLE_INFO in q.issues)
    }

    @Test
    fun flagsExactDuplicatesButKeepsDistinctKeys() {
        val doc = parse(
            line("**TEMA I**", left = 286f),
            line(". Misma pregunta"), line("A).- **Uno**"), line("B).- Dos"), line("C).- Tres"),
            line(". Misma pregunta"), line("A).- **Uno**"), line("B).- Dos"), line("C).- Tres"),
            line(". Misma pregunta"), line("A).- Cuatro"), line("B).- **Dos**"), line("C).- Tres"),
        )
        assertEquals(listOf(false, true, false), doc.questions.map { QuestionIssue.DUPLICATE in it.issues })
        assertEquals(3, doc.questions.map { it.stableKey }.toSet().size)
    }

    @Test
    fun stableKeysDoNotDependOnPosition() {
        val a = parse(*intro, *theory, *questions)
        val b = parse(*intro, *theory, *questions.copyOfRange(0, 2), *questions.copyOfRange(6, 11), *questions.copyOfRange(2, 6), *questions.copyOfRange(11, questions.size))
        assertEquals(a.questions.map { it.stableKey }.toSet(), b.questions.map { it.stableKey }.toSet())
    }

    @Test
    fun extractsExamPresetAndDeclaredCount() {
        val doc = parse(*intro, *theory, *questions)
        val p = doc.examPreset!!
        assertEquals(3, p.questionCount)
        assertEquals(2, p.passMark)
        assertEquals(5, p.timeLimitMinutes)
        assertEquals(mapOf("T1" to 2, "T2" to 1), p.perTopic)
        assertEquals(3, doc.declaredQuestionCount)
        assertTrue(doc.warnings.none { it.contains("indica") })
    }

    @Test
    fun warnsWhenDeclaredCountDiffers() {
        val doc = parse(*intro, *theory, *questions.copyOfRange(0, 11))
        assertTrue(doc.warnings.any { it.contains("indica 3 preguntas") })
    }

    @Test
    fun appendixAfterLargerHeadingBecomesItsOwnTopic() {
        val doc = parse(
            *questions,
            line("**TEMARIO OBJETO DE ESTUDIO – PRUEBA PRÁCTICA:**", size = 12f),
            line("Debemos acudir a la prueba con el arma descargada."),
        )
        val appx = doc.topics.first { it.key == "APPX1" }
        assertEquals("Prueba práctica", appx.title)
        assertEquals("Debemos acudir a la prueba con el arma descargada.", appx.blocks.single().text.text)
        assertEquals(3, doc.questions.size)
    }

    @Test
    fun relatedTheoryPointsToMatchingParagraph() {
        val doc = parse(*intro, *theory, *questions)
        val q = doc.questions[0]
        val t1 = doc.topics.first { it.key == "T1" }
        assertTrue(q.relatedBlocks.isNotEmpty())
        assertTrue(t1.blocks[q.relatedBlocks.first()].text.text.startsWith("Cañón"))
    }

    @Test
    fun lineAssemblerMergesShiftedBoldBaselineAndDetectsBoldFonts() {
        val glyphs = listOf(
            Glyph(1, "A", 32f, 100f, 5f, 7.3f, false),
            Glyph(1, ")", 37f, 100f, 2f, 7.3f, false),
            Glyph(1, "S", 45f, 100.6f, 5f, 7.3f, true),
            Glyph(1, "í", 50f, 100.6f, 3f, 7.3f, true),
            Glyph(1, "B", 32f, 115f, 5f, 7.3f, false),
        )
        val lines = LineAssembler.assemble(glyphs, mapOf(1 to 595f))
        assertEquals(listOf("A) Sí", "B"), lines.map { it.text })
        assertEquals(listOf(false, false, false, true, true), lines[0].boldMask.toList())
        assertTrue(FontStyle.isBold("AAAAAA+Roboto_700wght", null, false))
        assertTrue(FontStyle.isBold("Arial-BoldMT", null, false))
        assertFalse(FontStyle.isBold("BAAAAA+RobotoStatic-Regular", 400f, false))
    }
}
