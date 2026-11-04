package com.llamaaistudio.app.linux

import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.archivers.tar.TarConstants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.zip.GZIPOutputStream

class LinuxPiecesTest {
    @get:Rule val tmp = TemporaryFolder()

    private sealed interface E {
        val name: String
        data class Dir(override val name: String, val mode: Int = 0b111_101_101) : E
        data class FileE(override val name: String, val body: String, val mode: Int = 0b110_100_100) : E
        data class Sym(override val name: String, val target: String) : E
        data class Hard(override val name: String, val target: String) : E
    }

    private fun tarGz(vararg entries: E): ByteArray {
        val bytes = ByteArrayOutputStream()
        TarArchiveOutputStream(GZIPOutputStream(bytes)).use { tar ->
            tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
            for (e in entries) {
                val entry = when (e) {
                    is E.Dir -> TarArchiveEntry(e.name.trimEnd('/') + "/").apply { mode = TarArchiveEntry.DEFAULT_DIR_MODE.and(0b111_000_000_000.inv()) or e.mode }
                    is E.FileE -> TarArchiveEntry(e.name).apply { size = e.body.toByteArray().size.toLong(); mode = 0b1_000_000_000_000_000 or e.mode }
                    is E.Sym -> TarArchiveEntry(e.name, TarConstants.LF_SYMLINK).apply { linkName = e.target }
                    is E.Hard -> TarArchiveEntry(e.name, TarConstants.LF_LINK).apply { linkName = e.target }
                }
                tar.putArchiveEntry(entry)
                if (e is E.FileE) tar.write(e.body.toByteArray())
                tar.closeArchiveEntry()
            }
        }
        return bytes.toByteArray()
    }

    private fun extract(vararg entries: E): File {
        val root = tmp.newFolder()
        RootfsExtractor.extractTarGz(ByteArrayInputStream(tarGz(*entries)), root)
        return root
    }

    @Test
    fun extractsFilesDirsSymlinksAndKeepsExecBits() {
        val root = extract(
            E.Dir("./"),
            E.Dir("bin"),
            E.FileE("bin/busybox", "#!fake", 0b111_101_101),
            E.Sym("bin/sh", "/bin/busybox"),
            E.Sym("bin/ls", "busybox"),
            E.FileE("etc/motd", "hi"),
        )
        val busybox = File(root, "bin/busybox")
        assertTrue(busybox.canExecute())
        assertFalse(File(root, "etc/motd").canExecute())
        assertEquals("/bin/busybox", Files.readSymbolicLink(File(root, "bin/sh").toPath()).toString())
        assertEquals("busybox", Files.readSymbolicLink(File(root, "bin/ls").toPath()).toString())
        assertEquals("hi", File(root, "etc/motd").readText())
    }

    @Test
    fun absoluteDirectorySymlinksResolveInsideTheRoot() {
        // usr-merged layout: /lib -> /usr/lib (absolute). Writing lib/libc.so must land in
        // <root>/usr/lib, never in the host's real /usr/lib.
        val root = extract(
            E.Dir("usr/lib"),
            E.Sym("lib", "/usr/lib"),
            E.FileE("lib/libc.so", "elf"),
            E.Sym("usr/lib64", "../lib"), // relative, climbing to the guest root and back
            E.FileE("usr/lib64/ld.so", "ld"),
        )
        assertEquals("elf", File(root, "usr/lib/libc.so").readText())
        assertEquals("ld", File(root, "lib/ld.so").let { File(root, "usr/lib/ld.so") }.readText())
    }

    @Test
    fun symlinksCannotEscapeViaDotDot() {
        val root = extract(
            E.Sym("evil", "../../../../../../outside"),
            E.FileE("evil/payload", "x"),
        )
        // "../" beyond the guest root clamps at the root, like a real chroot.
        assertTrue(File(root, "outside/payload").isFile)
        assertFalse(File(root.parentFile, "outside").exists())
    }

    @Test
    fun skipsTraversalEntryNames() {
        val root = extract(E.FileE("../escape.txt", "x"), E.FileE("ok.txt", "y"))
        assertFalse(File(root.parentFile, "escape.txt").exists())
        assertTrue(File(root, "ok.txt").isFile)
        assertNull(RootfsExtractor.components("a/../../b"))
    }

    @Test
    fun hardLinksBecomeCopies() {
        val root = extract(E.FileE("usr/bin/a", "same", 0b111_101_101), E.Hard("usr/bin/b", "usr/bin/a"))
        val b = File(root, "usr/bin/b")
        assertEquals("same", b.readText())
        assertTrue(b.canExecute())
        assertFalse(Files.isSymbolicLink(b.toPath()))
    }

    @Test(expected = IOException::class)
    fun refusesSymlinkLoops() {
        extract(E.Sym("a", "b"), E.Sym("b", "a"), E.FileE("a/x", "loop"))
    }

    @Test
    fun terminalBufferStripsAnsiAndHandlesCarriageReturns() {
        val t = TerminalBuffer(maxLines = 3)
        t.append("\u001B[1;32mgreen\u001B[0m text\n")
        t.append("progress 10%\rprogress 100%\n")
        t.append("a\nb\nc\n")
        assertEquals(listOf("a", "b", "c"), t.snapshot())
        val u = TerminalBuffer()
        u.append("\u001B[1;32mgreen\u001B[0m text\nprogress 10%\rprogress 100%\npartial")
        assertEquals(listOf("green text", "progress 100%", "partial"), u.snapshot())
        u.append("\r\n") // CRLF is just a newline
        assertEquals("partial", u.snapshot().last())
    }

    @Test
    fun prootArgvRunsShellInsideRootfsWithCleanEnv() {
        val argv = ProotCommand.argv(
            File("/n/libproot.so"), File("/d/alpine"), "node -v",
            binds = listOf(ProotCommand.Bind("/d/web", "/projects/web")),
        )
        assertEquals("/n/libproot.so", argv.first())
        assertTrue(argv.containsAll(listOf("--kill-on-exit", "--link2symlink", "-0")))
        assertEquals("/d/alpine", argv[argv.indexOf("-r") + 1])
        assertTrue("/d/web:/projects/web" in argv)
        assertEquals(listOf("/bin/sh", "-l", "-c", "node -v"), argv.takeLast(4))
        assertTrue("PATH=${ProotCommand.GUEST_PATH}" in argv)
        val env = ProotCommand.hostEnv(File("/n/libproot-loader.so"), File("/d/tmp"))
        assertEquals("/n/libproot-loader.so", env["PROOT_LOADER"])
    }
}
