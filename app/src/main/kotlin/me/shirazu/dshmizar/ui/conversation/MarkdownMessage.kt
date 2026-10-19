package me.shirazu.dshmizar.ui.conversation

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.shirazu.dshmizar.R
import me.shirazu.dshmizar.ui.design.ContinuousCornerShape
import me.shirazu.dshmizar.ui.design.Corner
import me.shirazu.dshmizar.ui.design.Radius
import me.shirazu.dshmizar.ui.design.Space
import me.shirazu.mizar.kit.Markdown
import me.shirazu.mizar.kit.MarkdownStyle

/**
 * A model's answer, drawn block by block -- ported from iOS
 * `Views/Conversation/MarkdownMessage.swift`.
 *
 * Block structure (paragraphs, headings, lists, quotes, code, tables, dividers)
 * is cut here, in the app layer: it is a drawing concern, and the protocol
 * module's `Markdown` parser stays the single, tested owner of *inline* styling
 * (bold, italic, code spans, links), which each block's text is still passed
 * through. A body that defeats the block cutter falls back to one paragraph of
 * verbatim text -- never nothing.
 */

/** How a table column is aligned, from its delimiter cell. */
internal enum class CellAlign { START, CENTER, END }

internal sealed interface MarkdownBlock {
    data class Paragraph(val text: String) : MarkdownBlock
    data class Heading(val level: Int, val text: String) : MarkdownBlock
    data class ListItem(val marker: String, val text: String, val indent: Int)
    data class ListBlock(val items: List<ListItem>) : MarkdownBlock
    data class Quote(val text: String) : MarkdownBlock
    data class Code(val language: String?, val text: String) : MarkdownBlock
    data class Table(val header: List<String>, val align: List<CellAlign>, val rows: List<List<String>>) : MarkdownBlock
    data object Divider : MarkdownBlock
}

/** The block cutter. Line-based, and it never throws: worst case is one paragraph. */
internal object MarkdownBlocks {

    /**
     * Whether the body is worth cutting into blocks at all. A superset of the
     * protocol parser's gate: it also looks for the table pipe, which the
     * inline parser has no use for.
     */
    fun looksBlocky(text: String): Boolean = Markdown.looksLikeMarkdown(text) || text.contains('|')

    fun parse(source: String): List<MarkdownBlock> {
        val lines = source.split('\n')
        val blocks = mutableListOf<MarkdownBlock>()
        val paragraph = StringBuilder()

        fun flushParagraph() {
            val text = paragraph.toString().trim()
            if (text.isNotEmpty()) blocks += MarkdownBlock.Paragraph(text)
            paragraph.clear()
        }

        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val trimmed = line.trim()

            // Fenced code. An unterminated fence is the half-streamed case:
            // everything after it is code, which is what the reader is watching.
            if (trimmed.startsWith("```")) {
                flushParagraph()
                val language = trimmed.removePrefix("```").trim().ifEmpty { null }
                val body = StringBuilder()
                i++
                while (i < lines.size) {
                    if (lines[i].trim().startsWith("```")) { i++; break }
                    body.append(lines[i]).append('\n')
                    i++
                }
                blocks += MarkdownBlock.Code(language, body.toString().trimEnd('\n'))
                continue
            }

            if (trimmed.isEmpty()) {
                flushParagraph()
                i++
                continue
            }

            if (DIVIDER.matches(trimmed)) {
                flushParagraph()
                blocks += MarkdownBlock.Divider
                i++
                continue
            }

            // A table is a header line, a delimiter line, then rows -- all piped.
            if (trimmed.contains('|') && i + 1 < lines.size && isTableDelimiter(lines[i + 1])) {
                flushParagraph()
                val header = splitCells(trimmed)
                val align = splitCells(lines[i + 1]).map { cell ->
                    val c = cell.trim()
                    when {
                        c.startsWith(":") && c.endsWith(":") -> CellAlign.CENTER
                        c.endsWith(":") -> CellAlign.END
                        else -> CellAlign.START
                    }
                }
                i += 2
                val rows = mutableListOf<List<String>>()
                while (i < lines.size && lines[i].isNotBlank() && lines[i].trim().contains('|')) {
                    val cells = splitCells(lines[i])
                    rows += List(header.size) { index -> cells.getOrElse(index) { "" } }
                    i++
                }
                blocks += MarkdownBlock.Table(header, align, rows)
                continue
            }

            val heading = HEADING.matchEntire(line)
            if (heading != null) {
                flushParagraph()
                blocks += MarkdownBlock.Heading(heading.groupValues[1].length, heading.groupValues[2])
                i++
                continue
            }

            val item = LIST_ITEM.matchEntire(line)
            if (item != null) {
                flushParagraph()
                val items = mutableListOf<MarkdownBlock.ListItem>()
                while (i < lines.size) {
                    val m = LIST_ITEM.matchEntire(lines[i]) ?: break
                    val indent = m.groupValues[1].replace("\t", "    ").length / 2
                    // A numbered item keeps its number; a bullet is drawn as one glyph.
                    val marker = if (NUMBER_MARKER.matches(m.groupValues[2])) m.groupValues[2] else "•"
                    items += MarkdownBlock.ListItem(marker, m.groupValues[3], indent)
                    i++
                }
                blocks += MarkdownBlock.ListBlock(items)
                continue
            }

            if (trimmed.startsWith("> ")) {
                flushParagraph()
                val quote = StringBuilder()
                while (i < lines.size && lines[i].trim().startsWith("> ")) {
                    if (quote.isNotEmpty()) quote.append('\n')
                    quote.append(lines[i].trim().removePrefix("> "))
                    i++
                }
                blocks += MarkdownBlock.Quote(quote.toString())
                continue
            }

            if (paragraph.isNotEmpty()) paragraph.append('\n')
            paragraph.append(line)
            i++
        }
        flushParagraph()

