package it.presenter.app.remote

import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.JSchException
import com.jcraft.jsch.SftpException
import it.presenter.app.R
import it.presenter.app.UserFacingException
import it.presenter.app.doc.DocStamp
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.util.Vector

/** An entry of an IDE folder. */
data class FileEntry(val path: String, val name: String, val isDir: Boolean, val size: Long)

/**
 * Files of an IDE tab: a folder on the tablet or on the server (SFTP). The calls block: run them on a
 * worker thread. Paths are absolute (on the tablet or on the server).
 */
interface FileAccess {
    val root: String
    fun list(dir: String): List<FileEntry>
    fun read(path: String): String
    fun write(path: String, text: String)

    /** Creates the empty file [path]; if it already exists (file or folder) it fails, no overwrite. */
    fun create(path: String)

    /** File size in bytes (before opening it in the editor). */
    fun size(path: String): Long

    /**
     * Size and modification time of file [path], to notice changes made by others (terminal, other apps);
     * null if it does not exist. Connection errors pass through.
     */
    fun stamp(path: String): DocStamp?
    fun close() = Unit

    companion object {
        /** Files too large for the editor. */
        const val MAX_EDIT_BYTES = 2L * 1024 * 1024

        /**
         * UTF-8 text of [bytes]. A file in another encoding (Latin-1, …) is refused: read as UTF-8 the accented
         * letters would become "�" and saving would ruin the file.
         */
        fun decodeUtf8(bytes: ByteArray, path: String): String = try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: CharacterCodingException) {
            throw UserFacingException(R.string.code_not_utf8, path.substringAfterLast('/'))
        }

        fun sorted(list: List<FileEntry>) = list.sortedWith(compareBy<FileEntry> { !it.isDir }.thenBy { it.name.lowercase() })
    }
}

class LocalFiles(override val root: String) : FileAccess {
    override fun list(dir: String): List<FileEntry> =
        FileAccess.sorted(File(dir).listFiles().orEmpty().map { FileEntry(it.path, it.name, it.isDirectory, it.length()) })

    override fun read(path: String): String = FileAccess.decodeUtf8(File(path).readBytes(), path)

    override fun size(path: String): Long = File(path).length()

    override fun stamp(path: String): DocStamp? = File(path).takeIf { it.isFile }?.let { DocStamp(it.length(), it.lastModified()) }

    override fun create(path: String) {
        if (!File(path).createNewFile()) throw UserFacingException(R.string.code_file_exists, path.substringAfterLast('/'))
    }

    /**
     * Safe write: temporary file next to it (synced to disk), then renamed. In a folder where files cannot
     * be created but this one can be written, it is rewritten in place.
     */
    override fun write(path: String, text: String) {
        // A symbolic link is written where it points (renaming would replace the link with a file).
        val f = File(path).canonicalFile
        val tmp = File(f.parentFile, ".${f.name}.presenter-tmp")
        val bytes = text.toByteArray(Charsets.UTF_8)
        try {
            FileOutputStream(tmp).use { o ->
                o.write(bytes)
                o.fd.sync()
            }
        } catch (e: Exception) {
            tmp.delete()
            if (e is FileNotFoundException && !tmp.exists() && f.isFile && f.canWrite()) {
                FileOutputStream(f).use { o ->
                    o.write(bytes)
                    o.fd.sync()
                }
                return
            }
            throw e
        }
        if (!tmp.renameTo(f)) {
            f.writeText(text)
            tmp.delete()
        }
    }
}

/**
 * Files on the server through SFTP, over the tab's own connection ([SshLink]); if the channel drops it
 * is reopened at the next call (the connection reconnects by itself).
 */
class RemoteFiles(val link: SshLink, override val root: String) : FileAccess {
    @Volatile
    private var sftp: ChannelSftp? = null

    @Synchronized
    private fun channel(): ChannelSftp {
        sftp?.takeIf { it.isConnected && !it.isClosed }?.let { return it }
        val session = link.connect()
        return (session.openChannel("sftp") as ChannelSftp).also {
            it.connect(10_000)
            sftp = it
        }
    }

    /** Starting folder: the profile's, or the home. */
    fun home(): String = retry { ch -> ch.home }

    /**
     * Runs [block] on the channel, one operation at a time: an SFTP channel cannot take two threads at once
     * (remote PDFs use it from the documents thread and from the checks thread).
     */
    @Synchronized
    private fun <T> retry(block: (ChannelSftp) -> T): T = try {
        block(channel())
    } catch (e: SshLink.Failure) {
        throw e
    } catch (e: UserFacingException) {
        // Error of the file (not UTF-8, …), not of the connection: retrying is useless.
        throw e
    } catch (e: Exception) {
        // Missing file or no permission, with the channel still alive: retrying is useless.
        if (isFileError(e) && sftp?.isConnected == true) throw e
        // Channel or connection dropped: a second attempt on a new channel (the old one is closed).
        runCatching { sftp?.disconnect() }
        sftp = null
        if (!link.isConnected) link.disconnect()
        block(channel())
    }

    /** File [path] exists (file, folder or link). Connection errors pass through. */
    fun exists(path: String): Boolean = retry { ch ->
        try {
            ch.lstat(path)
            true
        } catch (e: SftpException) {
            if (e.id == ChannelSftp.SSH_FX_NO_SUCH_FILE) false else throw e
        }
    }

