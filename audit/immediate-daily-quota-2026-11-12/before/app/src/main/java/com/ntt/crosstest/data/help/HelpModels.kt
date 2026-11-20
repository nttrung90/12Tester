package com.ntt.crosstest.data.help

sealed interface InlineSpan {
    val text: String

    data class Plain(override val text: String) : InlineSpan
    data class Bold(override val text: String) : InlineSpan
    data class Code(override val text: String) : InlineSpan
    data class Link(override val text: String, val url: String) : InlineSpan
}

sealed interface HelpBlock {
    data class Heading(val level: Int, val text: String, val spans: List<InlineSpan> = emptyList()) : HelpBlock
    data class Paragraph(val spans: List<InlineSpan>) : HelpBlock
    data class OrderedListItem(val number: Int, val spans: List<InlineSpan>) : HelpBlock
    data class BulletListItem(val spans: List<InlineSpan>) : HelpBlock
    data class Table(val headers: List<String>, val rows: List<List<List<InlineSpan>>>) : HelpBlock
}

data class GuideSection(
    val number: Int,
    val title: String,
    val blocks: List<HelpBlock>,
)

data class GuideDocument(
    val title: String,
    val introBlocks: List<HelpBlock>,
    val sections: List<GuideSection>,
)

data class FaqQuestion(
    val number: Int,
    val questionText: String,
    val answerBlocks: List<HelpBlock>,
)

data class FaqGroup(
    val title: String,
    val questions: List<FaqQuestion>,
)

data class FaqDocument(
    val title: String,
    val introBlocks: List<HelpBlock>,
    val groups: List<FaqGroup>,
)
