package com.llamaaistudio.app.ui.devstudio

import com.llamaaistudio.app.ui.webstudio.WebCodeGen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/** One file in the AI's plan. */
data class PlannedFile(val path: String, val purpose: String)

/** Talks to whichever model is selected (OpenCode or on-device). */
interface CodeAi {
    /** Name shown in the UI, e.g. "OpenCode · Big Pickle". */
    val label: String
    /** True for big-context cloud models: bigger files and more context per request. */
    val largeContext: Boolean
    suspend fun complete(system: String, user: String, maxTokens: Int): Result<String>
}

/** Runs Gradle for the project; [log] is the build output. */
interface ProjectBuildRunner {
    data class Outcome(val success: Boolean, val log: List<String>, val apk: File?)
    suspend fun build(onLog: (String) -> Unit): Outcome
}

/**
 * Builds bigger Android apps from one large prompt:
 * 1. **Plan** — the model lists every file (Kotlin sources, layouts, values, manifest) with its job.
 * 2. **Write** — each file gets its own request (full length), with the plan and the files
 *    written so far as context, so projects can span many files and screens.
 * 3. **Build & fix** — Gradle builds it; compiler/resource errors are grouped per file and sent
 *    back to the model to fix, then it rebuilds — up to [maxFixRounds] times.
 */
