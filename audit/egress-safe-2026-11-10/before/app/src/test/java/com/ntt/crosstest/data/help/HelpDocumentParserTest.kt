package com.ntt.crosstest.data.help

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class HelpDocumentParserTest {

    private fun resolveFile(name: String): File {
        val candidates = listOf(
            File(name),
            File("..", name),
            File("app", name),
            File("../app", name),
        )
        for (f in candidates) {
            if (f.exists()) return f
        }
        throw AssertionError("File not found: $name (cwd=${File(".").absolutePath})")
    }

    @Test
    fun testGuideDocumentParsingCompleteness() {
        val guideFile = resolveFile("HUONG_DAN_SU_DUNG_12_CROSS_TESTERS.md")
        assertTrue("Guide markdown must exist", guideFile.exists())

        val markdown = guideFile.readText(Charsets.UTF_8)
        val guide = HelpDocumentParser.parseGuideDocument(markdown)

        assertEquals("Document title mismatch", "Hướng dẫn sử dụng 12 Cross Testers", guide.title)
        assertTrue("Intro blocks should not be empty", guide.introBlocks.isNotEmpty())

        // Must have exactly 6 guide sections
        assertEquals("Must contain all 6 guide sections", 6, guide.sections.size)

        val expectedSectionTitles = listOf(
            "1. Bắt đầu",
            "2. Kiểm thử app và nộp báo cáo",
            "3. Nhiệm vụ và chỉ tiêu hôm nay",
            "4. Đăng ứng dụng để nhận tester",
            "5. Quản lý ứng dụng đã đăng",
            "6. Hồ sơ, thông báo và hỗ trợ",
        )

        for (i in 0 until 6) {
            val section = guide.sections[i]
            assertEquals("Section number mismatch at index $i", i + 1, section.number)
            assertEquals("Section title mismatch at index $i", expectedSectionTitles[i], section.title)
            assertTrue("Section $i blocks must not be empty", section.blocks.isNotEmpty())
        }

        // Section 1 table: "Các tab chính" with 5 rows
        val sec1Tables = guide.sections[0].blocks.filterIsInstance<HelpBlock.Table>()
        assertEquals("Section 1 should have 1 table", 1, sec1Tables.size)
        assertEquals("Section 1 table should have 5 rows", 5, sec1Tables[0].rows.size)
        assertEquals("Section 1 table headers", listOf("Tab", "Bạn có thể làm gì"), sec1Tables[0].headers)

        // Section 2 table: "Trạng thái", "Bạn có thể làm gì" with 4 rows
        val sec2Tables = guide.sections[1].blocks.filterIsInstance<HelpBlock.Table>()
        assertEquals("Section 2 should have 1 table", 1, sec2Tables.size)
        assertEquals("Section 2 table should have 4 rows", 4, sec2Tables[0].rows.size)
        assertEquals("Section 2 table headers", listOf("Trạng thái", "Bạn có thể làm gì"), sec2Tables[0].headers)

        // Section 5 table: "Tính năng", "Cách dùng" with 3 rows
        val sec5Tables = guide.sections[4].blocks.filterIsInstance<HelpBlock.Table>()
        assertEquals("Section 5 should have 1 table", 1, sec5Tables.size)
        assertEquals("Section 5 table should have 3 rows", 3, sec5Tables[0].rows.size)
        assertEquals("Section 5 table headers", listOf("Tính năng", "Cách dùng"), sec5Tables[0].headers)

        // Section 6 table: "Tình huống", "Cách xử lý" with 7 rows
        val sec6Tables = guide.sections[5].blocks.filterIsInstance<HelpBlock.Table>()
        assertEquals("Section 6 should have 1 table", 1, sec6Tables.size)
        assertEquals("Section 6 table should have 7 rows", 7, sec6Tables[0].rows.size)
        assertEquals("Section 6 table headers", listOf("Tình huống", "Cách xử lý"), sec6Tables[0].headers)

        // Verify code formatting parsed (e.g. versionCode in section 5 table)
        val sec5CodeSpans = sec5Tables[0].rows
            .flatMap { it.flatten() }
            .filterIsInstance<InlineSpan.Code>()
        assertTrue("Should parse versionCode code span in section 5 table", sec5CodeSpans.any { it.text == "versionCode" })

        // Verify external links in guide
        val allGuideSpans = guide.sections.flatMap { it.blocks }.flatMap { block ->
            when (block) {
                is HelpBlock.Paragraph -> block.spans
                is HelpBlock.OrderedListItem -> block.spans
                is HelpBlock.BulletListItem -> block.spans
                is HelpBlock.Heading -> block.spans
                is HelpBlock.Table -> block.rows.flatMap { it.flatten() }
            }
        }
        val links = allGuideSpans.filterIsInstance<InlineSpan.Link>()
        assertTrue("Guide must contain Google Group link", links.any { it.url == "https://groups.google.com/g/testappLV" })
        assertTrue("Guide must contain Play Console closed testing link", links.any { it.url.contains("answer/9845334") })
        assertTrue("Guide must contain Play Console requirements link", links.any { it.url.contains("answer/14151465") })
    }

    @Test
    fun testFaqDocumentParsingCompleteness() {
        val faqFile = resolveFile("CAU_HOI_THUONG_GAP_12_CROSS_TESTERS.md")
        assertTrue("FAQ markdown must exist", faqFile.exists())

        val markdown = faqFile.readText(Charsets.UTF_8)
        val faq = HelpDocumentParser.parseFaqDocument(markdown)

        assertEquals("Document title mismatch", "Câu hỏi thường gặp về 12 Cross Testers", faq.title)

        // Verify internal link to guide in intro
        val introLinks = faq.introBlocks.flatMap { block ->
            when (block) {
                is HelpBlock.Paragraph -> block.spans
                is HelpBlock.OrderedListItem -> block.spans
                is HelpBlock.BulletListItem -> block.spans
                is HelpBlock.Heading -> block.spans
                is HelpBlock.Table -> block.rows.flatMap { it.flatten() }
            }
        }.filterIsInstance<InlineSpan.Link>()

        val internalGuideLink = introLinks.find { it.url == "HUONG_DAN_SU_DUNG_12_CROSS_TESTERS.md" }
        assertNotNull("FAQ intro must have internal link to guide", internalGuideLink)
        assertEquals("Hướng dẫn sử dụng 12 Cross Testers", internalGuideLink?.text)

        // Must have exactly 5 groups
        assertEquals("Must contain exactly 5 FAQ groups", 5, faq.groups.size)

        val expectedGroupTitles = listOf(
            "Tài khoản và cài bản thử nghiệm",
            "Báo cáo kiểm thử",
            "Chỉ tiêu, nhiệm vụ và Credit",
            "Dành cho chủ app",
            "Hỗ trợ",
        )

        for (i in 0 until 5) {
            assertEquals("Group $i title mismatch", expectedGroupTitles[i], faq.groups[i].title)
            assertTrue("Group $i must have questions", faq.groups[i].questions.isNotEmpty())
        }

        // Check question count per group
        assertEquals("Group 1 must have 4 questions (1..4)", 4, faq.groups[0].questions.size)
        assertEquals("Group 2 must have 6 questions (5..10)", 6, faq.groups[1].questions.size)
        assertEquals("Group 3 must have 7 questions (11..17)", 7, faq.groups[2].questions.size)
        assertEquals("Group 4 must have 9 questions (18..26)", 9, faq.groups[3].questions.size)
        assertEquals("Group 5 must have 1 question (27)", 1, faq.groups[4].questions.size)

        // Total 27 questions in exact order
        val allQuestions = faq.groups.flatMap { it.questions }
        assertEquals("Total questions must be exactly 27", 27, allQuestions.size)

        for (i in 1..27) {
            val q = allQuestions[i - 1]
            assertEquals("Question number mismatch at index ${i - 1}", i, q.number)
            assertFalse("Question $i text should not be blank", q.questionText.isBlank())
            assertTrue("Question $i answer blocks should not be empty", q.answerBlocks.isNotEmpty())
        }

        // Verify specific question texts
        assertTrue(
            "Question 1 text mismatch",
            allQuestions[0].questionText.contains("Tôi có cần có app riêng mới tham gia được không")
        )
        assertTrue(
            "Question 27 text mismatch",
            allQuestions[26].questionText.contains("Tài khoản bị tạm khóa hoặc tôi cần báo lỗi thì liên hệ ở đâu")
        )

        // External HTTPS links in FAQ
        val allFaqSpans = allQuestions.flatMap { it.answerBlocks }.flatMap { block ->
            when (block) {
                is HelpBlock.Paragraph -> block.spans
                is HelpBlock.OrderedListItem -> block.spans
                is HelpBlock.BulletListItem -> block.spans
                is HelpBlock.Heading -> block.spans
                is HelpBlock.Table -> block.rows.flatMap { it.flatten() }
            }
        }
        val faqLinks = allFaqSpans.filterIsInstance<InlineSpan.Link>()
        assertTrue("FAQ must contain Google Group link", faqLinks.any { it.url == "https://groups.google.com/g/testappLV" })
        assertTrue("FAQ must contain Play Console requirements link", faqLinks.any { it.url.contains("answer/14151465") })
    }

    @Test
    fun testInlineFormattingParser() {
        val input = "Test with **bold text**, `code block` and [Google Group](https://groups.google.com/g/testappLV) link."
        val spans = HelpDocumentParser.parseInline(input)

        assertEquals(7, spans.size)
        assertEquals(InlineSpan.Plain("Test with "), spans[0])
        assertEquals(InlineSpan.Bold("bold text"), spans[1])
        assertEquals(InlineSpan.Plain(", "), spans[2])
        assertEquals(InlineSpan.Code("code block"), spans[3])
        assertEquals(InlineSpan.Plain(" and "), spans[4])
        assertEquals(InlineSpan.Link(text = "Google Group", url = "https://groups.google.com/g/testappLV"), spans[5])
        assertEquals(InlineSpan.Plain(" link."), spans[6])
    }

    @Test(timeout = 2000)
    fun testUnsupportedHeadingDoesNotStallParsing() {
        val blocks = HelpDocumentParser.parseBlocks(listOf("#### Ghi chú", "", "Nội dung tiếp theo."))

        assertEquals(
            listOf(
                HelpBlock.Paragraph(listOf(InlineSpan.Plain("#### Ghi chú"))),
                HelpBlock.Paragraph(listOf(InlineSpan.Plain("Nội dung tiếp theo."))),
            ),
            blocks,
        )
    }

    @Test
    fun testLocalizedHelpXmlParity() {
        val baseXml = resolveFile("src/main/res/values/help_faq.xml")
        val enXml = resolveFile("src/main/res/values-en/help_faq.xml")
        val viXml = resolveFile("src/main/res/values-vi/help_faq.xml")

        assertTrue("values/help_faq.xml must exist", baseXml.exists())
        assertTrue("values-en/help_faq.xml must exist", enXml.exists())
        assertTrue("values-vi/help_faq.xml must exist", viXml.exists())

        fun extractKeys(file: File): Set<String> {
            val keyRegex = Regex("""<string\s+name="([^"]+)">""")
            return keyRegex.findAll(file.readText()).map { it.groupValues[1] }.toSet()
        }

        val baseKeys = extractKeys(baseXml)
        val enKeys = extractKeys(enXml)
        val viKeys = extractKeys(viXml)

        val expectedKeys = setOf(
            "help_faq_title",
            "help_faq_desc",
            "help_tab_guide",
            "help_tab_faq",
            "help_action_expand",
            "help_action_collapse",
            "help_state_expanded",
            "help_state_collapsed",
        )

        assertEquals("Base keys must match expected", expectedKeys, baseKeys)
        assertEquals("EN keys must match base", baseKeys, enKeys)
        assertEquals("VI keys must match base", baseKeys, viKeys)
    }
}