        // **Never lose a message.** A body that cuts to nothing -- a lone fence
        // marker is the degenerate case -- is drawn verbatim.
        if (blocks.isEmpty() && source.isNotBlank()) {
            blocks += MarkdownBlock.Paragraph(source)
        }
        return blocks
    }

    private fun isTableDelimiter(line: String): Boolean {
        val trimmed = line.trim()
        return trimmed.contains('-') && DELIMITER_CHARSET.matches(trimmed)
    }

    private fun splitCells(line: String): List<String> {
        var l = line.trim()
        if (l.startsWith("|")) l = l.substring(1)
        if (l.endsWith("|")) l = l.dropLast(1)
        return l.split('|').map { it.trim() }
    }

    private val HEADING = Regex("^\\s*(#{1,6})\\s+(.*)$")
    private val LIST_ITEM = Regex("^(\\s*)([-*+]|\\d+[.)])\\s+(.*)$")
    private val NUMBER_MARKER = Regex("\\d+[.)]")
    private val DIVIDER = Regex("^(-{3,}|_{3,}|\\*{3,})$")
    private val DELIMITER_CHARSET = Regex("^[\\s|:\\-]+$")
}

/**
 * One run of inline markdown, styled by the protocol parser. Code and link
 * colours come from the caller: inside an accent bubble they are the bubble's
 * ink, on the page they are the theme's.
 */
@Composable
internal fun inlineMarkdown(source: String, codeColor: Color, linkColor: Color): AnnotatedString {
    val document = remember(source) { Markdown.parse(source) }
    if (document.plain) return AnnotatedString(document.text)
    return remember(document, codeColor, linkColor) {
        buildAnnotatedString {
            append(document.text)
            for (span in document.spans) {
                if (span.end > document.text.length) continue
                val style = when (span.style) {
                    MarkdownStyle.BOLD -> SpanStyle(fontWeight = FontWeight.Bold)
                    MarkdownStyle.ITALIC -> SpanStyle(fontStyle = FontStyle.Italic)
                    MarkdownStyle.CODE, MarkdownStyle.CODE_BLOCK ->
                        SpanStyle(fontFamily = FontFamily.Monospace, color = codeColor)

                    MarkdownStyle.HEADING -> SpanStyle(fontWeight = FontWeight.Bold)
                    MarkdownStyle.LIST_ITEM -> SpanStyle(fontWeight = FontWeight.Normal)
                    MarkdownStyle.QUOTE -> SpanStyle(fontStyle = FontStyle.Italic)
                    MarkdownStyle.LINK ->
                        SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)
                }
                addStyle(style, span.start, span.end)
            }
        }
    }
}

/** The blocks of a message, as a column. Selection is the caller's to wrap. */
@Composable
internal fun MarkdownMessage(
    text: String,
    color: Color,
    codeColor: Color,
    linkColor: Color,
    onCopy: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val blocks = remember(text) { MarkdownBlocks.parse(text) }
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Space.sm + 2.dp)) {
        for (block in blocks) {
            MarkdownBlockView(block, color, codeColor, linkColor, onCopy)
        }
    }
}

@Composable
private fun MarkdownBlockView(
    block: MarkdownBlock,
    color: Color,
    codeColor: Color,
    linkColor: Color,
    onCopy: (String) -> Unit,
) {
    when (block) {
        is MarkdownBlock.Paragraph -> Text(
            text = inlineMarkdown(block.text, codeColor, linkColor),
            style = MaterialTheme.typography.bodyMedium,
            color = color,
        )

        is MarkdownBlock.Heading -> {
            val style = when (block.level) {
                1 -> MaterialTheme.typography.titleLarge
                2 -> MaterialTheme.typography.titleMedium
                3, 4 -> MaterialTheme.typography.bodyLarge
                else -> MaterialTheme.typography.bodyMedium
            }
            Text(
                text = inlineMarkdown(block.text, codeColor, linkColor),
                style = style.copy(fontWeight = FontWeight.SemiBold),
                color = color,
                modifier = Modifier.padding(top = if (block.level <= 2) 4.dp else 2.dp),
            )
        }

        is MarkdownBlock.ListBlock -> Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
            for (item in block.items) {
                Row(modifier = Modifier.padding(start = (item.indent * 6).dp)) {
                    Text(
                        text = item.marker,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.End,
                        modifier = Modifier.widthIn(min = 18.dp),
                    )
                    Spacer(Modifier.width(Space.sm))
                    Text(
                        text = inlineMarkdown(item.text, codeColor, linkColor),
                        style = MaterialTheme.typography.bodyMedium,
                        color = color,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }

        is MarkdownBlock.Quote ->
            // The bar matches the text's height because the row is intrinsically
            // sized: a plain fillMaxHeight bar in a lazy list is offered the whole
            // viewport, and a quote drew a full-screen line.
            Row(modifier = Modifier.height(IntrinsicSize.Min)) {
                Box(
                    Modifier
                        .width(3.dp)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(1.5.dp))
                        .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.18f)),
                )
                Text(
                    text = inlineMarkdown(block.text, codeColor, linkColor),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 10.dp),
                )
            }

        is MarkdownBlock.Code -> CodeBlockView(block, onCopy)

        is MarkdownBlock.Table -> TableView(block, color, codeColor, linkColor)

        MarkdownBlock.Divider -> Box(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 2.dp)
                .height(0.5.dp)
                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)),
        )
    }
}

