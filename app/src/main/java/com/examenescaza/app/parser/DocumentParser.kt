package com.examenescaza.app.parser

/**
 * Turns styled PDF lines into a structured study guide:
 *
 *  - an introduction (everything before the first theory topic header),
 *  - theory topics, delimited by headers such as "Tema 1:",
 *  - a question bank, delimited by headers such as "TEMA I", where each question has options
 *    "A).-", "B).-", "C).-" and the correct option is the one printed in bold,
 *  - optional appendices after the question bank (detected by a larger heading font).
 *
 * The parser never guesses: when the correct option cannot be identified unambiguously the
 * question is flagged for manual review instead.
 */
class DocumentParser {

    private sealed interface Item {
        val page: Int
        val y: Float
    }

    private class LineItem(val line: StyledLine) : Item {
        override val page get() = line.page
        override val y get() = line.baseline
    }

    private class ImageItem(val image: PageImage) : Item {
        override val page get() = image.page
        override val y get() = image.top
    }

    fun parse(input: ParserInput): ParsedDocument {
        val warnings = ArrayList<String>()
        val items: List<Item> = buildList<Item> {
            input.lines.forEach { add(LineItem(it.trimmed())) }
            input.images.forEach { add(ImageItem(it)) }
        }.sortedWith(compareBy<Item>({ it.page }, { it.y }))

        val qStart = findQuestionSectionStart(items)
        val theoryEnd = if (qStart >= 0) findTheoryEnd(items, qStart) else items.size
        val qEnd = if (qStart >= 0) findQuestionSectionEnd(items, qStart) else items.size

        // --- Theory topics ---------------------------------------------------------------
        val theoryItems = items.subList(0, theoryEnd)
        val headerIdx = findTheoryTopicHeaders(theoryItems)
        val topics = ArrayList<ParsedTopic>()
        val index = parseSyllabusIndex(theoryItems, headerIdx.values.minOrNull() ?: theoryItems.size)

        val firstHeader = headerIdx.values.minOrNull() ?: theoryItems.size
        val introItems = theoryItems.subList(0, firstHeader)
        if (introItems.any { it is LineItem && !it.line.isBlank }) {
            topics += ParsedTopic(
                key = INTRO_KEY, number = 0, code = null, title = "Introducción y programa",
                subtopics = emptyList(), blocks = buildBlocks(introItems),
            )
        }
        val sortedHeaders = headerIdx.entries.sortedBy { it.value }
        sortedHeaders.forEachIndexed { i, (number, idx) ->
            val end = if (i + 1 < sortedHeaders.size) sortedHeaders[i + 1].value else theoryItems.size
            val blocks = buildBlocks(theoryItems.subList(idx + 1, end))
            val subtopics = index[number].orEmpty()
            val title = subtopics.firstOrNull()?.trimEnd('.', ':')
                ?: blocks.firstOrNull { it.type == BlockType.HEADING }?.text?.text?.trimEnd(':')?.let(::sentenceCase)
                ?: "Tema $number"
            topics += ParsedTopic("T$number", number, toRoman(number), title, subtopics.map { it.trimEnd('.') }, blocks)
        }

        // --- Question bank ---------------------------------------------------------------
        var questions: List<ParsedQuestion> = emptyList()
        if (qStart >= 0) {
            val lines = items.subList(qStart, qEnd).filterIsInstance<LineItem>().map { it.line }
            val (qs, sectionTitles) = parseQuestions(lines, input.ocrPages)
            questions = qs
            // Make sure every question topic exists, even if the theory section lacks it.
            for ((key, title) in sectionTitles) {
                if (topics.none { it.key == key }) {
                    val n = key.removePrefix("T").toIntOrNull()
                    topics += ParsedTopic(key, n, n?.let(::toRoman), title, emptyList(), emptyList())
                }
            }
        } else {
            warnings += "No se ha encontrado la sección de preguntas (encabezados del tipo \"TEMA I\")."
        }

        // --- Appendices --------------------------------------------------------------------
        if (qStart >= 0 && qEnd < items.size) {
            val appx = items.subList(qEnd, items.size)
            var bodyStart = 0
            while (bodyStart < appx.size) {
                val line = (appx[bodyStart] as? LineItem)?.line ?: break
                if (line.isBlank || line.boldRatio() > 0.8f) bodyStart++ else break
            }
            val rawTitle = appx.subList(0, bodyStart).filterIsInstance<LineItem>()
                .joinToString(" ") { it.line.text }.replace(Regex("\\s+"), " ").trim().trimEnd(':')
            val title = rawTitle.substringAfterLast('–').substringAfterLast(" - ").trim().ifEmpty { "Anexo" }
            topics += ParsedTopic(
                key = "APPX1", number = null, code = null, title = sentenceCase(title),
                subtopics = emptyList(), blocks = buildBlocks(appx.subList(bodyStart, appx.size)),
            )
        }

        topics.sortWith(compareBy({ topicOrder(it.key) }, { it.number ?: 0 }))

        // --- Related theory, stable keys, duplicates --------------------------------------
        val matcher = RelatedTheoryMatcher(topics)
        questions = questions.map { q -> q.copy(relatedBlocks = matcher.related(q)) }
        questions = markDuplicatesAndKeys(questions)

        // --- Exam preset & document checks ----------------------------------------------
        val introText = topics.firstOrNull { it.key == INTRO_KEY }?.blocks?.joinToString(" ") { it.text.text }.orEmpty()
        val preset = extractExamPreset(introText, topics)
        val declared = Regex("""total de (\d+) preguntas""", RegexOption.IGNORE_CASE).find(introText)?.groupValues?.get(1)?.toIntOrNull()
        if (declared != null && declared != questions.size) {
            warnings += "El documento indica $declared preguntas, pero se han extraído ${questions.size}."
        }
        topics.filter { it.number != null && it.number > 0 && questions.none { q -> q.topicKey == it.key } }.forEach {
            warnings += "El tema \"${it.title}\" no tiene preguntas asociadas."
        }
        if (input.ocrPages.isNotEmpty()) {
            warnings += "${input.ocrPages.size} página(s) se han leído mediante OCR; revisa su contenido."
        }

        return ParsedDocument(topics, questions, preset, declared, warnings, input.pageCount)
    }

