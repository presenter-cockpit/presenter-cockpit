package it.presenter.app.doc

import it.presenter.app.remote.ServerProfile
import it.presenter.app.testing.Elements
import it.presenter.app.testing.Fuzz
import it.presenter.app.testing.Property
import it.presenter.app.testing.Strings
import it.presenter.app.tools.ToolPreset
import it.presenter.app.tools.ToolRef
import it.presenter.app.ui.Markdown
import org.junit.Test

/**
 * Damaged or random input to everything that reads files and settings: no crash, only null, an empty
 * result or what could be read. (An exception escaping here would close the app at opening.)
 */
class FuzzTest {
    private val pdfpcJson = """{"pdfpcFormat": 2, "disableMarkdown": false, "duration": 20, "endSlide": 3,
        "pages": [{"idx": 0, "label": "1", "overlay": 0, "note": "a **b**"}, {"idx": 2, "label": "2", "overlay": 1, "hidden": true, "note": "c"}]}"""
    private val pdfpcLegacy = "[duration]\n20\n[skip]\n2,3,\n[end_time]\n10:30\n[notes]\n### 1\nfirst \\[x\\]\n### 2\nsecond\n"

    @Test
    fun `pdfpc files`() = Property.check("fuzz pdfpc", runs = 3000) { r ->
        val text = when (r.nextInt(3)) {
            0 -> Fuzz.mutate(r, pdfpcJson)
            1 -> Fuzz.mutate(r, pdfpcLegacy)
            else -> Strings.random(r, 200)
        }
        val n = r.nextInt(0, 6)
        val labels = if (r.nextBoolean()) null else List(n) { listOf("1", "2", "2", "").random(r) }
        // What was read can always be rewritten and read again.
        PdfpcNotes.read(text, n, labels)?.let { PdfpcNotes.read(it.toJson(n, labels), n, labels) }
        PdfpcNotes.parse(text, n, labels)
    }

    @Test
    fun `encoded elements`() = Property.check("fuzz elements", runs = 5000) { r ->
        val bytes = if (r.nextInt(5) == 0) r.nextBytes(r.nextInt(64)) else Fuzz.mutate(r, ElementCodec.encode(Elements.random(r))!!)
        ElementCodec.decode(bytes)
    }

    @Test
    fun `settings stored as json`() = Property.check("fuzz settings", runs = 2000) { r ->
        val servers = """[{"id": "a", "name": "Lab", "host": "h.example", "port": 22, "user": "u", "tmux": true}]"""
        ToolPreset.listFromJson(Fuzz.mutate(r, """[{"id": "p1", "kind": "PEN", "color": -16777216, "size": 3.5}]"""))
        ServerProfile.listFromJson(Fuzz.mutate(r, servers))
        ToolRef.decodeDuals(Fuzz.mutate(r, """{"preset:p1": "eraser", "lasso": "spotlight"}"""))
        ToolRef.decode(Strings.random(r, 12))
    }

    @Test
    fun `markdown notes`() = Property.check("fuzz markdown", runs = 3000) { r ->
        val text = if (r.nextBoolean()) Strings.random(r, 120) else Fuzz.mutate(r, "- **a** *b* `c` \\_ [x](y)\n# t\n1. z\n")
        Markdown.render(text, enabled = true)
        Markdown.render(text, enabled = false)
    }
}
