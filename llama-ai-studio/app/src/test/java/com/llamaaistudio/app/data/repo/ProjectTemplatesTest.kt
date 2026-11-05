package com.llamaaistudio.app.data.repo

import com.llamaaistudio.app.linux.AndroidBuildToolchain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectTemplatesTest {
    @Test
    fun packageSegmentsAreValidIdentifiers() {
        assertEquals("my_app", ProjectTemplates.packageSegment("My-App"))
        assertEquals("p_2048", ProjectTemplates.packageSegment("2048"))
        assertEquals("app", ProjectTemplates.packageSegment(""))
    }

    @Test
    fun androidTemplateIsBuildableOnDevice() {
        val files = ProjectTemplates.files("my-app", "empty-compose")
        val app = files.getValue("app/build.gradle.kts")
        // Kotlin 2.x would default to JVM 1.8 against Java 17 and fail the build.
        assertTrue(app.contains("jvmTarget = \"17\""))
        assertTrue(app.contains("namespace = \"com.example.my_app\""))
        assertTrue("app/src/main/java/com/example/my_app/MainActivity.kt" in files)
        assertTrue(files.getValue("app/src/main/AndroidManifest.xml").contains("android.intent.category.LAUNCHER"))
    }

    @Test
    fun consoleTemplateMainClassMatchesItsSourceFile() {
        val files = ProjectTemplates.files("hello-cli", "library")
        assertTrue("src/main/kotlin/com/example/hello_cli/Main.kt" in files)
        assertTrue(files.getValue("build.gradle.kts").contains("mainClass.set(\"com.example.hello_cli.MainKt\")"))
    }

    @Test
    fun onDeviceGradlePropertiesUseTheArm64Aapt2() {
        val props = AndroidBuildToolchain.gradleProperties.toMap()
        assertEquals(AndroidBuildToolchain.AAPT2, props["android.aapt2FromMavenOverride"])
        assertTrue(AndroidBuildToolchain.setupScript().contains("sha256sum -c -"))
    }
}
