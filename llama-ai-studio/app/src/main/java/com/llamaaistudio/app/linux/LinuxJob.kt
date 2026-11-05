package com.llamaaistudio.app.linux

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * One long-running command inside the built-in Linux (a Node server, a Gradle build, an SDK
 * install), app-scoped so it keeps going when you leave the screen. Keeps its own log.
 */
class LinuxJob(private val env: LinuxEnvironment, val name: String) {

    data class Status(
        val running: Boolean = false,
        val label: String? = null,
        /** Who started it (e.g. a project id), so screens only show their own job. */
        val owner: Long? = null,
        val lastExitCode: Int? = null,
        val finishedAt: Long? = null,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val buffer = TerminalBuffer(maxLines = 1500)
    private var job: Job? = null

    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status

    private val _log = MutableStateFlow<List<String>>(emptyList())
    val log: StateFlow<List<String>> = _log

    fun append(text: String) {
        val snapshot = synchronized(buffer) { buffer.append(text); buffer.snapshot() }
        _log.value = snapshot
    }

    /**
     * Runs [command] (stopping whatever this job was running first). [onFinished] gets the
     * exit code (or -1 if stopped/failed) on the main thread.
     */
    fun start(
        label: String,
        command: String,
        owner: Long? = null,
        workdir: String = "/root",
        env: Map<String, String> = emptyMap(),
        clearLog: Boolean = true,
        onFinished: (Int) -> Unit = {},
    ) {
        val previous = job
        if (clearLog) synchronized(buffer) { buffer.clear() }
        _log.value = synchronized(buffer) { buffer.snapshot() }
        _status.value = Status(running = true, label = label, owner = owner)
        job = scope.launch {
            previous?.cancel()
            previous?.join()
            var code = -1
            try {
                append("$ $label\n")
                code = this@LinuxJob.env.run(command, workdir, env, onOutput = ::append).exitCode
                append(if (code == 0) "[done]\n" else "[exit code $code]\n")
            } catch (e: CancellationException) {
                append("[stopped]\n")
                throw e
            } catch (e: Exception) {
                append("[error] ${e.message}\n")
            } finally {
                if (job == null || job == coroutineContext[Job]) {
                    _status.value = Status(false, label, owner, code, System.currentTimeMillis())
                }
                onFinished(code)
            }
        }
    }

    fun stop() {
        job?.cancel()
    }
}
