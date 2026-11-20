package com.ntt.crosstest.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ntt.crosstest.R
import com.ntt.crosstest.data.help.FaqDocument
import com.ntt.crosstest.data.help.GuideDocument
import com.ntt.crosstest.data.help.HelpBlock
import com.ntt.crosstest.data.help.HelpContentRepository
import com.ntt.crosstest.data.help.InlineSpan

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HelpScreen(
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val guideDocument = remember(context) { HelpContentRepository.getGuideDocument(context) }
    val faqDocument = remember(context) { HelpContentRepository.getFaqDocument(context) }

    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    var expandedQuestionNumber by rememberSaveable { mutableStateOf<Int?>(null) }
    val guideListState = rememberLazyListState()
    val faqListState = rememberLazyListState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.help_faq_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                ),
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            PrimaryTabRow(
                selectedTabIndex = selectedTab,
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.primary,
            ) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = {
                        Text(
                            text = stringResource(R.string.help_tab_guide),
                            fontWeight = if (selectedTab == 0) FontWeight.Bold else FontWeight.Medium,
                        )
                    },
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = {
                        Text(
                            text = stringResource(R.string.help_tab_faq),
                            fontWeight = if (selectedTab == 1) FontWeight.Bold else FontWeight.Medium,
                        )
                    },
                )
            }

            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                when (selectedTab) {
                    0 -> GuideTabContent(
                        document = guideDocument,
                        listState = guideListState,
                        onNavigateToGuideTab = { selectedTab = 0 },
                    )
                    1 -> FaqTabContent(
                        document = faqDocument,
                        listState = faqListState,
                        expandedQuestionNumber = expandedQuestionNumber,
                        onToggleQuestion = { qNum ->
                            expandedQuestionNumber = if (expandedQuestionNumber == qNum) null else qNum
                        },
                        onNavigateToGuideTab = { selectedTab = 0 },
                    )
                }
            }
        }
    }
}

@Composable
private fun GuideTabContent(
    document: GuideDocument,
    listState: LazyListState,
    onNavigateToGuideTab: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        state = listState,
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 16.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Text(
                text = document.title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(8.dp))
        }

        items(document.introBlocks) { block ->
            RenderBlock(block = block, onNavigateToGuideTab = onNavigateToGuideTab)
        }

        for (section in document.sections) {
            item(key = "section_${section.number}") {
                Spacer(Modifier.height(16.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                Spacer(Modifier.height(12.dp))
                Text(
                    text = section.title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(4.dp))
            }

            items(section.blocks) { block ->
                RenderBlock(block = block, onNavigateToGuideTab = onNavigateToGuideTab)
            }
        }
    }
}

