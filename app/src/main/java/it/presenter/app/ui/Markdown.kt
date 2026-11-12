package it.presenter.app.ui

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.em

/**
 * Essential Markdown for the notes (PDFPC writes them in markdown): `#` headings, `-`/`*`/`1.` lists, `>`
 * quotes, ``` code blocks, and inline **bold**, *italic*, `code`, ~~strikethrough~~; HTML entities (`&lt;`,
 * `&#8594;`) become their character, as in PDFPC, except in code. With [enabled] = false the text stays as it is.
 */
object Markdown {
    fun render(text: String, enabled: Boolean): AnnotatedString {
        if (!enabled) return AnnotatedString(text.trim())
        return buildAnnotatedString {
            var inCode = false
            var first = true
            for (raw in text.trim().lines()) {
                val line = raw.trimEnd()
                if (line.trimStart().startsWith("```")) {
                    inCode = !inCode
                    continue
                }
                if (!first) append('\n')
                first = false
                if (inCode) {
                    pushStyle(SpanStyle(fontFamily = FontFamily.Monospace))
                    append(line)
                    pop()
                    continue
                }
                val trimmed = line.trimStart()
                val indent = (line.length - trimmed.length) / 2
                val heading = HEADING.find(trimmed)
                val bullet = BULLET.find(trimmed)
                val numbered = NUMBERED.find(trimmed)
                when {
                    heading != null -> {
                        val level = heading.groupValues[1].length
                        val size = when (level) { 1 -> 1.35f; 2 -> 1.2f; else -> 1.08f }
                        pushStyle(SpanStyle(fontWeight = FontWeight.Bold, fontSize = size.em))
                        inline(heading.groupValues[2])
                        pop()
                    }
                    bullet != null -> {
                        append("    ".repeat(indent))
                        append("•  ")
                        inline(bullet.groupValues[1])
                    }
                    numbered != null -> {
                        append("    ".repeat(indent))
                        append("${numbered.groupValues[1]}.  ")
                        inline(numbered.groupValues[2])
                    }
                    trimmed.startsWith(">") -> {
                        pushStyle(SpanStyle(fontStyle = FontStyle.Italic))
                        append("▎ ")
                        inline(trimmed.removePrefix(">").trimStart())
                        pop()
                    }
                    else -> inline(trimmed)
                }
            }
        }
    }

    private val ENTITY = Regex("&(#[0-9]{1,7}|#[xX][0-9a-fA-F]{1,6}|[A-Za-z][A-Za-z0-9]{1,31});")

    /** HTML entities most used in notes; an unknown one stays as written (as in Markdown). */
    private val NAMED = mapOf(
        "lt" to "<", "gt" to ">", "amp" to "&", "quot" to "\"", "apos" to "'", "nbsp" to "\u00A0",
        "ndash" to "–", "mdash" to "—", "hellip" to "…", "laquo" to "«", "raquo" to "»",
        "lsquo" to "‘", "rsquo" to "’", "ldquo" to "“", "rdquo" to "”", "bull" to "•", "middot" to "·",
        "copy" to "©", "reg" to "®", "trade" to "™", "deg" to "°", "euro" to "€", "times" to "×", "divide" to "÷",
        "plusmn" to "±", "le" to "≤", "ge" to "≥", "ne" to "≠", "asymp" to "≈", "infin" to "∞",
        "larr" to "←", "rarr" to "→", "uarr" to "↑", "darr" to "↓", "harr" to "↔", "lArr" to "⇐", "rArr" to "⇒", "hArr" to "⇔",
    )

    /** Text of the entity without "&" and ";" (`lt`, `#60`, `#x3C`), or null. */
    private fun decode(name: String): String? {
        if (!name.startsWith("#")) return NAMED[name]
        val code = if (name[1] == 'x' || name[1] == 'X') name.substring(2).toIntOrNull(16) else name.substring(1).toIntOrNull()
        // Like HTML: 0 and surrogates become the replacement character, beyond Unicode nothing.
        return when {
            code == null || code > 0x10FFFF -> null
            code == 0 || code in 0xD800..0xDFFF -> "\uFFFD"
            else -> String(Character.toChars(code))
        }
    }

    private val HEADING = Regex("^(#{1,6})\\s+(.*)")
    private val BULLET = Regex("^[-*+]\\s+(.*)")
    private val NUMBERED = Regex("^(\\d+)[.)]\\s+(.*)")

    private fun AnnotatedString.Builder.inline(s: String) {
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                append(s[i + 1])
                i += 2
                continue
            }
            if (c == '&') {
                val entity = ENTITY.matchAt(s, i)
                val decoded = entity?.let { decode(it.groupValues[1]) }
                if (entity != null && decoded != null) {
                    append(decoded)
                    i += entity.value.length
                    continue
                }
            }
            val marker = when {
                s.startsWith("**", i) -> "**"
                s.startsWith("__", i) -> "__"
                s.startsWith("~~", i) -> "~~"
                c == '*' || c == '_' || c == '`' -> c.toString()
                else -> null
            }
            if (marker != null) {
                val end = s.indexOf(marker, i + marker.length)
                // A "_" inside a word (file_name) is not italic.
                val wordUnderscore = marker == "_" && i > 0 && s[i - 1].isLetterOrDigit()
                if (end > i + marker.length && !wordUnderscore) {
                    val inner = s.substring(i + marker.length, end)
                    val style = when (marker) {
                        "**", "__" -> SpanStyle(fontWeight = FontWeight.Bold)
                        "~~" -> SpanStyle(textDecoration = TextDecoration.LineThrough)
                        "`" -> SpanStyle(fontFamily = FontFamily.Monospace, fontSize = 0.95.em)
                        else -> SpanStyle(fontStyle = FontStyle.Italic)
                    }
                    pushStyle(style)
                    if (marker == "`") append(inner) else inline(inner)
                    pop()
                    i = end + marker.length
                    continue
                }
            }
            append(c)
            i++
        }
    }
}