    // ---------------------------------------------------------------------------------------
    // Section detection
    // ---------------------------------------------------------------------------------------

    /** First "TEMA <roman>" line that is followed by answer options within a few lines. */
    private fun findQuestionSectionStart(items: List<Item>): Int {
        for (i in items.indices) {
            val line = (items[i] as? LineItem)?.line ?: continue
            if (!QUESTION_TOPIC.matches(line.text)) continue
            val window = items.subList(i + 1, minOf(items.size, i + 30)).filterIsInstance<LineItem>()
            if (window.any { OPTION.matches(it.line.text) }) return i
        }
        return -1
    }

    /** Theory stops at a decorative separator (e.g. "+++++") preceding the question bank. */
    private fun findTheoryEnd(items: List<Item>, qStart: Int): Int {
        for (i in qStart - 1 downTo maxOf(0, qStart - 15)) {
            val line = (items[i] as? LineItem)?.line ?: continue
            if (SEPARATOR.matches(line.text)) return i
        }
        return qStart
    }

    /** The question bank ends where a heading with a clearly larger font starts (an appendix). */
    private fun findQuestionSectionEnd(items: List<Item>, qStart: Int): Int {
        val sizes = items.subList(qStart, items.size).filterIsInstance<LineItem>()
            .filter { !it.line.isBlank }.map { it.line.fontSize }.sorted()
        if (sizes.isEmpty()) return items.size
        val body = sizes[sizes.size / 2]
        for (i in qStart + 1 until items.size) {
            val line = (items[i] as? LineItem)?.line ?: continue
            if (!line.isBlank && line.fontSize >= body * 1.4f && line.boldRatio() > 0.8f) return i
        }
        return items.size
    }

