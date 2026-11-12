package it.presenter.app.res

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/** Translations must have exactly the keys of the base language, with the same placeholders. */
class StringsTest {
    private val res = File("src/main/res")

    private fun strings(dir: String): Map<String, String> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File(res, "$dir/strings.xml"))
        val nodes = doc.getElementsByTagName("string")
        return (0 until nodes.length).map { nodes.item(it) }
            .filter { it.attributes.getNamedItem("translatable")?.nodeValue != "false" }
            .associate { it.attributes.getNamedItem("name").nodeValue to it.textContent }
    }

    /** Plurals: name → (quantity → text). */
    private fun plurals(dir: String): Map<String, Map<String, String>> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File(res, "$dir/strings.xml"))
        val nodes = doc.getElementsByTagName("plurals")
        return (0 until nodes.length).map { nodes.item(it) as Element }.associate { p ->
            val items = p.getElementsByTagName("item")
            p.getAttribute("name") to (0 until items.length).map { items.item(it) as Element }
                .associate { it.getAttribute("quantity") to it.textContent }
        }
    }

    private fun placeholders(s: String) = Regex("%\\d+\\$[sd]").findAll(s).map { it.value }.toSortedSet()

    @Test
    fun `Italian has the same keys as English`() {
        val base = strings("values")
        val it = strings("values-it")
        assertEquals("missing or extra keys", base.keys.toSortedSet(), it.keys.toSortedSet())
        for ((key, text) in base) assertEquals("placeholders of $key", placeholders(text), placeholders(it.getValue(key)))
    }

    @Test
    fun `Italian has the same plurals as English`() {
        val base = plurals("values")
        val it = plurals("values-it")
        assertEquals("missing or extra plurals", base.keys.toSortedSet(), it.keys.toSortedSet())
        for (lang in listOf(base, it)) for ((key, items) in lang) {
            // "one" and "other" are the quantities used by both languages.
            assertEquals("quantities of $key", setOf("one", "other"), items.keys)
            for (text in items.values) assertEquals("placeholders of $key", placeholders(base.getValue(key).getValue("other")), placeholders(text))
        }
    }

    /** Style guide (`docs/guida-testi.md`): «…» in Italian, “…” in English. */
    @Test
    fun `quotes of the right language`() {
        for ((key, text) in strings("values-it")) assertTrue("English quotes in values-it/$key", '“' !in text && '”' !in text)
        for ((key, text) in strings("values")) assertTrue("Italian quotes in values/$key", '«' !in text && '»' !in text)
    }

    @Test
    fun `every declared language has its folder`() {
        val config = File(res, "xml/locales_config.xml").readText()
        assertTrue(config.contains("\"en\"") && config.contains("\"it\""))
        assertTrue(File(res, "values-it/strings.xml").isFile)
    }
}
