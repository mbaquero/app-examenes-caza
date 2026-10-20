package com.examenescaza.app.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test

/**
 * End-to-end check against the study PDF in the project root. Skipped when the PDF is absent.
 * The PDF itself is development data only; it is never bundled into the app.
 */
class RealPdfParsingTest {

    companion object {
        private var doc: ParsedDocument? = null

        @BeforeClass
        @JvmStatic
        fun parseOnce() {
            val pdf = DesktopPdfReader.samplePdf() ?: return
            doc = DocumentParser().parse(DesktopPdfReader.read(pdf))
        }
    }

    private fun document(): ParsedDocument {
        assumeTrue("Sample PDF not available", doc != null)
        return doc!!
    }

    @Test
    fun extractsAllDeclaredQuestions() {
        val d = document()
        assertEquals(400, d.declaredQuestionCount)
        assertEquals(400, d.questions.size)
    }

    @Test
    fun everyQuestionHasThreeOptionsAndOneCorrectAnswer() {
        val d = document()
        val blocking = d.questions.filter { q -> q.issues.any { it.blocking } }
        assertTrue("Blocking issues: " + blocking.take(5).joinToString { it.text.take(60) + " " + it.issues }, blocking.isEmpty())
        d.questions.forEach { q ->
            assertEquals(q.text, 3, q.options.size)
            assertNotNull(q.text, q.correctIndex)
        }
    }

    @Test
    fun questionsAreDistributedAcrossSixTopics() {
        val d = document()
        val perTopic = d.questions.groupingBy { it.topicKey }.eachCount()
        assertEquals(mapOf("T1" to 70, "T2" to 65, "T3" to 65, "T4" to 70, "T5" to 65, "T6" to 65), perTopic)
    }

    @Test
    fun knownAnswersAreDetected() {
        val d = document()
        fun answerOf(prefix: String) = d.questions.first { it.text.startsWith(prefix) }.let { it.options[it.correctIndex!!].text }
        assertEquals("Arma de fuego automática.", answerOf("El arma de fuego que se recarga automáticamente, después de cada disparo, efectuando"))
        assertEquals("Arma de fuego semiautomática.", answerOf("El arma de fuego que después de cada disparo, se recarga"))
        assertEquals("El cañón.", answerOf("¿Qué elemento de un arma de fuego conduce y estabiliza el proyectil"))
        // Bold is offset by one character in the source ("**S**í" vs "prohibid**o.**").
        assertEquals("Sí.", answerOf("¿Se pueden utilizar miras telescópicas en las armas de la categoría 2ª.2"))
        // Answer split across two lines.
        assertTrue(answerOf("¿En qué casos se permitirá que la reparación del arma suponga").endsWith("Banco Oficial de Pruebas."))
    }

    @Test
    fun topicsAreAssociatedWithTheory() {
        val d = document()
        val keys = d.topics.map { it.key }
        assertEquals(listOf("INTRO", "T1", "T2", "T3", "T4", "T5", "T6", "APPX1"), keys)
        assertEquals("Funcionamiento de las armas", d.topics.first { it.key == "T1" }.title)
        assertEquals("Prueba práctica", d.topics.first { it.key == "APPX1" }.title)
        d.topics.filter { it.key.startsWith("T") }.forEach { assertTrue(it.key, it.blocks.size > 5) }
        val t2 = d.topics.first { it.key == "T2" }
        assertTrue(t2.blocks.any { it.type == BlockType.HEADING && it.text.text.startsWith("1.ª categoría") })
    }

    @Test
    fun extractsOfficialExamPreset() {
        val p = document().examPreset!!
        assertEquals(20, p.questionCount)
        assertEquals(16, p.passMark)
        assertEquals(20, p.timeLimitMinutes)
        assertEquals(mapOf("T1" to 4, "T2" to 3, "T3" to 3, "T4" to 4, "T5" to 3, "T6" to 3), p.perTopic)
    }

    @Test
    fun flagsTruncatedOptionAndKeepsStableKeysUnique() {
        val d = document()
        val last = d.questions.last()
        assertTrue(QuestionIssue.POSSIBLY_TRUNCATED in last.issues)
        assertEquals(d.questions.size, d.questions.map { it.stableKey }.toSet().size)
    }

    @Test
    fun mostQuestionsHaveRelatedTheory() {
        val d = document()
        val withTheory = d.questions.count { it.relatedBlocks.isNotEmpty() }
        assertTrue("only $withTheory", withTheory > d.questions.size * 0.8)
    }
}
