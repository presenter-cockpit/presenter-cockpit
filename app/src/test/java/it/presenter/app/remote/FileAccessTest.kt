package it.presenter.app.remote

import it.presenter.app.UserFacingException
import java.io.File
import java.net.SocketException
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class FileAccessTest {
    @Test
    fun utf8TextIsDecoded() {
        assertEquals("naïve café, déjà vu", FileAccess.decodeUtf8("naïve café, déjà vu".toByteArray(Charsets.UTF_8), "/a/b.txt"))
    }

    @Test(expected = UserFacingException::class)
    fun latin1TextIsRefused() {
        // In Latin-1 "è" is the single byte 0xE8: invalid as UTF-8, and saving it would ruin the file.
        FileAccess.decodeUtf8("naïve café, déjà vu".toByteArray(Charsets.ISO_8859_1), "/a/b.txt")
    }

    @Test
    fun newFileIsCreatedOnlyOnce() {
        val dir = Files.createTempDirectory("create").toFile()
        try {
            val files = LocalFiles(dir.path)
            val path = File(dir, "new.py").path
            files.create(path)
            assertEquals(0L, File(path).length())
            File(path).writeText("x")
            // Name already in use: error, and the file stays as it was.
            assertThrows(UserFacingException::class.java) { files.create(path) }
            assertEquals("x", File(path).readText())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun writableFileInAReadOnlyFolderIsRewrittenInPlace() {
        val dir = Files.createTempDirectory("readonly").toFile()
        try {
            val file = File(dir, "a.py").apply { writeText("old") }
            dir.setWritable(false)
            LocalFiles(dir.path).write(file.path, "new")
            assertEquals("new", file.readText())
            assertEquals(listOf("a.py"), dir.list()!!.toList())
        } finally {
            dir.setWritable(true)
            dir.deleteRecursively()
        }
    }

    @Test
    fun stampFollowsTheFile() {
        val dir = Files.createTempDirectory("stamp").toFile()
        try {
            val files = LocalFiles(dir.path)
            val file = File(dir, "a.py").apply { writeText("x") }
            val first = files.stamp(file.path)!!
            file.writeText("xy")
            file.setLastModified(first.modified + 5_000)
            assertEquals(2L, files.stamp(file.path)!!.size)
            assertNotEquals(first, files.stamp(file.path))
            assertEquals(null, files.stamp(File(dir, "missing").path))
            assertEquals(null, files.stamp(dir.path))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun startDirOnTheServer() {
        fun dir(d: String) = ServerProfile(name = "s", host = "h", user = "u", dir = d).startDir("/home/u")
        assertEquals("/home/u", dir(""))
        assertEquals("/home/u", dir("~"))
        assertEquals("/home/u/talks", dir("~/talks"))
        assertEquals("/home/u/talks", dir("talks"))
        assertEquals("/srv/www", dir("/srv/www"))
        assertEquals("/srv/www", dir(" /srv/www "))
    }

    @Test
    fun fileErrorsAreNotConnectionErrors() {
        val missing = com.jcraft.jsch.SftpException(com.jcraft.jsch.ChannelSftp.SSH_FX_NO_SUCH_FILE, "no such file")
        val denied = com.jcraft.jsch.SftpException(com.jcraft.jsch.ChannelSftp.SSH_FX_PERMISSION_DENIED, "denied")
        // A missing file must not switch the PDF to the tablet copy (nor empty a file).
        assertEquals(false, RemoteFiles.isConnectionError(missing))
        assertEquals(false, RemoteFiles.isConnectionError(denied))
        assertEquals(false, RemoteFiles.isConnectionError(UserFacingException(0)))
        assertEquals(true, RemoteFiles.isConnectionError(com.jcraft.jsch.SftpException(7, "connection lost")))
        assertEquals(true, RemoteFiles.isConnectionError(com.jcraft.jsch.JSchException("session is down")))
        assertEquals(true, RemoteFiles.isConnectionError(SocketException("reset")))
        assertEquals(true, RemoteFiles.isConnectionError(SshLink.Failure.Network(null)))
    }
}
