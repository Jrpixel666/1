package com.llamaaistudio.app.ui.webstudio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WebCodeGenTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun parsesMultipleFiles() {
        val out = """
            === FILE: index.html ===
            <h1>Hi</h1>
            === FILE: css/style.css ===
            h1 { color: red; }
        """.trimIndent()
        val files = WebCodeGen.parse(out, "index.html")
        assertEquals(listOf("index.html", "css/style.css"), files.map { it.path })
        assertEquals("<h1>Hi</h1>\n", files[0].content)
        assertEquals("h1 { color: red; }\n", files[1].content)
    }

    @Test
    fun stripsCodeFencesInsideBlocks() {
        val out = "=== FILE: script.js ===\n```javascript\nconsole.log(1)\n```\n"
        assertEquals("console.log(1)\n", WebCodeGen.parse(out, "index.html").single().content)
    }

    @Test
    fun toleratesMarkdownDecoratedMarkers() {
        val out = "**=== FILE: index.html ===**\n<p>x</p>\n## === FILE: a.js ===\nlet a\n"
        assertEquals(listOf("index.html", "a.js"), WebCodeGen.parse(out, "x").map { it.path })
    }

    @Test
    fun fallsBackToTargetFileWithoutMarkers() {
        val files = WebCodeGen.parse("```html\n<b>only</b>\n```", "public/index.html")
        assertEquals("public/index.html", files.single().path)
        assertEquals("<b>only</b>\n", files.single().content)
    }

    @Test
    fun rejectsPathsEscapingTheProject() {
        assertNull(WebCodeGen.normalizePath("../evil.sh"))
        assertNull(WebCodeGen.normalizePath("/etc/passwd"))
        assertNull(WebCodeGen.normalizePath("a/../../b"))
        assertEquals("a/b.js", WebCodeGen.normalizePath("./a//b.js"))
        val root = tmp.newFolder("site")
        assertNull(WebCodeGen.resolveInside(root, "../x"))
        assertEquals(java.io.File(root, "js/app.js").canonicalFile, WebCodeGen.resolveInside(root, "js/app.js"))
        val files = WebCodeGen.parse("=== FILE: ../../x ===\nbad\n=== FILE: ok.html ===\ngood\n", "index.html")
        assertEquals(listOf("ok.html"), files.map { it.path })
    }
}