/**
 * A fenced block. The language tag is a caption, the body scrolls sideways
 * rather than wrapping, and the copy button is the one this screen keeps --
 * copying a whole answer is rarely the job; copying a command is.
 */
@Composable
private fun CodeBlockView(block: MarkdownBlock.Code, onCopy: (String) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f), Corner.sm)
            .border(
                0.5.dp,
                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
                Corner.sm,
            )
            .padding(2.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (!block.language.isNullOrEmpty()) {
                Text(
                    text = block.language.uppercase(),
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 1.5.sp,
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    modifier = Modifier.padding(start = 10.dp),
                )
            }
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                IconButton(onClick = { onCopy(block.text) }, modifier = Modifier.size(24.dp)) {
                    Icon(
                        Icons.Default.ContentCopy,
                        contentDescription = stringResource(R.string.copy_code),
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Box(Modifier.horizontalScroll(rememberScrollState())) {
            // An empty fence still needs a line's height, or the block collapses
            // to a border with nothing inside it.
            Text(
                text = block.text.ifEmpty { " " },
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            )
        }
    }
}

/**
 * The table's corner. The one clip inside the transcript -- the zebra rows reach the
 * corners under a sideways scroll -- so it is cut circular (smoothing 0): a native
 * rounded clip on every API level, and at 12 dp indistinguishable from continuous.
 */
private val TableShape = ContinuousCornerShape(Radius.sm, smoothing = 0f)

/** A GFM table: a grid that scrolls sideways when it outgrows the column. */
@Composable
private fun TableView(
    table: MarkdownBlock.Table,
    color: Color,
    codeColor: Color,
    linkColor: Color,
) {
    val lineColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)
    Box(
        modifier = Modifier
            .clip(TableShape)
            // The hairline follows the corner. A rectangle stroked under a rounded clip
            // loses its corners -- invisibly at 6 dp, as a visible gap at 12.
            .border(0.5.dp, lineColor, TableShape)
            .horizontalScroll(rememberScrollState()),
    ) {
        Column {
            TableRow(table.header, table.align, header = true, zebra = false, color, codeColor, linkColor)
            table.rows.forEachIndexed { index, row ->
                TableRow(row, table.align, header = false, zebra = index % 2 == 1, color, codeColor, linkColor)
            }
        }
    }
}

@Composable
private fun TableRow(
    cells: List<String>,
    align: List<CellAlign>,
    header: Boolean,
    zebra: Boolean,
    color: Color,
    codeColor: Color,
    linkColor: Color,
) {
    val fill = when {
        header -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f)
        zebra -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.03f)
        else -> Color.Transparent
    }
    val lineColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)
    Row {
        cells.forEachIndexed { column, cell ->
            val alignment = align.getOrElse(column) { CellAlign.START }
            Box(
                modifier = Modifier
                    .widthIn(min = 84.dp, max = 220.dp)
                    .background(fill)
                    .drawBehind {
                        // Row and column lines, inside the cell, so the grid needs
                        // no divider rows of its own.
                        drawRect(
                            color = lineColor,
                            topLeft = Offset(0f, size.height - 0.5.dp.toPx()),
                            size = Size(size.width, 0.5.dp.toPx()),
                        )
                        drawRect(
                            color = lineColor.copy(alpha = 0.08f),
                            topLeft = Offset(size.width - 0.5.dp.toPx(), 0f),
                            size = Size(0.5.dp.toPx(), size.height),
                        )
                    }
                    .padding(horizontal = 10.dp, vertical = 8.dp),
            ) {
                Text(
                    text = inlineMarkdown(cell, codeColor, linkColor),
                    style = if (header) {
                        MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold)
                    } else {
                        MaterialTheme.typography.bodyMedium
                    },
                    color = color,
                    textAlign = when (alignment) {
                        CellAlign.START -> TextAlign.Start
                        CellAlign.CENTER -> TextAlign.Center
                        CellAlign.END -> TextAlign.End
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}
