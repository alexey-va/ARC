package ru.arc.sync

import com.google.gson.Gson
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import net.kyori.adventure.text.Component
import org.bukkit.entity.Player
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.plugin.Plugin
import ru.arc.core.TestTaskScheduler
import ru.arc.dungeons.api.ArcPlayerSaveService
import ru.arc.paper.api.ArcRedisDiagnostics
import ru.arc.paper.api.ArcRedisLease
import ru.arc.paper.api.ArcRedisListener
import ru.arc.paper.api.ArcRedisService
import ru.arc.paper.api.ArcRedisSettings
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.util.UUID
import java.util.function.Consumer
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

class EmSyncLifecycleTest : FreeSpec({
    "compact EmData payload keys and default values remain stable" {
        val dto = EmSync.EmDataDTO(
            id = UUID.randomUUID(),
            skillXP = longArrayOf(1L, 2L),
            skillBonusSelections = "bonus",
            lostLootCreditReceipt = "credit-token",
        )
        val json = Gson().toJson(dto)

        json.contains("\"t\"") shouldBe true
        json.contains("\"s\"") shouldBe true
        json.contains("\"u\"") shouldBe true
        json.contains("\"c\"") shouldBe true
        json.contains("\"sx\"") shouldBe true
        json.contains("\"sb\"") shouldBe true
        json.contains("\"pl\"") shouldBe true
        json.contains("\"qc\"") shouldBe true
        json.contains("\"lr\":\"credit-token\"") shouldBe true
        json.contains("\"currency\"") shouldBe false
        EmSync.EmDataDTO().skillXP shouldBe null
        EmSync.EmDataDTO().questsCompleted shouldBe 0
    }

    "EmData payload round-trips the legacy fields and skill XP array" {
        val original = EmSync.EmDataDTO(
            ts = 12345L,
            srv = "test-server",
            id = UUID.randomUUID(),
            currency = 999.99,
            highestLevelKilled = 42,
            score = 100,
            kills = 20,
            deaths = 5,
            useBookMenu = true,
            dismissEmStatus = false,
            playerLevel = 7,
            skillXP = longArrayOf(1000L, 2000L, 3000L),
            skillBonusSelections = "sword:crit,armor:tough",
            gamblingDebt = 250.5,
            questsCompleted = 15,
            lostLootCreditReceipt = "credit-round-trip",
        )
        val restored = Gson().fromJson(Gson().toJson(original), EmSync.EmDataDTO::class.java)

        restored.ts shouldBe original.ts
        restored.srv shouldBe original.srv
        restored.id shouldBe original.id
        restored.currency shouldBe original.currency
        restored.highestLevelKilled shouldBe original.highestLevelKilled
        restored.score shouldBe original.score
        restored.kills shouldBe original.kills
        restored.deaths shouldBe original.deaths
        restored.useBookMenu shouldBe original.useBookMenu
        restored.dismissEmStatus shouldBe original.dismissEmStatus
        restored.playerLevel shouldBe original.playerLevel
        restored.skillXP?.toList() shouldBe original.skillXP?.toList()
        restored.skillBonusSelections shouldBe original.skillBonusSelections
        restored.gamblingDebt shouldBe original.gamblingDebt
        restored.questsCompleted shouldBe original.questsCompleted
        restored.lostLootCreditReceipt shouldBe original.lostLootCreditReceipt
    }

    "quit snapshots the receipt before clearing readiness and receipt state" {
        Fixture().use { fixture ->
            val player = fixture.paper.addPlayer("ReceiptQuit")
            fixture.startReady(player)
            fixture.sync.markLootCredit(player.uniqueId, "credit-quit-1")
            val pendingSave = CompletableFuture<Any?>()
            fixture.lease.queuedSaves += pendingSave

            fixture.paper.callEvent(PlayerQuitEvent(player, Component.empty()))

            fixture.lease.writes.size shouldBe 1
            Gson().fromJson(fixture.lease.writes.single().payload, EmSync.EmDataDTO::class.java)
                .lostLootCreditReceipt shouldBe "credit-quit-1"
            fixture.sync.isReady(player.uniqueId) shouldBe false
            fixture.sync.lootCreditReceipt(player.uniqueId) shouldBe null

            pendingSave.complete(Unit)
            fixture.closeSync()
        }
    }

    "shared round-robin callback saves ready players and unregisters before final snapshots" {
        Fixture().use { fixture ->
            val player = fixture.paper.addPlayer("SharedSaveCallback")
            fixture.startReady(player)

            fixture.saveService.registeredOwner shouldBe fixture.plugin
            fixture.saveService.closed shouldBe false
            fixture.scheduler.timerCount() shouldBe 0

            fixture.saveService.snapshot?.accept(player)
            fixture.lease.writes.size shouldBe 1

            fixture.sync.close()
            fixture.sync.closeCompletion().get(2, TimeUnit.SECONDS)
            fixture.saveService.closed shouldBe true
            fixture.lifecycleEvents.takeLast(2) shouldBe listOf("unregister", "snapshot")
            fixture.sync.closeCompletion().isDone shouldBe true
        }
    }

    "per-player save tails preserve receipt snapshots and close waits asynchronously for accepted writes" {
        Fixture().use { fixture ->
            val player = fixture.paper.addPlayer("ReceiptTail")
            fixture.startReady(player)
            val firstWrite = CompletableFuture<Any?>()
            val secondWrite = CompletableFuture<Any?>()
            fixture.lease.queuedSaves += firstWrite
            fixture.lease.queuedSaves += secondWrite

            fixture.sync.markLootCredit(player.uniqueId, "credit-first")
            val first = fixture.sync.persist(player)
            fixture.sync.markLootCredit(player.uniqueId, "credit-second")
            val second = fixture.sync.persist(player)

            fixture.lease.writes.size shouldBe 1
            Gson().fromJson(fixture.lease.writes[0].payload, EmSync.EmDataDTO::class.java)
                .lostLootCreditReceipt shouldBe "credit-first"
            firstWrite.complete(Unit)
            fixture.lease.writes.size shouldBe 2
            Gson().fromJson(fixture.lease.writes[1].payload, EmSync.EmDataDTO::class.java)
                .lostLootCreditReceipt shouldBe "credit-second"

            fixture.sync.close()
            fixture.lease.closed shouldBe false
            fixture.sync.closeCompletion().isDone shouldBe false
            fixture.sync.lootCreditReceipt(player.uniqueId) shouldBe null
            secondWrite.complete(Unit)
            fixture.lease.writes.size shouldBe 3
            Gson().fromJson(fixture.lease.writes[2].payload, EmSync.EmDataDTO::class.java)
                .lostLootCreditReceipt shouldBe "credit-second"
            first.get(2, TimeUnit.SECONDS)
            second.get(2, TimeUnit.SECONDS)
            fixture.sync.closeCompletion().get(2, TimeUnit.SECONDS)
            fixture.lease.closed shouldBe true
        }
    }

    "a stale load completing after quit cannot apply or reopen the readiness gate" {
        Fixture().use { fixture ->
            val player = fixture.paper.addPlayer("StaleEmLoad")
            val pendingLoad = CompletableFuture<List<String?>>()
            fixture.lease.nextLoad = pendingLoad
            fixture.state.available += player.uniqueId
            fixture.sync.start()
            fixture.scheduler.tick(5)

            fixture.paper.callEvent(PlayerQuitEvent(player, Component.empty()))
            val stale = EmSync.EmDataDTO(
                ts = 100L,
                srv = "other-server",
                id = player.uniqueId,
                currency = 321.0,
                lostLootCreditReceipt = "old-receipt",
            )
            pendingLoad.complete(listOf(Gson().toJson(stale)))
            fixture.scheduler.executeImmediate()

            fixture.state.applied.size shouldBe 0
            fixture.sync.isReady(player.uniqueId) shouldBe false
            fixture.sync.lootCreditReceipt(player.uniqueId) shouldBe null
            fixture.closeSync()
        }
    }

    "cancelling a caller future does not make an accepted Redis write disappear from shutdown drain" {
        Fixture().use { fixture ->
            val player = fixture.paper.addPlayer("CancelledEmSave")
            fixture.startReady(player)
            val pendingSave = CompletableFuture<Any?>()
            fixture.lease.queuedSaves += pendingSave

            val callerView = fixture.sync.persist(player)
            callerView.cancel(true) shouldBe true
            fixture.sync.close()

            fixture.lease.closed shouldBe false
            fixture.sync.closeCompletion().isDone shouldBe false
            pendingSave.complete(Unit)
            fixture.sync.closeCompletion().get(2, TimeUnit.SECONDS)
            fixture.lease.closed shouldBe true
        }
    }

    "same-server data is skipped unless it carries a lost-loot receipt" {
        Fixture().use { fixture ->
            val player = fixture.paper.addPlayer("SameServerEmLoad")
            fixture.lease.nextLoad = CompletableFuture.completedFuture(
                listOf(Gson().toJson(EmSync.EmDataDTO(srv = "survival", id = player.uniqueId, currency = 321.0))),
            )
            fixture.startReady(player)
            fixture.state.applied.size shouldBe 0
            fixture.sync.isReady(player.uniqueId) shouldBe true
            fixture.closeSync()
        }
        Fixture().use { fixture ->
            val player = fixture.paper.addPlayer("SameServerReceipt")
            fixture.lease.nextLoad = CompletableFuture.completedFuture(
                listOf(
                    Gson().toJson(
                        EmSync.EmDataDTO(
                            srv = "survival",
                            id = player.uniqueId,
                            currency = 321.0,
                            lostLootCreditReceipt = "credit-same-server",
                        ),
                    ),
                ),
            )
            fixture.startReady(player)
            fixture.state.applied.size shouldBe 1
            fixture.sync.lootCreditReceipt(player.uniqueId) shouldBe "credit-same-server"
            fixture.closeSync()
        }
    }
})

