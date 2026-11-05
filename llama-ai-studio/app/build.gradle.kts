import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.llamaaistudio.app"
    compileSdk = 35
    ndkVersion = "27.2.12479018"

    defaultConfig {
        applicationId = "com.llamaaistudio.app"
        minSdk = 31 // Pixel 9 ships Android 14+; 31 keeps headroom for the thermal/perf APIs we rely on
        targetSdk = 35
        versionCode = 21
        versionName = "1.10.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            // Pixel 9 / Tensor G4 is arm64-v8a only; we don't ship 32-bit or x86 to keep the APK lean.
            abiFilters += "arm64-v8a"
        }

        externalNativeBuild {
            cmake {
                // All llama.cpp/ggml build flags live in app/src/main/cpp/CMakeLists.txt (single
                // source of truth); this only sets the Android NDK side of the toolchain.
                arguments += listOf("-DANDROID_STL=c++_shared")
                cppFlags += listOf("-std=c++17", "-O3", "-fvisibility=hidden")
                abiFilters += "arm64-v8a"
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    signingConfigs {
        create("release") {
            val ksProps = Properties()
            val ksFile = rootProject.file("keystore.properties")
            if (ksFile.exists()) {
                ksProps.load(ksFile.inputStream())
                storeFile = rootProject.file(ksProps.getProperty("storeFile", "release.keystore"))
                storePassword = ksProps.getProperty("storePassword")
                keyAlias = ksProps.getProperty("keyAlias")
                keyPassword = ksProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            isDebuggable = true
            applicationIdSuffix = ".debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (rootProject.file("keystore.properties").exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }


    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        jniLibs {
            // Extracted to nativeLibraryDir at install time: proot (libproot.so) and its loader
            // are executables, and that directory is the only place Android lets a modern app
            // execve() files from. Uncompressed in-APK libraries can't be executed.
            useLegacyPackaging = true
            // :app and :imagegen each independently build against ANDROID_STL=c++_shared;
            // both contribute a byte-identical libc++_shared.so from the same NDK, which
            // the merge step otherwise flags as a conflict.
            pickFirsts += "**/libc++_shared.so"
        }
    }

}

// --- proot (built-in Linux) -----------------------------------------------------------------
// Cross-compiles third_party/proot + talloc with the NDK into generated jniLibs, so the APK
// ships libproot.so / libproot-loader.so next to the other native libraries.
val prootJniLibs = layout.buildDirectory.dir("generated/proot/jniLibs")
val buildProot = tasks.register<Exec>("buildProot") {
    description = "Cross-compiles proot for arm64-v8a with the NDK"
    val script = rootProject.file("scripts/build-proot.sh")
    inputs.file(script)
    inputs.dir(rootProject.file("third_party/proot/src"))
    inputs.dir(rootProject.file("third_party/talloc-android"))
    inputs.files(rootProject.file("third_party/talloc/talloc.c"), rootProject.file("third_party/talloc/talloc.h"))
    outputs.dir(prootJniLibs)
    val sdkDir = androidComponents.sdkComponents.sdkDirectory
    val ndkVersion = android.ndkVersion
    doFirst {
        val ndk = sdkDir.get().asFile.resolve("ndk/$ndkVersion")
        commandLine("bash", script.absolutePath, ndk.absolutePath, prootJniLibs.get().dir("arm64-v8a").asFile.absolutePath)
    }
}
android.sourceSets.getByName("main").jniLibs.srcDir(prootJniLibs)
tasks.matching { it.name.startsWith("merge") && it.name.endsWith("JniLibFolders") }.configureEach {
    dependsOn(buildProot)
}

tasks.withType<KotlinCompile>().configureEach {
    compilerOptions {
        freeCompilerArgs.addAll("-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi")
    }
}

dependencies {
    implementation(project(":imagegen"))

    val composeBom = platform("androidx.compose:compose-bom:2024.11.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.activity:activity-compose:1.9.3")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.navigation:navigation-compose:2.8.4")
    implementation("androidx.window:window:1.3.0")

    // Persistence
    implementation("androidx.room:room-runtime:2.8.4")
    implementation("androidx.room:room-ktx:2.8.4")
    ksp("androidx.room:room-compiler:2.8.4")
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // LiteRT-LM: Google's on-device LLM runtime (.litertlm models, CPU / GPU / NPU)
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.19.0")

    // Built-in Linux: unpacking the Alpine root filesystem (tar.gz with symlinks/modes)
    implementation("org.apache.commons:commons-compress:1.27.1")

    // Networking (Hugging Face API + model downloads)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-moshi:2.11.0")
    implementation("com.squareup.moshi:moshi:1.15.2")
    ksp("com.squareup.moshi:moshi-kotlin-codegen:1.15.2")

    testImplementation("junit:junit:4.13.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    // Real org.json for JVM unit tests (android.jar only ships throwing stubs).
    testImplementation("org.json:json:20240303")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}
