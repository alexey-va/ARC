package ru.arc.hooks.economyshop

import dev.lone.itemsadder.api.CustomFurniture
import dev.lone.itemsadder.api.Events.FurnitureInteractEvent
import org.bukkit.Bukkit
import org.bukkit.block.Block
import org.bukkit.entity.Entity
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.player.PlayerInteractAtEntityEvent
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.EquipmentSlot
import ru.arc.ARC
import java.util.LinkedHashMap
import java.util.LinkedHashSet
import java.util.Locale
import java.util.UUID

internal const val FURNITURE_GALLERY_WORLD = "rc_atelier_furniture_gallery"

/** Routes only current catalog furniture clicks in the one authored gallery world. */
internal class FurnitureGalleryPurchaseListener(
    private val purchases: ShopPurchaseService,
    private val gallery: FurnitureGalleryInteractionRuntime,
) : Listener {
    private val deduplicator = FurnitureGalleryClickDeduplicator()
    private val loggedUnavailableItems = LinkedHashSet<String>()

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onEntityInteract(event: PlayerInteractEntityEvent) {
        // Some Paper builds share the base handler list with INTERACT_AT.
        if (event is PlayerInteractAtEntityEvent) return
        if (!FurnitureGalleryInteractionPolicy.accepts(
                worldName = event.player.world.name,
                rightClick = true,
                mainHand = event.hand == EquipmentSlot.HAND,
            )
        ) return

        gallery.targetForMarker(event.rightClicked)?.let { target ->
            handleFurnitureClick(
                player = event.player,
                furnitureId = target.furnitureId,
                targetIdentity = target.key,
                tick = Bukkit.getCurrentTick(),
                cancel = { event.isCancelled = true },
            )
            return
        }
        val furniture = furniture(event.rightClicked) ?: return
        val target = gallery.targetForFurniture(furniture.namespacedID, furniture.entity ?: event.rightClicked) ?: return
        handleFurnitureClick(
            player = event.player,
            furnitureId = target.furnitureId,
            targetIdentity = target.key,
            tick = Bukkit.getCurrentTick(),
            cancel = { event.isCancelled = true },
        )
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onEntityInteractAt(event: PlayerInteractAtEntityEvent) {
        if (!FurnitureGalleryInteractionPolicy.accepts(
                worldName = event.player.world.name,
                rightClick = true,
                mainHand = event.hand == EquipmentSlot.HAND,
            )
        ) return

        gallery.targetForMarker(event.rightClicked)?.let { target ->
            handleFurnitureClick(
                player = event.player,
                furnitureId = target.furnitureId,
                targetIdentity = target.key,
                tick = Bukkit.getCurrentTick(),
                cancel = { event.isCancelled = true },
            )
            return
        }
        val furniture = furniture(event.rightClicked) ?: return
        val target = gallery.targetForFurniture(furniture.namespacedID, furniture.entity ?: event.rightClicked) ?: return
        handleFurnitureClick(
            player = event.player,
            furnitureId = target.furnitureId,
            targetIdentity = target.key,
            tick = Bukkit.getCurrentTick(),
            cancel = { event.isCancelled = true },
        )
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onBlockInteract(event: PlayerInteractEvent) {
        val block = event.clickedBlock ?: return
        if (!FurnitureGalleryInteractionPolicy.accepts(
                worldName = event.player.world.name,
                rightClick = event.action == Action.RIGHT_CLICK_BLOCK,
                mainHand = event.hand == EquipmentSlot.HAND,
            )
        ) return

        val furniture = furniture(block) ?: return
        val target = gallery.targetForFurniture(furniture.namespacedID, furniture.entity) ?: return
        handleFurnitureClick(
            player = event.player,
            furnitureId = target.furnitureId,
            targetIdentity = target.key,
            tick = Bukkit.getCurrentTick(),
            cancel = { event.isCancelled = true },
        )
    }

    /**
     * ItemsAdder 4.0.18 fires this cancellable event before running the furniture
     * interaction callback that can mount, rotate, or remove the displayed piece.
     * Its Bukkit interaction listener runs at MONITOR, so cancelling this nested
     * event at HIGHEST is the last safe read-side interception point.
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    fun onItemsAdderFurnitureInteract(event: FurnitureInteractEvent) {
        val furniture = runCatching { event.furniture }.getOrNull() ?: return
        if (event.player.world.name != FURNITURE_GALLERY_WORLD) return
        val entity = runCatching { furniture.entity }.getOrNull()
            ?: runCatching { event.bukkitEntity }.getOrNull()
        val target = gallery.targetForFurniture(
            runCatching { furniture.namespacedID }.getOrNull(),
            entity,
        ) ?: return
        handleFurnitureClick(
            player = event.player,
            furnitureId = target.furnitureId,
            targetIdentity = target.key,
            tick = Bukkit.getCurrentTick(),
            cancel = { event.isCancelled = true },
        )
    }

    private fun handleFurnitureClick(
        player: Player,
        furnitureId: String?,
        targetIdentity: String,
        tick: Int,
        cancel: () -> Unit,
    ) {
        val id = furnitureId?.trim()?.takeIf(String::isNotEmpty) ?: return
        // Only configured gallery targets reach this method. Keep ItemsAdder's
        // native seating/removal callback blocked even when ESG has no current offer.
        cancel()
        val key = FurnitureGalleryClickKey(player.uniqueId, id.lowercase(Locale.ROOT), targetIdentity)
        if (!deduplicator.first(key, tick)) return
        if (!purchases.hasFurniturePurchaseMenu(id)) {
            logUnavailable(id)
            return
        }

        when (purchases.openFurniturePurchaseMenu(player, id)) {
            FurnitureShopMenuOpenResult.OPENED,
            FurnitureShopMenuOpenResult.NOT_LISTED,
            FurnitureShopMenuOpenResult.NO_PERMISSION,
            FurnitureShopMenuOpenResult.REQUIREMENTS_NOT_MET,
            -> Unit

            FurnitureShopMenuOpenResult.AMBIGUOUS_OFFER,
            FurnitureShopMenuOpenResult.SHOP_UNAVAILABLE,
            FurnitureShopMenuOpenResult.FAILED,
            -> logUnavailable(id)
        }
    }

    private fun furniture(entity: Entity) =
        runCatching { CustomFurniture.byAlreadySpawned(entity) }.getOrNull()

    private fun furniture(block: Block) =
        runCatching { CustomFurniture.byAlreadySpawned(block) }.getOrNull()

    private fun logUnavailable(furnitureId: String) {
        val key = furnitureId.take(256)
        if (!loggedUnavailableItems.add(key)) return
        if (loggedUnavailableItems.size > MAX_LOGGED_ITEMS) {
            val oldest = loggedUnavailableItems.firstOrNull() ?: return
            loggedUnavailableItems.remove(oldest)
        }
        ARC.instance.logger.warning("Gallery furniture purchase menu unavailable for $key; purchase lookup failed closed")
    }

    private companion object {
        const val MAX_LOGGED_ITEMS = 64
    }
}

internal object FurnitureGalleryInteractionPolicy {
    fun accepts(worldName: String, rightClick: Boolean, mainHand: Boolean): Boolean =
        worldName == FURNITURE_GALLERY_WORLD && rightClick && mainHand
}

internal data class FurnitureGalleryClickKey(
    val playerId: UUID,
    val furnitureId: String,
    val targetIdentity: String,
)

/** Suppresses paired Bukkit click routes for the same player and furniture within one server tick. */
internal class FurnitureGalleryClickDeduplicator(
    private val capacity: Int = 512,
) {
    private val seen = LinkedHashMap<FurnitureGalleryClickKey, Int>()

    init {
        require(capacity > 0) { "Gallery click dedupe bound must be positive" }
    }

    fun first(key: FurnitureGalleryClickKey, tick: Int): Boolean {
        val iterator = seen.entries.iterator()
        while (iterator.hasNext()) {
            if (iterator.next().value != tick) iterator.remove()
        }
        if (seen.containsKey(key)) return false
        seen[key] = tick
        while (seen.size > capacity) {
            val oldest = seen.entries.iterator()
            if (oldest.hasNext()) {
                oldest.next()
                oldest.remove()
            }
        }
        return true
    }
}
