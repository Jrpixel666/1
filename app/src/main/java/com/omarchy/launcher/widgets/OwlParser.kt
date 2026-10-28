package com.omarchy.launcher.widgets

/**
 * Hand-written tokenizer + recursive-descent parser for the scoped widget format described in
 * OwlNode.kt. Grammar:
 *
 *   file       := widgetHeader nodeBlock+
 *   widgetHeader := 'widget' '{' prop* '}'
 *   nodeBlock  := IDENT '{' (prop | nodeBlock)* '}'
 *   prop       := IDENT ':' value
 *   value      := STRING | NUMBER | IDENT | call
 *   call       := IDENT '(' (value (',' value)*)? ')'
 *
 * Comments start with '#' and run to end of line. Commas/semicolons between props are optional
 * whitespace-equivalents. No semantic validation happens here (unknown node/prop names are kept
 * as-is) - OwlRenderer decides what it understands, so a typo degrades gracefully instead of
 * failing the whole widget.
 */
object OwlParser {

    private sealed class Token {
        data class Ident(val text: String) : Token()
        data class Number(val text: String) : Token()
        data class Str(val text: String) : Token()
        data object LBrace : Token()
        data object RBrace : Token()
        data object LParen : Token()
        data object RParen : Token()
        data object Colon : Token()
        data object Comma : Token()
    }

    fun parse(source: String): OwlWidget {
        val tokens = tokenize(source)
        val cursor = Cursor(tokens)

        val headerName = cursor.expectIdent()
        if (headerName != "widget") {
            throw OwlParseException("expected 'widget' header, found '$headerName'")
        }
        val headerProps = parsePropsBlock(cursor)

        val topLevelNodes = mutableListOf<OwlNode>()
        while (!cursor.atEnd()) {
            topLevelNodes.add(parseNode(cursor))
        }
        if (topLevelNodes.isEmpty()) {
            throw OwlParseException("widget has no content")
        }

        val root = if (topLevelNodes.size == 1) topLevelNodes[0] else OwlNode.Column(emptyMap(), topLevelNodes)
        val (cols, rows) = parseSize(headerProps["size"])
        return OwlWidget(sizeCols = cols, sizeRows = rows, root = root)
    }

    private fun parseSize(raw: String?): Pair<Int, Int> {
        val parts = raw?.lowercase()?.split("x")
        val cols = parts?.getOrNull(0)?.trim()?.toIntOrNull() ?: 6
        val rows = parts?.getOrNull(1)?.trim()?.toIntOrNull() ?: 2
        return cols.coerceIn(1, 6) to rows.coerceIn(1, 12)
    }

    private fun parseNode(cursor: Cursor): OwlNode {
        val name = cursor.expectIdent()
        cursor.expect<Token.LBrace>("'{'")
        val props = mutableMapOf<String, String>()
        val children = mutableListOf<OwlNode>()

        while (cursor.peek() !is Token.RBrace) {
            if (cursor.atEnd()) throw OwlParseException("unterminated '$name' block")
            if (cursor.peek() is Token.Ident && cursor.peekNext() is Token.LBrace) {
                children.add(parseNode(cursor))
            } else {
                val (key, value) = parseProp(cursor)
                props[key] = value
            }
        }
        cursor.expect<Token.RBrace>("'}'")

        return when (name) {
            "Column" -> OwlNode.Column(props, children)
            "Row" -> OwlNode.Row(props, children)
            "Box" -> OwlNode.Box(props, children)
            "Text" -> OwlNode.Text(props)
            "Spacer" -> OwlNode.Spacer(props)
            "Button" -> OwlNode.Button(props)
            else -> throw OwlParseException("unknown node type '$name'")
        }
    }

    private fun parsePropsBlock(cursor: Cursor): Map<String, String> {
        cursor.expect<Token.LBrace>("'{'")
        val props = mutableMapOf<String, String>()
        while (cursor.peek() !is Token.RBrace) {
            if (cursor.atEnd()) throw OwlParseException("unterminated 'widget' header")
            val (key, value) = parseProp(cursor)
            props[key] = value
        }
        cursor.expect<Token.RBrace>("'}'")
        return props
    }

    private fun parseProp(cursor: Cursor): Pair<String, String> {
        val key = cursor.expectIdent()
        cursor.expect<Token.Colon>("':'")
        val value = parseValue(cursor)
        return key to value
    }

    private fun parseValue(cursor: Cursor): String {
        return when (val token = cursor.next()) {
            is Token.Str -> token.text
            is Token.Number -> token.text
            is Token.Ident -> {
                if (cursor.peek() is Token.LParen) {
                    cursor.next()
                    val args = mutableListOf<String>()
                    while (cursor.peek() !is Token.RParen) {
                        args.add(parseValue(cursor))
                        if (cursor.peek() is Token.Comma) cursor.next()
                    }
                    cursor.expect<Token.RParen>("')'")
                    "${token.text}(${args.joinToString(",")})"
                } else {
                    token.text
                }
            }
            else -> throw OwlParseException("expected a value, found $token")
        }
    }

    private fun tokenize(source: String): List<Token> {
        val tokens = mutableListOf<Token>()
        var i = 0
        while (i < source.length) {
            val c = source[i]
            when {
                c.isWhitespace() || c == ';' -> i++
                c == '#' -> {
                    while (i < source.length && source[i] != '\n') i++
                }
                c == '{' -> { tokens.add(Token.LBrace); i++ }
                c == '}' -> { tokens.add(Token.RBrace); i++ }
                c == '(' -> { tokens.add(Token.LParen); i++ }
                c == ')' -> { tokens.add(Token.RParen); i++ }
                c == ':' -> { tokens.add(Token.Colon); i++ }
                c == ',' -> { tokens.add(Token.Comma); i++ }
                c == '"' -> {
                    val sb = StringBuilder()
                    i++
                    while (i < source.length && source[i] != '"') {
                        if (source[i] == '\\' && i + 1 < source.length) {
                            sb.append(source[i + 1])
                            i += 2
                        } else {
                            sb.append(source[i])
                            i++
                        }
                    }
                    if (i >= source.length) throw OwlParseException("unterminated string")
                    i++ // closing quote
                    tokens.add(Token.Str(sb.toString()))
                }
                c.isDigit() || (c == '-' && i + 1 < source.length && source[i + 1].isDigit()) -> {
                    val start = i
                    if (c == '-') i++
                    while (i < source.length && (source[i].isDigit() || source[i] == '.')) i++
                    tokens.add(Token.Number(source.substring(start, i)))
                }
                c.isLetter() || c == '_' -> {
                    val start = i
                    while (i < source.length && (source[i].isLetterOrDigit() || source[i] == '_')) i++
                    tokens.add(Token.Ident(source.substring(start, i)))
                }
                else -> throw OwlParseException("unexpected character '$c' at position $i")
            }
        }
        return tokens
    }

    private class Cursor(private val tokens: List<Token>) {
        private var pos = 0

        fun atEnd(): Boolean = pos >= tokens.size
        fun peek(): Token = tokens.getOrNull(pos) ?: throw OwlParseException("unexpected end of input")
        fun peekNext(): Token? = tokens.getOrNull(pos + 1)
        fun next(): Token = peek().also { pos++ }

        fun expectIdent(): String {
            val token = next()
            if (token !is Token.Ident) throw OwlParseException("expected an identifier, found $token")
            return token.text
        }

        inline fun <reified T : Token> expect(description: String) {
            val token = next()
            if (token !is T) throw OwlParseException("expected $description, found $token")
        }
    }
}