private class Fixture : AutoCloseable {
    val paper = MockBukkitTestRuntime.open()
    val plugin = paper.createSimplePlugin("ArcDungeons")
    val scheduler = TestTaskScheduler()
    val lease = FakeArcRedisLease()
    val state = FakeEmSyncPlayerData()
    private val service = FakeArcRedisService(lease)
    val saveService = FakeArcPlayerSaveService()
    val lifecycleEvents = mutableListOf<String>()
    val sync = EmSync(plugin, "survival", service, scheduler, state, saveService)

    init {
        state.onSnapshot = { lifecycleEvents += "snapshot" }
        saveService.onUnregister = { lifecycleEvents += "unregister" }
    }

    fun startReady(player: Player) {
        service.acquiredOwner shouldBe "ArcDungeons-EmSync"
        state.available += player.uniqueId
        sync.start()
        scheduler.tick(5)
        scheduler.executeImmediate()
        sync.isReady(player.uniqueId) shouldBe true
    }

    fun closeSync() {
        sync.close()
        lease.completeSaves()
        sync.closeCompletion().get(2, TimeUnit.SECONDS)
    }

    override fun close() {
        if (!sync.closeCompletion().isDone) closeSync()
        paper.close()
    }
}

private class FakeEmSyncPlayerData : EmSyncPlayerData {
    val available = mutableSetOf<UUID>()
    val applied = mutableListOf<EmSync.EmDataDTO>()
    var currency = 100.0
    var onSnapshot: (() -> Unit)? = null

