package ru.arc.treasurechests

import io.kotest.assertions.throwables.shouldThrowAny
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import net.kyori.adventure.bossbar.BossBar
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.entity.Player
import ru.arc.core.TestTaskScheduler
import ru.arc.redis.InMemoryRedis
import ru.arc.redis.ServerIdentity
import java.util.concurrent.ConcurrentHashMap

class TreasureHuntBossBarRelayTest : FreeSpec({
    val channel = TreasureHuntBossBarRelay.CHANNEL
    fun state(remaining: Int = 6) = HuntBossBarState("spawn:1000", "<gold>Охота · Осталось $remaining", remaining, 6, "YELLOW", "PROGRESS")
    fun snapshot(time: Long, states: List<HuntBossBarState> = listOf(state())) = HuntBossBarSnapshot("spawn", time, states)

    "the existing title and progress follow survival join world change quit and hunt completion" {
        var time = 1_000L
        var worldName = "survival"
        val player = mockk<Player>(relaxed = true)
        every { player.world.name } answers { worldName }
        every { player.isOnline } returns true
        var viewers = listOf(player)
        val shown = mutableListOf<BossBar>()
        every { player.showBossBar(capture(shown)) } returns Unit
        mockkStatic(Bukkit::class)
        every { Bukkit.getOnlinePlayers() } answers { viewers }
        val scheduler = TestTaskScheduler()
        val redis = InMemoryRedis(ServerIdentity { "survival" })
        val relay = TreasureHuntBossBarRelay(redis, scheduler, "survival", TreasureHuntBossBarRelaySettings(), { time })
        fun receive(value: HuntBossBarSnapshot, origin: String = "spawn") {
            redis.simulateExternalMessage(channel, HuntBossBarCodec.codec.encode(value), origin)
            scheduler.executeImmediate()
        }
        try {
            redis.simulateExternalMessage(channel, HuntBossBarCodec.codec.encode(snapshot(time)), "spawn")
            shown.size shouldBe 0 // Redis callback cannot touch Paper players directly.
            scheduler.executeImmediate()
            val bar = shown.single()
            PlainTextComponentSerializer.plainText().serialize(bar.name()) shouldBe "Охота · Осталось 6"
            bar.color() shouldBe BossBar.Color.YELLOW
            bar.overlay() shouldBe BossBar.Overlay.PROGRESS

            time += 100
            receive(snapshot(time, listOf(state(2))))
            shown.size shouldBe 1
            bar.progress() shouldBe 2f / 6f
            PlainTextComponentSerializer.plainText().serialize(bar.name()) shouldBe "Охота · Осталось 2"

            worldName = "mining"
            relay.refreshAudience()
            verify(exactly = 1) { player.hideBossBar(bar) }
            worldName = "survival"
            relay.refreshAudience()
            shown shouldBe listOf(bar, bar)

            relay.onPlayerQuit(player)
            viewers = emptyList()
            relay.refreshAudience()
            verify(exactly = 2) { player.hideBossBar(bar) }
            viewers = listOf(player)
            relay.refreshAudience()
            shown.size shouldBe 3

            time += 100
            receive(snapshot(time, emptyList()))
            verify(exactly = 3) { player.hideBossBar(bar) }
            receive(snapshot(time - 100)) // Delayed pre-completion status must not resurrect the bar.
            receive(snapshot(time + 1), "parkour")
            receive(snapshot(time + 1).copy(source = "survival"), "spawn")
            shown.size shouldBe 3
        } finally {
            relay.close()
            unmockkStatic(Bukkit::class)
        }
        redis.listenerCount(channel) shouldBe 0
        scheduler.timerCount() shouldBe 0
    }

    "source loss expires the bar and closing fences queued messages before a fresh relay starts" {
        var time = 1_000L
        val player = mockk<Player>(relaxed = true)
        every { player.world.name } returns "survival"
        every { player.isOnline } returns true
        val shown = mutableListOf<BossBar>()
        every { player.showBossBar(capture(shown)) } returns Unit
        mockkStatic(Bukkit::class)
        every { Bukkit.getOnlinePlayers() } returns listOf(player)
        val scheduler = TestTaskScheduler()
        val redis = InMemoryRedis(ServerIdentity { "survival" })
        val relay = TreasureHuntBossBarRelay(redis, scheduler, "survival", TreasureHuntBossBarRelaySettings(), { time })
        try {
            redis.simulateExternalMessage(channel, HuntBossBarCodec.codec.encode(snapshot(time)), "spawn")
            scheduler.executeImmediate()
            time += 15_000
            scheduler.tick(20)
            verify(exactly = 1) { player.hideBossBar(shown.single()) }

            redis.simulateExternalMessage(channel, HuntBossBarCodec.codec.encode(snapshot(time)), "spawn")
            relay.close()
            relay.close()
            scheduler.executeImmediate()
            shown.size shouldBe 1
            redis.listenerCount(channel) shouldBe 0
            scheduler.timerCount() shouldBe 0

            TreasureHuntBossBarRelay(redis, scheduler, "survival", TreasureHuntBossBarRelaySettings(), { time }).use {
                redis.listenerCount(channel) shouldBe 1
                redis.simulateExternalMessage(channel, HuntBossBarCodec.codec.encode(snapshot(time)), "spawn")
                scheduler.executeImmediate()
                shown.size shouldBe 2
            }
            verify(exactly = 1) { player.hideBossBar(shown.last()) }
        } finally {
            relay.close()
            unmockkStatic(Bukkit::class)
        }
    }

    "the source publishes active presentation heartbeat and an empty shutdown snapshot" {
        var time = 1_000L
        val scheduler = TestTaskScheduler()
        val redis = InMemoryRedis(ServerIdentity { "spawn" })
        val chestType = ChestType.vanilla("unchanged-rewards")
        val world = mockk<World>(relaxed = true)
        val chests = ConcurrentHashMap<Location, PlacedChest>()
        repeat(2) { chests[Location(world, it.toDouble(), 70.0, 0.0)] = PlacedChest(mockk(relaxed = true), chestType) }
        val hunt = ActiveHunt(
            TreasureHuntConfig.simple("spawn", "spawn", chestType).copy(
                bossBar = BossBarConfig(message = "<gold>Осталось %left%", color = BossBar.Color.YELLOW),
            ),
            world, chests, 6, 500L,
        )
        mockkStatic(Bukkit::class)
        every { Bukkit.getOnlinePlayers() } returns emptyList()
        val relay = TreasureHuntBossBarRelay(redis, scheduler, "spawn", TreasureHuntBossBarRelaySettings(), { time })
        try {
            relay.onHuntsChanged(listOf(hunt))
            val first = HuntBossBarCodec.codec.decode(redis.getPublishedMessages().last().message)
            first.hunts.single().message shouldBe "<gold>Осталось 2"
            first.hunts.single().remaining shouldBe 2
            first.hunts.single().total shouldBe 6
            first.hunts.single().color shouldBe "YELLOW"
            time += 5_000
            scheduler.tick(100)
            redis.getPublishedMessages().size shouldBe 2
            relay.close()
            HuntBossBarCodec.codec.decode(redis.getPublishedMessages().last().message).hunts shouldBe emptyList()
            redis.listenerCount(channel) shouldBe 0
            scheduler.timerCount() shouldBe 0
        } finally {
            relay.close()
            unmockkStatic(Bukkit::class)
        }
    }

    "a delayed old shutdown cannot hide the replacement generation at the same wall clock time" {
        val time = 1_000L
        val player = mockk<Player>(relaxed = true)
        every { player.world.name } returns "survival"
        every { player.isOnline } returns true
        val shown = mutableListOf<BossBar>()
        every { player.showBossBar(capture(shown)) } returns Unit
        mockkStatic(Bukkit::class)
        every { Bukkit.getOnlinePlayers() } returns listOf(player)
        val senderRedis = InMemoryRedis(ServerIdentity { "spawn" })
        val receiverRedis = InMemoryRedis(ServerIdentity { "survival" })
        val senderScheduler = TestTaskScheduler()
        val receiverScheduler = TestTaskScheduler()
        val settings = TreasureHuntBossBarRelaySettings()
        val receiver = TreasureHuntBossBarRelay(receiverRedis, receiverScheduler, "survival", settings, { time })
        val original = TreasureHuntBossBarRelay(senderRedis, senderScheduler, "spawn", settings, { time })
        val chestType = ChestType.vanilla("unchanged-rewards")
        val world = mockk<World>(relaxed = true)
        val chests = ConcurrentHashMap<Location, PlacedChest>()
        chests[Location(world, 0.0, 70.0, 0.0)] = PlacedChest(mockk(relaxed = true), chestType)
        val hunt = ActiveHunt(TreasureHuntConfig.simple("spawn", "spawn", chestType), world, chests, 1, 500L)
        fun deliver(message: InMemoryRedis.PublishedMessage) {
            receiverRedis.simulateExternalMessage(message.channel, message.message, message.originServer)
            receiverScheduler.executeImmediate()
        }
        try {
            original.onHuntsChanged(listOf(hunt))
            deliver(senderRedis.getPublishedMessages().last())
            val bar = shown.single()
            original.close()
            val delayedStop = senderRedis.getPublishedMessages().last()
            HuntBossBarCodec.codec.decode(delayedStop.message).hunts shouldBe emptyList()

            TreasureHuntBossBarRelay(
                senderRedis, senderScheduler, "spawn", settings, { time },
                initialLastSentAt = original.lastPublishedAt,
            ).use { replacement ->
                replacement.onHuntsChanged(listOf(hunt))
                val active = senderRedis.getPublishedMessages().last()
                (HuntBossBarCodec.codec.decode(active.message).sentAtMillis >
                    HuntBossBarCodec.codec.decode(delayedStop.message).sentAtMillis) shouldBe true
                deliver(active)
                deliver(delayedStop)

                shown shouldBe listOf(bar)
                verify(exactly = 0) { player.hideBossBar(bar) }
            }
        } finally {
            original.close()
            receiver.close()
            unmockkStatic(Bukkit::class)
        }
        senderScheduler.timerCount() shouldBe 0
        receiverScheduler.timerCount() shouldBe 0
    }

    "wire validation rejects invalid progress unknown fields and oversized lists" {
        val encoded = HuntBossBarCodec.codec.encode(snapshot(1_000))
        HuntBossBarCodec.codec.decode(encoded) shouldBe snapshot(1_000)
        shouldThrowAny { HuntBossBarCodec.codec.decode(encoded.dropLast(1) + ",\"command\":\"stop\"}") }
        shouldThrowAny { HuntBossBarCodec.codec.encode(snapshot(1_000, listOf(state().copy(remaining = 7)))) }
        shouldThrowAny { HuntBossBarCodec.codec.encode(snapshot(1_000, listOf(state().copy(total = 0)))) }
        shouldThrowAny { HuntBossBarCodec.codec.encode(snapshot(1_000, List(33) { state().copy(id = "$it") })) }
    }
})
