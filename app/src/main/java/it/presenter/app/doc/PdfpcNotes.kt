package it.presenter.app.doc

import org.json.JSONArray
import org.json.JSONObject

/**
 * Presenter notes in PDFPC's format (file `<name>.pdfpc` next to the PDF).
 *
 * Two formats, as in PDFPC's `src/classes/metadata/pdf.vala`:
 * - **JSON** (current): `{"pdfpcFormat": 2, "disableMarkdown": false, "pages": [{"idx": 0, "label": "1",
 *   "overlay": 0, "note": "…"}, …]}`. A note belongs to the PDF page found by label + overlay (if the
 *   label matches), otherwise by index `idx`;
 * - **legacy**: sections `[notes]`, `[skip]`, …; in `[notes]` blocks `### N` (N = user slide, from 1)
 *   with `\[`, `\]` and `\#` as escapes. The note applies to all pages of that slide.
 *
 * As in PDFPC, a note belongs to the slide: PDFPC writes it on the first overlay (`"overlay": 0`) and shows
 * it on all of them ([noteFor] with the slide's pages). The slide comes from [Slides], not from the label
 * alone: Beamer also gives section start pages the same label as the slide before.
 *
 * When the PDF pages change order ([remapped]) the file is always rewritten as JSON ([toJson], user's
 * choice), keeping the other fields of the file ([meta]: duration, end time…) and of the pages ([extras]:
 * `hidden`, `forcedOverlay`).
 */