    override fun isAvailable(uuid: UUID): Boolean = uuid in available

    override fun snapshot(uuid: UUID, serverName: String, receipt: String?): EmSync.EmDataDTO? {
        onSnapshot?.invoke()
        if (!isAvailable(uuid)) return null
        return EmSync.EmDataDTO(
            ts = 1L,
            srv = serverName,
            id = uuid,
            currency = currency,
            skillXP = longArrayOf(10L, 20L, 30L),
            lostLootCreditReceipt = receipt,
        )
    }

    override fun apply(data: EmSync.EmDataDTO) {
        applied += data
        currency = data.currency
    }
}

private class FakeArcPlayerSaveService : ArcPlayerSaveService {
    var registeredOwner: Plugin? = null
    var snapshot: Consumer<Player>? = null
    var closed = true
    var onUnregister: (() -> Unit)? = null

    override fun register(owner: Plugin, snapshot: Consumer<Player>): AutoCloseable {
        registeredOwner = owner
        this.snapshot = snapshot
        closed = false
        return AutoCloseable {
            if (!closed) {
                closed = true
                this.snapshot = null
                onUnregister?.invoke()
            }
        }
    }
}

private data class SavedEntry(val key: String, val field: String?, val payload: String?)

private class FakeArcRedisLease : ArcRedisLease {
    val writes = mutableListOf<SavedEntry>()
    val queuedSaves = ArrayDeque<CompletableFuture<Any?>>()
    private val outstandingSaves = mutableListOf<CompletableFuture<Any?>>()
    var nextLoad: CompletableFuture<List<String?>>? = null
    var closed = false

    override fun saveMapEntries(key: String, vararg keyValuePairs: String?): CompletableFuture<*> {
        writes += SavedEntry(key, keyValuePairs.getOrNull(0), keyValuePairs.getOrNull(1))
        if (queuedSaves.isEmpty()) return CompletableFuture.completedFuture(Unit)
        return queuedSaves.removeFirst().also(outstandingSaves::add)
    }

    override fun loadMapEntries(key: String, vararg mapKeys: String): CompletableFuture<List<String?>> =
        nextLoad.also { nextLoad = null } ?: CompletableFuture.completedFuture(listOf(null))

    fun completeSaves() {
        while (outstandingSaves.isNotEmpty()) {
            val pending = outstandingSaves.toList()
            pending.forEach { it.complete(Unit) }
            outstandingSaves.removeAll(pending.toSet())
        }
    }

    override fun publish(channel: String, message: String) = Unit
    override fun saveMap(key: String, map: Map<String, String>) = Unit
    override fun loadMap(key: String): CompletableFuture<Map<String, String>> = CompletableFuture.completedFuture(emptyMap())
    override fun compareAndSetMapEntry(key: String, mapKey: String, expectedValue: String?, replacementValue: String?) =
        CompletableFuture.completedFuture(false)
    override fun registerChannelUnique(channel: String, listener: ArcRedisListener) = Unit
    override fun unregisterChannel(channel: String, listener: ArcRedisListener) = Unit
    override fun init() = Unit
    override fun healthCheck(): CompletableFuture<Boolean> = CompletableFuture.completedFuture(true)
    override fun isConnected() = !closed
    override fun isSubscriptionActive() = false
    override fun channelCount() = 0
    override fun channels(): Set<String> = emptySet()
    override fun close() { closed = true }
}

private class FakeArcRedisService(private val lease: FakeArcRedisLease) : ArcRedisService {
    var acquiredOwner: String? = null
    override fun acquire(owner: String, settings: ArcRedisSettings, origin: String): ArcRedisLease = lease
    override fun acquirePrimary(owner: String): ArcRedisLease {
        acquiredOwner = owner
        return lease
    }
    override fun diagnostics() = ArcRedisDiagnostics(0, 0, 0, 0)
    override fun close() = Unit
}
