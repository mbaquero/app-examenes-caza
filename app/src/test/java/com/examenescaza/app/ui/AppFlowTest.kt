package com.examenescaza.app.ui

import androidx.compose.ui.test.SemanticsNodeInteractionsProvider
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.examenescaza.app.ExamApp
import com.examenescaza.app.TestContent
import com.examenescaza.app.data.ReviewedQuestion
import com.examenescaza.app.data.StudyRepository
import com.examenescaza.app.ui.navigation.AppNavigation
import com.examenescaza.app.ui.theme.ExamTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Drives the real UI, ViewModels and database through the three learning modes. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AppFlowTest {

    @get:Rule val compose = createComposeRule()
    private val app: ExamApp = ApplicationProvider.getApplicationContext()

    @Before
    fun seed() = runBlocking {
        val p = TestContent.preview(app)
        app.container.content.commit(p, p.document.questions.map { ReviewedQuestion(it) })
        compose.setContent { ExamTheme { AppNavigation() } }
    }

    private fun waitForText(text: String, substring: Boolean = true) =
        compose.waitUntil(10_000) { compose.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty() }

    private fun SemanticsNodeInteractionsProvider.scrollTo(text: String) =
        onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasText(text, substring = true))

    @Test
    fun studyTopicsReadAndComplete() {
        waitForText("Estudiar temario")
        compose.onNodeWithText("Estudiar temario").performClick()
        waitForText("Funcionamiento de las armas")
        compose.onAllNodesWithText("Funcionamiento de las armas").onFirst().performClick()
        waitForText("conduce y estabiliza el proyectil")
        compose.scrollTo("Fin del tema")
        compose.onAllNodesWithText("Marcar como completado").onFirst().performClick()
        compose.waitUntil(5_000) { runBlocking { app.container.user.reading("T1")?.completed == true } }
    }

    @Test
    fun liveQuestionsGiveImmediateFeedback() {
        waitForText("Practicar")
        compose.onAllNodesWithText("Practicar").onFirst().performClick()
        waitForText("Aleatoria general")
        compose.scrollTo("Empezar (")
        compose.onNodeWithText("Empezar (", substring = true).performClick()
        waitForText("Pregunta 1 de")
        // The correct answer is never revealed before answering.
        assertEquals(0, compose.onAllNodesWithText("Respuesta correcta", substring = true).fetchSemanticsNodes().size)
        compose.onAllNodesWithText("Respuesta dos", substring = true).onFirst().performClick()
        compose.onNodeWithText("Comprobar").performClick()
        waitForText("¡Correcto!")
        compose.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasText("Teoría relacionada"))
        compose.onNodeWithText("Siguiente pregunta").performClick()
        waitForText("Pregunta 2 de")
        compose.onAllNodesWithText("Respuesta uno", substring = true).onFirst().performClick()
        compose.onNodeWithText("Comprobar").performClick()
        waitForText("Incorrecto")
        waitForText("Respuesta correcta: B)")
        val session = runBlocking { app.container.study.practiceInProgress.first()!! }
        assertEquals(1, session.correct)
        assertEquals(1, session.incorrect)
    }

    @Test
    fun examHidesAnswersUntilSubmissionThenShowsResultAndReview() {
        waitForText("Examen")
        compose.onAllNodesWithText("Examen").onFirst().performClick()
        waitForText("Examen oficial")
        compose.scrollTo("Empezar examen")
        compose.onNodeWithText("Empezar examen", substring = true).performClick()
        waitForText("Pregunta 1 de 4")
        compose.onAllNodesWithText("Respuesta dos", substring = true).onFirst().performClick()
        assertEquals(0, compose.onAllNodesWithText("Respuesta correcta", substring = true).fetchSemanticsNodes().size)
        assertEquals(0, compose.onAllNodesWithText("Teoría relacionada").fetchSemanticsNodes().size)
        repeat(3) {
            compose.onNodeWithText("Siguiente").performClick()
            compose.waitForIdle()
        }
        compose.onNodeWithText("Revisar y entregar").performClick()
        waitForText("Resumen del examen")
        compose.onNodeWithText("Entregar examen").performClick()
        waitForText("¿Entregar el examen?")
        waitForText("3 preguntas sin responder")
        compose.onNodeWithText("Entregar").performClick()
        waitForText("Resultado del examen")
        waitForText("NO APTO")
        val exam = runBlocking { app.container.study.submittedExams.first().single() }
        assertEquals(StudyRepository.STATUS_SUBMITTED, exam.status)
        assertEquals(1, exam.correct)
        assertEquals(3, exam.unanswered)
        compose.scrollTo("Revisar todas las preguntas")
        compose.onNodeWithText("Revisar todas las preguntas").performClick()
        waitForText("Revisión del examen")
        waitForText("Sin responder (3)")
        waitForText("Teoría relacionada")
    }
}
