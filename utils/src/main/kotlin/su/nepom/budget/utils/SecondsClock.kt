package su.nepom.budget.utils

import kotlinx.datetime.Clock
import kotlinx.datetime.Instant

object SecondsClock: Clock {
    override fun now(): Instant = Clock.System.now().let { Instant.fromEpochSeconds(it.epochSeconds) }
}