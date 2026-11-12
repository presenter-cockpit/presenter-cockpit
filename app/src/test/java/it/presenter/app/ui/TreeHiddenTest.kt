package it.presenter.app.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TreeHiddenTest {
    @Test fun hiddenPathsAreRecognizedBelowTheRoot() {
        val root = "/home/u/.config/project"
        assertFalse(isHiddenPath("$root/src/main.c", root))
        assertTrue(isHiddenPath("$root/.git", root))
        assertTrue(isHiddenPath("$root/.git/config", root))
        assertTrue(isHiddenPath("$root/src/.cache/x", root))
    }
}
