package com.llamaaistudio.app.data.repo

/**
 * Repairs the escaping mistakes AI models make in res/values strings, which aapt2 rejects:
 * stray backslashes ("C:\Users", "\u" without 4 hex digits → "Invalid unicode escape") and bare
 * apostrophes ("Don't" → "Apostrophe not preceded by \"). Only the text of <string> and <item>
 * elements is touched, and text that is already valid is left exactly as it was.
 */
object StringsXmlFixer {
    private val ELEMENT = Regex("""(<(string|item)\b[^>]*>)(.*?)(</\2>)""", RegexOption.DOT_MATCHES_ALL)
    private val VALID_ESCAPE = Regex("""\\(?:[nt'"\\@?]|u[0-9a-fA-F]{4})""")

    fun fix(xml: String): String = ELEMENT.replace(xml) { m ->
        val (open, _, body, close) = m.destructured
        // Leave CDATA, nested markup and whole-string "double-quoted" text alone.
        if (body.contains("<![CDATA[") || body.contains('<') || (body.trim().startsWith("\"") && body.trim().endsWith("\""))) {
            return@replace m.value
        }
        open + fixText(body) + close
    }

    internal fun fixText(text: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '\\') {
                val valid = VALID_ESCAPE.matchAt(text, i)
                if (valid != null) { sb.append(valid.value); i += valid.value.length; continue }
                sb.append("\\\\"); i++; continue
            }
            if (c == '\'') { sb.append("\\'"); i++; continue }
            sb.append(c); i++
        }
        return sb.toString()
    }
}
