package com.examenescaza.app.parser

/** An image drawn on a PDF page, positioned by its top edge (top-down coordinates). */
data class PageImage(val page: Int, val top: Float, val id: String, val width: Int, val height: Int)

data class ParserInput(
    val lines: List<StyledLine>,
    val images: List<PageImage> = emptyList(),
    /** Pages whose text came from OCR and therefore carry no font (bold) information. */
    val ocrPages: Set<Int> = emptySet(),
    val pageCount: Int = lines.maxOfOrNull { it.page } ?: 0,
)

enum class BlockType { HEADING, PARAGRAPH, BULLET, IMAGE }

data class ContentBlock(
    val type: BlockType,
    val text: StyledText,
    val page: Int,
    val level: Int = 0,
    val imageId: String? = null,
    val imageAspect: Float = 0f,
)

data class ParsedTopic(
    /** Stable identifier across re-imports: INTRO, T1..Tn, APPX1.. */
    val key: String,
    val number: Int?,
    val code: String?,
    val title: String,
    val subtopics: List<String>,
    val blocks: List<ContentBlock>,
)

enum class QuestionIssue {
    NO_CORRECT_ANSWER,
    MULTIPLE_CORRECT_CANDIDATES,
    TOO_FEW_OPTIONS,
    POSSIBLY_TRUNCATED,
    DUPLICATE,
    EMPTY_TEXT,
    NO_STYLE_INFO,
    UNKNOWN_TOPIC;

    /** Issues that make a question unusable until the user fixes it. */
    val blocking: Boolean
        get() = this == NO_CORRECT_ANSWER || this == MULTIPLE_CORRECT_CANDIDATES ||
            this == TOO_FEW_OPTIONS || this == EMPTY_TEXT || this == NO_STYLE_INFO
}

data class ParsedOption(val label: String, val text: String)

data class ParsedQuestion(
    val topicKey: String,
    val numberInTopic: Int,
    val text: String,
    val options: List<ParsedOption>,
    val correctIndex: Int?,
    val issues: Set<QuestionIssue>,
    val page: Int,
    val subsection: String? = null,
    /** Indices into the topic's [ParsedTopic.blocks] with the most related theory. */
    val relatedBlocks: List<Int> = emptyList(),
    val stableKey: String = "",
    /** Bold ratio per option; kept for diagnostics in the review screen. */
    val boldRatios: List<Float> = emptyList(),
)

data class ExamPreset(
    val questionCount: Int,
    val passMark: Int?,
    val timeLimitMinutes: Int?,
    /** Questions per topic key (e.g. T1 -> 4). Empty if the source does not define it. */
    val perTopic: Map<String, Int>,
)

data class ParsedDocument(
    val topics: List<ParsedTopic>,
    val questions: List<ParsedQuestion>,
    val examPreset: ExamPreset?,
    val declaredQuestionCount: Int?,
    val warnings: List<String>,
    val pageCount: Int,
) {
    val validQuestions: List<ParsedQuestion> get() = questions.filter { q -> q.issues.none { it.blocking } }
    val flaggedQuestions: List<ParsedQuestion> get() = questions.filter { it.issues.isNotEmpty() }
}
