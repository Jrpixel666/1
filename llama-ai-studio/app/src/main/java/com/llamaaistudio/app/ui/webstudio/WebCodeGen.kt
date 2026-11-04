package com.llamaaistudio.app.ui.webstudio

import java.io.File

/** One file the model produced. [path] is relative to the project root, '/'-separated. */
data class GeneratedFile(val path: String, val content: String)

/**
 * Prompt format + parser for Web Studio's "describe an idea, get a site" flow. The model is
 * asked to emit one block per file, each introduced by a `=== FILE: path ===` marker, which
 * lets it create or change several files (HTML + CSS + JS) in a single generation.
 */
object WebCodeGen {
    private val MARKER = Regex("""^[ \t]*(?:#+[ \t]*|\*\*)?=+[ \t]*FILE:[ \t]*(.+?)[ \t]*=+(?:\*\*)?[ \t]*\r?$""", RegexOption.MULTILINE)
    private val FENCED = Regex("""^```[A-Za-z0-9_+.-]*[ \t]*\r?\n([\s\S]*?)\r?\n?```\s*$""")

    fun systemPrompt(template: String): String = buildString {
        append("You are an expert web developer building a small website that will be served locally. ")
        if (template == "node") {
            append(
                "This is a Node.js project: server.js uses only Node's built-in modules and serves " +
                    "the static files in public/ plus any /api routes. Keep it runnable with `node server.js` " +
                    "and read the port from process.env.PORT. "
            )
        } else {
            append("This is a static site (HTML, CSS and vanilla JavaScript, no build step, no CDNs needed). ")
        }
        append(
            "Implement the user's request. Output the COMPLETE new contents of every file you create " +
                "or change, each starting on its own line with a marker exactly like:\n" +
                "=== FILE: index.html ===\n" +
                "Use paths relative to the project root. Output nothing except file markers and file " +
                "contents: no explanations, no markdown code fences. Files you don't output stay as they are."
        )
    }

    fun userPrompt(idea: String, files: List<Pair<String, String>>): String = buildString {
        if (files.isNotEmpty()) {
            append("Current project files:\n\n")
            files.forEach { (path, content) ->
                append("=== FILE: ").append(path).append(" ===\n").append(content.trimEnd()).append("\n\n")
            }
        }
        append("Request:\n").append(idea.trim())
    }

    /**
     * Splits model output into files. If the model ignored the marker format, the whole output
     * becomes [fallbackPath] (so a single-file answer still lands somewhere sensible).
     */
    fun parse(output: String, fallbackPath: String): List<GeneratedFile> {
        val matches = MARKER.findAll(output).toList()
        if (matches.isEmpty()) {
            val body = stripFences(output)
            return if (body.isBlank()) emptyList() else listOf(GeneratedFile(fallbackPath, body))
        }
        val files = LinkedHashMap<String, String>()
        matches.forEachIndexed { i, m ->
            val start = m.range.last + 1
            val end = if (i + 1 < matches.size) matches[i + 1].range.first else output.length
            val path = normalizePath(m.groupValues[1]) ?: return@forEachIndexed
            val body = stripFences(output.substring(start, end))
            if (body.isNotBlank()) files[path] = body
        }
        return files.map { (path, content) -> GeneratedFile(path, content) }
    }

    fun stripFences(text: String): String {
        val trimmed = text.trim()
        val fenced = FENCED.find(trimmed)?.groupValues?.get(1) ?: trimmed
        return fenced.trimEnd() + "\n"
    }

    /** Relative, '/'-separated path with no `..`/absolute parts, or null if unusable. */
    fun normalizePath(raw: String): String? {
        val cleaned = raw.trim().trim('`', '"', '\'', '*').replace('\\', '/').removePrefix("./")
        if (cleaned.isEmpty() || cleaned.startsWith("/") || cleaned.contains(':')) return null
        val parts = cleaned.split('/').filter { it.isNotEmpty() && it != "." }
        if (parts.isEmpty() || parts.any { it == ".." }) return null
        return parts.joinToString("/")
    }

    /** Resolves [relative] under [root], refusing anything that would escape it. */
    fun resolveInside(root: File, relative: String): File? {
        val path = normalizePath(relative) ?: return null
        val target = File(root, path).canonicalFile
        val rootCanonical = root.canonicalFile
        return if (target.path.startsWith(rootCanonical.path + File.separator)) target else null
    }
}
