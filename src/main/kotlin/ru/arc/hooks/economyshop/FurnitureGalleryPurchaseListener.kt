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

/** Routes exact current catalog furniture clicks in the gallery, with or without a geometry profile. */
internal class FurnitureGalleryPurchaseListener(
    private val purchases: ShopPurchaseService,
    private val gallery: FurnitureGalleryInteractionRuntime,
) : Listener {
    private val deduplicator = FurnitureGalleryClickDeduplicator()
    private val loggedUnavailableItems = LinkedHashSet<String>()

    /**
     * WorldGuard handles these events at NORMAL with ignoreCancelled=true. Mark
     * only exact, currently purchasable gallery furniture before that handler;
     * the HIGHEST route below still owns opening the native ESG menu.
     */
    @EventHandler(priority = EventPriority.LOWEST)
    fun onEntityInteractProtection(event: PlayerInteractEntityEvent) {
        if (event is PlayerInteractAtEntityEvent) return
        preCancelPurchasableEntityClick(
            worldName = event.player.world.name,
            hand = event.hand,
            entity = event.rightClicked,
            cancel = { event.isCancelled = true },
        )
    }

    @EventHandler(priority = EventPriority.LOWEST)
    fun onEntityInteractAtProtection(event: PlayerInteractAtEntityEvent) {
        preCancelPurchasableEntityClick(
            worldName = event.player.world.name,
            hand = event.hand,
            entity = event.rightClicked,
            cancel = { event.isCancelled = true },
        )
    }

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

        val target = entityClickTarget(event.rightClicked) ?: return
        handleFurnitureClick(
            player = event.player,
            furnitureId = target.furnitureId,
            targetIdentity = target.targetIdentity,
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

        val target = entityClickTarget(event.rightClicked) ?: return
        handleFurnitureClick(
            player = event.player,
            furnitureId = target.furnitureId,
            targetIdentity = target.targetIdentity,
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
        val id = furniture.namespacedID
        val targetIdentity = gallery.nativeFurnitureIdentity(id, furniture.entity) ?: return
        handleFurnitureClick(
            player = event.player,
            furnitureId = id,
            targetIdentity = targetIdentity,
            tick = Bukkit.getCurrentTick(),
            cancel = { event.isCancelled = true },
        )
    }

    /**
     * ItemsAdder fires this cancellable event before its seating/removal callback.
     * This native route deliberately does not require a geometry profile, so a
     * listed ID remains purchasable even when its model cannot get a precise box.
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    fun onItemsAdderFurnitureInteract(event: FurnitureInteractEvent) {
        val furniture = runCatching { event.furniture }.getOrNull() ?: return
        if (event.player.world.name != FURNITURE_GALLERY_WORLD) return
        val entity = exactItemsAdderFurnitureRoot(
            furnitureRoot = runCatching { furniture.entity }.getOrNull(),
            eventEntity = runCatching { event.bukkitEntity }.getOrNull(),
        )
        val id = runCatching { furniture.namespacedID }.getOrNull()
        val targetIdentity = gallery.nativeFurnitureIdentity(id, entity) ?: return
        handleFurnitureClick(
            player = event.player,
            furnitureId = id,
            targetIdentity = targetIdentity,
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
        if (!purchases.hasFurniturePurchaseMenu(id)) return
        // Only an exact current ESG furniture entry reaches this route. The
        // ItemsAdder callback is cancelled before it can seat/remove the root.
        cancel()
        val key = FurnitureGalleryClickKey(player.uniqueId, id.lowercase(Locale.ROOT), targetIdentity)
        if (!deduplicator.first(key, tick)) return

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

    private fun preCancelPurchasableEntityClick(
        worldName: String,
        hand: EquipmentSlot,
        entity: Entity,
        cancel: () -> Unit,
    ) {
        if (!FurnitureGalleryInteractionPolicy.accepts(
                worldName = worldName,
                rightClick = true,
                mainHand = hand == EquipmentSlot.HAND,
            )
        ) return

        val target = entityClickTarget(entity) ?: return
        if (!FurnitureGalleryInteractionPolicy.shouldPreCancelExactPurchaseClick(
                worldName = worldName,
                rightClick = true,
                mainHand = hand == EquipmentSlot.HAND,
                exactGalleryFurniture = true,
                hasCurrentPurchaseOffer = purchases.hasFurniturePurchaseMenu(target.furnitureId),
            )
        ) return

        // Never clear cancellation: this only suppresses WorldGuard's denial
        // for this exact gallery shop target, not region protection generally.
        cancel()
    }

    private fun entityClickTarget(entity: Entity): FurnitureGalleryEntityTarget? {
        gallery.targetForMarker(entity)?.let { target ->
            return FurnitureGalleryEntityTarget(target.furnitureId, target.targetKey)
        }
        val furniture = furniture(entity) ?: return null
        val id = furniture.namespacedID ?: return null
        val root = furniture.entity ?: entity
        val targetIdentity = gallery.nativeFurnitureIdentity(id, root) ?: return null
        return FurnitureGalleryEntityTarget(id, targetIdentity)
    }

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

private data class FurnitureGalleryEntityTarget(
    val furnitureId: String,
    val targetIdentity: String,
)

/** Prefer the API's exact furniture root over an event entity which may be a hitbox child. */
internal fun exactItemsAdderFurnitureRoot(furnitureRoot: Entity?, eventEntity: Entity?): Entity? =
    furnitureRoot ?: eventEntity

internal object FurnitureGalleryInteractionPolicy {
    fun accepts(worldName: String, rightClick: Boolean, mainHand: Boolean): Boolean =
        worldName == FURNITURE_GALLERY_WORLD && rightClick && mainHand

    fun shouldPreCancelExactPurchaseClick(
        worldName: String,
        rightClick: Boolean,
        mainHand: Boolean,
        exactGalleryFurniture: Boolean,
        hasCurrentPurchaseOffer: Boolean,
    ): Boolean =
        accepts(worldName, rightClick, mainHand) && exactGalleryFurniture && hasCurrentPurchaseOffer
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