    /** Maps topic number to the index of its theory header (the last occurrence wins, earlier ones are the index). */
    private fun findTheoryTopicHeaders(items: List<Item>): Map<Int, Int> {
        val result = LinkedHashMap<Int, Int>()
        items.forEachIndexed { i, item ->
            val line = (item as? LineItem)?.line ?: return@forEachIndexed
            val m = THEORY_TOPIC.matchEntire(line.text) ?: return@forEachIndexed
            result[m.groupValues[1].toInt()] = i
        }
        // Keep only headers in ascending order of position (protects against stray references).
        return result.filter { (_, idx) -> idx >= 0 }
    }

    /** Parses the syllabus index ("Tema 1:" followed by "· item" bullets) found in the introduction. */
    private fun parseSyllabusIndex(items: List<Item>, limit: Int): Map<Int, List<String>> {
        val result = LinkedHashMap<Int, MutableList<String>>()
        var current: Int? = null
        for (i in 0 until minOf(limit, items.size)) {
            val line = (items[i] as? LineItem)?.line ?: continue
            if (line.isBlank) continue
            val m = THEORY_TOPIC.matchEntire(line.text)
            if (m != null) {
                current = m.groupValues[1].toInt(); result[current] = mutableListOf(); continue
            }
            val bullet = BULLET_GLYPH.find(line.text)
            if (current != null && bullet != null) {
                result.getValue(current) += line.text.substring(bullet.range.last + 1).trim()
            } else if (current != null && result.getValue(current).isNotEmpty() &&
                (!line.text[0].isUpperCase() || !result.getValue(current).last().endsWith('.'))
            ) {
                val list = result.getValue(current)
                list[list.lastIndex] = list.last() + " " + line.text
            } else {
                current = null
            }
        }
        return result.filterValues { it.isNotEmpty() }
    }

    // ---------------------------------------------------------------------------------------
    // Theory blocks
    // ---------------------------------------------------------------------------------------

    private fun buildBlocks(items: List<Item>): List<ContentBlock> {
        val blocks = ArrayList<ContentBlock>()
        var open: ContentBlock? = null
        var lastLine: StyledLine? = null

        fun flush() {
            open?.let { blocks += it }
            open = null
        }

        for (item in items) {
            if (item is ImageItem) {
                flush()
                val img = item.image
                blocks += ContentBlock(
                    BlockType.IMAGE, StyledText.plain(""), img.page, imageId = img.id,
                    imageAspect = if (img.height > 0) img.width.toFloat() / img.height else 1f,
                )
                lastLine = null
                continue
            }
            val line = (item as LineItem).line
            if (line.isBlank || SEPARATOR.matches(line.text)) {
                flush(); lastLine = null; continue
            }
            val bulletMatch = BULLET_GLYPH.find(line.text)
            val isNumbered = NUMBERED_ITEM.containsMatchIn(line.text)
            val isHeading = line.alnumCount > 0 && line.boldRatio() >= 0.85f && line.text.length <= 140 &&
                bulletMatch == null && !line.text.startsWith("-")
            val current = open
            val continues = current != null && current.type != BlockType.HEADING && lastLine != null &&
                bulletMatch == null && !isNumbered && !isHeading &&
                (lastLine.isWrapped || line.text.first().isLowerCase())
            when {
                continues -> open = current!!.copy(text = current.text.joinWith(line.toStyledText()))
                isHeading -> {
                    flush()
                    // Consecutive heading lines (a wrapped heading) are merged.
                    val prev = blocks.lastOrNull()
                    if (prev != null && prev.type == BlockType.HEADING && lastLine != null && lastLine.isWrapped) {
                        blocks[blocks.lastIndex] = prev.copy(text = prev.text.joinWith(line.toStyledText()))
                    } else {
                        val upper = line.text.filter { it.isLetter() }.let { it.isNotEmpty() && it == it.uppercase() }
                        blocks += ContentBlock(BlockType.HEADING, line.toStyledText(), line.page, level = if (upper) 1 else 2)
                    }
                }
                bulletMatch != null -> {
                    flush()
                    open = ContentBlock(BlockType.BULLET, line.sub(bulletMatch.range.last + 1).trimStart(), line.page)
                }
                isNumbered -> {
                    flush()
                    open = ContentBlock(BlockType.BULLET, line.toStyledText(), line.page, level = 1)
                }
                else -> {
                    flush()
                    open = ContentBlock(BlockType.PARAGRAPH, line.toStyledText(), line.page)
                }
            }
            lastLine = line
        }
        flush()
        // Short standalone labels such as "Guías:" act as minor headings in the source.
        return blocks.map { b ->
            val t = b.text.text
            if (b.type == BlockType.PARAGRAPH && t.endsWith(":") && t.length <= 45 && t.count { it == ' ' } < 6 &&
                t.first().isUpperCase()
            ) b.copy(type = BlockType.HEADING, level = 3) else b
        }
    }

