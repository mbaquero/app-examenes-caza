package com.examenescaza.app.pdf

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.examenescaza.app.parser.DesktopPdfReader
import com.examenescaza.app.parser.DocumentParser
import com.examenescaza.app.parser.QuestionIssue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Runs the on-device extractor (PdfBox-Android) against the sample PDF, when present. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AndroidPdfExtractorTest {

    @Test
    fun androidExtractorProducesTheSameQuestionBank() {
        val pdf = DesktopPdfReader.samplePdf()
        assumeTrue("Sample PDF not available", pdf != null)
        val context: Context = ApplicationProvider.getApplicationContext()
        val extracted = PdfExtractor(context).extract(pdf!!, pdf.name) { _, _ -> }
        val doc = DocumentParser().parse(extracted.input)
        assertEquals(400, doc.questions.size)
        assertTrue(doc.questions.none { q -> q.issues.any { it.blocking } })
        assertEquals(listOf(QuestionIssue.POSSIBLY_TRUNCATED), doc.questions.flatMap { it.issues }.distinct())
        // Same answers as the desktop pipeline.
        val desktop = DocumentParser().parse(DesktopPdfReader.read(pdf))
        assertEquals(desktop.questions.map { it.stableKey to it.correctIndex }, doc.questions.map { it.stableKey to it.correctIndex })
        // Illustrations are extracted and placed within the theory.
        val images = doc.topics.flatMap { it.blocks }.count { it.imageId != null }
        println("Extracted images: $images, per topic: " + doc.topics.associate { it.key to it.blocks.count { b -> b.imageId != null } })
        assertTrue("images: $images", images > 50)
    }
}
