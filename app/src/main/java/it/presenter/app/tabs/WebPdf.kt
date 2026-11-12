package it.presenter.app.tabs

import android.content.Context
import android.webkit.CookieManager
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.security.MessageDigest

/**
 * PDFs reached from a web page tab. The WebView cannot show them, so they are downloaded into
 * `files/web-pdfs/` and opened as PDF tabs. The download sends the page's cookies, so sites that need a
 * login work too. A PDF downloaded before opens again as it is, with the annotations saved on it.
 */
object WebPdf {
    /** The link is a PDF: by its type, or by the name in the address or in the download header. */
    fun isPdf(url: String, contentDisposition: String?, mimeType: String?): Boolean =
        mimeType.equals("application/pdf", ignoreCase = true) || nameOf(url, contentDisposition).endsWith(".pdf", ignoreCase = true)

    /** File name from the download header (`filename="…"`), otherwise the last part of the address. */
    private fun nameOf(url: String, contentDisposition: String?): String =
        contentDisposition?.let { FILENAME.find(it)?.groupValues?.get(1)?.trim() }?.takeIf { it.isNotEmpty() }
            ?: url.substringBefore('#').substringBefore('?').substringAfterLast('/').let { runCatching { URLDecoder.decode(it, "UTF-8") }.getOrDefault(it) }

    /** Where the PDF of [url] is kept: one folder per address, so two files with the same name do not clash. */
    fun fileFor(context: Context, url: String, contentDisposition: String?, mimeType: String?): File {
        val guessed = nameOf(url, contentDisposition).replace('/', '_').replace('\\', '_').ifEmpty { "document" }
        val name = if (guessed.endsWith(".pdf", ignoreCase = true)) guessed else "$guessed.pdf"
        val hash = MessageDigest.getInstance("SHA-1").digest(url.toByteArray()).joinToString("") { "%02x".format(it) }
        return File(File(context.filesDir, "web-pdfs"), hash.take(12) + "/" + name)
    }

    /** Downloads [url] into [dest] (call from a worker thread). Half a file never takes the place of [dest]. */
    fun download(url: String, userAgent: String, dest: File) {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = TIMEOUT_MS
            conn.readTimeout = TIMEOUT_MS
            conn.setRequestProperty("User-Agent", userAgent)
            CookieManager.getInstance().getCookie(url)?.let { conn.setRequestProperty("Cookie", it) }
            if (conn.responseCode !in 200..299) throw IOException("HTTP ${conn.responseCode}")
            dest.parentFile?.mkdirs()
            val part = File(dest.parentFile, dest.name + ".part")
            try {
                conn.inputStream.use { input -> part.outputStream().use { input.copyTo(it) } }
                if (!part.renameTo(dest)) throw IOException("rename ${dest.name}")
            } finally {
                part.delete()
            }
        } finally {
            conn.disconnect()
        }
    }

    private const val TIMEOUT_MS = 30_000

    private val FILENAME = Regex("filename\\*?=(?:UTF-8'')?\"?([^\";]+)", RegexOption.IGNORE_CASE)
}
