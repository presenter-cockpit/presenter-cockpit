package it.presenter.app.testing

import kotlin.random.Random

/**
 * Minimal property testing, without libraries: [check] runs [body] on [runs] cases generated from fixed
 * seeds, so a failure always comes back the same. The failing seed is in the message; to replay only
 * that one: `./gradlew … -Pproperty.seed=N`. With `-Pproperty.scale=K` every property runs K times as
 * many cases (longer hunts, by hand).
 */
object Property {
    private val scale = System.getProperty("property.scale")?.toIntOrNull()?.coerceAtLeast(1) ?: 1
    private val only = System.getProperty("property.seed")?.toLongOrNull()

    fun check(name: String, runs: Int = 300, body: (Random) -> Unit) {
        val seeds = only?.let { listOf(it) } ?: (0L until runs.toLong() * scale).map { it * 7919 + name.hashCode() }
        for (seed in seeds) {
            try {
                body(Random(seed))
            } catch (e: Throwable) {
                throw AssertionError("$name: failed with seed $seed (-Pproperty.seed=$seed): ${e.message}", e)
            }
        }
    }
}
