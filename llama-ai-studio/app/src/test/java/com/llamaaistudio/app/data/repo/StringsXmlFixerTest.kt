package com.llamaaistudio.app.data.repo

import org.junit.Assert.assertEquals
import org.junit.Test

class StringsXmlFixerTest {
    @Test
    fun fixesBadEscapesAndApostrophes() {
        val xml = """<resources>
    <string name="terminal_welcome">Welcome! Type \help or C:\users\me — it's \u26A1 fast\n</string>
    <string name="ok">Don\'t worry</string>
    <string name="quoted">"It's fine as is"</string>
    <string-array name="a"><item>Bob's</item></string-array>
</resources>"""
        val out = StringsXmlFixer.fix(xml)
        assertEquals(
            """<resources>
    <string name="terminal_welcome">Welcome! Type \\help or C:\\users\\me — it\'s \u26A1 fast\n</string>
    <string name="ok">Don\'t worry</string>
    <string name="quoted">"It's fine as is"</string>
    <string-array name="a"><item>Bob\'s</item></string-array>
</resources>""",
            out,
        )
        assertEquals(out, StringsXmlFixer.fix(out)) // idempotent
    }
}
