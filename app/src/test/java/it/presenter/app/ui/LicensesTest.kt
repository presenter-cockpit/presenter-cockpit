package it.presenter.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LicensesTest {
    private val full = """
        === apache-2.0
        Apache License
        Version 2.0
        === jsch
        Copyright (c) JCraft
        === jbcrypt
        Copyright (c) Damien Miller
        === libvterm
        The MIT License
        === ofl-1.1
        SIL OPEN FONT LICENSE Version 1.1
    """.trimIndent()

    private fun build(vararg entries: Pair<String, String>): List<LicenseGroup> {
        val data = StringBuilder()
        val meta = StringBuilder()
        for ((name, text) in entries) {
            meta.append("${data.length}:${text.length} $name\n")
            data.append(text)
        }
        return Licenses.build(meta.toString(), data.toString().toByteArray(), full)
    }

    private fun List<LicenseGroup>.of(kind: LicenseKind) = first { it.kind == kind }

    @Test fun sectionsAreSplitByKey() {
        val s = Licenses.sections(full)
        assertEquals(setOf("apache-2.0", "jsch", "jbcrypt", "libvterm", "ofl-1.1"), s.keys)
        assertEquals("Apache License\nVersion 2.0", s["apache-2.0"])
    }

    @Test fun apacheComponentsShareOneTextAndKeepTheirCopyright() {
        val aosp = "Copyright (c) 2005-2011, The Android Open Source Project\n\n" +
            "   Licensed under the Apache License, Version 2.0 (the \"License\");\n   TERMS AND CONDITIONS"
        val list = build(
            "Compose UI" to "http://www.apache.org/licenses/LICENSE-2.0.txt",
            "PdfBox-Android" to "Apache License\n   Version 2.0, January 2004\n   TERMS AND CONDITIONS",
            "AndroidX core library" to aosp,
            "AndroidX activity library" to aosp,
            "AndroidX fragment library" to aosp,
            "AndroidX loader library" to aosp,
            "AndroidX print library" to aosp,
        )
        val apache = list.of(LicenseKind.APACHE)
        assertEquals(listOf("AndroidX", "PdfBox-Android"), apache.families)
        assertEquals(7, apache.components.size)
        assertEquals(listOf("AndroidX activity library", "AndroidX core library"), apache.components.take(2))
        assertTrue(apache.text.startsWith("Apache License\nVersion 2.0"))
        assertEquals(1, Regex("Version 2.0").findAll(apache.text).count())
        assertTrue("— AndroidX —\nCopyright (c) 2005-2011" in apache.text)
        assertFalse("TERMS AND CONDITIONS" in apache.text)
    }

    @Test fun duplicateModulesAreMerged() {
        val list = build(
            "AndroidX lifecycle runtime library" to "http://www.apache.org/licenses/LICENSE-2.0.txt",
            "Lifecycle Runtime" to "http://www.apache.org/licenses/LICENSE-2.0.txt",
            "Kotlin" to "http://www.apache.org/licenses/LICENSE-2.0.txt",
            "kotlin" to "Copyright 2008 Google Inc.\nRedistribution and use in source and binary forms",
        )
        assertEquals(listOf("Kotlin", "Lifecycle Runtime"), list.of(LicenseKind.APACHE).components)
        assertEquals(listOf("Protocol Buffers"), list.of(LicenseKind.BSD).families)
    }

    @Test fun otherLicensesKeepEachNoticeAndAreOrdered() {
        val list = build(
            "JSch" to "https://github.com/mwiede/jsch/blob/master/LICENSE.txt",
            "JSch" to "https://github.com/mwiede/jsch/blob/master/LICENSE.jBCrypt.txt",
            "JSR 305" to "Copyright (c) JSR305\nRedistribution and use in source and binary forms",
            "Animal Sniffer" to "The MIT License\nPermission is hereby granted",
            "common" to "https://developers.google.com/ml-kit/terms",
            "Odd" to "Some embedded license\nwith two lines",
        )
        assertEquals(
            listOf(LicenseKind.MIT, LicenseKind.BSD, LicenseKind.ISC, LicenseKind.OFL, LicenseKind.OTHER, LicenseKind.TERMS),
            list.map { it.kind },
        )
        val bsd = list.of(LicenseKind.BSD)
        assertEquals(listOf("JSR, JSpecify, javax.inject", "JSch"), bsd.families)
        assertTrue("— JSch —\nCopyright (c) JCraft" in bsd.text && "Copyright (c) JSR305" in bsd.text)
        assertEquals(listOf("JSch (jBCrypt)"), list.of(LicenseKind.ISC).components)
        assertEquals(listOf("Animal Sniffer", "libvterm (termlib)"), list.of(LicenseKind.MIT).components)
        assertTrue("The MIT License" in list.of(LicenseKind.MIT).text)
        assertEquals(listOf("Google Play services, Firebase, ML Kit"), list.of(LicenseKind.TERMS).families)
        assertTrue("ml-kit/terms" in list.of(LicenseKind.TERMS).text)
    }

    @Test fun malformedLinesAreIgnored() {
        val list = Licenses.build("garbage\n5:999 Too long\n", ByteArray(3), full)
        // Only what is added by hand: libvterm and the bundled font.
        assertEquals(listOf(LicenseKind.MIT, LicenseKind.OFL), list.map { it.kind })
        assertEquals(listOf("libvterm (termlib)"), list.of(LicenseKind.MIT).components)
    }

    @Test fun theBundledFontHasItsNoticeAndTheLicenseText() {
        val fonts = Licenses.build("", ByteArray(0), full).of(LicenseKind.OFL)
        assertEquals(listOf("Indie Flower (font)"), fonts.components)
        assertTrue(fonts.text.startsWith("SIL OPEN FONT LICENSE Version 1.1"))
        assertTrue("— Indie Flower (font) —\nCopyright 2010 The Indie Flower Authors" in fonts.text)
    }
}
