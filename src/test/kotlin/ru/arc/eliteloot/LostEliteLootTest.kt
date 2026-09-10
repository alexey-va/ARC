package ru.arc.eliteloot

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import net.kyori.adventure.text.Component
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.Chunk
import org.bukkit.entity.Item
import org.bukkit.entity.Player
import org.bukkit.event.entity.ItemSpawnEvent
import org.bukkit.event.player.PlayerDropItemEvent
import org.bukkit.event.world.EntitiesLoadEvent
import org.bukkit.event.world.EntitiesUnloadEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CompletableFuture

class LostEliteLootTest : FreeSpec({
    lateinit var paper: MockBukkitTestRuntime
    lateinit var root: Path
    lateinit var scheduler: ru.arc.core.TestTaskScheduler
    val ownerKey = NamespacedKey("elitemobs", "soulbind")
    val sourceKey = NamespacedKey("elitemobs", "itemsource")

    beforeEach { scheduler = ru.arc.core.TestTaskScheduler(); ru.arc.core.Tasks.install(scheduler); paper = MockBukkitTestRuntime.open(); root = Files.createTempDirectory("lost-elite-loot-test") }
    afterEach { ru.arc.core.Tasks.reset(); paper.close(); root.toFile().deleteRecursively() }

    fun item(player: Player): ItemStack = ItemStack.of(Material.DIAMOND_HELMET).also {
        it.editMeta { meta ->
            meta.displayName(Component.text("Добыча босса"))
            meta.persistentDataContainer.set(ownerKey, PersistentDataType.STRING, player.uniqueId.toString())
            meta.persistentDataContainer.set(sourceKey, PersistentDataType.STRING, "Выпало с Босс")
        }
    }

    fun service(publish: (LostLootRecord) -> CompletableFuture<Unit> = { CompletableFuture.completedFuture(Unit) }) = LostEliteLoot(
        root, publish, isElite = { true }, mobSource = { "Выпало с \$mob" }, nativePrice = { 12.5 },
    )

    fun entity(player: Player, stack: ItemStack = item(player), id: UUID = UUID.randomUUID()): Item {
        val entity = mockk<Item>(relaxed = true)
        every { entity.uniqueId } returns id
        every { entity.isValid } returns true
        every { entity.isDead } returns false
        every { entity.thrower } returns null
        every { entity.itemStack } returns stack
        every { entity.persistentDataContainer } returns ItemStack.of(Material.STONE).itemMeta.persistentDataContainer
        every { entity.world } answers { player.world }
        every { entity.location } returns player.location.clone()
        return entity
    }

    fun listen(loot: LostEliteLoot) {
        paper.server.pluginManager.registerEvents(loot, paper.createSimplePlugin("LostLootTest"))
        loot.start()
    }

    fun unloading(vararg items: Item) = EntitiesUnloadEvent(mockk<Chunk>(relaxed = true), items.toList())

    "chunk entity unload commits inactive but living loot before removing it and suppresses a stale reload" {
        val player = paper.addPlayer("chunk-owner")
        val loot = service()
        listen(loot)
        val stack = item(player)
        val drop = entity(player, stack)
        // Purpur makes entities inaccessible before emitting EntitiesUnloadEvent.
        every { drop.isValid } returns false
        every { drop.remove() } answers {
            val saved = requireNotNull(LostLootStore(root).get(drop.uniqueId.toString()))
            ItemStack.deserializeBytes(Base64.getDecoder().decode(saved.item)) shouldBe stack
            saved.owner shouldBe player.uniqueId.toString()
            saved.nativePrice shouldBe 12.5
        }
        paper.callEvent(unloading(drop))
        verify(exactly = 1) { drop.remove() }
        loot.store.pending(10).size shouldBe 1
        val stale = entity(player, id = drop.uniqueId)
        paper.callEvent(EntitiesLoadEvent(mockk<Chunk>(relaxed = true), listOf(stale)))
        verify(exactly = 1) { stale.remove() }
        loot.store.pending(10).size shouldBe 1
        loot.close()
    }

    "chunk unload keeps excluded drops on the ground" {
        val player = paper.addPlayer("excluded-owner")
        val loot = service()
        listen(loot)
        val manual = entity(player)
        paper.callEvent(PlayerDropItemEvent(player, manual))
        val unbound = entity(player, item(player).also { it.editMeta { meta -> meta.persistentDataContainer.remove(ownerKey) } })
        val bought = entity(player, item(player).also {
            it.editMeta { meta -> meta.persistentDataContainer.set(NamespacedKey("arc", "dungeon_case_reward"), PersistentDataType.BYTE, 1) }
        })
        val ordinary = entity(player, ItemStack.of(Material.STONE))
        paper.callEvent(unloading(manual, unbound, bought, ordinary))
        listOf(manual, unbound, bought, ordinary).forEach { drop -> verify(exactly = 0) { drop.remove() } }
        loot.store.pending(10) shouldBe emptyList()
        loot.close()
    }

    "failed chunk capture retains the entity and retries the persisted capture marker on load" {
        val player = paper.addPlayer("chunk-disk-failure")
        val loot = service()
        listen(loot)
        val drop = entity(player)
        every { drop.isValid } returns false
        val obstruction = root.resolve("data/lost-elite-loot/${drop.uniqueId}.json")
        Files.createDirectories(obstruction)
        paper.callEvent(unloading(drop))
        verify(exactly = 0) { drop.remove() }
        loot.store.pending(10) shouldBe emptyList()
        val marker = NamespacedKey("arc", "lost_loot_capture")
        drop.persistentDataContainer.has(marker) shouldBe true
        Files.delete(obstruction)
        val loaded = entity(player, id = drop.uniqueId)
        loaded.persistentDataContainer.set(marker, PersistentDataType.BYTE, 1)
        paper.callEvent(EntitiesLoadEvent(mockk<Chunk>(relaxed = true), listOf(loaded)))
        scheduler.tick()
        verify(exactly = 1) { loaded.remove() }
        loot.store.pending(10).size shouldBe 1
        loot.close()
    }

    "distance collection leaves nearby loot and captures once its owner passes 64 blocks" {
        val player = paper.addPlayer("distant-owner")
        val loot = service()
        listen(loot)
        val stack = ItemStack.of(Material.DIAMOND_HELMET)
        val drop = entity(player, stack)
        paper.callEvent(ItemSpawnEvent(drop))
        // Native EliteMobs metadata is attached after the spawn event returns.
        stack.itemMeta = item(player).itemMeta
        player.teleport(player.location.clone().add(64.0, 0.0, 0.0))
        scheduler.tick(20)
        verify(exactly = 0) { drop.remove() }
        player.teleport(player.location.clone().add(1.0, 0.0, 0.0))
        scheduler.tick(20)
        verify(exactly = 1) { drop.remove() }
        loot.store.pending(10).size shouldBe 1
        loot.close()
    }

    "leaving the server collects only the departing owner's tracked mob drops" {
        val player = paper.addPlayer("departing-owner")
        val neighbour = paper.addPlayer("staying-owner")
        val loot = service()
        listen(loot)
        val mine = entity(player)
        val theirs = entity(neighbour)
        val manual = entity(player)
        listOf(mine, theirs, manual).forEach { paper.callEvent(ItemSpawnEvent(it)) }
        paper.callEvent(PlayerDropItemEvent(player, manual))
        paper.callEvent(org.bukkit.event.player.PlayerQuitEvent(player, Component.empty()))
        scheduler.tick()
        verify(exactly = 1) { mine.remove() }
        verify(exactly = 0) { theirs.remove(); manual.remove() }
        loot.store.pending(10).map { it.owner } shouldBe listOf(player.uniqueId.toString())
        loot.close()
    }

    "changing worlds collects tracked loot and pickup before collection cannot create a mailbox copy" {
        val player = paper.addPlayer("world-traveller")
        val loot = service()
        listen(loot)
        val oldWorld = player.world
        val drop = entity(player)
        val pickedUp = entity(player)
        every { drop.world } returns oldWorld
        every { pickedUp.world } returns oldWorld
        listOf(drop, pickedUp).forEach { paper.callEvent(ItemSpawnEvent(it)) }
        every { pickedUp.isDead } returns true
        player.teleport(paper.addSimpleWorld("destination").spawnLocation)
        scheduler.tick(20)
        verify(exactly = 1) { drop.remove() }
        verify(exactly = 0) { pickedUp.remove() }
        loot.store.pending(10).size shouldBe 1
        loot.close()
    }

    "loaded mob loot for an offline owner is collected without another disconnect event" {
        val player = paper.addPlayer("offline-fixture")
        val owner = UUID.randomUUID()
        val stack = item(player).also {
            it.editMeta { meta -> meta.persistentDataContainer.set(ownerKey, PersistentDataType.STRING, owner.toString()) }
        }
        val loot = service()
        listen(loot)
        val drop = entity(player, stack)
        paper.callEvent(EntitiesLoadEvent(mockk<Chunk>(relaxed = true), listOf(drop)))
        scheduler.tick(20)
        verify(exactly = 1) { drop.remove() }
        loot.store.pending(10).map { it.owner } shouldBe listOf(owner.toString())
        loot.close()
    }

    "world unload is refused if committing an inactive living item fails" {
        val player = paper.addPlayer("world-write-failure")
        val loot = service()
        val drop = entity(player)
        every { drop.isValid } returns false
        val world = mockk<org.bukkit.World>()
        every { world.getEntitiesByClass(Item::class.java) } returns listOf(drop)
        Files.createDirectories(root.resolve("data/lost-elite-loot/${drop.uniqueId}.json"))
        val event = org.bukkit.event.world.WorldUnloadEvent(world)
        loot.worldUnloading(event)
        event.isCancelled shouldBe true
        verify(exactly = 0) { drop.remove() }
        loot.store.pending(10) shouldBe emptyList()
        loot.close()
    }

    "only canonical bound mob loot qualifies and a deliberate throw permanently excludes that entity" {
        val player = paper.addPlayer("owner")
        val stack = item(player)
        val entity = entity(player, stack)
        val loot = service()
        loot.owner(entity) shouldBe player.uniqueId
        stack.editMeta { it.persistentDataContainer.set(NamespacedKey("arc", "dungeon_case_reward"), PersistentDataType.BYTE, 1) }
        loot.owner(entity) shouldBe null
        stack.editMeta { it.persistentDataContainer.remove(NamespacedKey("arc", "dungeon_case_reward")); it.persistentDataContainer.remove(ownerKey) }
        loot.owner(entity) shouldBe null
        loot.manuallyDropped(PlayerDropItemEvent(player, entity))
        loot.owner(entity) shouldBe null
        loot.close()
    }

    "failed journal commit keeps the entity frozen and unavailable until a verified retry" {
        val player = paper.addPlayer("disk-failure")
        val loot = service()
        val id = UUID.randomUUID()
        val entity = entity(player, id = id)
        val obstruction = root.resolve("data/lost-elite-loot/$id.json")
        Files.createDirectories(obstruction)
        loot.despawn(org.bukkit.event.entity.ItemDespawnEvent(entity, player.location))
        loot.capture(entity)
        verify(exactly = 0) { entity.remove() }
        loot.store.available(requireNotNull(loot.store.get(id.toString()))) shouldBe false
        Files.delete(obstruction)
        loot.capture(entity)
        verify(exactly = 1) { entity.remove() }
        loot.store.available(requireNotNull(loot.store.get(id.toString()))) shouldBe true
        loot.close()
    }

    "world unload captures remaining mob loot before an instance can be deleted" {
        val player = paper.addPlayer("unload-owner")
        val loot = service()
        val entity = entity(player)
        val world = mockk<org.bukkit.World>()
        var valid = true
        every { entity.isValid } answers { valid }
        every { entity.isDead } answers { !valid }
        every { entity.remove() } answers { valid = false }
        every { world.getEntitiesByClass(Item::class.java) } returns listOf(entity)
        val event = org.bukkit.event.world.WorldUnloadEvent(world)
        loot.worldUnloading(event)
        event.isCancelled shouldBe false
        valid shouldBe false
        loot.store.get(entity.uniqueId.toString())?.state shouldBe LostLootState.STORED
        loot.close()
    }

    "capture commits the exact stack before removing its physical entity" {
        val player = paper.addPlayer("drop-owner")
        val stack = item(player)
        val loot = service()
        val id = UUID.randomUUID()
        val entity = entity(player, stack, id)
        every { entity.remove() } answers {
            val saved = requireNotNull(LostLootStore(root).get(id.toString()))
            ItemStack.deserializeBytes(Base64.getDecoder().decode(saved.item)) shouldBe stack
        }
        loot.capture(entity)
        verify(exactly = 1) { entity.remove() }
        loot.close()
    }

    "publish retries after failure and exports an idempotent tombstone" {
        val player = paper.addPlayer("publisher")
        var attempts = 0
        val loot = service {
            attempts++
            if (attempts == 1) CompletableFuture.failedFuture(IllegalStateException("temporary"))
            else CompletableFuture.completedFuture(Unit)
        }
        val entity = entity(player)
        loot.capture(entity)
        loot.publishPending()
        scheduler.executeImmediate()
        attempts shouldBe 1
        loot.publishPending()
        scheduler.executeImmediate()
        attempts shouldBe 2
        loot.store.get(entity.uniqueId.toString())?.state shouldBe LostLootState.EXPORTED
        loot.store.get(entity.uniqueId.toString())?.item shouldBe null
        loot.publishPending()
        scheduler.executeImmediate()
        attempts shouldBe 2
        loot.close()
    }
})
