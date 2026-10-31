package com.github.ytlog.mobby.android.conversation.ui

import com.github.ytlog.mobby.android.conversation.ui.UiStrings as AppStrings

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.ClickableText
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.*
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import org.commonmark.node.*
import org.commonmark.parser.Parser
import org.commonmark.ext.gfm.tables.*
import org.commonmark.ext.gfm.strikethrough.*
import org.commonmark.ext.autolink.AutolinkExtension
import org.commonmark.ext.task.list.items.*
import java.net.URI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.withContext

internal object ReplyMarkdown {
    private fun parser() = Parser.builder().extensions(listOf(TablesExtension.create(), StrikethroughExtension.create(), AutolinkExtension.create(), TaskListItemsExtension.create())).build()
    fun parse(text: String): Node = parser().parse(text)
    fun children(node: Node): List<Node> = generateSequence(node.firstChild) { it.next }.toList()
    fun safeLink(destination: String): Boolean = runCatching {
        val uri = URI(destination)
        uri.scheme?.lowercase() in setOf("https", "http", "mailto") &&
            if (uri.scheme.equals("mailto", true)) !uri.schemeSpecificPart.isNullOrBlank() else !uri.host.isNullOrBlank()
    }.getOrDefault(false)
}

internal val LocalReplyParser = staticCompositionLocalOf<(String) -> Node> { ReplyMarkdown::parse }
@OptIn(ExperimentalCoroutinesApi::class)
private val replyParsingDispatcher = Dispatchers.Default.limitedParallelism(1)
private data class ParsedReply(val source: String, val document: Node)

@Composable internal fun ReplyContent(text: String, streaming: Boolean = false, read: (String, String) -> Unit) {
    val stream = if (streaming) rememberStreamPresentation(text, true) else StreamPresentation(text)
    val source = if (streaming) stream.markdown else text
    val parse = LocalReplyParser.current
    val parsed by produceState<ParsedReply?>(null, source, parse) {
        // withContext checks cancellation before returning, so superseded work cannot publish.
        value = if (source.isEmpty()) null else withContext(replyParsingDispatcher) { ParsedReply(source, parse(source)) }
    }
    val visible = parsed?.takeIf { source.startsWith(it.source) }
    Column {
        visible?.let { MarkdownBlocks(ReplyMarkdown.children(it.document), read) }
        if (!streaming && source.isNotEmpty() && visible == null) {
            Text(AppStrings.formatting, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable private fun MarkdownBlocks(nodes: List<Node>, read: (String, String) -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        nodes.forEach { node -> when (node) {
            is Heading -> MarkdownInline(node, when (node.level) { 1 -> MaterialTheme.typography.headlineSmall; 2 -> MaterialTheme.typography.titleLarge; else -> MaterialTheme.typography.titleMedium }, Modifier.semantics { heading() })
            is org.commonmark.node.Paragraph -> MarkdownInline(node)
            is FencedCodeBlock -> CodeContent(node.info.ifBlank { AppStrings.code }, node.literal, read)
            is IndentedCodeBlock -> CodeContent(AppStrings.code, node.literal, read)
            is BlockQuote -> Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).padding(12.dp)) {
                MarkdownBlocks(ReplyMarkdown.children(node), read)
            }
            is BulletList -> MarkdownList(node, null, read)
            is OrderedList -> MarkdownList(node, node.markerStartNumber ?: 1, read)
            is ThematicBreak -> HorizontalDivider()
            is TableBlock -> MarkdownTable(node)
            is HtmlBlock -> CodeContent("HTML", node.literal, read)
            is LinkReferenceDefinition -> Unit
            else -> if (node.firstChild != null) MarkdownBlocks(ReplyMarkdown.children(node), read)
        } }
    }
}

@Composable private fun MarkdownList(node: Node, start: Int?, read: (String, String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        ReplyMarkdown.children(node).forEachIndexed { index, item ->
            val children = ReplyMarkdown.children(item)
            val task = children.filterIsInstance<TaskListItemMarker>().firstOrNull()
            Row(Modifier.fillMaxWidth()) {
                Text(if (task != null) if (task.isChecked) "☑" else "☐" else if (start == null) "•" else "${start + index}.", Modifier.widthIn(min = 28.dp).padding(end = 8.dp), style = MaterialTheme.typography.bodyLarge)
                Box(Modifier.weight(1f)) { MarkdownBlocks(children.filterNot { it is TaskListItemMarker }, read) }
            }
        }
    }
}

/**
 * A table sized by its content: columns get their natural widths (64-320 dp), stretched to fill the reply when they
 * fit and shrunk toward 120 dp when they do not; only a table still too wide scrolls sideways. Cells in a row share
 * the tallest cell's height so their borders line up.
 */
@Composable private fun MarkdownTable(node: TableBlock) {
    val rows = ReplyMarkdown.children(node).flatMap { ReplyMarkdown.children(it) }
        .map { row -> ReplyMarkdown.children(row).filterIsInstance<TableCell>() }
    val columns = rows.maxOfOrNull { it.size } ?: 0
    if (columns == 0) return
    val outline = MaterialTheme.colorScheme.outlineVariant
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val available = constraints.maxWidth
        Layout(
            content = { rows.forEach { row -> repeat(columns) { column -> MarkdownTableCell(row.getOrNull(column), outline) } } },
            modifier = Modifier.horizontalScroll(rememberScrollState()).border(1.dp, outline),
        ) { measurables, _ ->
            val grid = measurables.chunked(columns)
            val shortest = 64.dp.roundToPx(); val longest = 320.dp.roundToPx(); val floor = 120.dp.roundToPx()
            val natural = (0 until columns).map { column -> grid.maxOf { it[column].maxIntrinsicWidth(Constraints.Infinity) }.coerceIn(shortest, longest) }
            val widths = tableColumnWidths(natural, natural.map { it.coerceAtMost(floor) }, available)
            val heights = grid.map { row -> row.indices.maxOf { column -> row[column].minIntrinsicHeight(widths[column]) } }
            val placeables = grid.mapIndexed { r, row -> row.mapIndexed { column, cell -> cell.measure(Constraints.fixed(widths[column], heights[r])) } }
            layout(widths.sum(), heights.sum()) {
                var y = 0
                placeables.forEachIndexed { r, row ->
                    var x = 0
                    row.forEachIndexed { column, cell -> cell.place(x, y); x += widths[column] }
                    y += heights[r]
                }
            }
        }
    }
}

