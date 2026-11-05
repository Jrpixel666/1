package com.llamaaistudio.app.linux

import java.io.File

/**
 * Everything needed to build Android apps inside the built-in Alpine Linux:
 * OpenJDK 17 (apk), the Android 35 platform + build-tools 34 (Google), and statically linked
 * aarch64 `aapt2`/`aidl`/`zipalign` (lzhiyong/android-sdk-tools) replacing Google's x86-64-only
 * ones. Gradle itself comes from each project's wrapper. All downloads are SHA-256 pinned.
 */
object AndroidBuildToolchain {
    const val SDK = "/opt/android-sdk"
    const val BUILD_TOOLS = "34.0.0"
    const val PLATFORM = "android-35"
    const val JAVA_HOME = "/usr/lib/jvm/java-17-openjdk"
    const val AAPT2 = "$SDK/build-tools/$BUILD_TOOLS/aapt2"
    private const val MARKER = "$SDK/.llama-studio-toolchain-1"

    private data class Download(val url: String, val sha256: String, val file: String)

    private val PLATFORM_ZIP = Download(
        "https://dl.google.com/android/repository/platform-35_r02.zip",
        "0988cacad01b38a18a47bac14a0695f246bc76c1b06c0eeb8eb0dc825ab0c8e0",
        "platform-35_r02.zip",
    )
    private val BUILD_TOOLS_ZIP = Download(
        "https://dl.google.com/android/repository/build-tools_r34-linux.zip",
        "e858c4b60069d0431051b225d384413b1643e1289b00a4825aed347f25bd510f",
        "build-tools_r34-linux.zip",
    )
    private val ARM64_TOOLS_ZIP = Download(
        "https://github.com/lzhiyong/android-sdk-tools/releases/download/35.0.2/android-sdk-tools-static-aarch64.zip",
        "db1cea2c4454d5f9c5a802646b2d1cf560b4ee7badbe23e51ab8e1881bb50fc2",
        "android-sdk-tools-static-aarch64.zip",
    )

    /** Guest-side environment for Gradle builds. */
    val buildEnv: Map<String, String> = mapOf(
        "JAVA_HOME" to JAVA_HOME,
        "ANDROID_HOME" to SDK,
        "ANDROID_SDK_ROOT" to SDK,
        "GRADLE_USER_HOME" to "/root/.gradle",
        "GRADLE_OPTS" to "-Dorg.gradle.daemon=false -Dorg.gradle.vfs.watch=false",
    )

    fun isInstalled(rootfs: File): Boolean = File(rootfs, MARKER.removePrefix("/")).isFile

    /** Idempotent setup script: re-running skips finished downloads and re-checks hashes. */
    fun setupScript(): String = """
        set -e
        echo "==> Java 17, bash, unzip (apk)"
        apk update >/dev/null
        apk add --no-progress openjdk17-jdk bash unzip ca-certificates
        mkdir -p $SDK/licenses $SDK/platforms $SDK/build-tools /root/.cache/llama-sdk
        cd /root/.cache/llama-sdk
        fetch() {
          if [ -f "${'$'}3" ] && echo "${'$'}2  ${'$'}3" | sha256sum -c - >/dev/null 2>&1; then echo "    cached ${'$'}3"; return 0; fi
          echo "==> Downloading ${'$'}3"
          wget -q -O "${'$'}3.part" "${'$'}1"
          mv "${'$'}3.part" "${'$'}3"
          echo "${'$'}2  ${'$'}3" | sha256sum -c - || { rm -f "${'$'}3"; exit 1; }
        }
        ${fetchLine(PLATFORM_ZIP)}
        ${fetchLine(BUILD_TOOLS_ZIP)}
        ${fetchLine(ARM64_TOOLS_ZIP)}

        echo "==> Installing platforms;$PLATFORM"
        rm -rf $SDK/platforms/$PLATFORM /tmp/llama-sdk-x && mkdir -p /tmp/llama-sdk-x
        unzip -q ${PLATFORM_ZIP.file} -d /tmp/llama-sdk-x
        mv /tmp/llama-sdk-x/$PLATFORM $SDK/platforms/$PLATFORM

        echo "==> Installing build-tools;$BUILD_TOOLS (with aarch64 aapt2/aidl/zipalign)"
        rm -rf $SDK/build-tools/$BUILD_TOOLS /tmp/llama-sdk-x && mkdir -p /tmp/llama-sdk-x
        unzip -q ${BUILD_TOOLS_ZIP.file} -d /tmp/llama-sdk-x
        mv /tmp/llama-sdk-x/android-14 $SDK/build-tools/$BUILD_TOOLS
        rm -rf /tmp/llama-sdk-x && mkdir -p /tmp/llama-sdk-x
        unzip -q ${ARM64_TOOLS_ZIP.file} -d /tmp/llama-sdk-x
        for t in aapt aapt2 aidl zipalign dexdump split-select; do
          cp -f /tmp/llama-sdk-x/build-tools/${'$'}t $SDK/build-tools/$BUILD_TOOLS/${'$'}t
          chmod 755 $SDK/build-tools/$BUILD_TOOLS/${'$'}t
        done
        mkdir -p $SDK/platform-tools
        cp -f /tmp/llama-sdk-x/platform-tools/adb $SDK/platform-tools/adb && chmod 755 $SDK/platform-tools/adb
        rm -rf /tmp/llama-sdk-x

        # Accepting the Android SDK License Agreement (shown in the app before this runs).
        printf '\n24333f8a63b6825ea9c5514f83c2829b004d1fee\n8933bad161af4178b1185d1a37fbf41ea5269c55\nd56f5187479451eabf01fb78af6dfcb131a6481e\n' > $SDK/licenses/android-sdk-license

        echo "==> Checking"
        $AAPT2 version
        ${'$'}JAVA_HOME/bin/java -version 2>&1 | head -1
        touch $MARKER
        echo "Android build tools are ready."
    """.trimIndent()

    private fun fetchLine(d: Download) = "fetch '${d.url}' '${d.sha256}' '${d.file}'"

    /** gradle.properties lines a project needs to build on-device; added if missing. */
    val gradleProperties: List<Pair<String, String>> = listOf(
        // Google's aapt2 from Maven is x86-64 only; use the static aarch64 build instead.
        "android.aapt2FromMavenOverride" to AAPT2,
        "android.useAndroidX" to "true",
        "org.gradle.jvmargs" to "-Xmx1536m -Dfile.encoding=UTF-8",
        "org.gradle.daemon" to "false",
        "org.gradle.vfs.watch" to "false",
        "kotlin.compiler.execution.strategy" to "in-process",
        "kotlin.incremental" to "false",
    )

    fun buildCommand(task: String): String =
        "test -x ./gradlew || chmod +x ./gradlew; bash ./gradlew --no-daemon --console=plain $task"
}
