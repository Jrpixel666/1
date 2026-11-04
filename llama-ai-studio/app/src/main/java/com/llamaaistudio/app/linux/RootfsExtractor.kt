package com.llamaaistudio.app.linux

import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Paths
import java.nio.file.attribute.PosixFilePermission
import java.util.zip.GZIPInputStream

/**
 * Unpacks a Linux root filesystem tarball (e.g. Alpine's minirootfs) into [destination],
 * preserving what a distro needs to work: directories, regular files with their permission
 * bits (executables must stay executable), symlinks (busybox applets are all symlinks) and
 * hard links (materialised as copies — Android's app storage doesn't allow hard links).
 *
 * Every entry is checked to stay inside [destination], including through already-extracted
 * symlinks, so a hostile archive can't write anywhere else in app storage.
 */
object RootfsExtractor {

    data class Result(val files: Int, val dirs: Int, val symlinks: Int, val hardlinks: Int)

    fun extractTarGz(input: InputStream, destination: File, onProgress: (entries: Int) -> Unit = {}): Result =
        TarArchiveInputStream(GZIPInputStream(input.buffered(1 shl 16))).use { extractTar(it, destination, onProgress) }

    fun extractTar(tar: TarArchiveInputStream, destination: File, onProgress: (Int) -> Unit = {}): Result {
        destination.mkdirs()
        val root = destination.canonicalFile
        var files = 0
        var dirs = 0
        var symlinks = 0
        val hardlinks = mutableListOf<Pair<File, String>>()
        val dirModes = mutableListOf<Pair<File, Int>>()
        var count = 0

        while (true) {
            val entry: TarArchiveEntry = tar.nextEntry ?: break
            val target = resolveForWrite(root, entry.name) ?: continue // skip "./" and unsafe names
            when {
                entry.isDirectory -> {
                    target.mkdirs()
                    dirModes += target to entry.mode
                    dirs++
                }
                entry.isSymbolicLink -> {
                    target.parentFile?.mkdirs()
                    deleteIfExists(target)
                    Files.createSymbolicLink(target.toPath(), Paths.get(entry.linkName))
                    symlinks++
                }
                entry.isLink -> {
                    // Hard link: copy once its source exists (sources always precede links in
                    // well-formed archives, but resolve at the end to be order-independent).
                    hardlinks += target to entry.linkName
                }
                entry.isFile -> {
                    target.parentFile?.mkdirs()
                    deleteIfExists(target)
                    target.outputStream().use { out -> tar.copyTo(out, 1 shl 16) }
                    applyMode(target, entry.mode)
                    files++
                }
                // Device nodes, FIFOs etc. are meaningless without root — skip them.
            }
            if (++count % 200 == 0) onProgress(count)
        }

        for ((link, sourceName) in hardlinks) {
            val source = resolveForWrite(root, sourceName) ?: continue
            if (!source.isFile) continue
            deleteIfExists(link)
            source.copyTo(link)
            applyMode(link, posixMode(source))
        }
        // Directory modes last, so read-only directories don't block their own contents.
        for ((dir, mode) in dirModes) applyMode(dir, mode or 0b111_000_000)
        onProgress(count)
        return Result(files, dirs, symlinks, hardlinks.size)
    }

    /** `name` split into safe components, or null for the root itself / `..` escapes. */
    internal fun components(name: String): List<String>? {
        val parts = name.replace('\\', '/').split('/').filter { it.isNotEmpty() && it != "." }
        if (parts.isEmpty() || parts.any { it == ".." }) return null
        return parts
    }

    /**
     * Where an entry named [name] must be written: its parent directories are resolved the way
     * the guest will see them (chroot-style), so a symlink like `/lib -> /usr/lib` is followed
     * *inside* [root] instead of to the phone's real `/usr/lib`. The final component itself is
     * not followed (it's the thing being created or replaced). Never leaves [root].
     */
    internal fun resolveForWrite(root: File, name: String): File? {
        val parts = components(name) ?: return null
        val parent = resolveDirInRoot(root, parts.dropLast(1)) ?: throw IOException("Unresolvable path in archive: $name")
        return File(parent, parts.last())
    }

    private fun resolveDirInRoot(root: File, parts: List<String>, depth: Int = 0): File? {
        if (depth > 40) return null // symlink loop
        var current = root
        for ((i, part) in parts.withIndex()) {
            val next = File(current, part)
            val path = next.toPath()
            if (Files.isSymbolicLink(path)) {
                val linkTarget = Files.readSymbolicLink(path).toString()
                val rest = parts.drop(i + 1)
                val base: List<String> = if (linkTarget.startsWith("/")) emptyList() else relativeParts(root, current)
                val joined = (base + linkTarget.split('/')).fold(mutableListOf<String>()) { acc, c ->
                    when (c) {
                        "", "." -> {}
                        ".." -> if (acc.isNotEmpty()) acc.removeAt(acc.size - 1) // clamp at the guest's "/"
                        else -> acc += c
                    }
                    acc
                }
                return resolveDirInRoot(root, joined + rest, depth + 1)
            }
            current = next
        }
        return current
    }

    private fun relativeParts(root: File, dir: File): List<String> =
        dir.relativeTo(root).path.split(File.separatorChar).filter { it.isNotEmpty() }

    private fun deleteIfExists(f: File) {
        Files.deleteIfExists(f.toPath())
    }

    private fun posixMode(f: File): Int = runCatching {
        Files.getPosixFilePermissions(f.toPath(), LinkOption.NOFOLLOW_LINKS).fold(0) { acc, p -> acc or bit(p) }
    }.getOrDefault(0b110_100_100)

    private fun applyMode(f: File, mode: Int) {
        // Owner always gets read/write so the app can later update or delete the file.
        val m = (mode and 0b111_111_111) or 0b110_000_000
        val perms = PosixFilePermission.entries.filterTo(mutableSetOf()) { m and bit(it) != 0 }
        runCatching { Files.setPosixFilePermissions(f.toPath(), perms) }
    }

    private fun bit(p: PosixFilePermission): Int = when (p) {
        PosixFilePermission.OWNER_READ -> 0b100_000_000
        PosixFilePermission.OWNER_WRITE -> 0b010_000_000
        PosixFilePermission.OWNER_EXECUTE -> 0b001_000_000
        PosixFilePermission.GROUP_READ -> 0b000_100_000
        PosixFilePermission.GROUP_WRITE -> 0b000_010_000
        PosixFilePermission.GROUP_EXECUTE -> 0b000_001_000
        PosixFilePermission.OTHERS_READ -> 0b000_000_100
        PosixFilePermission.OTHERS_WRITE -> 0b000_000_010
        PosixFilePermission.OTHERS_EXECUTE -> 0b000_000_001
    }
}
