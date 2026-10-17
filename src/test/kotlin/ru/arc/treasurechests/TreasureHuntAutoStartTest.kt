package ru.arc.treasurechests

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkObject
import io.mockk.unmockkStatic
import io.mockk.verify
import org.bukkit.Bukkit
import org.bukkit.World
import org.bukkit.command.ConsoleCommandSender
import org.bukkit.entity.Player
import ru.arc.config.TestConfig
import ru.arc.core.ScheduledTask
import ru.arc.core.TaskScheduler
import ru.arc.core.TestTaskScheduler

class TreasureHuntAutoStartTest : DescribeSpec({
    it("requires a durable reservation before native placement and bounds dynamic counts") {
        for ((online, expected) in listOf(1 to 20, 6 to 20, 7 to 21, 19 to 57, 20 to 60, Int.MAX_VALUE to 60)) {
            val h = AutoStartHarness(online = online)
            h.reload()
            h.finishIo()
            h.at(QUARTER)
            h.started shouldBe emptyList()
            h.io.pendingCount() shouldBe 1
            h.at(QUARTER + 1_000)
            h.io.pendingCount() shouldBe 1
            h.finishIo()
            h.started shouldBe listOf("spawn" to expected)
            h.deadline shouldBe QUARTER + COOLDOWN
            h.reservations shouldBe 1
            h.controller.close()
        }
    }

    it("imports the legacy deadline once and keeps quarter-hour checks") {
        val h = AutoStartHarness(initialDeadline = null)
        val expiry = 3 * QUARTER + 965
        h.reload(HuntAutoStartSettings(enabled = true, initialNotBefore = expiry))
        h.finishIo()
        h.deadline shouldBe expiry
        h.at(3 * QUARTER)
        h.reservations shouldBe 0
        h.at(expiry + 1)
        h.reservations shouldBe 0
        h.at(4 * QUARTER)
        h.finishIo()
        h.started.size shouldBe 1
        h.controller.close()
    }

    it("uses a one-time conservative three-hour deadline when no migration seed exists") {
        val h = AutoStartHarness(initialDeadline = null)
        h.reload()
        h.finishIo()
        h.deadline shouldBe COOLDOWN
        h.clock = QUARTER
        h.reload()
        h.loads shouldBe 1
        h.deadline shouldBe COOLDOWN
        h.at(COOLDOWN)
        h.finishIo()
        h.started.size shouldBe 1
        h.controller.close()
    }

    it("does not reserve for an empty or missing world") {
        val h = AutoStartHarness(online = 0)
        h.reload()
        h.finishIo()
        h.at(QUARTER)
        h.reservations shouldBe 0
        h.io.pendingCount() shouldBe 0
        h.online = 2
        h.at(2 * QUARTER)
        h.finishIo()
        h.started shouldBe listOf("spawn" to 20)
        h.controller.close()
    }

    it("retains cooldown when an active hunt skips or the start fails") {
        for (throws in listOf(false, true)) {
            val h = AutoStartHarness()
            h.startFailure = throws
            h.reload()
            h.finishIo()
            h.at(QUARTER)
            h.finishIo()
            h.started.size shouldBe 1
            h.at(QUARTER + COOLDOWN - 1)
            h.reservations shouldBe 1
            h.controller.close()
        }
    }

    it("keeps one accepted write across reload but fences its old native start") {
        val h = AutoStartHarness()
        h.reload()
        h.finishIo()
        h.at(QUARTER)
        h.reload()
        h.io.pendingCount() shouldBe 1
        h.finishIo()
        h.started shouldBe emptyList()
        h.deadline shouldBe QUARTER + COOLDOWN
        h.at(QUARTER + COOLDOWN)
        h.finishIo()
        h.started.size shouldBe 1
        h.controller.close()
    }

    it("reconciles initialization across reload without another queued load") {
        val h = AutoStartHarness(initialDeadline = null)
        h.reload()
        h.reload()
        h.io.pendingCount() shouldBe 1
        h.finishIo()
        h.loads shouldBe 1
        h.at(COOLDOWN)
        h.finishIo()
        h.started.size shouldBe 1
        h.controller.close()
    }

    it("shutdown cancels its timer and accepted storage can finish without placement") {
        val h = AutoStartHarness()
        h.reload()
        h.finishIo()
        h.at(QUARTER)
        h.controller.close()
        h.main.timerCount() shouldBe 0
        h.finishIo()
        h.deadline shouldBe QUARTER + COOLDOWN
        h.started shouldBe emptyList()
    }

    it("pauses after unknown storage failure and reload reads the durable deadline") {
        val h = AutoStartHarness()
        h.reload()
        h.finishIo()
        h.failAfterCommit = true
        h.at(QUARTER)
        h.finishIo()
        h.started shouldBe emptyList()
        h.deadline shouldBe QUARTER + COOLDOWN
        h.at(2 * QUARTER)
        h.io.pendingCount() shouldBe 0
        h.failAfterCommit = false
        h.reload()
        h.finishIo()
        h.loads shouldBe 2
        h.at(3 * QUARTER)
        h.reservations shouldBe 1
        h.controller.close()
    }

    it("coalesces missed checks and does not start if players leave during the write") {
        val h = AutoStartHarness()
        h.reload()
        h.finishIo()
        h.at(10 * QUARTER)
        h.online = 0
        h.finishIo()
        h.reservations shouldBe 1
        h.started shouldBe emptyList()
        h.online = 1
        h.at(11 * QUARTER)
        h.reservations shouldBe 1
        h.controller.close()
    }

    it("disabled settings perform no storage or world work") {
        val h = AutoStartHarness()
        h.reload(HuntAutoStartSettings())
        h.at(QUARTER)
        h.io.pendingCount() shouldBe 0
        h.main.timerCount() shouldBe 0
        h.loads shouldBe 0
        h.started shouldBe emptyList()
        h.controller.close()
    }

    it("loads safe defaults and disables invalid operator settings") {
        TreasureHuntModuleConfig(TestConfig()).autoStartSettings shouldBe HuntAutoStartSettings()
        val config = TestConfig(mapOf("auto-start.enabled" to true, "auto-start.initial-not-before" to -1L))
        TreasureHuntModuleConfig(config).autoStartSettings.enabled shouldBe false
    }

    it("counts only real online Origin players and ignores NPC-only worlds") {
        mockkStatic(Bukkit::class)
        try {
            val origin = mockk<World>()
            val foreign = mockk<World>()
            val npc = mockk<Player>()
            val player = mockk<Player>()
            val elsewhere = mockk<Player>()
            every { Bukkit.getWorld("rc_origin_spawn") } returns origin
            every { origin.players } returns listOf(npc)
            every { Bukkit.getOnlinePlayers() } returns emptyList<Player>()
            val paper = PaperHuntAutoStartWorld()
            val h = AutoStartHarness(worldOverride = paper)
            h.reload()
            h.finishIo()
            h.at(QUARTER)
            h.reservations shouldBe 0
            every { player.world } returns origin
            every { elsewhere.world } returns foreign
            every { Bukkit.getOnlinePlayers() } returns listOf(player, elsewhere)
            paper.playerCount("rc_origin_spawn") shouldBe 1
            verify(exactly = 0) { origin.players }
            h.controller.close()
        } finally {
            unmockkStatic(Bukkit::class)
        }
    }

    it("uses the resolved validated registry overload with replace false") {
        mockkStatic(Bukkit::class)
        mockkObject(TreasureHuntRegistry)
        try {
            val config = TreasureHuntConfig.simple("spawn", "rc_origin_spawn", ChestType.vanilla("spawn_hunt"))
            val sender = mockk<ConsoleCommandSender>()
            every { Bukkit.getConsoleSender() } returns sender
            every { TreasureHuntRegistry.getHuntConfig("spawn") } returns config
            every { TreasureHuntRegistry.startHunt(config, 20, sender, false) } returns null
            PaperHuntAutoStartWorld().start("spawn", 20)
            verify(exactly = 1) { TreasureHuntRegistry.startHunt(config, 20, sender, false) }
        } finally {
            unmockkObject(TreasureHuntRegistry)
            unmockkStatic(Bukkit::class)
        }
    }
})