    private fun StyledText.trimStart(): StyledText {
        val n = text.length - text.trimStart().length
        return if (n == 0) this else StyledText(text.substring(n), boldMask.copyOfRange(n, boldMask.size))
    }

    // ---------------------------------------------------------------------------------------
    // Questions
    // ---------------------------------------------------------------------------------------

    private class QuestionBuilder(val topicKey: String, val page: Int, var text: StyledText, val subsection: String?) {
        val options = ArrayList<Pair<String, StyledText>>()
        var fromOcr = false
    }

    private fun parseQuestions(lines: List<StyledLine>, ocrPages: Set<Int>): Pair<List<ParsedQuestion>, Map<String, String>> {
        val out = ArrayList<ParsedQuestion>()
        val sectionTitles = LinkedHashMap<String, String>()
        val perTopicCounter = HashMap<String, Int>()
        var topicKey = UNKNOWN_TOPIC_KEY
        var awaitingTopicTitle = false
        var subsection: String? = null
        var cur: QuestionBuilder? = null
        var prev: StyledLine? = null

        fun finish() {
            val b = cur ?: return
            cur = null
            val n = (perTopicCounter[b.topicKey] ?: 0) + 1
            perTopicCounter[b.topicKey] = n
            out += buildQuestion(b, n)
        }

        for (raw in lines) {
            if (raw.isBlank) continue
            val line = raw
            val t = line.text
            val topicMatch = QUESTION_TOPIC.matchEntire(t)
            if (topicMatch != null) {
                finish()
                val n = TextNormalizer.romanToInt(topicMatch.groupValues[1]) ?: topicMatch.groupValues[1].toIntOrNull()
                topicKey = if (n != null) "T$n" else UNKNOWN_TOPIC_KEY
                sectionTitles.putIfAbsent(topicKey, "Tema ${n ?: "?"}")
                awaitingTopicTitle = true
                subsection = null
                prev = null
                continue
            }

            val b = cur
            val optMatch = OPTION.matchEntire(t)
            val expected = if (b != null && b.options.size < MAX_OPTIONS) LABELS[b.options.size] else null
            if (b != null && optMatch != null && optMatch.groupValues[1] == expected) {
                val bodyStart = optMatch.groups[2]!!.range.first
                b.options += expected to line.sub(bodyStart)
                if (line.page in ocrPages) b.fromOcr = true
                prev = line
                continue
            }

            if (b != null && b.options.isNotEmpty()) {
                val p = prev!!
                val strongNewQuestion = t.startsWith(".") || t.startsWith("¿")
                val continuation = !strongNewQuestion && (
                    b.options.size < MIN_OPTIONS ||
                        t.first().isLowerCase() ||
                        p.isWrapped ||
                        (line.boldRatio() >= 0.8f && p.endsBold())
                    )
                if (continuation) {
                    val (label, text) = b.options.last()
                    b.options[b.options.lastIndex] = label to text.joinWith(line.toStyledText())
                    prev = line
                    continue
                }
                finish()
            }

            if (cur == null) {
                if (isQuestionSectionHeading(line)) {
                    val heading = t.trimStart('.', ' ', '-').trim()
                    if (awaitingTopicTitle && topicKey != UNKNOWN_TOPIC_KEY) {
                        sectionTitles[topicKey] = sentenceCase(heading)
                    } else {
                        subsection = sentenceCase(heading.trimEnd('.', ' '))
                    }
                    awaitingTopicTitle = false
                    prev = line
                    continue
                }
                awaitingTopicTitle = false
                val start = QUESTION_PREFIX.find(t)?.range?.last?.plus(1) ?: 0
                cur = QuestionBuilder(topicKey, line.page, line.sub(start), subsection).also {
                    if (line.page in ocrPages) it.fromOcr = true
                }
            } else {
                cur!!.text = cur!!.text.joinWith(line.toStyledText())
            }
            prev = line
        }
        finish()
        return out to sectionTitles
    }

