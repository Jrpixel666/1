package com.llamaaistudio.app.util

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Tiny cross-workspace handoff point: Notes writes here when the user taps "Send to chat",
 * Chat drains it the next time its screen becomes visible. Deliberately simple (no event bus)
 * since there is only ever one pending handoff at a time.
 */
object WorkspaceBridge {
    val pendingChatContext = MutableStateFlow<String?>(null)

    fun sendToChat(text: String) {
        pendingChatContext.value = text
    }

    fun consumePendingChatContext(): String? {
        val value = pendingChatContext.value
        pendingChatContext.value = null
        return value
    }
}
