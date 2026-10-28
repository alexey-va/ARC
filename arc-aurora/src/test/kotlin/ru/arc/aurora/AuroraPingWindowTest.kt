package ru.arc.aurora

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe

class AuroraPingWindowTest : FreeSpec({
    "empty windows fail closed and the first sample initializes the average" {
        val window = AuroraPingWindow(10)
        val policy = AuroraPingSettings()
        window.admits(50, policy) shouldBe false
        window.sample(50)
        window.averageMs shouldBe 50.0
        window.admits(50, policy) shouldBe true
    }

    "a current spike is rejected independently of the rolling average" {
        val policy = AuroraPingSettings(maxCurrentMs = 250, maxAverageMs = 180, windowSamples = 10)
        val window = AuroraPingWindow(policy.windowSamples)
        repeat(9) { window.sample(50) }
        window.admits(251, policy) shouldBe false
        window.sample(300)
        window.averageMs shouldBe 75.0
        window.admits(300, policy) shouldBe false
        window.admits(250, policy) shouldBe true
    }

    "a sustained high average rejects a recovered current ping until its samples roll out" {
        val policy = AuroraPingSettings(maxCurrentMs = 250, maxAverageMs = 180, windowSamples = 3)
        val window = AuroraPingWindow(policy.windowSamples)
        repeat(3) { window.sample(240) }
        window.admits(50, policy) shouldBe false
        window.sample(60)
        window.averageMs shouldBe 180.0
        window.admits(50, policy) shouldBe true
        window.sample(0)
        window.averageMs shouldBe 100.0
        window.sample(0)
        window.averageMs shouldBe 20.0
    }

    "both thresholds are inclusive and negative observations remain invalid until replaced" {
        val policy = AuroraPingSettings(maxCurrentMs = 250, maxAverageMs = 180, windowSamples = 2)
        val window = AuroraPingWindow(2)
        window.sample(180)
        window.admits(250, policy) shouldBe true
        window.admits(251, policy) shouldBe false
        window.admits(-1, policy) shouldBe false
        window.sample(181)
        window.admits(250, policy) shouldBe false
        window.sample(-1)
        window.averageMs shouldBe Double.POSITIVE_INFINITY
        window.sample(0)
        window.admits(0, policy) shouldBe false
        window.sample(0)
        window.averageMs shouldBe 0.0
        window.admits(0, policy) shouldBe true
    }

    "fixed capacity and long sums prevent overflow" {
        shouldThrow<IllegalArgumentException> { AuroraPingWindow(0) }
        shouldThrow<IllegalArgumentException> { AuroraPingWindow(121) }
        val window = AuroraPingWindow(120)
        repeat(120) { window.sample(Int.MAX_VALUE) }
        window.averageMs shouldBe Int.MAX_VALUE.toDouble()
        repeat(120) { window.sample(0) }
        window.averageMs shouldBe 0.0
    }
})
