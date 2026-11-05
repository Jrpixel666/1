package com.llamaaistudio.app.data.repo

/**
 * Starter projects for Dev Studio, as relative path → file content (pure, so the templates
 * can be built and checked off-device). Templates: "empty-compose" (historical id; an Android
 * app with one Activity) and "library" (a Kotlin console program run with `gradle run`).
 */
object ProjectTemplates {
    const val AGP_VERSION = "8.7.2"
    const val KOTLIN_VERSION = "2.0.21"
    const val GRADLE_VERSION = "8.14.3"

    /**
     * Libraries every Android project gets, so typical AI-written code (AppCompatActivity,
     * Material buttons, ConstraintLayout) compiles without the user editing Gradle files.
     */
    const val ANDROID_DEPENDENCIES = """dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
}"""

    /** A valid Kotlin package / applicationId segment for [name] ('-' and leading digits aren't). */
    fun packageSegment(name: String): String =
        name.lowercase().replace(Regex("[^a-z0-9_]"), "_").ifEmpty { "app" }
            .let { if (it.first().isDigit()) "p_$it" else it }

    fun files(name: String, template: String): Map<String, String> {
        val pkg = packageSegment(name)
        val common = mapOf(".gitignore" to ".gradle/\n.kotlin/\nbuild/\napp/build/\nlocal.properties\n")
        return common + if (template == "library") console(name, pkg) else android(name, pkg)
    }

    private fun android(name: String, pkg: String): Map<String, String> = mapOf(
        "settings.gradle.kts" to """
            pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
            dependencyResolutionManagement { repositories { google(); mavenCentral() } }
            rootProject.name = "$name"
            include(":app")
        """.trimIndent() + "\n",
        "build.gradle.kts" to """
            plugins {
                id("com.android.application") version "$AGP_VERSION" apply false
                id("org.jetbrains.kotlin.android") version "$KOTLIN_VERSION" apply false
            }
        """.trimIndent() + "\n",
        "app/build.gradle.kts" to """
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
                kotlinOptions {
                    jvmTarget = "17"
                }
            }
        """.trimIndent() + "\n\n" + ANDROID_DEPENDENCIES + "\n",
        "app/src/main/java/com/example/$pkg/MainActivity.kt" to """
            package com.example.$pkg

            import android.app.Activity
            import android.os.Bundle
            import android.view.Gravity
            import android.widget.TextView

            class MainActivity : Activity() {
                override fun onCreate(savedInstanceState: Bundle?) {
                    super.onCreate(savedInstanceState)
                    setContentView(TextView(this).apply {
                        text = "Hello from $name!\nBuilt on this phone with Llama AI Studio."
                        textSize = 22f
                        gravity = Gravity.CENTER
                    })
                }
            }
        """.trimIndent() + "\n",
        "app/src/main/AndroidManifest.xml" to """
            <?xml version="1.0" encoding="utf-8"?>
            <manifest xmlns:android="http://schemas.android.com/apk/res/android">
                <application
                    android:label="$name"
                    android:theme="@style/Theme.AppCompat.DayNight.NoActionBar">
                    <activity android:name=".MainActivity" android:exported="true">
                        <intent-filter>
                            <action android:name="android.intent.action.MAIN" />
                            <category android:name="android.intent.category.LAUNCHER" />
                        </intent-filter>
                    </activity>
                </application>
            </manifest>
        """.trimIndent() + "\n",
    )

    private fun console(name: String, pkg: String): Map<String, String> = mapOf(
        "settings.gradle.kts" to """
            pluginManagement { repositories { mavenCentral(); gradlePluginPortal() } }
            dependencyResolutionManagement { repositories { mavenCentral() } }
            rootProject.name = "$name"
        """.trimIndent() + "\n",
        "build.gradle.kts" to """
            plugins {
                kotlin("jvm") version "$KOTLIN_VERSION"
                application
            }

            kotlin { jvmToolchain(17) }

            application {
                mainClass.set("com.example.$pkg.MainKt")
            }
        """.trimIndent() + "\n",
        "src/main/kotlin/com/example/$pkg/Main.kt" to """
            package com.example.$pkg

            fun main() {
                println("Hello from $name!")
            }
        """.trimIndent() + "\n",
    )
}