class ProjectBuilder(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
) {
    enum class Phase { IDLE, PLANNING, PLANNED, WRITING, BUILDING, FIXING, DONE, FAILED, STOPPED }

    data class State(
        val phase: Phase = Phase.IDLE,
        val projectId: Long? = null,
        val idea: String = "",
        val plan: List<PlannedFile> = emptyList(),
        /** Index into [plan] of the file being written. */
        val writingIndex: Int = -1,
        val written: Set<String> = emptySet(),
        val round: Int = 0,
        val maxRounds: Int = 0,
        val activity: List<String> = emptyList(),
        val lastErrors: List<String> = emptyList(),
        val apk: File? = null,
        val message: String? = null,
        val startedAt: Long? = null,
    ) {
        val running: Boolean get() = phase in setOf(Phase.PLANNING, Phase.WRITING, Phase.BUILDING, Phase.FIXING)
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state
    private var job: Job? = null

    var maxFixRounds = 5

    private fun log(line: String) = _state.update { it.copy(activity = (it.activity + line).takeLast(200)) }

    fun stop() {
        job?.cancel()
        _state.update { if (it.running) it.copy(phase = Phase.STOPPED, message = "Stopped") else it }
    }

    fun reset() {
        job?.cancel()
        _state.value = State()
    }

    /** Step 1: ask the model for a file plan. */
    fun plan(projectId: Long, root: File, pkg: String, idea: String, ai: CodeAi) {
        if (_state.value.running || idea.isBlank()) return
        job = scope.launch {
            _state.value = State(Phase.PLANNING, projectId, idea, startedAt = System.currentTimeMillis())
            log("Planning with ${ai.label}…")
            val existing = sourceFiles(root).map { it.relativeTo(root).path.replace('\\', '/') }
            val reply = ai.complete(planSystem(pkg), planUser(idea, existing), if (ai.largeContext) 3000 else 1200)
            val plan = reply.getOrNull()?.let { parsePlan(it, pkg) }.orEmpty()
            if (plan.isEmpty()) {
                _state.update {
                    it.copy(
                        phase = Phase.FAILED,
                        message = reply.exceptionOrNull()?.message?.let { e -> "Planning failed: $e" }
                            ?: "The model didn't return a usable file plan — try again, or use a bigger model (OpenCode).",
                    )
                }
                return@launch
            }
            log("Plan: ${plan.size} files")
            _state.update { it.copy(phase = Phase.PLANNED, plan = plan) }
        }
    }

    fun removeFromPlan(path: String) = _state.update { s -> s.copy(plan = s.plan.filterNot { it.path == path }) }

    /** Steps 2 + 3: write every planned file, then build and auto-fix. */
    fun buildAll(root: File, pkg: String, ai: CodeAi, runner: ProjectBuildRunner) {
        val s0 = _state.value
        if (s0.running || s0.plan.isEmpty()) return
        job = scope.launch {
            _state.update { it.copy(phase = Phase.WRITING, written = emptySet(), round = 0, maxRounds = maxFixRounds, lastErrors = emptyList(), apk = null, message = null, startedAt = System.currentTimeMillis()) }
            val plan = _state.value.plan
            for ((i, f) in plan.withIndex()) {
                _state.update { it.copy(writingIndex = i) }
                log("Writing ${f.path} (${i + 1}/${plan.size})…")
                val target = WebCodeGen.resolveInside(root, f.path)
                if (target == null) { log("  skipped: path outside the project"); continue }
                val context = contextFiles(root, exclude = f.path, budget = if (ai.largeContext) 60_000 else 5_000)
                val reply = ai.complete(fileSystem(pkg), fileUser(_state.value.idea, plan, f, context), if (ai.largeContext) 8000 else 2000)
                val content = reply.getOrNull()?.let { cleanFile(it, f.path) }
                if (content.isNullOrBlank()) {
                    log("  failed: ${reply.exceptionOrNull()?.message ?: "empty reply"}")
                    continue
                }
                withContext(Dispatchers.IO) { target.parentFile?.mkdirs(); target.writeText(content) }
                _state.update { it.copy(written = it.written + f.path) }
            }
            buildAndFix(root, pkg, ai, runner)
        }
    }

    /** Build; on failure send grouped errors back for fixing; repeat. */
    private suspend fun buildAndFix(root: File, pkg: String, ai: CodeAi, runner: ProjectBuildRunner) {
        for (round in 0..maxFixRounds) {
            _state.update { it.copy(phase = Phase.BUILDING, round = round) }
            log(if (round == 0) "Building with Gradle…" else "Rebuilding (fix round $round/$maxFixRounds)…")
            val outcome = runner.build { log(it) }
            if (outcome.success) {
                log("Build succeeded")
                _state.update { it.copy(phase = Phase.DONE, apk = outcome.apk, lastErrors = emptyList(), message = "Built ${outcome.apk?.name ?: "the app"} — tap Install APK.") }
                return
            }
            val errors = parseErrors(outcome.log)
            _state.update { it.copy(lastErrors = errors.flatMap { (f, e) -> e.map { "$f: $it" } }.ifEmpty { whatWentWrong(outcome.log).ifEmpty { tail(outcome.log) } }) }
            if (round == maxFixRounds) break
            _state.update { it.copy(phase = Phase.FIXING) }
            if (errors.isEmpty()) {
                // Gradle-level failure (dependency, manifest merge, plugin…): give the model the
                // whole summary plus every file and let it rewrite whichever files it needs.
                val summary = whatWentWrong(outcome.log)
                if (summary.isEmpty()) { log("Build failed with no readable error — see the build log."); break }
                log("Fixing a project-level error…")
                val reply = ai.complete(
                    fileSystem(pkg).replace("Output ONLY the complete contents of the requested file — no explanations, no markdown fences.",
                        "Output each file you change as \"=== FILE: relative/path ===\" followed by its complete contents. No explanations."),
                    "The Gradle build failed:\n${summary.joinToString("\n")}\n\nProject files:\n" +
                        contextFiles(root, exclude = "", budget = if (ai.largeContext) 60_000 else 6_000) +
                        "\n\nRewrite the files needed to fix it (app/build.gradle.kts may be changed to fix dependencies).",
                    if (ai.largeContext) 8000 else 2000,
                )
                val files = reply.getOrNull()?.let { WebCodeGen.parse(it, "app/build.gradle.kts") }.orEmpty()
                    .filter { ALLOWED_PREFIX.containsMatchIn(it.path) }
                if (files.isEmpty()) { log("  fix failed: ${reply.exceptionOrNull()?.message ?: "no files in reply"}"); continue }
                for (g in files) {
                    val f = WebCodeGen.resolveInside(root, g.path) ?: continue
                    log("  rewrote ${g.path}")
                    withContext(Dispatchers.IO) { f.parentFile?.mkdirs(); f.writeText(cleanFile(g.content, g.path)) }
                }
                continue
            }
            for ((path, errs) in errors) {
                val file = WebCodeGen.resolveInside(root, path) ?: continue
                if (!file.isFile) continue
                log("Fixing $path (${errs.size} error${if (errs.size == 1) "" else "s"})…")
                val context = contextFiles(root, exclude = path, budget = if (ai.largeContext) 40_000 else 3_000)
                val reply = ai.complete(
                    fileSystem(pkg),
                    fixUser(path, file.readText(), errs, context),
                    if (ai.largeContext) 8000 else 2000,
                )
                reply.getOrNull()?.let { cleanFile(it, path) }?.takeIf { it.isNotBlank() }?.let { fixed ->
                    withContext(Dispatchers.IO) { file.writeText(fixed) }
                } ?: log("  fix failed: ${reply.exceptionOrNull()?.message ?: "empty reply"}")
            }
        }
        _state.update {
            it.copy(phase = Phase.FAILED, message = "Still failing after ${it.round} fix round${if (it.round == 1) "" else "s"}. Errors are below — copy them, tweak the idea, or try a bigger model.")
        }
    }

    // --- prompts ------------------------------------------------------------------------------

    private fun facts(pkg: String) =
        "Android app, Kotlin, package com.example.$pkg, minSdk 26, compileSdk 35, AGP 8.7, Kotlin 2.0.21, Gradle 8.x. " +
            "Already available: androidx.core (core-ktx), androidx.appcompat, com.google.android.material, " +
            "androidx.constraintlayout, theme Theme.AppCompat.DayNight.NoActionBar. " +
            "You MAY use any other library (Jetpack Compose, Room, Navigation, Retrofit, Coroutines…) — but then " +
            "app/build.gradle.kts must be in the plan and must add them. For Compose: add " +
            "id(\"org.jetbrains.kotlin.plugin.compose\") to its plugins block (the root build already declares it), " +
            "buildFeatures { compose = true }, and the Compose BOM (androidx.compose:compose-bom:2024.10.01) with " +
            "ui, material3, ui-tooling-preview and androidx.activity:activity-compose:1.9.3. Keep its namespace, " +
            "applicationId, compileSdk/minSdk, compileOptions (Java 17) and kotlinOptions jvmTarget 17. Never use kapt; " +
            "use KSP only if you also add its plugin with a version matching Kotlin 2.0.21 (2.0.21-1.0.28). " +
            "Sources go in app/src/main/java/com/example/$pkg/, resources in app/src/main/res/ " +
            "(layout/, values/, drawable/), manifest at app/src/main/AndroidManifest.xml. " +
            "Every Activity must be declared in the manifest; MainActivity is the launcher. " +
            "Things Android apps cannot do (root access, kernel modules, running a real Linux desktop natively) must be " +
            "scoped down to what an ordinary app can do."

    internal fun planSystem(pkg: String) =
        "You are a senior Android engineer planning an app. ${facts(pkg)}\n" +
            "Reply with JSON only: {\"files\": [{\"path\": \"relative/path\", \"purpose\": \"what it contains\"}]}. " +
            "Include every Kotlin source, layout XML, values XML (strings/colors/themes if used) and the " +
            "AndroidManifest.xml if it needs new activities. Be complete but keep files focused."

    private fun planUser(idea: String, existing: List<String>) =
        "App to build:\n$idea\n\nFiles that already exist:\n" + existing.joinToString("\n").ifEmpty { "(none)" }

    internal fun fileSystem(pkg: String) =
        "You are a senior Android engineer writing one file of a larger app. ${facts(pkg)}\n" +
            "Output ONLY the complete contents of the requested file — no explanations, no markdown fences. " +
            "Make it compile against the other files exactly (same class, id and resource names)."

    private fun fileUser(idea: String, plan: List<PlannedFile>, f: PlannedFile, context: String) =
        "App:\n$idea\n\nFull plan:\n" + plan.joinToString("\n") { "- ${it.path}: ${it.purpose}" } +
            "\n\nFiles written so far:\n$context\n\nNow write ${f.path} — ${f.purpose}"

    private fun fixUser(path: String, content: String, errors: List<String>, context: String) =
        "Fix these build errors in $path:\n" + errors.joinToString("\n") +
            "\n\nCurrent $path:\n$content\n\nOther project files for reference:\n$context\n\n" +
            "Output the complete corrected $path."

    // --- files --------------------------------------------------------------------------------

    private fun sourceFiles(root: File): List<File> = root.walkTopDown()
        .onEnter { it == root || it.name !in setOf("build", ".gradle", ".kotlin", "gradle") }
        .filter { it.isFile && it.extension in setOf("kt", "xml", "kts") }
        .toList()

    private fun contextFiles(root: File, exclude: String, budget: Int): String {
        val sb = StringBuilder()
        for (f in sourceFiles(root).filter { it.path.contains("/src/main/") || it.relativeTo(root).path == "app/build.gradle.kts" }) {
            val rel = f.relativeTo(root).path.replace('\\', '/')
            if (rel == exclude) continue
            val text = f.readText()
            if (sb.length + text.length > budget) {
                sb.append("=== $rel (omitted, ${text.length} chars) ===\n")
                continue
            }
            sb.append("=== $rel ===\n").append(text.trimEnd()).append("\n\n")
        }
        return sb.toString().ifBlank { "(none)" }
    }

    private fun tail(log: List<String>) = log.takeLast(15)

    companion object {
        private val ALLOWED_PREFIX = Regex("^app/src/main/(java/|kotlin/|res/(layout|values|drawable|menu|xml|mipmap|raw)[^/]*/|AndroidManifest\\.xml$)|^app/build\\.gradle\\.kts$")

        /** Reads {"files":[{"path","purpose"}]} leniently; keeps only safe, in-project paths. */
        fun parsePlan(reply: String, pkg: String): List<PlannedFile> {
            val start = reply.indexOf('{')
            val end = reply.lastIndexOf('}')
            if (start < 0 || end <= start) return emptyList()
            val arr = runCatching { JSONObject(reply.substring(start, end + 1)).optJSONArray("files") }.getOrNull() ?: return emptyList()
            return (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val path = WebCodeGen.normalizePath(o.optString("path")) ?: return@mapNotNull null
                if (!ALLOWED_PREFIX.containsMatchIn(path)) return@mapNotNull null
                PlannedFile(path, o.optString("purpose").ifBlank { "as planned" })
            }.distinctBy { it.path }
                // Sources before the manifest so the manifest can reference what exists.
                .sortedBy { if (it.path == "app/build.gradle.kts") -1 else if (it.path.endsWith("AndroidManifest.xml")) 2 else if (it.path.contains("/res/")) 0 else 1 }
        }

        /** Strips fences / chatter; XML must start at '<'. */
        fun cleanFile(reply: String, path: String): String {
            var text = reply
            val open = text.indexOf("```")
            if (open >= 0) {
                val bodyStart = text.indexOf('\n', open).let { if (it < 0) open + 3 else it + 1 }
                val close = text.indexOf("```", bodyStart)
                text = if (close >= 0) text.substring(bodyStart, close) else text.substring(bodyStart)
            }
            text = text.trim() + "\n"
            if (path.endsWith(".xml")) {
                val i = text.indexOf('<')
                if (i > 0) text = text.substring(i)
            } else if (path.endsWith(".kts")) {
                val i = text.indexOf("plugins")
                if (i > 0) text = text.substring(i)
            } else if (path.endsWith(".kt")) {
                val i = text.indexOf("package ")
                if (i > 0) text = text.substring(i)
            }
            return text
        }

        private val KOTLIN_ERR = Regex("""^e: (?:file://)?(\S+?\.kt):(\d+):(\d+) (.*)$""")
        private val AAPT_ERR = Regex("""^ERROR:\s*(\S+?\.xml):(\d+):\s*(?:AAPT: )?(.*)$""")
        private val AAPT_ERR2 = Regex("""(\S+/res/\S+?\.xml):(\d+):\s*error:\s*(.*)$""")

        // Manifest merger / resource linking / Gradle script errors: "<path>:12:5-30 Error: …" or "…build.gradle.kts:7:1: …"
        private val GENERIC_ERR = Regex("""(\S*/app/(?:src/main/\S+?\.\w+|build\.gradle\.kts)):(\d+)(?::[\d-]+)?:?\s+(?:Error:|error:|e:)?\s*(.+)$""")

        /** Gradle's "* What went wrong:" section, minus the boilerplate after it. */
        fun whatWentWrong(log: List<String>): List<String> {
            val out = mutableListOf<String>()
            var inside = false
            for (l in log) {
                val t = l.trim()
                if (t.startsWith("* What went wrong")) { inside = true; continue }
                if (inside && (t.startsWith("* Try") || t.startsWith("* Exception") || t.startsWith("BUILD FAILED"))) inside = false
                if (inside && t.isNotEmpty() && out.size < 40) out += t
            }
            return out
        }

        /** Build errors grouped by project-relative file path. */
        fun parseErrors(log: List<String>): Map<String, List<String>> {
            val out = LinkedHashMap<String, MutableList<String>>()
            for (raw in log) {
                val line = raw.trim()
                val m = KOTLIN_ERR.find(line) ?: AAPT_ERR.find(line) ?: AAPT_ERR2.find(line)
                    ?: GENERIC_ERR.find(line)?.takeIf { line.contains("rror") || line.startsWith("e:") } ?: continue
                val path = relativize(m.groupValues[1]) ?: continue
                val msg = if (m.groupValues.size > 4) "line ${m.groupValues[2]}: ${m.groupValues[4]}" else "line ${m.groupValues[2]}: ${m.groupValues[3]}"
                out.getOrPut(path) { mutableListOf() }.let { if (msg !in it && it.size < 20) it += msg }
            }
            return out
        }

        /** "/projects/apps/<name>/app/src/…" or any absolute path → "app/src/…". */
        private fun relativize(p: String): String? {
            if (p.endsWith("/app/build.gradle.kts") || p == "app/build.gradle.kts") return "app/build.gradle.kts"
            val i = p.indexOf("/app/src/")
            return if (i >= 0) p.substring(i + 1) else if (p.startsWith("app/src/")) p else null
        }
    }
}
