package com.llamaaistudio.app.data.repo

import android.content.Context
import com.llamaaistudio.app.data.db.DevProjectDao
import com.llamaaistudio.app.data.db.DevProjectEntity
import com.llamaaistudio.app.linux.AndroidBuildToolchain
import com.llamaaistudio.app.util.DownloadsExporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Dev Studio projects, stored in app storage and mounted inside the built-in Linux at
 * `/projects/apps/<name>`, where Gradle builds them.
 *
 * Templates: "empty-compose" (historical id; an Android app with one Activity) and "library"
 * (a Kotlin console program, built and run with the Gradle `application` plugin).
 */
class DevProjectRepository(
    private val context: Context,
    private val dao: DevProjectDao,
) {
    val projectsRoot: File = File(context.filesDir, "projects").apply { mkdirs() }

    val projects: Flow<List<DevProjectEntity>> = dao.observeAll()

    suspend fun createProject(name: String, template: String): DevProjectEntity =
        withContext(Dispatchers.IO) {
            val base = name.replace(Regex("[^A-Za-z0-9_-]"), "_").ifBlank { "project" }
            var safeName = base
            var n = 2
            while (File(projectsRoot, safeName).exists()) safeName = "${base}_${n++}"
            val root = File(projectsRoot, safeName)
            root.mkdirs()
            scaffold(root, safeName, template)
            val entity = DevProjectEntity(name = safeName, rootPath = root.absolutePath, template = template)
            val id = dao.insert(entity)
            entity.copy(id = id)
        }

    suspend fun touchOpened(project: DevProjectEntity) {
        dao.update(project.copy(lastOpenedAt = System.currentTimeMillis()))
    }

    suspend fun deleteProject(project: DevProjectEntity) = withContext(Dispatchers.IO) {
        File(project.rootPath).deleteRecursively()
        dao.delete(project)
    }

    /** Source files only — build output and Gradle caches would otherwise flood the list. */
    fun listFiles(root: File): List<File> =
        root.walkTopDown()
            .onEnter { it == root || it.name !in IGNORED_DIRS }
            .filter { it.isFile && it.name != "gradle-wrapper.jar" }
            .sortedBy { it.relativeTo(root).path }
            .toList()

    suspend fun readFile(file: File): String = withContext(Dispatchers.IO) { file.readText() }

    suspend fun writeFile(file: File, content: String) = withContext(Dispatchers.IO) {
        file.parentFile?.mkdirs()
        file.writeText(content)
    }

    /** Copies the project's sources into Download/LlamaAiStudioProjects/<name> (MediaStore). */
    suspend fun exportToDownloads(project: DevProjectEntity): String = withContext(Dispatchers.IO) {
        DownloadsExporter.exportTree(
            context, File(project.rootPath), "LlamaAiStudioProjects/${project.name}", skipDirs = IGNORED_DIRS,
        )
    }

    fun guestPath(project: DevProjectEntity): String = "/projects/apps/${File(project.rootPath).name}"

    fun isAndroidApp(project: DevProjectEntity): Boolean = project.template != "library"

    /** The debug APK Gradle produces for an Android project, if it has been built. */
    fun builtApk(project: DevProjectEntity): File =
        File(project.rootPath, "app/build/outputs/apk/debug/app-debug.apk")

    /**
     * Makes sure the project can be built on-device: Gradle wrapper, gradle.properties with the
     * aarch64 aapt2 override, local.properties pointing at the in-Linux SDK, and (for projects
     * scaffolded by older versions of this app) the Kotlin JVM target fix. Only adds what's
     * missing; never overwrites the user's own settings.
     */
    suspend fun ensureBuildFiles(project: DevProjectEntity) = withContext(Dispatchers.IO) {
        val root = File(project.rootPath)
        installWrapper(root)

        val props = File(root, "gradle.properties")
        val existing = if (props.exists()) props.readText() else ""
        val keys = existing.lineSequence().map { it.substringBefore('=').trim() }.toSet()
        val missing = AndroidBuildToolchain.gradleProperties.filter { (k, _) -> k !in keys }
        if (missing.isNotEmpty()) {
            val sb = StringBuilder(existing)
            if (sb.isNotEmpty() && !sb.endsWith("\n")) sb.append('\n')
            if (existing.isEmpty()) sb.append("# Added by Llama AI Studio for on-device builds\n")
            missing.forEach { (k, v) -> sb.append(k).append('=').append(v).append('\n') }
            props.writeText(sb.toString())
        }
        File(root, "local.properties").writeText("sdk.dir=${AndroidBuildToolchain.SDK}\n")

        // Projects from older scaffolds: Kotlin 2.x defaults to JVM target 1.8 while the
        // Java side is 17, which fails the build with "Inconsistent JVM-target compatibility".
        val appBuild = File(root, "app/build.gradle.kts")
        if (isAndroidApp(project) && appBuild.isFile) {
            val text = appBuild.readText()
            var updated = text
            if (!updated.contains("jvmTarget") && updated.contains("compileOptions")) {
                updated = updated.trimEnd().removeSuffix("}").trimEnd() + "\n    kotlinOptions {\n        jvmTarget = \"17\"\n    }\n}\n"
            }
            // Older scaffolds had no dependencies; AI-written code usually needs AppCompat etc.
            if (!updated.contains("dependencies {")) {
                updated = updated.trimEnd() + "\n\n" + ProjectTemplates.ANDROID_DEPENDENCIES + "\n"
            }
            if (updated != text) appBuild.writeText(updated)
            // AppCompatActivity crashes at launch without an AppCompat theme.
            val manifest = File(root, "app/src/main/AndroidManifest.xml")
            if (manifest.isFile) {
                val m = manifest.readText()
                val fixed = m.replace("@android:style/Theme.Material.Light.NoActionBar", "@style/Theme.AppCompat.DayNight.NoActionBar")
                if (fixed != m) manifest.writeText(fixed)
            }
        }
    }

    private fun installWrapper(root: File) {
        val gradlew = File(root, "gradlew")
        if (!gradlew.exists()) {
            context.assets.open("gradle-wrapper/gradlew").use { input -> gradlew.outputStream().use { input.copyTo(it) } }
        }
        gradlew.setExecutable(true, false)
        val wrapperDir = File(root, "gradle/wrapper").apply { mkdirs() }
        val jar = File(wrapperDir, "gradle-wrapper.jar")
        if (!jar.exists()) {
            context.assets.open("gradle-wrapper/gradle-wrapper.jar").use { input -> jar.outputStream().use { input.copyTo(it) } }
        }
        val wrapperProps = File(wrapperDir, "gradle-wrapper.properties")
        if (!wrapperProps.exists()) {
            wrapperProps.writeText(
                "distributionBase=GRADLE_USER_HOME\n" +
                    "distributionPath=wrapper/dists\n" +
                    "distributionUrl=https\\://services.gradle.org/distributions/gradle-$GRADLE_VERSION-bin.zip\n" +
                    "networkTimeout=60000\n" +
                    "validateDistributionUrl=true\n" +
                    "zipStoreBase=GRADLE_USER_HOME\n" +
                    "zipStorePath=wrapper/dists\n"
            )
        }
    }

    private fun scaffold(root: File, name: String, template: String) {
        ProjectTemplates.files(name, template).forEach { (path, content) ->
            File(root, path).apply { parentFile?.mkdirs() }.writeText(content)
        }
        installWrapper(root)
    }

    companion object {
        const val GRADLE_VERSION = ProjectTemplates.GRADLE_VERSION
        val IGNORED_DIRS = setOf("build", ".gradle", ".kotlin", ".idea", ".cxx")
    }
}
