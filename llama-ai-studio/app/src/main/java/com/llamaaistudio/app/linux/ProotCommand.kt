package com.llamaaistudio.app.linux

import java.io.File

/**
 * Builds the proot invocation that runs a shell command inside the Alpine root filesystem.
 * Pure (no Android APIs) so it can be unit-tested.
 */
object ProotCommand {
    const val GUEST_PATH = "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"

    /** Host directory → guest mount point. */
    data class Bind(val host: String, val guest: String)

    fun argv(
        proot: File,
        rootfs: File,
        command: String,
        binds: List<Bind> = emptyList(),
        workdir: String = "/root",
        extraEnv: Map<String, String> = emptyMap(),
    ): List<String> = buildList {
        add(proot.absolutePath)
        add("--kill-on-exit")   // stop every guest process when the command finishes/is stopped
        add("--link2symlink")   // Android forbids hard links in app storage; apk/npm use them
        add("-0")               // fake root, so `apk add` works without real root
        add("-r"); add(rootfs.absolutePath)
        for (dir in listOf("/dev", "/proc", "/sys")) { add("-b"); add(dir) }
        // Shared memory for runtimes (Node, JVM) that expect a writable /dev/shm.
        add("-b"); add("${File(rootfs, "tmp").absolutePath}:/dev/shm")
        binds.forEach { add("-b"); add("${it.host}:${it.guest}") }
        add("-w"); add(workdir)
        add("/usr/bin/env"); add("-i")
        val env = linkedMapOf(
            "HOME" to "/root",
            "USER" to "root",
            "LOGNAME" to "root",
            "PATH" to GUEST_PATH,
            "TERM" to "dumb",
            "LANG" to "C.UTF-8",
            "TMPDIR" to "/tmp",
            "SHELL" to "/bin/sh",
        )
        env.putAll(extraEnv)
        env.forEach { (k, v) -> add("$k=$v") }
        add("/bin/sh"); add("-l"); add("-c"); add(command)
    }

    /** Environment of the proot process itself (host side). */
    fun hostEnv(loader: File, prootTmp: File): Map<String, String> = mapOf(
        // Unbundled loader from nativeLibraryDir: the bundled one would be extracted to app
        // storage and executed from there, which Android 10+ blocks for modern apps.
        "PROOT_LOADER" to loader.absolutePath,
        "PROOT_TMP_DIR" to prootTmp.absolutePath,
    )
}
