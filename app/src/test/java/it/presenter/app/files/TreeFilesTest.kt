package it.presenter.app.files

import it.presenter.app.doc.DocSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TreeFilesTest {
    private val tree = "content://com.android.externalstorage.documents/tree/primary%3ADocuments%2Fslides"

    @Test fun treePathsAreRecognized() {
        assertTrue(TreeFiles.isTreePath("$tree/src/main.c"))
        assertFalse(TreeFiles.isTreePath("/storage/emulated/0/Documents/slides"))
    }

    @Test fun rootIsFoundFromAnyDepth() {
        assertEquals(tree, TreeFiles.treeRootOf(tree))
        assertEquals(tree, TreeFiles.treeRootOf("$tree/talk.pdf"))
        assertEquals(tree, TreeFiles.treeRootOf("$tree/a/b/c/main.c"))
    }

    @Test fun nameParentAndNotesWorkLikeFilePaths() {
        val pdf = "$tree/talks/talk.pdf"
        assertEquals("talk.pdf", pdf.substringAfterLast('/'))
        assertEquals("$tree/talks", pdf.substringBeforeLast('/'))
        assertEquals("$tree/talks/talk.pdfpc", DocSource.pdfpcPath(pdf))
        // Name of the granted folder: the last piece of the URI contains no real "/".
        assertEquals(tree, "$tree/talk.pdf".substringBeforeLast('/'))
    }
}
