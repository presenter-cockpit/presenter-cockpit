package it.presenter.app.doc

import it.presenter.app.testing.Elements
import it.presenter.app.testing.Property
import it.presenter.app.testing.Strings
import org.junit.Assert.assertEquals
import org.junit.Test

/** Formats written by the app and read back: elements in the PDF and the session, PDFPC notes. */
class CodecPropertyTest {
    @Test
    fun `every element comes back the same`() = Property.check("element codec", runs = 2000) { r ->
        val e = Elements.random(r)
        val bytes = ElementCodec.encode(e)!!
        assertEquals(Elements.bytes(listOf(e)), Elements.bytes(listOfNotNull(ElementCodec.decode(bytes))))
    }

    @Test
    fun `notes with any text and labels come back the same`() = Property.check("pdfpc json") { r ->
        val n = r.nextInt(1, 20)
        val labels = if (r.nextBoolean()) null else List(n) { if (r.nextInt(4) == 0) "" else Strings.random(r, 3) }
        val texts = (0 until n).filter { r.nextBoolean() }.associateWith { Strings.random(r, 60).ifEmpty { "x" } }
        val source = org.json.JSONObject().put(
            "pages", org.json.JSONArray().apply { texts.forEach { (p, t) -> put(org.json.JSONObject().put("idx", p).put("note", t)) } },
        ).toString()
        val notes = PdfpcNotes.read(source, n, null)!!
        val json = notes.toJson(n, labels)
        val back = PdfpcNotes.read(json, n, labels)!!
        for (p in 0 until n) assertEquals("page $p of $json", texts[p], back.noteFor(p))
    }

    @Test
    fun `legacy notes with escapes go to all pages of their slide`() = Property.check("pdfpc legacy") { r ->
        val n = r.nextInt(1, 12)
        // Overlays by label: "1", "1", "2"…
        val labels = ArrayList<String>().apply { var k = 0; repeat(n) { add(if (isNotEmpty() && r.nextBoolean()) last() else (++k).toString()) } }
        val slideOf = labels.map { labels.distinct().indexOf(it) }
        val texts = slideOf.distinct().filter { r.nextBoolean() }.associateWith { "[" + Strings.random(r, 30).replace("###", "") + "] #x" }
        val file = buildString {
            append("[duration]\n15\n[notes]\n")
            for ((s, t) in texts) append("### ${s + 1}\n").append(t.replace("[", "\\[").replace("]", "\\]").replace("#", "\\#")).append('\n')
        }
        val notes = PdfpcNotes.read(file, n, labels)!!
        for (p in 0 until n) assertEquals("page $p", texts[slideOf[p]]?.trim(), notes.noteFor(p))
        assertEquals(15, org.json.JSONObject(notes.toJson(n, labels)).getInt("duration"))
    }
}