    private fun isQuestionSectionHeading(line: StyledLine): Boolean {
        val t = line.text.trimStart('.', ' ')
        if (t.isEmpty()) return true
        val letters = t.filter { it.isLetter() }
        val upper = letters.isNotEmpty() && letters == letters.uppercase()
        if (t.startsWith("-") && upper) return true
        return line.boldRatio() >= 0.8f && (line.isIndented || upper) && !t.startsWith("¿")
    }

    private fun buildQuestion(b: QuestionBuilder, number: Int): ParsedQuestion {
        val issues = HashSet<QuestionIssue>()
        val text = b.text.text.trim()
        if (text.isEmpty()) issues += QuestionIssue.EMPTY_TEXT
        if (b.topicKey == UNKNOWN_TOPIC_KEY) issues += QuestionIssue.UNKNOWN_TOPIC
        if (b.options.size < MIN_OPTIONS) issues += QuestionIssue.TOO_FEW_OPTIONS

        val ratios = b.options.map { (_, st) ->
            val alnum = st.text.count { it.isLetterOrDigit() }
            val bold = st.text.indices.count { st.boldMask[it] && st.text[it].isLetterOrDigit() }
            if (alnum == 0) 0f else bold.toFloat() / alnum
        }
        var correct: Int? = null
        if (b.fromOcr) {
            issues += QuestionIssue.NO_STYLE_INFO
        } else if (ratios.isNotEmpty()) {
            val ranked = ratios.withIndex().sortedByDescending { it.value }
            val best = ranked[0]
            val second = ranked.getOrNull(1)?.value ?: 0f
            when {
                best.value < BOLD_MIN -> issues += QuestionIssue.NO_CORRECT_ANSWER
                second >= BOLD_SECOND_MAX -> issues += QuestionIssue.MULTIPLE_CORRECT_CANDIDATES
                else -> correct = best.index
            }
        }
        val options = b.options.map { (label, st) -> ParsedOption(label, st.text.trim()) }
        if (options.any { looksTruncated(it.text) }) issues += QuestionIssue.POSSIBLY_TRUNCATED
        if (correct == null && QuestionIssue.TOO_FEW_OPTIONS !in issues && QuestionIssue.NO_STYLE_INFO !in issues &&
            QuestionIssue.MULTIPLE_CORRECT_CANDIDATES !in issues) {
            issues += QuestionIssue.NO_CORRECT_ANSWER
        }
        return ParsedQuestion(
            topicKey = b.topicKey, numberInTopic = number, text = text, options = options,
            correctIndex = correct, issues = issues, page = b.page, subsection = b.subsection, boldRatios = ratios,
        )
    }

    /** e.g. "Serán depositadas en la Intervención de Armas y Exp" — ends in a short capitalized fragment. */
    private fun looksTruncated(option: String): Boolean {
        if (option.length < 25) return false
        val last = option.trimEnd()
        if (!last.last().isLetter()) return false
        val lastWord = last.substringAfterLast(' ')
        return lastWord.length in 1..3 && lastWord.first().isUpperCase() && lastWord !in setOf("A", "B", "C", "D", "E", "F", "UE", "DNI")
    }