@Composable
private fun FaqTabContent(
    document: FaqDocument,
    listState: LazyListState,
    expandedQuestionNumber: Int?,
    onToggleQuestion: (Int) -> Unit,
    onNavigateToGuideTab: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val expandedStateText = stringResource(R.string.help_state_expanded)
    val collapsedStateText = stringResource(R.string.help_state_collapsed)
    val expandActionLabel = stringResource(R.string.help_action_expand)
    val collapseActionLabel = stringResource(R.string.help_action_collapse)

    LazyColumn(
        state = listState,
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 16.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Text(
                text = document.title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(8.dp))
        }

        items(document.introBlocks) { block ->
            RenderBlock(block = block, onNavigateToGuideTab = onNavigateToGuideTab)
        }

        for (group in document.groups) {
            item(key = "group_${group.title}") {
                Spacer(Modifier.height(16.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                Spacer(Modifier.height(12.dp))
                Text(
                    text = group.title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(4.dp))
            }

            items(
                items = group.questions,
                key = { "faq_${it.number}" },
            ) { question ->
                val isExpanded = expandedQuestionNumber == question.number
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (isExpanded) {
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f)
                        }
                    ),
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(
                                    role = Role.Button,
                                    onClickLabel = if (isExpanded) collapseActionLabel else expandActionLabel,
                                ) {
                                    onToggleQuestion(question.number)
                                }
                                .semantics {
                                    stateDescription = if (isExpanded) expandedStateText else collapsedStateText
                                }
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "${question.number}. ${question.questionText}",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = if (isExpanded) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f),
                            )
                            Spacer(Modifier.width(8.dp))
                            Icon(
                                imageVector = if (isExpanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                                contentDescription = null,
                                tint = if (isExpanded) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(24.dp),
                            )
                        }

                        if (isExpanded) {
                            HorizontalDivider(
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                                modifier = Modifier.padding(horizontal = 16.dp),
                            )
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                for (block in question.answerBlocks) {
                                    RenderBlock(
                                        block = block,
                                        onNavigateToGuideTab = onNavigateToGuideTab,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RenderBlock(
    block: HelpBlock,
    onNavigateToGuideTab: () -> Unit,
) {
    when (block) {
        is HelpBlock.Heading -> {
            when (block.level) {
                2 -> Text(
                    text = block.text,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
                )
                else -> Text(
                    text = block.text,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(top = 10.dp, bottom = 2.dp),
                )
            }
        }
        is HelpBlock.Paragraph -> {
            RenderAnnotatedSpans(
                spans = block.spans,
                style = MaterialTheme.typography.bodyMedium,
                onNavigateToGuideTab = onNavigateToGuideTab,
            )
        }
        is HelpBlock.OrderedListItem -> {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 2.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Text(
                    text = "${block.number}.",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.width(28.dp),
                )
                RenderAnnotatedSpans(
                    spans = block.spans,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                    onNavigateToGuideTab = onNavigateToGuideTab,
                )
            }
        }
        is HelpBlock.BulletListItem -> {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 2.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Text(
                    text = "•",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.width(18.dp),
                )
                RenderAnnotatedSpans(
                    spans = block.spans,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                    onNavigateToGuideTab = onNavigateToGuideTab,
                )
            }
        }
        is HelpBlock.Table -> {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                for (row in block.rows) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                        ),
                        shape = RoundedCornerShape(8.dp),
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            if (row.isNotEmpty()) {
                                RenderAnnotatedSpans(
                                    spans = row[0],
                                    style = MaterialTheme.typography.bodyMedium.copy(
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary,
                                    ),
                                    onNavigateToGuideTab = onNavigateToGuideTab,
                                )
                            }
                            if (row.size > 1) {
                                RenderAnnotatedSpans(
                                    spans = row[1],
                                    style = MaterialTheme.typography.bodyMedium,
                                    onNavigateToGuideTab = onNavigateToGuideTab,
                                )
                            }
                            for (colIndex in 2 until row.size) {
                                RenderAnnotatedSpans(
                                    spans = row[colIndex],
                                    style = MaterialTheme.typography.bodySmall,
                                    onNavigateToGuideTab = onNavigateToGuideTab,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RenderAnnotatedSpans(
    spans: List<InlineSpan>,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyMedium,
    onNavigateToGuideTab: () -> Unit,
) {
    val uriHandler = LocalUriHandler.current

    val annotatedString = buildAnnotatedString {
        for (span in spans) {
            when (span) {
                is InlineSpan.Plain -> append(span.text)
                is InlineSpan.Bold -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                    append(span.text)
                }
                is InlineSpan.Code -> {
                    withStyle(
                        SpanStyle(
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.tertiary,
                        )
                    ) {
                        append(span.text)
                    }
                }
                is InlineSpan.Link -> {
                    val styles = TextLinkStyles(
                        style = SpanStyle(
                            color = MaterialTheme.colorScheme.primary,
                            textDecoration = TextDecoration.Underline,
                            fontWeight = FontWeight.SemiBold,
                        ),
                    )
                    when {
                        span.url == HelpContentRepository.GUIDE_ASSET_NAME -> {
                            withLink(LinkAnnotation.Clickable("guide", styles) { onNavigateToGuideTab() }) {
                                append(span.text)
                            }
                        }
                        span.url.startsWith("https://") -> {
                            withLink(LinkAnnotation.Url(span.url, styles) {
                                try {
                                    uriHandler.openUri(span.url)
                                } catch (_: IllegalArgumentException) {
                                    // The device has no activity to handle this link.
                                }
                            }) {
                                append(span.text)
                            }
                        }
                        else -> append(span.text)
                    }
                }
            }
        }
    }

    Text(
        text = annotatedString,
        modifier = modifier,
        style = style,
        lineHeight = 22.sp,
    )
}
