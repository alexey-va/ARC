package ru.arc.listeners

import de.tr7zw.changeme.nbtapi.iface.ReadableNBT
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.papermc.paper.event.player.PlayerPickBlockEvent
import io.papermc.paper.event.player.PlayerPickEntityEvent
import org.bukkit.GameMode
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.block.Container
import org.bukkit.entity.ArmorStand
import org.bukkit.entity.Item
import org.bukkit.entity.ItemFrame
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.event.block.Action
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.inventory.ClickType
import org.bukkit.event.inventory.InventoryAction
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryCreativeEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.event.inventory.InventoryType
import org.bukkit.event.player.PlayerArmorStandManipulateEvent
import org.bukkit.event.player.PlayerDropItemEvent
import org.bukkit.event.player.PlayerInteractAtEntityEvent
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryView
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.BlockStateMeta
import org.bukkit.inventory.meta.BundleMeta
import org.bukkit.inventory.meta.CrossbowMeta
import org.bukkit.inventory.meta.ItemMeta
import org.bukkit.persistence.PersistentDataType
import org.bukkit.util.Vector
import ru.arc.itemcatalog.PhysicalRewardVoucher
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.arc.paper.testing.failOnUnsupportedMockBukkitOperation
import ru.arc.util.withCustomModelData

