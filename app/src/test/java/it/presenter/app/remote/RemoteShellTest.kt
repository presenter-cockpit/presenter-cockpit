package it.presenter.app.remote

import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class RemoteShellTest {
    private fun bytes(s: String) = s.toByteArray()

    @Test
    fun markersAreFoundAlsoWhenSplitAcrossReads() {
        val m = RemoteShell.Markers()
        val all = "hello\u001b]7777;pid=42\u0007world\u001b]0;title\u0007\u001b]7777;tmux\u0007"
        val found = mutableListOf<String>()
        for (chunk in all.chunked(3)) found += m.feed(bytes(chunk), 0, chunk.length)
        assertEquals(listOf("pid=42", "tmux"), found)
    }

    @Test
    fun otherEscapeSequencesAreNotMarkers() {
        val m = RemoteShell.Markers()
        val s = "\u001b]7777\u001b]7777;x\u001b[0m\u0007\u001b]777;y\u0007"
        assertTrue(m.feed(bytes(s), 0, s.length).isEmpty())
    }

    @Test
    fun serverKeepsTheTmuxFlag() {
        val s = ServerProfile(name = "n", host = "h", user = "u", tmux = true)
        assertTrue(ServerProfile.fromJson(s.toJson())!!.tmux)
        val old = s.toJson().apply { remove("tmux") }
        assertFalse(ServerProfile.fromJson(old)!!.tmux)
    }

    @Test
    fun tmuxSessionNameIsSafe() {
        assertEquals("presenter-ab12cd34ef56", RemoteShell.tmuxSession("ab12cd34-ef56-7890-aaaa-bbbbbbbbbbbb"))
    }

    private fun hasCommand(name: String) = System.getenv("PATH").orEmpty().split(':').any { File(it, name).canExecute() }

    /** The start line goes through a login shell like the server's (sh here). */
    private fun runLauncher(cmd: String, env: Map<String, String> = emptyMap(), stdin: String = ""): String {
        val pb = ProcessBuilder("sh", "-c", cmd).redirectErrorStream(true)
        pb.environment().putAll(env)
        val p = pb.start()
        p.outputStream.use { it.write(stdin.toByteArray()) }
        assertTrue(p.waitFor(10, TimeUnit.SECONDS))
        return p.inputStream.readBytes().decodeToString()
    }

    @Test
    fun plainShellStartsInTheFolderAndTellsItsPid() {
        assumeTrue(hasCommand("sh"))
        val dir = Files.createTempDirectory("rs it's").toFile()
        try {
            val out = runLauncher(RemoteShell.launcher(null, dir.path), mapOf("SHELL" to "/bin/sh"), "pwd\n")
            val m = RemoteShell.Markers()
            val markers = m.feed(out.toByteArray(), 0, out.toByteArray().size)
            val pid = markers.single().removePrefix("pid=").toInt()
            assertTrue(pid > 0)
            assertTrue(out, out.contains(dir.canonicalPath))
        } finally {
            dir.deleteRecursively()
        }
    }

    /** As sshd does: the line goes through the user's login shell (zsh here, if present). */
    @Test
    fun launcherWorksFromZsh() {
        assumeTrue(hasCommand("zsh"))
        val dir = Files.createTempDirectory("rs").toFile()
        try {
            val pb = ProcessBuilder("zsh", "-c", RemoteShell.launcher(null, dir.path)).redirectErrorStream(true)
            pb.environment()["SHELL"] = "/usr/bin/zsh"
            val p = pb.start()
            p.outputStream.use { it.write("pwd\n".toByteArray()) }
            assertTrue(p.waitFor(10, TimeUnit.SECONDS))
            val out = p.inputStream.readBytes()
            assertTrue(String(out), RemoteShell.Markers().feed(out, 0, out.size).single().startsWith("pid="))
            assertTrue(String(out), String(out).contains(dir.canonicalPath))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun cwdQueryReadsTheFolderOfAProcess() {
        assumeTrue(hasCommand("sh") && File("/proc/self").exists())
        val dir = Files.createTempDirectory("rs").toFile()
        // The PID is read from the process itself (exec: it stays the same).
        val p = ProcessBuilder("sh", "-c", "echo \$\$; exec sleep 5").directory(dir).start()
        try {
            val pid = p.inputStream.bufferedReader().readLine().trim().toInt()
            val out = runLauncher(RemoteShell.cwdQuery(pid))
            assertEquals(dir.canonicalPath, out.trim())
        } finally {
            p.destroy()
            dir.deleteRecursively()
        }
    }

    /**
     * Real tmux (if present, with a tmux server of its own): the session is created, resumed, and once detached
     * beyond the maximum time it closes by itself.
     */
    @Test
    fun tmuxSessionIsResumedAndExpires() {
        assumeTrue(hasCommand("tmux") && hasCommand("script"))
        val tmp = Files.createTempDirectory("tmuxtest").toFile()
        val env = mapOf("TMUX_TMPDIR" to tmp.path, "TERM" to "xterm-256color", "LANG" to "C", "LC_ALL" to "C")
        fun tmux(vararg args: String): String {
            val pb = ProcessBuilder(listOf("tmux") + args).redirectErrorStream(true)
            pb.environment().putAll(env)
            pb.environment().remove("TMUX")
            val p = pb.start()
            p.waitFor(5, TimeUnit.SECONDS)
            return p.inputStream.readBytes().decodeToString().trim()
        }
        fun attach(): Process {
            val cmd = RemoteShell.launcher("presenter-test", tmp.path, idle = 2)
            // The input (Java's pipe) stays open, like the app's terminal.
            val pb = ProcessBuilder("script", "-qfc", cmd, "/dev/null")
            pb.environment().putAll(env)
            pb.environment().remove("TMUX")
            return pb.start().also { Thread.sleep(1500) }
        }
        try {
            val first = attach()
            assertEquals("1 1", tmux("display", "-p", "-t", "=presenter-test:", "#{session_attached} #{mouse}"))
            // UTF-8 even without LANG in the environment.
            assertEquals("1", tmux("list-clients", "-F", "#{client_utf8}"))
            // Connection dropped: the tmux client dies.
            first.destroy()
            Thread.sleep(500)
            assertEquals("0", tmux("display", "-p", "-t", "=presenter-test:", "#{session_attached}"))
            // Resumed within the time: same session; the first wait does not close it.
            val second = attach()
            Thread.sleep(1500)
            assertEquals("1", tmux("display", "-p", "-t", "=presenter-test:", "#{session_attached}"))
            second.destroy()
            Thread.sleep(3500)
            assertTrue(tmux("has-session", "-t", "=presenter-test").isNotEmpty())
        } finally {
            tmux("kill-server")
            tmp.deleteRecursively()
        }
    }

    @Test
    fun terminalHereReusesOnlyTheExactFolder() {
        // Most recent first: the first in the right folder wins; the trailing slash does not count.
        assertEquals(1, RemoteShell.pickTerminal("/home/u/prj/", listOf("/home/u/prj/src", "/home/u/prj", "/home/u/prj")))
        // A subfolder or a folder above is not enough (user's choice); unknown folder: no.
        assertEquals(-1, RemoteShell.pickTerminal("/home/u/prj", listOf("/home/u/prj/src", "/home/u", null)))
        assertEquals(-1, RemoteShell.pickTerminal("/home/u/prj", emptyList()))
        assertEquals("/", RemoteShell.normalizeDir("/"))
        assertEquals("/a", RemoteShell.normalizeDir("/a//"))
    }

    @Test
    fun resolveDirFollowsSymlinks() {
        assumeTrue(hasCommand("sh"))
        val tmp = Files.createTempDirectory("resolve").toFile().canonicalFile
        try {
            val real = File(tmp, "real folder's").apply { mkdirs() }
            val link = File(tmp, "link")
            Files.createSymbolicLink(link.toPath(), real.toPath())
            assertEquals(real.path, runLauncher(RemoteShell.resolveDirQuery(link.path)).trim())
            // A folder that does not exist: no output (the tree path is used as it is).
            assertEquals("", runLauncher(RemoteShell.resolveDirQuery(File(tmp, "nope").path)).trim())
        } finally {
            tmp.deleteRecursively()
        }
    }

    @Test
    fun tmuxDirQueryReadsTheActivePane() {
        assumeTrue(hasCommand("tmux"))
        val tmp = Files.createTempDirectory("tmuxdir").toFile().canonicalFile
        val env = mapOf("TMUX_TMPDIR" to tmp.path, "TMUX" to "")
        try {
            val dir = File(tmp, "project").apply { mkdirs() }
            runLauncher("tmux new-session -d -s presenter-dirtest -c '${dir.path}' 'sleep 30'", env)
            assertEquals(dir.path, runLauncher(RemoteShell.tmuxDirQuery("presenter-dirtest"), env).trim())
            // A session that does not exist: no output.
            assertEquals("", runLauncher(RemoteShell.tmuxDirQuery("presenter-missing"), env).trim())
        } finally {
            runLauncher("tmux kill-server", env)
            tmp.deleteRecursively()
        }
    }

    /**
     * The separate commands (folder, closing the session) go through the user's shell: with zsh `=word`
     * expands like a command, so the tmux target must be quoted.
     */
    @Test
    fun tmuxCommandsWorkFromZsh() {
        assumeTrue(hasCommand("tmux") && hasCommand("zsh"))
        val tmp = Files.createTempDirectory("tmuxzsh").toFile().canonicalFile
        val env = mapOf("TMUX_TMPDIR" to tmp.path, "TMUX" to "")
        fun zsh(cmd: String): String {
            val pb = ProcessBuilder("zsh", "-c", cmd).redirectErrorStream(true)
            pb.environment().putAll(env)
            val p = pb.start()
            assertTrue(p.waitFor(10, TimeUnit.SECONDS))
            return p.inputStream.readBytes().decodeToString().trim()
        }
        try {
            val dir = File(tmp, "project").apply { mkdirs() }
            runLauncher("tmux new-session -d -s presenter-zsh -c '${dir.path}' 'sleep 30'", env)
            assertEquals(dir.path, zsh(RemoteShell.tmuxDirQuery("presenter-zsh")))
            zsh(RemoteShell.killTmux("presenter-zsh"))
            assertEquals("", zsh(RemoteShell.tmuxDirQuery("presenter-zsh")))
        } finally {
            runLauncher("tmux kill-server", env)
            tmp.deleteRecursively()
        }
    }
}
