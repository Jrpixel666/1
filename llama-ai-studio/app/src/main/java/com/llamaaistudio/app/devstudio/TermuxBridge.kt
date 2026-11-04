package com.llamaaistudio.app.devstudio

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

/**
 * Talks to Termux's RUN_COMMAND plugin API to get ARM64-native build tooling onto the device.
 *
 * Why Termux and not Google's own Android SDK build-tools: aapt2/d8/zipalign in the official
 * `build-tools;*` package are published only for linux-x86_64, macOS and Windows hosts — there is
 * no linux-aarch64 build. Running them on an Android ARM64 device (no root, no emulation) is not
 * possible with the official binaries. Termux ships (or can install) aarch64-native OpenJDK,
 * Gradle and community-maintained aarch64 rebuilds of aapt/apksigner, which is the realistic path
 * to on-device compilation. Full Android-APK builds via this path are best-effort: Google's AGP
 * still expects the x86-only aapt2 artifact unless it is substituted for an aarch64 one, so plain
 * JVM (Kotlin/Java) compiles are the dependable case, APK assembly is experimental.
 */
object TermuxBridge {
    private const val TERMUX_PACKAGE = "com.termux"
    private const val RUN_COMMAND_ACTION = "com.termux.RUN_COMMAND"
    private const val RUN_COMMAND_SERVICE = "com.termux.app.RunCommandService"

    data class ToolchainComponent(
        val name: String,
        val termuxPackage: String,
        val arm64Compatible: Boolean,
        val note: String,
    )

    val components = listOf(
        ToolchainComponent("OpenJDK 17", "openjdk-17", true, "Termux aarch64 build — compatible"),
        ToolchainComponent("Gradle", "gradle", true, "Pure JVM — runs under Termux's JDK, compatible"),
        ToolchainComponent("Git", "git", true, "Termux aarch64 build — compatible"),
        ToolchainComponent("Kotlin compiler", "kotlin", true, "Pure JVM — compatible"),
        ToolchainComponent(
            "Android build-tools (aapt2/d8/zipalign)", "(none — see note)", false,
            "Google ships these for linux-x86_64/macOS/Windows only — no aarch64 build exists. " +
                "Full APK assembly on-device is therefore experimental and depends on community " +
                "aarch64 rebuilds, not Google's official binaries.",
        ),
    )

    fun isTermuxInstalled(context: Context): Boolean =
        try {
            context.packageManager.getPackageInfo(TERMUX_PACKAGE, 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }

    fun installTermuxIntent(): Intent =
        Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://f-droid.org/packages/com.termux/"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun setupCommand(): String =
        components.filter { it.arm64Compatible }.joinToString(" ") { it.termuxPackage }
            .let { pkgs -> "pkg install -y $pkgs" }

    /**
     * Fires Termux's RUN_COMMAND intent to run [shellCommand] in [workingDir] (must already be
     * reachable from Termux — e.g. under its own storage symlinks after `termux-setup-storage`).
     * Requires the user to have enabled "allow-external-apps=true" in Termux's own properties and
     * granted this app the com.termux.permission.RUN_COMMAND permission; returns false otherwise.
     */
    fun runCommand(context: Context, shellCommand: String, workingDir: String): Boolean {
        if (!isTermuxInstalled(context)) return false
        return try {
            val intent = Intent().apply {
                setClassName(TERMUX_PACKAGE, RUN_COMMAND_SERVICE)
                action = RUN_COMMAND_ACTION
                putExtra("com.termux.RUN_COMMAND_PATH", "/data/data/com.termux/files/usr/bin/bash")
                putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf("-c", shellCommand))
                putExtra("com.termux.RUN_COMMAND_WORKDIR", workingDir)
                putExtra("com.termux.RUN_COMMAND_BACKGROUND", false)
            }
            context.startForegroundService(intent)
            true
        } catch (e: Exception) {
            false
        }
    }
}
