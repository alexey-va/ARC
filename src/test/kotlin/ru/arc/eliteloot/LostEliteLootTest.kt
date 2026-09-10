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
import ru.arc.paper.playerstate.PaperPlayerDataPersistence
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import java.util.UUID

class LostEliteLootTest : FreeSpec({
    lateinit var paper: MockBukkitTestRuntime
    lateinit var root: Path
    val ownerKey = NamespacedKey("elitemobs", "soulbind")
    val sourceKey = NamespacedKey("elitemobs", "itemsource")
    val receiptKey = NamespacedKey("arc", "lost_loot_receipt")

    beforeEach { ru.arc.core.Tasks.install(ru.arc.core.TestTaskScheduler()); paper = MockBukkitTestRuntime.open(); root = Files.createTempDirectory("lost-elite-loot-test") }
    afterEach { ru.arc.core.Tasks.reset(); paper.close(); root.toFile().deleteRecursively() }

    fun item(player: Player): ItemStack = ItemStack.of(Material.DIAMOND_HELMET).also {
        it.editMeta { meta ->
            meta.displayName(Component.text("Добыча босса"))
            meta.persistentDataContainer.set(ownerKey, PersistentDataType.STRING, player.uniqueId.toString())
            meta.persistentDataContainer.set(sourceKey, PersistentDataType.STRING, "Выпало с Босс")
            meta.tooltipStyle = NamespacedKey("lzblocks", "tooltip/rare")
        }
    }
    fun service(persist: PaperPlayerDataPersistence = PaperPlayerDataPersistence {}) = LostEliteLoot(
        root, { _, fallback -> Component.text(fallback) }, persist, isElite = { true }, mobSource = { "Выпало с \$mob" },
    )
    fun record(player: Player, stack: ItemStack) = LostLootRecord(UUID.randomUUID().toString(), player.uniqueId.toString(),
        Base64.getEncoder().encodeToString(stack.serializeAsBytes()), 1000)

    "only canonical bound mob loot qualifies and a deliberate throw permanently excludes that entity" {
        val player = paper.addPlayer("owner")
        var stack = item(player)
        val entity = mockk<Item>(relaxed = true)
        every { entity.itemStack } answers { stack }
        every { entity.thrower } returns null
        every { entity.persistentDataContainer } returns player.persistentDataContainer
        val loot = service()
        loot.owner(entity) shouldBe player.uniqueId
        stack.editMeta { it.persistentDataContainer.set(NamespacedKey("arc", "dungeon_case_reward"), PersistentDataType.BYTE, 1) }
        loot.owner(entity) shouldBe null
        stack = item(player)
        stack.editMeta { it.persistentDataContainer.remove(ownerKey) }
        loot.owner(entity) shouldBe null
        stack = item(player)
        stack.editMeta { it.persistentDataContainer.set(sourceKey, PersistentDataType.STRING, "Куплено") }
        loot.owner(entity) shouldBe null
        stack = item(player)
        loot.manuallyDropped(PlayerDropItemEvent(player, entity))
        loot.owner(entity) shouldBe null
        loot.close()
    }

    "claim saves one intact item and keeps a durable tombstone against stale entity copies and repeat clicks" {
        val player = paper.addPlayer("claimant")
        val stack = item(player)
        val pending = record(player, stack)
        var saves = 0
        val loot = service(PaperPlayerDataPersistence { saved ->
            lootReceipt(saved, receiptKey) shouldBe true
            saved.inventory.getItem(0) shouldBe stack
            saves++
        })
        loot.store.write(pending)
        loot.claim(player, pending.id) shouldBe true
        saves shouldBe 1
        player.inventory.getItem(0) shouldBe stack
        loot.claim(player, pending.id) shouldBe false
        LostLootStore(root).get(pending.id)?.state shouldBe LostLootState.CLAIMED
        LostLootStore(root).get(pending.id)?.item shouldBe null
        loot.close()
    }

    "foreign players and full inventories cannot consume a stored reward" {
        val player = paper.addPlayer("owner")
        val foreign = paper.addPlayer("foreign")
        val loot = service()
        val pending = record(player, item(player))
        loot.store.write(pending)
        loot.claim(foreign, pending.id) shouldBe false
        for (slot in 0..35) player.inventory.setItem(slot, ItemStack.of(Material.STONE, 64))
        loot.claim(player, pending.id) shouldBe false
        LostLootStore(root).get(pending.id) shouldBe pending
        loot.close()
    }

    "a player save failure retains an unresolved claim and cannot issue a second item" {
        val player = paper.addPlayer("save-failure")
        val loot = service(PaperPlayerDataPersistence { error("player save failed") })
        val pending = record(player, item(player))
        loot.store.write(pending)
        loot.claim(player, pending.id) shouldBe false
        LostLootStore(root).get(pending.id)?.state shouldBe LostLootState.CLAIMING
        loot.claim(player, pending.id) shouldBe false
        player.inventory.storageContents.filterNotNull().size shouldBe 1
        loot.close()
        // Simulate an authoritative loaded player receipt proving the first delivery survived.
        val reopened = service()
        reopened.recoverClaims(player)
        reopened.store.get(pending.id)?.state shouldBe LostLootState.CLAIMED
        player.inventory.storageContents.filterNotNull().size shouldBe 1
        reopened.close()
    }

    "an ambiguous claim without a receipt is retained without blind redelivery" {
        val player = paper.addPlayer("ambiguous")
        val loot = service()
        val pending = record(player, item(player)).copy(state = LostLootState.CLAIMING, claim = UUID.randomUUID().toString())
        loot.store.write(pending)
        loot.recoverClaims(player)
        loot.claim(player, pending.id) shouldBe false
        player.inventory.storageContents.filterNotNull().size shouldBe 0
        LostLootStore(root).get(pending.id) shouldBe pending
        loot.close()
    }

    "failed journal commit keeps the entity frozen and unavailable until a verified retry" {
        val player = paper.addPlayer("disk-failure")
        val loot = service()
        val entity = mockk<Item>(relaxed = true)
        val id = UUID.randomUUID()
        every { entity.uniqueId } returns id
        every { entity.isValid } returns true
        every { entity.thrower } returns null
        every { entity.itemStack } returns item(player)
        every { entity.persistentDataContainer } returns player.persistentDataContainer
        val obstruction = root.resolve("data/lost-elite-loot/$id.json")
        Files.createDirectory(obstruction)
        val event = org.bukkit.event.entity.ItemDespawnEvent(entity, player.location)
        loot.despawn(event)
        event.isCancelled shouldBe true
        loot.capture(entity)
        verify(exactly = 0) { entity.remove() }
        verify { entity.setCanPlayerPickup(false); entity.setCanMobPickup(false); entity.setWillAge(false) }
        loot.store.available(requireNotNull(loot.store.get(id.toString()))) shouldBe false
        loot.claim(player, id.toString()) shouldBe false
        Files.delete(obstruction)
        loot.capture(entity)
        verify(exactly = 1) { entity.remove() }
        loot.store.available(requireNotNull(loot.store.get(id.toString()))) shouldBe true
        loot.close()
    }

    "world unload captures remaining mob loot before an instance can be deleted" {
        val player = paper.addPlayer("unload-owner")
        val loot = service()
        val entity = mockk<Item>(relaxed = true)
        val world = mockk<org.bukkit.World>()
        val id = UUID.randomUUID()
        var valid = true
        every { entity.uniqueId } returns id
        every { entity.isValid } answers { valid }
        every { entity.thrower } returns null
        every { entity.itemStack } returns item(player)
        every { entity.persistentDataContainer } returns player.persistentDataContainer
        every { entity.remove() } answers { valid = false }
        every { world.getEntitiesByClass(Item::class.java) } returns listOf(entity)
        val event = org.bukkit.event.world.WorldUnloadEvent(world)
        loot.worldUnloading(event)
        event.isCancelled shouldBe false
        valid shouldBe false
        LostLootStore(root).get(id.toString())?.state shouldBe LostLootState.STORED
        loot.close()
    }

    "capture commits the exact stack before removing its physical entity" {
        val player = paper.addPlayer("drop-owner")
        val loot = service()
        val stack = item(player)
        val entity = mockk<Item>(relaxed = true)
        val id = UUID.randomUUID()
        every { entity.uniqueId } returns id
        every { entity.isValid } returns true
        every { entity.thrower } returns null
        every { entity.itemStack } returns stack
        every { entity.persistentDataContainer } returns player.persistentDataContainer
        every { entity.remove() } answers {
            val saved = requireNotNull(LostLootStore(root).get(id.toString()))
            ItemStack.deserializeBytes(Base64.getDecoder().decode(saved.item)) shouldBe stack
        }
        loot.capture(entity)
        verify(exactly = 1) { entity.remove() }
        loot.close()
    }
})

private fun lootReceipt(player: Player, key: NamespacedKey) = player.persistentDataContainer.has(key, PersistentDataType.STRING)