class PdfpcNotes private constructor(
    private val notes: Map<Int, String>,
    val markdown: Boolean,
    private val meta: JSONObject,
    private val extras: Map<Int, JSONObject>,
) {
    val isEmpty get() = notes.isEmpty()

    /** Pages marked `hidden` (slides PDFPC skips when presenting). */
    val hiddenPages: Set<Int> get() = extras.filterValues { it.optBoolean("hidden", false) }.keys

    /** Pages with a bookmark (`bookmark`, a field of Presenter that PDFPC keeps as it is). */
    val bookmarkPages: Set<Int> get() = extras.filterValues { it.optBoolean(BOOKMARK, false) }.keys

    /**
     * The same notes with the slide made of [pages] hidden (marked on its first page, as PDFPC does) or shown
     * (the mark taken off all its pages).
     */
    fun withHidden(pages: IntRange, hidden: Boolean): PdfpcNotes = withFlag(pages, "hidden", hidden)

    /** The same notes with a bookmark on the slide made of [pages], or without. */
    fun withBookmark(pages: IntRange, on: Boolean): PdfpcNotes = withFlag(pages, BOOKMARK, on)

    /** Field [key] set on the first of [pages] ([on]), or taken off all of them. */
    private fun withFlag(pages: IntRange, key: String, on: Boolean): PdfpcNotes {
        val out = HashMap(extras)
        for (p in pages) {
            val o = out[p]?.let { JSONObject(it.toString()) } ?: continue
            o.remove(key)
            if (o.length() == 0) out.remove(p) else out[p] = o
        }
        if (on) out[pages.first] = (out[pages.first]?.let { JSONObject(it.toString()) } ?: JSONObject()).put(key, true)
        return PdfpcNotes(notes, markdown, meta, out)
    }

    /** The same notes with [text] as the note of [page]; a blank text removes it. */
    fun withNote(page: Int, text: String): PdfpcNotes {
        val out = HashMap(notes)
        if (text.isBlank()) out.remove(page) else out[page] = text.trim()
        return PdfpcNotes(out, markdown, meta, extras)
    }

    fun noteFor(page: Int): String? = notes[page]

    /** Note shown on [page] of the slide made of [slide]: its own, otherwise the first one of the slide. */
    fun noteFor(page: Int, slide: IntRange): String? = notes[page] ?: slide.firstNotNullOfOrNull { notes[it] }

    /**
     * The same notes on the pages in the new order [slots] of a document of [pageCount] pages. The file's
     * fields that point to a slide ([SLIDE_FIELDS]) are dropped: after the reorder they would point to
     * another slide, and without them PDFPC uses its defaults.
     */
    fun remapped(slots: List<PageSlot>, pageCount: Int) = PdfpcNotes(
        PageEdits.remap(notes, slots, pageCount),
        markdown,
        JSONObject(meta.toString()).apply { SLIDE_FIELDS.forEach { remove(it) } },
        PageEdits.remap(extras, slots, pageCount),
    )

    /**
     * The file as JSON for a PDF of [pageCount] pages with labels [labels] (null: the page number, as PDFPC
     * shows it). One entry for each page with a note or other fields.
     */
    fun toJson(pageCount: Int, labels: List<String>?): String {
        val root = JSONObject(meta.toString())
        root.put("pdfpcFormat", FORMAT)
        root.put("disableMarkdown", !markdown)
        val pages = JSONArray()
        for (page in (notes.keys + extras.keys).filter { it in 0 until pageCount }.sorted()) {
            val o = extras[page]?.let { JSONObject(it.toString()) } ?: JSONObject()
            val label = labels?.getOrNull(page) ?: (page + 1).toString()
            o.put("idx", page).put("label", label).put("overlay", labels?.let { overlayOf(page, it) } ?: 0)
            notes[page]?.let { o.put("note", it) }
            pages.put(o)
        }
        root.put("pages", pages)
        return root.toString()
    }

    companion object {
        private const val FORMAT = 2

        /** Page field of a bookmark. */
        private const val BOOKMARK = "bookmark"

        /** No .pdfpc yet: created when there is something to write (a hidden slide). */
        fun empty() = PdfpcNotes(emptyMap(), markdown = true, JSONObject(), emptyMap())

        /** File fields holding a slide number (end of the presentation, last viewed). */
        private val SLIDE_FIELDS = listOf("endSlide", "savedSlide")

        /** Page fields that Presenter recomputes: the others are kept. */
        private val PAGE_KEYS = setOf("idx", "label", "overlay", "note")

        /**
         * [labels]: page labels of the PDF (may be missing: then only the index counts). Returns null if the file
         * contains no notes.
         */
        fun parse(text: String, pageCount: Int, labels: List<String>?): PdfpcNotes? =
            read(text, pageCount, labels)?.takeUnless { it.isEmpty }

        /** Like [parse], but also without notes (to rewrite the file); null if it is not a PDFPC file. */
        fun read(text: String, pageCount: Int, labels: List<String>?): PdfpcNotes? {
            val trimmed = text.trimStart()
            return if (trimmed.startsWith("{")) parseJson(trimmed, pageCount, labels) else parseLegacy(text, pageCount, labels)
        }

        private fun parseJson(text: String, pageCount: Int, labels: List<String>?): PdfpcNotes? {
            val root = runCatching { JSONObject(text) }.getOrNull() ?: return null
            val pages = root.optJSONArray("pages") ?: JSONArray()
            val notes = HashMap<Int, String>()
            val extras = HashMap<Int, JSONObject>()
            for (k in 0 until pages.length()) {
                val p = pages.optJSONObject(k) ?: continue
                val page = resolvePage(p.optInt("idx", -1), p.optString("label", ""), p.optInt("overlay", 0), labels)
                if (page !in 0 until pageCount) continue
                p.optString("note", "").takeIf { it.isNotEmpty() }?.let { notes[page] = it }
                val other = JSONObject()
                for (key in p.keys()) if (key !in PAGE_KEYS) other.put(key, p.get(key))
                if (other.length() > 0) extras[page] = other
            }
            val meta = JSONObject()
            for (key in root.keys()) if (key != "pages" && key != "pdfpcFormat" && key != "disableMarkdown") meta.put(key, root.get(key))
            return PdfpcNotes(notes, markdown = !root.optBoolean("disableMarkdown", false), meta, extras)
        }

        /** Same logic as `parse_pdfpc_page` in PDFPC. */
        private fun resolvePage(idx: Int, label: String, overlay: Int, labels: List<String>?): Int {
            if (label.isEmpty() || labels == null) return idx
            if (labels.getOrNull(idx) == label && overlayOf(idx, labels) == overlay) return idx
            val first = labels.indexOf(label)
            if (first < 0) return idx
            val candidate = first + overlay
            return if (labels.getOrNull(candidate) == label) candidate else idx
        }

        private fun overlayOf(page: Int, labels: List<String>): Int {
            if (page !in labels.indices) return 0
            var i = page
            while (i > 0 && labels[i - 1] == labels[page]) i--
            return page - i
        }

        /** Sections of the legacy format with their field in PDFPC's JSON (numbers where PDFPC wants them). */
        private val LEGACY_FIELDS = mapOf(
            "duration" to ("duration" to true),
            "start_time" to ("startTime" to false),
            "end_time" to ("endTime" to false),
            "last_minutes" to ("lastMinutes" to true),
            "font_size" to ("noteFontSize" to true),
            "default_transition" to ("defaultTransition" to false),
        )

        private fun parseLegacy(text: String, pageCount: Int, labels: List<String>?): PdfpcNotes? {
            val sections = HashMap<String, String>()
            val header = Regex("""\[([A-Za-z_]+)]""")
            val matches = header.findAll(text).toList()
            for ((n, m) in matches.withIndex()) {
                val end = if (n + 1 < matches.size) matches[n + 1].range.first else text.length
                sections[m.groupValues[1]] = text.substring(m.range.last + 1, end).trim()
            }
            if (sections.isEmpty()) return null
            val notesText = sections["notes"].orEmpty()

            // "Overlay" pages (merged with the previous one): listed in [skip] (from 1) or with a repeated label.
            val skip = sections["skip"].orEmpty().split(',').mapNotNull { it.trim().toIntOrNull()?.minus(1) }.toSet()
            val userSlide = IntArray(pageCount)
            var current = -1
            for (i in 0 until pageCount) {
                val overlay = i > 0 && (i in skip || (labels != null && labels.getOrNull(i) == labels.getOrNull(i - 1)))
                if (!overlay) current++
                userSlide[i] = current
            }

            val unescape = Regex("""\\([\[\]#])""")
            val notes = HashMap<Int, String>()
            for (block in notesText.split("### ")) {
                if (block.isBlank()) continue
                val nl = block.indexOf('\n')
                val number = (if (nl >= 0) block.substring(0, nl) else block).trim().toIntOrNull() ?: continue
                val body = if (nl >= 0) unescape.replace(block.substring(nl + 1), "$1").trim() else ""
                if (body.isEmpty()) continue
                for (i in 0 until pageCount) if (userSlide[i] == number - 1) notes[i] = body
            }
            // For the JSON rewrite: the sections known to PDFPC and the overlays of [skip].
            val meta = JSONObject()
            for ((section, field) in LEGACY_FIELDS) {
                val value = sections[section]?.takeIf { it.isNotEmpty() } ?: continue
                if (field.second) value.toIntOrNull()?.let { meta.put(field.first, it) } else meta.put(field.first, value)
            }
            val extras = skip.filter { it in 0 until pageCount }.associateWith { JSONObject().put("forcedOverlay", true) }
            return PdfpcNotes(notes, markdown = true, meta, extras)
        }
    }
}