private const val QUARTER = TreasureHuntAutoStart.CHECK_MILLIS
private const val COOLDOWN = TreasureHuntAutoStart.COOLDOWN_MILLIS

private class AutoStartHarness(
    initialDeadline: Long? = 1L,
    var online: Int = 1,
    worldOverride: HuntAutoStartWorld? = null,
) {
    val main = TestTaskScheduler()
    val io = TestTaskScheduler()
    var clock = 0L
    var deadline = initialDeadline
    var loads = 0
    var reservations = 0
    var startFailure = false
    var failAfterCommit = false
    val started = mutableListOf<Pair<String, Int>>()
    private var onStorage = false
    private val scheduler = object : TaskScheduler by main {
        override fun runAsync(task: Runnable): ScheduledTask = io.runAsync(Runnable {
            onStorage = true
            try { task.run() } finally { onStorage = false }
        })
    }
    private val store = object : HuntAutoStartStore {
        override fun loadOrInitialize(initialNotBefore: Long): Long {
            onStorage shouldBe true
            loads++
            return deadline ?: initialNotBefore.also { deadline = it }
        }
        override fun reserve(nextAttemptAt: Long): Long {
            onStorage shouldBe true
            reservations++
            deadline = maxOf(requireNotNull(deadline), nextAttemptAt)
            if (failAfterCommit) error("unknown commit outcome")
            return requireNotNull(deadline)
        }
    }
    private val world = worldOverride ?: object : HuntAutoStartWorld {
        override fun playerCount(world: String): Int {
            onStorage shouldBe false
            return online
        }
        override fun start(preset: String, chests: Int) {
            onStorage shouldBe false
            started += preset to chests
            if (startFailure) error("provider unavailable")
        }
    }
    val controller = TreasureHuntAutoStart(scheduler, world, store) { clock }
    fun reload(settings: HuntAutoStartSettings = HuntAutoStartSettings(enabled = true)) = controller.reload(settings)
    fun finishIo() {
        io.executeImmediate()
        main.executeImmediate()
    }
    fun at(timestamp: Long) {
        clock = timestamp
        main.advanceMs(6_000L)
    }
}
