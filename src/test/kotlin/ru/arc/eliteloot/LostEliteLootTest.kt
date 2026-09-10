package ru.arc.eliteloot

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import net.kyori.adventure.text.Component
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.entity.Item
import org.bukkit.entity.Player
import org.bukkit.event.player.PlayerDropItemEvent
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
        every { entity.thrower } returns null
        every { entity.itemStack } returns stack
        every { entity.persistentDataContainer } returns player.persistentDataContainer
        return entity
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
