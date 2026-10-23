package ru.arc.eliteloot

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import net.kyori.adventure.text.Component
import org.bukkit.Chunk
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.entity.Item
import org.bukkit.entity.Player
import org.bukkit.event.entity.EntityPickupItemEvent
import org.bukkit.event.entity.ItemSpawnEvent
import org.bukkit.event.player.PlayerDropItemEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.world.EntitiesLoadEvent
import org.bukkit.event.world.EntitiesUnloadEvent
import org.bukkit.event.world.WorldUnloadEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import ru.arc.core.BoundedAsyncExecutor
import ru.arc.core.BudgetedTaskQueue
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.Tasks
import ru.arc.core.TestTaskScheduler
import ru.arc.paper.api.ArcNativeCleanupService
import ru.arc.paper.chunk.PaperChunkKey
import ru.arc.paper.chunk.PaperChunkTicketAddResult
import ru.arc.paper.chunk.PaperChunkTicketBackend
import ru.arc.paper.chunk.PaperChunkTicketRegistry
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.function.BooleanSupplier

class LostEliteLootTest : FreeSpec({
    lateinit var paper: MockBukkitTestRuntime
    lateinit var root: Path
    lateinit var scheduler: TestTaskScheduler
    lateinit var scope: LifecycleTaskScope
    lateinit var native: BudgetedTaskQueue
    lateinit var cleanupScope: LifecycleTaskScope
    lateinit var cleanupQueue: BudgetedTaskQueue
    lateinit var cleanup: ArcNativeCleanupService
    lateinit var protection: LostLootProtection
    lateinit var plugin: org.bukkit.plugin.Plugin
    val loots = mutableListOf<LostEliteLoot>()
    val openTickets = mutableSetOf<PaperChunkKey>()

    fun tickUntil(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8)
        while (!condition() && System.nanoTime() < deadline) { scheduler.tick(); Thread.sleep(1) }
        condition() shouldBe true
    }
    beforeEach {
        paper = MockBukkitTestRuntime.open()
        scheduler = TestTaskScheduler(); Tasks.install(scheduler)
        scope = LifecycleTaskScope(scheduler); native = BudgetedTaskQueue(scope, maximumSteps = 128, nanoTime = { 0 })
        cleanupScope = LifecycleTaskScope(scheduler); cleanupQueue = BudgetedTaskQueue(cleanupScope, nanoTime = { 0 })
        cleanup = object : ArcNativeCleanupService {
            override fun submit(owner: String, step: BooleanSupplier) = cleanupQueue.submit({ true }) { step.asBoolean }.thenApply<Void> { null }
        }
        plugin = paper.createSimplePlugin("LostLootConsumer")
        val host = paper.createSimplePlugin("ArcCoreLootFixture")
        protection = LostLootProtection()
        paper.server.pluginManager.registerEvents(protection, host)
        root = Files.createTempDirectory("lost-elite-loot-test")
    }
    afterEach {
        val drains = loots.map { it.closeAsync() }
        tickUntil { drains.all { it.isDone } }
        drains.forEach { it.get(5, TimeUnit.SECONDS) }
        loots.clear(); openTickets.clear()
        native.close(); scope.close(); cleanupQueue.close(); cleanupScope.close()
        Tasks.reset(); paper.close(); root.toFile().deleteRecursively()
    }

    fun item(player: Player): ItemStack = ItemStack.of(Material.DIAMOND_HELMET).also {
        it.editMeta { meta ->
            meta.displayName(Component.text("Добыча босса"))
            meta.persistentDataContainer.set(lostLootSoulbindKey, PersistentDataType.STRING, player.uniqueId.toString())
            meta.persistentDataContainer.set(lostLootSourceKey, PersistentDataType.STRING, "Выпало с Босс")
        }
    }
    fun entity(player: Player, stack: ItemStack = item(player), id: UUID = UUID.randomUUID(), beforeRemove: () -> Unit = {}): Item {
        player.world.getChunkAt(player.location).load()
        val entity = mockk<Item>(relaxed = true)
        var dead = false
        every { entity.uniqueId } returns id
        every { entity.isValid } answers { !dead }
        every { entity.isDead } answers { dead }
        every { entity.remove() } answers { beforeRemove(); dead = true }
        every { entity.thrower } returns null
        every { entity.itemStack } returns stack
        every { entity.persistentDataContainer } returns ItemStack.of(Material.STONE).itemMeta.persistentDataContainer
        every { entity.world } answers { player.world }
        every { entity.location } returns player.location.clone()
        return entity
    }
    fun chunk(player: Player): Chunk = mockk<Chunk>(relaxed = true) {
        every { world } returns player.world
        every { x } returns (player.location.blockX shr 4)
        every { z } returns (player.location.blockZ shr 4)
    }
    fun service(
        open: () -> LostLootJournal = { LostLootStore(root) },
        publish: (LostLootRecord) -> CompletableFuture<Unit> = { CompletableFuture.completedFuture(Unit) },
        worker: BoundedAsyncExecutor = BoundedAsyncExecutor("lost-loot-outbox"),
    ): Pair<LostEliteLoot, LostLootOutbox> {
        val outbox = LostLootOutbox(open, publish, worker)
        val tickets = PaperChunkTicketRegistry(object : PaperChunkTicketBackend {
            override fun add(key: PaperChunkKey): PaperChunkTicketAddResult { openTickets.add(key); return PaperChunkTicketAddResult.ADDED }
            override fun remove(key: PaperChunkKey) = openTickets.remove(key)
        })
        val loot = LostEliteLoot(paper.server, outbox, native, cleanup, tickets, protection,
            isElite = { true }, mobSource = { "Выпало с \$mob" }, nativePrice = { 12.5 })
        loots.add(loot)
        paper.server.pluginManager.registerEvents(loot, plugin)
        loot.start()
        return loot to outbox
    }
    fun ready(loot: LostEliteLoot) { tickUntil { loot.ready().isDone }; loot.ready().get(1, TimeUnit.SECONDS) }

    "failed journal startup protects only bound native loot and manual drops stay excluded" {
        val player = paper.addPlayer("startup-owner")
        val (loot, _) = service(open = { error("injected unreadable journal") })
        val bound = entity(player)
        paper.callEvent(EntityPickupItemEvent(player, bound, 0)).isCancelled shouldBe true
        paper.callEvent(EntityPickupItemEvent(player, entity(player, ItemStack.of(Material.STONE)), 0)).isCancelled shouldBe false
        paper.callEvent(PlayerDropItemEvent(player, bound))
        paper.callEvent(EntityPickupItemEvent(player, bound, 0)).isCancelled shouldBe false
        loot.ready().isCompletedExceptionally shouldBe true
    }

    "blocked capture keeps physical ownership frozen and close does not wait on IO" {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val player = paper.addPlayer("blocked-owner")
        val rows = object : MemoryLostLootJournal() {
            override fun write(record: LostLootRecord): LostLootRecord {
                entered.countDown(); check(release.await(5, TimeUnit.SECONDS)); return super.write(record)
            }
        }
        val (loot, _) = service(open = { rows })
        ready(loot)
        val drop = entity(player)
        loot.capture(drop)
        tickUntil { entered.count == 0L }
        try {
            verify(exactly = 0) { drop.remove() }
            drop.persistentDataContainer.has(lostLootCaptureKey) shouldBe true
            paper.callEvent(EntityPickupItemEvent(player, drop, 0)).isCancelled shouldBe true
            val closing = loot.closeAsync()
            closing.isDone shouldBe false
            // Bukkit removes consumer listeners, while the host's O(1) continuity guard survives.
            org.bukkit.event.HandlerList.unregisterAll(plugin)
            paper.callEvent(EntityPickupItemEvent(player, entity(player, id = drop.uniqueId), 0)).isCancelled shouldBe true
        } finally { release.countDown() }
        tickUntil { loot.closeAsync().isDone }
        rows.get(drop.uniqueId.toString())?.nativePrice shouldBe 12.5
        openTickets.size shouldBe 0
        verify(exactly = 0) { drop.remove() } // No late native mutation from the stopped generation.
    }

    "capture commits the exact stack before removal and retained UUID suppresses a stale reload" {
        val player = paper.addPlayer("exact-owner")
        val exported = CompletableFuture<Unit>()
        val (loot, outbox) = service(publish = { exported })
        ready(loot)
        val stack = item(player)
        val id = UUID.randomUUID()
        val drop = entity(player, stack, id) {
            val record = requireNotNull(LostLootStore(root).get(id.toString()))
            ItemStack.deserializeBytes(Base64.getDecoder().decode(record.item)) shouldBe stack
        }
        try {
            loot.capture(drop)
            tickUntil { drop.isDead }
            outbox.entry(id.toString())?.certain shouldBe true
            verify(exactly = 1) { drop.remove() }
            val stale = entity(player, id = id)
            paper.callEvent(EntitiesLoadEvent(chunk(player), listOf(stale)))
            paper.callEvent(EntityPickupItemEvent(player, stale, 0)).isCancelled shouldBe true
            tickUntil { stale.isDead }
            verify(exactly = 1) { stale.remove() }
        } finally { exported.complete(Unit) }
    }

    "late entity unload never serializes an inaccessible object and cancels queued old entity work" {
        val player = paper.addPlayer("late-unload")
        val (loot, outbox) = service()
        ready(loot)
        val drop = entity(player)
        paper.callEvent(EntitiesLoadEvent(chunk(player), listOf(drop)))
        paper.callEvent(EntitiesUnloadEvent(chunk(player), listOf(drop)))
        every { drop.isValid } returns false
        scheduler.tick(30)
        verify(exactly = 0) { drop.remove(); drop.itemStack }
        outbox.entry(drop.uniqueId.toString()) shouldBe null
    }

    "write failure keeps uncertainty and retries the original payload after recovery" {
        val player = paper.addPlayer("disk-failure")
        val worker = BoundedAsyncExecutor("lost-loot-retry-test")
        val (loot, outbox) = service(worker = worker)
        ready(loot)
        val drop = entity(player)
        val obstruction = root.resolve("data/lost-elite-loot/${drop.uniqueId}.json")
        Files.createDirectories(obstruction)
        loot.capture(drop)
        tickUntil { outbox.entry(drop.uniqueId.toString()) != null }
        // The uncertainty index is published before the failed write completes.
        worker.submit {}.get(5, TimeUnit.SECONDS)
        scheduler.tick(5)
        verify(exactly = 0) { drop.remove() }
        outbox.entry(drop.uniqueId.toString())?.certain shouldBe false
        Files.delete(obstruction)
        loot.capture(drop)
        tickUntil { drop.isDead }
        outbox.entry(drop.uniqueId.toString())?.certain shouldBe true
    }

    "distance collection and departure preserve nearby and manually dropped items" {
        val player = paper.addPlayer("departing-owner")
        val neighbour = paper.addPlayer("staying-owner")
        val (loot, outbox) = service()
        ready(loot)
        val mine = entity(player); val theirs = entity(neighbour); val manual = entity(player)
        listOf(mine, theirs, manual).forEach { paper.callEvent(ItemSpawnEvent(it)) }
        paper.callEvent(PlayerDropItemEvent(player, manual))
        scheduler.tick(20)
        verify(exactly = 0) { mine.remove(); theirs.remove(); manual.remove() }
        paper.callEvent(PlayerQuitEvent(player, Component.empty()))
        tickUntil { mine.isDead }
        verify(exactly = 0) { theirs.remove(); manual.remove() }
        outbox.entry(mine.uniqueId.toString())?.record?.owner shouldBe player.uniqueId.toString()
    }

    "world unload is cancelled until sliced capture and its durable write finish" {
        val player = paper.addPlayer("world-unload")
        val (loot, _) = service()
        ready(loot)
        val drop = entity(player)
        paper.callEvent(ItemSpawnEvent(drop))
        val first = WorldUnloadEvent(player.world)
        loot.worldUnloading(first)
        first.isCancelled shouldBe true
        tickUntil { drop.isDead }
        var allowed = false
        tickUntil {
            val retry = WorldUnloadEvent(player.world)
            loot.worldUnloading(retry)
            allowed = !retry.isCancelled
            allowed
        }
        allowed shouldBe true
        LostLootStore(root).get(drop.uniqueId.toString())?.owner shouldBe player.uniqueId.toString()
    }

    "large stale entity loads share a native budget and close cancels partial reconciliation" {
        val player = paper.addPlayer("bulk-stale")
        val rows = MemoryLostLootJournal()
        val drops = List(300) {
            entity(player).also { item -> rows.write(LostLootRecord(item.uniqueId.toString(), player.uniqueId.toString(), null, 1234, LostLootState.EXPORTED)) }
        }
        val (loot, _) = service(open = { rows })
        ready(loot)
        var removed = 0
        for (drop in drops) every { drop.remove() } answers { removed++; Unit }
        paper.callEvent(EntitiesLoadEvent(chunk(player), drops))
        removed shouldBe 0
        var previous = 0
        repeat(3) {
            scheduler.tick()
            (removed - previous <= 128) shouldBe true
            previous = removed
        }
        (removed < 300) shouldBe true
        loot.close()
        val atClose = removed
        scheduler.tick(10)
        removed shouldBe atClose
    }

    "failed startup releases ordinary loot but keeps bound feature loot protected" {
        val player = paper.addPlayer("failed-startup")
        val (loot, _) = service(open = { error("corrupt journal") })
        tickUntil { loot.ready().isDone }
        loot.ready().isCompletedExceptionally shouldBe true
        paper.callEvent(EntityPickupItemEvent(player, entity(player), 0)).isCancelled shouldBe true
        paper.callEvent(EntityPickupItemEvent(player, entity(player, ItemStack.of(Material.STONE)), 0)).isCancelled shouldBe false
    }
})
