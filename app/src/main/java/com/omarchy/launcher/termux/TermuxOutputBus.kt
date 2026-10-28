package com.omarchy.launcher.termux

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** In-memory log the embedded Terminal screen renders; fed by TermuxResultReceiver. */
object TermuxOutputBus {
    private val _lines = MutableStateFlow<List<String>>(listOf("omarchy-launcher terminal ready."))
    val lines = _lines.asStateFlow()

    fun append(line: String) {
        _lines.value = (_lines.value + line).takeLast(2000)
    }

    fun appendBlock(block: String) {
        if (block.isBlank()) return
        _lines.value = (_lines.value + block.lines()).takeLast(2000)
    }

    fun clear() {
        _lines.value = listOf("omarchy-launcher terminal ready.")
    }
}
