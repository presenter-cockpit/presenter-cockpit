package it.presenter.app.remote

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HostKeysTest {
    private val key = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAII2LDKBYO/sEIEobmT3BVcoRgVs0H5+X8MImGdMqPVzO"

    @Test
    fun `forgets only the given server`() {
        val file = File.createTempFile("known_hosts", "")
        try {
            file.writeText("one.example $key\n[two.example]:2222 $key\nthree.example $key\n")
            val first = ServerProfile(name = "", host = "one.example", user = "u")
            val two = ServerProfile(name = "", host = "two.example", port = 2222, user = "u")
            val twoStd = ServerProfile(name = "", host = "two.example", user = "u")
            assertTrue(HostKeys.has(file, first))
            assertTrue(HostKeys.has(file, two))
            assertFalse(HostKeys.has(file, twoStd))

            HostKeys.forget(file, two)
            assertFalse(HostKeys.has(file, two))
            assertTrue(HostKeys.has(file, first))
            val lines = file.readLines().filter { it.isNotBlank() }
            assertEquals(listOf("one.example", "three.example"), lines.map { it.substringBefore(' ') })
        } finally {
            file.delete()
        }
    }

    @Test
    fun `without a file there is nothing to forget`() {
        val missing = File(System.getProperty("java.io.tmpdir"), "no-known-hosts-${System.nanoTime()}")
        val s = ServerProfile(name = "", host = "x", user = "u")
        assertFalse(HostKeys.has(missing, s))
        HostKeys.forget(missing, s)
        assertFalse(missing.exists())
    }
}
