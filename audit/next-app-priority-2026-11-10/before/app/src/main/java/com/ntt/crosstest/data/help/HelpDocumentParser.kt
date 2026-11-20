package com.ntt.crosstest.data.help

object HelpDocumentParser {
    private val LINK_REGEX = Regex("""\[([^\]]+)\]\(([^)]+)\)""")
    private val CODE_REGEX = Regex("""`([^`]+)`""")
    private val BOLD_REGEX = Regex("""\*\*([^*]+)\*\*""")
    private val ORDERED_LIST_REGEX = Regex("""^(\d+)\.\s+(.*)$""")
    private val BULLET_LIST_REGEX = Regex("""^-\s+(.*)$""")
    private val QUESTION_HEADING_REGEX = Regex("""^###\s+(\d+)\.\s*(.*)$""")

    fun parseInline(text: String): List<InlineSpan> {
        if (text.isEmpty()) return emptyList()

        val spans = mutableListOf<InlineSpan>()
        var currentIndex = 0

        while (currentIndex < text.length) {
            val linkMatch = LINK_REGEX.find(text, currentIndex)
            val codeMatch = CODE_REGEX.find(text, currentIndex)
            val boldMatch = BOLD_REGEX.find(text, currentIndex)

            var earliestMatch: MatchResult? = null
            var matchType = 0 // 1: link, 2: code, 3: bold

            if (linkMatch != null) {
                earliestMatch = linkMatch
                matchType = 1
            }
            if (codeMatch != null && (earliestMatch == null || codeMatch.range.first < earliestMatch.range.first)) {
                earliestMatch = codeMatch
                matchType = 2
            }
            if (boldMatch != null && (earliestMatch == null || boldMatch.range.first < earliestMatch.range.first)) {
                earliestMatch = boldMatch
                matchType = 3
            }

            if (earliestMatch == null) {
                spans.add(InlineSpan.Plain(text.substring(currentIndex)))
                break
            }

            if (earliestMatch.range.first > currentIndex) {
                spans.add(InlineSpan.Plain(text.substring(currentIndex, earliestMatch.range.first)))
            }

            when (matchType) {
                1 -> {
                    val linkText = earliestMatch.groupValues[1]
                    val linkUrl = earliestMatch.groupValues[2]
                    spans.add(InlineSpan.Link(text = linkText, url = linkUrl))
                }
                2 -> {
                    val codeContent = earliestMatch.groupValues[1]
                    spans.add(InlineSpan.Code(text = codeContent))
                }
                3 -> {
                    val boldContent = earliestMatch.groupValues[1]
                    spans.add(InlineSpan.Bold(text = boldContent))
                }
            }

            currentIndex = earliestMatch.range.last + 1
        }

        return spans
    }

    fun splitTableRow(line: String): List<String> {
        val trimmed = line.trim()
        val raw = if (trimmed.startsWith("|")) trimmed.substring(1) else trimmed
        val cleaned = if (raw.endsWith("|")) raw.substring(0, raw.length - 1) else raw
        return cleaned.split("|").map { it.trim() }
    }

    private fun isTableDelimiterLine(line: String): Boolean {
        val cells = splitTableRow(line)
        if (cells.isEmpty()) return false
        return cells.all { cell -> cell.all { ch -> ch == '-' || ch == ':' || ch.isWhitespace() } }
    }

    fun parseBlocks(lines: List<String>): List<HelpBlock> {
        val blocks = mutableListOf<HelpBlock>()
        var i = 0

        while (i < lines.size) {
            val line = lines[i].trim()
            if (line.isEmpty()) {
                i++
                continue
            }

            if (line.startsWith("### ")) {
                val headingText = line.removePrefix("### ").trim()
                blocks.add(HelpBlock.Heading(level = 3, text = headingText, spans = parseInline(headingText)))
                i++
                continue
            }

            if (line.startsWith("|") && line.endsWith("|")) {
                val tableLines = mutableListOf<String>()
                while (i < lines.size && lines[i].trim().startsWith("|") && lines[i].trim().endsWith("|")) {
                    tableLines.add(lines[i].trim())
                    i++
                }
                if (tableLines.size >= 2) {
                    val headerCells = splitTableRow(tableLines[0])
                    val startIndex = if (isTableDelimiterLine(tableLines[1])) 2 else 1
                    val dataRows = mutableListOf<List<List<InlineSpan>>>()
                    for (rowIndex in startIndex until tableLines.size) {
                        val cells = splitTableRow(tableLines[rowIndex])
                        val parsedCells = cells.map { cellText -> parseInline(cellText) }
                        dataRows.add(parsedCells)
                    }
                    blocks.add(HelpBlock.Table(headers = headerCells, rows = dataRows))
                }
                continue
            }

            val orderedMatch = ORDERED_LIST_REGEX.find(line)
            if (orderedMatch != null) {
                val number = orderedMatch.groupValues[1].toInt()
                val content = orderedMatch.groupValues[2].trim()
                blocks.add(HelpBlock.OrderedListItem(number = number, spans = parseInline(content)))
                i++
                continue
            }

            val bulletMatch = BULLET_LIST_REGEX.find(line)
            if (bulletMatch != null) {
                val content = bulletMatch.groupValues[1].trim()
                blocks.add(HelpBlock.BulletListItem(spans = parseInline(content)))
                i++
                continue
            }

            val paraLines = mutableListOf<String>()
            while (i < lines.size) {
                val cur = lines[i].trim()
                if (cur.isEmpty() || cur.startsWith("### ") || (cur.startsWith("|") && cur.endsWith("|")) ||
                    ORDERED_LIST_REGEX.containsMatchIn(cur) || BULLET_LIST_REGEX.containsMatchIn(cur)
                ) {
                    break
                }
                paraLines.add(cur)
                i++
            }
            if (paraLines.isNotEmpty()) {
                val paragraphText = paraLines.joinToString(" ")
                blocks.add(HelpBlock.Paragraph(spans = parseInline(paragraphText)))
            }
        }

        return blocks
    }

