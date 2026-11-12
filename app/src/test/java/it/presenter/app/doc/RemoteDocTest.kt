package it.presenter.app.doc

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteDocTest {
    @Test
    fun pdfpcIsNextToThePdf() {
        assertEquals("/home/u/talk.pdfpc", DocSource.pdfpcPath("/home/u/talk.pdf"))
        // A period in a folder name does not count; without an extension one is added.
        assertEquals("/a/b.c/talk.pdfpc", DocSource.pdfpcPath("/a/b.c/talk"))
        assertEquals("/a/talk.v2.pdfpc", DocSource.pdfpcPath("/a/talk.v2.PDF"))
    }

    @Test
    fun copyNameDependsOnServerAndPath() {
        val k = RemoteCopy.keyOf("srv1", "/home/u/talk.pdf")
        assertEquals(k, RemoteCopy.keyOf("srv1", "/home/u/talk.pdf"))
        assertNotEquals(k, RemoteCopy.keyOf("srv2", "/home/u/talk.pdf"))
        assertNotEquals(k, RemoteCopy.keyOf("srv1", "/home/u/talk2.pdf"))
        assertTrue(k.matches(Regex("[0-9a-f]{24}")))
    }

    @Test
    fun sameRemoteFileIsRecognized() {
        val a = DocSource.Remote("srv1", "/home/u/talk.pdf")
        assertTrue(a.sameAs(DocSource.Remote("srv1", "/home/u/talk.pdf", fromSession = true)))
        assertFalse(a.sameAs(DocSource.Remote("srv1", "/home/u/other.pdf")))
        // Different profiles that nobody knows any more: they cannot be compared, so they differ.
        assertFalse(a.sameAs(DocSource.Remote("srv2", "/home/u/talk.pdf")))
        // A tablet file with the same path is not the server's file.
        assertFalse(a.sameAs(DocSource.Local(File("/home/u/talk.pdf"))))
        assertFalse(DocSource.Local(File("/home/u/talk.pdf")).sameAs(a))
        assertEquals("talk.pdf", a.displayName)
    }
}
