package com.llamaaistudio.app.data.repo

import android.content.Context
import com.llamaaistudio.app.data.db.DevProjectDao
import com.llamaaistudio.app.data.db.DevProjectEntity
import com.llamaaistudio.app.util.DownloadsExporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File

class DevProjectRepository(
    private val context: Context,
    private val dao: DevProjectDao,
) {
    val projectsRoot: File = File(context.filesDir, "projects").apply { mkdirs() }

    val projects: Flow<List<DevProjectEntity>> = dao.observeAll()

    suspend fun createProject(name: String, template: String): DevProjectEntity =
        withContext(Dispatchers.IO) {
            val safeName = name.replace(Regex("[^A-Za-z0-9_-]"), "_").ifBlank { "project" }
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

    fun listFiles(root: File): List<File> =
        root.walkTopDown().filter { it.isFile }.sortedBy { it.relativeTo(root).path }.toList()

    suspend fun readFile(file: File): String = withContext(Dispatchers.IO) { file.readText() }

    suspend fun writeFile(file: File, content: String) = withContext(Dispatchers.IO) {
        file.parentFile?.mkdirs()
        file.writeText(content)
    }

    /**
     * Copies the project tree into the shared Downloads collection via MediaStore so Termux (or
     * any other app with storage access) can reach it — direct java.io.File writes to shared
     * storage are blocked by scoped storage on API 29+, so this goes through
     * MediaStore.Downloads instead, which needs no runtime permission for app-contributed files.
     */
    suspend fun exportToDownloads(project: DevProjectEntity): String = withContext(Dispatchers.IO) {
        DownloadsExporter.exportTree(context, File(project.rootPath), "LlamaAiStudioProjects/${project.name}")
    }

    private fun scaffold(root: File, name: String, template: String) {
        // Project names may contain '-' or start with a digit; neither is legal in a Kotlin
        // package / applicationId segment.
        val pkg = name.lowercase().replace(Regex("[^a-z0-9_]"), "_")
            .let { if (it.first().isDigit()) "p_$it" else it }
        File(root, "settings.gradle.kts").writeText(
            """
            pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
            dependencyResolutionManagement { repositories { google(); mavenCentral() } }
            rootProject.name = "$name"
            include(":app")
            """.trimIndent()
        )
        File(root, "build.gradle.kts").writeText(
            """
            plugins {
                id("com.android.application") version "8.7.2" apply false
                id("org.jetbrains.kotlin.android") version "2.0.21" apply false
            }
            """.trimIndent()
        )
        val appDir = File(root, "app/src/main").apply { mkdirs() }
        File(root, "app/build.gradle.kts").writeText(
            """
            plugins {
                id("com.android.application")
                id("org.jetbrains.kotlin.android")
            }
            android {
                namespace = "com.example.$pkg"
                compileSdk = 35
                defaultConfig {
                    applicationId = "com.example.$pkg"
                    minSdk = 26
                    targetSdk = 35
                    versionCode = 1
                    versionName = "1.0"
                }
                compileOptions {
                    sourceCompatibility = JavaVersion.VERSION_17
                    targetCompatibility = JavaVersion.VERSION_17
                }
            }
            """.trimIndent()
        )
        val pkgDir = File(appDir, "java/com/example/$pkg").apply { mkdirs() }
        when (template) {
            "empty-compose" -> File(pkgDir, "MainActivity.kt").writeText(
                """
                package com.example.$pkg

                import android.app.Activity
                import android.os.Bundle
                import android.widget.TextView

                class MainActivity : Activity() {
                    override fun onCreate(savedInstanceState: Bundle?) {
                        super.onCreate(savedInstanceState)
                        setContentView(TextView(this).apply { text = "Hello from $name" })
                    }
                }
                """.trimIndent()
            )
            else -> File(pkgDir, "Main.kt").writeText(
                """
                package com.example.$pkg

                fun main() {
                    println("Hello from $name")
                }
                """.trimIndent()
            )
        }
        File(appDir, "AndroidManifest.xml").apply {
            parentFile?.mkdirs()
            writeText(
                """
                <?xml version="1.0" encoding="utf-8"?>
                <manifest xmlns:android="http://schemas.android.com/apk/res/android">
                    <application android:label="$name">
                        <activity android:name=".MainActivity" android:exported="true">
                            <intent-filter>
                                <action android:name="android.intent.action.MAIN" />
                                <category android:name="android.intent.category.LAUNCHER" />
                            </intent-filter>
                        </activity>
                    </application>
                </manifest>
                """.trimIndent()
            )
        }
    }
}