    override fun list(dir: String): List<FileEntry> = retry { ch ->
        @Suppress("UNCHECKED_CAST")
        val entries = ch.ls(dir) as Vector<ChannelSftp.LsEntry>
        FileAccess.sorted(
            entries.filter { it.filename != "." && it.filename != ".." }
                .map { FileEntry("${dir.trimEnd('/')}/${it.filename}", it.filename, it.attrs.isDir, it.attrs.size) },
        )
    }

    override fun size(path: String): Long = retry { ch -> ch.stat(path).size }

    /**
     * Check and write are retried separately: if the connection drops after the file was created, the
     * second attempt rewrites the empty file instead of saying it already exists.
     */
    override fun create(path: String) {
        // Only "not there" allows writing: with a connection error we do not know, and writing (overwriting)
        // could empty an existing file.
        if (exists(path)) throw UserFacingException(R.string.code_file_exists, path.substringAfterLast('/'))
        retry { ch -> ch.put(ByteArrayInputStream(ByteArray(0)), path) }
    }

    override fun read(path: String): String = retry { ch ->
        val out = ByteArrayOutputStream()
        ch.get(path, out)
        FileAccess.decodeUtf8(out.toByteArray(), path)
    }

    /** Safe write on the server: temporary file, then renamed over the original. */
    override fun write(path: String, text: String) = retry { ch -> putAtomic(ch, { text.toByteArray(Charsets.UTF_8).inputStream() }, path) }

    override fun stamp(path: String): DocStamp? = retry { ch ->
        try {
            val a = ch.stat(path)
            if (a.isDir) null else DocStamp(a.size, a.mTime * 1000L)
        } catch (e: SftpException) {
            if (e.id == ChannelSftp.SSH_FX_NO_SUCH_FILE) null else throw e
        }
    }

    /** Size and modification time (ms) of [path] on the server. */
    fun stat(path: String): Pair<Long, Long> = retry { ch ->
        val a = ch.stat(path)
        a.size to a.mTime * 1000L
    }

    /** Downloads [path] into [dest] (overwritten). */
    fun download(path: String, dest: File) = retry { ch -> dest.outputStream().use { ch.get(path, it) } }

    /** Uploads [src] in place of [path], with the same safe write as [write]. */
    fun upload(src: File, path: String) = retry { ch -> putAtomic(ch, src::inputStream, path) }

    /**
     * Temporary file next to it, then renamed over the original. A symbolic link is written where it
     * points (renaming would replace the link with a file); permissions (e.g. an executable script) stay
     * as they were. In a folder where files cannot be created (but the file can be written), it is
     * rewritten in place.
     */
    private fun putAtomic(ch: ChannelSftp, open: () -> InputStream, path: String) {
        val target = runCatching { if (ch.lstat(path).isLink) ch.realpath(path) else path }.getOrDefault(path)
        val dir = target.substringBeforeLast('/', ".")
        val tmp = "$dir/.${target.substringAfterLast('/')}.presenter-tmp"
        val permissions = runCatching { ch.stat(target).permissions and 0xFFF }.getOrNull()
        try {
            open().use { ch.put(it, tmp) }
        } catch (e: SftpException) {
            if (e.id != ChannelSftp.SSH_FX_PERMISSION_DENIED || permissions == null) throw e
            open().use { ch.put(it, target, ChannelSftp.OVERWRITE) }
            return
        }
        try {
            // JSch uses the atomic rename over the original (posix-rename) if the server offers it.
            ch.rename(tmp, target)
        } catch (e: Exception) {
            // Server without posix-rename: remove the original and retry (if it fails again, the text stays in the
            // temporary file and in the editor, which still shows it as unsaved).
            runCatching { ch.rm(target) }
            ch.rename(tmp, target)
        }
        permissions?.let { runCatching { ch.chmod(it, target) } }
    }

    companion object {
        /** Error of the file (missing, permissions), not of the connection. */
        fun isFileError(e: Throwable) = e is SftpException &&
            (e.id == ChannelSftp.SSH_FX_NO_SUCH_FILE || e.id == ChannelSftp.SSH_FX_PERMISSION_DENIED)

        /**
         * Connection error (network down, session closed), not a file error: remote PDFs then switch to the
         * tablet copy.
         */
        fun isConnectionError(e: Throwable): Boolean = when {
            e is SshLink.Failure -> true
            isFileError(e) -> false
            e is SftpException -> e.id == SSH_FX_NO_CONNECTION || e.id == SSH_FX_CONNECTION_LOST || e.cause is IOException
            e is JSchException -> true
            e is IOException && e !is UserFacingException -> true
            // With a dead session JSch sometimes fails with an internal error (NullPointerException…).
            e is RuntimeException && e.stackTrace.any { it.className.startsWith("com.jcraft.jsch.") } -> true
            else -> false
        }

        private const val SSH_FX_NO_CONNECTION = 6
        private const val SSH_FX_CONNECTION_LOST = 7
    }

    override fun close() {
        runCatching { sftp?.disconnect() }
        sftp = null
    }
}
