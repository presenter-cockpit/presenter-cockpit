package it.presenter.app.testing

import kotlin.random.Random

/** Damaged versions of valid inputs, for the fuzz tests: flipped bytes, cuts, insertions, repetitions. */
object Fuzz {
    fun mutate(r: Random, input: ByteArray): ByteArray {
        var b = input.copyOf()
        repeat(r.nextInt(1, 6)) {
            b = when (r.nextInt(6)) {
                0 -> if (b.isEmpty()) b else b.also { it[r.nextInt(it.size)] = r.nextInt(256).toByte() }
                1 -> if (b.isEmpty()) b else b.also { val i = r.nextInt(it.size); it[i] = (it[i].toInt() xor (1 shl r.nextInt(8))).toByte() }
                2 -> b.copyOf(if (b.isEmpty()) 0 else r.nextInt(b.size))
                3 -> {
                    val at = r.nextInt(b.size + 1)
                    b.copyOfRange(0, at) + r.nextBytes(r.nextInt(1, 9)) + b.copyOfRange(at, b.size)
                }
                4 -> if (b.size < 2) b else {
                    val from = r.nextInt(b.size)
                    val to = r.nextInt(from, b.size)
                    b.copyOfRange(0, to) + b.copyOfRange(from, b.size)
                }
                // Large or negative numbers where a size usually is.
                else -> if (b.size < 4) b else b.also {
                    val i = r.nextInt(it.size - 3)
                    val v = listOf(Int.MAX_VALUE, Int.MIN_VALUE, -1, 1 shl 24, 0x7fff).random(r)
                    for (k in 0..3) it[i + k] = (v ushr (24 - 8 * k)).toByte()
                }
            }
        }
        return b
    }

    fun mutate(r: Random, text: String): String = mutate(r, text.toByteArray()).toString(Charsets.UTF_8)
}
