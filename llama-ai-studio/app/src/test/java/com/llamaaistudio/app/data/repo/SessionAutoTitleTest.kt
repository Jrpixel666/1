package com.llamaaistudio.app.data.repo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionAutoTitleTest {

    @Test
    fun shortMessageIsUsedVerbatim() {
        assertEquals("How do I bake bread?", ChatRepository.autoTitleFor("How do I bake bread?"))
    }

    @Test
    fun usesFirstNonBlankLineAndCollapsesWhitespace() {
        assertEquals(
            "Explain the code below",
            ChatRepository.autoTitleFor("\n\n  Explain   the code\tbelow  \nfun main() {}"),
        )
    }

    @Test
    fun longMessageIsCutAtWordBoundaryWithEllipsis() {
        val title = ChatRepository.autoTitleFor(
            "Please write a detailed comparison of every major sorting algorithm including complexity"
        )
        assertTrue(title, title.endsWith("…"))
        assertTrue(title, title.length <= 49)
        assertTrue(title, !title.removeSuffix("…").endsWith(" "))
        assertEquals("Please write a detailed comparison of every…", title)
    }

    @Test
    fun blankMessageFallsBackToDefaultTitle() {
        assertEquals(DEFAULT_SESSION_TITLE, ChatRepository.autoTitleFor("   \n  "))
    }

    @Test
    fun longWordWithoutSpacesIsHardCut() {
        val title = ChatRepository.autoTitleFor("x".repeat(100))
        assertEquals("x".repeat(48) + "…", title)
    }
}
