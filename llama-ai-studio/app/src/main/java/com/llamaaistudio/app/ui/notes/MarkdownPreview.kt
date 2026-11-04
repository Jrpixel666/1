package com.llamaaistudio.app.ui.notes

import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.em

/**
 * Minimal markdown -> AnnotatedString renderer covering the subset notes actually use:
 * headers, bold/italic, inline code, and bullet lists. Not a full CommonMark renderer —
 * good enough for a quick-glance preview without pulling in a WebView.
 */
fun renderMarkdownPreview(source: String) = buildAnnotatedString {
    val lines = source.lines()
    for ((index, rawLine) in lines.withIndex()) {
        val line = rawLine
        when {
            line.startsWith("### ") -> withHeader(1.1f) { appendInline(line.removePrefix("### ")) }
            line.startsWith("## ") -> withHeader(1.3f) { appendInline(line.removePrefix("## ")) }
            line.startsWith("# ") -> withHeader(1.6f) { appendInline(line.removePrefix("# ")) }
            line.startsWith("- ") || line.startsWith("* ") -> {
                append("•  ")
                appendInline(line.substring(2))
            }
            else -> appendInline(line)
        }
        if (index != lines.lastIndex) append("\n")
    }
}

private fun androidx.compose.ui.text.AnnotatedString.Builder.withHeader(
    scale: Float,
    block: androidx.compose.ui.text.AnnotatedString.Builder.() -> Unit,
) {
    withStyle(SpanStyle(fontWeight = FontWeight.Bold, fontSize = scale.em)) { block() }
}

private val INLINE_TOKEN = Regex("(\\*\\*[^*]+\\*\\*|\\*[^*]+\\*|`[^`]+`)")

private fun androidx.compose.ui.text.AnnotatedString.Builder.appendInline(text: String) {
    var lastEnd = 0
    for (match in INLINE_TOKEN.findAll(text)) {
        if (match.range.first > lastEnd) append(text.substring(lastEnd, match.range.first))
        val token = match.value
        when {
            token.startsWith("**") -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                append(token.removePrefix("**").removeSuffix("**"))
            }
            token.startsWith("`") -> withStyle(SpanStyle(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)) {
                append(token.removePrefix("`").removeSuffix("`"))
            }
            else -> withStyle(SpanStyle(fontStyle = androidx.compose.ui.text.font.FontStyle.Italic)) {
                append(token.removePrefix("*").removeSuffix("*"))
            }
        }
        lastEnd = match.range.last + 1
    }
    if (lastEnd < text.length) append(text.substring(lastEnd))
}
