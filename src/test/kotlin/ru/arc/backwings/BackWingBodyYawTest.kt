package ru.arc.backwings

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.floats.plusOrMinus
import io.kotest.matchers.shouldBe

class BackWingBodyYawTest : FreeSpec({
    "captured native forward movement converges from server 50 degrees to 1 point 4 in ten ticks" {
        val body = BackWingBodyYaw(50f, 0.0, 0.0, 0L)
        for (tick in 1L..10L) body.observe(tick, 0.0, tick * 0.2, 0f, false)
        body.yaw shouldBe (1.412376f plusOrMinus 0.0001f)
        // The server getter stayed at 50; the native client then turned and walked toward 270.
        for (tick in 11L..50L) body.observe(tick, (tick - 10) * 0.2, 2.0, 270f, false)
        body.yaw shouldBe (-90f plusOrMinus 0.0001f)
    }

    "idle head turns preserve the body within native limits and blocking narrows the limit" {
        nextBackWingBodyYaw(0f, 35f, 0.0, 0.0, false) shouldBe 0f
        nextBackWingBodyYaw(0f, 270f, 0.0, 0.0, false) shouldBe -40f
        nextBackWingBodyYaw(0f, 90f, 0.0, 0.0, true) shouldBe 75f
        nextBackWingBodyYaw(20f, 20f, 0.0, 0.049, false) shouldBe 20f
    }

    "backwards walking faces the head and wraparound takes the short arc" {
        nextBackWingBodyYaw(180f, 180f, 0.0, 0.2, false) shouldBe -180f
        val radians = Math.toRadians(179.0)
        nextBackWingBodyYaw(-179f, 179f, -kotlin.math.sin(radians) * 0.2,
            kotlin.math.cos(radians) * 0.2, false) shouldBe (-179.6f plusOrMinus 0.001f)
    }

    "multiple move packets and reload in one tick revise a single smoothing step" {
        val body = BackWingBodyYaw(50f, 0.0, 0.0, 0L)
        body.observe(1L, 0.0, 0.1, 0f, false) shouldBe 35f
        body.observe(1L, 0.0, 0.2, 0f, false) shouldBe 35f
        body.observe(1L, 0.0, 0.2, 0f, false) shouldBe 35f
        body.observe(2L, 0.0, 0.4, 0f, false) shouldBe 24.5f
        body.observe(100L, 0.0, 0.4, 0f, false) shouldBe 24.5f
    }
})
