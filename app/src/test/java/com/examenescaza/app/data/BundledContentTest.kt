package com.examenescaza.app.data

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.examenescaza.app.ExamApp
import com.examenescaza.app.domain.ExamConfig
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The study guide bundled in the APK is prepared on first launch and only once. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BundledContentTest {

    @Test
    fun bundledPdfIsLoadedOnceAndKeepsHistory() = runBlocking {
        val c = ApplicationProvider.getApplicationContext<ExamApp>().container
        c.bundledContent.ensureLoaded()
        assertEquals(ContentState.Ready, c.bundledContent.state.value)
        assertEquals(400, c.content.usableQuestions().size)
        val preset = c.content.preset()!!
        assertEquals(20, preset.questionCount)
        assertEquals(16, preset.passMark)

        val examId = c.study.startExam(ExamConfig(questionCount = 5), emptyMap())
        c.study.submitExam(examId, 10)
        val docId = c.content.activeDocument.first()!!.id

        // A later launch with the same bundled PDF does not reprocess or touch history.
        c.bundledContent.ensureLoaded()
        assertEquals(docId, c.content.activeDocument.first()!!.id)
        assertEquals(1, c.study.submittedExams.first().size)
        assertEquals(400, c.content.usableQuestions().size)
    }
}
