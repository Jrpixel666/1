package com.omarchy.launcher.widgets

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OwlParserTest {

    @Test
    fun `parses size from header`() {
        val widget = OwlParser.parse(
            """
            widget { size: "3x1" }
            Text { text: "hi" }
            """.trimIndent()
        )
        assertEquals(3, widget.sizeCols)
        assertEquals(1, widget.sizeRows)
    }

    @Test
    fun `defaults size when missing or invalid`() {
        val widget = OwlParser.parse("widget {}\nText { text: \"hi\" }")
        assertEquals(6, widget.sizeCols)
        assertEquals(2, widget.sizeRows)
    }

    @Test
    fun `wraps multiple top-level nodes in an implicit column`() {
        val widget = OwlParser.parse(
            """
            widget { size: "6x2" }
            Text { text: "a" }
            Text { text: "b" }
            """.trimIndent()
        )
        val root = widget.root
        assertTrue(root is OwlNode.Column)
        assertEquals(2, (root as OwlNode.Column).children.size)
    }

    @Test
    fun `parses nested layout and props`() {
        val widget = OwlParser.parse(
            """
            widget { size: "6x2" }
            Column {
              padding: 16
              spacing: 8
              Text { text: "${'$'}{clock}" size: 32 color: accent }
              Row {
                Text { text: "left" }
                Spacer { weight: 1 }
                Text { text: "right" }
              }
            }
            """.trimIndent()
        )
        val column = widget.root as OwlNode.Column
        assertEquals("16", column.props["padding"])
        assertEquals("8", column.props["spacing"])
        assertEquals(2, column.children.size)

        val text = column.children[0] as OwlNode.Text
        assertEquals("\${clock}", text.props["text"])
        assertEquals("32", text.props["size"])
        assertEquals("accent", text.props["color"])

        val row = column.children[1] as OwlNode.Row
        assertEquals(3, row.children.size)
        val spacer = row.children[1] as OwlNode.Spacer
        assertEquals("1", spacer.props["weight"])
    }

    @Test
    fun `parses onTap call syntax on a button`() {
        val widget = OwlParser.parse(
            """
            widget { size: "6x1" }
            Button { text: "go"; onTap: open("com.android.settings") }
            """.trimIndent()
        )
        val button = widget.root as OwlNode.Button
        assertEquals("open(com.android.settings)", button.props["onTap"])
    }

    @Test
    fun `ignores comment lines`() {
        val widget = OwlParser.parse(
            """
            # a comment
            widget { size: "6x1" } # trailing comment too
            # another comment
            Text { text: "hi" }
            """.trimIndent()
        )
        assertEquals(6, widget.sizeCols)
    }

    @Test(expected = OwlParseException::class)
    fun `throws on missing widget header`() {
        OwlParser.parse("Text { text: \"hi\" }")
    }

    @Test(expected = OwlParseException::class)
    fun `throws on unterminated block`() {
        OwlParser.parse("widget { size: \"6x1\" }\nText { text: \"hi\"")
    }

    @Test(expected = OwlParseException::class)
    fun `throws on unknown node type`() {
        OwlParser.parse("widget {}\nRing { value: 1 }")
    }

    @Test
    fun `parses the three bundled example widgets`() {
        OwlParser.parse(CLOCK_WIDGET)
        OwlParser.parse(BATTERY_WIDGET)
        OwlParser.parse(QUICK_ACTIONS_WIDGET)
    }

    @Test
    fun `interpolate substitutes known values and marks unknown ones`() {
        val values = mapOf("clock" to "08:30", "battery" to "42")
        assertEquals("08:30 - 42%", interpolate("\${clock} - \${battery}%", values))
        assertEquals("…", interpolate("\${nope}", values))
    }

    @Test
    fun `WidgetAction parses open and url calls, and rejects unknown ones`() {
        assertEquals(WidgetAction.OpenApp("com.android.settings"), WidgetAction.parse("open(com.android.settings)"))
        assertEquals(WidgetAction.OpenUrl("https://example.com"), WidgetAction.parse("url(https://example.com)"))
        assertNull(WidgetAction.parse("notify(hello)"))
        assertNull(WidgetAction.parse(null))
    }

    companion object {
        // Mirrors app/src/main/assets/widgets/01-clock.owl
        private val CLOCK_WIDGET = """
            widget {
              size: "6x2"
            }

            Column {
              padding: 16
              spacing: 4

              Text {
                text: "${'$'}{clock}"
                size: 36
                color: accent
              }

              Text {
                text: "${'$'}{date}"
                size: 14
                color: subtle
              }
            }
        """.trimIndent()

        // Mirrors app/src/main/assets/widgets/02-battery.owl
        private val BATTERY_WIDGET = """
            widget {
              size: "6x1"
            }

            Row {
              padding: 16
              spacing: 8

              Text {
                text: "battery"
                color: subtle
              }

              Spacer {
                weight: 1
              }

              Text {
                text: "${'$'}{battery}%"
                color: accent
              }
            }
        """.trimIndent()

        // Mirrors app/src/main/assets/widgets/03-quick-actions.owl
        private val QUICK_ACTIONS_WIDGET = """
            widget {
              size: "6x1"
            }

            Row {
              padding: 12
              spacing: 8

              Button {
                text: "Settings"
                onTap: open("com.android.settings")
              }

              Button {
                text: "Termux"
                onTap: open("com.termux")
              }

              Spacer {
                weight: 1
              }

              Button {
                text: "Docs"
                onTap: url("https://github.com/SimonSchubert/omalauncher")
              }
            }
        """.trimIndent()
    }
}
