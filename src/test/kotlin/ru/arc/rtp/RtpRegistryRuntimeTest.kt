package ru.arc.rtp

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class RtpRegistryRuntimeTest : FreeSpec({
    "cold preparation and blocked persistence never block admission or read snapshots" {
        val path = Files.createTempDirectory("arc-rtp-runtime-").resolve("state.json")
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val player = UUID.randomUUID()
        val runtime = RtpRegistryRuntime {
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            RtpPlayerStore.open(path)
        }
        try {
            entered.await(5, TimeUnit.SECONDS) shouldBe true
            runtime.state(player, "survival") shouldBe null
            val mark = runtime.mutate(player) { it.markTeleported(player, "survival") }
            mark.isDone shouldBe false
            runtime.state(player, "survival") shouldBe null
            release.countDown()
            mark.get(5, TimeUnit.SECONDS) shouldBe true
            runtime.state(player, "survival") shouldBe PlayerRtpState(true, true)
        } finally {
            release.countDown()
            runtime.closeAsync().get(5, TimeUnit.SECONDS)
        }
    }

    "writes admitted during bootstrap preserve FIFO and shutdown drains durable state" {
        val path = Files.createTempDirectory("arc-rtp-runtime-").resolve("state.json")
        val player = UUID.randomUUID()
        val runtime = RtpRegistryRuntime { RtpPlayerStore.open(path) }
        val mark = runtime.mutate(player) { it.markTeleported(player, "survival") }
        val reset = runtime.mutate(player) { it.reset(player) }
        val closed = runtime.closeAsync()
        mark.get(5, TimeUnit.SECONDS) shouldBe true
        reset.get(5, TimeUnit.SECONDS).changed shouldBe true
        closed.get(5, TimeUnit.SECONDS)
        RtpPlayerStore.open(path).state(player, "survival") shouldBe PlayerRtpState(false, false)
        runtime.mutate(player) { it.markTeleported(player, "survival") }.isCompletedExceptionally shouldBe true
    }

    "failed initialization remains unavailable and does not silently create state" {
        val runtime = RtpRegistryRuntime { error("unreadable store") }
        val player = UUID.randomUUID()
        try {
            runCatching { runtime.ready().get(5, TimeUnit.SECONDS) }.isFailure shouldBe true
            runCatching { runtime.mutate(player) { it.reset(player) }.get(5, TimeUnit.SECONDS) }.isFailure shouldBe true
            runtime.state(player, "survival") shouldBe null
        } finally { runtime.closeAsync().get(5, TimeUnit.SECONDS) }
    }
})