@Composable private fun MarkdownTableCell(cell: TableCell?, outline: Color) {
    Box(Modifier.testTag("markdown-table-cell")
        .background(if (cell?.isHeader == true) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface)
        .border(0.5.dp, outline).padding(10.dp)) {
        cell ?: return@Box
        val alignment = when (cell.alignment) { TableCell.Alignment.CENTER -> androidx.compose.ui.text.style.TextAlign.Center; TableCell.Alignment.RIGHT -> androidx.compose.ui.text.style.TextAlign.End; else -> androidx.compose.ui.text.style.TextAlign.Start }
        MarkdownInline(cell, MaterialTheme.typography.bodyMedium.copy(fontWeight = if (cell.isHeader) FontWeight.SemiBold else FontWeight.Normal, textAlign = alignment))
    }
}

/**
 * Column widths in pixels for a table [available] wide. Columns that fit at their [natural] widths are stretched in
 * proportion until the table fills the width; wider tables shrink each column toward its [least] width, and only a
 * table whose floors still overflow keeps them and scrolls sideways.
 */
internal fun tableColumnWidths(natural: List<Int>, least: List<Int>, available: Int): List<Int> {
    val full = natural.sum()
    if (full <= available) return spread(natural, natural, available - full)
    val floor = least.sum()
    if (floor >= available) return least
    return spread(least, natural.zip(least) { n, l -> n - l }, available - floor)
}

/** Adds [extra] to [base] in proportion to [weights]; rounding leftovers go to the last column. */
private fun spread(base: List<Int>, weights: List<Int>, extra: Int): List<Int> {
    val total = weights.sum().toLong()
    var given = 0
    return base.mapIndexed { i, width ->
        val add = when {
            i == base.lastIndex -> extra - given
            total == 0L -> extra / base.size
            else -> (extra * weights[i] / total).toInt()
        }
        given += add
        width + add
    }
}

@Composable private fun MarkdownInline(node: Node, style: TextStyle = MaterialTheme.typography.bodyLarge, modifier: Modifier = Modifier) {
    val linkColor = MaterialTheme.colorScheme.primary
    val codeBackground = MaterialTheme.colorScheme.surfaceVariant
    val value = remember(node, linkColor, codeBackground) {
        buildAnnotatedString {
            fun visit(current: Node) {
                when (current) {
                    is org.commonmark.node.Text -> append(current.literal)
                    is Code -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = codeBackground)) { append(current.literal) }
                    is SoftLineBreak, is HardLineBreak -> append('\n')
                    is Emphasis -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { ReplyMarkdown.children(current).forEach(::visit) }
                    is StrongEmphasis -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { ReplyMarkdown.children(current).forEach(::visit) }
                    is Strikethrough -> withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) { ReplyMarkdown.children(current).forEach(::visit) }
                    is Link -> {
                        if (ReplyMarkdown.safeLink(current.destination)) {
                            pushStringAnnotation("URL", current.destination)
                            withStyle(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)) { ReplyMarkdown.children(current).forEach(::visit) }
                            pop()
                        } else ReplyMarkdown.children(current).forEach(::visit)
                    }
                    is Image -> {
                        append(AppStrings.image)
                        if (ReplyMarkdown.safeLink(current.destination)) pushStringAnnotation("URL", current.destination)
                        ReplyMarkdown.children(current).forEach(::visit)
                        if (ReplyMarkdown.safeLink(current.destination)) pop()
                        append(']')
                    }
                    is HtmlInline -> append(current.literal)
                    is TaskListItemMarker -> append(if (current.isChecked) "☑ " else "☐ ")
                    else -> ReplyMarkdown.children(current).forEach(::visit)
                }
            }
            visit(node)
        }
    }
    val links = value.getStringAnnotations("URL", 0, value.length)
    val handler = LocalUriHandler.current
    val context = LocalContext.current
    fun open(url: String) {
        try { handler.openUri(url) } catch (_: Exception) { android.widget.Toast.makeText(context, AppStrings.cannotOpenThisLink, android.widget.Toast.LENGTH_SHORT).show() }
    }
    SelectionContainer {
        if (links.isEmpty()) Text(value, modifier.fillMaxWidth(), style = style)
        else ClickableText(value, modifier.fillMaxWidth().semantics {
            customActions = links.map { link -> CustomAccessibilityAction(AppStrings.open2(value.text.substring(link.start, link.end))) { open(link.item); true } }
        }, style = style.copy(color = style.color.takeIf { it != Color.Unspecified } ?: LocalContentColor.current), onClick = { offset ->
            value.getStringAnnotations("URL", offset, offset).firstOrNull()?.let { open(it.item) }
        })
    }
}
