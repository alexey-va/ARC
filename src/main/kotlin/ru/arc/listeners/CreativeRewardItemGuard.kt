package ru.arc.listeners

import de.tr7zw.changeme.nbtapi.NBT
import de.tr7zw.changeme.nbtapi.iface.ReadableNBT
import io.papermc.paper.event.player.PlayerPickBlockEvent
import io.papermc.paper.event.player.PlayerPickEntityEvent
import io.papermc.paper.event.player.PlayerPickItemEvent
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.GameMode
import org.bukkit.Material
import org.bukkit.block.Container
import org.bukkit.block.TileState
import org.bukkit.entity.ItemFrame
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.event.Cancellable
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.inventory.ClickType
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryCreativeEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.event.player.PlayerArmorStandManipulateEvent
import org.bukkit.event.player.PlayerDropItemEvent
import org.bukkit.event.player.PlayerInteractAtEntityEvent
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.InventoryHolder
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.BlockStateMeta
import org.bukkit.inventory.meta.BundleMeta
import org.bukkit.inventory.meta.CrossbowMeta
import ru.arc.itemcatalog.PhysicalRewardVoucher
import ru.arc.util.customModelDataOrNull

/** Creative may propose arbitrary stacks; cancellation preserves the server's existing items. */
class CreativeRewardItemGuard(
    private val protectedItems: (Sequence<ItemStack?>) -> Boolean = { containsProtectedItems(it) },
) : Listener {
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    fun onCreative(event: InventoryCreativeEvent) {
        denyIfProtected(event, event.whoClicked as? Player) {
            sequence {
                yield(event.cursor)
                if (event.rawSlot >= 0) yield(event.currentItem)
            }
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    fun onInventoryClick(event: InventoryClickEvent) {
        if (event is InventoryCreativeEvent) return // Its cursor is the proposed replacement, not a server cursor.
        val player = event.whoClicked as? Player ?: return
        denyIfProtected(event, player) {
            sequence {
                yield(event.currentItem)
                yield(event.cursor)
                if (event.click == ClickType.NUMBER_KEY && event.hotbarButton in 0..8) {
                    yield(player.inventory.getItem(event.hotbarButton))
                }
                if (event.click == ClickType.SWAP_OFFHAND) yield(player.inventory.itemInOffHand)
            }
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    fun onInventoryDrag(event: InventoryDragEvent) {
        denyIfProtected(event, event.whoClicked as? Player) {
            sequenceOf(event.oldCursor) + event.newItems.values.asSequence()
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    fun onBlockPlace(event: BlockPlaceEvent) {
        denyIfProtected(event, event.player) { sequenceOf(event.itemInHand) }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    fun onInteract(event: PlayerInteractEvent) {
        denyIfProtected(event, event.player) { heldItems(event.player) }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    fun onEntityInteract(event: PlayerInteractEntityEvent) = denyEntityInteraction(event)

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    fun onInteractAtEntity(event: PlayerInteractAtEntityEvent) = denyEntityInteraction(event)

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    fun onArmorStandManipulate(event: PlayerArmorStandManipulateEvent) {
        denyIfProtected(event, event.player) {
            heldItems(event.player) + sequenceOf(event.playerItem, event.armorStandItem)
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    fun onDrop(event: PlayerDropItemEvent) {
        denyIfProtected(event, event.player) { sequenceOf(event.itemDrop.itemStack) }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    fun onPick(event: PlayerPickItemEvent) {
        denyIfProtected(event, event.player) {
            when (event) {
                is PlayerPickBlockEvent -> if (event.isIncludeData) sequence {
                    val state = event.block.state
                    if (state is Container) yieldAll(state.snapshotInventory.contents.asSequence())
                    // Lecterns, campfires and spawners also carry item/entity data, without Container.
                    if (state is TileState) {
                        val snapshot = ItemStack(state.type)
                        val meta = snapshot.itemMeta as? BlockStateMeta
                            ?: error("Cannot inspect the picked block entity ${state.type}")
                        meta.blockState = state
                        snapshot.itemMeta = meta
                        yield(snapshot)
                    }
                } else emptySequence()
                is PlayerPickEntityEvent -> sequence {
                    val entity = event.entity
                    yield(entity.pickItemStack)
                    if (entity is ItemFrame) yield(entity.item)
                    if (event.isIncludeData) {
                        if (entity is InventoryHolder) yieldAll(entity.inventory.contents.asSequence())
                        if (entity is LivingEntity) yieldAll(equipmentItems(entity))
                    }
                }
                else -> emptySequence()
            }
        }
    }

    private fun denyEntityInteraction(event: PlayerInteractEntityEvent) {
        denyIfProtected(event, event.player) {
            heldItems(event.player) + if (event.rightClicked is ItemFrame) {
                sequenceOf((event.rightClicked as ItemFrame).item)
            } else emptySequence()
        }
    }

    private fun denyIfProtected(event: Cancellable, player: Player?, items: () -> Sequence<ItemStack?>) {
        if (player == null || player.gameMode != GameMode.CREATIVE) return
        if (!runCatching { protectedItems(items()) }.getOrDefault(true)) return
        val alreadyCancelled = event.isCancelled
        event.isCancelled = true
        if (!alreadyCancelled) deny(player)
    }

    private fun heldItems(player: Player): Sequence<ItemStack?> =
        sequenceOf(player.inventory.itemInMainHand, player.inventory.itemInOffHand)

    private fun equipmentItems(entity: LivingEntity): Sequence<ItemStack?> = entity.equipment?.let {
        sequenceOf(it.itemInMainHand, it.itemInOffHand) + it.armorContents.asSequence()
    } ?: emptySequence()

    companion object {
        internal const val MAX_DEPTH = 8
        internal const val MAX_ITEM_VISITS = 256
        private val voucherKeys = listOf(
            PhysicalRewardVoucher.idKey,
            PhysicalRewardVoucher.versionKey,
            PhysicalRewardVoucher.sourceKey,
            PhysicalRewardVoucher.fingerprintKey,
        )
        private val deniedMessage = Component.text(
            "Денежные предметы и награды нельзя копировать или использовать в творческом режиме. Переключитесь в выживание.",
            NamedTextColor.RED,
        )

        fun deny(player: Player) = player.sendMessage(deniedMessage)

        fun isProtected(stack: ItemStack?): Boolean = containsProtectedItems(sequenceOf(stack))

        internal fun hasRewardCarrierTag(data: ReadableNBT): Boolean =
            data.hasTag("arc:treasure_key") || data.hasTag("arc:pouch_id")

        /** One budget covers every source root and all nested item slots; unreadable input is denied. */
        internal fun containsProtectedItems(
            items: Sequence<ItemStack?>,
            carrierMarker: (ItemStack) -> Boolean = { stack -> NBT.get<Boolean>(stack) { hasRewardCarrierTag(it) } },
            opaqueComponents: (ItemStack) -> Boolean = CreativeRewardOpaqueComponents::isProtected,
        ): Boolean = runCatching {
            var remaining = MAX_ITEM_VISITS
            fun visit(item: ItemStack?, depth: Int): Boolean {
                if (--remaining < 0) return true
                if (item == null || item.type.isAir) return false
                if (depth > MAX_DEPTH) return true
                if (carrierMarker(item)) return true
                // Historical money can be normalized from appearance; renaming must not bypass this gate.
                if (item.type == Material.STICK && item.customModelDataOrNull in 11135..11138) return true
                if (opaqueComponents(item)) return true
                val meta = item.itemMeta ?: return false
                if (voucherKeys.any { meta.persistentDataContainer.has(it) }) return true
                if (meta.hasUseRemainder() && visit(meta.useRemainder ?: return true, depth + 1)) return true
                return when (meta) {
                    is BundleMeta -> meta.items.any { visit(it, depth + 1) }
                    is BlockStateMeta -> if (meta.hasBlockState()) {
                        (meta.blockState as? Container)?.snapshotInventory?.contents?.any { visit(it, depth + 1) } == true
                    } else false
                    is CrossbowMeta -> meta.chargedProjectiles.any { visit(it, depth + 1) }
                    else -> false
                }
            }
            items.any { visit(it, 0) }
        }.getOrDefault(true)
    }
}
