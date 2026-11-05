package com.llamaaistudio.app.ui.devstudio

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ProjectBuilderTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun parsesPlanKeepingOnlySafePathsManifestLast() {
        val reply = """Here's the plan:
            ```json
            {"files": [
              {"path": "app/src/main/AndroidManifest.xml", "purpose": "declares activities"},
              {"path": "app/src/main/java/com/example/golf/MainActivity.kt", "purpose": "home screen"},
              {"path": "app/src/main/res/layout/activity_main.xml", "purpose": "home layout"},
              {"path": "../../etc/passwd", "purpose": "evil"},
              {"path": "build.gradle.kts", "purpose": "not allowed"},
              {"path": "app/src/main/res/values/strings.xml", "purpose": "strings"}
            ]}
            ```"""
        val plan = ProjectBuilder.parsePlan(reply, "golf").map { it.path }
        assertEquals(
            listOf(
                "app/src/main/res/layout/activity_main.xml",
                "app/src/main/res/values/strings.xml",
                "app/src/main/java/com/example/golf/MainActivity.kt",
                "app/src/main/AndroidManifest.xml",
            ),
            plan,
        )
    }

    @Test
    fun groupsKotlinAndAaptErrorsByFile() {
        val log = listOf(
            "e: file:///projects/apps/golf/app/src/main/java/com/example/golf/MainActivity.kt:6:17 Unresolved reference 'appcompat'.",
            "e: file:///projects/apps/golf/app/src/main/java/com/example/golf/MainActivity.kt:15:5 'onCreate' overrides nothing.",
            "ERROR: /projects/apps/golf/app/src/main/res/layout/activity_main.xml:12: AAPT: error: resource string/title not found.",
            "> Task :app:compileDebugKotlin FAILED",
        )
        val errors = ProjectBuilder.parseErrors(log)
        assertEquals(2, errors.getValue("app/src/main/java/com/example/golf/MainActivity.kt").size)
        assertTrue(errors.getValue("app/src/main/res/layout/activity_main.xml").single().contains("string/title"))
    }

    @Test
    fun cleansChattyReplies() {
        assertEquals("package a\n\nclass A\n", ProjectBuilder.cleanFile("Sure!\n```kotlin\npackage a\n\nclass A\n```", "x/A.kt"))
        assertEquals("<LinearLayout/>\n", ProjectBuilder.cleanFile("Here you go: <LinearLayout/>", "x/a.xml"))
    }

    /** Fake model: plans 2 files, writes them (one broken), then fixes the broken one. */
    private class FakeAi : CodeAi {
        override val label = "fake"
        override val largeContext = true
        val calls = mutableListOf<String>()
        override suspend fun complete(system: String, user: String, maxTokens: Int): Result<String> {
            calls += user.lineSequence().first()
            return Result.success(
                when {
                    user.startsWith("App to build:") -> """{"files":[
                        {"path":"app/src/main/java/com/example/golf/MainActivity.kt","purpose":"home"},
                        {"path":"app/src/main/res/values/strings.xml","purpose":"strings"}]}"""
                    user.startsWith("Fix these build errors") -> "package com.example.golf\nclass MainActivity // fixed\n"
                    user.contains("Now write app/src/main/java") -> "package com.example.golf\nclass MainActivity { broken\n"
                    else -> "<resources><string name=\"app\">Golf</string></resources>"
                }
            )
        }
    }

    /** Fake Gradle: fails while MainActivity contains "broken". */
    private class FakeRunner(private val root: File) : ProjectBuildRunner {
        var builds = 0
        override suspend fun build(onLog: (String) -> Unit): ProjectBuildRunner.Outcome {
            builds++
            val main = File(root, "app/src/main/java/com/example/golf/MainActivity.kt").readText()
            return if ("broken" in main) {
                ProjectBuildRunner.Outcome(false, listOf("e: file:///projects/apps/golf/app/src/main/java/com/example/golf/MainActivity.kt:2:30 Expecting '}'"), null)
            } else {
                ProjectBuildRunner.Outcome(true, listOf("BUILD SUCCESSFUL"), File(root, "app-debug.apk"))
            }
        }
    }

    @Test
    fun plansWritesBuildsFixesAndSucceeds() = runBlocking {
        val root = tmp.newFolder("golf")
        val builder = ProjectBuilder(CoroutineScope(Dispatchers.Default))
        val ai = FakeAi()
        val runner = FakeRunner(root)

        builder.plan(1, root, "golf", "A golf score app", ai)
        withTimeout(5000) { builder.state.first { it.phase == ProjectBuilder.Phase.PLANNED } }
        assertEquals(2, builder.state.value.plan.size)

        builder.buildAll(root, "golf", ai, runner)
        val done = withTimeout(5000) { builder.state.first { it.phase == ProjectBuilder.Phase.DONE || it.phase == ProjectBuilder.Phase.FAILED } }
        assertEquals(ProjectBuilder.Phase.DONE, done.phase)
        assertEquals(2, runner.builds) // failed once, fixed, succeeded
        assertTrue(File(root, "app/src/main/java/com/example/golf/MainActivity.kt").readText().contains("fixed"))
        assertTrue(File(root, "app/src/main/res/values/strings.xml").isFile)
        assertTrue(ai.calls.any { it.startsWith("Fix these build errors in app/src/main/java/com/example/golf/MainActivity.kt") })
    }

    @Test
    fun givesUpAfterMaxRounds() = runBlocking {
        val root = tmp.newFolder("golf")
        val builder = ProjectBuilder(CoroutineScope(Dispatchers.Default)).apply { maxFixRounds = 2 }
        val stubborn = object : CodeAi {
            override val label = "stubborn"
            override val largeContext = false
            override suspend fun complete(system: String, user: String, maxTokens: Int) = Result.success(
                if (user.startsWith("App to build:")) """{"files":[{"path":"app/src/main/java/com/example/golf/MainActivity.kt","purpose":"x"}]}"""
                else "package com.example.golf\nclass MainActivity { broken\n"
            )
        }
        val runner = FakeRunner(root)
        builder.plan(1, root, "golf", "x", stubborn)
        withTimeout(5000) { builder.state.first { it.phase == ProjectBuilder.Phase.PLANNED } }
        builder.buildAll(root, "golf", stubborn, runner)
        val end = withTimeout(5000) { builder.state.first { it.phase == ProjectBuilder.Phase.FAILED } }
        assertEquals(3, runner.builds) // initial + 2 fix rounds
        assertTrue(end.lastErrors.isNotEmpty())
    }
}
