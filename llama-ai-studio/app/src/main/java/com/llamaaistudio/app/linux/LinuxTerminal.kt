package com.llamaaistudio.app.linux

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Plain-text terminal output: strips ANSI escape sequences and treats a bare `\r` as "rewrite
 * the current line" (how progress bars redraw), keeping at most [maxLines] lines.
 */
class TerminalBuffer(private val maxLines: Int = 4000) {
    private val lines = ArrayDeque<String>().apply { add("") }
    private var pendingCr = false

    fun append(text: String) {
        val clean = ANSI.replace(text, "")
        for (ch in clean) {
            when (ch) {
                '\n' -> { pendingCr = false; lines.addLast("") }
                '\r' -> pendingCr = true
                else -> {
                    if (pendingCr) { lines[lines.lastIndex] = ""; pendingCr = false }
                    if (ch == '\t' || ch >= ' ') lines[lines.lastIndex] = lines.last() + ch
                }
            }
        }
        // The trailing empty line is the cursor's line, not output — don't count it.
        while (lines.size - (if (lines.last().isEmpty()) 1 else 0) > maxLines) lines.removeFirst()
    }

    fun snapshot(): List<String> = lines.toList().let { if (it.lastOrNull() == "") it.dropLast(1) else it }

    fun clear() {
        lines.clear(); lines.add(""); pendingCr = false
    }

    private companion object {
        // CSI (colors, cursor moves), OSC (titles) and two-char escapes.
        val ANSI = Regex("\u001B\\[[0-?]*[ -/]*[@-~]|\u001B][^\u0007\u001B]*(\u0007|\u001B\\\\)|\u001B[@-Z\\\\-_]")
    }
}

/**
 * The app-wide Linux terminal: one command at a time, output kept across screens, so a long
 * `apk add openjdk17` keeps running when you leave the Linux screen.
 */
class LinuxTerminal(private val env: LinuxEnvironment) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val buffer = TerminalBuffer()

    private val _lines = MutableStateFlow<List<String>>(emptyList())
    val lines: StateFlow<List<String>> = _lines

    private val _running = MutableStateFlow<String?>(null)
    /** The command currently running, or null when idle. */
    val running: StateFlow<String?> = _running

    private var job: Job? = null

    fun print(text: String) {
        synchronized(buffer) { buffer.append(text) }
        _lines.value = synchronized(buffer) { buffer.snapshot() }
    }

    fun clear() {
        synchronized(buffer) { buffer.clear() }
        _lines.value = emptyList()
    }

    fun run(command: String, label: String = command) {
        if (_running.value != null || command.isBlank()) return
        _running.value = label
        print("\n$ $label\n")
        job = scope.launch {
            try {
                val result = env.run(command, onOutput = ::print)
                print(if (result.exitCode == 0) "[done]\n" else "[exit code ${result.exitCode}]\n")
            } catch (e: kotlinx.coroutines.CancellationException) {
                print("[stopped]\n")
                throw e
            } catch (e: Exception) {
                print("[error] ${e.message}\n")
            } finally {
                _running.value = null
            }
        }
    }

    fun install() {
        if (_running.value != null) return
        _running.value = "Installing Alpine Linux"
        job = scope.launch {
            try {
                print("\nInstalling Alpine Linux ${LinuxEnvironment.ALPINE_VERSION} (about 4 MB download)…\n")
                print(env.install() + "\n")
                print("Alpine Linux is ready. Running the self-test…\n")
                env.selfTest(::print)
            } catch (e: kotlinx.coroutines.CancellationException) {
                print("[install cancelled]\n")
                throw e
            } catch (e: Exception) {
                print("[install failed] ${e.message}\n")
            } finally {
                _running.value = null
            }
        }
    }

    fun selfTest() {
        if (_running.value != null) return
        _running.value = "Self-test"
        job = scope.launch {
            try {
                env.selfTest(::print)
            } finally {
                _running.value = null
            }
        }
    }

    fun stop() {
        job?.cancel()
    }
}