    fun parseGuideDocument(markdown: String): GuideDocument {
        val allLines = markdown.lines()
        var title = ""
        val introLines = mutableListOf<String>()
        val sections = mutableListOf<GuideSection>()

        var i = 0
        while (i < allLines.size) {
            val line = allLines[i].trim()
            if (line.startsWith("# ")) {
                title = line.removePrefix("# ").trim()
                i++
                break
            }
            i++
        }

        while (i < allLines.size) {
            val line = allLines[i].trim()
            if (line.startsWith("## ")) {
                break
            }
            introLines.add(allLines[i])
            i++
        }

        val introBlocks = parseBlocks(introLines)

        while (i < allLines.size) {
            val line = allLines[i].trim()
            if (line.startsWith("## ")) {
                val rawSectionTitle = line.removePrefix("## ").trim()
                val sectionNumber = Regex("""^(\d+)\.""").find(rawSectionTitle)?.groupValues?.get(1)?.toIntOrNull()
                    ?: (sections.size + 1)
                i++
                val sectionLines = mutableListOf<String>()
                while (i < allLines.size && !allLines[i].trim().startsWith("## ")) {
                    sectionLines.add(allLines[i])
                    i++
                }
                val blocks = parseBlocks(sectionLines)
                sections.add(
                    GuideSection(
                        number = sectionNumber,
                        title = rawSectionTitle,
                        blocks = blocks,
                    )
                )
            } else {
                i++
            }
        }

        return GuideDocument(
            title = title,
            introBlocks = introBlocks,
            sections = sections,
        )
    }

    fun parseFaqDocument(markdown: String): FaqDocument {
        val allLines = markdown.lines()
        var title = ""
        val introLines = mutableListOf<String>()
        val groups = mutableListOf<FaqGroup>()

        var i = 0
        while (i < allLines.size) {
            val line = allLines[i].trim()
            if (line.startsWith("# ")) {
                title = line.removePrefix("# ").trim()
                i++
                break
            }
            i++
        }

        while (i < allLines.size) {
            val line = allLines[i].trim()
            if (line.startsWith("## ")) {
                break
            }
            introLines.add(allLines[i])
            i++
        }

        val introBlocks = parseBlocks(introLines)

        while (i < allLines.size) {
            val line = allLines[i].trim()
            if (line.startsWith("## ")) {
                val groupTitle = line.removePrefix("## ").trim()
                i++
                val groupQuestions = mutableListOf<FaqQuestion>()

                while (i < allLines.size && !allLines[i].trim().startsWith("## ")) {
                    val curLine = allLines[i].trim()
                    val qMatch = QUESTION_HEADING_REGEX.find(curLine)
                    if (qMatch != null) {
                        val qNum = qMatch.groupValues[1].toInt()
                        val qText = qMatch.groupValues[2].trim()

                        i++
                        val answerLines = mutableListOf<String>()
                        while (i < allLines.size) {
                            val nextLine = allLines[i].trim()
                            if (nextLine.startsWith("### ") || nextLine.startsWith("## ")) {
                                break
                            }
                            answerLines.add(allLines[i])
                            i++
                        }
                        val answerBlocks = parseBlocks(answerLines)
                        groupQuestions.add(
                            FaqQuestion(
                                number = qNum,
                                questionText = qText,
                                answerBlocks = answerBlocks,
                            )
                        )
                    } else {
                        i++
                    }
                }
                groups.add(FaqGroup(title = groupTitle, questions = groupQuestions))
            } else {
                i++
            }
        }

        return FaqDocument(
            title = title,
            introBlocks = introBlocks,
            groups = groups,
        )
    }
}