    private fun markDuplicatesAndKeys(questions: List<ParsedQuestion>): List<ParsedQuestion> {
        val seen = HashMap<String, Int>()
        return questions.map { q ->
            val stem = TextNormalizer.comparable(q.text)
            val opts = q.options.map { TextNormalizer.comparable(it.text) }
            val dupKey = stem + "|" + opts.sorted().joinToString("|")
            val occurrence = seen.getOrDefault(dupKey, 0)
            seen[dupKey] = occurrence + 1
            val base = TextNormalizer.sha256(stem + "|" + opts.joinToString("|")).take(24)
            val key = if (occurrence == 0) base else "$base-$occurrence"
            if (occurrence > 0) q.copy(issues = q.issues + QuestionIssue.DUPLICATE, stableKey = key) else q.copy(stableKey = key)
        }
    }

    // ---------------------------------------------------------------------------------------
    // Exam preset
    // ---------------------------------------------------------------------------------------

    private fun extractExamPreset(text: String, topics: List<ParsedTopic>): ExamPreset? {
        val flat = text.replace(Regex("\\s+"), " ")
        val pass = Regex("""al menos (?:a )?(\d+) de las (\d+) preguntas""", RegexOption.IGNORE_CASE).find(flat)
        val time = Regex("""tiempo m[aá]ximo[^.]*?(\d+) minutos""", RegexOption.IGNORE_CASE).find(flat)
        val perTopic = LinkedHashMap<String, Int>()
        Regex("""del tema ([IVXLivxl]+|\d+)\s*:\s*(\d+) preguntas""", RegexOption.IGNORE_CASE).findAll(flat).forEach { m ->
            val n = m.groupValues[1].toIntOrNull() ?: TextNormalizer.romanToInt(m.groupValues[1])
            if (n != null && topics.any { it.key == "T$n" }) perTopic["T$n"] = m.groupValues[2].toInt()
        }
        val count = pass?.groupValues?.get(2)?.toIntOrNull() ?: perTopic.values.sum().takeIf { it > 0 } ?: return null
        val passMark = pass?.groupValues?.get(1)?.toIntOrNull()
        val validPerTopic = if (perTopic.values.sum() == count) perTopic else emptyMap()
        return ExamPreset(count, passMark, time?.groupValues?.get(1)?.toIntOrNull(), validPerTopic)
    }

    companion object {
        const val INTRO_KEY = "INTRO"
        const val UNKNOWN_TOPIC_KEY = "T0"
        private const val MIN_OPTIONS = 3
        private const val MAX_OPTIONS = 4
        private val LABELS = listOf("A", "B", "C", "D")
        /** Minimum share of bold characters for an option to be considered the marked answer. */
        const val BOLD_MIN = 0.4f
        /** If a second option reaches this share of bold characters, the answer is ambiguous. */
        const val BOLD_SECOND_MAX = 0.3f

        private val QUESTION_TOPIC = Regex("""^TEMA\s+([IVXL]+|\d+)\.?$""")
        private val THEORY_TOPIC = Regex("""^Tema\s+(\d+)\s*[:.]?$""", RegexOption.IGNORE_CASE)
        private val OPTION = Regex("""^([A-D])\s*(?:\)\s*\.?\s*-?|\.\s*-)\s*(.*)$""")
        private val SEPARATOR = Regex("""^[+=*_~#]{5,}$|^-{8,}$""")
        private val BULLET_GLYPH = Regex("""^[·•▪◦●]\s*|^[-–]\s+""")
        private val NUMBERED_ITEM = Regex("""^(\d{1,2}\.(?!ª)\s|[a-z]\)\s|[A-Z]\)\s)""")
        private val QUESTION_PREFIX = Regex("""^[.\s]+""")

        fun toRoman(n: Int): String {
            val numerals = listOf(10 to "X", 9 to "IX", 5 to "V", 4 to "IV", 1 to "I")
            var rest = n
            val sb = StringBuilder()
            for ((v, s) in numerals) while (rest >= v) { sb.append(s); rest -= v }
            return sb.toString()
        }

        fun sentenceCase(s: String): String {
            val letters = s.filter { it.isLetter() }
            if (letters.isEmpty() || letters != letters.uppercase()) return s
            val lower = s.lowercase()
            return lower.replaceFirstChar { it.uppercase() }
        }

        private fun topicOrder(key: String): Int = when {
            key == INTRO_KEY -> 0
            key.startsWith("T") -> 1
            else -> 2
        }
    }
}
