package com.llamaaistudio.app.devstudio

/**
 * Shell scripts Web Studio hands to Termux. The project is exported to shared Downloads by the
 * app, then copied into Termux's own home (`~/llama-web/<name>`) before serving — npm can't
 * create the symlinks it needs on shared storage, and serving from Termux's home is faster.
 *
 * Everything Termux needs is installed on first use (`pkg install python` / `nodejs`), so
 * a fresh Termux only needs the one-time allow-external-apps + storage setup.
 */
object TermuxWebServer {
    const val EXPORT_BASE = "LlamaAiStudioWeb"

    private fun header(name: String, port: Int) = """
        NAME='$name'
        PORT=$port
        SRC="${'$'}HOME/storage/downloads/$EXPORT_BASE/${'$'}NAME"
        DEST="${'$'}HOME/llama-web/${'$'}NAME"
        PIDFILE="${'$'}HOME/llama-web/.server-${'$'}PORT.pid"
    """.trimIndent()

    private val ensureFn = """
        ensure() {
          command -v "${'$'}1" >/dev/null 2>&1 && return 0
          echo "==> Installing ${'$'}2 (first run only)…"
          pkg install -y "${'$'}2" || { pkg update -y && pkg install -y "${'$'}2"; }
        }
    """.trimIndent()

    private val syncBlock = """
        if [ ! -d "${'$'}SRC" ]; then
          echo "Termux can't see the project yet - granting storage access."
          echo "Allow the prompt, then tap Start server again in Llama AI Studio."
          termux-setup-storage
          sleep 3
          exit 1
        fi
        mkdir -p "${'$'}DEST"
        cp -rf "${'$'}SRC"/. "${'$'}DEST"/
    """.trimIndent()

    private val stopBlock = """
        if [ -f "${'$'}PIDFILE" ]; then
          kill "${'$'}(cat "${'$'}PIDFILE")" 2>/dev/null
          rm -f "${'$'}PIDFILE"
          sleep 1
        fi
    """.trimIndent()

    /** Installs what's missing, syncs the latest files and (re)starts the server in the foreground. */
    fun startScript(name: String, template: String, port: Int): String {
        val run = if (template == "node") {
            """
            ensure node nodejs || exit 1
            if [ -f package.json ] && grep -q '"dependencies"' package.json; then
              echo "==> npm install"
              npm install --no-audit --no-fund || exit 1
            fi
            ENTRY=server.js
            [ -f "${'$'}ENTRY" ] || ENTRY=index.js
            echo "==> Serving on http://127.0.0.1:${'$'}PORT (Ctrl+C to stop)"
            PORT=${'$'}PORT node "${'$'}ENTRY" &
            """.trimIndent()
        } else {
            """
            ensure python python || exit 1
            echo "==> Serving on http://127.0.0.1:${'$'}PORT (Ctrl+C to stop)"
            python -m http.server "${'$'}PORT" --bind 127.0.0.1 &
            """.trimIndent()
        }
        return listOf(
            header(name, port),
            ensureFn,
            syncBlock,
            stopBlock,
            "cd \"\$DEST\" || exit 1",
            run,
            "SERVER=\$!",
            "echo \"\$SERVER\" > \"\$PIDFILE\"",
            // Background jobs of a non-interactive shell ignore Ctrl+C; forward it (and the
            // session closing) to the server so it never keeps running orphaned.
            "trap 'kill \"\$SERVER\" 2>/dev/null; rm -f \"\$PIDFILE\"; exit 0' INT TERM HUP",
            "wait \"\$SERVER\"",
            "rm -f \"\$PIDFILE\"",
        ).joinToString("\n")
    }

    /** Copies the latest exported files over the served copy (no restart). */
    fun syncScript(name: String, port: Int): String =
        listOf(header(name, port), "[ -d \"\$SRC\" ] || exit 0", "mkdir -p \"\$DEST\"", "cp -rf \"\$SRC\"/. \"\$DEST\"/")
            .joinToString("\n")

    fun stopScript(name: String, port: Int): String = listOf(header(name, port), stopBlock).joinToString("\n")
}