class CreativeRewardItemGuardTest : StringSpec({
    "raw reward and pouch markers are protected by presence even without a valid value" {
        for (tag in listOf("arc:treasure_key", "arc:pouch_id")) {
            val data = mockk<ReadableNBT>()
            every { data.hasTag(any()) } answers { firstArg<String>() == tag }
            CreativeRewardItemGuard.hasRewardCarrierTag(data) shouldBe true
        }
        val ordinary = mockk<ReadableNBT>()
        every { ordinary.hasTag(any()) } returns false
        CreativeRewardItemGuard.hasRewardCarrierTag(ordinary) shouldBe false
    }

    "legacy money cannot evade protection by renaming while ordinary decor remains usable" {
        withGuard { _, _, _ ->
            for (model in 11135..11138) {
                isProtectedFixture(ItemStack(Material.STICK).withCustomModelData(model)) shouldBe true
            }
            isProtectedFixture(ItemStack(Material.STICK).withCustomModelData(11103)) shouldBe false
            isProtectedFixture(ItemStack(Material.IRON_INGOT).withCustomModelData(12034)) shouldBe false
            isProtectedFixture(ItemStack(Material.STONE)) shouldBe false
            isProtectedFixture(null) shouldBe false
        }
    }

    "partial or malformed physical voucher PDC markers cannot be admitted by creative" {
        withGuard { _, _, _ ->
            for (key in listOf(
                PhysicalRewardVoucher.idKey,
                PhysicalRewardVoucher.versionKey,
                PhysicalRewardVoucher.sourceKey,
                PhysicalRewardVoucher.fingerprintKey,
            )) {
                val forged = ItemStack(Material.PAPER).apply {
                    editMeta { it.persistentDataContainer.set(key, PersistentDataType.INTEGER, 7) }
                }
                PhysicalRewardVoucher.identity(forged) shouldBe null
                CreativeRewardItemGuard.containsProtectedItems(sequenceOf(forged), { false }, { false }) shouldBe true
            }
        }
    }

    "one detector finds reward items inside bundles shulkers use remainders and charged crossbows" {
        withGuard { _, _, _ ->
            val reward = marked()
            val bundle = ItemStack(Material.BUNDLE).apply {
                editMeta(BundleMeta::class.java) { it.setItems(listOf(reward)) }
            }
            isProtectedFixture(bundle) shouldBe true
            val container = mockk<Container>()
            val inventory = mockk<Inventory>()
            every { container.snapshotInventory } returns inventory
            every { inventory.contents } returns arrayOf(null, bundle)
            val shulkerMeta = mockk<BlockStateMeta>(relaxed = true)
            every { shulkerMeta.hasBlockState() } returns true
            every { shulkerMeta.blockState } returns container
            isProtectedFixture(itemWithMeta(Material.SHULKER_BOX, shulkerMeta)) shouldBe true

            val remainderMeta = mockk<ItemMeta>(relaxed = true)
            every { remainderMeta.hasUseRemainder() } returns true
            every { remainderMeta.useRemainder } returns reward
            isProtectedFixture(itemWithMeta(Material.APPLE, remainderMeta)) shouldBe true
            val crossbowMeta = mockk<CrossbowMeta>(relaxed = true)
            every { crossbowMeta.chargedProjectiles } returns listOf(reward)
            isProtectedFixture(itemWithMeta(Material.CROSSBOW, crossbowMeta)) shouldBe true
            reward.amount shouldBe 1
            (bundle.itemMeta as BundleMeta).items.single() shouldBe reward
        }
    }

    "unreadable deep or oversized inputs fail closed with one budget across all roots" {
        withGuard { _, _, _ ->
            var nested: ItemStack = ItemStack(Material.STONE)
            repeat(CreativeRewardItemGuard.MAX_DEPTH + 1) {
                val child = nested
                val meta = mockk<BundleMeta>(relaxed = true)
                every { meta.items } returns listOf(child)
                nested = itemWithMeta(Material.BUNDLE, meta)
            }
            isProtectedFixture(nested) shouldBe true
            CreativeRewardItemGuard.containsProtectedItems(
                generateSequence { ItemStack(Material.STONE) }.take(CreativeRewardItemGuard.MAX_ITEM_VISITS + 1),
                ::fixtureMarker,
                { false },
            ) shouldBe true
            CreativeRewardItemGuard.containsProtectedItems(sequenceOf(ItemStack(Material.STONE)), {
                throw IllegalArgumentException("Unreadable custom data")
            }, { false }) shouldBe true
            val meta = mockk<BlockStateMeta>(relaxed = true)
            every { meta.hasBlockState() } returns true
            every { meta.blockState } throws IllegalArgumentException("Unreadable block state")
            isProtectedFixture(itemWithMeta(Material.SHULKER_BOX, meta)) shouldBe true
        }
    }

    "client proposed sixty-four copies and outside creative drops never replace the original server item" {
        withGuard { _, player, guard ->
            val original = marked()
            player.inventory.setItem(0, original.clone())
            for (slot in listOf(36, -999)) {
                val proposed = original.clone().apply { amount = 64 }
                val event = InventoryCreativeEvent(view(player), InventoryType.SlotType.QUICKBAR, slot, proposed)
                guard.onCreative(event)
                event.isCancelled shouldBe true
                event.cursor shouldBe proposed
                player.inventory.getItem(0) shouldBe original
                player.inventory.storageContents.filterNotNull().sumOf(ItemStack::getAmount) shouldBe 1
            }
        }
    }

    "survival and ordinary creative item proposals remain unchanged" {
        withGuard { _, player, guard ->
            player.gameMode = GameMode.SURVIVAL
            val survival = InventoryCreativeEvent(view(player), InventoryType.SlotType.QUICKBAR, 36, marked(64))
            guard.onCreative(survival)
            survival.isCancelled shouldBe false
            player.gameMode = GameMode.CREATIVE
            val ordinary = InventoryCreativeEvent(view(player), InventoryType.SlotType.QUICKBAR, 36, ItemStack(Material.STONE, 64))
            guard.onCreative(ordinary)
            ordinary.isCancelled shouldBe false
        }
    }

    "replacing a protected server original with a plain creative proposal preserves that original" {
        withGuard { _, player, guard ->
            val original = marked()
            player.inventory.setItem(0, original.clone())
            val event = InventoryCreativeEvent(view(player), InventoryType.SlotType.QUICKBAR, 36, ItemStack(Material.STONE))
            guard.onCreative(event)
            event.isCancelled shouldBe true
            event.cursor.type shouldBe Material.STONE
            player.inventory.getItem(0) shouldBe original
            player.inventory.storageContents.filterNotNull().sumOf(ItemStack::getAmount) shouldBe 1
        }
    }

    "creative clone normal movement number keys and offhand swaps cannot carry protected items" {
        withGuard { _, player, guard ->
            player.inventory.setItem(0, marked())
            for ((click, action) in listOf(
                ClickType.MIDDLE to InventoryAction.CLONE_STACK,
                ClickType.LEFT to InventoryAction.PICKUP_ALL,
                ClickType.SHIFT_LEFT to InventoryAction.MOVE_TO_OTHER_INVENTORY,
            )) {
                val event = InventoryClickEvent(view(player), InventoryType.SlotType.QUICKBAR, 36, click, action)
                guard.onInventoryClick(event)
                event.isCancelled shouldBe true
                player.inventory.getItem(0)?.amount shouldBe 1
            }
            player.inventory.setItem(0, ItemStack(Material.STONE))
            player.inventory.setItem(2, marked())
            val number = InventoryClickEvent(view(player), InventoryType.SlotType.QUICKBAR, 36,
                ClickType.NUMBER_KEY, InventoryAction.HOTBAR_SWAP, 2)
            guard.onInventoryClick(number)
            number.isCancelled shouldBe true
            player.inventory.setItemInOffHand(marked())
            val offhand = InventoryClickEvent(view(player), InventoryType.SlotType.QUICKBAR, 36,
                ClickType.SWAP_OFFHAND, InventoryAction.HOTBAR_SWAP)
            guard.onInventoryClick(offhand)
            offhand.isCancelled shouldBe true
        }
    }

    "drag checks both the old cursor and proposed destination stacks" {
        withGuard { _, player, guard ->
            for ((cursor, destination) in listOf(
                marked(64) to ItemStack(Material.STONE),
                ItemStack(Material.STONE) to marked(64),
            )) {
                val event = InventoryDragEvent(view(player), ItemStack(Material.AIR), cursor, false, mapOf(36 to destination))
                guard.onInventoryDrag(event)
                event.isCancelled shouldBe true
                event.oldCursor shouldBe cursor
            }
        }
    }

    "creative block placement and dropped items are denied while survival keeps its normal routes" {
        withGuard { _, player, guard ->
            val reward = marked()
            val place = mockk<BlockPlaceEvent>(relaxed = true)
            every { place.player } returns player
            every { place.itemInHand } returns reward
            val dropped = mockk<Item>()
            every { dropped.itemStack } returns reward
            val drop = PlayerDropItemEvent(player, dropped)
            guard.onBlockPlace(place)
            guard.onDrop(drop)
            verify(exactly = 1) { place.isCancelled = true }
            drop.isCancelled shouldBe true
            reward.amount shouldBe 1
            player.gameMode = GameMode.SURVIVAL
            val survivalDrop = PlayerDropItemEvent(player, dropped)
            guard.onDrop(survivalDrop)
            survivalDrop.isCancelled shouldBe false
        }
    }

    "world use checks both server hands and item-frame or armor-stand source items" {
        withGuard { _, player, guard ->
            player.inventory.setItemInMainHand(ItemStack(Material.STONE))
            player.inventory.setItemInOffHand(marked())
            val use = PlayerInteractEvent(player, Action.RIGHT_CLICK_AIR, ItemStack(Material.STONE), null,
                BlockFace.SELF, EquipmentSlot.HAND)
            guard.onInteract(use)
            use.isCancelled shouldBe true
            player.inventory.setItemInOffHand(ItemStack(Material.AIR))
            val frame = mockk<ItemFrame>()
            every { frame.item } returns marked()
            val frameClick = PlayerInteractEntityEvent(player, frame, EquipmentSlot.HAND)
            guard.onEntityInteract(frameClick)
            frameClick.isCancelled shouldBe true
            val atFrame = PlayerInteractAtEntityEvent(player, frame, Vector(), EquipmentSlot.HAND)
            guard.onInteractAtEntity(atFrame)
            atFrame.isCancelled shouldBe true
            val stand = mockk<ArmorStand>()
            val equip = PlayerArmorStandManipulateEvent(player, stand, ItemStack(Material.AIR), marked(),
                EquipmentSlot.HEAD, EquipmentSlot.HAND)
            guard.onArmorStandManipulate(equip)
            equip.isCancelled shouldBe true
        }
    }

    "native pick includes block snapshots item frames and entity equipment without modifying them" {
        withGuard { _, player, guard ->
            val reward = marked()
            val inventory = mockk<Inventory>()
            every { inventory.contents } returns arrayOf(reward)
            val container = mockk<Container>()
            every { container.snapshotInventory } returns inventory
            val block = mockk<Block>()
            every { block.state } returns container
            val pickBlock = mockk<PlayerPickBlockEvent>(relaxed = true)
            every { pickBlock.player } returns player
            every { pickBlock.isIncludeData } returns true
            every { pickBlock.block } returns block
            guard.onPick(pickBlock)
            verify(exactly = 1) { pickBlock.isCancelled = true }

            val frame = mockk<ItemFrame>(relaxed = true)
            every { frame.pickItemStack } returns ItemStack(Material.ITEM_FRAME)
            every { frame.item } returns reward
            val pickFrame = mockk<PlayerPickEntityEvent>(relaxed = true)
            every { pickFrame.player } returns player
            every { pickFrame.entity } returns frame
            guard.onPick(pickFrame)
            verify(exactly = 1) { pickFrame.isCancelled = true }

            val living = mockk<LivingEntity>(relaxed = true)
            every { living.pickItemStack } returns ItemStack(Material.STONE)
            every { living.equipment!!.itemInMainHand } returns ItemStack(Material.AIR)
            every { living.equipment!!.itemInOffHand } returns ItemStack(Material.AIR)
            every { living.equipment!!.armorContents } returns arrayOf(reward)
            val pickEquipped = mockk<PlayerPickEntityEvent>(relaxed = true)
            every { pickEquipped.player } returns player
            every { pickEquipped.entity } returns living
            every { pickEquipped.isIncludeData } returns true
            guard.onPick(pickEquipped)
            verify(exactly = 1) { pickEquipped.isCancelled = true }
            reward.amount shouldBe 1
        }
    }

    "native pick sends non-container block entity snapshots through the same classifier" {
        withGuard { runtime, player, _ ->
            val block = runtime.server.addSimpleWorld("opaque-pick").getBlockAt(0, 64, 0)
            block.type = Material.SPAWNER
            val inspected = mutableListOf<Material>()
            val guard = CreativeRewardItemGuard { items ->
                val snapshots = items.filterNotNull().toList()
                inspected += snapshots.map { it.type }
                snapshots.any { it.type == Material.SPAWNER }
            }
            val pick = mockk<PlayerPickBlockEvent>(relaxed = true)
            every { pick.player } returns player
            every { pick.isIncludeData } returns true
            every { pick.block } returns block
            guard.onPick(pick)
            verify(exactly = 1) { pick.isCancelled = true }
            inspected shouldBe listOf(Material.SPAWNER)
            block.type shouldBe Material.SPAWNER
        }
    }
})

// NBT reads/snapshots are injected: MockBukkit does not provide the NBT API's native item bridge.
private val fixtureKey = NamespacedKey("test", "creative_reward")
private fun fixtureMarker(item: ItemStack): Boolean = item.itemMeta?.persistentDataContainer
    ?.has(fixtureKey, PersistentDataType.BYTE) == true
private fun isProtectedFixture(item: ItemStack?): Boolean =
    CreativeRewardItemGuard.containsProtectedItems(sequenceOf(item), ::fixtureMarker) { false }

private fun marked(amount: Int = 1) = ItemStack(Material.PAPER, amount).apply {
    editMeta { it.persistentDataContainer.set(fixtureKey, PersistentDataType.BYTE, 1.toByte()) }
}

private fun withGuard(body: (MockBukkitTestRuntime, Player, CreativeRewardItemGuard) -> Unit) {
    failOnUnsupportedMockBukkitOperation {
        MockBukkitTestRuntime.open().use { runtime ->
            val player = runtime.addPlayer("CreativeRewards")
            player.gameMode = GameMode.CREATIVE
            body(runtime, player, CreativeRewardItemGuard {
                CreativeRewardItemGuard.containsProtectedItems(it, ::fixtureMarker) { false }
            })
        }
    }
}

private fun itemWithMeta(material: Material, meta: ItemMeta): ItemStack = mockk {
    every { type } returns material
    every { itemMeta } returns meta
}

// Only the view conversion boundary is mocked; cancellation is the real Bukkit event's state.
private fun view(actor: Player): InventoryView = mockk {
    every { player } returns actor
    every { convertSlot(any()) } answers { firstArg<Int>().let { if (it == 36) 0 else it } }
    every { getItem(36) } answers { actor.inventory.getItem(0) }
    every { cursor } answers { actor.itemOnCursor }
}
